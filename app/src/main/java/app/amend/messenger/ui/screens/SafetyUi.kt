package app.amend.messenger.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.ui.Text
import app.amend.messenger.ui.components.GlassCard
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.util.CheckIn
import app.amend.messenger.util.FakeCall
import app.amend.messenger.util.SosPrefs
import kotlinx.coroutines.delay

/** The fake incoming call, above everything (like a real call screen). */
@Composable
fun FakeCallOverlay() {
    val context = LocalContext.current
    val ringing by FakeCall.ringing.collectAsStateWithLifecycle()
    val answeredAt by FakeCall.answeredAt.collectAsStateWithLifecycle()
    if (ringing == null && answeredAt == null) return
    val name = ringing ?: FakeCall.callerName(context)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(answeredAt) { while (answeredAt != null) { now = System.currentTimeMillis(); delay(1_000) } }
    val pulse by rememberInfiniteTransition(label = "ring").animateFloat(1f, 1.12f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0E1A24), Color(0xFF16313F), Color(0xFF0B141B))))
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(112.dp).graphicsLayer { if (ringing != null) { scaleX = pulse; scaleY = pulse } }
                    .clip(CircleShape).background(Color(0xFF3A5568)),
                contentAlignment = Alignment.Center,
            ) { androidx.compose.material3.Text(name.take(1).uppercase(), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(24.dp))
            androidx.compose.material3.Text(name, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            val status = answeredAt?.let { ((now - it) / 1000).let { s -> String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60) } }
            Text(status ?: "Incoming call", color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp)
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 72.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RoundCallButton(Color(0xFFE53935), Icons.Rounded.CallEnd, "Decline") { FakeCall.end(context) }
            if (ringing != null) RoundCallButton(Color(0xFF2BB673), Icons.Rounded.Call, "Answer") { FakeCall.answer(context) }
        }
    }
}

@Composable
private fun RoundCallButton(color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
    }
}

/** Settings → Safety: "check in within …", or amend starts your SOS for you. */
@Composable
fun CheckInCard() {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val deadline by CheckIn.deadline.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) { while (deadline != null) { now = System.currentTimeMillis(); delay(15_000) } }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Safety check-in 🛡️", style = MaterialTheme.typography.titleMedium)
            Text(
                "Walking home late, meeting someone new, hiking alone? Set a timer. If you don't tap “I'm safe” in time, amend starts your SOS and your SOS contacts get your live location. A reminder comes 5 minutes before.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val d = deadline
            if (d != null) {
                val left = ((d - now) / 60_000).coerceAtLeast(0)
                Text(
                    if (left >= 60) "Check in within ${left / 60} h ${left % 60} min" else "Check in within $left min",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { haptics.success(); CheckIn.safe(context) }, shape = RoundedCornerShape(16.dp)) { Text("I'm safe") }
                    OutlinedButton(onClick = { haptics.tap(); CheckIn.start(context, (left + 30).toInt().coerceAtMost(24 * 60)) }, shape = RoundedCornerShape(16.dp)) { Text("+30 min") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30 to "30 min", 60 to "1 h", 120 to "2 h", 240 to "4 h").forEach { (m, label) ->
                        OutlinedButton(onClick = { haptics.tap(); CheckIn.start(context, m) }, shape = RoundedCornerShape(16.dp)) { Text(label) }
                    }
                }
            }
        }
    }
}

/** Settings → Safety: a believable incoming call on demand. */
@Composable
fun FakeCallCard() {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var name by remember { mutableStateOf(FakeCall.callerName(context)) }
    val scheduled by FakeCall.scheduledAt.collectAsStateWithLifecycle()
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Fake call 📞", style = MaterialTheme.typography.titleMedium)
            Text(
                "Need a polite way out? Your phone will ring with a normal-looking call from the name below. Nothing is sent to anyone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40); FakeCall.setCallerName(context, name) },
                label = { Text("Caller name") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            )
            if (scheduled != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your phone will ring shortly.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { FakeCall.cancelScheduled(context) }, shape = RoundedCornerShape(16.dp)) { Text("Cancel") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10 to "10 s", 30 to "30 s", 60 to "1 min", 300 to "5 min").forEach { (sec, label) ->
                        OutlinedButton(onClick = { haptics.tap(); FakeCall.schedule(context, sec) }, shape = RoundedCornerShape(16.dp)) { Text(label) }
                    }
                }
            }
        }
    }
}

/** Settings → Safety: the anonymous nearby beacon over the offline mesh. */
@Composable
fun NearbySosCard() {
    val context = LocalContext.current
    val prefs = remember { SosPrefs(context) }
    var beacon by remember { mutableStateOf(prefs.nearbyBeacon) }
    var alerts by remember { mutableStateOf(prefs.nearbyAlerts) }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SOS without internet 📡", style = MaterialTheme.typography.titleMedium)
            Text(
                "With the offline mesh on, your SOS hops phone to phone to your contacts even when there's no network — floods, cyclones, shutdowns.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Also alert people nearby", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Anonymous: amend users within Bluetooth/Wi-Fi range see “someone nearby needs help” with a rough location (~100 m). Never sent to the internet.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(checked = beacon, onCheckedChange = { beacon = it; prefs.nearbyBeacon = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show nearby SOS alerts", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = alerts, onCheckedChange = { alerts = it; prefs.nearbyAlerts = it })
            }
        }
    }
}
