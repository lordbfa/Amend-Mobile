package app.amend.messenger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class ParticleKind(val colors: List<Color>) {
    LEAVES(listOf(Color(0xFF5BE39B), Color(0xFF2BB673), Color(0xFF9BE15D), Color(0xFF3FA34D))),
    PETALS(listOf(Color(0xFFFF9EBB), Color(0xFFFFC4D6), Color(0xFFFF7AA2))),
    SPARKS(listOf(Color(0xFFFFE58A), Color(0xFFFFC145), Color(0xFFFFFFFF))),
}

internal class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    var rot: Float, val spin: Float, val size: Float, val color: Color,
    var life: Float, val maxLife: Float, val kind: ParticleKind,
)

/** Holds live particles. Call [burst] from any event handler. */
@Stable
class ParticleField {
    private val particles = ArrayList<Particle>()
    internal val frame = mutableLongStateOf(0L)
    var reduced = false

    fun burst(at: Offset, kind: ParticleKind = ParticleKind.LEAVES, count: Int = 24, power: Float = 1f) {
        val n = if (reduced) count / 4 else count
        repeat(n) {
            val angle = Random.nextFloat() * 6.283f
            val speed = (180f + Random.nextFloat() * 420f) * power
            particles.add(
                Particle(
                    at.x, at.y,
                    cos(angle) * speed, sin(angle) * speed - 260f * power,
                    Random.nextFloat() * 360f, (Random.nextFloat() - 0.5f) * 720f,
                    8f + Random.nextFloat() * 10f,
                    kind.colors.random(),
                    0f, 1.1f + Random.nextFloat() * 0.9f, kind,
                )
            )
        }
    }

    /** Gentle falling leaves from the top edge (ambient). */
    fun drift(width: Float, kind: ParticleKind = ParticleKind.LEAVES) {
        if (reduced) return
        particles.add(
            Particle(
                Random.nextFloat() * width, -20f, (Random.nextFloat() - 0.5f) * 40f, 40f + Random.nextFloat() * 40f,
                Random.nextFloat() * 360f, (Random.nextFloat() - 0.5f) * 120f, 7f + Random.nextFloat() * 6f,
                kind.colors.random(), 0f, 6f, kind,
            )
        )
    }

    internal fun step(dt: Float, height: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life += dt
            if (p.life > p.maxLife || p.y > height + 40f) { it.remove(); continue }
            val drag = if (p.kind == ParticleKind.SPARKS) 0.96f else 0.985f
            p.vx *= drag
            p.vy = p.vy * drag + 520f * dt * (if (p.kind == ParticleKind.SPARKS) 0.4f else 0.55f)
            // flutter: leaves and petals swing side to side as they fall
            if (p.kind != ParticleKind.SPARKS) p.vx += sin(p.life * 6f + p.size) * 30f * dt * 10f
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.rot += p.spin * dt
        }
    }

    internal fun snapshot(): List<Particle> = particles
    internal val isEmpty get() = particles.isEmpty()
}

@Composable
fun rememberParticleField(): ParticleField = remember { ParticleField() }

/** Draws and animates a [ParticleField]. Place it on top of content (it ignores touches). */
@Composable
fun ParticleLayer(field: ParticleField, modifier: Modifier = Modifier) {
    LaunchedEffect(field) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                field.step(dt, 4000f)
                field.frame.longValue = now
            }
        }
    }
    Canvas(modifier) {
        field.frame.longValue // read to redraw every frame
        field.snapshot().forEach { p ->
            val alpha = (1f - p.life / p.maxLife).coerceIn(0f, 1f)
            rotate(p.rot, Offset(p.x, p.y)) {
                when (p.kind) {
                    ParticleKind.SPARKS -> drawCircle(p.color.copy(alpha = alpha), p.size * 0.35f, Offset(p.x, p.y))
                    else -> drawOval(
                        p.color.copy(alpha = alpha),
                        topLeft = Offset(p.x - p.size, p.y - p.size * 0.45f),
                        size = Size(p.size * 2f, p.size * 0.9f),
                    )
                }
            }
        }
    }
}
