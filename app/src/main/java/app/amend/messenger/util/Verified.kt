package app.amend.messenger.util

import android.content.Context
import android.content.SharedPreferences
import app.amend.messenger.core.crypto.Hashes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Contacts whose security code you checked (in person, by QR or on a call).
 *
 * Only a keyed hash of each contact's key is stored, never the key itself, so this file
 * doesn't reveal who you talk to. The hash includes your own key, so the list is
 * meaningless for any other identity, and it is wiped with the identity.
 */
object Verified {
    const val PREFS = "amend_verified"
    private var prefs: SharedPreferences? = null
    private var me: String = ""
    private val _hashes = MutableStateFlow<Set<String>>(emptySet())
    val hashes: StateFlow<Set<String>> = _hashes

    fun load(context: Context, myKey: String) {
        me = myKey
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _hashes.value = p.getStringSet(slot(), emptySet())?.toSet() ?: emptySet()
    }

    private fun slot() = "v1:" + tag("slot")

    private fun tag(peer: String): String =
        Hashes.sha256("amend-verified-v1".toByteArray(), me.toByteArray(), peer.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(32)

    fun isVerified(peer: String, set: Set<String> = _hashes.value): Boolean = me.isNotEmpty() && tag(peer) in set

    fun set(peer: String, verified: Boolean) {
        if (me.isEmpty()) return
        val next = if (verified) _hashes.value + tag(peer) else _hashes.value - tag(peer)
        _hashes.value = next
        prefs?.edit()?.putStringSet(slot(), next)?.apply()
    }

    fun clear() {
        _hashes.value = emptySet()
        me = ""
    }
}
