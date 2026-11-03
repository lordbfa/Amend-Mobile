package app.amend.messenger.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import app.amend.messenger.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.AmendApp
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.eco.Footprint
import app.amend.messenger.net.RelayState
import app.amend.messenger.ui.components.GlassCard
import app.amend.messenger.ui.components.MyceliumNetwork
import app.amend.messenger.ui.components.rememberHaptics

/** Independent reforestation charities. amend has no partnership with them; links open their own sites. */
private val TREE_ORGS = listOf(
    Triple("One Tree Planted", "Global reforestation charity", "https://onetreeplanted.org/"),
    Triple("Eden: People+Planet", "Community-led forest restoration", "https://www.edenprojects.org/"),
    Triple("Trees for the Future", "Agroforestry with farming communities", "https://trees.org/"),
)

@Composable
fun ImpactTab(repo: AmendRepository, openReports: () -> Unit = {}) {
    val eco by repo.eco.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var showAssumptions by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Your impact", style = MaterialTheme.typography.headlineMedium)
        Text(
            "amend measures its own data use and shows what it costs the planet — then helps you offset far more with real-world actions.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Hero: CO2 avoided vs CO2 caused
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                val caused = Footprint.gramsCo2(eco.totalBytes)
                val avoided = eco.co2AvoidedGrams.toDouble()
                val ratio = if (caused <= 0.0) 1f else (avoided / (avoided + caused)).toFloat()
                val sweep = remember { Animatable(0f) }
                LaunchedEffect(ratio) { sweep.animateTo(ratio, tween(1400, easing = FastOutSlowInEasing)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(120.dp)) {
                        val stroke = 16.dp.toPx()
                        val inset = stroke / 2
                        val arcSize = Size(size.width - stroke, size.height - stroke)
                        drawArc(Color(0x33FF7A7A), -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                        drawArc(
                            Brush.sweepGradient(listOf(Color(0xFF2BB673), Color(0xFF5BE39B), Color(0xFF2BB673))),
                            -90f, 360f * sweep.value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text("CO₂ avoided", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        AnimatedNumber(avoided, Footprint::formatGrams, MaterialTheme.typography.headlineMedium.fontSize.value)
                        Spacer(Modifier.height(6.dp))
                        Text("amend's data footprint", style = MaterialTheme.typography.labelLarge, color = Color(0xFFFF8A80))
                        AnimatedNumber(caused, Footprint::formatGrams, 18f)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (avoided > caused) "Your challenges have offset amend's footprint ${if (caused > 0) "${(avoided / caused).toLong().coerceAtLeast(1)}×" else "completely"}. 🌍"
                    else "Complete a daily challenge to offset your messaging footprint.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatChip("✉️", "${eco.messagesSent + eco.messagesReceived}", "messages", Modifier.weight(1f))
            StatChip("📶", Footprint.formatBytes(eco.totalBytes), "data", Modifier.weight(1f))
            StatChip("💡", String.format("%.1fs", Footprint.ledSeconds(eco.totalBytes)), "of LED light", Modifier.weight(1f))
        }

        WeeklyCarbonCard(eco)

        // Walking: every km on foot is a km not driven
        StepsCard(onOpenReports = openReports)

        GlassCard(Modifier.fillMaxWidth().clickable { showAssumptions = !showAssumptions }) {
            Column(Modifier.padding(16.dp)) {
                Text("How is this estimated? ${if (showAssumptions) "▲" else "▼"}", style = MaterialTheme.typography.titleSmall)
                AnimatedVisibility(showAssumptions) {
                    Text(
                        "amend counts every byte it sends and receives. It assumes ${Footprint.KWH_PER_GB} kWh per GB for networks and data centres and " +
                            "${Footprint.GRID_G_PER_KWH.toInt()} g CO₂ per kWh of electricity. Published estimates vary a lot (up to 10×), so treat these numbers as an order of magnitude. " +
                            "Challenge savings are rough typical values for one day of the action. The phone's own charging isn't included.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        // Eco settings
        Text("Greener settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp))
        SettingSwitch(
            "🔋 Efficiency mode",
            "Dark theme, calmer animations and less frequent background checks. Earns ${app.amend.messenger.eco.EcoPoints.EFFICIENT_DAY} points a day.",
            eco.efficiencyMode,
        ) {
            haptics.tick()
            repo.setEfficiencyMode(it)
            (context.applicationContext as AmendApp).rescheduleBackground(repo)
        }
        SettingSwitch(
            "⚡ Carbon-aware sync",
            if (eco.instantNotifications) "Applies when Instant notifications (Settings → Notifications) are off: background checks then only run while charging on Wi-Fi — like carbon-aware system updates."
            else "Background checks only while charging on Wi-Fi — like carbon-aware system updates. Messages still arrive instantly while amend is open.",
            eco.carbonAwareSync,
        ) {
            haptics.tick()
            repo.setCarbonAwareSync(it)
            (context.applicationContext as AmendApp).rescheduleBackground(repo)
        }

        // Real trees
        Text("Plant a real tree 🌳", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp))
        Text(
            "Your forest in amend is virtual. If you'd like to plant real trees, these independent charities do it — donations go directly to them. amend isn't affiliated and receives nothing.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TREE_ORGS.forEach { (name, blurb, url) ->
            GlassCard(Modifier.fillMaxWidth().clickable {
                haptics.tap()
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2BB673)), contentAlignment = Alignment.Center) {
                        Text("🌱", fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("↗", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Last 7 days: amend's data footprint (red) against what challenges avoided (green). */
@Composable
private fun WeeklyCarbonCard(eco: app.amend.messenger.data.EcoState) {
    val days = remember(eco.dailyBytes, eco.dailyAvoided) {
        val today = java.time.LocalDate.now()
        (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            Triple(
                d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                Footprint.gramsCo2(eco.dailyBytes[d.toString()] ?: 0L),
                (eco.dailyAvoided[d.toString()] ?: 0L).toDouble(),
            )
        }
    }
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val weekCaused = days.sumOf { it.second }
    val weekAvoided = days.sumOf { it.third }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Your digital carbon this week", style = MaterialTheme.typography.titleSmall)
            Text(
                "Footprint ${Footprint.formatGrams(weekCaused)} · avoided ${Footprint.formatGrams(weekAvoided)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            val max = days.maxOf { maxOf(it.second, it.third) }.coerceAtLeast(0.001)
            val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
            Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                days.forEach { (label, caused, avoided) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Canvas(Modifier.width(26.dp).height(88.dp)) {
                            val bw = size.width / 2 - 2.dp.toPx()
                            val r = androidx.compose.ui.geometry.CornerRadius(bw / 2, bw / 2)
                            fun bar(x: Float, v: Double, c: Color) {
                                drawRoundRect(track, Offset(x, 0f), Size(bw, size.height), r)
                                val h = (size.height * (v / max).toFloat() * grow.value).coerceAtLeast(if (v > 0) 3f else 0f)
                                if (h > 0) drawRoundRect(c, Offset(x, size.height - h), Size(bw, h), r)
                            }
                            bar(0f, caused, Color(0xFFFF8A80))
                            bar(size.width - bw, avoided, Color(0xFF2BB673))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFF8A80)))
                Text("  data footprint    ", style = MaterialTheme.typography.labelSmall)
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2BB673)))
                Text("  CO₂ avoided", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AnimatedNumber(target: Double, format: (Double) -> String, fontSizeSp: Float) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) { anim.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    Text(format(target * anim.value), fontSize = fontSizeSp.sp, style = MaterialTheme.typography.headlineSmall)
}

@Composable
fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    GlassCard(Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

/** The relays your messages travel through, live — shown in Settings → Your network. */
@Composable
fun NetworkSection(repo: AmendRepository) {
    val statuses by repo.relayStatuses.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
        "Like mycelium linking a forest, your messages travel through independent relays run by different people. No single company holds them — and they're encrypted so relays can't read them.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            MyceliumNetwork(statuses, Modifier.fillMaxWidth().height(240.dp))
            statuses.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val col = when (s.state) {
                        RelayState.ONLINE -> Color(0xFF5BE39B)
                        RelayState.CONNECTING -> Color(0xFFFFC145)
                        RelayState.ERROR -> Color(0xFFFF7A7A)
                        RelayState.OFFLINE -> Color(0xFF6E8A7C)
                    }
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(col))
                    Spacer(Modifier.width(10.dp))
                    Text(s.url.removePrefix("wss://"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        when (s.state) {
                            RelayState.ONLINE -> s.latencyMs?.let { "${it} ms" } ?: "online"
                            RelayState.CONNECTING -> "connecting"
                            RelayState.ERROR -> "unreachable"
                            RelayState.OFFLINE -> "offline"
                        },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!(LocalContext.current.applicationContext as AmendApp).nativeCrypto) {
                Text("Using portable crypto (native library unavailable on this device).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
        }
    }
    }
}
