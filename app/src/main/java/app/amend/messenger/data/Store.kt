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
        )
    }

    fun saveSettings(s: Settings) = write(
        "settings.json",
        Json.write(
            linkedMapOf(
                "relays" to s.relays, "lastSync" to s.lastSync, "blocked" to s.blocked.toList(),
                "accepted" to s.accepted.toList(), "pinned" to s.pinned.toList(), "nicknames" to s.nicknames,
                "seenWraps" to s.seenWraps.takeLast(5000),
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
