package app.amend.messenger.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Sky colours keyed by hour of day (0..24). Interpolated smoothly. */
private val SKY_STOPS = listOf(
    0f to (Color(0xFF040B1A) to Color(0xFF0B1F2E)),
    5f to (Color(0xFF1B2A4A) to Color(0xFF6B4E71)),
    6.5f to (Color(0xFFFF9A76) to Color(0xFFFFD6A5)),
    9f to (Color(0xFF6EC6F5) to Color(0xFFCFF3FF)),
    15f to (Color(0xFF4FB3EB) to Color(0xFFBDEBFF)),
    18.5f to (Color(0xFFFF8E6E) to Color(0xFFFFC98B)),
    20f to (Color(0xFF3A2B5C) to Color(0xFF9B5B7A)),
    22f to (Color(0xFF0B1630) to Color(0xFF1A2944)),
    24f to (Color(0xFF040B1A) to Color(0xFF0B1F2E)),
)

fun skyColors(hour: Float): Pair<Color, Color> {
    val h = ((hour % 24f) + 24f) % 24f
    for (i in 0 until SKY_STOPS.size - 1) {
        val (h0, c0) = SKY_STOPS[i]
        val (h1, c1) = SKY_STOPS[i + 1]
        if (h in h0..h1) {
            val t = (h - h0) / (h1 - h0)
            return lerp(c0.first, c1.first, t) to lerp(c0.second, c1.second, t)
        }
    }
    return SKY_STOPS.first().second
}

fun isNight(hour: Float) = hour < 5.5f || hour > 20.5f

@Composable
fun rememberHourOfDay(): Float {
    var hour by remember { mutableFloatStateOf(currentHour()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            hour = currentHour()
        }
    }
    return hour
}

private fun currentHour(): Float = LocalTime.now().let { it.hour + it.minute / 60f }

private data class Star(val x: Float, val y: Float, val r: Float, val phase: Float)
private data class Cloud(val y: Float, val scale: Float, val speed: Float, val offset: Float)

/**
 * The animated sky behind amend's screens: gradient by time of day, a sun or
 * moon travelling along its arc, twinkling stars at night and drifting clouds.
 * [energy] (0..1) brightens the scene — it rises with recent chat activity.
 */
@Composable
fun LivingSky(modifier: Modifier = Modifier, hour: Float = rememberHourOfDay(), energy: Float = 0f, animate: Boolean = true) {
    val stars = remember { List(60) { Star(Random.nextFloat(), Random.nextFloat() * 0.7f, 0.6f + Random.nextFloat() * 1.6f, Random.nextFloat() * 6.28f) } }
    val clouds = remember { List(4) { Cloud(0.08f + Random.nextFloat() * 0.35f, 0.7f + Random.nextFloat() * 0.8f, 0.4f + Random.nextFloat(), Random.nextFloat()) } }
    val transition = rememberInfiniteTransition(label = "sky")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (animate) 60_000 else 1_000_000, easing = LinearEasing), RepeatMode.Restart),
        label = "skyTime",
    )
    Canvas(modifier) {
        val (top, bottom) = skyColors(hour)
        drawRect(Brush.verticalGradient(listOf(lerp(top, Color.White, energy * 0.08f), bottom)))

        val night = isNight(hour)
        if (night) {
            stars.forEach { s ->
                val twinkle = 0.5f + 0.5f * sin(t * 2 * PI.toFloat() * 8 + s.phase)
                drawCircle(Color.White.copy(alpha = 0.25f + 0.6f * twinkle), s.r * density * 0.6f, Offset(s.x * size.width, s.y * size.height))
            }
        }
        drawCelestial(hour)
        clouds.forEach { c -> drawCloud(c, t, night) }
    }
}

private fun DrawScope.drawCelestial(hour: Float) {
    val night = isNight(hour)
    // Sun from 6 to 20, moon otherwise; both move along an arc.
    val progress = if (!night) ((hour - 6f) / 14f).coerceIn(0f, 1f)
    else (((hour + 24f - 20.5f) % 24f) / 9f).coerceIn(0f, 1f)
    val angle = PI * (1 - progress)
    val cx = size.width * (0.5f + 0.42f * cos(angle).toFloat())
    val cy = size.height * (0.62f - 0.5f * sin(angle).toFloat())
    if (!night) {
        val r = px(26)
        drawCircle(Brush.radialGradient(listOf(Color(0x66FFE8A3), Color.Transparent), Offset(cx, cy), r * 3.2f), r * 3.2f, Offset(cx, cy))
        drawCircle(Color(0xFFFFE58A), r, Offset(cx, cy))
    } else {
        val r = px(18)
        drawCircle(Brush.radialGradient(listOf(Color(0x33E8F1FF), Color.Transparent), Offset(cx, cy), r * 3f), r * 3f, Offset(cx, cy))
        drawCircle(Color(0xFFEFF4FF), r, Offset(cx, cy))
        drawCircle(Color(0xFFD5DEEF), r * 0.22f, Offset(cx - r * 0.3f, cy - r * 0.2f))
        drawCircle(Color(0xFFD5DEEF), r * 0.15f, Offset(cx + r * 0.35f, cy + r * 0.3f))
    }
}

private fun DrawScope.drawCloud(c: Cloud, t: Float, night: Boolean) {
    val w = size.width
    val x = (((c.offset + t * c.speed) % 1f) * (w + px(240))) - px(120)
    val y = c.y * size.height
    val s = c.scale * px(22)
    val col = if (night) Color(0x22FFFFFF) else Color(0xCCFFFFFF)
    drawCircle(col, s, Offset(x, y))
    drawCircle(col, s * 1.3f, Offset(x + s * 1.2f, y - s * 0.4f))
    drawCircle(col, s * 1.1f, Offset(x + s * 2.4f, y))
    drawCircle(col, s * 0.9f, Offset(x + s * 1.2f, y + s * 0.3f))
}

/** dp → px inside a DrawScope. */
internal fun DrawScope.px(dp: Number): Float = dp.toFloat() * density
