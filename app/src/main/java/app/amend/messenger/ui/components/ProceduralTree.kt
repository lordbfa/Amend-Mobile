package app.amend.messenger.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
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
    autumn: Float = 0f,
    snow: Float = 0f,
) {
    val p = progress.coerceIn(0f, 1f)
    if (p < 0.1f) {
        drawSeedling(base, height, p / 0.1f, sway)
        return
    }
    val g = (p - 0.1f) / 0.9f // 0..1 growth after sprouting
    if (species.conifer) {
        drawConifer(species, g, base, height, sway + shake, night, snow)
        return
    }
    val model = TreeModels.get(species, seed, g)
    val trunk = if (night) model.trunkNight else model.trunkDay
    val bark = if (night) model.barkNight else model.barkDay
    val barkLight = if (night) model.barkLightNight else model.barkLightDay
    val detailed = height > 140f
    // soft shadow on the ground
    if (detailed) {
        val w = height * (0.18f + 0.32f * g) * species.crown
        drawOval(Color.Black.copy(alpha = if (night) 0.22f else 0.14f), Offset(base.x - w / 2, base.y - height * 0.012f), Size(w, height * 0.035f))
    }
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
        val w = model.width[i] * height
        if (detailed && level <= 3) {
            // bark: darker edge, the wood, and light catching the left side
            drawLine(bark, Offset(sx, sy), Offset(ex[i], ey[i]), strokeWidth = w * 1.18f, cap = StrokeCap.Round)
            drawLine(trunk, Offset(sx, sy), Offset(ex[i], ey[i]), strokeWidth = w, cap = StrokeCap.Round)
            val nx = -sin(a) * w * 0.22f
            val ny = cos(a) * w * 0.22f
            drawLine(barkLight, Offset(sx + nx, sy + ny), Offset(ex[i] + nx, ey[i] + ny), strokeWidth = w * 0.22f, cap = StrokeCap.Round)
        } else {
            drawLine(trunk, Offset(sx, sy), Offset(ex[i], ey[i]), strokeWidth = w, cap = StrokeCap.Round)
        }
        if (parent < 0 && detailed) {
            // root flare where the trunk meets the ground
            val flare = Path().apply {
                moveTo(sx - w * 1.1f, sy + w * 0.15f)
                quadraticBezierTo(sx - w * 0.4f, sy - w * 0.2f, sx - w * 0.35f, sy - w * 1.2f)
                lineTo(sx + w * 0.35f, sy - w * 1.2f)
                quadraticBezierTo(sx + w * 0.4f, sy - w * 0.2f, sx + w * 1.1f, sy + w * 0.15f)
                close()
            }
            drawPath(flare, trunk)
        }
    }
    val leafR = height * 0.045f * species.crown * (0.5f + 0.5f * g)
    val colors = if (night) model.leafNight else model.leafDay
    val a = autumn.coerceIn(0f, 1f)
    // weeping trees: hanging strands under every tip
    if (species.weeping && g > 0.3f) {
        val strand = colors[0].copy(alpha = 0.75f)
        for (k in 0 until model.leafCount step 3) {
            val node = model.leafNode[k]
            val x = ex[node] + model.leafDx[k] * leafR * 0.5f
            val y = ey[node]
            val drop = leafR * (2.2f + model.leafScale[k] * 1.6f) * g
            val drift = swayAll * drop * 0.6f
            drawLine(strand, Offset(x, y), Offset(x + drift, y + drop), strokeWidth = leafR * 0.28f, cap = StrokeCap.Round)
        }
    }
    // canopy, back to front: a shaded underside, the leaves, and sunlit tops
    for (o in 0 until model.leafCount) {
        val k = model.leafOrder[o]
        val node = model.leafNode[k]
        val base0 = colors[k]
        val col = if (a > 0f) lerp(base0, AUTUMN_LEAF[k % AUTUMN_LEAF.size], a * 0.8f) else base0
        val r = leafR * model.leafScale[k]
        val cx = ex[node] + model.leafDx[k] * leafR
        val cy = ey[node] + model.leafDy[k] * leafR
        if (detailed && k % 2 == 0) drawCircle(lerp(col, Color.Black, 0.28f), r * 1.04f, Offset(cx + r * 0.22f, cy + r * 0.28f))
        drawCircle(col, r, Offset(cx, cy))
        if (detailed && k % 3 == 0) drawCircle(lerp(col, Color(0xFFFFF6C8), if (night) 0.12f else 0.32f), r * 0.52f, Offset(cx - r * 0.28f, cy - r * 0.32f))
    }
    // snow settling on the crown
    if (snow > 0.02f) {
        val s = snow.coerceIn(0f, 1f)
        val cap = Color.White.copy(alpha = 0.85f * s)
        for (k in 0 until model.leafCount step 2) {
            val node = model.leafNode[k]
            drawCircle(
                cap,
                leafR * model.leafScale[k] * 0.72f,
                Offset(ex[node] + model.leafDx[k] * leafR, ey[node] + model.leafDy[k] * leafR - leafR * 0.4f * s),
            )
        }
    }
    if (model.bloomCount > 0 && g > 0.45f && a < 0.4f && snow < 0.3f) {
        val petal = if (night) model.bloomNight else model.bloomDay
        val eye = if (night) model.eyeNight else model.eyeDay
        val open = ((g - 0.45f) / 0.35f).coerceIn(0.3f, 1f)
        val br = leafR * 0.42f * open
        for (k in 0 until model.bloomCount) {
            val node = model.bloomNode[k]
            val cx = ex[node] + model.bloomDx[k] * leafR
            val cy = ey[node] + model.bloomDy[k] * leafR
            if (!detailed || br < 3f) {
                drawCircle(petal, br, Offset(cx, cy))
            } else if (species.ballBlooms) {
                drawCircle(petal, br * 1.1f, Offset(cx, cy))
                drawCircle(lerp(petal, Color.White, 0.45f), br * 0.45f, Offset(cx - br * 0.3f, cy - br * 0.3f))
            } else {
                // five petals around a small eye, each flower turned a little differently
                val turn = k * 0.7f
                for (pi in 0 until 5) {
                    val pa = turn + pi * (2f * PI.toFloat() / 5f)
                    drawCircle(petal, br * 0.55f, Offset(cx + cos(pa) * br * 0.55f, cy + sin(pa) * br * 0.55f))
                }
                drawCircle(eye, br * 0.32f, Offset(cx, cy))
            }
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
    /** Leaf indices back to front (higher on the tree first), for depth. */
    val leafOrder: IntArray,
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
    val barkDay: Color,
    val barkNight: Color,
    val barkLightDay: Color,
    val barkLightNight: Color,
    val eyeDay: Color,
    val eyeNight: Color,
) {
    val scratchX = FloatArray(count)
    val scratchY = FloatArray(count)
    val scratchA = FloatArray(count)
}

/** Sort key for leaf depth: leaves drawn top/back first. */
private fun ey0(i: Int, dy: FloatArray): Float = -dy[i]

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
        val perTip = if (species.foliage >= 0.99f) 3 else if (species.foliage >= 0.5f) 2 else 1
        val nLeaves = (tips.size * perTip).coerceAtLeast(1)
        val leafNode = IntArray(nLeaves); val dx = FloatArray(nLeaves); val dy = FloatArray(nLeaves); val sc = FloatArray(nLeaves)
        val day = arrayOfNulls<Color>(nLeaves); val night = arrayOfNulls<Color>(nLeaves)
        var k = 0
        tips.forEach { t ->
            repeat(perTip) {
                if (k >= nLeaves) return@repeat
                leafNode[k] = t
                dx[k] = (leafRnd.nextFloat() - 0.5f) * 1.6f
                dy[k] = (leafRnd.nextFloat() - 0.5f) * 1.6f
                val jitterL = (leafRnd.nextFloat() - 0.5f) * 0.12f
                val hue = (species.leafHue + (leafRnd.nextFloat() - 0.5f) * 22f + 360f) % 360f
                // outer leaves a touch lighter and yellower, inner ones deeper green
                val outer = dy[k] < 0f
                val l = (if (outer) 0.44f else 0.36f) + jitterL
                day[k] = Color.hsl(hue, (species.leafSat + if (outer) 0.04f else -0.04f).coerceIn(0f, 1f), l.coerceIn(0.1f, 0.8f), 0.92f)
                night[k] = Color.hsl(hue, species.leafSat * 0.8f, (l - 0.15f).coerceIn(0.08f, 0.6f), 0.92f)
                sc[k] = 0.7f + leafRnd.nextFloat() * 0.6f
                k++
            }
        }
        val bloomHue = species.bloomHue
        val bNode = ArrayList<Int>(); val bdx = ArrayList<Float>(); val bdy = ArrayList<Float>()
        if (bloomHue != null) {
            val bloomRnd = Random(seed * 13 + 3)
            tips.forEach { t ->
                // dense bloomers carry up to two flowers per tip
                repeat(if (species.bloomDensity > 0.85f) 2 else 1) {
                    if (bloomRnd.nextFloat() < species.bloomDensity) {
                        bNode.add(t)
                        bdx.add((bloomRnd.nextFloat() - 0.5f) * 1.6f)
                        bdy.add((bloomRnd.nextFloat() - 0.5f) * 1.6f)
                    }
                }
            }
        }
        return TreeModel(
            depth, parents.size, parents.toIntArray(), levels.toIntArray(), rel.toFloatArray(), lens.toFloatArray(), widths.toFloatArray(),
            nLeaves, leafNode, dx, dy, sc, (0 until nLeaves).sortedBy { ey0(it, dy) }.toIntArray(),
            day.requireNoNulls(), night.requireNoNulls(),
            bNode.size, bNode.toIntArray(), bdx.toFloatArray(), bdy.toFloatArray(),
            Color.hsl(bloomHue ?: 0f, species.bloomSat, species.bloomLight), Color.hsl(bloomHue ?: 0f, species.bloomSat, species.bloomLight * 0.7f),
            Color.hsl(species.trunkHue, 0.35f, 0.28f), Color.hsl(species.trunkHue, 0.35f, 0.18f),
            Color.hsl(species.trunkHue, 0.30f, 0.17f), Color.hsl(species.trunkHue, 0.30f, 0.10f),
            Color.hsl(species.trunkHue, 0.30f, 0.45f, 0.7f), Color.hsl(species.trunkHue, 0.30f, 0.28f, 0.6f),
            Color.hsl(species.bloomEyeHue ?: (bloomHue ?: 0f), 0.85f, if (species.bloomEyeHue != null) 0.55f else species.bloomLight * 0.6f),
            Color.hsl(species.bloomEyeHue ?: (bloomHue ?: 0f), 0.85f, 0.35f),
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

private fun DrawScope.drawConifer(species: TreeSpecies, g: Float, base: Offset, height: Float, sway: Float, night: Boolean, snow: Float = 0f) {
    val h = height * (0.3f + 0.6f * g)
    val trunkW = height * (0.02f + 0.02f * g)
    drawLine(Color.hsl(species.trunkHue, 0.35f, 0.25f), base, Offset(base.x, base.y - h * 0.25f), trunkW, StrokeCap.Round)
    val tiers = 3 + (3 * g).toInt()
    val s = snow.coerceIn(0f, 1f)
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
        // shade the right half of each tier, as if the sun were on the left
        val shade = Path().apply {
            moveTo(base.x + lean, topY)
            lineTo(base.x + w / 2 + lean * 0.5f, tierBase)
            lineTo(base.x + lean * 0.5f + w * 0.05f, tierBase)
            close()
        }
        drawPath(shade, Color.Black.copy(alpha = 0.16f))
        // a few needle highlights along the lit edge
        drawLine(Color.hsl(species.leafHue, species.leafSat, (if (night) 0.3f else 0.46f)).copy(alpha = 0.7f),
            Offset(base.x + lean, topY + h * 0.03f), Offset(base.x - w * 0.34f + lean * 0.5f, tierBase - h * 0.02f), strokeWidth = h * 0.008f, cap = StrokeCap.Round)
        if (s > 0.02f) {
            // a lighter snow cap sitting on the upper half of each tier
            val snowPath = Path().apply {
                moveTo(base.x - w * 0.28f + lean * 0.5f, tierBase - h * 0.16f)
                lineTo(base.x + w * 0.28f + lean * 0.5f, tierBase - h * 0.16f)
                lineTo(base.x + lean, topY)
                close()
            }
            drawPath(snowPath, Color.White.copy(alpha = 0.8f * s))
        }
    }
}

/** Warm autumn leaf colours, blended in as the season turns. */
private val AUTUMN_LEAF = arrayOf(
    Color(0xFFE2711D), Color(0xFFD1410C), Color(0xFFF2B707), Color(0xFFB5641E), Color(0xFFC0392B), Color(0xFFE8A33D),
)

/**
 * Rain streaks and/or drifting snow drawn over the forest canvas. [phase] (0..1, looping)
 * drives the fall; [reduced] thins everything out for Efficiency mode.
 */
fun DrawScope.drawPrecip(rain: Float, snow: Float, phase: Float, reduced: Boolean) {
    val w = size.width
    val h = size.height
    if (rain > 0.02f) {
        val n = ((if (reduced) 24 else 58) * rain).toInt()
        val len = h * 0.07f
        for (i in 0 until n) {
            val x = (((i * 73) % 100) / 100f) * w
            val yBase = ((i * 137) % 100) / 100f
            val y = ((yBase + phase * 1.7f) % 1f) * h
            drawLine(Color(0x99BBD6FF), Offset(x, y), Offset(x - len * 0.22f, y + len), strokeWidth = 2f)
        }
    }
    if (snow > 0.02f) {
        val n = ((if (reduced) 22 else 52) * snow).toInt()
        for (i in 0 until n) {
            val drift = sin((phase * 2f + i) * PI.toFloat()) * 0.03f
            val x = ((((i * 53) % 100) / 100f) + drift) * w
            val yBase = ((i * 97) % 100) / 100f
            val y = ((yBase + phase * 0.75f) % 1f) * h
            drawCircle(Color.White.copy(alpha = 0.85f), 2f + (i % 3), Offset(x, y))
        }
    }
}
