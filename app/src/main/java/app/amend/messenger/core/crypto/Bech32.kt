package app.amend.messenger.core.crypto

import java.io.ByteArrayOutputStream

/** Bech32 (BIP-173) encoding as used for npub / nsec (NIP-19). */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((b ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            out[i] = hrp[i].code ushr 5
            out[i + hrp.length + 1] = hrp[i].code and 31
        }
        return out
    }

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = ByteArrayOutputStream()
        val maxv = (1 shl to) - 1
        for (b in data) {
            val value = b.toInt() and 0xff
            require(value ushr from == 0) { "invalid data range" }
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out.write((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.write((acc shl (to - bits)) and maxv)
        } else {
            require(bits < from && ((acc shl (to - bits)) and maxv) == 0) { "invalid padding" }
        }
        return out.toByteArray()
    }

    fun encode(hrp: String, data: ByteArray): String {
        val values = convertBits(data, 8, 5, true).map { it.toInt() }.toIntArray()
        val mod = polymod(hrpExpand(hrp) + values + IntArray(6)) xor 1
        val sb = StringBuilder(hrp).append('1')
        for (v in values) sb.append(CHARSET[v])
        for (i in 0 until 6) sb.append(CHARSET[(mod ushr (5 * (5 - i))) and 31])
        return sb.toString()
    }

    /** Returns (hrp, data). Throws on malformed input. */
    fun decode(input: String): Pair<String, ByteArray> {
        val s = input.trim()
        require(s == s.lowercase() || s == s.uppercase()) { "mixed case" }
        val str = s.lowercase()
        val pos = str.lastIndexOf('1')
        require(pos >= 1 && pos + 7 <= str.length) { "invalid separator position" }
        val hrp = str.substring(0, pos)
        val values = IntArray(str.length - pos - 1) {
            val c = CHARSET.indexOf(str[pos + 1 + it])
            require(c >= 0) { "invalid character" }
            c
        }
        require(polymod(hrpExpand(hrp) + values) == 1) { "invalid checksum" }
        val data = values.copyOfRange(0, values.size - 6).map { it.toByte() }.toByteArray()
        return hrp to convertBits(data, 5, 8, false)
    }
}

/** NIP-19 helpers. */
object Nip19 {
    fun npub(pubHex: String) = Bech32.encode("npub", pubHex.hexToBytes())
    fun nsec(secret: ByteArray) = Bech32.encode("nsec", secret)

    /** Accepts npub1…, nostr:npub1… or a 64-char hex key. Returns hex or null. */
    fun parsePublicKey(input: String): String? {
        val s = input.trim().removePrefix("nostr:")
        if (s.isHex64()) return s.lowercase()
        return try {
            val (hrp, data) = Bech32.decode(s)
            if (hrp == "npub" && data.size == 32) data.toHex() else null
        } catch (e: Exception) {
            null
        }
    }

    fun parseSecretKey(input: String): ByteArray? {
        val s = input.trim()
        if (s.isHex64()) return s.hexToBytes()
        return try {
            val (hrp, data) = Bech32.decode(s)
            if (hrp == "nsec" && data.size == 32) data else null
        } catch (e: Exception) {
            null
        }
    }

    /** Short, friendly form for UI: npub1abcd…wxyz */
    fun short(pubHex: String): String {
        val n = npub(pubHex)
        return n.take(10) + "…" + n.takeLast(6)
    }
}
