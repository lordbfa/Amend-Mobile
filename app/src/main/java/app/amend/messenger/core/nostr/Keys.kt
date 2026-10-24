package app.amend.messenger.core.nostr

import app.amend.messenger.core.crypto.Nip19
import app.amend.messenger.core.crypto.Rand
import app.amend.messenger.core.crypto.Secp
import app.amend.messenger.core.crypto.toHex

/** A user's identity: a secp256k1 key pair. There are no accounts, phone numbers or emails. */
class KeyPair(val secretKey: ByteArray) {
    init {
        require(Secp.provider.isValidSecretKey(secretKey)) { "invalid secret key" }
    }

    val publicKeyHex: String by lazy { Secp.provider.xOnlyPublicKey(secretKey).toHex() }
    val npub: String get() = Nip19.npub(publicKeyHex)
    val nsec: String get() = Nip19.nsec(secretKey)

    companion object {
        fun generate(): KeyPair {
            while (true) {
                val candidate = Rand.bytes(32)
                if (Secp.provider.isValidSecretKey(candidate)) return KeyPair(candidate)
            }
        }
    }
}
