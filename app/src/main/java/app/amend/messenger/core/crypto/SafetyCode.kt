package app.amend.messenger.core.crypto

import java.security.MessageDigest

/**
 * The security code two people compare to check that nobody is pretending to be either of
 * them: 60 digits (12 groups of 5) worked out from both public keys.
 *
 * Both phones get the same code, because the keys are put in a fixed order first. The hash
 * is repeated thousands of times so that an attacker can't cheaply try millions of fake keys
 * looking for one whose code starts the same way (the same idea as Signal's safety numbers).
 */
object SafetyCode {
    private const val ITERATIONS = 5200
    private const val VERSION = "amend-safety-code-v1"
    private const val VERSION_2 = "amend-safety-code-v2"

    /**
     * [a] and [b] are 64-character hex public keys, in any order. With both people's ratchet
     * identity keys ([ia] for [a], [ib] for [b]; forward-secret chats) the code covers those too
     * (v2), so a changed ratchet identity changes the code.
     */
    fun of(a: String, b: String, ia: ByteArray? = null, ib: ByteArray? = null): String {
        val x = a.lowercase()
        val y = b.lowercase()
        val v2 = ia != null && ib != null
        val (first, second) = if (x <= y) x to y else y to x
        val (firstId, secondId) = if (x <= y) ia to ib else ib to ia
        val fb = first.hexToBytes() + (if (v2) firstId!! else ByteArray(0))
        val sb = second.hexToBytes() + (if (v2) secondId!! else ByteArray(0))
        val md = MessageDigest.getInstance("SHA-512")
        var h = md.digest((if (v2) VERSION_2 else VERSION).toByteArray(Charsets.UTF_8) + fb + sb)
        repeat(ITERATIONS) {
            md.update(h)
            md.update(fb)
            md.update(sb)
            h = md.digest()
        }
        return (0 until GROUPS).joinToString(" ") { i ->
            var v = 0L
            for (j in 0 until 5) v = (v shl 8) or (h[i * 5 + j].toLong() and 0xff)
            (v % 100_000).toString().padStart(5, '0')
        }
    }

    const val GROUPS = 12

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0 && all { Character.digit(it, 16) >= 0 }) { "not hex" }
        return ByteArray(length / 2) { i -> ((Character.digit(this[2 * i], 16) shl 4) or Character.digit(this[2 * i + 1], 16)).toByte() }
    }
}
