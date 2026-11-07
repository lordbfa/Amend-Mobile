package app.amend.messenger.util

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How much motion amend can afford on this phone. On low-memory phones (≤ 3 GB, or ones
 * Android itself calls low-RAM, like the Redmi 9A) the living sky, particles and other
 * looping animations cost more than they give: they take CPU from the keyboard and scrolling
 * and drain the battery. "Lite animations" keeps them still. It's on by default on such
 * phones, and anyone can switch it in Settings → Appearance.
 */
object Performance {
    private const val PREFS = "amend_perf"
    private const val KEY = "lite_motion"

    /** Compose state, so the whole app follows a change at once. */
    var liteMotion by mutableStateOf(false)
        private set

    fun load(context: Context) {
        liteMotion = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, isLowEnd(context))
    }

    fun setLiteMotion(context: Context, on: Boolean) {
        liteMotion = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /** Low-RAM by Android's own flag, or about 3 GB of memory or less. */
    fun isLowEnd(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        if (am.isLowRamDevice) return true
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        // a "3 GB" phone reports a little under 3 GB to apps
        return info.totalMem in 1 until 3_300L * 1024 * 1024
    }
}
