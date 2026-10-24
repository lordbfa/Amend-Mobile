package app.amend.messenger.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.IncomingSos
import app.amend.messenger.data.SosLocation
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.util.PressPattern
import app.amend.messenger.util.SosManager
import app.amend.messenger.util.SosState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SosRed = Color(0xFFE53935)
private val SosDeep = Color(0xFF7F0000)

/** Radar-style pulse used on SOS screens. */
@Composable
private fun SosPulse(modifier: Modifier, color: Color = Color.White) {
    val t = rememberInfiniteTransition(label = "sosPulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "p")
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val maxR = size.minDimension / 2
        repeat(3) { i ->
            val f = (p + i / 3f) % 1f
            drawCircle(color.copy(alpha = (1f - f) * 0.45f), maxR * (0.25f + 0.75f * f), c, style = Stroke(3.dp.toPx()))
        }
    }
}

/**
 * Shown on top of the whole app while an SOS is counting down or active.
 * When active it can be minimised to a red bar so the user can keep chatting.
 */
@Composable
fun SosOverlay(repo: AmendRepository?) {
    val context = LocalContext.current
    val state by SosManager.state.collectAsStateWithLifecycle()
    var minimised by remember { mutableStateOf(false) }
    LaunchedEffect(state is SosState.Countdown) { if (state is SosState.Countdown) minimised = false }

    when (val s = state) {
        is SosState.Idle -> Unit
        is SosState.Countdown -> CountdownScreen(s) { SosManager.cancelCountdown(context) }
        is SosState.Active -> {
            AnimatedContent(minimised, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "sosActive") { min ->
                if (min) SosBar(s) { minimised = false }
                else ActiveScreen(s, repo, onMinimise = { minimised = true })
            }
        }
    }
}

@Composable
private fun CountdownScreen(s: SosState.Countdown, onCancel: () -> Unit) {
    val haptics = rememberHaptics()
    val scale = remember { Animatable(1.3f) }
    LaunchedEffect(s.secondsLeft) {
        scale.snapTo(1.35f)
        scale.animateTo(1f, tween(500))
    }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(SosRed, SosDeep)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        SosPulse(Modifier.size(340.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(if (s.practice) "PRACTICE SOS" else "SOS", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text(
                "${s.secondsLeft}", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value },
            )
            Text(
                when {
                    s.practice -> "This is only practice — nothing will be sent."
                    s.recipients == 0 -> "You haven't chatted with anyone yet. The emergency dialer will still open."
                    else -> "Your live location will be sent to ${s.recipients} ${if (s.recipients == 1) "person" else "people"} and the emergency dialer will open."
                },
                color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
            )
        }
        Button(
            onClick = { haptics.heavy(); onCancel() },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(32.dp).fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(32.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = SosDeep),
        ) { Text("Cancel", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun ActiveScreen(s: SosState.Active, repo: AmendRepository?, onMinimise: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val number = remember { SosManager.emergencyNumber(context) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(SosRed, SosDeep)))) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.practice) "Practice SOS" else "SOS active", color = Color.White, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Text(
                    "Minimise", color = Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onMinimise() }.background(Color(0x33FFFFFF)).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                SosPulse(Modifier.fillMaxSize())
                Text("📍", fontSize = 56.sp)
            }
            val elapsed = (now - s.startedAt) / 1000
            Text("Sharing live location for ${elapsed / 60}:${"%02d".format(elapsed % 60)}", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    s.practice -> "Practice — nothing is being sent."
                    s.recipients == 0 -> "No contacts to alert yet."
                    s.lastSentAt == 0L -> "Sending to ${s.recipients} contacts…"
                    else -> "Reached ${s.reached} of ${s.recipients} contacts · last update ${(now - s.lastSentAt) / 1000}s ago"
                },
                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                s.location?.let { "%.5f, %.5f  ·  ±%d m".format(it.lat, it.lon, it.accuracyM.toInt()) }
                    ?: if (SosManager.hasLocationPermission(context)) "Finding your location…" else "Location permission is off — contacts get alerts without a map.",
                color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { haptics.heavy(); SosManager.openDialer(context) },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = SosDeep),
            ) { Text("📞  Call $number", style = MaterialTheme.typography.titleLarge) }
            Text(
                "Android lets you press call yourself. On most phones the emergency call also shares your location where the country supports it.",
                color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            HoldToStop { SosManager.stop(context) }
        }
    }
}

/** "I'm safe" — must be held for 3 seconds so it can't be ended by an accidental tap. */
@Composable
private fun HoldToStop(onStop: () -> Unit) {
    val haptics = rememberHaptics()
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(30.dp)).background(Color(0x33FFFFFF))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    haptics.tick()
                    val job = scope.launch {
                        progress.animateTo(1f, tween(3_000, easing = LinearEasing))
                        haptics.success()
                        onStop()
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) {
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(250)) }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = progress.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }.background(Color(0xFF2BB673)))
        Text("✅  Hold 3 s: I'm safe — stop SOS", color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SosBar(s: SosState.Active, onExpand: () -> Unit) {
    val t = rememberInfiniteTransition(label = "bar")
    val a by t.animateFloat(0.75f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(SosRed.copy(alpha = a)).clickable { onExpand() }
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🆘", fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                if (s.practice) "Practice SOS — tap to open" else "SOS active · sharing live location · tap to open",
                color = Color.White, style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** Red SOS button: tap 4 times quickly to start an SOS. Shows progress dots. */
@Composable
fun SosButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val pattern = remember { PressPattern(presses = 4, windowMs = 3_000) }
    var taps by remember { mutableIntStateOf(0) }
    LaunchedEffect(taps) { if (taps > 0) { delay(3_000); taps = 0 } }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(SosRed)
            .clickable {
                haptics.heavy()
                if (pattern.press()) {
                    taps = 0
                    SosManager.trigger(context)
                } else {
                    taps = pattern.count()
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("SOS", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        AnimatedVisibility(taps > 0) {
            Row(Modifier.padding(start = 6.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier.padding(horizontal = 1.5.dp).size(6.dp).clip(CircleShape)
                            .background(if (i < taps) Color.White else Color(0x55FFFFFF))
                    )
                }
            }
        }
    }
}

/** Big red card for a friend's active SOS (Chats header and inside their chat). */
@Composable
fun IncomingSosCard(sos: IncomingSos, name: String, onOpenChat: (() -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() / 1000 } }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(SosRed, SosDeep)))
            .let { if (onOpenChat != null) it.clickable { onOpenChat() } else it }
    ) {
        SosPulse(Modifier.size(140.dp).align(Alignment.CenterEnd))
        Column(Modifier.padding(16.dp)) {
            Text("🆘 $name needs help", color = Color.White, style = MaterialTheme.typography.titleLarge)
            val loc: SosLocation? = sos.location
            Text(
                if (loc != null) "Live location · ±${loc.accuracyM.toInt()} m · updated ${formatAgo(now - sos.updatedAt)}"
                else "Started ${formatAgo(now - sos.startedAt)} · waiting for location",
                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loc != null) {
                    Button(
                        onClick = {
                            val geo = Intent(Intent.ACTION_VIEW, Uri.parse(loc.geoUri()))
                            runCatching { context.startActivity(geo) }.onFailure {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loc.mapLink())))
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = SosDeep),
                    ) { Text("Open map") }
                }
                Button(
                    onClick = { SosManager.openDialer(context) },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF), contentColor = Color.White),
                ) { Text("Call ${SosManager.emergencyNumber(context)}") }
                Text(
                    "Hide", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.CenterVertically).clickable { onDismiss() }.padding(8.dp),
                )
            }
        }
    }
}

fun formatAgo(seconds: Long): String = when {
    seconds < 10 -> "just now"
    seconds < 60 -> "${seconds}s ago"
    seconds < 3600 -> "${seconds / 60} min ago"
    else -> "${seconds / 3600} h ago"
}
