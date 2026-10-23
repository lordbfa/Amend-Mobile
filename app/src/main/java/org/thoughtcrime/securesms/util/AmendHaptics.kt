package org.thoughtcrime.securesms.util

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * amend: small, consistent haptic vocabulary used across the app.
 *
 * All calls go through [View.performHapticFeedback], so they follow the user's
 * system-wide "touch feedback" setting automatically.
 */
object AmendHaptics {

    /** A light tick: crossing a swipe threshold, toggling something small. */
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** A crisp confirmation: a message was sent, a swipe action was committed. */
    fun confirm(view: View) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }
        view.performHapticFeedback(constant)
    }

    /** A heavier thud: long-press menus, reactions. */
    fun heavy(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}
