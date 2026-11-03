package app.amend.messenger.data

import app.amend.messenger.core.crypto.isHex64
import app.amend.messenger.core.nostr.Event
import app.amend.messenger.core.nostr.KeyPair

/**
 * amend's shared blocklist. When a report is checked and an account is confirmed to be
 * abusing amend (child exploitation, trafficking, selling drugs, threats…), the amend
 * moderator adds its public key to a public NIP-51 mute list signed by the moderator key
 * below. Every amend app follows that list: those accounts can no longer message anyone
 * on amend, their chats disappear and their public posts are hidden.
 *
 * Only the public key is in the app. The moderator's secret key is never shipped or
 * stored in the repository; it is entered by the moderator on their own phone.
 */
object Moderation {
    /** The amend moderator's public key (hex). */
    const val MODERATOR = "8be50ce80fe302c24bbc4e7813e13da42db35ef9a7436c654f28c1fb5467ecf7"
    /** NIP-51 mute list. */
    const val LIST_KIND = 10000
    const val SUB = "amend-mod"
    /** Upper bound on keys we accept from the list (keeps a bad publish from flooding phones). */
    const val MAX_KEYS = 50_000

    val filter: Map<String, Any?> get() = linkedMapOf("kinds" to listOf(LIST_KIND), "authors" to listOf(MODERATOR), "limit" to 1)

    /** The blocked keys in [e], or null if it isn't a valid list from the moderator. */
    fun parse(e: Event, moderator: String = MODERATOR): Set<String>? {
        if (e.kind != LIST_KIND || e.pubkey != moderator || !e.isValid()) return null
        return e.tagValues("p").map { it.lowercase() }.filter { it.isHex64() && it != moderator }.take(MAX_KEYS).toSet()
    }

    /** Signs a new list (moderator only). */
    fun build(moderator: KeyPair, keys: Collection<String>, expected: String = MODERATOR): Event {
        require(moderator.publicKeyHex == expected) { "not the amend moderator key" }
        val tags = keys.map { it.lowercase() }.filter { it.isHex64() }.distinct().sorted().map { listOf("p", it) }
        return Event.sign(moderator.secretKey, LIST_KIND, "", tags)
    }
}
