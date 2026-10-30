package app.amend.messenger.ui.screens

import app.amend.messenger.util.StepTracker
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Forest
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import app.amend.messenger.ui.Text
import app.amend.messenger.ui.tr
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.AmendApp
import app.amend.messenger.MainActivity
import app.amend.messenger.core.crypto.Nip19
import app.amend.messenger.data.AmendEvent
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.Conversation
import app.amend.messenger.data.EcoRules
import app.amend.messenger.data.MessageType
import app.amend.messenger.data.Profile
import app.amend.messenger.ui.LocalParticles
import app.amend.messenger.ui.components.LeafAvatar
import app.amend.messenger.ui.components.LivingSky
import app.amend.messenger.ui.components.ParticleKind
import app.amend.messenger.ui.components.bouncy
import app.amend.messenger.ui.components.drawPrecip
import app.amend.messenger.ui.components.drawTree
import app.amend.messenger.ui.components.isNight
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.ui.components.rememberHourOfDay
import app.amend.messenger.ui.components.rememberSway
import app.amend.messenger.ui.theme.LocalEfficiency
import app.amend.messenger.util.SosManager
import app.amend.messenger.util.SosPrefs
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Tab(val label: String, val icon: ImageVector) {
    CHATS("Chats", Icons.Rounded.Forum),
    FOREST("Forest", Icons.Rounded.Forest),
    IMPACT("Impact", Icons.Rounded.Eco),
    YOU("You", Icons.Rounded.Person),
}

@Composable
fun HomeScreen(app: AmendApp, repo: AmendRepository, openChat: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHATS) }
    StepPermissionOnce()
    var showNewChat by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val context = LocalContext.current
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { (context as? MainActivity)?.requestNotificationPermission() }

    // One-time: allow location now, so an SOS can share it instantly in an emergency.
    val sosPrefs = remember { SosPrefs(context) }
    var askLocation by remember { mutableStateOf(false) }
    val locationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        delay(1_500) // let the notification permission dialog go first
        if (sosPrefs.enabled && !sosPrefs.askedLocation && !SosManager.hasLocationPermission(context)) {
            askLocation = true
        }
    }
    if (askLocation) {
        AlertDialog(
            onDismissRequest = { askLocation = false; sosPrefs.askedLocation = true },
            title = { Text("📍 Allow location for SOS") },
            text = {
                Text(
                    "If you're ever in danger, amend can send your live location to the people you chat with. " +
                        "It's only used during an SOS you start — never otherwise — and it's end-to-end encrypted. " +
                        "Allow it now so it works instantly when it matters."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askLocation = false
                    sosPrefs.askedLocation = true
                    locationLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text("Allow") }
            },
            dismissButton = { TextButton(onClick = { askLocation = false; sosPrefs.askedLocation = true }) { Text("Not now") } },
        )
    }
    LaunchedEffect(repo) {
        repo.events.collect { e ->
            when (e) {
                is AmendEvent.TreeGrown -> { toast = "🌳 A ${e.tree.species.name} joined your forest!"; haptics.success() }
                is AmendEvent.PointsEarned -> toast = "🌱 +${e.points} · ${e.reason}"
                else -> Unit
            }
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2600); toast = null } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    val selected = t == tab
                    val scale by animateFloatAsState(if (selected) 1.18f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "tabScale")
                    NavigationBarItem(
                        selected = selected,
                        onClick = { if (!selected) { haptics.tick(); tab = t } },
                        icon = { Icon(t.icon, contentDescription = t.label, Modifier.scale(scale)) },
                        label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(tab == Tab.CHATS, enter = scaleIn(spring(Spring.DampingRatioMediumBouncy)), exit = fadeOut()) {
                val source = remember { MutableInteractionSource() }
                FloatingActionButton(
                    onClick = { haptics.tap(); showNewChat = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(22.dp),
                    interactionSource = source,
                    modifier = Modifier.bouncy(source),
                ) { Icon(Icons.Filled.Add, contentDescription = "New chat") }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            // each tab keeps its scroll position when you switch away and back
            val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(220)) + slideInVertically(androidx.compose.animation.core.spring(stiffness = 600f)) { it / 24 } togetherWith fadeOut(tween(120)) },
                label = "tabs",
            ) { t ->
                tabStates.SaveableStateProvider(t.name) {
                    when (t) {
                        Tab.CHATS -> ChatsTab(repo, openChat, onScan = { haptics.tap(); showScanner = true })
                        Tab.FOREST -> ForestTab(repo, openChat)
                        Tab.IMPACT -> ImpactTab(repo, openReports = { openChat(StepTracker.REPORTS_PEER) })
                        Tab.YOU -> YouTab(app, repo, openSettings = { openChat(SETTINGS_ROUTE) })
                    }
                }
            }
            // in-app toast for points and new trees
            AnimatedVisibility(
                visible = toast != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(Color(0xFF1C7A4E), Color(0xFF2BB673))))
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) { Text(toast ?: "", color = Color.White, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }

    AnimatedVisibility(
        visible = showScanner,
        enter = fadeIn(tween(250)) + scaleIn(initialScale = 1.06f),
        exit = fadeOut(tween(200)),
    ) {
        QrScannerScreen(
            myKey = repo.me,
            onFound = { pub ->
                showScanner = false
                repo.addContact(pub)
                openChat(pub)
            },
            onClose = { showScanner = false },
        )
    }

    if (showNewChat) {
        NewChatDialog(
            onDismiss = { showNewChat = false },
            onStart = { pub, nick ->
                repo.addContact(pub, nick)
                showNewChat = false
                haptics.success()
                openChat(pub)
            },
        )
    }
}

@Composable
private fun NewChatDialog(onDismiss: () -> Unit, onStart: (String, String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var nick by remember { mutableStateOf("") }
    val parsed = Nip19.parsePublicKey(key)
    val haptics = rememberHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a conversation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Paste your friend's amend ID (npub1…). Any Nostr app that supports private messages (NIP-17) works too.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim() },
                    label = { Text("npub1…") }, singleLine = true,
                    isError = key.isNotEmpty() && parsed == null,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = nick, onValueChange = { nick = it.take(40) },
                    label = { Text("Nickname (optional)") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (parsed != null) onStart(parsed, nick) else haptics.reject() }, enabled = key.isNotEmpty()) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------- Chats tab

@Composable
private fun ChatsTab(repo: AmendRepository, openChat: (String) -> Unit, onScan: () -> Unit) {
    val conversations by repo.conversations.collectAsStateWithLifecycle()
    val profiles by repo.profiles.collectAsStateWithLifecycle()
    val eco by repo.eco.collectAsStateWithLifecycle()
    val incomingSos by repo.incomingSos.collectAsStateWithLifecycle()
    val sorted = remember(conversations) {
        conversations.values.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.lastActivity })
    }
    val (accepted, requests) = sorted.partition { repo.isAccepted(it.peer) || it.messages.any { m -> m.fromMe } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item { ChatsHeader(repo, profiles[repo.me], eco.forest.currentProgress, eco.trees.size, onScan) }
        // Friends who need help right now — always on top
        val activeSos = incomingSos.values.filter { it.active }.sortedByDescending { it.updatedAt }
        items(activeSos, key = { "sos" + it.peer }) { sos ->
            IncomingSosCard(sos, repo.displayName(sos.peer), onOpenChat = { openChat(sos.peer) }, onDismiss = { repo.dismissIncomingSos(sos.peer) })
        }
        if (requests.isNotEmpty()) {
            item { SectionLabel("🌾 Message requests · ${requests.size}") }
            items(requests, key = { "r" + it.peer }) { c -> ConversationRow(repo, c, profiles[c.peer], openChat, isRequest = true) }
            item { SectionLabel("💬 Conversations") }
        }
        if (accepted.isEmpty() && requests.isEmpty()) {
            item { EmptyChats() }
        }
        item(key = "walking") { WalkingChatRow(onOpen = { openChat(StepTracker.REPORTS_PEER) }) }
        items(accepted, key = { it.peer }) { c -> ConversationRow(repo, c, profiles[c.peer], openChat, isRequest = false) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun ChatsHeader(repo: AmendRepository, me: Profile?, progress: Float, trees: Int, onScan: () -> Unit) {
    val hour = rememberHourOfDay()
    val sway by rememberSway()
    val efficiency = LocalEfficiency.current
    val mood = app.amend.messenger.util.rememberSceneFx()
    val precipPhase by androidx.compose.animation.core.rememberInfiniteTransition(label = "precip")
        .animateFloat(
            0f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(if (efficiency) 3200 else 1500, easing = androidx.compose.animation.core.LinearEasing),
                androidx.compose.animation.core.RepeatMode.Restart,
            ),
            label = "precipPhase",
        )
    val greeting = when (hour.toInt()) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..21 -> "Good evening"
        else -> "Good night"
    }
    val haptics = rememberHaptics()
    val particles = LocalParticles.current
    var treeCenter by remember { mutableStateOf(Offset.Zero) }
    var headerTop by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
            .onGloballyPositioned { headerTop = it.positionInRoot() }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                haptics.tick()
                particles.burst(headerTop + treeCenter, ParticleKind.LEAVES, 18, 0.7f)
            }
    ) {
        LivingSky(Modifier.fillMaxSize(), hour = hour, animate = !efficiency)
        Canvas(Modifier.fillMaxSize()) {
            val ground = size.height * 0.86f
            val hill = Path().apply {
                moveTo(0f, ground)
                quadraticBezierTo(size.width * 0.75f, ground - 60f, size.width, ground - 10f)
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            val night = isNight(hour)
            drawPath(hill, if (night) Color(0xFF0F2E21) else Color(0xFF66C47A))
            if (mood.snow > 0.02f) {
                drawRect(
                    Color.White.copy(alpha = 0.5f * mood.snow),
                    topLeft = Offset(0f, ground - 12f),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height - ground + 12f),
                )
            }
            treeCenter = Offset(size.width * 0.78f, ground - 40f)
            drawTree(app.amend.messenger.eco.Species.all[(trees) % app.amend.messenger.eco.Species.all.size], progress.coerceAtLeast(0.12f), 7 + trees, Offset(size.width * 0.78f, ground - 34f), size.height * 0.95f, sway, night = night, autumn = mood.autumn, snow = mood.snow)
            if (mood.hasPrecip) drawPrecip(mood.rain, mood.snow, precipPhase, efficiency)
        }
        // SOS (tap 4×) and QR scanner, top-right
        Row(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SosButton()
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onScan,
                modifier = Modifier.clip(CircleShape).background(Color(0x33FFFFFF)),
            ) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Scan a QR code", tint = Color.White)
            }
        }
        Column(Modifier.statusBarsPadding().padding(start = 20.dp, top = 64.dp, bottom = 20.dp, end = 20.dp)) {
            Text("$greeting${me?.name?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""}", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                if (trees == 0) "Your first tree is ${(progress * 100).toInt()}% grown" else "$trees ${if (trees == 1) "tree" else "trees"} in your forest · next one ${(progress * 100).toInt()}%",
                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EmptyChats() {
    Column(Modifier.fillMaxWidth().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🌱", fontSize = 56.sp)
        Spacer(Modifier.height(8.dp))
        Text("No conversations yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tap + and paste a friend's amend ID. Share yours from the You tab.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(repo: AmendRepository, c: Conversation, profile: Profile?, openChat: (String) -> Unit, isRequest: Boolean) {
    val haptics = rememberHaptics()
    var confirmDelete by remember { mutableStateOf(false) }
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> { haptics.success(); repo.setPinned(c.peer, !c.pinned); false }
            SwipeToDismissBoxValue.EndToStart -> { haptics.heavy(); confirmDelete = true; false }
            SwipeToDismissBoxValue.Settled -> false
        }
    })
    val name = repo.displayName(c.peer)
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (toRight) Color(0xFF2BB673) else Color(0xFFE5534B))
                    .padding(horizontal = 24.dp),
                contentAlignment = if (toRight) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(if (toRight) Icons.Filled.PushPin else Icons.Filled.Delete, contentDescription = null, tint = Color.White)
            }
        },
    ) {
        val garden = EcoRules.gardenFor(repo.me, c)
        val last = c.lastMessage
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(onClick = { haptics.tap(); repo.markRead(c.peer); openChat(c.peer) }, onLongClick = { haptics.heavy(); repo.setPinned(c.peer, !c.pinned) })
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LeafAvatar(c.peer, profile, 54.dp, garden)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (c.pinned) Text("📌 ", fontSize = 12.sp)
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                Spacer(Modifier.height(2.dp))
                // a friend's words are shown as they are; only amend's own labels are translated
                Text(
                    androidx.compose.ui.text.AnnotatedString(when {
                        isRequest -> tr("Wants to chat · tap to review")
                        last == null -> tr("${garden.species.emoji} Say hello to grow your garden")
                        last.type == MessageType.ECO_CARD -> tr("🌱 Eco challenge")
                        last.type == MessageType.SOS -> tr(if (last.cardId == "stop") "✅ SOS ended" else "🆘 SOS")
                        else -> (if (last.fromMe) tr("You: ") else "") + last.text
                    }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                last?.let { Text(formatTime(it.createdAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(4.dp))
                AnimatedVisibility(c.unread > 0, enter = scaleIn(spring(Spring.DampingRatioHighBouncy)), exit = fadeOut()) {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(c.unread.coerceAtMost(99).toString(), color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete conversation?") },
            text = { Text("Messages with $name are removed from this phone. Their shared garden will regrow if you chat again.") },
            confirmButton = { TextButton(onClick = { repo.deleteConversation(c.peer); confirmDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

fun formatTime(epochSeconds: Long): String {
    val dt = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    return when {
        dt.toLocalDate() == today -> dt.format(DateTimeFormatter.ofPattern("HH:mm"))
        dt.toLocalDate() == today.minusDays(1) -> "Yesterday"
        dt.year == today.year -> dt.format(DateTimeFormatter.ofPattern("d MMM"))
        else -> dt.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
}
