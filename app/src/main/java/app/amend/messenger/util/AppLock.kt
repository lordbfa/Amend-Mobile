package app.amend.messenger.util

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Screen lock: amend asks for your fingerprint, face or phone PIN when it opens, and again
 * after it has been in the background for the chosen time. Uses the phone's own lock, so
 * amend never sees or stores a PIN.
 */
object AppLock {
    private const val PREFS = "amend_lock"
    val TIMEOUTS = listOf(0L, 60_000L, 5 * 60_000L, 30 * 60_000L)

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked

    @Volatile var enabled: Boolean = false
        private set
    @Volatile var timeoutMs: Long = 60_000L
        private set

    /** True while the system unlock prompt is showing, so leaving for it doesn't count. */
    @Volatile var authInProgress = false

    private var backgroundedAt = 0L

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = p.getBoolean("on", false) && deviceSecure(context)
        timeoutMs = p.getLong("timeout", 60_000L)
        // a fresh start of the app is always locked
        if (enabled) _locked.value = true
    }

    /** Whether the phone has a PIN, pattern, password or biometric set up. */
    fun deviceSecure(context: Context): Boolean =
        runCatching { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }.getOrDefault(false)

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on && deviceSecure(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", enabled).apply()
        if (!enabled) _locked.value = false
    }

    fun setTimeout(context: Context, ms: Long) {
        timeoutMs = ms
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("timeout", ms).apply()
    }

    fun onStop() {
        if (enabled && !authInProgress && !_locked.value) backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun onStart(context: Context) {
        if (!enabled) return
        // the phone's lock was removed meanwhile: there is nothing to unlock with
        if (!deviceSecure(context)) { setEnabled(context, false); return }
        if (backgroundedAt > 0 && SystemClock.elapsedRealtime() - backgroundedAt >= timeoutMs) _locked.value = true
    }

    fun unlock() {
        authInProgress = false
        backgroundedAt = 0L
        _locked.value = false
    }
}
