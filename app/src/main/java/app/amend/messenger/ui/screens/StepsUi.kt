package app.amend.messenger.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.data.StepLog
import app.amend.messenger.data.StepReportMessage
import app.amend.messenger.ui.components.GlassCard
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.ui.theme.LocalDark
import app.amend.messenger.util.StepTracker
import app.amend.messenger.util.StepsUi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max

private fun fmt(n: Long) = "%,d".format(Locale.US, n)

/** Asks for the step-counter permission once, the first time the app opens. */
@Composable
fun StepPermissionOnce() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { StepTracker.refresh(context) }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < 29 || StepTracker.hasPermission(context) || !StepTracker.hasSensor(context)) return@LaunchedEffect
        val p = context.getSharedPreferences("amend_steps", Context.MODE_PRIVATE)
        if (p.getBoolean("asked", false)) return@LaunchedEffect
        p.edit().putBoolean("asked", true).apply()
        launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
    }
}

/** Steps today (ring), distance, the last 7 days, and this month — shown on the profile. */
@Composable
fun StepsCard(onOpenReports: () -> Unit) {
    val context = LocalContext.current
    val ui by StepTracker.ui.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    var settings by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        StepTracker.refresh(context)
        val p = context.getSharedPreferences("amend_steps", Context.MODE_PRIVATE)
        val refusals = p.getInt("refusals", 0) + if (granted) 0 else 1
        p.edit().putInt("refusals", refusals).apply()
        if (!granted && refusals >= 2) {
            // refused before → Android won't show the dialog again; open amend's settings instead
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("👣 Walking", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { haptics.tap(); onOpenReports() }) {
                    Text(if (ui.unreadReports > 0) "Reports · ${ui.unreadReports} new" else "Reports")
                }
                TextButton(onClick = { settings = true }) { Text("Settings") }
            }
            when {
                !ui.hasSensor -> Text(
                    "This phone has no step counter chip, so amend can't count steps.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                !ui.enabled -> Text(
                    "Step counting is off. Turn it on in Settings.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                !ui.hasPermission -> Column {
                    Text(
                        "Allow “Physical activity” so amend can count your steps. The count never leaves your phone.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { if (Build.VERSION.SDK_INT >= 29) launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION) }, shape = RoundedCornerShape(16.dp)) {
                        Text("Allow step counting")
                    }
                }
                else -> StepsContent(ui)
            }
        }
    }
    if (settings) StepSettingsDialog(ui, onDismiss = { settings = false })
}

@Composable
private fun StepsContent(ui: StepsUi) {
    val progress = remember { Animatable(0f) }
    val target = (ui.today / ui.goal.toFloat()).coerceIn(0f, 1.5f)
    LaunchedEffect(target) { progress.animateTo(target, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessVeryLow)) }
    val count = remember { Animatable(0f) }
    LaunchedEffect(ui.today) { count.animateTo(ui.today.toFloat(), tween(900)) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 14.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(Color(0x332BB673), -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(
                    Brush.sweepGradient(listOf(Color(0xFF2BB673), Color(0xFF5BE39B), Color(0xFF4FC3F7), Color(0xFF2BB673))),
                    -90f, 360f * progress.value.coerceAtMost(1f), false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                // past the goal: a second, golden lap
                if (progress.value > 1f) {
                    drawArc(Color(0xFFFFC94D), -90f, 360f * (progress.value - 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke / 2, cap = StrokeCap.Round))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(fmt(count.value.toLong()), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("of ${fmt(ui.goal.toLong())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ui.today >= ui.goal) Text("🎉", fontSize = 14.sp)
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Today", StepLog.formatKm(ui.todayKm) + " km")
            Stat(LocalDate.now().month.getDisplayName(TextStyle.FULL, Locale.ENGLISH), "${fmt(ui.monthSteps)} steps · ${StepLog.formatKm(ui.monthKm)} km")
            Stat("CO₂ a car would emit", "%.1f kg".format(Locale.US, ui.monthKm * StepLog.CAR_CO2_KG_PER_KM))
        }
    }
    Spacer(Modifier.height(14.dp))
    WeekBars(ui)
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun WeekBars(ui: StepsUi) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }
    val peak = max(ui.goal, ui.last7.maxOfOrNull { it.second } ?: 0).toFloat()
    val goalColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Column {
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            val n = ui.last7.size.coerceAtLeast(1)
            val slot = size.width / n
            val w = slot * 0.55f
            // goal line
            val gy = size.height * (1 - ui.goal / peak)
            drawLine(goalColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            ui.last7.forEachIndexed { i, (_, steps) ->
                val h = (size.height * steps / peak * grow.value).coerceAtLeast(3.dp.toPx())
                val reached = steps >= ui.goal
                drawRoundRect(
                    if (reached) Color(0xFF2BB673) else Color(0xFF7FCFA4),
                    topLeft = Offset(i * slot + (slot - w) / 2, size.height - h),
                    size = Size(w, h), cornerRadius = CornerRadius(w / 3, w / 3),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            ui.last7.forEach { (day, _) ->
                Text(
                    if (day == LocalDate.now()) "Today" else day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StepSettingsDialog(ui: StepsUi, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var height by remember { mutableStateOf(ui.heightCm.toString()) }
    var goal by remember { mutableStateOf(ui.goal.toString()) }
    var enabled by remember { mutableStateOf(ui.enabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Walking settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Count my steps", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = height, onValueChange = { height = it.filter(Char::isDigit).take(3) },
                    label = { Text("Your height (cm)") }, singleLine = true,
                    supportingText = { Text("Used to estimate your stride, for kilometres") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = goal, onValueChange = { goal = it.filter(Char::isDigit).take(5) },
                    label = { Text("Daily goal (steps)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    "Steps are counted by your phone's low-power sensor and stored only on this phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                height.toIntOrNull()?.let { StepTracker.setHeight(context, it) }
                goal.toIntOrNull()?.let { StepTracker.setGoal(context, it) }
                StepTracker.setEnabled(context, enabled)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ============================================================ the "amend · Walking" chat

/** Row at the top of the chat list once a monthly report has arrived. */
@Composable
fun WalkingChatRow(onOpen: () -> Unit) {
    val ui by StepTracker.ui.collectAsStateWithLifecycle()
    val last = ui.reports.lastOrNull() ?: return
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().clickable { haptics.tap(); onOpen() }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF2BB673), Color(0xFF4FC3F7)))),
            contentAlignment = Alignment.Center,
        ) { Text("👣", fontSize = 24.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("amend · Walking", style = MaterialTheme.typography.titleMedium)
            Text(
                "${StepLog.monthName(last.month)}: ${fmt(last.report.totalSteps)} steps · ${StepLog.formatKm(last.report.km)} km",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        if (ui.unreadReports > 0) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Color(0xFF2BB673)), contentAlignment = Alignment.Center) {
                Text("${ui.unreadReports}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** A chat-style list of the monthly walking reports, from "amend". */
@Composable
fun WalkingReportsScreen(onBack: () -> Unit) {
    val ui by StepTracker.ui.collectAsStateWithLifecycle()
    val dark = LocalDark.current
    LaunchedEffect(ui.unreadReports) { if (ui.unreadReports > 0) StepTracker.markReportsRead() }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF1C7A4E), Color(0xFF2BB673), Color(0xFF4FC3F7))))
                .statusBarsPadding().padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text("👣", fontSize = 26.sp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("amend · Walking", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("A report arrives at the end of every month", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "This month so far: ${fmt(ui.monthSteps)} steps · ${StepLog.formatKm(ui.monthKm)} km",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            if (ui.reports.isEmpty()) {
                item {
                    ReportBubble(
                        "👋 Hi! I'm counting your steps quietly on this phone. At the end of the month I'll send you a full walking report here.",
                        null, null, dark,
                    )
                }
            }
            items(ui.reports, key = { it.month }) { r ->
                ReportBubble(r.text, r, Instant.ofEpochSecond(r.createdAt), dark)
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ReportBubble(text: String, report: StepReportMessage?, at: Instant?, dark: Boolean) {
    Column(
        Modifier.widthIn(max = 330.dp)
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 6.dp))
            .background(if (dark) Color(0xFF1B3A2D) else Color.White)
            .padding(14.dp),
    ) {
        report?.let { MonthChart(it) ; Spacer(Modifier.height(10.dp)) }
        Text(text, style = MaterialTheme.typography.bodyMedium)
        at?.let {
            Text(
                it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
            )
        }
    }
}

/** Daily bars for the whole month, in the report. */
@Composable
private fun MonthChart(m: StepReportMessage) {
    val r = m.report
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(900)) }
    val peak = max(1, r.daily.maxOrNull() ?: 1).toFloat()
    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
        val n = r.daily.size.coerceAtLeast(1)
        val slot = size.width / n
        val w = slot * 0.65f
        r.daily.forEachIndexed { i, steps ->
            val h = (size.height * steps / peak * grow.value).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                if (r.bestDay != null && i == LocalDate.parse(r.bestDay).dayOfMonth - 1) Color(0xFFFFC94D) else Color(0xFF2BB673),
                topLeft = Offset(i * slot + (slot - w) / 2, size.height - h),
                size = Size(w, h), cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}
