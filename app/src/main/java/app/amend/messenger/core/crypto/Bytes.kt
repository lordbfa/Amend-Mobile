package app.amend.messenger.core.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0f]
    }
    return String(out)
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have even length" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "invalid hex" }
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

fun String.isHex64(): Boolean = length == 64 && all { Character.digit(it, 16) >= 0 }

object Hashes {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /** HKDF-Extract (RFC 5869). */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

    /** HKDF-Expand (RFC 5869). */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            previous = hmacSha256(prk, previous, info, byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - written)
            System.arraycopy(previous, 0, out, written, n)
            written += n
            counter++
        }
        return out
    }

    /** BIP-340 tagged hash. */
    fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
        val t = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(t, t, *parts)
    }

    /** Constant-time comparison. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

object Rand {
    private val random = SecureRandom()
    fun bytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }
    fun int(bound: Int): Int = random.nextInt(bound)
}

/** Unsigned big-endian 32-byte encoding. */
internal fun BigInteger.to32Bytes(): ByteArray {
    val raw = toByteArray()
    return when {
        raw.size == 32 -> raw
        raw.size == 33 && raw[0] == 0.toByte() -> raw.copyOfRange(1, 33)
        raw.size < 32 -> ByteArray(32 - raw.size) + raw
        else -> throw IllegalArgumentException("number too large for 32 bytes")
    }
}

internal fun ByteArray.toPositiveBigInt(): BigInteger = BigInteger(1, this)
