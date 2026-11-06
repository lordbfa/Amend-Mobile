package app.amend.messenger.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import app.amend.messenger.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.MainActivity
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.CallLog
import app.amend.messenger.data.CallOutcome
import app.amend.messenger.data.ChatMessage
import app.amend.messenger.data.formatCallDuration
import app.amend.messenger.ui.components.GlassCard
import app.amend.messenger.ui.components.LeafAvatar
import app.amend.messenger.ui.components.bouncy
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.util.CallManager
import app.amend.messenger.util.CallPhase
import app.amend.messenger.util.CallPrefs
import app.amend.messenger.util.CallUi
import app.amend.messenger.util.Rtc
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private fun callPermissions(video: Boolean) =
    if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)

private fun granted(context: Context, perms: Array<String>) =
    perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** Returns a function that starts a call, asking for the microphone (and camera) first if needed. */
@Composable
fun rememberCallStarter(): (String, Boolean) -> Unit {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<Pair<String, Boolean>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val p = pending[0]
        pending[0] = null
        if (p != null && result.values.all { it }) CallManager.startCall(p.first, p.second)
    }
    return remember(launcher) {
        { peer: String, video: Boolean ->
            val perms = callPermissions(video)
            if (granted(context, perms)) CallManager.startCall(peer, video)
            else {
                pending[0] = peer to video
                launcher.launch(perms)
            }
        }
    }
}

// ======================================================================= overlay

/** Sits above everything: incoming-call screen, in-call screen, or a "return to call" bar. */
@Composable
fun CallOverlay(repo: AmendRepository?) {
    val st by CallManager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(st.phase) {
        if (st.phase == CallPhase.IDLE) (context as? MainActivity)?.showOverLockScreen(false)
    }
    if (repo == null || st.phase == CallPhase.IDLE) return

    val answerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) CallManager.answer() else CallManager.decline()
    }
    fun answer() {
        val perms = callPermissions(st.video)
        if (granted(context, perms)) CallManager.answer() else answerLauncher.launch(perms)
    }
    // "Answer" tapped in the notification
    val answerRequested by CallManager.answerRequest.collectAsStateWithLifecycle()
    LaunchedEffect(answerRequested, st.phase) {
        if (answerRequested && st.phase == CallPhase.INCOMING) {
            CallManager.answerRequest.value = false
            answer()
        }
    }

    var minimized by remember(st.callId) { mutableStateOf(false) }
    if (minimized && st.phase != CallPhase.INCOMING && st.phase != CallPhase.ENDED) {
        ReturnToCallBar(repo, st) { minimized = false }
        return
    }
    BackHandler(enabled = st.phase != CallPhase.INCOMING) { minimized = true }

    Box(Modifier.fillMaxSize().background(Color(0xFF06140E)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
        if (st.phase == CallPhase.INCOMING) IncomingCall(repo, st, onAnswer = { answer() }, onDecline = { CallManager.decline() })
        else InCall(repo, st, onMinimize = { minimized = true })
    }
}

@Composable
private fun ReturnToCallBar(repo: AmendRepository, st: CallUi, onOpen: () -> Unit) {
    val elapsed = rememberElapsed(st.connectedAt)
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp)) {
        Row(
            Modifier.align(Alignment.TopCenter).clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1C9A62), Color(0xFF2BB673))))
                .clickable { onOpen() }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (st.video) Icons.Rounded.Videocam else Icons.Rounded.PhoneInTalk, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                repo.displayName(st.peer) + " · " + (if (st.connectedAt > 0) formatCallDuration(elapsed) else statusText(st)) + " · tap to return",
                color = Color.White, style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun rememberElapsed(since: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            kotlinx.coroutines.delay(500)
        }
    }
    return if (since > 0) now - since else 0
}

private fun statusText(st: CallUi): String = when (st.phase) {
    CallPhase.OUTGOING -> if (st.ringing) "Ringing…" else "Calling…"
    CallPhase.CONNECTING -> "Connecting…"
    CallPhase.CONNECTED -> if (st.reconnecting) "Reconnecting…" else ""
    CallPhase.ENDED -> st.endReason ?: "Call ended"
    else -> ""
}

// ======================================================================= incoming

@Composable
private fun IncomingCall(repo: AmendRepository, st: CallUi, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val profiles by repo.profiles.collectAsStateWithLifecycle()
    val t = rememberInfiniteTransition(label = "incoming")
    val ripple by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200)), label = "ripple")
    val bob by t.animateFloat(0f, -10f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "bob")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF06140E), Color(0xFF0F3B2A), Color(0xFF1C7A4E))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            Text(if (st.video) "amend video call" else "amend voice call", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleSmall)
            EncryptedTag()
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                // three growing rings, like ripples on a pond
                Canvas(Modifier.fillMaxSize()) {
                    repeat(3) { i ->
                        val p = (ripple + i / 3f) % 1f
                        drawCircle(Color(0xFF5BE39B).copy(alpha = (1 - p) * 0.45f), radius = size.minDimension / 2 * (0.45f + 0.55f * p), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                    }
                }
                LeafAvatar(st.peer, profiles[st.peer], 140.dp)
            }
            Spacer(Modifier.height(20.dp))
            Text(repo.displayName(st.peer), color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1.3f))
            Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundAction(Icons.Rounded.CallEnd, "Decline", Color(0xFFE53935), onDecline)
                RoundAction(
                    if (st.video) Icons.Rounded.Videocam else Icons.Rounded.Call, "Answer", Color(0xFF2BB673), onAnswer,
                    modifier = Modifier.graphicsLayer { translationY = bob.dp.toPx() },
                )
            }
        }
    }
}

@Composable
private fun EncryptedTag() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text("End-to-end encrypted", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier, size: Int = 72) {
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            Modifier.size(size.dp).bouncy(source).clip(CircleShape).background(color)
                .clickable(interactionSource = source, indication = null) { haptics.heavy(); onClick() },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size((size * 0.42f).dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

// ======================================================================= in call

@Composable
private fun InCall(repo: AmendRepository, st: CallUi, onMinimize: () -> Unit) {
    val profiles by repo.profiles.collectAsStateWithLifecycle()
    val remote by CallManager.remoteVideo.collectAsStateWithLifecycle()
    val local by CallManager.localVideo.collectAsStateWithLifecycle()
    val elapsed = rememberElapsed(st.connectedAt)
    val showRemoteVideo = st.video && remote != null && st.phase == CallPhase.CONNECTED

    Box(Modifier.fillMaxSize()) {
        if (showRemoteVideo) {
            VideoRenderer(remote!!, mirror = false, overlay = false, modifier = Modifier.fillMaxSize())
        } else {
            VoiceBackdrop(repo, st, profiles[st.peer])
        }

        // our own camera, as a small draggable window
        val l = local
        if (st.video && l != null && st.cameraOn && st.phase != CallPhase.ENDED) {
            var drag by remember { mutableStateOf(Offset.Zero) }
            // before the friend picks up, our camera fills the screen
            val full = !showRemoteVideo
            Box(
                if (full) Modifier.fillMaxSize()
                else Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 70.dp, end = 14.dp)
                    .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
                    .size(width = 110.dp, height = 160.dp).clip(RoundedCornerShape(18.dp))
                    .pointerInput(Unit) { detectDragGestures { change, amount -> change.consume(); drag += amount } }
            ) {
                VideoRenderer(l, mirror = st.frontCamera, overlay = !full, modifier = Modifier.fillMaxSize())
                if (full) Box(Modifier.fillMaxSize().background(Color(0x66000000)))
            }
        }

        // top bar
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMinimize) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Minimise", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    if (showRemoteVideo) {
                        Text(repo.displayName(st.peer), color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (st.reconnecting) "Reconnecting…" else formatCallDuration(elapsed),
                            color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        // controls
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(st.phase != CallPhase.ENDED, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    Modifier.clip(RoundedCornerShape(36.dp)).background(Color(0x66000000)).padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Control(if (st.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (st.muted) "Unmute" else "Mute", st.muted) { CallManager.toggleMute() }
                    Control(if (st.speaker) Icons.AutoMirrored.Rounded.VolumeUp else Icons.Rounded.PhoneInTalk, if (st.speaker) "Speaker on" else "Speaker off", st.speaker) { CallManager.toggleSpeaker() }
                    if (st.video) {
                        Control(if (st.cameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, if (st.cameraOn) "Camera off" else "Camera on", !st.cameraOn) { CallManager.toggleCamera() }
                        Control(Icons.Rounded.Cameraswitch, "Switch camera", false) { CallManager.switchCamera() }
                    }
                    RoundAction(Icons.Rounded.CallEnd, "", Color(0xFFE53935), { CallManager.hangUp() }, size = 60)
                }
            }
        }
    }
}

/** For voice calls (and video calls before the picture arrives): a breathing avatar and the call status. */
@Composable
private fun VoiceBackdrop(repo: AmendRepository, st: CallUi, profile: app.amend.messenger.data.Profile?) {
    val elapsed = rememberElapsed(st.connectedAt)
    val t = rememberInfiniteTransition(label = "voice")
    val breathe by t.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "breathe")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600)), label = "wave")
    val connected = st.phase == CallPhase.CONNECTED
    val glow by animateFloatAsState(if (connected) 1f else 0.35f, tween(800), label = "glow")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF06140E), Color(0xFF0F3B2A), Color(0xFF155E3E))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(repo.displayName(st.peer), color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    st.phase == CallPhase.CONNECTED && !st.reconnecting -> formatCallDuration(elapsed)
                    else -> statusText(st)
                },
                color = if (st.phase == CallPhase.ENDED) Color(0xFFFFB4B4) else Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleMedium,
            )
            EncryptedTag()
            Spacer(Modifier.height(50.dp))
            Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    repeat(4) { i ->
                        val p = (wave + i / 4f) % 1f
                        drawCircle(
                            Color(0xFF5BE39B).copy(alpha = (1 - p) * 0.35f * glow),
                            radius = size.minDimension / 2 * (0.42f + 0.58f * p),
                        )
                    }
                }
                Box(Modifier.graphicsLayer { scaleX = breathe; scaleY = breathe }) { LeafAvatar(st.peer, profile, 150.dp) }
            }
        }
    }
}

@Composable
private fun Control(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.size(52.dp).bouncy(source).clip(CircleShape)
            .background(if (active) Color.White else Color(0x33FFFFFF))
            .clickable(interactionSource = source, indication = null) { haptics.tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = if (active) Color(0xFF0F3B2A) else Color.White) }
}

/** Draws a WebRTC video track. */
@Composable
private fun VideoRenderer(track: VideoTrack, mirror: Boolean, overlay: Boolean, modifier: Modifier) {
    var view by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(Rtc.egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (overlay) setZOrderMediaOverlay(true)
                view = this
            }
        },
        update = { it.setMirror(mirror) },
        onRelease = { runCatching { it.release() } },
    )
    DisposableEffect(track, view) {
        val v = view
        if (v != null) runCatching { track.addSink(v) }
        onDispose { if (v != null) runCatching { track.removeSink(v) } }
    }
}

// ======================================================================= chat history + settings

/** A call in the chat: what happened, and a button to call back. */
@Composable
fun CallLogContent(message: ChatMessage, textColor: Color, onCall: (Boolean) -> Unit) {
    val log = remember(message.extra) { CallLog.fromJson(message.extra) }
    val video = log?.video == true
    val missed = log != null && !log.outgoing && (log.outcome == CallOutcome.MISSED)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(if (missed) Color(0x33E53935) else textColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (video) Icons.Rounded.Videocam else Icons.Rounded.Call, contentDescription = null,
                tint = if (missed) Color(0xFFE53935) else textColor, modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(message.text.substringAfter(' '), color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(10.dp))
        Text(
            "Call back", color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(textColor.copy(alpha = 0.15f)).clickable { onCall(video) }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * Settings: a TURN server, so calls connect across strict networks (for example
 * Finland ↔ Bangladesh on mobile data). Paste a Metered link, a Cloudflare key, or type one in.
 */
@Composable
fun CallsCard() {
    val context = LocalContext.current
    val prefs = remember { CallPrefs(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptics = rememberHaptics()
    var service by remember { mutableStateOf(prefs.turnService) }
    var metered by remember { mutableStateOf(prefs.meteredLink) }
    var cfKey by remember { mutableStateOf(prefs.cloudflareKeyId) }
    var cfToken by remember { mutableStateOf(prefs.cloudflareToken) }
    var url by remember { mutableStateOf(prefs.turnUrl) }
    var user by remember { mutableStateOf(prefs.turnUser) }
    var pass by remember { mutableStateOf(prefs.turnPass) }
    var advanced by remember { mutableStateOf(prefs.turnUrl.isNotBlank() || prefs.cloudflareKeyId.isNotBlank()) }
    var status by remember { mutableStateOf<String?>(if (prefs.hasTurn) "TURN server saved" else null) }
    var testing by remember { mutableStateOf(false) }
    var ok by remember { mutableStateOf<Boolean?>(null) }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current

    fun save() {
        prefs.turnService = service
        prefs.meteredLink = metered; prefs.cloudflareKeyId = cfKey; prefs.cloudflareToken = cfToken
        prefs.turnUrl = url; prefs.turnUser = user; prefs.turnPass = pass
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Calls 📞", style = MaterialTheme.typography.titleMedium)
            Text(
                "Calls go straight from phone to phone, end-to-end encrypted. Between countries or on mobile data " +
                    "(carriers like Grameenphone, Robi or Banglalink share one address among many users) the phones often can't reach each other directly. " +
                    "A TURN server relays the encrypted call — it can't listen in. Only one of you needs one.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("amend TURN service (Cloudflare)", style = MaterialTheme.typography.titleSmall)
            Text(
                if (CallPrefs.DEFAULT_TURN_SERVICE.isNotBlank() && service == CallPrefs.DEFAULT_TURN_SERVICE)
                    "Built in ✓ — calls use Cloudflare's global TURN network automatically. Nothing to set up."
                else "The address of an amend TURN worker (https://…workers.dev). Leave empty to use one of the options below.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(service, { service = it; ok = null }, label = { Text("TURN service address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Or: free Metered TURN (20 GB/month)", style = MaterialTheme.typography.titleSmall)
            Text(
                "1. Sign up at metered.ca → TURN Server (free plan)\n2. Open your app → copy the “credentials” link (…metered.live/api/v1/turn/credentials?apiKey=…)\n3. Paste it below, Save, then Test",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Open metered.ca", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable { uri.openUri("https://www.metered.ca/tools/openrelay/") }.padding(vertical = 2.dp),
            )
            OutlinedTextField(metered, { metered = it; ok = null }, label = { Text("Metered credentials link") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text(
                if (advanced) "Hide other options" else "Other options: Cloudflare or your own TURN server",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable { advanced = !advanced }.padding(vertical = 4.dp),
            )
            AnimatedVisibility(advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cloudflare TURN (dash.cloudflare.com → Realtime → TURN)", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(cfKey, { cfKey = it; ok = null }, label = { Text("TURN key ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        cfToken, { cfToken = it; ok = null }, label = { Text("API token") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Your own server (e.g. coturn)", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(url, { url = it; ok = null }, label = { Text("turn:host:3478 (several: separate with commas)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(user, { user = it; ok = null }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        pass, { pass = it; ok = null }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { save(); haptics.success(); status = "Saved ✓"; ok = null }, shape = RoundedCornerShape(16.dp)) { Text("Save") }
                androidx.compose.material3.OutlinedButton(
                    enabled = !testing,
                    onClick = {
                        save()
                        haptics.tap()
                        testing = true
                        status = "Testing…"
                        scope.launch {
                            val servers = prefs.resolveIceServers(onlyTurn = true)
                            val works = if (servers.isEmpty()) false else app.amend.messenger.util.testTurnServers(context, servers)
                            testing = false
                            ok = works
                            status = when {
                                servers.isEmpty() -> "No TURN server found — check the link or key"
                                works -> "✓ TURN works — calls will connect across networks"
                                else -> "✗ The TURN server didn't answer — check the details"
                            }
                            if (works) haptics.success() else haptics.reject()
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Test") }
            }
            status?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall,
                    color = when (ok) { true -> Color(0xFF2BB673); false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.onSurfaceVariant },
                )
            }
        }
    }
}
