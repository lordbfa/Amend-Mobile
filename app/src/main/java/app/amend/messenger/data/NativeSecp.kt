package app.amend.messenger.data

import app.amend.messenger.core.crypto.PureSecp256k1
import app.amend.messenger.core.crypto.Secp
import app.amend.messenger.core.crypto.SecpProvider
import fr.acinq.secp256k1.Secp256k1

/**
 * secp256k1 backed by the native, constant-time libsecp256k1 (via ACINQ's bindings).
 * Installed at app start; if the native library can't load, amend keeps the
 * pure-Kotlin implementation.
 */
object NativeSecp : SecpProvider {
    private val EVEN_PREFIX = byteArrayOf(0x02)

    fun install(): Boolean = try {
        val probe = ByteArray(32).also { it[31] = 1 }
        val ok = xOnlyPublicKey(probe).contentEquals(PureSecp256k1.xOnlyPublicKey(probe))
        if (ok) Secp.provider = this
        ok
    } catch (t: Throwable) {
        false
    }

    override fun xOnlyPublicKey(secretKey: ByteArray): ByteArray =
        Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(secretKey)).copyOfRange(1, 33)

    override fun signSchnorr(message32: ByteArray, secretKey: ByteArray, auxRand32: ByteArray): ByteArray =
        Secp256k1.signSchnorr(message32, secretKey, auxRand32)

    override fun verifySchnorr(signature64: ByteArray, message32: ByteArray, xOnlyPub: ByteArray): Boolean =
        try {
            Secp256k1.verifySchnorr(signature64, message32, xOnlyPub)
        } catch (e: Exception) {
            false
        }

    override fun sharedX(secretKey: ByteArray, xOnlyPub: ByteArray): ByteArray =
        Secp256k1.pubKeyTweakMul(EVEN_PREFIX + xOnlyPub, secretKey).copyOfRange(1, 33)

    override fun isValidSecretKey(secretKey: ByteArray): Boolean =
        secretKey.size == 32 && try {
            Secp256k1.secKeyVerify(secretKey)
        } catch (e: Exception) {
            false
        }
}
