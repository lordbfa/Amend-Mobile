package app.amend.messenger.data

import app.amend.messenger.core.json.Json
import app.amend.messenger.core.json.long
import app.amend.messenger.core.crypto.Hashes
import app.amend.messenger.core.crypto.Rand
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JSON files in the app's private storage. Every write goes to a temp file first and is
 * then renamed, so a crash never leaves a half-written file.
 *
 * With a [key] (32 bytes, derived from the identity key — see [keyFor]) every file is
 * sealed with AES-256-GCM before it touches the disk, so chats, contacts and settings are
 * unreadable to anything that copies the files off the phone without the identity key
 * (which itself sits behind the Android Keystore). Files written by older versions are
 * still read as plain text and are sealed the next time they are saved, or all at once by
 * [sealExisting].
 */
class Store(private val dir: File, private val key: ByteArray? = null) {
    init {
        require(key == null || key.size == 32) { "store key must be 32 bytes" }
        dir.mkdirs()
        File(dir, "chats").mkdirs()
    }

    private fun isSealed(bytes: ByteArray) =
        bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    private fun seal(name: String, text: String): ByteArray {
        val k = key ?: return text.toByteArray(Charsets.UTF_8)
        val nonce = Rand.bytes(NONCE_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, nonce))
        // the file's name is authenticated too: one sealed file can't be swapped in for another
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return MAGIC + nonce + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    /** The text of [bytes] as stored under [name]; null if it is sealed and can't be opened. */
    private fun open(name: String, bytes: ByteArray): String? {
        if (!isSealed(bytes)) return String(bytes, Charsets.UTF_8) // written before sealing existed
        val k = key ?: return null
        if (bytes.size < MAGIC.size + NONCE_SIZE + 16) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"),
                GCMParameterSpec(128, bytes, MAGIC.size, NONCE_SIZE),
            )
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(bytes, MAGIC.size + NONCE_SIZE, bytes.size - MAGIC.size - NONCE_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    private fun read(name: String): String? {
        val f = File(dir, name).takeIf { it.exists() } ?: return null
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
        open(name, bytes)?.let { return it }
        // Sealed with another key, or damaged. Keep it aside rather than let the next save
        // silently replace it — it may still be recoverable.
        runCatching { f.renameTo(File(f.parentFile, f.name + ".unreadable")) }
        return null
    }

    @Synchronized
    private fun write(name: String, text: String) {
        val target = File(dir, name)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(seal(name, text))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    /**
     * Seals every file an older version left as plain text. Safe to run at any time and to
     * interrupt: each file is rewritten on its own, through the usual temp-file-and-rename.
     * Returns how many files were sealed.
     */
    fun sealExisting(): Int {
        if (key == null) return 0
        val names = (dir.listFiles().orEmpty().filter { it.isFile }.map { it.name } +
            listOf("chats", "instants").flatMap { sub -> File(dir, sub).listFiles().orEmpty().filter { it.isFile }.map { "$sub/${it.name}" } })
            .filter { it.endsWith(".json") }
        return names.count { sealOne(it) }
    }

    @Synchronized
    private fun sealOne(name: String): Boolean {
        val f = File(dir, name)
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return false
        if (bytes.isEmpty() || isSealed(bytes)) return false
        write(name, String(bytes, Charsets.UTF_8))
        return true
    }

    // --- settings -------------------------------------------------------------

    data class Settings(
        val relays: List<String>,
        val lastSync: Long,
        val blocked: Set<String>,
        val accepted: Set<String>,
        val pinned: Set<String>,
        val nicknames: Map<String, String>,
        val seenWraps: List<String>,
        /** Shared gardens (groups) by key. */
        val groups: Map<String, GroupInfo> = emptyMap(),
        /** Disappearing-message timer per chat, seconds. */
        val disappear: Map<String, Long> = emptyMap(),
        /** When each chat's timer was last changed (so older history can't undo a newer change). */
        val disappearAt: Map<String, Long> = emptyMap(),
        /** Unsent message ids → their author, so a copy that arrives late is not shown. */
        val unsent: Map<String, String> = emptyMap(),
        /** Accounts amend's moderator has blocked (from the shared blocklist), and that list's time. */
        val modBlocked: Set<String> = emptySet(),
        val modListAt: Long = 0,
        /** The blocks the moderator list made (so lifting one never undoes a block you made). */
        val modAdded: Set<String> = emptySet(),
        /** Close friends: who gets your Instants and close-friends stories by default. */
        val closeFriends: Set<String> = emptySet(),
        /** Who sees my Instants: everyone in my chat list (default), or only [closeFriends]. */
        val instantsToAll: Boolean = true,
    )

    @Suppress("UNCHECKED_CAST")
    fun loadSettings(defaultRelays: List<String>): Settings {
        val m = read("settings.json")?.let { runCatching { Json.parseObject(it) }.getOrNull() } ?: emptyMap()
        fun strList(key: String) = (m[key] as? List<Any?>).orEmpty().mapNotNull { it as? String }
        return Settings(
            relays = strList("relays").ifEmpty { defaultRelays },
            lastSync = m.long("lastSync") ?: 0,
            blocked = strList("blocked").toSet(),
            accepted = strList("accepted").toSet(),
            pinned = strList("pinned").toSet(),
            nicknames = (m["nicknames"] as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() },
            seenWraps = strList("seenWraps"),
            groups = (m["groups"] as? Map<String, Any?>).orEmpty()
                .mapNotNull { (k, v) -> (v as? Map<String, Any?>)?.let { k to GroupInfo.fromMap(it) } }.toMap(),
            disappear = (m["disappear"] as? Map<String, Any?>).orEmpty().mapValues { (it.value as? Number)?.toLong() ?: 0L },
            disappearAt = (m["disappearAt"] as? Map<String, Any?>).orEmpty().mapValues { (it.value as? Number)?.toLong() ?: 0L },
            unsent = (m["unsent"] as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() },
            modBlocked = strList("modBlocked").toSet(),
            modListAt = m.long("modListAt") ?: 0,
            modAdded = strList("modAdded").toSet(),
            closeFriends = strList("closeFriends").toSet(),
            instantsToAll = m["instantsToAll"] != false,
        )
    }

    fun saveSettings(s: Settings) = write(
        "settings.json",
        Json.write(
            linkedMapOf(
                "relays" to s.relays, "lastSync" to s.lastSync, "blocked" to s.blocked.toList(),
                "accepted" to s.accepted.toList(), "pinned" to s.pinned.toList(), "nicknames" to s.nicknames,
                "seenWraps" to s.seenWraps.takeLast(5000),
                "groups" to s.groups.mapValues { it.value.toMap() },
                "disappear" to s.disappear,
                "disappearAt" to s.disappearAt,
                "unsent" to s.unsent.entries.toList().takeLast(2000).associate { it.key to it.value },
                "modBlocked" to s.modBlocked.toList(), "modListAt" to s.modListAt, "modAdded" to s.modAdded.toList(), "closeFriends" to s.closeFriends.toList(), "instantsToAll" to s.instantsToAll,
            )
        )
    )

    // --- messages -------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    fun loadChats(): Map<String, List<ChatMessage>> {
        val files = File(dir, "chats").listFiles { f -> f.name.endsWith(".json") } ?: return emptyMap()
        return files.mapNotNull { f ->
            runCatching {
                val list = Json.parseArray(read("chats/${f.name}")!!).map { ChatMessage.fromMap(it as Map<String, Any?>) }
                f.name.removeSuffix(".json") to list
            }.getOrNull()
        }.toMap()
    }

    /** One chat from disk, or null if there is none. */
    @Suppress("UNCHECKED_CAST")
    fun loadChat(peer: String): List<ChatMessage>? {
        if (chatFile(peer) == null) return null
        val text = read("chats/$peer.json") ?: return null
        return runCatching { Json.parseArray(text).map { ChatMessage.fromMap(it as Map<String, Any?>) } }.getOrNull()
    }

    fun saveChat(peer: String, messages: List<ChatMessage>) {
        if (chatFile(peer) == null) return // never write outside chats/
        write("chats/$peer.json", Json.write(messages.map { it.toMap() }))
    }

    fun deleteChat(peer: String) {
        chatFile(peer)?.delete()
    }

    /** Chat ids are hex keys, group keys or amend's own "amend:…" ids — never a path. */
    private fun chatFile(peer: String): File? =
        if (SAFE_ID.matches(peer)) File(dir, "chats/$peer.json") else null

    // --- profiles & relay lists -------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    fun loadProfiles(): Map<String, Profile> {
        val m = read("profiles.json")?.let { runCatching { Json.parseObject(it) }.getOrNull() } ?: return emptyMap()
        return m.mapNotNull { (k, v) -> (v as? Map<String, Any?>)?.let { k to Profile.fromMap(it) } }.toMap()
    }

    fun saveProfiles(p: Map<String, Profile>) = write("profiles.json", Json.write(p.mapValues { it.value.toMap() }))

    @Suppress("UNCHECKED_CAST")
    fun loadInboxRelays(): Map<String, List<String>> {
        val m = read("inbox_relays.json")?.let { runCatching { Json.parseObject(it) }.getOrNull() } ?: return emptyMap()
        return m.mapValues { (_, v) -> (v as? List<Any?>).orEmpty().mapNotNull { it as? String } }
    }

    fun saveInboxRelays(m: Map<String, List<String>>) = write("inbox_relays.json", Json.write(m))

    // --- stories -----------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    fun loadStories(): List<Story> {
        val list = read("stories.json")?.let { runCatching { Json.parseArray(it) }.getOrNull() } ?: return emptyList()
        return list.mapNotNull { (it as? Map<String, Any?>)?.let(Story::fromMap) }
    }

    fun saveStories(list: List<Story>) = write("stories.json", Json.write(list.map { it.toMap() }))

    // --- instants archive -----------------------------------------------------------

    fun instantFile(id: String) = File(dir, "instants/$id.jpg")

    fun saveInstant(a: ArchivedInstant, jpeg: ByteArray) {
        val f = instantFile(a.id)
        f.parentFile?.mkdirs()
        f.writeBytes(jpeg)
    }

    @Suppress("UNCHECKED_CAST")
    fun loadInstantArchive(): List<ArchivedInstant> {
        val list = read("instants/index.json")?.let { runCatching { Json.parseArray(it) }.getOrNull() } ?: return emptyList()
        val cutoff = System.currentTimeMillis() / 1000 - 365L * 86400
        val all = list.mapNotNull { (it as? Map<String, Any?>)?.let(ArchivedInstant::fromMap) }
        all.filter { it.createdAt <= cutoff }.forEach { instantFile(it.id).delete() }
        return all.filter { it.createdAt > cutoff && instantFile(it.id).exists() }
    }

    fun saveInstantArchive(list: List<ArchivedInstant>) = write("instants/index.json", Json.write(list.map { it.toMap() }))


    // --- friends' shared forests ---------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    fun loadFriendStats(): Map<String, FriendStats> {
        val m = read("friend_stats.json")?.let { runCatching { Json.parseObject(it) }.getOrNull() } ?: return emptyMap()
        return m.mapNotNull { (k, v) -> (v as? Map<String, Any?>)?.let { k to FriendStats.fromMap(it) } }.toMap()
    }

    fun saveFriendStats(m: Map<String, FriendStats>) = write("friend_stats.json", Json.write(m.mapValues { it.value.toMap() }))

    // --- eco --------------------------------------------------------------------

    fun loadEco(): EcoState =
        read("eco.json")?.let { runCatching { EcoState.fromMap(Json.parseObject(it)) }.getOrNull() } ?: EcoState()

    fun saveEco(e: EcoState) = write("eco.json", Json.write(e.toMap()))

    fun wipe() {
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "chats").mkdirs()
    }

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9:_-]{1,96}")
        /** Marks a sealed file. Plain JSON starts with '{' or '[', so the two never collide. */
        private val MAGIC = byteArrayOf(0x41, 0x4D, 0x44, 0x01) // "AMD" + format version 1
        private const val NONCE_SIZE = 12

        /**
         * The storage key for an identity: derived one-way from its secret key, so whoever can
         * open the identity can open its files and nothing extra has to be kept or can be lost.
         */
        fun keyFor(secretKey: ByteArray): ByteArray =
            Hashes.hkdfExpand(Hashes.hkdfExtract("amend/store".toByteArray(), secretKey), "files/v1".toByteArray(), 32)
    }
}
