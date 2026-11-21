package app.amend.messenger.util

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small sensitive values in SharedPreferences (SOS contact lists, live-share chats), sealed with
 * a hardware-backed Android Keystore key (AES-256-GCM), so a copy of the app's files doesn't
 * reveal them. Values written by older versions are still read. If the Keystore fails, the value
 * is kept in plain form so SOS keeps working.
 */
object SealedPrefs {
    private const val ALIAS = "amend_prefs_key"
    private const val SUFFIX = ".sealed"

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun seal(name: String, text: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(name.toByteArray()) // a value can't be moved to another key
        Base64.encodeToString(c.iv + c.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }.getOrNull()

    private fun open(name: String, b64: String): String? = runCatching {
        val blob = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        c.updateAAD(name.toByteArray())
        String(c.doFinal(blob, 12, blob.size - 12))
    }.getOrNull()

    fun putSet(p: SharedPreferences, name: String, value: Set<String>) {
        val text = value.joinToString("\n")
        val e = p.edit().remove(name).remove(name + SUFFIX)
        val sealed = seal(name, text)
        if (sealed != null) e.putString(name + SUFFIX, sealed) else e.putString(name, text)
        e.apply()
    }

    fun getSet(p: SharedPreferences, name: String): Set<String> {
        p.getString(name + SUFFIX, null)?.let { open(name, it) }
            ?.let { s -> return s.split('\n').filter { it.isNotEmpty() }.toSet() }
        // written by older versions (StringSet) or the plain fallback (String)
        return runCatching { p.getStringSet(name, null) }.getOrNull()
            ?: runCatching { p.getString(name, null) }.getOrNull()
                ?.split('\n')?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()
    }
}
