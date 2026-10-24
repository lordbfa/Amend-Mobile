package app.amend.messenger.core.nostr

import app.amend.messenger.core.crypto.Nip44
import app.amend.messenger.core.crypto.Rand
import app.amend.messenger.core.crypto.hexToBytes

/**
 * Private direct messages (NIP-17) using gift wraps (NIP-59).
 *
 *   rumor (kind 14, unsigned)  – the actual message
 *     └ seal (kind 13)         – rumor encrypted to the recipient, signed by the sender
 *         └ gift wrap (1059)   – seal encrypted again with a throw-away key
 *
 * Relays only ever see the gift wrap: a random one-time author, the recipient's
 * key in a "p" tag, and a timestamp randomised up to two days into the past.
 * They can't see who sent it, when exactly, or what it says.
 */
object GiftWrap {
    const val TWO_DAYS = 2 * 24 * 60 * 60L

    private fun randomPast(now: Long): Long = now - Rand.int(TWO_DAYS.toInt())

    fun rumor(senderPub: String, kind: Int, content: String, tags: List<List<String>>, createdAt: Long = Event.nowSeconds()): Event =
        Event.unsigned(senderPub, kind, content, tags, createdAt)

    /** Seal + wrap one rumor for one recipient. */
    fun wrap(rumor: Event, sender: KeyPair, recipientPub: String, now: Long = Event.nowSeconds()): Event {
        require(rumor.pubkey == sender.publicKeyHex) { "rumor author must be the sender" }
        val sealKey = Nip44.conversationKey(sender.secretKey, recipientPub.hexToBytes())
        val seal = Event.sign(
            secretKey = sender.secretKey,
            kind = Kinds.SEAL,
            content = Nip44.encrypt(rumor.toJson(includeSig = false), sealKey),
            tags = emptyList(),
            createdAt = randomPast(now),
        )
        val ephemeral = KeyPair.generate()
        val wrapKey = Nip44.conversationKey(ephemeral.secretKey, recipientPub.hexToBytes())
        return Event.sign(
            secretKey = ephemeral.secretKey,
            kind = Kinds.GIFT_WRAP,
            content = Nip44.encrypt(seal.toJson(), wrapKey),
            tags = listOf(listOf("p", recipientPub)),
            createdAt = randomPast(now),
        )
    }

    class UnwrapException(message: String) : Exception(message)

    /** Opens a gift wrap addressed to [me]. Returns the verified rumor. */
    fun unwrap(wrap: Event, me: KeyPair): Event {
        if (wrap.kind != Kinds.GIFT_WRAP) throw UnwrapException("not a gift wrap")
        if (!wrap.isValid()) throw UnwrapException("invalid gift wrap signature")
        val sealJson = try {
            Nip44.decrypt(wrap.content, Nip44.conversationKey(me.secretKey, wrap.pubkey.hexToBytes()))
        } catch (e: Exception) {
            throw UnwrapException("cannot decrypt gift wrap: ${e.message}")
        }
        val seal = Event.fromJson(sealJson)
        if (seal.kind != Kinds.SEAL || !seal.isValid()) throw UnwrapException("invalid seal")
        val rumorJson = try {
            Nip44.decrypt(seal.content, Nip44.conversationKey(me.secretKey, seal.pubkey.hexToBytes()))
        } catch (e: Exception) {
            throw UnwrapException("cannot decrypt seal: ${e.message}")
        }
        val rumor = Event.fromJson(rumorJson)
        // The seal's signer must be the rumor's author, otherwise someone is impersonating.
        if (rumor.pubkey != seal.pubkey) throw UnwrapException("rumor author does not match seal signer")
        if (!rumor.isValid(requireSignature = false)) throw UnwrapException("rumor id mismatch")
        return rumor
    }
}
