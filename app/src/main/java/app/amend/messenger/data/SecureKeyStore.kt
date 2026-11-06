package app.amend.messenger.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.amend.messenger.core.nostr.KeyPair
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the user's secret key encrypted at rest with a hardware-backed
 * Android Keystore AES key. The raw key never leaves the device unless the
 * user explicitly reveals their backup phrase (nsec).
 */
class SecureKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("amend_identity", Context.MODE_PRIVATE)

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun hasIdentity(): Boolean = prefs.contains(KEY_BLOB)

    fun save(keys: KeyPair) {
        // Never overwrite a stored identity this phone can't open right now: keep it aside so
        // it isn't lost for good if the Keystore was only failing for a moment.
        val old = prefs.getString(KEY_BLOB, null)
        if (old != null && load() == null) prefs.edit().putString(KEY_UNREADABLE, old).apply()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val blob = cipher.iv + cipher.doFinal(keys.secretKey)
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun load(): KeyPair? {
        val blob = prefs.getString(KEY_BLOB, null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() } ?: return null
        if (blob.size <= 12) return null
        // the Keystore can fail for a moment (busy after boot, OS update): try a few times
        repeat(3) { attempt ->
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
                return KeyPair(cipher.doFinal(blob.copyOfRange(12, blob.size)))
            } catch (e: Exception) {
                if (attempt < 2) Thread.sleep(150L * (attempt + 1))
            }
        }
        return null
    }

    /** An identity is stored but couldn't be opened (see [save]). */
    fun hasUnreadableIdentity(): Boolean = hasIdentity() && load() == null

    fun clear() {
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private companion object {
        const val ALIAS = "amend_identity_key"
        const val KEY_BLOB = "sk_blob"
        /** An identity that couldn't be opened, kept instead of being overwritten. */
        const val KEY_UNREADABLE = "sk_blob_unreadable"
    }
}
