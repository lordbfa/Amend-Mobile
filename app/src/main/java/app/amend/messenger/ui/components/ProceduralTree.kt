package app.amend.messenger.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import app.amend.messenger.eco.TreeSpecies
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws a procedurally grown tree. Every tree is unique ([seed]) but repeatable,
 * grows smoothly with [progress] (0 = seed, 1 = mature) and sways with [sway]
 * (radians, typically a small oscillating value). [shake] adds a stronger
 * wobble when the user taps the tree.
 */
fun DrawScope.drawTree(
    species: TreeSpecies,
    progress: Float,
    seed: Int,
    base: Offset,
    height: Float,
    sway: Float,
    shake: Float = 0f,
    night: Boolean = false,
) {
    val p = progress.coerceIn(0f, 1f)
    if (p < 0.1f) {
        drawSeedling(base, height, p / 0.1f, sway)
        return
    }
    val g = (p - 0.1f) / 0.9f // 0..1 growth after sprouting
    if (species.conifer) {
        drawConifer(species, g, base, height, sway + shake, night)
        return
    }
    val rnd = Random(seed)
    val depth = max(2, (2 + (species.depth - 2) * g).toInt())
    val trunkLen = height * (0.2f + 0.14f * g)
    val trunkWidth = height * (0.018f + 0.03f * g)
    val trunk = Color.hsl(species.trunkHue, 0.35f, if (night) 0.18f else 0.28f)
    val leafLight = if (night) 0.28f else 0.42f
    val leaves = ArrayList<Pair<Offset, Float>>()

    fun branch(start: Offset, angle: Float, len: Float, width: Float, level: Int) {
        // Sway grows with level, like a real crown moving more than its trunk
        val a = angle + (sway + shake) * (0.15f + level * 0.12f)
        val end = Offset(start.x + cos(a) * len, start.y + sin(a) * len)
        drawLine(trunk, start, end, strokeWidth = width, cap = StrokeCap.Round)
        if (level >= depth) {
            leaves.add(end to len)
            return
        }
        if (level >= depth - 2) leaves.add(end to len * 0.8f)
        val spread = species.branchAngle * (PI.toFloat() / 180f)
        val children = if (rnd.nextFloat() < 0.3f && level > 1) 3 else 2
        for (i in 0 until children) {
            val offset = when (children) {
                2 -> if (i == 0) -spread else spread
                else -> (i - 1) * spread
            }
            val jitter = (rnd.nextFloat() - 0.5f) * spread * 0.5f
            val shrink = species.shrink * (0.88f + rnd.nextFloat() * 0.2f)
            branch(end, a + offset + jitter, len * shrink, width * 0.68f, level + 1)
        }
    }
    branch(base, -PI.toFloat() / 2, trunkLen, trunkWidth, 1)

    // Leaf clusters: several overlapping soft circles per tip
    val leafR = height * 0.045f * species.crown * (0.5f + 0.5f * g)
    val leafRnd = Random(seed * 31 + 7)
    leaves.forEach { (pt, _) ->
        repeat(3) {
            val dx = (leafRnd.nextFloat() - 0.5f) * leafR * 1.6f
            val dy = (leafRnd.nextFloat() - 0.5f) * leafR * 1.6f
            val l = leafLight + (leafRnd.nextFloat() - 0.5f) * 0.12f
            drawCircle(
                Color.hsl(species.leafHue + (leafRnd.nextFloat() - 0.5f) * 16f, species.leafSat, l.coerceIn(0.1f, 0.8f), 0.82f),
                leafR * (0.7f + leafRnd.nextFloat() * 0.6f),
                Offset(pt.x + dx, pt.y + dy),
            )
        }
    }
    // Blossoms once the tree is established
    val bloomHue = species.bloomHue
    if (bloomHue != null && g > 0.55f) {
        val bloomRnd = Random(seed * 13 + 3)
        leaves.forEach { (pt, _) ->
            if (bloomRnd.nextFloat() < 0.6f) {
                drawCircle(
                    Color.hsl(bloomHue, 0.75f, if (night) 0.55f else 0.78f),
                    leafR * 0.38f,
                    Offset(pt.x + (bloomRnd.nextFloat() - 0.5f) * leafR, pt.y + (bloomRnd.nextFloat() - 0.5f) * leafR),
                )
            }
        }
    }
}

private fun DrawScope.drawSeedling(base: Offset, height: Float, t: Float, sway: Float) {
    // mound of soil
    drawOval(
        Color(0xFF6B4630),
        topLeft = Offset(base.x - height * 0.12f, base.y - height * 0.03f),
        size = androidx.compose.ui.geometry.Size(height * 0.24f, height * 0.06f),
    )
    if (t < 0.3f) {
        drawOval(
            Color(0xFF9C6B3F),
            topLeft = Offset(base.x - height * 0.025f, base.y - height * 0.05f),
            size = androidx.compose.ui.geometry.Size(height * 0.05f, height * 0.035f),
        )
        return
    }
    val stemH = height * 0.14f * t
    val tip = Offset(base.x + sway * stemH * 0.6f, base.y - stemH)
    val path = Path().apply {
        moveTo(base.x, base.y - height * 0.02f)
        quadraticBezierTo(base.x - stemH * 0.15f, base.y - stemH * 0.5f, tip.x, tip.y)
    }
    drawPath(path, Color(0xFF5BBF6A), style = androidx.compose.ui.graphics.drawscope.Stroke(width = height * 0.012f, cap = StrokeCap.Round))
    val leaf = height * 0.035f * t
    drawOval(Color(0xFF6FD07E), topLeft = Offset(tip.x - leaf * 1.8f, tip.y - leaf * 0.6f), size = androidx.compose.ui.geometry.Size(leaf * 1.8f, leaf))
    drawOval(Color(0xFF4FB560), topLeft = Offset(tip.x, tip.y - leaf * 0.8f), size = androidx.compose.ui.geometry.Size(leaf * 1.8f, leaf))
}

private fun DrawScope.drawConifer(species: TreeSpecies, g: Float, base: Offset, height: Float, sway: Float, night: Boolean) {
    val h = height * (0.3f + 0.6f * g)
    val trunkW = height * (0.02f + 0.02f * g)
    drawLine(Color.hsl(species.trunkHue, 0.35f, 0.25f), base, Offset(base.x, base.y - h * 0.25f), trunkW, StrokeCap.Round)
    val tiers = 3 + (3 * g).toInt()
    for (i in 0 until tiers) {
        val f = i / tiers.toFloat()
        val tierBase = base.y - h * (0.15f + 0.75f * f)
        val w = h * 0.42f * (1f - f * 0.75f)
        val topY = tierBase - h * 0.32f
        val lean = sway * h * 0.08f * (0.3f + f)
        val path = Path().apply {
            moveTo(base.x - w / 2 + lean * 0.5f, tierBase)
            lineTo(base.x + w / 2 + lean * 0.5f, tierBase)
            lineTo(base.x + lean, topY)
            close()
        }
        drawPath(path, Color.hsl(species.leafHue, species.leafSat, (if (night) 0.18f else 0.30f) + f * 0.08f))
    }
}
