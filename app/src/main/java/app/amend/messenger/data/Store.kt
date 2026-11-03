package app.amend.messenger.data

import app.amend.messenger.core.json.Json
import app.amend.messenger.core.json.long
import app.amend.messenger.core.json.str
import java.io.File

/**
 * Plain JSON files in the app's private storage. Every write goes to a temp
 * file first and is then renamed, so a crash never leaves a half-written file.
 * Messages are only ever stored decrypted on the device itself.
 */
class Store(private val dir: File) {
    init {
        dir.mkdirs()
        File(dir, "chats").mkdirs()
    }

    private fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()

    @Synchronized
    private fun write(name: String, text: String) {
        val target = File(dir, name)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
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
                "modBlocked" to s.modBlocked.toList(), "modListAt" to s.modListAt, "modAdded" to s.modAdded.toList(), "closeFriends" to s.closeFriends.toList(),
            )
        )
    )

    // --- messages -------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    fun loadChats(): Map<String, List<ChatMessage>> {
        val files = File(dir, "chats").listFiles { f -> f.name.endsWith(".json") } ?: return emptyMap()
        return files.mapNotNull { f ->
            runCatching {
                val list = Json.parseArray(f.readText()).map { ChatMessage.fromMap(it as Map<String, Any?>) }
                f.name.removeSuffix(".json") to list
            }.getOrNull()
        }.toMap()
    }

    fun saveChat(peer: String, messages: List<ChatMessage>) =
        write("chats/$peer.json", Json.write(messages.map { it.toMap() }))

    fun deleteChat(peer: String) {
        File(dir, "chats/$peer.json").delete()
    }

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

    fun deleteInstant(id: String) { instantFile(id).delete() }

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

    @Suppress("unused")
    private fun Map<String, Any?>.s(k: String) = str(k)
}
