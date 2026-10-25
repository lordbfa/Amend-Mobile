package app.amend.messenger.core.nostr

import app.amend.messenger.core.crypto.Hashes
import app.amend.messenger.core.crypto.Rand
import app.amend.messenger.core.crypto.Secp
import app.amend.messenger.core.crypto.hexToBytes
import app.amend.messenger.core.crypto.isHex64
import app.amend.messenger.core.crypto.toHex
import app.amend.messenger.core.json.Json
import app.amend.messenger.core.json.long
import app.amend.messenger.core.json.str

/** A Nostr event (NIP-01). [sig] is empty for unsigned "rumors" (NIP-59). */
data class Event(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun toMap(includeSig: Boolean = true): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to id,
        "pubkey" to pubkey,
        "created_at" to createdAt,
        "kind" to kind,
        "tags" to tags,
        "content" to content,
    ).also { if (includeSig) it["sig"] = sig }

    fun toJson(includeSig: Boolean = true): String = Json.write(toMap(includeSig))

    fun tagValues(name: String): List<String> = tags.filter { it.size >= 2 && it[0] == name }.map { it[1] }
    fun firstTag(name: String): String? = tagValues(name).firstOrNull()

    /** Checks the id matches the content, and (if signed) the signature. */
    fun isValid(requireSignature: Boolean = true): Boolean {
        if (!pubkey.isHex64() || !id.isHex64()) return false
        if (computeId(pubkey, createdAt, kind, tags, content) != id) return false
        if (!requireSignature) return true
        if (sig.length != 128) return false
        return try {
            Secp.provider.verifySchnorr(sig.hexToBytes(), id.hexToBytes(), pubkey.hexToBytes())
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        fun computeId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
            val serialized = Json.write(listOf(0, pubkey, createdAt, kind, tags, content), nip01 = true)
            return Hashes.sha256(serialized.toByteArray(Charsets.UTF_8)).toHex()
        }

        fun sign(
            secretKey: ByteArray,
            kind: Int,
            content: String,
            tags: List<List<String>> = emptyList(),
            createdAt: Long = nowSeconds(),
        ): Event {
            val pub = Secp.provider.xOnlyPublicKey(secretKey).toHex()
            val id = computeId(pub, createdAt, kind, tags, content)
            val sig = Secp.provider.signSchnorr(id.hexToBytes(), secretKey, Rand.bytes(32)).toHex()
            return Event(id, pub, createdAt, kind, tags, content, sig)
        }

        /** Unsigned event (NIP-59 "rumor"). */
        fun unsigned(pubkey: String, kind: Int, content: String, tags: List<List<String>>, createdAt: Long = nowSeconds()): Event =
            Event(computeId(pubkey, createdAt, kind, tags, content), pubkey, createdAt, kind, tags, content, "")

        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>): Event {
            val tags = (m["tags"] as? List<Any?>).orEmpty().mapNotNull { t ->
                (t as? List<Any?>)?.map { it?.toString() ?: "" }
            }
            return Event(
                id = m.str("id") ?: "",
                pubkey = m.str("pubkey") ?: throw IllegalArgumentException("missing pubkey"),
                createdAt = m.long("created_at") ?: throw IllegalArgumentException("missing created_at"),
                kind = (m.long("kind") ?: throw IllegalArgumentException("missing kind")).toInt(),
                tags = tags,
                content = m.str("content") ?: "",
                sig = m.str("sig") ?: "",
            )
        }

        fun fromJson(json: String): Event = fromMap(Json.parseObject(json))

        fun nowSeconds(): Long = System.currentTimeMillis() / 1000
    }
}

/** Event kinds amend uses. */
object Kinds {
    const val METADATA = 0
    const val REACTION = 7
    const val SEAL = 13
    const val CHAT_MESSAGE = 14
    const val FILE_MESSAGE = 15
    /** amend delivery / read receipt (only ever sent gift-wrapped; other apps ignore it). */
    const val RECEIPT = 1405
    const val GIFT_WRAP = 1059
    const val DM_RELAYS = 10050
    const val CLIENT_AUTH = 22242
}
