package app.amend.messenger.core.crypto

import java.util.Base64

/**
 * NIP-44 version 2 payload encryption:
 * secp256k1 ECDH + HKDF-SHA256 + ChaCha20 + HMAC-SHA256, with length padding.
 */
object Nip44 {
    private const val VERSION: Byte = 2
    private val SALT = "nip44-v2".toByteArray(Charsets.UTF_8)
    const val MIN_PLAINTEXT = 1
    const val MAX_PLAINTEXT = 65535

    class Nip44Exception(message: String) : Exception(message)

    fun conversationKey(secretKey: ByteArray, otherXOnlyPub: ByteArray): ByteArray =
        Hashes.hkdfExtract(SALT, Secp.provider.sharedX(secretKey, otherXOnlyPub))

    private class MessageKeys(val chachaKey: ByteArray, val chachaNonce: ByteArray, val hmacKey: ByteArray)

    private fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): MessageKeys {
        if (conversationKey.size != 32) throw Nip44Exception("invalid conversation key length")
        if (nonce.size != 32) throw Nip44Exception("invalid nonce length")
        val keys = Hashes.hkdfExpand(conversationKey, nonce, 76)
        return MessageKeys(keys.copyOfRange(0, 32), keys.copyOfRange(32, 44), keys.copyOfRange(44, 76))
    }

    fun calcPaddedLen(unpaddedLen: Int): Int {
        if (unpaddedLen <= 0) throw Nip44Exception("expected positive length")
        if (unpaddedLen <= 32) return 32
        val nextPower = 1 shl (32 - Integer.numberOfLeadingZeros(unpaddedLen - 1))
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpaddedLen - 1) / chunk + 1)
    }

    private fun pad(plaintext: String): ByteArray {
        val unpadded = plaintext.toByteArray(Charsets.UTF_8)
        val len = unpadded.size
        if (len < MIN_PLAINTEXT || len > MAX_PLAINTEXT) throw Nip44Exception("invalid plaintext length")
        val out = ByteArray(2 + calcPaddedLen(len))
        out[0] = (len ushr 8).toByte()
        out[1] = len.toByte()
        System.arraycopy(unpadded, 0, out, 2, len)
        return out
    }

    private fun unpad(padded: ByteArray): String {
        val len = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        if (len < MIN_PLAINTEXT || len > MAX_PLAINTEXT || padded.size != 2 + calcPaddedLen(len) || 2 + len > padded.size) {
            throw Nip44Exception("invalid padding")
        }
        return String(padded, 2, len, Charsets.UTF_8)
    }

    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray = Rand.bytes(32)): String {
        val keys = messageKeys(conversationKey, nonce)
        val ciphertext = ChaCha20.xor(keys.chachaKey, keys.chachaNonce, pad(plaintext))
        val mac = Hashes.hmacSha256(keys.hmacKey, nonce, ciphertext)
        val payload = ByteArray(1 + 32 + ciphertext.size + 32)
        payload[0] = VERSION
        System.arraycopy(nonce, 0, payload, 1, 32)
        System.arraycopy(ciphertext, 0, payload, 33, ciphertext.size)
        System.arraycopy(mac, 0, payload, 33 + ciphertext.size, 32)
        return Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(payload: String, conversationKey: ByteArray): String {
        if (payload.isEmpty() || payload[0] == '#') throw Nip44Exception("unknown encryption version")
        if (payload.length < 132 || payload.length > 87472) throw Nip44Exception("invalid payload size")
        val data = try {
            Base64.getDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            throw Nip44Exception("invalid base64")
        }
        if (data.size < 99 || data.size > 65603) throw Nip44Exception("invalid data size")
        if (data[0] != VERSION) throw Nip44Exception("unknown encryption version ${data[0]}")
        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)
        val keys = messageKeys(conversationKey, nonce)
        val expected = Hashes.hmacSha256(keys.hmacKey, nonce, ciphertext)
        if (!Hashes.constantTimeEquals(expected, mac)) throw Nip44Exception("invalid MAC")
        return unpad(ChaCha20.xor(keys.chachaKey, keys.chachaNonce, ciphertext))
    }
}
