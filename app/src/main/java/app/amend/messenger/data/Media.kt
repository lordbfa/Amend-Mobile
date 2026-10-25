package app.amend.messenger.data

import app.amend.messenger.core.crypto.hexToBytes
import app.amend.messenger.core.json.Json
import app.amend.messenger.core.json.long
import app.amend.messenger.core.json.str
import app.amend.messenger.core.nostr.Event
import java.util.Base64

enum class MediaKind(val tag: String) {
    VOICE("voice"), VIDEO("video"), FILE("file");

    companion object {
        fun of(tag: String?, mime: String): MediaKind = entries.firstOrNull { it.tag == tag }
            ?: when {
                mime.startsWith("audio/") -> VOICE
                mime.startsWith("video/") -> VIDEO
                else -> FILE
            }
    }
}

/**
 * Everything needed to fetch and decrypt one media file. Stored (as JSON) in
 * [ChatMessage.extra] for VOICE / VIDEO messages.
 */
data class MediaInfo(
    val kind: MediaKind,
    val urls: List<String>,          // main URL first, then fallbacks
    val mime: String,
    val keyHex: String,
    val nonceHex: String,
    val sha256: String,               // of the encrypted file
    val size: Long,
    val durationMs: Long = 0,
    val waveform: ByteArray = ByteArray(0), // 0..255 amplitude samples for the voice bubble
    val width: Int = 0,
    val height: Int = 0,
) {
    fun toJson(): String = Json.write(
        linkedMapOf(
            "kind" to kind.tag, "urls" to urls, "mime" to mime, "key" to keyHex, "nonce" to nonceHex,
            "x" to sha256, "size" to size, "dur" to durationMs,
            "wave" to Base64.getEncoder().encodeToString(waveform), "w" to width, "h" to height,
        )
    )

    /** NIP-17 kind-15 file-message tags (plus amend extras other apps can ignore). */
    fun tags(peer: String): List<List<String>> = buildList {
        add(listOf("p", peer))
        add(listOf("file-type", mime))
        add(listOf("encryption-algorithm", "aes-gcm"))
        add(listOf("decryption-key", keyHex))
        add(listOf("decryption-nonce", nonceHex))
        add(listOf("x", sha256))
        add(listOf("size", size.toString()))
        if (width > 0 && height > 0) add(listOf("dim", "${width}x$height"))
        urls.drop(1).forEach { add(listOf("fallback", it)) }
        add(listOf("amend", "media", kind.tag))
        if (durationMs > 0) add(listOf("amend-dur", durationMs.toString()))
        if (waveform.isNotEmpty()) add(listOf("amend-wave", Base64.getEncoder().encodeToString(waveform)))
    }

    override fun equals(other: Any?) = other is MediaInfo && other.sha256 == sha256 && other.urls == urls
    override fun hashCode() = sha256.hashCode()

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(s: String?): MediaInfo? = try {
            val m = Json.parseObject(s ?: return null)
            val mime = m.str("mime") ?: "application/octet-stream"
            MediaInfo(
                kind = MediaKind.of(m.str("kind"), mime),
                urls = (m["urls"] as? List<Any?>).orEmpty().mapNotNull { it as? String },
                mime = mime,
                keyHex = m.str("key") ?: return null,
                nonceHex = m.str("nonce") ?: return null,
                sha256 = m.str("x") ?: "",
                size = m.long("size") ?: 0,
                durationMs = m.long("dur") ?: 0,
                waveform = runCatching { Base64.getDecoder().decode(m.str("wave") ?: "") }.getOrDefault(ByteArray(0)),
                width = (m.long("w") ?: 0).toInt(),
                height = (m.long("h") ?: 0).toInt(),
            )
        } catch (e: Exception) {
            null
        }

        /** Reads a kind-15 rumor (from amend or any NIP-17 app). */
        fun fromRumor(rumor: Event): MediaInfo? {
            val url = rumor.content.trim().takeIf { it.startsWith("https://") } ?: return null
            if (rumor.firstTag("encryption-algorithm") != "aes-gcm") return null
            val key = rumor.firstTag("decryption-key") ?: return null
            val nonce = rumor.firstTag("decryption-nonce") ?: return null
            // sanity: must be valid hex of the right length
            if (runCatching { key.hexToBytes().size == 32 && nonce.hexToBytes().size in 12..16 }.getOrDefault(false).not()) return null
            val mime = rumor.firstTag("file-type") ?: "application/octet-stream"
            val dim = rumor.firstTag("dim")?.split("x")
            val amendKind = rumor.tags.firstOrNull { it.size >= 3 && it[0] == "amend" && it[1] == "media" }?.get(2)
            return MediaInfo(
                kind = MediaKind.of(amendKind, mime),
                urls = listOf(url) + rumor.tagValues("fallback").filter { it.startsWith("https://") }.take(3),
                mime = mime,
                keyHex = key,
                nonceHex = nonce,
                sha256 = rumor.firstTag("x") ?: "",
                size = rumor.firstTag("size")?.toLongOrNull() ?: 0,
                durationMs = rumor.firstTag("amend-dur")?.toLongOrNull() ?: 0,
                waveform = rumor.firstTag("amend-wave")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: ByteArray(0),
                width = dim?.getOrNull(0)?.toIntOrNull() ?: 0,
                height = dim?.getOrNull(1)?.toIntOrNull() ?: 0,
            )
        }
    }
}
