package app.amend.messenger.core.crypto

/** ChaCha20 stream cipher (RFC 8439), as used by NIP-44 v2. */
object ChaCha20 {

    private fun rotl(v: Int, c: Int) = (v shl c) or (v ushr (32 - c))

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
    }

    private fun block(key: ByteArray, counter: Int, nonce: ByteArray, out: ByteArray) {
        val init = IntArray(16)
        init[0] = 0x61707865; init[1] = 0x3320646e; init[2] = 0x79622d32; init[3] = 0x6b206574
        for (i in 0 until 8) init[4 + i] = le32(key, i * 4)
        init[12] = counter
        for (i in 0 until 3) init[13 + i] = le32(nonce, i * 4)
        val s = init.copyOf()
        repeat(10) {
            quarter(s, 0, 4, 8, 12); quarter(s, 1, 5, 9, 13)
            quarter(s, 2, 6, 10, 14); quarter(s, 3, 7, 11, 15)
            quarter(s, 0, 5, 10, 15); quarter(s, 1, 6, 11, 12)
            quarter(s, 2, 7, 8, 13); quarter(s, 3, 4, 9, 14)
        }
        for (i in 0 until 16) {
            val v = s[i] + init[i]
            out[i * 4] = v.toByte()
            out[i * 4 + 1] = (v ushr 8).toByte()
            out[i * 4 + 2] = (v ushr 16).toByte()
            out[i * 4 + 3] = (v ushr 24).toByte()
        }
    }

    /** XORs [data] with the keystream; encryption and decryption are the same operation. */
    fun xor(key: ByteArray, nonce: ByteArray, data: ByteArray, initialCounter: Int = 0): ByteArray {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(nonce.size == 12) { "ChaCha20 nonce must be 12 bytes" }
        val out = ByteArray(data.size)
        val ks = ByteArray(64)
        var counter = initialCounter
        var off = 0
        while (off < data.size) {
            block(key, counter, nonce, ks)
            val n = minOf(64, data.size - off)
            for (i in 0 until n) out[off + i] = (data[off + i].toInt() xor ks[i].toInt()).toByte()
            off += n
            counter++
        }
        return out
    }
}
