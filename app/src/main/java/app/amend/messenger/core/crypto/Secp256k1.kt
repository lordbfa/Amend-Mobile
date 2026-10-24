package app.amend.messenger.core.crypto

import java.math.BigInteger

/**
 * The secp256k1 operations amend needs: x-only public keys, BIP-340 Schnorr
 * signatures and the raw ECDH x-coordinate used by NIP-44.
 *
 * On Android a native libsecp256k1 provider is installed at start-up
 * (see `NativeSecp`); [PureSecp256k1] is the portable fallback and the
 * reference used by the unit tests.
 */
interface SecpProvider {
    /** 32-byte x-only public key for a 32-byte secret key. */
    fun xOnlyPublicKey(secretKey: ByteArray): ByteArray

    /** 64-byte BIP-340 signature of a 32-byte message. */
    fun signSchnorr(message32: ByteArray, secretKey: ByteArray, auxRand32: ByteArray): ByteArray

    fun verifySchnorr(signature64: ByteArray, message32: ByteArray, xOnlyPub: ByteArray): Boolean

    /** x-coordinate (32 bytes) of secretKey * lift_x(xOnlyPub). */
    fun sharedX(secretKey: ByteArray, xOnlyPub: ByteArray): ByteArray

    fun isValidSecretKey(secretKey: ByteArray): Boolean
}

object Secp {
    @Volatile
    var provider: SecpProvider = PureSecp256k1
}

/**
 * Straightforward BigInteger implementation of secp256k1 (Jacobian coordinates).
 * Correct, but not constant-time — the native provider is preferred whenever it loads.
 */
object PureSecp256k1 : SecpProvider {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val SEVEN = BigInteger.valueOf(7)
    private val SQRT_EXP = P.add(BigInteger.ONE).shiftRight(2)

    /** Affine point; null represents infinity. */
    private data class Point(val x: BigInteger, val y: BigInteger)

    /** Jacobian point (X/Z^2, Y/Z^3); z == 0 is infinity. */
    private class Jac(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
        val isInfinity get() = z.signum() == 0
    }

    private val G = Point(GX, GY)
    private val INF = Jac(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private fun BigInteger.modP() = mod(P)

    private fun toJac(p: Point) = Jac(p.x, p.y, BigInteger.ONE)

    private fun toAffine(j: Jac): Point? {
        if (j.isInfinity) return null
        val zInv = j.z.modInverse(P)
        val zInv2 = zInv.multiply(zInv).modP()
        return Point(j.x.multiply(zInv2).modP(), j.y.multiply(zInv2).multiply(zInv).modP())
    }

    private fun double(p: Jac): Jac {
        if (p.isInfinity || p.y.signum() == 0) return INF
        val ysq = p.y.multiply(p.y).modP()
        val s = BigInteger.valueOf(4).multiply(p.x).multiply(ysq).modP()
        val m = BigInteger.valueOf(3).multiply(p.x).multiply(p.x).modP() // a = 0
        val nx = m.multiply(m).subtract(s.shiftLeft(1)).modP()
        val ny = m.multiply(s.subtract(nx)).subtract(BigInteger.valueOf(8).multiply(ysq).multiply(ysq)).modP()
        val nz = BigInteger.valueOf(2).multiply(p.y).multiply(p.z).modP()
        return Jac(nx, ny, nz)
    }

    private fun add(p: Jac, q: Jac): Jac {
        if (p.isInfinity) return q
        if (q.isInfinity) return p
        val z1z1 = p.z.multiply(p.z).modP()
        val z2z2 = q.z.multiply(q.z).modP()
        val u1 = p.x.multiply(z2z2).modP()
        val u2 = q.x.multiply(z1z1).modP()
        val s1 = p.y.multiply(q.z).multiply(z2z2).modP()
        val s2 = q.y.multiply(p.z).multiply(z1z1).modP()
        if (u1 == u2) {
            return if (s1 != s2) INF else double(p)
        }
        val h = u2.subtract(u1).modP()
        val h2 = h.multiply(h).modP()
        val h3 = h.multiply(h2).modP()
        val r = s2.subtract(s1).modP()
        val u1h2 = u1.multiply(h2).modP()
        val nx = r.multiply(r).subtract(h3).subtract(u1h2.shiftLeft(1)).modP()
        val ny = r.multiply(u1h2.subtract(nx)).subtract(s1.multiply(h3)).modP()
        val nz = h.multiply(p.z).multiply(q.z).modP()
        return Jac(nx, ny, nz)
    }

    private fun multiply(k: BigInteger, p: Point): Point? {
        var result = INF
        var addend = toJac(p)
        var n = k.mod(N)
        // simple double-and-add, LSB first
        while (n.signum() > 0) {
            if (n.testBit(0)) result = add(result, addend)
            addend = double(addend)
            n = n.shiftRight(1)
        }
        return toAffine(result)
    }

    private fun liftX(x: BigInteger): Point? {
        if (x >= P) return null
        val c = x.modPow(BigInteger.valueOf(3), P).add(SEVEN).modP()
        val y = c.modPow(SQRT_EXP, P)
        if (y.multiply(y).modP() != c) return null
        return Point(x, if (y.testBit(0)) P.subtract(y) else y)
    }

    override fun isValidSecretKey(secretKey: ByteArray): Boolean {
        if (secretKey.size != 32) return false
        val d = secretKey.toPositiveBigInt()
        return d.signum() > 0 && d < N
    }

    override fun xOnlyPublicKey(secretKey: ByteArray): ByteArray {
        require(isValidSecretKey(secretKey)) { "invalid secret key" }
        return multiply(secretKey.toPositiveBigInt(), G)!!.x.to32Bytes()
    }

    override fun signSchnorr(message32: ByteArray, secretKey: ByteArray, auxRand32: ByteArray): ByteArray {
        require(message32.size == 32 && auxRand32.size == 32)
        require(isValidSecretKey(secretKey)) { "invalid secret key" }
        val d0 = secretKey.toPositiveBigInt()
        val pub = multiply(d0, G)!!
        val d = if (pub.y.testBit(0)) N.subtract(d0) else d0
        val pBytes = pub.x.to32Bytes()
        val auxHash = Hashes.taggedHash("BIP0340/aux", auxRand32)
        val t = d.to32Bytes()
        for (i in t.indices) t[i] = (t[i].toInt() xor auxHash[i].toInt()).toByte()
        val k0 = Hashes.taggedHash("BIP0340/nonce", t, pBytes, message32).toPositiveBigInt().mod(N)
        check(k0.signum() != 0) { "nonce is zero" }
        val r = multiply(k0, G)!!
        val k = if (r.y.testBit(0)) N.subtract(k0) else k0
        val rBytes = r.x.to32Bytes()
        val e = Hashes.taggedHash("BIP0340/challenge", rBytes, pBytes, message32).toPositiveBigInt().mod(N)
        val s = k.add(e.multiply(d)).mod(N)
        val sig = rBytes + s.to32Bytes()
        check(verifySchnorr(sig, message32, pBytes)) { "signature self-check failed" }
        return sig
    }

    override fun verifySchnorr(signature64: ByteArray, message32: ByteArray, xOnlyPub: ByteArray): Boolean {
        if (signature64.size != 64 || xOnlyPub.size != 32) return false
        val pub = liftX(xOnlyPub.toPositiveBigInt()) ?: return false
        val r = signature64.copyOfRange(0, 32).toPositiveBigInt()
        val s = signature64.copyOfRange(32, 64).toPositiveBigInt()
        if (r >= P || s >= N) return false
        val e = Hashes.taggedHash("BIP0340/challenge", signature64.copyOfRange(0, 32), xOnlyPub, message32)
            .toPositiveBigInt().mod(N)
        val sG = multiply(s, G)
        val eP = multiply(N.subtract(e), pub)
        val rPoint = toAffine(
            add(
                if (sG == null) INF else toJac(sG),
                if (eP == null) INF else toJac(eP)
            )
        ) ?: return false
        return !rPoint.y.testBit(0) && rPoint.x == r
    }

    override fun sharedX(secretKey: ByteArray, xOnlyPub: ByteArray): ByteArray {
        require(isValidSecretKey(secretKey)) { "invalid secret key" }
        val pub = liftX(xOnlyPub.toPositiveBigInt()) ?: throw IllegalArgumentException("invalid public key")
        return multiply(secretKey.toPositiveBigInt(), pub)!!.x.to32Bytes()
    }
}
