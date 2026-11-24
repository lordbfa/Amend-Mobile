package app.amend.messenger.core.ratchet

import app.amend.messenger.core.json.Json
import app.amend.messenger.core.json.long
import app.amend.messenger.core.json.str
import app.amend.messenger.core.nostr.Event
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.core.nostr.Kinds
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.Base64

/**
 * Forward-secret chats: the Signal Protocol (PQXDH + Double Ratchet, libsignal) inside NIP-17.
 * See docs/RATCHET.md. The nsec opens only the outer gift wrap; the message inside is
 * encrypted with keys that stay on the phone and change with every message.
 *
 * Every public method takes the same lock, so libsignal never sees two operations at once.
 */
class Ratchet(private val me: String, val store: RatchetStore, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()
    private fun ByteArray.b64() = b64e.encodeToString(this)
    private fun String.unb64(): ByteArray = b64d.decode(this)

    val deviceId: Int get() = store.deviceId

    /** True once a bundle should go out again (new keys, a one-time prekey used up, a new device id). */
    @Volatile var bundleStale = true
        private set

    /**
     * Another phone published bundles for my npub with a different ratchet identity: either my
     * own second phone set up separately, or someone holding my nsec. Nothing changes on its own
     * (this phone keeps its keys); the user is warned and links the phones to settle it.
     */
    val identityConflict: Boolean get() = store.extra["conflict"] == "1"

    // ------------------------------------------------------------------ own keys

    /** Makes or rotates this phone's prekeys. Returns true if the bundle changed. */
    @Synchronized
    fun maintain(): Boolean {
        val t = now()
        val id = store.identityKeyPair
        var changed = false
        val spks = store.loadSignedPreKeys().sortedByDescending { it.timestamp }
        if (spks.isEmpty() || t - spks.first().timestamp / 1000 > SIGNED_PREKEY_DAYS * 86400) {
            val newId = (spks.maxOfOrNull { it.id } ?: 0) % 0xFFFFFF + 1
            val kp = ECKeyPair.generate()
            store.storeSignedPreKey(newId, SignedPreKeyRecord(newId, t * 1000, kp, id.privateKey.calculateSignature(kp.publicKey.serialize())))
            changed = true
        }
        // keep the newest three: a message made from a slightly old bundle still opens
        store.loadSignedPreKeys().sortedByDescending { it.timestamp }.drop(3).forEach { store.removeSignedPreKey(it.id) }
        val kpks = store.loadKyberPreKeys().sortedByDescending { it.timestamp }
        if (kpks.isEmpty() || t - kpks.first().timestamp / 1000 > KYBER_PREKEY_DAYS * 86400) {
            val newId = (kpks.maxOfOrNull { it.id } ?: 0) % 0xFFFFFF + 1
            val kp = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            store.storeKyberPreKey(newId, KyberPreKeyRecord(newId, t * 1000, kp, id.privateKey.calculateSignature(kp.publicKey.serialize())))
            changed = true
        }
        store.loadKyberPreKeys().sortedByDescending { it.timestamp }.drop(3).forEach { store.removeKyberPreKey(it.id) }
        store.dropOldUsedPreKeys(t)
        val fresh = store.freshPreKeys().size
        if (fresh < ONE_TIME_PREKEYS) {
            var next = store.maxPreKeyId()
            repeat(ONE_TIME_PREKEYS - fresh) {
                next = next % 0xFFFFFF + 1
                store.storePreKey(next, PreKeyRecord(next, ECKeyPair.generate()))
            }
            changed = true
        }
        val lastPublished = store.extra["published"]?.toLongOrNull() ?: 0
        if (changed || t - lastPublished > REPUBLISH_DAYS * 86400) bundleStale = true
        store.commit()
        return changed
    }

    private fun bodyMessage(npub: String, device: Int, body: String) =
        "$BUNDLE_DOMAIN\n$npub\n$device\n$body".toByteArray(Charsets.UTF_8)

    /** This phone's prekey bundle, signed with the nsec and (inside) with the ratchet identity. */
    @Synchronized
    fun ownBundle(keys: KeyPair): Event {
        val t = now()
        val id = store.identityKeyPair
        val spk = store.loadSignedPreKeys().maxByOrNull { it.timestamp } ?: error("no signed prekey: call maintain() first")
        val kpk = store.loadKyberPreKeys().maxByOrNull { it.timestamp } ?: error("no kyber prekey: call maintain() first")
        val body = Json.write(
            mapOf(
                "v" to 1, "ik" to id.publicKey.serialize().b64(), "ikt" to store.identityCreated,
                "reg" to store.registrationId, "t" to t,
                "spk" to mapOf("id" to spk.id, "k" to spk.keyPair.publicKey.serialize().b64(), "s" to spk.signature.b64()),
                "kpk" to mapOf("id" to kpk.id, "k" to kpk.keyPair.publicKey.serialize().b64(), "s" to kpk.signature.b64()),
                "pk" to store.freshPreKeys().map { mapOf("id" to it.id, "k" to it.keyPair.publicKey.serialize().b64()) },
            ),
        )
        return signedBundle(keys, body, t)
    }

    /** Tells my contacts this phone no longer takes protected messages (feature off, or removed). */
    @Synchronized
    fun retiredBundle(keys: KeyPair): Event {
        val t = now()
        val id = store.identityKeyPair
        val body = Json.write(mapOf("v" to 1, "ik" to id.publicKey.serialize().b64(), "ikt" to store.identityCreated, "t" to t, "retired" to true))
        return signedBundle(keys, body, t)
    }

    private fun signedBundle(keys: KeyPair, body: String, t: Long): Event {
        val sig = store.identityKeyPair.privateKey.calculateSignature(bodyMessage(keys.publicKeyHex, store.deviceId, body))
        return Event.sign(
            secretKey = keys.secretKey,
            kind = Kinds.PREKEY_BUNDLE,
            content = Json.write(mapOf("body" to body, "isig" to sig.b64())),
            tags = listOf(listOf("d", store.deviceId.toString()), listOf("expiration", (t + BUNDLE_LIFETIME_DAYS * 86400).toString())),
            createdAt = t,
        )
    }

    /** Called once the bundle from [ownBundle] has reached a relay. */
    @Synchronized
    fun bundlePublished() {
        store.extra["published"] = now().toString()
        store.markDirty()
        store.commit()
        bundleStale = false
    }

    // ------------------------------------------------------------------ other people's bundles

    sealed interface BundleResult {
        /** A bundle we now use (first one for this person, or a refresh). */
        data object Accepted : BundleResult
        /** Their ratchet identity changed: sessions were reset. Show a notice, un-verify. */
        data object IdentityChanged : BundleResult
        /** They turned protection off on that phone. */
        data object Retired : BundleResult
        /** Not usable: bad signature, expired, malformed, or older than what we have. */
        data class Rejected(val why: String) : BundleResult
        /** First protection identity seen for this person (no notice; old verifications no longer apply). */
        data object FirstSeen : BundleResult
        /** A bundle for my npub with another identity: warn (my other phone, or a stolen nsec). */
        data object OwnConflict : BundleResult
    }

    /** Checks a kind-30443 bundle event (its Nostr id and signature already verified) and keeps it. */
    @Synchronized
    fun acceptBundle(ev: Event): BundleResult {
        if (ev.kind != Kinds.PREKEY_BUNDLE) return BundleResult.Rejected("kind")
        val npub = ev.pubkey
        val device = ev.firstTag("d")?.toIntOrNull()?.takeIf { it in 1..127 } ?: return BundleResult.Rejected("device id")
        ev.firstTag("expiration")?.toLongOrNull()?.let { if (it < now()) return BundleResult.Rejected("expired") }
        val outer = runCatching { Json.parseObject(ev.content) }.getOrNull() ?: return BundleResult.Rejected("content")
        val body = outer.str("body") ?: return BundleResult.Rejected("body")
        val isig = outer.str("isig")?.let { runCatching { it.unb64() }.getOrNull() } ?: return BundleResult.Rejected("isig")
        val b = runCatching { Json.parseObject(body) }.getOrNull() ?: return BundleResult.Rejected("body json")
        val ik = runCatching { IdentityKey(b.str("ik")!!.unb64()) }.getOrNull() ?: return BundleResult.Rejected("identity key")
        if (!ik.publicKey.verifySignature(bodyMessage(npub, device, body), isig)) return BundleResult.Rejected("identity signature")
        val bt = b.long("t") ?: ev.createdAt
        val ikt = b.long("ikt") ?: 0
        val retired = b["retired"] == true
        val cacheKey = "b:$npub:$device"
        // never go back to an older bundle for the same phone
        val cachedT = store.extra[cacheKey]?.let { runCatching { Json.parseObject(it).long("t") }.getOrNull() }
        if (cachedT != null && bt < cachedT) return BundleResult.Rejected("older")

        if (npub == me) {
            val mine = store.identityKeyPair.publicKey
            if (ik != mine) {
                // never switch anything off because of it: anyone with the nsec could publish this
                if (!retired) { store.extra["conflict"] = "1"; store.markDirty(); store.commit() }
                return BundleResult.OwnConflict
            }
            if (device == store.deviceId) {
                // my own bundle echoed back, or another phone of mine picked the same device id
                if ((b.long("reg") ?: -1L).toInt() != store.registrationId && !retired) {
                    store.changeDeviceId(freeDeviceId(npub)); bundleStale = true; store.commit()
                }
                return BundleResult.Rejected("own device")
            }
        }

        var result: BundleResult = BundleResult.Accepted
        val trusted = store.trustedIdentity(npub)
        if (npub != me && trusted != null && trusted != ik) {
            if (retired) return BundleResult.Rejected("retired by another identity")
            // they restored from their backup key or reinstalled: a new identity. Visible, like Signal.
            store.setTrusted(npub, ik)
            store.extra["trustedAt:$npub"] = now().toString()
            store.deleteAllSessions(npub)
            store.extra.keys.filter { it.startsWith("b:$npub:") }.toList().forEach { store.extra.remove(it) }
            result = BundleResult.IdentityChanged
        } else if (npub != me && trusted == null) {
            store.setTrusted(npub, ik)
            store.extra["trustedAt:$npub"] = now().toString()
            result = BundleResult.FirstSeen
        }
        if (retired) {
            store.extra.remove(cacheKey)
            store.deleteSession(SignalProtocolAddress(npub, device))
            // An identity that is less than a week old can't switch protection off: "new identity,
            // then retire" would otherwise turn a visible takeover into a quiet downgrade.
            val since = store.extra["trustedAt:$npub"]?.toLongOrNull() ?: 0
            if (devicesOf(npub).isEmpty() && now() - since >= RETIRE_MIN_IDENTITY_AGE_SECS) store.extra.remove("pin:$npub")
            store.markDirty(); store.commit()
            return BundleResult.Retired
        }
        store.extra[cacheKey] = body
        store.extra["pin:$npub"] = "1"
        store.markDirty()
        store.commit()
        return result
    }

    private fun freeDeviceId(npub: String): Int {
        val taken = devicesOf(npub).toSet() + store.deviceId
        return (1..127).filter { it !in taken }.randomOrNull() ?: (1 + RatchetRandom.int(127))
    }

    /** Phones of [npub] we can send to: from their bundles, plus any we already have a session with. */
    @Synchronized
    fun devicesOf(npub: String): List<Int> {
        val fromBundles = store.extra.keys.filter { it.startsWith("b:$npub:") }.mapNotNull { it.substringAfterLast(':').toIntOrNull() }
        val mine = if (npub == me) setOf(store.deviceId) else emptySet()
        return (fromBundles + store.sessionDevices(npub)).distinct().filter { it !in mine }.sorted()
    }

    /** [npub] has used protected messages: never fall back to plain NIP-17 quietly. */
    @Synchronized fun isPinned(npub: String): Boolean = store.extra["pin:$npub"] == "1"

    /** Can we send [npub] a protected message right now? */
    @Synchronized fun canReach(npub: String): Boolean = devicesOf(npub).isNotEmpty()

    @Synchronized fun trustedIdentityOf(npub: String): ByteArray? = store.trustedIdentity(npub)?.serialize()

    fun ownIdentity(): ByteArray = store.identityKeyPair.publicKey.serialize()

    // ------------------------------------------------------------------ encrypt / decrypt

    /**
     * Encrypts [inner] (a NIP-17 rumor by me) for every phone of [npub]. Returns one kind-1443
     * envelope rumor per phone, ready to be sealed and gift-wrapped. Commits before returning:
     * a message key must never be used twice, even after a crash.
     */
    @Synchronized
    fun encrypt(npub: String, inner: Event): List<Event> {
        require(inner.pubkey == me) { "inner rumor must be mine" }
        val plain = pad(inner.toJson(includeSig = false).toByteArray(Charsets.UTF_8))
        val out = ArrayList<Event>()
        for (device in devicesOf(npub)) {
            val address = SignalProtocolAddress(npub, device)
            try {
                if (!store.containsSession(address)) {
                    val bundle = bundleFor(npub, device) ?: continue
                    SessionBuilder(store, address).process(bundle)
                }
                val ct = SessionCipher(store, address).encrypt(plain)
                val type = if (ct.type == CiphertextMessage.PREKEY_TYPE) "prekey" else "msg"
                out += Event.unsigned(
                    me, Kinds.RATCHET_MESSAGE, ct.serialize().b64(),
                    listOf(listOf("p", npub), listOf("device", device.toString()), listOf("from", store.deviceId.toString()), listOf("type", type)),
                    createdAt = inner.createdAt,
                )
            } catch (e: Exception) {
                // a broken bundle or session for one phone must not stop the others
                store.deleteSession(address)
            }
        }
        store.commit()
        return out
    }

    private fun bundleFor(npub: String, device: Int): PreKeyBundle? {
        val b = store.extra["b:$npub:$device"]?.let { runCatching { Json.parseObject(it) }.getOrNull() } ?: return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val spk = b["spk"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val kpk = b["kpk"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val pks = (b["pk"] as? List<Map<String, Any?>>).orEmpty()
            val pick = pks.randomOrNull()
            PreKeyBundle(
                (b.long("reg") ?: 0).toInt(), device,
                pick?.long("id")?.toInt() ?: PreKeyBundle.NULL_PRE_KEY_ID,
                pick?.str("k")?.let { ECPublicKey(it.unb64()) },
                spk.long("id")!!.toInt(), ECPublicKey(spk.str("k")!!.unb64()), spk.str("s")!!.unb64(),
                IdentityKey(b.str("ik")!!.unb64()),
                kpk.long("id")!!.toInt(), KEMPublicKey(kpk.str("k")!!.unb64()), kpk.str("s")!!.unb64(),
            )
        }.getOrNull()
    }

    sealed interface Opened {
        /** The real rumor inside; [identityChanged] when the sender's ratchet identity changed with it. */
        data class Message(val inner: Event, val identityChanged: Boolean) : Opened
        /** For another of my phones, or seen before: nothing to do. */
        data object Ignore : Opened
        /** Couldn't be opened (lost session, corrupted): show a placeholder, maybe start over. */
        data class Failed(val why: String) : Opened
    }

    /**
     * Opens a kind-1443 envelope (already taken out of its gift wrap and seal, so its author is
     * the verified sender). Does not commit: call [commit] after the message has been saved.
     */
    @Synchronized
    fun decrypt(envelope: Event): Opened {
        if (envelope.kind != Kinds.RATCHET_MESSAGE) return Opened.Failed("kind")
        val target = envelope.firstTag("device")?.toIntOrNull() ?: return Opened.Failed("no device")
        if (target != store.deviceId) return Opened.Ignore
        if (envelope.firstTag("p") != me) return Opened.Ignore
        val from = envelope.firstTag("from")?.toIntOrNull()?.takeIf { it in 1..127 } ?: return Opened.Failed("no sender device")
        val sender = envelope.pubkey
        val address = SignalProtocolAddress(sender, from)
        val bytes = runCatching { envelope.content.unb64() }.getOrNull() ?: return Opened.Failed("base64")
        return try {
            var changed = false
            val plain = if (envelope.firstTag("type") == "prekey") {
                val msg = PreKeySignalMessage(bytes)
                val trusted = store.trustedIdentity(sender)
                if (sender == me && msg.identityKey != store.identityKeyPair.publicKey) return Opened.Failed("not my identity")
                if (trusted == null && sender != me) store.extra["trustedAt:$sender"] = now().toString()
                if (trusted != null && trusted != msg.identityKey) {
                    changed = true
                    store.extra["trustedAt:$sender"] = now().toString()
                    store.deleteAllSessions(sender)
                    store.extra.keys.filter { it.startsWith("b:$sender:") }.toList().forEach { store.extra.remove(it) }
                }
                val p = SessionCipher(store, address).decrypt(msg)
                if (sender != me) store.extra["pin:$sender"] = "1"
                // a one-time prekey was used: the next bundle leaves it out
                bundleStale = true
                p
            } else {
                SessionCipher(store, address).decrypt(SignalMessage(bytes))
            }
            val inner = runCatching { Event.fromJson(String(unpad(plain), Charsets.UTF_8)) }.getOrNull()
                ?: return Opened.Failed("inner json")
            // the sealed sender (who signed the seal) must be the author of what's inside
            if (inner.pubkey != sender || !inner.isValid(requireSignature = false)) return Opened.Failed("inner author")
            store.markDirty()
            Opened.Message(inner, changed)
        } catch (e: DuplicateMessageException) {
            Opened.Ignore
        } catch (e: Exception) {
            Opened.Failed(e.javaClass.simpleName + ": " + e.message)
        }
    }

    /** Writes the ratchet state after the opened message has been stored. */
    @Synchronized fun commit() = store.commit()

    /** Starts over with one phone of [npub] after a message from it couldn't be opened (rate-limited). */
    @Synchronized
    fun resetSession(npub: String, device: Int): Boolean {
        val key = "reset:$npub:$device"
        val last = store.extra[key]?.toLongOrNull() ?: 0
        if (now() - last < RESET_INTERVAL_SECS) return false
        store.extra[key] = now().toString()
        store.deleteSession(SignalProtocolAddress(npub, device))
        store.commit()
        return true
    }

    /** For "Link another phone": the account identity, to hand over phone to phone. */
    fun exportIdentity(): Pair<ByteArray, Long> = store.identitySerialized() to store.identityCreated

    companion object {
        const val BUNDLE_DOMAIN = "amend-ratchet-bundle-v1"
        const val ONE_TIME_PREKEYS = 20
        const val SIGNED_PREKEY_DAYS = 7L
        const val KYBER_PREKEY_DAYS = 30L
        const val REPUBLISH_DAYS = 6L
        const val BUNDLE_LIFETIME_DAYS = 45L
        const val RESET_INTERVAL_SECS = 3600L
        const val RETIRE_MIN_IDENTITY_AGE_SECS = 7 * 86400L
        private const val BLOCK = 160

        /** Pads to a multiple of 160 bytes (0x80 then zeros) so lengths say little. */
        fun pad(m: ByteArray): ByteArray {
            val size = ((m.size + 1 + BLOCK - 1) / BLOCK) * BLOCK
            return ByteArray(size).also { m.copyInto(it); it[m.size] = 0x80.toByte() }
        }

        fun unpad(p: ByteArray): ByteArray {
            var i = p.size - 1
            while (i >= 0 && p[i] == 0.toByte()) i--
            require(i >= 0 && p[i] == 0x80.toByte()) { "bad padding" }
            return p.copyOf(i)
        }
    }
}
