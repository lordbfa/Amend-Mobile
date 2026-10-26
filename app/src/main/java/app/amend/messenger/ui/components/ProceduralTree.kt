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
    val model = TreeModels.get(species, seed, g)
    val trunk = if (night) model.trunkNight else model.trunkDay
    // Positions are recomputed each frame (cheap trig); the shape itself is cached.
    val n = model.count
    val ex = model.scratchX
    val ey = model.scratchY
    val ang = model.scratchA
    val swayAll = sway + shake
    for (i in 0 until n) {
        val parent = model.parent[i]
        val sx = if (parent < 0) base.x else ex[parent]
        val sy = if (parent < 0) base.y else ey[parent]
        val pa = if (parent < 0) 0f else ang[parent]
        val level = model.level[i]
        val a = pa + model.relAngle[i] + swayAll * (0.15f + level * 0.12f)
        val len = model.len[i] * height
        ang[i] = a
        ex[i] = sx + cos(a) * len
        ey[i] = sy + sin(a) * len
        drawLine(trunk, Offset(sx, sy), Offset(ex[i], ey[i]), strokeWidth = model.width[i] * height, cap = StrokeCap.Round)
    }
    val leafR = height * 0.045f * species.crown * (0.5f + 0.5f * g)
    val colors = if (night) model.leafNight else model.leafDay
    for (k in 0 until model.leafCount) {
        val node = model.leafNode[k]
        drawCircle(
            colors[k],
            leafR * model.leafScale[k],
            Offset(ex[node] + model.leafDx[k] * leafR, ey[node] + model.leafDy[k] * leafR),
        )
    }
    if (model.bloomCount > 0 && g > 0.55f) {
        val bloom = if (night) model.bloomNight else model.bloomDay
        for (k in 0 until model.bloomCount) {
            val node = model.bloomNode[k]
            drawCircle(bloom, leafR * 0.38f, Offset(ex[node] + model.bloomDx[k] * leafR, ey[node] + model.bloomDy[k] * leafR))
        }
    }
}

/**
 * The branching shape of one tree at one growth step, built once and reused
 * every frame. Growth is bucketed to 1 % steps so a growing tree rebuilds only
 * when it visibly changes.
 */
internal class TreeModel(
    val depth: Int,
    val count: Int,
    val parent: IntArray,
    val level: IntArray,
    val relAngle: FloatArray,
    val len: FloatArray,      // fraction of the tree height
    val width: FloatArray,    // fraction of the tree height
    val leafCount: Int,
    val leafNode: IntArray,
    val leafDx: FloatArray,
    val leafDy: FloatArray,
    val leafScale: FloatArray,
    val leafDay: Array<Color>,
    val leafNight: Array<Color>,
    val bloomCount: Int,
    val bloomNode: IntArray,
    val bloomDx: FloatArray,
    val bloomDy: FloatArray,
    val bloomDay: Color,
    val bloomNight: Color,
    val trunkDay: Color,
    val trunkNight: Color,
) {
    val scratchX = FloatArray(count)
    val scratchY = FloatArray(count)
    val scratchA = FloatArray(count)
}

internal object TreeModels {
    private val cache = object : LinkedHashMap<String, TreeModel>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TreeModel>?) = size > 96
    }

    fun get(species: TreeSpecies, seed: Int, g: Float): TreeModel {
        val step = (g * 100).toInt().coerceIn(0, 100)
        val key = "${species.name}:$seed:$step"
        synchronized(cache) { cache[key]?.let { return it } }
        val m = build(species, seed, step / 100f)
        synchronized(cache) { cache[key] = m }
        return m
    }

    private fun build(species: TreeSpecies, seed: Int, g: Float): TreeModel {
        val rnd = Random(seed)
        val depth = max(2, (2 + (species.depth - 2) * g).toInt())
        val parents = ArrayList<Int>(); val levels = ArrayList<Int>()
        val rel = ArrayList<Float>(); val lens = ArrayList<Float>(); val widths = ArrayList<Float>()
        val tips = ArrayList<Int>()
        fun branch(parent: Int, relAngle: Float, len: Float, width: Float, level: Int) {
            val idx = parents.size
            parents.add(parent); levels.add(level); rel.add(relAngle); lens.add(len); widths.add(width)
            if (level >= depth) { tips.add(idx); return }
            if (level >= depth - 2) tips.add(idx)
            val spread = species.branchAngle * (PI.toFloat() / 180f)
            val children = if (rnd.nextFloat() < 0.3f && level > 1) 3 else 2
            for (i in 0 until children) {
                val offset = when (children) {
                    2 -> if (i == 0) -spread else spread
                    else -> (i - 1) * spread
                }
                val jitter = (rnd.nextFloat() - 0.5f) * spread * 0.5f
                val shrink = species.shrink * (0.88f + rnd.nextFloat() * 0.2f)
                branch(idx, offset + jitter, len * shrink, width * 0.68f, level + 1)
            }
        }
        branch(-1, -PI.toFloat() / 2, 0.2f + 0.14f * g, 0.018f + 0.03f * g, 1)

        val leafRnd = Random(seed * 31 + 7)
        val nLeaves = tips.size * 3
        val leafNode = IntArray(nLeaves); val dx = FloatArray(nLeaves); val dy = FloatArray(nLeaves); val sc = FloatArray(nLeaves)
        val day = arrayOfNulls<Color>(nLeaves); val night = arrayOfNulls<Color>(nLeaves)
        var k = 0
        tips.forEach { t ->
            repeat(3) {
                leafNode[k] = t
                dx[k] = (leafRnd.nextFloat() - 0.5f) * 1.6f
                dy[k] = (leafRnd.nextFloat() - 0.5f) * 1.6f
                val jitterL = (leafRnd.nextFloat() - 0.5f) * 0.12f
                val hue = species.leafHue + (leafRnd.nextFloat() - 0.5f) * 16f
                day[k] = Color.hsl(hue, species.leafSat, (0.42f + jitterL).coerceIn(0.1f, 0.8f), 0.82f)
                night[k] = Color.hsl(hue, species.leafSat, (0.28f + jitterL).coerceIn(0.1f, 0.8f), 0.82f)
                sc[k] = 0.7f + leafRnd.nextFloat() * 0.6f
                k++
            }
        }
        val bloomHue = species.bloomHue
        val bNode = ArrayList<Int>(); val bdx = ArrayList<Float>(); val bdy = ArrayList<Float>()
        if (bloomHue != null) {
            val bloomRnd = Random(seed * 13 + 3)
            tips.forEach { t ->
                if (bloomRnd.nextFloat() < 0.6f) {
                    bNode.add(t)
                    bdx.add(bloomRnd.nextFloat() - 0.5f)
                    bdy.add(bloomRnd.nextFloat() - 0.5f)
                }
            }
        }
        return TreeModel(
            depth, parents.size, parents.toIntArray(), levels.toIntArray(), rel.toFloatArray(), lens.toFloatArray(), widths.toFloatArray(),
            nLeaves, leafNode, dx, dy, sc, day.requireNoNulls(), night.requireNoNulls(),
            bNode.size, bNode.toIntArray(), bdx.toFloatArray(), bdy.toFloatArray(),
            Color.hsl(bloomHue ?: 0f, 0.75f, 0.78f), Color.hsl(bloomHue ?: 0f, 0.75f, 0.55f),
            Color.hsl(species.trunkHue, 0.35f, 0.28f), Color.hsl(species.trunkHue, 0.35f, 0.18f),
        )
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
