package app.amend.messenger.util

import android.content.Context
import android.content.Intent
import app.amend.messenger.core.crypto.Rand
import app.amend.messenger.core.crypto.toHex
import java.security.MessageDigest

/**
 * Proves that an Intent to MainActivity came from one of amend's own notifications.
 * MainActivity has to be exported (it's the launcher), so any app can start it with any
 * extras — including "answer the ringing call" or "open this chat". amend's notifications
 * carry a random token that lives only in amend's private storage and inside its own
 * immutable PendingIntents, which other apps can't read; extras without it are ignored.
 */
object IntentAuth {
    const val EXTRA = "amend_token"
    private const val PREFS = "amend_intent"
    @Volatile private var cached: String? = null

    private fun token(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val t = p.getString("token", null) ?: Rand.bytes(32).toHex().also { p.edit().putString("token", it).apply() }
            cached = t
            return t
        }
    }

    /** New token (on sign-out): notifications from the old identity stop working. */
    fun reset(context: Context) {
        synchronized(this) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            cached = null
        }
    }

    /** Marks an Intent amend builds for its own notifications. */
    fun sign(context: Context, intent: Intent): Intent = intent.putExtra(EXTRA, token(context))

    /** True only for Intents amend built itself (compared in constant time). */
    fun isOurs(context: Context, intent: Intent?): Boolean {
        val got = runCatching { intent?.getStringExtra(EXTRA) }.getOrNull() ?: return false
        return MessageDigest.isEqual(got.toByteArray(), token(context).toByteArray())
    }
}
