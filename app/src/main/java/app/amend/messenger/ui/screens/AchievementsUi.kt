package app.amend.messenger.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.ChatMessage
import app.amend.messenger.eco.Achievement
import app.amend.messenger.eco.AchievementTab
import app.amend.messenger.eco.Achievements
import app.amend.messenger.eco.Tier
import app.amend.messenger.ui.LocalParticles
import app.amend.messenger.ui.Text
import app.amend.messenger.ui.components.GlassCard
import app.amend.messenger.ui.components.ParticleKind
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.ui.theme.LocalEfficiency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Two-colour gradient for each tier: light top, deep bottom. */
fun tierColors(t: Tier?): Pair<Color, Color> = when (t) {
    Tier.BRONZE -> Color(0xFFF0B27A) to Color(0xFF8C4F1F)
    Tier.SILVER -> Color(0xFFF2F6F8) to Color(0xFF7D8C97)
    Tier.GOLD -> Color(0xFFFFE58A) to Color(0xFFC98A0B)
    Tier.EMERALD -> Color(0xFF8FF5C2) to Color(0xFF0B6B40)
    null -> Color(0xFF9AA8A0) to Color(0xFF4A5751)
}

private fun hexPath(center: Offset, r: Float): Path = Path().apply {
    for (i in 0 until 6) {
        val a = (PI / 3 * i - PI / 2).toFloat()
        val p = Offset(center.x + r * cos(a), center.y + r * sin(a))
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

/**
 * A badge: a hexagon in the tier's metal with the emoji in the middle. Unlocked badges turn
 * slowly with light rays and a passing shine; locked ones are grey with a ring showing progress.
 */
@Composable
fun AchievementBadge(a: Achievement, tier: Tier?, progress: Float, size: Dp, modifier: Modifier = Modifier, animate: Boolean = true) {
    val unlocked = tier != null
    val efficiency = LocalEfficiency.current
    val t = rememberInfiniteTransition(label = "badge")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "spin")
    val shine by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(2_600, easing = LinearEasing), RepeatMode.Restart), label = "shine")
    val (light, deep) = tierColors(tier)
    val live = animate && unlocked && !efficiency
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = this.size.minDimension / 2f
            if (unlocked) {
                // rays
                rotate(if (live) spin else 0f, c) {
                    for (i in 0 until 12) {
                        val ang = (2 * PI / 12 * i).toFloat()
                        val p = Path().apply {
                            moveTo(c.x, c.y)
                            lineTo(c.x + r * cos(ang - 0.11f), c.y + r * sin(ang - 0.11f))
                            lineTo(c.x + r * cos(ang + 0.11f), c.y + r * sin(ang + 0.11f))
                            close()
                        }
                        drawPath(p, light.copy(alpha = 0.18f))
                    }
                }
            }
            val hex = hexPath(c, r * 0.78f)
            drawPath(hex, Brush.linearGradient(listOf(light, deep), start = Offset(c.x, c.y - r), end = Offset(c.x, c.y + r)))
            drawPath(hexPath(c, r * 0.66f), Brush.linearGradient(listOf(deep.copy(alpha = 0.35f), light.copy(alpha = 0.15f)), start = Offset(c.x, c.y - r), end = Offset(c.x, c.y + r)))
            drawPath(hex, Color.White.copy(alpha = if (unlocked) 0.55f else 0.25f), style = Stroke(r * 0.05f))
            if (live) shineSweep(hex, c, r, shine)
            if (!unlocked) {
                drawArc(Color.White.copy(alpha = 0.18f), -90f, 360f, false, Offset(c.x - r * 0.92f, c.y - r * 0.92f), androidx.compose.ui.geometry.Size(r * 1.84f, r * 1.84f), style = Stroke(r * 0.07f))
                drawArc(Color(0xFF5BE39B), -90f, 360f * progress, false, Offset(c.x - r * 0.92f, c.y - r * 0.92f), androidx.compose.ui.geometry.Size(r * 1.84f, r * 1.84f), style = Stroke(r * 0.07f, cap = StrokeCap.Round))
            }
        }
        Text(a.emoji, fontSize = (size.value * 0.34f).sp, modifier = Modifier.graphicsLayer { alpha = if (unlocked) 1f else 0.45f })
    }
}

private fun DrawScope.shineSweep(hex: Path, c: Offset, r: Float, phase: Float) {
    val x = c.x - r + phase * 2 * r
    clipPath(hex) {
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent),
                start = Offset(x - r * 0.3f, c.y - r), end = Offset(x + r * 0.3f, c.y + r),
            ),
        )
    }
}

/** Badges row on the Forest or Impact tab. */
@Composable
fun AchievementsCard(repo: AmendRepository, tab: AchievementTab) {
    val eco by repo.eco.collectAsStateWithLifecycle()
    val stats = remember(eco) { repo.achievementStats() }
    var showAll by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Achievement?>(null) }
    val list = Achievements.all.filter { it.tab == tab }
    val unlockedCount = list.sumOf { a -> a.tiers.count { Achievements.key(a, it) in eco.achievements } }
    val total = list.sumOf { it.tiers.size }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🏆 Achievements", style = MaterialTheme.typography.titleMedium)
                    Text("$unlockedCount of $total unlocked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { showAll = true }) { Text("See all") }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { a ->
                    val tier = a.tiers.lastOrNull { Achievements.key(a, it) in eco.achievements }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp).clickable { detail = a }) {
                        AchievementBadge(a, tier, a.progress(stats), 72.dp)
                        Text(a.title, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center)
                        Text(tier?.label ?: "${(a.progress(stats) * 100).toInt()}%", fontSize = 10.sp, color = tierColors(tier).second)
                    }
                }
            }
        }
    }
    if (showAll) AchievementsDialog(repo, tab, onClose = { showAll = false })
    detail?.let { a -> AchievementDetail(repo, a, onClose = { detail = null }) }
}

@Composable
private fun AchievementsDialog(repo: AmendRepository, tab: AchievementTab, onClose: () -> Unit) {
    val eco by repo.eco.collectAsStateWithLifecycle()
    val stats = remember(eco) { repo.achievementStats() }
    var detail by remember { mutableStateOf<Achievement?>(null) }
    FullScreenDialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeTop()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("✕", fontSize = 20.sp) }
                Text(if (tab == AchievementTab.FOREST) "Forest achievements" else "Impact achievements", style = MaterialTheme.typography.titleLarge)
            }
            LazyVerticalGrid(GridCells.Adaptive(108.dp), contentPadding = PaddingValues(12.dp)) {
                items(Achievements.all.filter { it.tab == tab }, key = { it.id }) { a ->
                    val tier = a.tiers.lastOrNull { Achievements.key(a, it) in eco.achievements }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp).clickable { detail = a }) {
                        AchievementBadge(a, tier, a.progress(stats), 92.dp)
                        Text(a.title, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            a.tiers.forEach { t ->
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (Achievements.key(a, t) in eco.achievements) tierColors(t).second else Color(0x33888888)))
                            }
                        }
                    }
                }
            }
        }
    }
    detail?.let { a -> AchievementDetail(repo, a, onClose = { detail = null }) }
}

/** One achievement up close: every tier, progress to the next, and share buttons. */
@Composable
fun AchievementDetail(repo: AmendRepository, a: Achievement, onClose: () -> Unit, highlight: Tier? = null) {
    val eco by repo.eco.collectAsStateWithLifecycle()
    val profiles by repo.profiles.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val stats = remember(eco) { repo.achievementStats() }
    val tier = highlight ?: a.tiers.lastOrNull { Achievements.key(a, it) in eco.achievements }
    val next = a.nextTier(stats)
    var pickChat by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                AchievementBadge(a, tier, a.progress(stats), 150.dp, Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value })
                Text(a.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(tier?.let { "${it.label} · ${a.describe(it)}" } ?: "Locked", color = tierColors(tier).second, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(12.dp))
                a.tiers.forEach { t ->
                    val done = Achievements.key(a, t) in eco.achievements
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(if (done) tierColors(t).second else Color(0x33888888)))
                        Spacer(Modifier.width(8.dp))
                        Text("${t.label}: ${a.describe(t)}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(if (done) "✓" else "", color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (next != null) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { a.progress(stats) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                    Text("Next: ${next.label} — ${a.describe(next)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tier != null) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { pickChat = true }, shape = RoundedCornerShape(16.dp)) { Text("Share in chat") }
                        OutlinedButton(onClick = {
                            scope.launch { AchievementShare.share(context, a, tier, profiles[repo.me]?.name?.ifBlank { null }) }
                        }, shape = RoundedCornerShape(16.dp)) { Text("Share ↗") }
                    }
                }
            }
        },
    )
    if (pickChat && tier != null) {
        ChatPicker(repo, title = "Share ${a.title}", onDismiss = { pickChat = false }) { peer ->
            pickChat = false
            scope.launch { repo.shareAchievement(peer, Achievements.key(a, tier)) }
            android.widget.Toast.makeText(context, "Shared with ${repo.displayName(peer)} 🌱", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

/** Pick one of your chats (people and shared gardens). */
@Composable
fun ChatPicker(repo: AmendRepository, title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val conversations by repo.conversations.collectAsStateWithLifecycle()
    val chats = remember(conversations) {
        conversations.values.filter { repo.isAccepted(it.peer) && it.peer.length >= 32 && !it.peer.startsWith("amend:") }
            .sortedByDescending { it.lastActivity }.map { it.peer }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (chats.isEmpty()) Text("No chats yet.")
                chats.forEach { p ->
                    Text(repo.displayName(p), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The big moment: a new tier, with leaves, a bouncing badge and share buttons. Shown over everything. */
@Composable
fun AchievementCelebration(repo: AmendRepository?) {
    repo ?: return
    val queue by repo.celebrate.collectAsStateWithLifecycle()
    val key = queue.firstOrNull() ?: return
    val (a, t) = Achievements.parse(key) ?: run { repo.celebrated(key); return }
    val particles = LocalParticles.current
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val profiles by repo.profiles.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pickChat by remember(key) { mutableStateOf(false) }
    val scale = remember(key) { Animatable(0.2f) }
    LaunchedEffect(key) {
        haptics.success()
        scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
    }
    FullScreenDialog(onDismissRequest = { repo.celebrated(key) }) {
        val (light, deep) = tierColors(t)
        Box(
            Modifier.fillMaxSize().background(Brush.radialGradient(listOf(deep.copy(alpha = 0.95f), Color(0xF0061410)))),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
                Text("ACHIEVEMENT UNLOCKED", color = light, fontSize = 13.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(18.dp))
                AchievementBadge(a, t, 1f, 200.dp, Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }.clickable {
                    particles.burst(Offset(540f, 900f), ParticleKind.LEAVES, 30, 0.9f)
                })
                Spacer(Modifier.height(18.dp))
                Text(a.title, color = Color.White, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text("${t.label} · ${a.describe(t)}", color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { pickChat = true }, shape = RoundedCornerShape(18.dp)) { Text("Share in chat") }
                    OutlinedButton(onClick = {
                        scope.launch { AchievementShare.share(context, a, t, profiles[repo.me]?.name?.ifBlank { null }) }
                    }, shape = RoundedCornerShape(18.dp)) { Text("Share ↗", color = Color.White) }
                }
                TextButton(onClick = { repo.celebrated(key) }) { Text("Continue", color = Color.White.copy(alpha = 0.8f)) }
            }
        }
    }
    LaunchedEffect(key) { particles.burst(Offset(540f, 700f), ParticleKind.LEAVES, 40, 1f) }
    if (pickChat) ChatPicker(repo, title = "Share ${a.title}", onDismiss = { pickChat = false }) { peer ->
        pickChat = false
        scope.launch { repo.shareAchievement(peer, key) }
        repo.celebrated(key)
    }
}

/** An achievement someone shared in a chat. */
@Composable
fun AchievementCardContent(message: ChatMessage, textColor: Color) {
    val (a, t) = message.cardId?.removePrefix("ach:")?.let { Achievements.parse(it) } ?: run {
        Text(message.text, color = textColor); return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AchievementBadge(a, t, 1f, 64.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Achievement unlocked", color = textColor.copy(alpha = 0.8f), fontSize = 11.sp)
            Text(a.title, color = textColor, style = MaterialTheme.typography.titleMedium)
            Text("${t.label} · ${a.describe(t)}", color = textColor.copy(alpha = 0.85f), fontSize = 12.sp)
        }
    }
}

/** Draws a 1080×1350 card for sharing outside amend (Instagram, WhatsApp…) and opens the share sheet. */
object AchievementShare {
    suspend fun share(context: Context, a: Achievement, t: Tier, name: String?) {
        val file = withContext(Dispatchers.Default) { render(context, a, t, name) } ?: return
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "I unlocked ${a.emoji} ${a.title} (${t.label}) on amend — ${a.describe(t)}. Private chats that grow a forest 🌱 https://getamend.app")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "Share achievement").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun render(context: Context, a: Achievement, t: Tier, name: String?): File? = runCatching {
        val w = 1080; val h = 1350
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val (light, deep) = tierColors(t)
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF0F2E21.toInt(), deep.toArgb(), 0xFF06140E.toInt()), null, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        // glow
        c.drawCircle(w / 2f, 560f, 460f, Paint().apply { shader = RadialGradient(w / 2f, 560f, 460f, light.copy(alpha = 0.45f).toArgb(), 0x00000000, Shader.TileMode.CLAMP) })
        // rays
        val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = light.copy(alpha = 0.16f).toArgb() }
        for (i in 0 until 16) {
            val ang = 2 * PI / 16 * i
            val p = android.graphics.Path().apply {
                moveTo(w / 2f, 560f)
                lineTo((w / 2f + 520 * cos(ang - 0.09)).toFloat(), (560f + 520 * sin(ang - 0.09)).toFloat())
                lineTo((w / 2f + 520 * cos(ang + 0.09)).toFloat(), (560f + 520 * sin(ang + 0.09)).toFloat())
                close()
            }
            c.drawPath(p, ray)
        }
        // hexagon badge
        val hex = android.graphics.Path()
        for (i in 0 until 6) {
            val ang = PI / 3 * i - PI / 2
            val x = (w / 2f + 300 * cos(ang)).toFloat(); val y = (560f + 300 * sin(ang)).toFloat()
            if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
        }
        hex.close()
        c.drawPath(hex, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 260f, 0f, 860f, light.toArgb(), deep.toArgb(), Shader.TileMode.CLAMP) })
        c.drawPath(hex, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 14f; color = 0x88FFFFFF.toInt() })
        val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 230f; textAlign = Paint.Align.CENTER }
        c.drawText(a.emoji, w / 2f, 640f, emoji)
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = light.toArgb(); textSize = 38f; textAlign = Paint.Align.CENTER; letterSpacing = 0.18f; typeface = bold }
        c.drawText("ACHIEVEMENT UNLOCKED", w / 2f, 150f, small)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 84f; textAlign = Paint.Align.CENTER; typeface = bold }
        c.drawText(a.title, w / 2f, 1000f, title)
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDDFFFFFF.toInt(); textSize = 44f; textAlign = Paint.Align.CENTER }
        c.drawText("${t.label} · ${a.describe(t)}", w / 2f, 1070f, sub)
        if (!name.isNullOrBlank()) c.drawText("by ${name.take(30)}", w / 2f, 1130f, sub.apply { textSize = 36f; color = 0xAAFFFFFF.toInt() })
        val foot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5BE39B.toInt(); textSize = 40f; textAlign = Paint.Align.CENTER; typeface = bold }
        c.drawText("🌱 amend · getamend.app", w / 2f, 1270f, foot)
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, "achievement-${a.id}-${t.name.lowercase()}.png")
        out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        out
    }.getOrNull()
}
