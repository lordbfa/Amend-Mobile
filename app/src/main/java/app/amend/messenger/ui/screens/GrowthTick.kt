package app.amend.messenger.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amend.messenger.data.MessageStatus

/*
 * The little tree next to your own messages grows with the message's journey:
 *   🌰 sending   — a grey sprout, swaying while it waits
 *   🌱 sent      — a green sapling (a relay has it)
 *   🌳 delivered — a fuller, deep-green tree (your friend's phone has it)
 *   🌸 seen      — the tree turns blue and blossoms (your friend saw it)
 *   🥀 failed    — a red, drooping sprout: tap to retry
 */

private val SENDING_COLOR = Color(0xFF9AA5A0)
private val SENT_COLOR = Color(0xFF2BB673)
private val DELIVERED_COLOR = Color(0xFF0F6B3A)
private val SEEN_COLOR = Color(0xFF1E88E5)
private val FAILED_COLOR = Color(0xFFD32F2F)
private val TRUNK = Color(0xFF7A5536)
private val BLOSSOM = Color(0xFFFF7AA8)

fun statusLabel(status: MessageStatus, relays: Int): String = when (status) {
    MessageStatus.SENDING -> "Sending…"
    MessageStatus.SENT -> "Sent · stored on $relays relay" + if (relays == 1) "" else "s"
    MessageStatus.DELIVERED -> "Delivered to their phone"
    MessageStatus.SEEN -> "Seen"
    MessageStatus.FAILED -> "Not sent — tap ⚠ to retry"
    MessageStatus.RECEIVED -> ""
}

@Composable
fun GrowthTick(status: MessageStatus, onRetry: () -> Unit) {
    if (status == MessageStatus.RECEIVED) return
    val growth by animateFloatAsState(
        when (status) {
            MessageStatus.SENDING -> 0.3f
            MessageStatus.FAILED -> 0.45f
            MessageStatus.SENT -> 0.6f
            MessageStatus.DELIVERED -> 0.85f
            else -> 1f
        },
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "growth",
    )
    val color by animateColorAsState(
        when (status) {
            MessageStatus.SENDING -> SENDING_COLOR
            MessageStatus.SENT -> SENT_COLOR
            MessageStatus.DELIVERED -> DELIVERED_COLOR
            MessageStatus.SEEN -> SEEN_COLOR
            else -> FAILED_COLOR
        },
        tween(600), label = "treeColor",
    )
    // a little "pop" every time the status moves on
    val pop = remember { Animatable(1f) }
    LaunchedEffect(status) {
        if (status != MessageStatus.SENDING) {
            pop.snapTo(1.45f)
            pop.animateTo(1f, spring(Spring.DampingRatioHighBouncy, Spring.StiffnessMediumLow))
        }
    }
    val waiting = status == MessageStatus.SENDING
    val t = rememberInfiniteTransition(label = "sway")
    val sway by t.animateFloat(-14f, 14f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s")
    val breathe by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "b")
    val gentle by t.animateFloat(-3f, 3f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "g")

    Row(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Color.White.copy(alpha = 0.92f))
            .then(if (status == MessageStatus.FAILED) Modifier.clickable { onRetry() } else Modifier)
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .semantics { contentDescription = statusLabel(status, 0) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            Modifier.size(width = 18.dp, height = 16.dp).graphicsLayer {
                scaleX = pop.value; scaleY = pop.value
                alpha = if (waiting) breathe else 1f
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }
        ) {
            val angle = when {
                waiting -> sway
                status == MessageStatus.FAILED -> 0f
                else -> gentle
            }
            rotate(angle, pivot = Offset(size.width / 2, size.height)) {
                drawSapling(growth, color, wilted = status == MessageStatus.FAILED, blossom = status == MessageStatus.SEEN)
            }
        }
        if (status == MessageStatus.FAILED) {
            Spacer(Modifier.width(2.dp))
            Text("retry", color = FAILED_COLOR, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun DrawScope.drawSapling(g: Float, color: Color, wilted: Boolean, blossom: Boolean) {
    val w = size.width
    val h = size.height
    val cx = w / 2
    val base = h
    val stemTop = base - h * (0.3f + 0.42f * g)
    val stroke = (1.3f + 1.2f * g).dp.toPx()
    // ground
    drawLine(color.copy(alpha = 0.35f), Offset(w * 0.2f, base - 0.5f), Offset(w * 0.8f, base - 0.5f), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
    // trunk: brown once it is a real tree, otherwise the plant's own colour
    drawLine(if (g > 0.75f) TRUNK else color, Offset(cx, base), Offset(cx, stemTop), strokeWidth = stroke, cap = StrokeCap.Round)

    // two leaves (they droop when the message failed)
    val leaf = w * (0.18f + 0.16f * g.coerceAtMost(0.75f))
    val droop = if (wilted) 0.55f else -0.35f
    leafPath(Offset(cx, stemTop + h * 0.12f), leaf, -1f, droop).let { drawPath(it, color) }
    leafPath(Offset(cx, stemTop + h * 0.2f), leaf * 0.9f, 1f, droop).let { drawPath(it, color) }

    // crown grows in for "delivered" and "seen"
    val crown = ((g - 0.7f) / 0.3f).coerceIn(0f, 1f)
    if (crown > 0f) {
        val r = w * 0.32f * crown
        drawCircle(color, r, Offset(cx, stemTop))
        drawCircle(color, r * 0.75f, Offset(cx - r * 0.75f, stemTop + r * 0.35f))
        drawCircle(color, r * 0.75f, Offset(cx + r * 0.75f, stemTop + r * 0.35f))
        drawCircle(Color.White.copy(alpha = 0.35f), r * 0.32f, Offset(cx - r * 0.3f, stemTop - r * 0.3f))
    }
    // blossoms for "seen"
    if (blossom && crown > 0.9f) {
        val r = w * 0.32f
        listOf(Offset(cx - r * 0.55f, stemTop - r * 0.1f), Offset(cx + r * 0.6f, stemTop + r * 0.15f), Offset(cx + r * 0.05f, stemTop - r * 0.65f))
            .forEach { drawCircle(BLOSSOM, w * 0.07f, it) }
    }
}

private fun leafPath(at: Offset, len: Float, side: Float, droop: Float): Path = Path().apply {
    val tip = Offset(at.x + side * len, at.y + droop * len)
    val bulge = len * 0.38f
    moveTo(at.x, at.y)
    quadraticBezierTo(at.x + side * len * 0.5f, at.y + droop * len * 0.5f - bulge, tip.x, tip.y)
    quadraticBezierTo(at.x + side * len * 0.5f, at.y + droop * len * 0.5f + bulge, at.x, at.y)
    close()
}

@Suppress("unused")
private val keepSize = Size.Zero
