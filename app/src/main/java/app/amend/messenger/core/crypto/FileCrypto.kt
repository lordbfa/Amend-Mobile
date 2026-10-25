package app.amend.messenger.core.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts media files before upload (NIP-17 kind-15 "aes-gcm"): a fresh random
 * 256-bit key and 96-bit nonce per file. The key travels only inside the
 * end-to-end encrypted chat message, so the file server stores unreadable bytes.
 */
object FileCrypto {
    class Encrypted(val data: ByteArray, val key: ByteArray, val nonce: ByteArray) {
        /** SHA-256 of the encrypted bytes — the file's address on Blossom servers. */
        val sha256Hex: String by lazy { Hashes.sha256(data).toHex() }
    }

    fun encrypt(plain: ByteArray, key: ByteArray = Rand.bytes(32), nonce: ByteArray = Rand.bytes(12)): Encrypted {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return Encrypted(cipher.doFinal(plain), key, nonce)
    }

    /** Throws if the data was tampered with (GCM authentication). */
    fun decrypt(data: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(data)
    }
}
