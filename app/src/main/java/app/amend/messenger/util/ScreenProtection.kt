package app.amend.messenger.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Screenshot protection, scoped to what is private:
 *
 *  - Your own screens (chat list, forest, impact, you, settings) can be captured.
 *  - Every chat — 1:1 or shared garden, yours too — and anyone else's profile or forest
 *    can't: Android shows a black frame for screenshots and screen recordings there.
 *
 * Screens that hold someone else's words or profile call [SecureScreen]. The whole system
 * can be switched off in Settings → Privacy & security. While it's on, the Recent apps
 * preview is blank so a chat can't be read from the app switcher either.
 */
object ScreenProtection {
    private const val PREFS = "amend_privacy"
    private const val KEY = "block_screenshots"

    var enabled by mutableStateOf(true)
        private set

    fun load(context: Context) {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun set(context: Context, on: Boolean) {
        enabled = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        context.findActivity()?.let { applyRecents(it) }
    }

    /** Blank preview in Recent apps while protection or the screen lock is on (Android 13+). */
    fun applyRecents(activity: Activity) {
        // with the screen lock on, Recent apps mustn't show what's behind it either
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(!(enabled || AppLock.enabled))
    }

    private val holders = HashMap<Activity, Int>()

    internal fun acquire(activity: Activity) {
        val n = (holders[activity] ?: 0) + 1
        holders[activity] = n
        if (n == 1) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    internal fun release(activity: Activity) {
        val n = (holders[activity] ?: 1) - 1
        if (n <= 0) {
            holders.remove(activity)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            holders[activity] = n
        }
    }
}

/** While this is on screen (and protection is on), screenshots and recordings are blocked. */
@Composable
fun SecureScreen(always: Boolean = false) {
    val activity = LocalContext.current.findActivity() ?: return
    val on = always || ScreenProtection.enabled
    DisposableEffect(activity, on) {
        if (on) ScreenProtection.acquire(activity)
        onDispose { if (on) ScreenProtection.release(activity) }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
