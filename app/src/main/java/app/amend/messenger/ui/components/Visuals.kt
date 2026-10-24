package app.amend.messenger.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import app.amend.messenger.util.rememberAvatar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amend.messenger.data.Profile
import app.amend.messenger.eco.Garden
import app.amend.messenger.net.RelayState
import app.amend.messenger.net.RelayStatus
import app.amend.messenger.ui.theme.LocalDark
import app.amend.messenger.ui.theme.LocalEfficiency
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val AVATAR_EMOJI = listOf("🌿", "🍃", "🌸", "🌻", "🌵", "🍄", "🌾", "🌺", "🍀", "🌼", "🌳", "🪴")

fun hueFor(pubkey: String): Float =
    ((pubkey.take(6).toLongOrNull(16) ?: 0L) % 360L).toFloat()

fun emojiFor(pubkey: String): String =
    AVATAR_EMOJI[((pubkey.takeLast(4).toLongOrNull(16) ?: 0L) % AVATAR_EMOJI.size).toInt()]

/** A gradient "leaf" avatar unique to each public key, optionally with the shared garden badge. */
@Composable
fun LeafAvatar(pubkey: String, profile: Profile?, size: Dp, garden: Garden? = null, modifier: Modifier = Modifier) {
    val hue = (profile?.hue?.takeIf { it >= 0 && !it.isNaN() } ?: hueFor(pubkey)) % 360f
    val emoji = profile?.avatar?.takeIf { it.isNotBlank() } ?: emojiFor(pubkey)
    Box(modifier.size(size)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color.hsl(hue, 0.65f, 0.55f), Color.hsl((hue + 50f) % 360f, 0.7f, 0.38f))
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            val photo by rememberAvatar(profile?.picture)
            val image = photo
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(2.dp).clip(CircleShape),
                )
            } else {
                Text(emoji, fontSize = (size.value * 0.45f).sp)
            }
        }
        if (garden != null && garden.messageCount > 0) {
            GardenBadge(garden, Modifier.align(Alignment.BottomEnd).size(size * 0.46f))
        }
    }
}

/** Tiny animated plant showing a conversation's shared garden. */
@Composable
fun GardenBadge(garden: Garden, modifier: Modifier = Modifier) {
    val dark = LocalDark.current
    val sway by rememberSway(2600)
    Box(
        modifier
            .clip(CircleShape)
            .background(if (dark) Color(0xFF0D2219) else Color.White)
            .border(1.5.dp, Color(0xFF2BB673), CircleShape)
    ) {
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            drawTree(garden.species, garden.progress, garden.seed[1].toInt(), Offset(size.width / 2, size.height * 0.92f), size.height * 1.4f, sway * 0.6f, night = dark)
        }
    }
}

@Composable
fun rememberSway(periodMs: Int = 3200): androidx.compose.runtime.State<Float> {
    val efficiency = LocalEfficiency.current
    val t = rememberInfiniteTransition(label = "sway")
    return t.animateFloat(
        initialValue = -0.06f,
        targetValue = 0.06f,
        animationSpec = infiniteRepeatable(tween(if (efficiency) periodMs * 3 else periodMs), RepeatMode.Reverse),
        label = "swayValue",
    )
}

/** Frosted card used across amend. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, tint: Color = Color.Unspecified, content: @Composable BoxScope.() -> Unit) {
    val dark = LocalDark.current
    val bg = if (tint != Color.Unspecified) tint else if (dark) Color(0xCC0D2219) else Color(0xE6FFFFFF)
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .border(1.dp, if (dark) Color(0x332BB673) else Color(0x222BB673), RoundedCornerShape(24.dp)),
        content = content,
    )
}

/**
 * "Mycelium" view of the relay network: you in the middle, relays around you,
 * with data packets flowing along the threads to every relay that's online.
 */
@Composable
fun MyceliumNetwork(statuses: List<RelayStatus>, modifier: Modifier = Modifier) {
    val efficiency = LocalEfficiency.current
    val t = rememberInfiniteTransition(label = "mycelium")
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (efficiency) 6000 else 2200, easing = LinearEasing)), label = "flow")
    val pulse by t.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")
    val dark = LocalDark.current
    val labelColor = MaterialTheme.colorScheme.onSurface
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val radius = minOf(size.width, size.height) * 0.36f
            val n = statuses.size.coerceAtLeast(1)
            statuses.forEachIndexed { i, s ->
                val a = -PI / 2 + 2 * PI * i / n
                val p = Offset(c.x + radius * cos(a).toFloat(), c.y + radius * sin(a).toFloat())
                val col = when (s.state) {
                    RelayState.ONLINE -> Color(0xFF5BE39B)
                    RelayState.CONNECTING -> Color(0xFFFFC145)
                    RelayState.ERROR -> Color(0xFFFF7A7A)
                    RelayState.OFFLINE -> Color(0xFF6E8A7C)
                }
                // organic thread: a curved line with a slight bend
                val mid = Offset((c.x + p.x) / 2 + (p.y - c.y) * 0.12f, (c.y + p.y) / 2 - (p.x - c.x) * 0.12f)
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x, c.y)
                    quadraticBezierTo(mid.x, mid.y, p.x, p.y)
                }
                drawPath(path, col.copy(alpha = 0.45f), style = Stroke(width = 2.dp.toPx()))
                if (s.state == RelayState.ONLINE) {
                    repeat(3) { k ->
                        val f = (flow + k / 3f + i * 0.13f) % 1f
                        val q = quad(c, mid, p, f)
                        drawCircle(col, 3.dp.toPx(), q)
                    }
                }
                drawCircle(col.copy(alpha = 0.25f), 16.dp.toPx() * (if (s.state == RelayState.ONLINE) pulse else 1f), p)
                drawCircle(col, 8.dp.toPx(), p)
            }
            drawCircle(Brush.radialGradient(listOf(Color(0xFF5BE39B), Color(0xFF1C7A4E)), c, 26.dp.toPx()), 24.dp.toPx() * pulse, c)
            drawCircle(if (dark) Color(0xFF06140E) else Color.White, 12.dp.toPx(), c)
        }
        Text("you", Modifier.align(Alignment.Center), color = labelColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

private fun quad(a: Offset, b: Offset, c: Offset, t: Float): Offset {
    val u = 1 - t
    return Offset(u * u * a.x + 2 * u * t * b.x + t * t * c.x, u * u * a.y + 2 * u * t * b.y + t * t * c.y)
}
