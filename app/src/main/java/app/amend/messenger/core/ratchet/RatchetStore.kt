package app.amend.messenger.core.ratchet

import app.amend.messenger.core.json.Json
import app.amend.messenger.core.json.long
import app.amend.messenger.core.json.str
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.io.File
import java.util.Base64
import java.util.UUID

/** Encrypts the ratchet file. On a phone: a non-exportable Android Keystore key; never the nsec. */
interface RatchetSealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * Everything libsignal keeps: this account's ratchet identity, this phone's prekeys, the
 * sessions with every phone we talk to, and the identities we trust (one per npub).
 *
 * libsignal changes it while it encrypts and decrypts. Nothing reaches the disk until
 * [commit]: the caller commits after the message has been saved, so a crash in between
 * leaves the old state and the re-delivered message can still be opened.
 */
class RatchetStore(private val file: File, private val sealer: RatchetSealer) : SignalProtocolStore {
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()
    private fun ByteArray.b64() = b64e.encodeToString(this)
    private fun String.unb64(): ByteArray = b64d.decode(this)

    /** Account ratchet identity (shared by linked phones) and when it was made. */
    @Volatile private var identity: IdentityKeyPair
    @Volatile var identityCreated: Long; private set
    @Volatile var registrationId: Int; private set
    @Volatile var deviceId: Int; private set

    private val preKeys = HashMap<Int, ByteArray>()
    /** One-time prekeys already used once, kept a while for a second sender racing on the same one. */
    private val usedPreKeys = HashMap<Int, Long>()
    private val signedPreKeys = HashMap<Int, ByteArray>()
    private val kyberPreKeys = HashMap<Int, ByteArray>()
    private val sessions = HashMap<String, ByteArray>()
    /** npub → trusted ratchet identity (public key bytes). */
    private val trusted = HashMap<String, ByteArray>()
    /** Free-form small values (pins, peer bundles, counters) kept by [Ratchet]. Read from several threads. */
    val extra = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile private var dirty = false
    /** True when this store was created just now (there was no file). */
    val fresh: Boolean
    /**
     * The file exists but couldn't be opened (the Keystore key failed or is gone). Nothing is
     * thrown away and nothing falls back to plain messages: the user is told and decides.
     */
    @Volatile var locked: Boolean = false
        private set

    init {
        val loaded = runCatching { if (file.exists()) Json.parseObject(String(sealer.open(file.readBytes()), Charsets.UTF_8)) else null }.getOrNull()
        if (loaded != null && loaded.str("ik") != null) {
            identity = IdentityKeyPair(loaded.str("ik")!!.unb64())
            identityCreated = loaded.long("ikt") ?: 0
            registrationId = (loaded.long("reg") ?: 1).toInt()
            deviceId = (loaded.long("dev") ?: 1).toInt()
            fun map(key: String, into: HashMap<Int, ByteArray>) =
                (loaded[key] as? Map<*, *>)?.forEach { (k, v) -> (k as? String)?.toIntOrNull()?.let { id -> (v as? String)?.let { into[id] = it.unb64() } } }
            map("pk", preKeys); map("spk", signedPreKeys); map("kpk", kyberPreKeys)
            (loaded["used"] as? Map<*, *>)?.forEach { (k, v) -> (k as? String)?.toIntOrNull()?.let { usedPreKeys[it] = (v as? Number)?.toLong() ?: 0 } }
            (loaded["s"] as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v is String) sessions[k] = v.unb64() }
            (loaded["t"] as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v is String) trusted[k] = v.unb64() }
            (loaded["x"] as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v is String) extra[k] = v }
            fresh = false
        } else {
            // No file yet: a new phone. A file that can't be opened locks the store instead (see
            // [locked]); a fresh identity is made in memory but never written over the old file.
            locked = file.exists()
            identity = IdentityKeyPair.generate()
            identityCreated = System.currentTimeMillis() / 1000
            registrationId = 1 + RatchetRandom.int(16380)
            deviceId = 1 + RatchetRandom.int(127)
            fresh = !locked
            dirty = !locked
        }
    }

    /** The user chose to start protection over after [locked]: the old file is kept aside. */
    @Synchronized
    fun startOverAfterLock() {
        if (!locked) return
        runCatching { file.renameTo(File(file.parentFile, file.name + ".unreadable-" + UUID.randomUUID())) }
        locked = false
        dirty = true
        commit()
    }

    // ------------------------------------------------------------------ own identity

    /** Takes over the account identity handed over by "Link another phone". */
    @Synchronized
    fun adoptIdentity(serialized: ByteArray, created: Long) {
        identity = IdentityKeyPair(serialized)
        identityCreated = created
        // a new identity makes every prekey and session of the old one useless
        preKeys.clear(); usedPreKeys.clear(); signedPreKeys.clear(); kyberPreKeys.clear(); sessions.clear()
        dirty = true
    }

    /** Starts this account's protection again with a brand-new identity (contacts see a changed code). */
    @Synchronized
    fun resetIdentity() {
        adoptIdentity(IdentityKeyPair.generate().serialize(), System.currentTimeMillis() / 1000)
    }

    @Synchronized
    fun changeDeviceId(newId: Int) {
        deviceId = newId
        dirty = true
    }

    fun identitySerialized(): ByteArray = identity.serialize()

    // ------------------------------------------------------------------ trusted identities (by npub)

    @Synchronized fun trustedIdentity(npub: String): IdentityKey? = trusted[npub]?.let { IdentityKey(it) }

    @Synchronized
    fun setTrusted(npub: String, key: IdentityKey?) {
        if (key == null) trusted.remove(npub) else trusted[npub] = key.serialize()
        dirty = true
    }

    // ------------------------------------------------------------------ SignalProtocolStore

    override fun getIdentityKeyPair(): IdentityKeyPair = identity
    override fun getLocalRegistrationId(): Int = registrationId

    /**
     * libsignal asks this before encrypting and while opening a first message. The identity was
     * already checked against the npub's trusted one when its bundle was accepted (see [Ratchet]);
     * a first message from an unknown identity is recorded here and reported by [Ratchet].
     */
    @Synchronized
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val old = trusted[address.name]
        if (old != null && old.contentEquals(identityKey.serialize())) return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        trusted[address.name] = identityKey.serialize()
        dirty = true
        return if (old == null) IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED else IdentityKeyStore.IdentityChange.REPLACED_EXISTING
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean = true

    @Synchronized override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = trusted[address.name]?.let { IdentityKey(it) }

    @Synchronized
    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        PreKeyRecord(preKeys[preKeyId] ?: throw InvalidKeyIdException("no prekey $preKeyId"))

    @Synchronized override fun storePreKey(preKeyId: Int, record: PreKeyRecord) { preKeys[preKeyId] = record.serialize(); usedPreKeys.remove(preKeyId); dirty = true }
    @Synchronized override fun containsPreKey(preKeyId: Int): Boolean = preKeyId in preKeys

    /** Not deleted at once: left out of the next bundle, and forgotten after [USED_PREKEY_GRACE_SECS]. */
    @Synchronized
    override fun removePreKey(preKeyId: Int) {
        if (preKeyId in preKeys && preKeyId !in usedPreKeys) { usedPreKeys[preKeyId] = System.currentTimeMillis() / 1000; dirty = true }
    }

    /** One-time prekeys that can still go in the bundle. */
    @Synchronized fun freshPreKeys(): List<PreKeyRecord> = preKeys.filterKeys { it !in usedPreKeys }.map { PreKeyRecord(it.value) }

    @Synchronized
    fun dropOldUsedPreKeys(now: Long) {
        val old = usedPreKeys.filterValues { now - it > USED_PREKEY_GRACE_SECS }.keys
        if (old.isNotEmpty()) { old.forEach { preKeys.remove(it); usedPreKeys.remove(it) }; dirty = true }
    }

    @Synchronized fun maxPreKeyId(): Int = preKeys.keys.maxOrNull() ?: 0

    @Synchronized
    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        SignedPreKeyRecord(signedPreKeys[signedPreKeyId] ?: throw InvalidKeyIdException("no signed prekey $signedPreKeyId"))

    @Synchronized override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = signedPreKeys.values.map { SignedPreKeyRecord(it) }
    @Synchronized override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) { signedPreKeys[signedPreKeyId] = record.serialize(); dirty = true }
    @Synchronized override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = signedPreKeyId in signedPreKeys
    @Synchronized override fun removeSignedPreKey(signedPreKeyId: Int) { if (signedPreKeys.remove(signedPreKeyId) != null) dirty = true }

    @Synchronized
    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        KyberPreKeyRecord(kyberPreKeys[kyberPreKeyId] ?: throw InvalidKeyIdException("no kyber prekey $kyberPreKeyId"))

    @Synchronized override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = kyberPreKeys.values.map { KyberPreKeyRecord(it) }
    @Synchronized override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) { kyberPreKeys[kyberPreKeyId] = record.serialize(); dirty = true }
    @Synchronized override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = kyberPreKeyId in kyberPreKeys
    @Synchronized fun removeKyberPreKey(id: Int) { if (kyberPreKeys.remove(id) != null) dirty = true }

    /** Only last-resort Kyber prekeys are published, so a used one stays. */
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) = Unit

    private fun key(a: SignalProtocolAddress) = a.name + "." + a.deviceId

    @Synchronized
    override fun loadSession(address: SignalProtocolAddress): SessionRecord? = sessions[key(address)]?.let { SessionRecord(it) }

    @Synchronized
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { sessions[key(it)]?.let(::SessionRecord) ?: throw NoSessionException("no session for $it") }

    @Synchronized
    override fun getSubDeviceSessions(name: String): List<Int> =
        sessions.keys.filter { it.startsWith("$name.") }.mapNotNull { it.substringAfterLast('.').toIntOrNull() }

    @Synchronized override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) { sessions[key(address)] = record.serialize(); dirty = true }
    @Synchronized override fun containsSession(address: SignalProtocolAddress): Boolean = sessions[key(address)]?.let { SessionRecord(it).hasSenderChain() } == true
    @Synchronized override fun deleteSession(address: SignalProtocolAddress) { if (sessions.remove(key(address)) != null) dirty = true }

    @Synchronized
    override fun deleteAllSessions(name: String) {
        if (sessions.keys.removeAll { it.startsWith("$name.") }) dirty = true
    }

    /** Devices we have a session with, by npub. */
    @Synchronized fun sessionDevices(npub: String): List<Int> = getSubDeviceSessions(npub)

    // sender keys (groups) aren't used: shared gardens use pairwise sessions
    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) = Unit
    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? = null

    // ------------------------------------------------------------------ persistence

    fun markDirty() { dirty = true }

    /** Writes the state to disk if anything changed. */
    @Synchronized
    fun commit() {
        if (!dirty || locked) return
        fun enc(m: Map<Int, ByteArray>) = m.entries.associate { it.key.toString() to it.value.b64() }
        val json = Json.write(
            mapOf(
                "v" to 1, "ik" to identity.serialize().b64(), "ikt" to identityCreated,
                "reg" to registrationId, "dev" to deviceId,
                "pk" to enc(preKeys), "used" to usedPreKeys.entries.associate { it.key.toString() to it.value },
                "spk" to enc(signedPreKeys), "kpk" to enc(kyberPreKeys),
                "s" to sessions.entries.associate { it.key to it.value.b64() },
                "t" to trusted.entries.associate { it.key to it.value.b64() },
                "x" to HashMap(extra),
            ),
        )
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(sealer.seal(json.toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        dirty = false
    }

    /** Forgets everything (Remove identity). */
    @Synchronized
    fun wipe() {
        runCatching { file.delete() }
        preKeys.clear(); usedPreKeys.clear(); signedPreKeys.clear(); kyberPreKeys.clear(); sessions.clear(); trusted.clear(); extra.clear()
        dirty = false
    }

    companion object {
        const val USED_PREKEY_GRACE_SECS = 72 * 3600L
    }
}

internal object RatchetRandom {
    private val r = java.security.SecureRandom()
    fun int(bound: Int) = r.nextInt(bound)
}
