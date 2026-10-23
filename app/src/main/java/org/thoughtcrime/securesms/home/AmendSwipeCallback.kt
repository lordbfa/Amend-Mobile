package org.thoughtcrime.securesms.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.util.AmendHaptics
import kotlin.math.abs
import kotlin.math.min

/**
 * amend: swipe actions for the conversation list.
 *
 *  - swipe right -> pin / unpin
 *  - swipe left  -> mark read / mark unread
 *
 * The row springs back after the action, so nothing is removed from the list.
 * A haptic tick fires when the finger crosses the commit threshold, and a
 * confirmation haptic fires when the action is committed.
 */
class AmendSwipeCallback(
    context: Context,
    private val onTogglePin: (ThreadRecord) -> Unit,
    private val onToggleRead: (ThreadRecord) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val threshold = 0.32f

    private val pinColor = ContextCompat.getColor(context, R.color.amend_dark_4)
    private val readColor = ContextCompat.getColor(context, R.color.accent_amend)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics)
    }
    private val iconSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, context.resources.displayMetrics).toInt()
    private val margin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, context.resources.displayMetrics)
    private val corner = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, context.resources.displayMetrics)
    private val inset = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, context.resources.displayMetrics)

    private val iconPin = ContextCompat.getDrawable(context, R.drawable.ic_pin)?.mutate()
    private val iconUnpin = ContextCompat.getDrawable(context, R.drawable.ic_pin_off)?.mutate()
    private val iconRead = ContextCompat.getDrawable(context, R.drawable.ic_mail_open)?.mutate()
    private val iconUnread = ContextCompat.getDrawable(context, R.drawable.ic_mail)?.mutate()

    private val labelPin = context.getString(R.string.amendSwipePin)
    private val labelUnpin = context.getString(R.string.amendSwipeUnpin)
    private val labelRead = context.getString(R.string.amendSwipeRead)
    private val labelUnread = context.getString(R.string.amendSwipeUnread)

    // Tracks whether we already fired the "threshold crossed" tick for the current gesture
    private var armedHolderId: Long = RecyclerView.NO_ID

    private fun threadOf(holder: RecyclerView.ViewHolder): ThreadRecord? =
        (holder as? HomeAdapter.ConversationViewHolder)?.view?.thread

    private fun isSwipeable(thread: ThreadRecord): Boolean {
        val recipient = thread.recipient
        return recipient.address !is Address.CommunityBlindedId && !recipient.isLegacyGroupRecipient
    }

    private fun hasUnread(thread: ThreadRecord) = thread.unreadCount > 0 || thread.isUnread

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        val thread = threadOf(viewHolder) ?: return 0
        if (!isSwipeable(thread)) return 0
        return makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = threshold

    // Make it a deliberate gesture: a quick fling alone shouldn't trigger actions
    override fun getSwipeEscapeVelocity(defaultValue: Float): Float = defaultValue * 4f

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val thread = threadOf(viewHolder)
        val view = viewHolder.itemView
        if (thread != null) {
            if (direction == ItemTouchHelper.RIGHT) onTogglePin(thread) else onToggleRead(thread)
            AmendHaptics.confirm(view)
        }
        armedHolderId = RecyclerView.NO_ID
        // Spring the row back into place; the list itself updates from the database.
        val position = viewHolder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            viewHolder.bindingAdapter?.notifyItemChanged(position)
        }
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val thread = threadOf(viewHolder)
        val item = viewHolder.itemView
        if (thread == null || actionState != ItemTouchHelper.ACTION_STATE_SWIPE || dX == 0f) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            return
        }

        val width = item.width.toFloat()
        val progress = min(1f, abs(dX) / (width * threshold))

        // Haptic tick the moment the gesture becomes "committed"
        if (isCurrentlyActive) {
            if (progress >= 1f && armedHolderId != viewHolder.itemId) {
                armedHolderId = viewHolder.itemId
                AmendHaptics.tick(item)
            } else if (progress < 1f && armedHolderId == viewHolder.itemId) {
                armedHolderId = RecyclerView.NO_ID
            }
        }

        val swipingRight = dX > 0
        val (color, icon, label) = if (swipingRight) {
            if (thread.isPinned) Triple(pinColor, iconUnpin, labelUnpin) else Triple(pinColor, iconPin, labelPin)
        } else {
            if (hasUnread(thread)) Triple(readColor, iconRead, labelRead) else Triple(readColor, iconUnread, labelUnread)
        }

        // Rounded "pill" that grows with the gesture
        bgPaint.color = color
        bgPaint.alpha = (110 + 145 * progress).toInt().coerceIn(0, 255)
        val rect = if (swipingRight) {
            RectF(item.left + inset, item.top + inset, item.left + dX, item.bottom - inset)
        } else {
            RectF(item.right + dX, item.top + inset, item.right - inset, item.bottom - inset)
        }
        c.drawRoundRect(rect, corner, corner, bgPaint)

        // Icon pops (scales up) as it reaches the threshold
        val scale = 0.6f + 0.4f * progress + if (progress >= 1f) 0.15f else 0f
        val size = (iconSize * scale).toInt()
        val cy = (item.top + item.bottom) / 2
        val labelWidth = textPaint.measureText(label)
        val gap = margin / 3f
        if (icon != null) {
            icon.setTint(0xFFFFFFFF.toInt())
            val left = if (swipingRight) (item.left + margin).toInt() else (item.right - margin - size).toInt()
            icon.setBounds(left, cy - size / 2, left + size, cy + size / 2)
            icon.alpha = (255 * progress).toInt().coerceIn(0, 255)
            // Only draw while it fits inside the revealed area
            if (abs(dX) > margin + size) icon.draw(c)
        }
        textPaint.alpha = (255 * progress).toInt().coerceIn(0, 255)
        val textX = if (swipingRight) item.left + margin + iconSize + gap else item.right - margin - iconSize - gap - labelWidth
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        if (abs(dX) > margin + iconSize + gap + labelWidth) c.drawText(label, textX, textY, textPaint)

        // Slight rubber-band resistance past the threshold
        val maxTravel = width * 0.55f
        val resisted = if (abs(dX) > maxTravel) {
            val extra = abs(dX) - maxTravel
            (maxTravel + extra * 0.25f) * if (swipingRight) 1 else -1
        } else dX
        super.onChildDraw(c, recyclerView, viewHolder, resisted, dY, actionState, isCurrentlyActive)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        armedHolderId = RecyclerView.NO_ID
    }
}
