package app.amend.messenger.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import app.amend.messenger.data.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.data.AmendEvent
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.ChatMessage
import app.amend.messenger.data.EcoRules
import app.amend.messenger.data.MessageStatus
import app.amend.messenger.data.MessageType
import app.amend.messenger.eco.Challenges
import app.amend.messenger.eco.Garden
import app.amend.messenger.ui.LocalParticles
import app.amend.messenger.ui.components.LeafAvatar
import app.amend.messenger.ui.components.LivingSky
import app.amend.messenger.ui.components.ParticleKind
import app.amend.messenger.ui.components.bouncy
import app.amend.messenger.ui.components.drawTree
import app.amend.messenger.ui.components.isNight
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.ui.components.rememberHourOfDay
import app.amend.messenger.ui.components.rememberSway
import app.amend.messenger.ui.theme.LocalDark
import app.amend.messenger.ui.theme.LocalEfficiency
import app.amend.messenger.util.AppVisibility
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val REACTIONS = listOf("💚", "🌱", "🌳", "😂", "😮", "🙏")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(repo: AmendRepository, peer: String, onBack: () -> Unit) {
    val conversations by repo.conversations.collectAsStateWithLifecycle()
    val profiles by repo.profiles.collectAsStateWithLifecycle()
    val conv = conversations[peer]
    val messages = conv?.messages.orEmpty()
    val garden = remember(messages.size) { EcoRules.gardenFor(repo.me, conv ?: app.amend.messenger.data.Conversation(peer)) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val particles = LocalParticles.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var input by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    val startCall = rememberCallStarter()
    val voiceRecorder = remember { VoiceRecorder(context) }
    var recordingVoice by remember { mutableStateOf(false) }
    var cancellingVoice by remember { mutableStateOf(false) }
    var showVideoRecorder by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) haptics.success()
    }
    val mediaPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) showVideoRecorder = true
    }
    DisposableEffect(Unit) { onDispose { voiceRecorder.cancel(); AudioPlayback.stop() } }
    var sendButtonPos by remember { mutableStateOf(Offset.Zero) }
    var gardenPos by remember { mutableStateOf(Offset.Zero) }
    var accepted by remember { mutableStateOf(repo.isAccepted(peer) || messages.any { it.fromMe }) }
    val reversed = remember(messages) { messages.asReversed() }
    val byId = remember(messages) { messages.associateBy { it.id } }
    val incomingSosAll by repo.incomingSos.collectAsStateWithLifecycle()
    // The friend's latest SOS start bubble shows their live location while the SOS is active
    val latestSosStartId = remember(messages) { messages.lastOrNull { it.type == MessageType.SOS && !it.fromMe && it.cardId == "start" }?.id }
    val liveSosLocation = incomingSosAll[peer]?.takeIf { it.active }?.location

    DisposableEffect(peer) {
        AppVisibility.openPeer = peer
        onDispose { if (AppVisibility.openPeer == peer) AppVisibility.openPeer = null }
    }
    LaunchedEffect(messages.size) {
        repo.markRead(peer)
        app.amend.messenger.util.Notifications.cancel(context, peer)
        app.amend.messenger.util.Notifications.cancelSosAlert(context, peer)
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }
    LaunchedEffect(peer) {
        repo.events.collect { e ->
            if (e is AmendEvent.MessageArrived && e.peer == peer) {
                haptics.tick()
                particles.burst(gardenPos, ParticleKind.LEAVES, 10, 0.5f)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        // Header with the shared garden growing behind the name
        GardenHeader(
            repo = repo, peer = peer, garden = garden, profileName = repo.displayName(peer),
            onBack = onBack, onGardenPos = { gardenPos = it },
            onGardenTap = {
                haptics.tick()
                particles.burst(gardenPos, if (garden.flowers > 0) ParticleKind.PETALS else ParticleKind.LEAVES, 20, 0.7f)
            },
            avatar = { LeafAvatar(peer, profiles[peer], 40.dp) },
            onCall = { video -> haptics.tap(); if (!accepted) { repo.accept(peer); accepted = true }; startCall(peer, video) },
        )

        // A live SOS from this person stays pinned at the top of the chat
        val incomingSos by repo.incomingSos.collectAsStateWithLifecycle()
        incomingSos[peer]?.takeIf { it.active }?.let { sos ->
            IncomingSosCard(sos, repo.displayName(peer), onOpenChat = null, onDismiss = { repo.dismissIncomingSos(peer) })
        }

        if (!accepted) {
            RequestBanner(
                onAccept = { repo.accept(peer); accepted = true; haptics.success(); repo.markSeen(peer) },
                onBlock = { repo.block(peer); haptics.heavy(); onBack() },
            )
        }

        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(reversed, key = { it.id }, contentType = { it.type }) { m ->
                MessageBubble(
                    liveSos = if (m.type == MessageType.SOS && !m.fromMe && m.id == latestSosStartId) liveSosLocation else null,
                    repo = repo,
                    message = m,
                    replied = m.replyTo?.let { id -> byId[id] },
                    modifier = Modifier.animateItem(),
                    onReply = { replyTo = m; haptics.tick() },
                    onReact = { emoji, pos ->
                        haptics.success()
                        particles.burst(pos, if (emoji == "💚" || emoji == "🌳" || emoji == "🌱") ParticleKind.LEAVES else ParticleKind.SPARKS, 14, 0.6f)
                        scope.launch { repo.react(peer, m.id, emoji) }
                    },
                    onRetry = { scope.launch { repo.retry(peer, m.id) } },
                    onCall = { video -> startCall(peer, video) },
                )
            }
            if (messages.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(garden.species.emoji, fontSize = 48.sp)
                        Text("Your shared ${garden.species.name.lowercase()} is a seed", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Every message you two exchange helps it grow. You both see the same plant.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Reply preview
        AnimatedVisibility(replyTo != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(width = 3.dp, height = 32.dp).background(MaterialTheme.colorScheme.primary))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(if (replyTo?.fromMe == true) "You" else repo.displayName(peer), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(replyTo?.text ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { replyTo = null }) { Icon(Icons.Filled.Close, contentDescription = "Cancel reply") }
            }
        }

        // Composer
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (recordingVoice) {
                RecordingBar(voiceRecorder, cancellingVoice, Modifier.weight(1f))
            } else TextField(
                value = input,
                onValueChange = { if (it.length <= 4000) input = it },
                placeholder = { Text("Grow the conversation…") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(26.dp),
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
            Spacer(Modifier.size(8.dp))
            val canSend = input.isNotBlank()
            if (!canSend) {
                if (!recordingVoice) {
                    // short round video message
                    Box(
                        Modifier.size(54.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable {
                            haptics.tap()
                            if (hasPermission(context, Manifest.permission.CAMERA) && hasPermission(context, Manifest.permission.RECORD_AUDIO)) showVideoRecorder = true
                            else mediaPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Videocam, contentDescription = "Record video message", tint = MaterialTheme.colorScheme.primary) }
                    Spacer(Modifier.size(8.dp))
                }
                // hold to record a voice message, slide left to cancel
                val micScale by animateFloatAsState(if (recordingVoice) 1.35f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "mic")
                val density = androidx.compose.ui.platform.LocalDensity.current
                val cancelDistance = with(density) { 110.dp.toPx() }
                Box(
                    Modifier
                        .size(54.dp)
                        .onGloballyPositioned { sendButtonPos = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) }
                        .graphicsLayer { scaleX = micScale; scaleY = micScale }
                        .clip(CircleShape)
                        .background(
                            if (cancellingVoice) Brush.linearGradient(listOf(Color(0xFFE53935), Color(0xFFFF7043)))
                            else Brush.linearGradient(listOf(Color(0xFF2BB673), Color(0xFF5BE39B)))
                        )
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
                                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                    return@awaitEachGesture
                                }
                                AudioPlayback.stop()
                                if (!voiceRecorder.start()) { haptics.reject(); return@awaitEachGesture }
                                haptics.heavy()
                                recordingVoice = true
                                cancellingVoice = false
                                var cancel = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val nowCancel = change.position.x - down.position.x < -cancelDistance
                                    if (nowCancel != cancel) { cancel = nowCancel; cancellingVoice = cancel; haptics.tick() }
                                    if (!change.pressed) break
                                }
                                recordingVoice = false
                                cancellingVoice = false
                                if (cancel) {
                                    voiceRecorder.cancel()
                                    haptics.reject()
                                } else {
                                    val result = voiceRecorder.stop()
                                    if (result == null) {
                                        haptics.reject()
                                    } else {
                                        val (file, duration, wave) = result
                                        val reply = replyTo?.id
                                        replyTo = null
                                        haptics.success()
                                        particles.burst(sendButtonPos, ParticleKind.LEAVES, 18, 0.8f)
                                        if (!accepted) { repo.accept(peer); accepted = true }
                                        scope.launch {
                                            val bytes = withContext(Dispatchers.IO) { file.readBytes().also { file.delete() } }
                                            runCatching { repo.sendMedia(peer, bytes, MediaKind.VOICE, "audio/mp4", duration, wave, replyTo = reply) }
                                        }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Mic, contentDescription = "Hold to record a voice message", tint = Color.White)
                }
            } else {
            val rotation by animateFloatAsState(if (canSend) -20f else 0f, spring(Spring.DampingRatioMediumBouncy), label = "sendRot")
            val sendScale by animateFloatAsState(if (canSend) 1f else 0.85f, spring(Spring.DampingRatioMediumBouncy), label = "sendScale")
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(54.dp)
                    .onGloballyPositioned { sendButtonPos = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) }
                    .graphicsLayer { scaleX = sendScale; scaleY = sendScale; rotationZ = rotation }
                    .bouncy(source)
                    .clip(CircleShape)
                    .background(
                        if (canSend) Brush.linearGradient(listOf(Color(0xFF2BB673), Color(0xFF5BE39B)))
                        else Brush.linearGradient(listOf(Color(0xFF6E8A7C), Color(0xFF6E8A7C)))
                    )
                    .clickable(interactionSource = source, indication = null, enabled = canSend) {
                        val text = input
                        val reply = replyTo?.id
                        input = ""
                        replyTo = null
                        haptics.success()
                        particles.burst(sendButtonPos, ParticleKind.LEAVES, 18, 0.8f)
                        if (!accepted) { repo.accept(peer); accepted = true }
                        scope.launch { repo.sendText(peer, text, reply) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
            }
            }
        }
    }

    if (showVideoRecorder) {
        // Full-screen layer in the app's own window (not a dialog): the system-bar sizes are always
        // known here, so the record button sits above the navigation bar on every phone and tablet.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } } // keep touches off the chat below
        ) {
            VideoRecorderScreen(
                onDone = { file, duration, w, h ->
                    showVideoRecorder = false
                    val reply = replyTo?.id
                    replyTo = null
                    if (!accepted) { repo.accept(peer); accepted = true }
                    scope.launch {
                        val bytes = withContext(Dispatchers.IO) { file.readBytes().also { file.delete() } }
                        runCatching { repo.sendMedia(peer, bytes, MediaKind.VIDEO, "video/mp4", duration, width = w, height = h, replyTo = reply) }
                    }
                },
                onClose = { showVideoRecorder = false },
            )
        }
    }
}

private fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun GardenHeader(
    repo: AmendRepository, peer: String, garden: Garden, profileName: String,
    onBack: () -> Unit, onGardenPos: (Offset) -> Unit, onGardenTap: () -> Unit,
    avatar: @Composable () -> Unit,
    onCall: (Boolean) -> Unit = {},
) {
    val hour = rememberHourOfDay()
    val sway by rememberSway()
    val efficiency = LocalEfficiency.current
    val grow = remember { Animatable(garden.progress) }
    LaunchedEffect(garden.progress) { grow.animateTo(garden.progress, spring(Spring.DampingRatioLowBouncy, 60f)) }
    var menu by remember { mutableStateOf(false) }
    val conv = repo.conversations.collectAsStateWithLifecycle().value[peer]

    Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))) {
        LivingSky(Modifier.fillMaxSize(), hour = hour, animate = !efficiency)
        Canvas(
            Modifier.fillMaxSize()
                .onGloballyPositioned { onGardenPos(it.positionInRoot() + Offset(it.size.width * 0.82f, it.size.height * 0.6f)) }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onGardenTap() }
        ) {
            val ground = size.height * 0.92f
            val night = isNight(hour)
            val hill = Path().apply {
                moveTo(size.width * 0.55f, size.height)
                quadraticBezierTo(size.width * 0.82f, ground - 30f, size.width, ground - 8f)
                lineTo(size.width, size.height); close()
            }
            drawPath(hill, if (night) Color(0xFF0F2E21) else Color(0xFF66C47A))
            drawTree(garden.species, grow.value, garden.seed[2].toInt(), Offset(size.width * 0.82f, ground - 6f), size.height * 0.9f, sway, night = night)
        }
        Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
            avatar()
            Column(Modifier.padding(start = 10.dp).weight(1f).clickable { menu = true }) {
                Text(profileName, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${garden.stage.emoji} Shared ${garden.species.name.lowercase()} · ${garden.stage.label.lowercase()} · ${(garden.progress * 100).toInt()}%",
                    color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (conv?.pinned == true) "Unpin" else "Pin") }, onClick = { repo.setPinned(peer, conv?.pinned != true); menu = false })
                    DropdownMenuItem(text = { Text("Block") }, onClick = { repo.block(peer); menu = false; onBack() })
                }
            }
            IconButton(onClick = { onCall(false) }) { Icon(Icons.Rounded.Call, contentDescription = "Voice call", tint = Color.White) }
            IconButton(onClick = { onCall(true) }) { Icon(Icons.Rounded.Videocam, contentDescription = "Video call", tint = Color.White) }
        }
    }
}

@Composable
private fun RequestBanner(onAccept: () -> Unit, onBlock: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)
    ) {
        Text("New message request", style = MaterialTheme.typography.titleMedium)
        Text(
            "You haven't chatted with this person before. Accept to reply, or block to never hear from them again.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onAccept, shape = RoundedCornerShape(16.dp)) { Text("Accept") }
            OutlinedButton(onClick = onBlock, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Block") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    liveSos: app.amend.messenger.data.SosLocation?,
    repo: AmendRepository,
    message: ChatMessage,
    replied: ChatMessage?,
    modifier: Modifier,
    onReply: () -> Unit,
    onReact: (String, Offset) -> Unit,
    onRetry: () -> Unit,
    onCall: (Boolean) -> Unit = {},
) {
    val mine = message.fromMe
    val dark = LocalDark.current
    var showReactions by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var bubblePos by remember { mutableStateOf(Offset.Zero) }
    val haptics = rememberHaptics()
    // pop-in when the bubble first appears
    val appear = remember { Animatable(if (System.currentTimeMillis() / 1000 - message.createdAt < 5) 0f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)) }
    // horizontal drag to reply
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = 0.6f + 0.4f * appear.value
                    scaleY = 0.6f + 0.4f * appear.value
                    alpha = appear.value
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (mine) 1f else 0f, 1f)
                    translationX = drag.value
                }
                .pointerDragToReply(drag, scope) { haptics.tick(); onReply() }
                .widthIn(max = 300.dp)
                .onGloballyPositioned { bubblePos = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) }
                .clip(
                    RoundedCornerShape(
                        topStart = 22.dp, topEnd = 22.dp,
                        bottomStart = if (mine) 22.dp else 6.dp, bottomEnd = if (mine) 6.dp else 22.dp,
                    )
                )
                .background(
                    if (message.type == MessageType.SOS) {
                        if (message.cardId == "stop") Brush.linearGradient(listOf(Color(0xFF1C7A4E), Color(0xFF2BB673)))
                        else Brush.linearGradient(listOf(Color(0xFFE53935), Color(0xFF7F0000)))
                    }
                    else if (message.type == MessageType.VIDEO) Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                    else if (message.type == MessageType.ECO_CARD) Brush.linearGradient(listOf(Color(0xFF1C7A4E), Color(0xFF2BB673), Color(0xFF4FC3F7)))
                    else if (mine) Brush.linearGradient(listOf(Color(0xFF2BB673), Color(0xFF1C9A62)))
                    else Brush.linearGradient(listOf(if (dark) Color(0xFF1B3A2D) else Color.White, if (dark) Color(0xFF16352A) else Color(0xFFF1F8F3)))
                )
                .combinedClickable(
                    onClick = {},
                    onDoubleClick = { onReact("💚", bubblePos) },
                    onLongClick = { haptics.heavy(); showReactions = true },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            val textColor = if (mine || message.type == MessageType.ECO_CARD || message.type == MessageType.SOS) Color.White else MaterialTheme.colorScheme.onSurface
            Column {
                replied?.let {
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.12f)).padding(8.dp)) {
                        Text(it.text, color = textColor.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (message.type == MessageType.SOS) {
                    SosMessageContent(message, liveSos)
                } else if (message.type == MessageType.ECO_CARD) {
                    EcoCardContent(repo, message)
                } else if (message.type == MessageType.VOICE) {
                    VoiceBubble(repo, message, textColor)
                } else if (message.type == MessageType.VIDEO) {
                    VideoBubble(repo, message)
                } else if (message.type == MessageType.CALL) {
                    CallLogContent(message, textColor, onCall)
                } else if (message.type == MessageType.ECO_PROOF) {
                    EcoProofContent(repo, message, textColor)
                } else {
                    Text(linkify(message.text, textColor), color = textColor, style = MaterialTheme.typography.bodyLarge)
                }
                Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Instant.ofEpochSecond(message.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
                        color = textColor.copy(alpha = 0.7f), fontSize = 11.sp,
                    )
                    if (mine && message.type != MessageType.CALL) {
                        Spacer(Modifier.size(4.dp))
                        GrowthTick(message.status, onRetry)
                    }
                }
            }
        }
        // reactions under the bubble
        if (message.reactions.isNotEmpty()) {
            Row(
                Modifier.padding(top = 2.dp, start = 8.dp, end = 8.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                message.reactions.values.groupingBy { it }.eachCount().forEach { (emoji, n) ->
                    Text(if (n > 1) "$emoji$n" else emoji, fontSize = 14.sp)
                }
            }
        }
        DropdownMenu(expanded = showReactions, onDismissRequest = { showReactions = false }) {
            Row(Modifier.padding(horizontal = 8.dp)) {
                REACTIONS.forEachIndexed { i, emoji ->
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { kotlinx.coroutines.delay(i * 40L); visible = true }
                    AnimatedVisibility(visible, enter = scaleIn(spring(Spring.DampingRatioHighBouncy)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                        Text(
                            emoji, fontSize = 26.sp,
                            modifier = Modifier.clip(CircleShape).clickable { showReactions = false; onReact(emoji, bubblePos) }.padding(6.dp),
                        )
                    }
                }
            }
            if (mine) {
                DropdownMenuItem(
                    text = { Text(statusLabel(message.status, message.relaysAccepted), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { showReactions = false },
                    leadingIcon = { GrowthTick(message.status, onRetry = {}, onBubble = false) },
                )
            }
            DropdownMenuItem(text = { Text("Reply") }, onClick = { showReactions = false; onReply() })
            DropdownMenuItem(text = { Text("Copy") }, onClick = {
                showReactions = false
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.text))
                haptics.success()
            })
        }
    }
}

/**
 * An SOS start/stop bubble with an embedded map. For a friend's active SOS the
 * map follows their live location; otherwise it shows where the message was sent from.
 */
@Composable
private fun SosMessageContent(message: ChatMessage, live: app.amend.messenger.data.SosLocation?) {
    val stored = app.amend.messenger.data.SosLocation.decode(message.extra, message.createdAt)
    val loc = live ?: stored
    Column {
        Text(
            if (message.cardId == "stop") "✅ Safe — SOS ended" else if (message.fromMe) "🆘 You sent an SOS" else "🆘 SOS — needs help!",
            color = Color.White, style = MaterialTheme.typography.titleMedium,
        )
        if (loc != null && message.cardId != "stop") {
            Spacer(Modifier.height(8.dp))
            app.amend.messenger.ui.components.LiveMap(loc, Modifier.size(width = 260.dp, height = 170.dp), live = live != null)
            Spacer(Modifier.height(4.dp))
            Text(
                (if (live != null) "Live · " else "") + "±${loc.accuracyM.toInt()} m · tap the map to open it",
                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall,
            )
        } else if (message.cardId != "stop") {
            Text("Location wasn't available yet", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Turns http(s) and www links in a message into tappable links. */
private val URL_REGEX = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"]+)""")

private fun linkify(text: String, color: Color): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        var last = 0
        for (m in URL_REGEX.findAll(text)) {
            // don't swallow trailing punctuation like "see example.com."
            var url = m.value
            while (url.isNotEmpty() && url.last() in ".,!?;:)]}'\"") url = url.dropLast(1)
            val start = m.range.first
            val end = start + url.length
            append(text.substring(last, start))
            val target = if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url
            withLink(
                androidx.compose.ui.text.LinkAnnotation.Url(
                    target,
                    androidx.compose.ui.text.TextLinkStyles(
                        androidx.compose.ui.text.SpanStyle(
                            color = color,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            fontWeight = FontWeight.Medium,
                        )
                    ),
                )
            ) { append(url) }
            last = end
        }
        append(text.substring(last))
    }

@Composable
private fun EcoCardContent(repo: AmendRepository, message: ChatMessage) {
    val c = message.cardId?.let { Challenges.byId(it) }
    val haptics = rememberHaptics()
    val particles = LocalParticles.current
    val eco by repo.eco.collectAsStateWithLifecycle()
    var pos by remember { mutableStateOf(Offset.Zero) }
    if (c == null) {
        Text(message.text, color = Color.White)
        return
    }
    val doneToday = eco.completedChallenges[java.time.LocalDate.now().toString()]
    Column {
        Text("${c.category.emoji}  ECO CHALLENGE", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
        Text(c.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(c.description, color = Color.White.copy(alpha = 0.95f), style = MaterialTheme.typography.bodyMedium)
        if (!message.fromMe) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (repo.completeChallenge(c.id)) {
                        haptics.success()
                        particles.burst(pos, ParticleKind.SPARKS, 30, 1f)
                    } else haptics.reject()
                },
                enabled = doneToday == null,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1C7A4E)),
                modifier = Modifier.onGloballyPositioned { pos = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) },
            ) { Text(if (doneToday == c.id) "Done ✓" else if (doneToday != null) "Already did one today" else "I did it too!") }
        }
    }
}

/** Drag a bubble sideways to reply; it springs back. */
private fun Modifier.pointerDragToReply(
    drag: Animatable<Float, AnimationVector1D>,
    scope: kotlinx.coroutines.CoroutineScope,
    onReply: () -> Unit,
): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        val threshold = 72.dp.toPx()
        var fired = false
        detectHorizontalDragGestures(
            onDragStart = { fired = false },
            onDragEnd = { scope.launch { drag.animateTo(0f, spring(Spring.DampingRatioMediumBouncy)) } },
            onDragCancel = { scope.launch { drag.animateTo(0f, spring()) } },
        ) { change, amount ->
            change.consume()
            // left swipe = reply (a right swipe goes back, like Telegram)
            val next = (drag.value + amount * 0.6f).coerceIn(-threshold * 1.4f, 0f)
            scope.launch { drag.snapTo(next) }
            if (!fired && kotlin.math.abs(next) > threshold) {
                fired = true
                onReply()
            }
        }
    }
)
