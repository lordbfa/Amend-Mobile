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
import androidx.compose.material.icons.rounded.Verified
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import app.amend.messenger.ui.Text
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.consume
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
fun ChatScreen(repo: AmendRepository, peer: String, onBack: () -> Unit, onOpenChat: (String) -> Unit = {}) {
    app.amend.messenger.util.SecureScreen()
    val conversations by repo.conversations.collectAsStateWithLifecycle()
    val profiles by repo.profiles.collectAsStateWithLifecycle()
    val groups by repo.groups.collectAsStateWithLifecycle()
    val isGroup = repo.isGroup(peer)
    val group = groups[peer]
    val conv = conversations[peer]
    val messages = conv?.messages.orEmpty()
    val garden = remember(messages.size) { EcoRules.gardenFor(repo.me, conv ?: app.amend.messenger.data.Conversation(peer)) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val particles = LocalParticles.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // The text being typed. Only ChatInput and the send/mic switch read it while composing, so a
    // keystroke redraws the input box alone, not the whole chat (slow phones lagged on every key).
    val inputState = remember { mutableStateOf("") }
    var input by inputState
    val canSend by remember { derivedStateOf { inputState.value.isNotBlank() } }
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
    // photos & videos from the gallery or camera
    var pickedMedia by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var attachMenu by remember { mutableStateOf(false) }
    var showInstantCamera by remember { mutableStateOf(false) }
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) pickedMedia = uris
    }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraShot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        if (ok && u != null) pickedMedia = listOf(u)
    }
    fun openCamera() {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        val f = java.io.File(dir, "shot-${System.currentTimeMillis()}.jpg")
        val u = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
        cameraUri = u
        runCatching { cameraShot.launch(u) }
    }
    var sendButtonPos by remember { mutableStateOf(Offset.Zero) }
    var gardenPos by remember { mutableStateOf(Offset.Zero) }
    var accepted by remember { mutableStateOf(repo.isAccepted(peer) || messages.any { it.fromMe }) }
    val reversed = remember(messages) { messages.asReversed() }
    val byId = remember(messages) { messages.associateBy { it.id } }
    val incomingSosAll by repo.incomingSos.collectAsStateWithLifecycle()
    // The friend's latest SOS start bubble shows their live location while the SOS is active
    val latestSosStartId = remember(messages) { messages.lastOrNull { it.type == MessageType.SOS && !it.fromMe && it.cardId == "start" }?.id }
    val liveSosLocation = incomingSosAll[peer]?.takeIf { it.active }?.location
    var showDisappear by remember { mutableStateOf(false) }
    var showVerify by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var unsendTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var editTarget by remember { mutableStateOf<ChatMessage?>(null) }
    /** The account being reported (the person, a garden's creator, or one message's author). */
    var reporting by remember { mutableStateOf<String?>(null) }
    var showLive by remember { mutableStateOf(false) }
    val myShares by app.amend.messenger.util.LiveShareManager.shares.collectAsStateWithLifecycle()
    val liveShares by repo.liveShares.collectAsStateWithLifecycle()
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) showLive = true
    }

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
            avatar = {
                if (isGroup) GroupAvatar(group?.members.orEmpty().filter { it != repo.me }, profiles, 40.dp)
                else LeafAvatar(peer, profiles[peer], 40.dp)
            },
            onCall = { video -> haptics.tap(); if (!accepted) { repo.accept(peer); accepted = true }; startCall(peer, video) },
            isGroup = isGroup,
            memberCount = group?.members?.size ?: 0,
            disappearSecs = conv?.disappearSecs ?: 0,
            onDisappear = { showDisappear = true },
            onVerify = { showVerify = true },
            onMembers = { showMembers = true },
            onRename = { showRename = true },
            onLeave = { confirmLeave = true },
            onReport = { reporting = if (isGroup) group?.createdBy?.takeIf { it.length == 64 && it != repo.me } ?: peer else peer },
            sharingLive = peer in myShares,
            onLive = {
                if (peer in myShares) app.amend.messenger.util.LiveShareManager.stop(context, peer)
                else if (app.amend.messenger.util.SosManager.hasLocationPermission(context)) showLive = true
                else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
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
                onReport = { reporting = if (isGroup) group?.createdBy?.takeIf { it.length == 64 && it != repo.me } ?: peer else peer },
                invitedBy = if (isGroup) group?.createdBy?.let { repo.displayName(it) } ?: "Someone" else null,
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
                if (m.type == MessageType.NOTICE) {
                    NoticeRow(m.text, Modifier.animateItem())
                    return@items
                }
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
                    authorName = if (isGroup && !m.fromMe) m.author?.let { repo.displayName(it) } else null,
                    liveShare = if (m.type == MessageType.LIVE) liveShares["$peer|${if (m.fromMe) repo.me else (m.author ?: peer)}"] else null,
                    onStopLive = { app.amend.messenger.util.LiveShareManager.stop(context, peer) },
                    canUnsend = repo.canUnsend(m),
                    onUnsend = { unsendTarget = m },
                    canEdit = repo.canEdit(m),
                    fromStranger = !accepted && !m.fromMe,
                    onReportMessage = { reporting = if (isGroup) m.author ?: peer else peer },
                    onEdit = { editTarget = m },
                    onSendNow = { scope.launch { repo.sendDueScheduled(forceId = m.id) } },
                    onCancelScheduled = { scope.launch { repo.cancelScheduled(peer, m.id) } },
                )
            }
            if (messages.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(garden.species.emoji, fontSize = 48.sp)
                        Text("Your shared ${garden.species.name.lowercase()} is a seed", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (isGroup) "Every message anyone here sends helps it grow. Everyone in the garden sees the same plant."
                            else "Every message you two exchange helps it grow. You both see the same plant.",
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
                    Text(if (replyTo?.fromMe == true) "You" else repo.displayName(replyTo?.author ?: peer), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(androidx.compose.ui.text.AnnotatedString(replyTo?.text ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { replyTo = null }) { Icon(Icons.Filled.Close, contentDescription = "Cancel reply") }
            }
        }

        if ((conv?.disappearSecs ?: 0) > 0) {
            Text(
                "⏳ Messages disappear after ${app.amend.messenger.data.Disappear.label(conv!!.disappearSecs).lowercase()}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        // Composer
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!recordingVoice) {
                Box {
                    Box(
                        Modifier.size(54.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { haptics.tap(); attachMenu = true },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Add, contentDescription = "Send a photo, video or Instant", tint = MaterialTheme.colorScheme.primary) }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Photo or video") },
                            onClick = {
                                attachMenu = false
                                galleryPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            },
                        )
                        DropdownMenuItem(text = { Text("Camera") }, onClick = { attachMenu = false; openCamera() })
                        DropdownMenuItem(
                            text = { Text("Round video message") },
                            leadingIcon = { Icon(Icons.Rounded.Videocam, contentDescription = null) },
                            onClick = {
                                attachMenu = false
                                if (hasPermission(context, Manifest.permission.CAMERA) && hasPermission(context, Manifest.permission.RECORD_AUDIO)) showVideoRecorder = true
                                else mediaPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                            },
                        )
                        DropdownMenuItem(text = { Text("📸 Instant (disappears after viewing)") }, onClick = { attachMenu = false; showInstantCamera = true })
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
            if (recordingVoice) {
                RecordingBar(voiceRecorder, cancellingVoice, Modifier.weight(1f))
            } else ChatInput(inputState, Modifier.weight(1f), onContent = { uris ->
                // a GIF / sticker / image from the keyboard: read it now (the keyboard's permission
                // to read it is short-lived), then encrypt and send it like a photo
                val items = uris.mapNotNull { Gifs.read(context, it) }
                if (items.isEmpty()) {
                    android.widget.Toast.makeText(context, "Couldn't read that GIF", android.widget.Toast.LENGTH_SHORT).show()
                    return@ChatInput
                }
                val reply = replyTo?.id
                replyTo = null
                haptics.success()
                repo.launchInBackground {
                    items.forEachIndexed { i, item ->
                        Gifs.send(context, repo, peer, item, if (i == 0) reply else null)?.let { err ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            })
            Spacer(Modifier.size(8.dp))
            if (!canSend) {

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
                    .combinedClickable(
                        interactionSource = source, indication = null, enabled = canSend,
                        // hold the send button to schedule it for later
                        onLongClick = { haptics.heavy(); showSchedule = true },
                    ) {
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

    if (showLive) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLive = false },
            title = { Text("📍 Share live location") },
            text = {
                Column {
                    Text(
                        (if (isGroup) "Everyone in this garden" else repo.displayName(peer)) +
                            " sees where you are, updating as you move, end-to-end encrypted. It stops by itself.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    listOf(15 to "15 minutes", 60 to "1 hour", 8 * 60 to "8 hours").forEach { (min, label) ->
                        Text(
                            label, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                showLive = false
                                haptics.success()
                                if (!accepted) { repo.accept(peer); accepted = true }
                                app.amend.messenger.util.LiveShareManager.start(context, peer, min)
                            }.padding(vertical = 12.dp, horizontal = 8.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLive = false }) { Text("Cancel") } },
        )
    }
    if (showVerify && !isGroup) VerifyScreen(repo, peer, onClose = { showVerify = false })
    if (showDisappear) {
        DisappearDialog(
            current = conv?.disappearSecs ?: 0,
            onPick = { secs -> showDisappear = false; haptics.success(); scope.launch { repo.setDisappearing(peer, secs) } },
            onDismiss = { showDisappear = false },
        )
    }
    if (showSchedule) {
        ScheduleDialog(
            onPick = { at ->
                showSchedule = false
                val text = input
                val reply = replyTo?.id
                input = ""
                replyTo = null
                haptics.success()
                if (!accepted) { repo.accept(peer); accepted = true }
                scope.launch { repo.scheduleText(peer, text, at, reply) }
            },
            onDismiss = { showSchedule = false },
        )
    }
    if (pickedMedia.isNotEmpty()) {
        val uris = pickedMedia
        MediaSendDialog(uris, repo.displayName(peer), onDismiss = { pickedMedia = emptyList() }) { caption ->
            pickedMedia = emptyList()
            val reply = replyTo?.id
            replyTo = null
            haptics.success()
            repo.launchInBackground {
                sendPickedMedia(context, repo, peer, uris, caption, reply)?.let { err ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    if (showInstantCamera) {
        InstantCameraScreen(
            repo = repo, presetRecipients = listOf(peer).filter { !isGroup }, groupPeer = peer.takeIf { isGroup },
            onClose = { showInstantCamera = false },
        )
    }
    reporting?.let { key ->
        val groupCreator = group?.createdBy
        ReportDialog(
            what = if (isGroup) "\"${repo.displayName(peer)}\"" else repo.displayName(peer),
            reportedKey = if (key.length == 64) key else peer,
            evidence = repo.reportEvidence(peer),
            extraContext = if (isGroup) "Shared garden members: " + group?.members.orEmpty().joinToString { app.amend.messenger.core.crypto.Nip19.npub(it) } else "",
            onDismiss = { reporting = null },
            onReported = {
                reporting = null
                haptics.heavy()
                if (isGroup) {
                    if (key.length == 64 && key != repo.me && key != peer) repo.block(key)
                    if (groupCreator != null && groupCreator.length == 64 && groupCreator != repo.me && !accepted) repo.block(groupCreator)
                    repo.leaveGroupInBackground(peer)
                } else repo.report(peer)
                onBack()
            },
        )
    }
    editTarget?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.text) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Edit message") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        draft, { draft = it.take(app.amend.messenger.data.AmendRepository.MAX_EDIT_CHARS) },
                        Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), maxLines = 8,
                    )
                    Text(
                        if (isGroup) "Everyone in the garden sees the new text, marked \"edited\"." else "They see the new text, marked \"edited\".",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank() && draft.trim() != target.text,
                    onClick = {
                        editTarget = null
                        haptics.success()
                        val text = draft
                        scope.launch { repo.editMessage(peer, target.id, text) }
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("Cancel") } },
        )
    }
    unsendTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { unsendTarget = null },
            title = { Text("Unsend this message?") },
            text = { Text(if (isGroup) "It disappears for everyone in the garden. They'll see that a message was unsent." else "It disappears for both of you. They'll see that a message was unsent.") },
            confirmButton = {
                TextButton(onClick = {
                    unsendTarget = null
                    haptics.heavy()
                    scope.launch { repo.unsend(peer, target.id) }
                }) { Text("Unsend", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { unsendTarget = null }) { Text("Cancel") } },
        )
    }
    if (showMembers && group != null) {
        GroupMembersDialog(
            repo = repo, group = group,
            onAdd = { extra ->
                showMembers = false
                scope.launch {
                    runCatching { repo.createGroup(group.name, group.members.filter { it != repo.me } + extra) }
                        .onSuccess { onOpenChat(it) }
                }
            },
            onDismiss = { showMembers = false },
        )
    }
    if (showRename && group != null) {
        var name by remember { mutableStateOf(group.name) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Name this garden") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it.take(60) }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { showRename = false; scope.launch { repo.renameGroup(peer, name) } }, enabled = name.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }
    if (confirmLeave) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave this garden?") },
            text = { Text("You'll say goodbye in the chat and stop getting its messages on this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    repo.leaveGroupInBackground(peer)
                    onBack()
                }) { Text("Leave", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
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
    isGroup: Boolean = false,
    memberCount: Int = 0,
    disappearSecs: Long = 0,
    onDisappear: () -> Unit = {},
    onVerify: () -> Unit = {},
    onMembers: () -> Unit = {},
    onRename: () -> Unit = {},
    onLeave: () -> Unit = {},
    onReport: () -> Unit = {},
    sharingLive: Boolean = false,
    onLive: () -> Unit = {},
) {
    val hour = rememberHourOfDay()
    val sway by rememberSway()
    val efficiency = LocalEfficiency.current
    // a new generation starts from a seed, so the animation restarts with it
    val grow = remember(garden.generation) { Animatable(if (garden.generation > 0) 0f else garden.progress) }
    LaunchedEffect(garden.progress, garden.generation) { grow.animateTo(garden.progress, spring(Spring.DampingRatioLowBouncy, 60f)) }
    var menu by remember { mutableStateOf(false) }
    val conv = repo.conversations.collectAsStateWithLifecycle().value[peer]
    val verifiedSet by app.amend.messenger.util.Verified.hashes.collectAsStateWithLifecycle()
    val verified = !isGroup && app.amend.messenger.util.Verified.isVerified(peer, verifiedSet)

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
                quadraticTo(size.width * 0.82f, ground - 30f, size.width, ground - 8f)
                lineTo(size.width, size.height); close()
            }
            drawPath(hill, if (night) Color(0xFF0F2E21) else Color(0xFF66C47A))
            // trees this chat already grew, smaller, behind the one growing now
            val grown = garden.grown.takeLast(5)
            grown.forEachIndexed { i, sp ->
                val gen = garden.generation - grown.size + i
                val x = size.width * (0.58f + 0.045f * i)
                drawTree(sp, 1f, garden.drawSeed(gen), Offset(x, ground + 2f), size.height * (0.5f + 0.04f * i), sway * 0.8f, night = night)
            }
            drawTree(garden.species, grow.value, garden.drawSeed(garden.generation), Offset(size.width * 0.84f, ground - 6f), size.height * 0.9f, sway, night = night)
        }
        Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
            avatar()
            Column(Modifier.padding(start = 10.dp).weight(1f).clickable { menu = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profileName, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (verified) Icon(
                        androidx.compose.material.icons.Icons.Rounded.Verified, contentDescription = "Verified",
                        tint = Color.White, modifier = Modifier.padding(start = 4.dp).size(16.dp),
                    )
                }
                Text(
                    (if (isGroup) "🌳 Shared garden · $memberCount · " else "") +
                        (if (garden.generation > 0) "🌳×${garden.generation} · " else "") +
                        "${garden.stage.emoji} ${if (isGroup) "" else "Shared "}${garden.species.name.lowercase()} · ${(garden.progress * 100).toInt()}%" +
                        (if (disappearSecs > 0) " · ⏳" else ""),
                    color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (conv?.pinned == true) "Unpin" else "Pin") }, onClick = { repo.setPinned(peer, conv?.pinned != true); menu = false })
                    DropdownMenuItem(
                        text = { Text("Disappearing messages · " + app.amend.messenger.data.Disappear.label(disappearSecs)) },
                        onClick = { menu = false; onDisappear() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (sharingLive) "Stop sharing live location" else "Share live location") },
                        onClick = { menu = false; onLive() },
                    )
                    if (isGroup) {
                        DropdownMenuItem(text = { Text("Members & add people") }, onClick = { menu = false; onMembers() })
                        DropdownMenuItem(text = { Text("Rename garden") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Leave garden") }, onClick = { menu = false; onLeave() })
                    } else {
                        DropdownMenuItem(
                            text = { Text(if (verified) "Security code · verified ✓" else "Verify security code") },
                            onClick = { menu = false; onVerify() },
                        )
                        DropdownMenuItem(text = { Text("Block") }, onClick = { repo.block(peer); menu = false; onBack() })
                    }
                    DropdownMenuItem(text = { Text("Report", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onReport() })
                }
            }
            if (!isGroup) {
                IconButton(onClick = { onCall(false) }) { Icon(Icons.Rounded.Call, contentDescription = "Voice call", tint = Color.White) }
                IconButton(onClick = { onCall(true) }) { Icon(Icons.Rounded.Videocam, contentDescription = "Video call", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun RequestBanner(onAccept: () -> Unit, onBlock: () -> Unit, onReport: () -> Unit, invitedBy: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)
    ) {
        Text(if (invitedBy != null) "🌳 Shared garden invitation" else "New message request", style = MaterialTheme.typography.titleMedium)
        Text(
            if (invitedBy != null) "$invitedBy added you to a group you haven't joined yet. Accept to chat, block to never hear from it again, or report if it's abusive."
            else "You haven't chatted with this person before. Accept to reply, block to never hear from them again, or report if it's abusive.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onAccept, shape = RoundedCornerShape(16.dp)) { Text("Accept") }
            OutlinedButton(onClick = onBlock, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Block") }
            TextButton(onClick = onReport) { Text("Report", color = MaterialTheme.colorScheme.error) }
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
    authorName: String? = null,
    liveShare: app.amend.messenger.data.LiveShare? = null,
    onStopLive: () -> Unit = {},
    canUnsend: Boolean = false,
    onUnsend: () -> Unit = {},
    canEdit: Boolean = false,
    onEdit: () -> Unit = {},
    onReportMessage: () -> Unit = {},
    fromStranger: Boolean = false,
    onSendNow: () -> Unit = {},
    onCancelScheduled: () -> Unit = {},
) {
    val mine = message.fromMe
    val dark = LocalDark.current
    val unsent = message.type == MessageType.UNSENT
    val scheduled = message.status == MessageStatus.SCHEDULED
    var showReactions by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var bubblePos by remember { mutableStateOf(Offset.Zero) }
    val haptics = rememberHaptics()
    // pop-in when the bubble first appears
    val appear = remember { Animatable(if (System.currentTimeMillis() / 1000 - message.createdAt < 5) 0f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)) }
    // horizontal drag to reply
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // on-device translation / voice transcription shown under the bubble
    var langNote by remember(message.id) { mutableStateOf<String?>(null) }
    var langBusy by remember(message.id) { mutableStateOf(false) }
    var strangerRevealed by remember(message.id) { mutableStateOf(false) }
    val mediaKind = remember(message.extra, message.type) {
        if (message.type == MessageType.VIDEO || message.type == MessageType.FILE) app.amend.messenger.data.MediaInfo.fromJson(message.extra)?.kind else null
    }
    val canTranslate = !mine && message.type == MessageType.TEXT && message.text.isNotBlank()
    val canTranscribe = message.type == MessageType.VOICE && app.amend.messenger.util.VoiceTranscriber.supported
    fun translate() {
        langBusy = true; langNote = null
        scope.launch {
            val r = app.amend.messenger.util.MessageTranslator.translate(message.text, app.amend.messenger.ui.I18n.current)
            langBusy = false
            langNote = when (r) {
                is app.amend.messenger.util.MessageTranslator.Result.Done -> "🌐 ${r.from}: ${r.text}"
                app.amend.messenger.util.MessageTranslator.Result.SameLanguage -> "🌐 Already in your language"
                app.amend.messenger.util.MessageTranslator.Result.Unknown -> "🌐 Couldn't tell which language this is"
                is app.amend.messenger.util.MessageTranslator.Result.Failed -> "🌐 ${r.reason}"
            }
        }
    }
    fun transcribe() {
        val media = app.amend.messenger.data.MediaInfo.fromJson(message.extra) ?: return
        langBusy = true; langNote = null
        scope.launch {
            val file = runCatching { repo.fetchMedia(media) }.getOrNull()
            val r = if (file == null) Result.failure(IllegalStateException("Couldn't download the voice note"))
                else app.amend.messenger.util.VoiceTranscriber.transcribe(context, file, java.util.Locale.getDefault().toLanguageTag())
            langBusy = false
            langNote = r.fold({ "📝 $it" }, { "📝 ${it.message}" })
        }
    }

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (authorName != null) {
            Text(
                authorName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
            )
        }
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
                    if (unsent) Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant))
                    else if (scheduled) Brush.linearGradient(listOf(Color(0xFF2BB673).copy(alpha = 0.55f), Color(0xFF1C9A62).copy(alpha = 0.55f)))
                    else if (message.type == MessageType.LIVE) Brush.linearGradient(listOf(Color(0xFF0C7C96), Color(0xFF2BB673)))
                    else if (message.type == MessageType.SOS) {
                        if (message.cardId == "stop") Brush.linearGradient(listOf(Color(0xFF1C7A4E), Color(0xFF2BB673)))
                        else Brush.linearGradient(listOf(Color(0xFFE53935), Color(0xFF7F0000)))
                    }
                    else if (message.type == MessageType.VIDEO && mediaKind != app.amend.messenger.data.MediaKind.CLIP) Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                    else if (message.type == MessageType.ECO_CARD) Brush.linearGradient(listOf(Color(0xFF1C7A4E), Color(0xFF2BB673), Color(0xFF4FC3F7)))
                    else if (mine) Brush.linearGradient(listOf(Color(0xFF2BB673), Color(0xFF1C9A62)))
                    else Brush.linearGradient(listOf(if (dark) Color(0xFF1B3A2D) else Color.White, if (dark) Color(0xFF16352A) else Color(0xFFF1F8F3)))
                )
                .combinedClickable(
                    onClick = {},
                    onDoubleClick = { if (!unsent && !scheduled) onReact("💚", bubblePos) },
                    onLongClick = { if (!unsent) { haptics.heavy(); showReactions = true } },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            val textColor = if (unsent) MaterialTheme.colorScheme.onSurfaceVariant
                else if (mine || message.type == MessageType.ECO_CARD || message.type == MessageType.SOS || message.type == MessageType.LIVE) Color.White else MaterialTheme.colorScheme.onSurface
            Column {
                replied?.let {
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.12f)).padding(8.dp)) {
                        Text(androidx.compose.ui.text.AnnotatedString(it.text), color = textColor.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (unsent) {
                    Text(
                        if (mine) "🚫 You unsent a message" else "🚫 This message was unsent",
                        color = textColor, style = MaterialTheme.typography.bodyMedium,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    )
                } else if (fromStranger && !strangerRevealed && message.type in STRANGER_HIDDEN) {
                    // Media, files and locations from someone you haven't accepted stay covered until you choose to look
                    Column(Modifier.clickable { strangerRevealed = true }) {
                        Text("🙈 Hidden " + when (message.type) {
                            MessageType.VOICE -> "voice message"; MessageType.VIDEO -> "video"; MessageType.LIVE -> "location"; else -> "photo or file"
                        }, color = textColor, style = MaterialTheme.typography.bodyMedium)
                        Text("From someone you haven't accepted · tap to show", color = textColor.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                } else if (message.type == MessageType.LIVE) {
                    LiveMessageContent(message, liveShare, onStopLive)
                } else if (message.type == MessageType.SOS) {
                    SosMessageContent(message, liveSos)
                } else if (message.type == MessageType.ECO_CARD && message.cardId?.startsWith("ach:") == true) {
                    AchievementCardContent(message, Color.White)
                } else if (message.type == MessageType.ECO_CARD) {
                    EcoCardContent(repo, message)
                } else if (message.type == MessageType.VOICE) {
                    VoiceBubble(repo, message, textColor)
                } else if (message.type == MessageType.VIDEO && mediaKind == app.amend.messenger.data.MediaKind.CLIP) {
                    ClipBubble(repo, message, textColor)
                } else if (message.type == MessageType.VIDEO) {
                    VideoBubble(repo, message)
                } else if (message.type == MessageType.FILE && mediaKind == app.amend.messenger.data.MediaKind.PHOTO) {
                    PhotoBubble(repo, message, textColor)
                } else if (message.type == MessageType.FILE && mediaKind == app.amend.messenger.data.MediaKind.INSTANT) {
                    InstantBubble(repo, message, textColor)
                } else if (message.type == MessageType.CALL) {
                    CallLogContent(message, textColor, onCall)
                } else if (message.type == MessageType.ECO_PROOF) {
                    EcoProofContent(repo, message, textColor)
                } else {
                    // a message that is just a Tenor / Giphy link shows the GIF (never for strangers)
                    val gif = remember(message.text, fromStranger) { if (fromStranger || unsent) null else GifLinks.parse(message.text) }
                    if (gif != null) {
                        GifLinkBubble(gif, autoLoad = mine, captionColor = textColor)
                    } else {
                        // links from strangers aren't tappable (phishing / grooming lures); worked out once per text
                        val body = remember(message.text, textColor, fromStranger) {
                            if (fromStranger) androidx.compose.ui.text.AnnotatedString(message.text) else linkify(message.text, textColor)
                        }
                        Text(body, color = textColor, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (message.expiresAt > 0) Text("⏳ ", color = textColor.copy(alpha = 0.7f), fontSize = 11.sp)
                    if (message.editedAt > 0 && !unsent) Text("edited · ", color = textColor.copy(alpha = 0.7f), fontSize = 11.sp)
                    val time = remember(scheduled, message.scheduledAt, message.createdAt) {
                        Instant.ofEpochSecond(if (scheduled) message.scheduledAt else message.createdAt).atZone(ZoneId.systemDefault())
                            .format(if (scheduled) SCHEDULED_TIME else BUBBLE_TIME)
                    }
                    Text((if (scheduled) "Scheduled · " else "") + time, color = textColor.copy(alpha = 0.7f), fontSize = 11.sp)
                    if (mine && message.type != MessageType.CALL && !unsent) {
                        Spacer(Modifier.size(4.dp))
                        GrowthTick(message.status, onRetry)
                    }
                }
            }
        }
        if (langBusy || langNote != null) {
            Text(
                androidx.compose.ui.text.AnnotatedString(if (langBusy) "…" else langNote!!),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 10.dp, vertical = 2.dp)
                    .clickable(enabled = !langBusy) { langNote = null },
            )
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
            if (scheduled) {
                DropdownMenuItem(text = { Text("Send now") }, onClick = { showReactions = false; onSendNow() })
                DropdownMenuItem(text = { Text("Cancel scheduled message") }, onClick = { showReactions = false; onCancelScheduled() })
            }
            if (!scheduled) Row(Modifier.padding(horizontal = 8.dp)) {
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
                // any other emoji: opens the full picker
                Box(
                    Modifier.padding(start = 2.dp).align(Alignment.CenterVertically).size(36.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showReactions = false; showEmojiPicker = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "More reactions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (mine) {
                DropdownMenuItem(
                    text = { Text(statusLabel(message.status, message.relaysAccepted), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { showReactions = false },
                    leadingIcon = { GrowthTick(message.status, onRetry = {}, onBubble = false) },
                )
            }
            if (canEdit) {
                val left = ((app.amend.messenger.data.AmendRepository.UNSEND_WINDOW - (System.currentTimeMillis() / 1000 - message.createdAt)) / 60).coerceAtLeast(1)
                DropdownMenuItem(text = { Text("Edit · ${left} min left") }, onClick = { showReactions = false; onEdit() })
            }
            if (canUnsend) {
                val left = ((app.amend.messenger.data.AmendRepository.UNSEND_WINDOW - (System.currentTimeMillis() / 1000 - message.createdAt)) / 60).coerceAtLeast(1)
                DropdownMenuItem(
                    text = { Text("Unsend · ${left} min left", color = MaterialTheme.colorScheme.error) },
                    onClick = { showReactions = false; onUnsend() },
                )
            }
            if (!scheduled) DropdownMenuItem(text = { Text("Reply") }, onClick = { showReactions = false; onReply() })
            if (canTranslate) DropdownMenuItem(text = { Text("Translate") }, onClick = { showReactions = false; translate() })
            if (canTranscribe) DropdownMenuItem(text = { Text("Transcribe") }, onClick = { showReactions = false; transcribe() })
            if (!mine && !scheduled) DropdownMenuItem(text = { Text("Report", color = MaterialTheme.colorScheme.error) }, onClick = { showReactions = false; onReportMessage() })
            DropdownMenuItem(text = { Text("Copy") }, onClick = {
                showReactions = false
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.text))
                haptics.success()
            })
        }
    }
    if (showEmojiPicker) {
        EmojiPickerDialog(onDismiss = { showEmojiPicker = false }) { emoji ->
            showEmojiPicker = false
            haptics.success()
            onReact(emoji, bubblePos)
        }
    }
}

/** The full emoji keyboard (every emoji, skin tones, recents) for reacting with anything. */
@Composable
private fun EmojiPickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val dark = LocalDark.current
    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    val themed = android.view.ContextThemeWrapper(
                        ctx, if (dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light,
                    )
                    androidx.emoji2.emojipicker.EmojiPickerView(themed).apply {
                        emojiGridColumns = 8
                        setOnEmojiPickedListener { item -> onPick(item.emoji) }
                    }
                },
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
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
/** Formatters are costly to build: make them once, not on every bubble draw. */
private val BUBBLE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val SCHEDULED_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm")

private val URL_REGEX = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"]+)""")

/** Message types covered when they come from someone you haven't accepted. */
private val STRANGER_HIDDEN = setOf(MessageType.VIDEO, MessageType.VOICE, MessageType.FILE, MessageType.ECO_PROOF, MessageType.LIVE)

internal fun linkify(text: String, color: Color): androidx.compose.ui.text.AnnotatedString =
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
        Text(androidx.compose.ui.text.AnnotatedString(message.text), color = Color.White)
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

/** A small centred note in the chat ("⏳ … turned disappearing messages on", "🌱 … planted this garden"). */
@Composable
private fun NoticeRow(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            androidx.compose.ui.text.AnnotatedString(text),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** Two members' leaves overlapping — the face of a shared garden. */
@Composable
fun GroupAvatar(members: List<String>, profiles: Map<String, app.amend.messenger.data.Profile>, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size)) {
        val small = size * 0.7f
        members.getOrNull(0)?.let { Box(Modifier.align(Alignment.TopStart)) { LeafAvatar(it, profiles[it], small) } }
        members.getOrNull(1)?.let { Box(Modifier.align(Alignment.BottomEnd)) { LeafAvatar(it, profiles[it], small) } }
        if (members.isEmpty()) Text("🌳", fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun DisappearDialog(current: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⏳ Disappearing messages") },
        text = {
            Column {
                Text(
                    "New messages in this chat delete themselves from every phone after the time you pick. Relays that support it delete their encrypted copy too.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                app.amend.messenger.data.Disappear.options.forEach { secs ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(secs) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = secs == current, onClick = { onPick(secs) })
                        Text(app.amend.messenger.data.Disappear.label(secs), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Hold-to-send: pick when the message goes out. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDialog(onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val now = java.time.ZonedDateTime.now(zone)
    fun at(t: java.time.ZonedDateTime) = t.toEpochSecond()
    val tonight = now.withHour(20).withMinute(0).withSecond(0)
    val tomorrowMorning = now.plusDays(1).withHour(9).withMinute(0).withSecond(0)
    val picker = androidx.compose.material3.rememberTimePickerState(
        initialHour = (now.hour + 1) % 24, initialMinute = 0, is24Hour = true,
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🕒 Send later") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!custom) {
                    ScheduleOption("In 1 hour") { onPick(at(now.plusHours(1))) }
                    if (now.isBefore(tonight.minusMinutes(5))) ScheduleOption("Tonight at 20:00") { onPick(at(tonight)) }
                    ScheduleOption("Tomorrow at 09:00") { onPick(at(tomorrowMorning)) }
                    ScheduleOption("Pick a time…") { custom = true }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "It waits safely on this phone and sends by itself — even if amend is closed.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    androidx.compose.material3.TimePicker(state = picker)
                }
            }
        },
        confirmButton = {
            if (custom) TextButton(onClick = {
                var t = now.withHour(picker.hour).withMinute(picker.minute).withSecond(0)
                if (!t.isAfter(now)) t = t.plusDays(1) // a time already past today means tomorrow
                onPick(at(t))
            }) { Text("Schedule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ScheduleOption(label: String, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 8.dp),
    )
}

/** Who's in a shared garden, and adding people (which plants a new garden with everyone, as NIP-17 rooms are defined by their members). */
@Composable
private fun GroupMembersDialog(
    repo: AmendRepository,
    group: app.amend.messenger.data.GroupInfo,
    onAdd: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    val picked = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val conversations by repo.conversations.collectAsStateWithLifecycle()
    val candidates = remember(conversations, group) {
        conversations.values.filter { !it.isGroup && it.peer != repo.me && repo.isAccepted(it.peer) && it.peer !in group.members }.map { it.peer }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (adding) "Add people" else "🌳 ${group.members.size} in this garden") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                if (!adding) {
                    group.members.sortedBy { if (it == repo.me) "" else repo.displayName(it) }.forEach { m ->
                        Text(
                            if (m == repo.me) "You" else repo.displayName(m),
                            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                } else {
                    Text(
                        "Adding people starts a fresh garden with everyone (the old chat stays as it is), so new members never see earlier messages.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (candidates.isEmpty()) Text("Everyone you chat with is already here.", style = MaterialTheme.typography.bodyMedium)
                    candidates.forEach { pub ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { if (pub in picked) picked.remove(pub) else picked.add(pub) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(checked = pub in picked, onCheckedChange = { on -> if (on) picked.add(pub) else picked.remove(pub) })
                            Text(repo.displayName(pub), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!adding) TextButton(onClick = { adding = true }) { Text("Add people") }
            else TextButton(onClick = { onAdd(picked.toList()) }, enabled = picked.isNotEmpty()) { Text("Plant new garden") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}


/** A live-location bubble: the pin follows the sender while the share is running. */
@Composable
private fun LiveMessageContent(message: ChatMessage, share: app.amend.messenger.data.LiveShare?, onStop: () -> Unit) {
    val stored = app.amend.messenger.data.SosLocation.decode(message.extra, message.createdAt)
    val active = share != null && share.active
    val loc = (if (active) share?.location else null) ?: stored
    val until = Instant.ofEpochSecond(message.scheduledAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    Column {
        Text(if (message.fromMe) "📍 You're sharing your live location" else "📍 Live location", color = Color.White, style = MaterialTheme.typography.titleMedium)
        if (loc != null) {
            Spacer(Modifier.height(8.dp))
            app.amend.messenger.ui.components.LiveMap(loc, Modifier.size(width = 260.dp, height = 170.dp), live = active)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (active) "Live until $until · ±${(loc?.accuracyM ?: 0f).toInt()} m" else "Sharing ended",
            color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall,
        )
        if (message.fromMe && active) {
            TextButton(onClick = onStop) { Text("Stop sharing", color = Color.White) }
        }
    }
}

/**
 * The message box. It reads the text itself, so typing redraws only this box.
 * It also takes GIFs, stickers and images straight from the keyboard (Gboard, GIPHY,
 * SwiftKey: Tenor and Giphy GIFs) or a paste, and hands them to [onContent].
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChatInput(
    state: androidx.compose.runtime.MutableState<String>,
    modifier: Modifier = Modifier,
    onContent: (List<android.net.Uri>) -> Unit = {},
) {
    val field = androidx.compose.foundation.text.input.rememberTextFieldState(state.value)
    // keep the field and the chat's text in step: typing updates the text; sending, drafts
    // and edits (which change the text from outside) update the field
    val lastFromField = remember { arrayOf(state.value) }
    LaunchedEffect(field) {
        androidx.compose.runtime.snapshotFlow { field.text.toString() }.collect { t ->
            lastFromField[0] = t
            if (t != state.value) state.value = t
        }
    }
    LaunchedEffect(field) {
        androidx.compose.runtime.snapshotFlow { state.value }.collect { v ->
            if (v != lastFromField[0] && v != field.text.toString()) field.setTextAndPlaceCursorAtEnd(v)
        }
    }
    val receiver = remember(onContent) {
        object : androidx.compose.foundation.content.ReceiveContentListener {
            override fun onReceive(
                transferableContent: androidx.compose.foundation.content.TransferableContent,
            ): androidx.compose.foundation.content.TransferableContent? {
                val uris = mutableListOf<android.net.Uri>()
                val rest = transferableContent.consume { item ->
                    val u = item.uri
                    if (u != null && u.scheme == "content") { uris += u; true } else false
                }
                if (uris.isNotEmpty()) onContent(uris)
                return rest
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    androidx.compose.foundation.text.BasicTextField(
        state = field,
        modifier = modifier.contentReceiver(receiver),
        inputTransformation = androidx.compose.foundation.text.input.InputTransformation.maxLength(4000),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
        lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
        decorator = { inner ->
            Box(
                Modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(26.dp)).background(colors.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (field.text.isEmpty()) {
                    Text("Grow the conversation…", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
                inner()
            }
        },
    )
}
