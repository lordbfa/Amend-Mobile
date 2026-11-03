package app.amend.messenger.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The one-time age question. It stays on this phone and is never sent anywhere.
 *
 *  - ADULT: everything as normal.
 *  - TEEN (13–17): the stranger shield is on — only people you've accepted, or chats you
 *    started yourself, can message you; the Grove is read-only.
 *  - CHILD (under 13): amend isn't for you (see the Terms of Use).
 */
object AgeCheck {
    enum class Group { UNKNOWN, ADULT, TEEN, CHILD }

    private const val PREFS = "amend_safety"
    private const val KEY = "age_group"

    var group by mutableStateOf(Group.UNKNOWN)
        private set

    val shielded: Boolean get() = group == Group.TEEN

    fun load(context: Context) {
        group = runCatching {
            Group.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "UNKNOWN")
        }.getOrDefault(Group.UNKNOWN)
    }

    fun set(context: Context, g: Group) {
        group = g
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, g.name).apply()
        (context.applicationContext as? app.amend.messenger.AmendApp)?.repository?.value?.blockStrangers = g == Group.TEEN
    }
}
