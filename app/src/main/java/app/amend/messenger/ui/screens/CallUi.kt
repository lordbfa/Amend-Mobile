package app.amend.messenger.ui.screens

import android.Manifest
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import app.amend.messenger.data.EcoRules
import app.amend.messenger.data.Profile
import app.amend.messenger.eco.Garden
import app.amend.messenger.ui.theme.LocalEfficiency
import app.amend.messenger.ui.tr
import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
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
import androidx.compose.material.icons.rounded.CancelPresentation
import androidx.compose.material.icons.rounded.PresentToAll
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
import app.amend.messenger.util.TextureVideoView
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

    // amend shrunk into the floating picture-in-picture window: only the friend's picture fits there
    if ((context as? MainActivity)?.inPictureInPicture?.value == true) {
        PipCall(repo, st)
        return
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

/** The call inside the small picture-in-picture window: the friend's video, or their name and the call status. */
@Composable
private fun PipCall(repo: AmendRepository, st: CallUi) {
    val remote by CallManager.remoteVideo.collectAsStateWithLifecycle()
    val r = remote
    Box(Modifier.fillMaxSize().background(Color(0xFF06140E)), contentAlignment = Alignment.Center) {
        if (st.video && r != null && st.phase == CallPhase.CONNECTED) {
            VideoRenderer(r, mirror = false, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(repo.displayName(st.peer), color = Color.White, style = MaterialTheme.typography.titleSmall)
                statusText(st).takeIf { it.isNotEmpty() }?.let {
                    Text(it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
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

// ======================================================================= look

private val Night = Color(0xFF06140E)
private val ForestDeep = Color(0xFF0F3B2A)
private val Moss = Color(0xFF1C7A4E)
private val Leaf = Color(0xFF2BB673)
private val LeafBright = Color(0xFF5BE39B)
private val Sky = Color(0xFF4FC3F7)
private val Blossom = Color(0xFFFF7AA2)
private val Danger = Color(0xFFE53935)
private val Mist = Color(0xFFE6F5EC)

/** Frosted glass: a light veil with a hairline edge. */
private fun Modifier.glass(shape: Shape, alpha: Float = 0.12f): Modifier =
    clip(shape).background(Color.White.copy(alpha = alpha)).border(1.dp, Color.White.copy(alpha = 0.2f), shape)

/** Night-to-moss gradient with soft lights that drift slowly (still in efficiency mode). */
@Composable
private fun CallBackdrop(modifier: Modifier = Modifier) {
    val still = LocalEfficiency.current
    val t = rememberInfiniteTransition(label = "backdrop")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000), RepeatMode.Reverse), label = "drift")
    val d = if (still) 0.5f else drift
    Box(
        modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Night, ForestDeep, Moss)))
            .drawBehind {
                fun glow(c: Color, cx: Float, cy: Float, r: Float) =
                    drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(cx, cy), radius = r), radius = r, center = Offset(cx, cy))
                val w = size.width
                val h = size.height
                glow(LeafBright.copy(alpha = 0.30f), w * (0.08f + 0.18f * d), h * 0.30f, w * 0.75f)
                glow(Sky.copy(alpha = 0.18f), w * (0.98f - 0.12f * d), h * 0.56f, w * 0.6f)
                glow(Blossom.copy(alpha = 0.12f), w * 0.12f, h * (0.86f - 0.05f * d), w * 0.5f)
            },
    )
}

@Composable
private fun EncryptedPill() {
    Row(Modifier.glass(CircleShape, 0.10f).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = LeafBright, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text("End-to-end encrypted", color = Mist.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall)
    }
}

/** The friend's avatar: ripples while it rings, a calm breathing halo once connected. */
@Composable
private fun HeroAvatar(peer: String, profile: Profile?, garden: Garden?, box: Dp, ringing: Boolean) {
    val still = LocalEfficiency.current
    val t = rememberInfiniteTransition(label = "hero")
    val ripple by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400)), label = "ripple")
    val breathe by t.animateFloat(0.95f, 1.04f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breathe")
    Box(Modifier.size(box), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r0 = size.minDimension / 2
            if (ringing) {
                repeat(3) { i ->
                    val p = if (still) (i + 1) / 3f else (ripple + i / 3f) % 1f
                    val r = r0 * (0.55f + 0.45f * p)
                    drawCircle(LeafBright.copy(alpha = (1 - p) * 0.10f), radius = r)
                    drawCircle(LeafBright.copy(alpha = (1 - p) * 0.6f), radius = r, style = Stroke(1.5.dp.toPx()))
                }
            } else {
                val s = if (still) 1f else breathe
                drawCircle(LeafBright.copy(alpha = 0.10f), radius = r0 * s)
                drawCircle(LeafBright.copy(alpha = 0.16f), radius = r0 * 0.82f * s)
            }
        }
        LeafAvatar(peer, profile, box * 0.57f, garden)
    }
}

/** A round call button with its label underneath. [active] = switched on (white); [danger] = red. */
@Composable
private fun CallControl(
    icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit,
    danger: Boolean = false, size: Dp = 60.dp, description: String = label,
) {
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        when { danger -> Danger; active -> Color.White; else -> Color.White.copy(alpha = 0.14f) }, tween(180), label = "controlBg",
    )
    val fg by animateColorAsState(if (active && !danger) ForestDeep else Color.White, tween(180), label = "controlFg")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).bouncy(source).clip(CircleShape).background(bg)
                .border(1.dp, if (active || danger) Color.Transparent else Color.White.copy(alpha = 0.2f), CircleShape)
                .clickable(interactionSource = source, indication = null, role = Role.Button) {
                    if (danger) haptics.heavy() else haptics.tick()
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = description, tint = fg, modifier = Modifier.size(size * 0.42f)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Frosted dock that holds the call controls: a row at the bottom, or a rail at the side in landscape. */
@Composable
private fun ControlDock(vertical: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    if (vertical) {
        Column(
            modifier.glass(shape, 0.10f).padding(horizontal = 10.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    } else {
        Row(
            modifier.glass(shape, 0.10f).padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top,
        ) { content() }
    }
}

@Composable
private fun CallControls(st: CallUi, size: Dp) {
    CallControl(
        if (st.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (st.muted) "Muted" else "Mute", st.muted,
        { CallManager.toggleMute() }, size = size, description = if (st.muted) "Unmute" else "Mute",
    )
    CallControl(
        Icons.AutoMirrored.Rounded.VolumeUp, "Speaker", st.speaker,
        { CallManager.toggleSpeaker() }, size = size, description = if (st.speaker) "Speaker on" else "Speaker off",
    )
    if (st.video) {
        CallControl(
            if (st.cameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, "Camera", !st.cameraOn,
            { CallManager.toggleCamera() }, size = size, description = if (st.cameraOn) "Camera off" else "Camera on",
        )
        if (!st.sharingScreen) CallControl(Icons.Rounded.Cameraswitch, "Flip", false, { CallManager.switchCamera() }, size = size, description = "Switch camera")
    }
    CallControl(Icons.Rounded.CallEnd, "End", false, { CallManager.hangUp() }, danger = true, size = size, description = "End call")
}

@Composable
private fun RoundGlassButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.size(44.dp).bouncy(source).glass(CircleShape, 0.14f)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

// ======================================================================= incoming

@Composable
private fun IncomingCall(repo: AmendRepository, st: CallUi, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val context = LocalContext.current
    val profiles by repo.profiles.collectAsStateWithLifecycle()
    val conversations by repo.conversations.collectAsStateWithLifecycle()
    val garden = conversations[st.peer]?.let { EcoRules.gardenFor(repo.me, it) }
    val peer = st.peer
    // a quick reply turns the call down and says why, in a message
    val reply: (String) -> Unit = { text ->
        onDecline()
        repo.launchInBackground { repo.sendText(peer, text) }
    }
    val message: () -> Unit = {
        onDecline()
        (context as? MainActivity)?.pendingPeer?.value = peer
    }
    Box(Modifier.fillMaxSize()) {
        CallBackdrop()
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            val landscape = maxWidth > maxHeight
            if (landscape) {
                val hero = minOf(260.dp, maxHeight * 0.62f)
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        IncomingHeader(st.video)
                        HeroAvatar(peer, profiles[peer], garden, hero, ringing = true)
                        CallerInfo(repo.displayName(peer), garden != null)
                    }
                    Spacer(Modifier.width(24.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        QuickReplies(reply)
                        SlideToAnswer(st.video, onAnswer)
                        IncomingActions(onDecline, message)
                    }
                }
            } else {
                val hero = minOf(300.dp, maxHeight * 0.36f)
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    IncomingHeader(st.video)
                    Spacer(Modifier.weight(1f))
                    HeroAvatar(peer, profiles[peer], garden, hero, ringing = true)
                    CallerInfo(repo.displayName(peer), garden != null)
                    Spacer(Modifier.weight(1f))
                    QuickReplies(reply)
                    Spacer(Modifier.height(18.dp))
                    SlideToAnswer(st.video, onAnswer)
                    Spacer(Modifier.height(20.dp))
                    IncomingActions(onDecline, message)
                }
            }
        }
    }
}

@Composable
private fun IncomingHeader(video: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (video) "amend video call" else "amend voice call", color = Mist.copy(alpha = 0.75f), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        EncryptedPill()
    }
}

@Composable
private fun CallerInfo(name: String, sharedGarden: Boolean, status: String? = null, statusColor: Color = LeafBright) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 6.dp)) {
        Text(
            name, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        AnimatedContent(targetState = status, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "status") { s ->
            when {
                s != null -> Text(s, color = statusColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                sharedGarden -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Eco, contentDescription = null, tint = LeafBright, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Your shared garden", color = Mist.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                }
                else -> Spacer(Modifier.height(1.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickReplies(onReply: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (label in listOf("Can't talk — text me", "Call you in 5 min", "On my way")) {
            Text(
                label, color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.glass(CircleShape, 0.10f).clickable(role = Role.Button) { onReply(tr(label)) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

/** Drag the green knob across (or tap it) to answer. */
@Composable
private fun SlideToAnswer(video: Boolean, onAnswer: () -> Unit) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val still = LocalEfficiency.current
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val offset = remember { Animatable(0f) }
    var trackWidth by remember { mutableFloatStateOf(0f) }
    var answered by remember { mutableStateOf(false) }
    val knob = 64.dp
    val inset = 6.dp
    val maxX = (trackWidth - with(density) { (knob + inset * 2).toPx() }).coerceAtLeast(1f)
    val t = rememberInfiniteTransition(label = "slideHint")
    val hint by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500)), label = "hint")
    fun answer() {
        if (answered) return
        answered = true
        haptics.success()
        onAnswer()
    }
    Box(
        Modifier.fillMaxWidth().height(knob + inset * 2).glass(CircleShape, 0.12f).onSizeChanged { trackWidth = it.width.toFloat() },
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(start = knob).graphicsLayer { alpha = 1f - (offset.value / maxX).coerceIn(0f, 1f) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Slide to answer", color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            repeat(3) { i ->
                val wave = (hint * 3f - i).let { if (it in 0f..1f) 1f - kotlin.math.abs(it - 0.5f) * 2f else 0f }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
                    tint = Color.White.copy(alpha = if (still) 0.6f else 0.25f + 0.7f * wave), modifier = Modifier.size(18.dp),
                )
            }
        }
        Box(
            Modifier.padding(inset)
                .offset { IntOffset((if (rtl) -offset.value else offset.value).roundToInt(), 0) }
                .size(knob).clip(CircleShape).background(Brush.linearGradient(listOf(LeafBright, Leaf)))
                .pointerInput(maxX, rtl) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offset.value > maxX * 0.7f) {
                                scope.launch { offset.animateTo(maxX) }
                                answer()
                            } else {
                                scope.launch { offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { change, dx ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + if (rtl) -dx else dx).coerceIn(0f, maxX)) }
                    }
                }
                .clickable(role = Role.Button) { answer() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (video) Icons.Rounded.Videocam else Icons.Rounded.Call, contentDescription = "Answer", tint = ForestDeep, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun IncomingActions(onDecline: () -> Unit, onMessage: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        CallControl(Icons.Rounded.CallEnd, "Decline", false, onDecline, danger = true)
        CallControl(Icons.Rounded.ChatBubble, "Message", false, onMessage)
    }
}

// ======================================================================= in call

@Composable
private fun InCall(repo: AmendRepository, st: CallUi, onMinimize: () -> Unit) {
    val profiles by repo.profiles.collectAsStateWithLifecycle()
    val conversations by repo.conversations.collectAsStateWithLifecycle()
    val remote by CallManager.remoteVideo.collectAsStateWithLifecycle()
    val local by CallManager.localVideo.collectAsStateWithLifecycle()
    val elapsed = rememberElapsed(st.connectedAt)
    val showRemoteVideo = st.video && remote != null && st.phase == CallPhase.CONNECTED
    val garden = conversations[st.peer]?.let { EcoRules.gardenFor(repo.me, it) }
    val context = LocalContext.current
    // Android asks "start sharing your screen?" first; only a yes starts it
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) CallManager.startScreenShare(data)
    }
    val canShare = st.video && st.phase == CallPhase.CONNECTED
    val onShare: () -> Unit = {
        if (st.sharingScreen) CallManager.stopScreenShare()
        else context.getSystemService(MediaProjectionManager::class.java)?.let { shareLauncher.launch(it.createScreenCaptureIntent()) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val railOnSide = landscape && showRemoteVideo
        val controlSize = if (maxWidth < 360.dp || st.video) 54.dp else 60.dp
        // read here: inside the Column below, BoxWithConstraints' maxHeight isn't reachable implicitly
        val screenHeight = maxHeight

        if (showRemoteVideo) {
            // double-tap it: the whole picture (a shared screen, a sideways phone) ↔ fill the screen
            VideoRenderer(remote!!, mirror = false, modifier = Modifier.fillMaxSize(), adaptive = true)
            Box(Modifier.fillMaxWidth().height(180.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Night.copy(alpha = 0.7f), Color.Transparent))))
            Box(Modifier.fillMaxWidth().height(260.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Night.copy(alpha = 0.85f)))))
        } else {
            CallBackdrop()
        }

        // our own camera, as a small draggable window (not while the screen is shared: it would
        // show the screen inside itself, and the camera rests meanwhile)
        val l = local
        if (st.video && l != null && st.cameraOn && !st.sharingScreen && st.phase != CallPhase.ENDED) {
            var drag by remember { mutableStateOf(Offset.Zero) }
            // before the friend picks up, our camera fills the screen
            val full = !showRemoteVideo
            Box(
                if (full) Modifier.fillMaxSize()
                else Modifier.align(Alignment.TopEnd).statusBarsPadding()
                    .padding(top = 76.dp, end = if (railOnSide) 112.dp else 16.dp)
                    .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
                    .size(width = if (landscape) 150.dp else 112.dp, height = if (landscape) 100.dp else 164.dp)
                    .clip(RoundedCornerShape(22.dp)).border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                    .pointerInput(Unit) { detectDragGestures { change, amount -> change.consume(); drag += amount } }
            ) {
                // Drawn as part of the normal screen, not as its own video layer: it gets the rounded
                // corners, and it can never end up stacked above the friend's video. (As a layer it
                // did: it starts full screen before the friend picks up, and the friend's video,
                // created later, stayed hidden behind it until the camera was switched off and on.)
                LocalVideo(l, mirror = st.frontCamera, modifier = Modifier.fillMaxSize())
                if (full) Box(Modifier.fillMaxSize().background(Color(0x66000000)))
            }
        }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(start = 20.dp, end = if (railOnSide) 112.dp else 20.dp, top = 8.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CallTopBar(repo, st, elapsed, showRemoteVideo, onMinimize, onShare = if (canShare) onShare else null)
            if (st.sharingScreen && st.phase != CallPhase.ENDED) {
                Spacer(Modifier.height(10.dp))
                SharingScreenChip { CallManager.stopScreenShare() }
            }
            if (showRemoteVideo) {
                Spacer(Modifier.weight(1f))
            } else {
                val hero = minOf(260.dp, screenHeight * if (landscape) 0.42f else 0.32f)
                Spacer(Modifier.weight(1f))
                HeroAvatar(st.peer, profiles[st.peer], garden, hero, ringing = st.phase != CallPhase.CONNECTED && st.phase != CallPhase.ENDED)
                CallerInfo(
                    repo.displayName(st.peer), garden != null,
                    status = statusText(st).ifEmpty { null },
                    statusColor = if (st.phase == CallPhase.ENDED) Color(0xFFFFB4B4) else LeafBright,
                )
                if (st.phase == CallPhase.CONNECTED && !st.reconnecting && !landscape) {
                    Spacer(Modifier.height(16.dp))
                    VoiceWave(active = !st.muted)
                }
                Spacer(Modifier.weight(1f))
                if (st.phase == CallPhase.OUTGOING || st.phase == CallPhase.CONNECTING) {
                    Row(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(Night.copy(alpha = 0.35f)).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Eco, contentDescription = null, tint = LeafBright, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Peer-to-peer — no company server in the middle", color = Mist.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            if (!railOnSide) {
                AnimatedVisibility(
                    st.phase != CallPhase.ENDED,
                    enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 },
                ) {
                    ControlDock(vertical = false, modifier = Modifier.fillMaxWidth()) { CallControls(st, controlSize) }
                }
            }
        }
        if (railOnSide) {
            AnimatedVisibility(
                st.phase != CallPhase.ENDED, enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).navigationBarsPadding().padding(end = 16.dp),
            ) {
                ControlDock(vertical = true) { CallControls(st, 48.dp) }
            }
        }
    }
}

@Composable
private fun CallTopBar(repo: AmendRepository, st: CallUi, elapsed: Long, video: Boolean, onMinimize: () -> Unit, onShare: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundGlassButton(Icons.Rounded.KeyboardArrowDown, "Minimise", onMinimize)
        if (video) {
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(repo.displayName(st.peer), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot()
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (st.reconnecting) "Reconnecting…" else formatCallDuration(elapsed),
                        color = Mist.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            onShare?.let { ShareScreenButton(st.sharingScreen, it) }
        } else {
            Spacer(Modifier.weight(1f))
            if (st.phase == CallPhase.CONNECTED && !st.reconnecting) {
                Row(Modifier.glass(CircleShape, 0.12f).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveDot()
                    Spacer(Modifier.width(6.dp))
                    Text(formatCallDuration(elapsed), color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.Lock, contentDescription = "End-to-end encrypted", tint = LeafBright, modifier = Modifier.size(13.dp))
                }
            } else {
                EncryptedPill()
            }
            Spacer(Modifier.weight(1f))
            if (onShare != null) ShareScreenButton(st.sharingScreen, onShare) else Spacer(Modifier.size(44.dp))
        }
    }
}

@Composable
private fun ShareScreenButton(sharing: Boolean, onClick: () -> Unit) {
    RoundGlassButton(
        if (sharing) Icons.Rounded.CancelPresentation else Icons.Rounded.PresentToAll,
        if (sharing) "Stop sharing your screen" else "Share your screen",
        onClick,
    )
}

/** Shown while our screen is being sent: what the friend sees, and how to stop. */
@Composable
private fun SharingScreenChip(onStop: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(Danger.copy(alpha = 0.9f))
            .clickable(role = Role.Button) { onStop() }.padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PresentToAll, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("You're sharing your screen · Stop", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** A small green dot that pulses while the call is live. */
@Composable
private fun LiveDot() {
    val still = LocalEfficiency.current
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "liveAlpha")
    Box(Modifier.size(8.dp).graphicsLayer { alpha = if (still) 1f else a }.clip(CircleShape).background(LeafBright))
}

/** Gently moving bars under the name while the call is connected; flat while you're muted. */
@Composable
private fun VoiceWave(active: Boolean) {
    val still = LocalEfficiency.current
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1400)), label = "wavePhase")
    val level by animateFloatAsState(if (active) 1f else 0.15f, tween(300), label = "waveLevel")
    Canvas(Modifier.width(170.dp).height(44.dp)) {
        val bars = 23
        val gapPx = size.width / bars
        val barW = gapPx * 0.55f
        for (i in 0 until bars) {
            val centre = 1f - kotlin.math.abs(i - bars / 2) / (bars / 2f)
            val wave = if (still) 0.5f else 0.5f + 0.5f * kotlin.math.sin(phase + i * 0.7f)
            val h = size.height * (0.12f + 0.88f * centre * (0.35f + 0.65f * wave) * level)
            drawRoundRect(
                Brush.verticalGradient(listOf(LeafBright, Leaf)),
                topLeft = Offset(i * gapPx + (gapPx - barW) / 2, (size.height - h) / 2),
                size = androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2),
                alpha = 0.45f + 0.55f * centre,
            )
        }
    }
}

/**
 * Draws a WebRTC video track as its own video layer, behind the rest of the screen: the
 * friend's video. [adaptive]: picks "fill the screen" or "show it whole" from the picture's
 * shape — a shared phone screen or a sideways camera is shown whole instead of cut off. A
 * double tap switches between the two. Our own camera uses [LocalVideo] instead.
 */
@Composable
private fun VideoRenderer(
    track: VideoTrack, mirror: Boolean, modifier: Modifier,
    adaptive: Boolean = false,
) {
    var view by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                val scaling = if (adaptive) AdaptiveScaling(this) else null
                init(Rtc.egl.eglBaseContext, scaling)
                tag = scaling
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                scaling?.let { s ->
                    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> s.apply() }
                    val taps = android.view.GestureDetector(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onDown(e: android.view.MotionEvent) = true
                        override fun onDoubleTap(e: android.view.MotionEvent): Boolean { s.toggle(); return true }
                    })
                    setOnTouchListener { v, e ->
                        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
                        taps.onTouchEvent(e)
                    }
                }
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

/** Our own camera, drawn as an ordinary view (see [TextureVideoView]). */
@Composable
private fun LocalVideo(track: VideoTrack, mirror: Boolean, modifier: Modifier) {
    var view by remember { mutableStateOf<TextureVideoView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx -> TextureVideoView(ctx).also { view = it } },
        update = { it.setMirror(mirror) },
        onRelease = { runCatching { it.release() } },
    )
    DisposableEffect(track, view) {
        val v = view
        if (v != null) runCatching { track.addSink(v) }
        onDispose { if (v != null) runCatching { track.removeSink(v) } }
    }
}

/** Chooses how [view] scales the picture, from its shape (see [VideoRenderer]). */
private class AdaptiveScaling(private val view: SurfaceViewRenderer) : RendererCommon.RendererEvents {
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    /** true = whole picture, false = fill, null = decide from the shape. Main thread only. */
    private var forced: Boolean? = null
    private var current: RendererCommon.ScalingType? = null

    /** The user's choice: the other one of the two. */
    fun toggle() {
        forced = current != RendererCommon.ScalingType.SCALE_ASPECT_FIT
        apply()
    }

    override fun onFirstFrameRendered() {}

    override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
        if (rotation % 180 == 0) { frameW = width; frameH = height } else { frameW = height; frameH = width }
        view.post { apply() }
    }

    fun apply() {
        val whole = forced ?: shouldShowWhole()
        val type = if (whole) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL
        if (type != current) {
            current = type
            view.setScalingType(type)
        }
    }

    /** Filling the screen would cut away more than a quarter of the picture. */
    private fun shouldShowWhole(): Boolean {
        if (frameW <= 0 || frameH <= 0 || view.width <= 0 || view.height <= 0) return false
        val frame = frameW.toFloat() / frameH
        val screen = view.width.toFloat() / view.height
        return minOf(frame / screen, screen / frame) < 0.75f
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
