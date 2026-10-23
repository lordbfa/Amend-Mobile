package org.thoughtcrime.securesms.conversation.v2.messages

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.OvershootInterpolator
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.util.AmendHaptics

/**
 * amend: playful "pop-in" for messages that arrive or are sent while the
 * conversation is open. Messages already in the history are left alone, and
 * each message animates at most once.
 *
 * Only alpha, scale and translationY are animated, so it never fights the
 * translationX used by swipe-to-reply.
 */
class MessageEntranceAnimator {

    private val openedAt = System.currentTimeMillis()
    private val animated = HashSet<Long>()
    private var lastTickAt = 0L

    fun maybeAnimate(view: View, message: MessageRecord, itemId: Long) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        if (!animated.add(itemId)) return

        val arrivedAt = if (message.isOutgoing) message.dateSent else message.dateReceived
        if (arrivedAt < openedAt) return

        val density = view.resources.displayMetrics.density
        view.post {
            // Grow out of the bubble's "tail" corner
            view.pivotX = if (message.isOutgoing) view.width.toFloat() else 0f
            view.pivotY = view.height.toFloat()
            view.alpha = 0f
            view.scaleX = 0.8f
            view.scaleY = 0.8f
            view.translationY = 28f * density
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(if (message.isOutgoing) 300L else 380L)
                .setInterpolator(OvershootInterpolator(1.8f))
                .withEndAction { reset(view) }
                .start()
        }

        // A gentle tick when someone else's message lands
        // (throttled, so a burst of messages doesn't buzz repeatedly)
        val now = System.currentTimeMillis()
        if (!message.isOutgoing && now - lastTickAt > 700L) {
            lastTickAt = now
            AmendHaptics.tick(view)
        }
    }

    /** Clear any leftover state, e.g. when a view is recycled mid-animation. */
    fun reset(view: View) {
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = 0f
    }
}
