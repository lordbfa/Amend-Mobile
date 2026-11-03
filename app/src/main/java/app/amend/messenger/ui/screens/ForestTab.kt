package app.amend.messenger.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import app.amend.messenger.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.eco.Challenge
import app.amend.messenger.eco.Challenges
import app.amend.messenger.eco.EcoPoints
import app.amend.messenger.eco.Species
import app.amend.messenger.ui.LocalParticles
import app.amend.messenger.ui.components.GlassCard
import app.amend.messenger.ui.components.LivingSky
import app.amend.messenger.ui.components.ParticleKind
import app.amend.messenger.ui.components.drawPrecip
import app.amend.messenger.ui.components.drawTree
import app.amend.messenger.ui.components.isNight
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.ui.components.rememberHourOfDay
import app.amend.messenger.ui.components.rememberSway
import app.amend.messenger.ui.theme.LocalEfficiency
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs

@Composable
fun ForestTab(repo: AmendRepository, openChat: (String) -> Unit) {
    val eco by repo.eco.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ForestScene(repo) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatChip("🌳", "${eco.trees.size}", "trees", Modifier.weight(1f))
                StatChip("🌱", "${eco.totalPoints}", "points", Modifier.weight(1f))
                StatChip("🔥", "${eco.streak}", "day streak", Modifier.weight(1f))
            }
        }
        item {
            Text("Today's challenges", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
            Text(
                "Swipe through, do one, and grow your forest by ${EcoPoints.CHALLENGE} points.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item { ChallengeDeck(repo, openChat) }
        item { Spacer(Modifier.height(12.dp)) }
        item { Box(Modifier.padding(horizontal = 16.dp)) { AchievementsCard(repo, app.amend.messenger.eco.AchievementTab.FOREST) } }
        item { Spacer(Modifier.height(12.dp)) }
        item { FriendsLeaderboardCard(repo, openChat) }
        item { Spacer(Modifier.height(12.dp)) }
        item { WeatherCard(repo) }
        item { Spacer(Modifier.height(12.dp)) }
        item { EcoMapCard(onOpen = { openChat(ECO_MAP_ROUTE) }, onPlant = { openChat(ECO_MAP_PLANT_ROUTE) }) }
        item { Spacer(Modifier.height(12.dp)) }
        item { RealTreesCard(onPlant = { openChat(ECO_MAP_PLANT_ROUTE) }) }
    }
}

@Composable
fun StatChip(emoji: String, value: String, label: String, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 22.sp)
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The user's forest: grown trees on a hill (scrollable), the growing tree in front. Tap a tree to shake it. */
@Composable
private fun ForestScene(repo: AmendRepository) {
    val eco by repo.eco.collectAsStateWithLifecycle()
    val hour = rememberHourOfDay()
    val sway by rememberSway()
    val haptics = rememberHaptics()
    val particles = LocalParticles.current
    val efficiency = LocalEfficiency.current
    val shakes = remember { mutableStateMapOf<Int, Float>() }
    val scope = rememberCoroutineScope()
    val growth by animateFloatAsState(eco.forest.currentProgress.coerceAtLeast(0.04f), spring(Spring.DampingRatioLowBouncy, 30f), label = "growth")
    val density = LocalDensity.current
    val treeSpacing = with(density) { 86.dp.toPx() }
    val contentWidth = with(density) { ((eco.trees.size + 2) * treeSpacing).toDp() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val scroll = rememberScrollState()
    val night = isNight(hour)
    // Season + live weather decide how the forest looks (autumn leaves, snow, rain).
    val mood = app.amend.messenger.util.rememberSceneFx()
    val precipTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "precip")
    val precipPhase by precipTransition.animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(
            tween(if (efficiency) 3200 else 1500, easing = androidx.compose.animation.core.LinearEasing),
            androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "precipPhase",
    )

    BoxWithConstraints(Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))) {
        // The ground/hills must always cover at least the full width of this device's screen;
        // more trees extend it further and make it scroll sideways. A fixed width left a gap
        // on the right of wider screens (e.g. Pixel 10).
        val widthDp = maxOf(contentWidth, maxWidth)
        LivingSky(Modifier.fillMaxSize(), hour = hour, animate = !efficiency)
        Box(Modifier.fillMaxSize().horizontalScroll(scroll)) {
            Canvas(
                Modifier
                    .width(widthDp)
                    .height(380.dp)
                    .onGloballyPositioned { origin = it.positionInRoot() }
                    .pointerInput(eco.trees.size) { detectTapGestures { pos ->
                        val idx = ((pos.x - treeSpacing * 0.5f) / treeSpacing).let { Math.round(it) }
                        if (idx in 0..eco.trees.size) {
                            haptics.tick()
                            particles.burst(origin + Offset(treeSpacing * 0.5f + idx * treeSpacing, pos.y), ParticleKind.LEAVES, 16, 0.6f)
                            scope.launch {
                                val a = Animatable(1f)
                                a.animateTo(0f, tween(900)) { shakes[idx] = value }
                                shakes.remove(idx)
                            }
                        }
                    } }
            ) {
                val ground = size.height * 0.8f
                // two hill layers for depth
                val back = Path().apply {
                    moveTo(0f, ground - 30f)
                    var x = 0f
                    while (x < size.width) {
                        quadraticBezierTo(x + 120f, ground - 70f, x + 240f, ground - 30f)
                        x += 240f
                    }
                    lineTo(size.width, size.height); lineTo(0f, size.height); close()
                }
                drawPath(back, if (night) Color(0xFF0B241A) else Color(0xFF8BD39A))
                drawRect(
                    Brush.verticalGradient(
                        if (night) listOf(Color(0xFF123526), Color(0xFF06140E)) else listOf(Color(0xFF5DBB6F), Color(0xFF2E8B57)),
                        startY = ground, endY = size.height,
                    ),
                    topLeft = Offset(0f, ground), size = androidx.compose.ui.geometry.Size(size.width, size.height - ground),
                )
                // snow settling over the ground
                if (mood.snow > 0.02f) {
                    drawRect(
                        Color.White.copy(alpha = 0.5f * mood.snow),
                        topLeft = Offset(0f, ground - 14f),
                        size = androidx.compose.ui.geometry.Size(size.width, size.height - ground + 14f),
                    )
                }
                eco.trees.forEach { tree ->
                    val x = treeSpacing * 0.5f + tree.index * treeSpacing
                    val shake = (shakes[tree.index] ?: 0f) * kotlin.math.sin((shakes[tree.index] ?: 0f) * 30f) * 0.25f
                    drawTree(tree.species, 1f, tree.seedHex.take(6).toInt(16), Offset(x, ground + 6f), size.height * 0.5f, sway * (1f + (tree.index % 3) * 0.2f), shake, night, autumn = mood.autumn, snow = mood.snow)
                }
                // the growing tree, slightly larger and in front
                val ix = eco.trees.size
                val x = treeSpacing * 0.5f + ix * treeSpacing
                val shake = (shakes[ix] ?: 0f) * kotlin.math.sin((shakes[ix] ?: 0f) * 30f) * 0.25f
                val species = repo.growingSpecies()
                drawTree(species, growth, 1000 + ix, Offset(x, ground + 14f), size.height * 0.62f, sway, shake, night, autumn = mood.autumn, snow = mood.snow)
                // progress ring under the growing tree
                drawArc(
                    Color(0x55FFFFFF), -90f, 360f, false,
                    topLeft = Offset(x - 22f, ground + 26f), size = androidx.compose.ui.geometry.Size(44f, 44f),
                    style = Stroke(6f),
                )
                drawArc(
                    Color(0xFFFFE58A), -90f, 360f * eco.forest.currentProgress, false,
                    topLeft = Offset(x - 22f, ground + 26f), size = androidx.compose.ui.geometry.Size(44f, 44f),
                    style = Stroke(6f, cap = StrokeCap.Round),
                )
                // fireflies at night (hidden while it's snowing/raining)
                if (night && !efficiency && !mood.hasPrecip) {
                    repeat(14) { i ->
                        val fx = (i * 137f + sway * 900f * (if (i % 2 == 0) 1 else -1)) % size.width
                        val fy = ground - 40f - (i * 53f) % 160f
                        drawCircle(Color(0x88FFF59D), 3f + (i % 3), Offset(abs(fx), fy))
                    }
                }
                // rain / snow falling over the forest
                if (mood.hasPrecip) drawPrecip(mood.rain, mood.snow, precipPhase, efficiency)
            }
        }
        Column(Modifier.statusBarsPadding().padding(20.dp)) {
            Text("Your forest", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(
                "${eco.forest.currentStage.emoji} ${eco.forest.currentStage.label} · ${eco.forest.pointsInCurrent}/${EcoPoints.PER_TREE}",
                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ChallengeDeck(repo: AmendRepository, openChat: (String) -> Unit) {
    val eco by repo.eco.collectAsStateWithLifecycle()
    val conversations by repo.conversations.collectAsStateWithLifecycle()
    val seed = remember(repo.me) { (repo.me.take(4).toIntOrNull(16) ?: 0) % 30 }
    val deck = remember(seed) { Challenges.deck(LocalDate.now(), seed) }
    val pager = rememberPagerState { deck.size }
    val haptics = rememberHaptics()
    val particles = LocalParticles.current
    var sharing by remember { mutableStateOf<Challenge?>(null) }
    val scope = rememberCoroutineScope()
    val doneToday = eco.completedChallenges[LocalDate.now().toString()]

    HorizontalPager(
        state = pager,
        contentPadding = PaddingValues(horizontal = 32.dp),
        pageSpacing = 12.dp,
        modifier = Modifier.padding(top = 14.dp),
    ) { page ->
        val c = deck[page]
        val pageOffset = (pager.currentPage - page) + pager.currentPageOffsetFraction
        var buttonPos by remember { mutableStateOf(Offset.Zero) }
        val done = doneToday == c.id
        GlassCard(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val f = 1f - abs(pageOffset).coerceIn(0f, 1f) * 0.12f
                    scaleX = f; scaleY = f
                    rotationZ = pageOffset * -4f
                    alpha = 1f - abs(pageOffset).coerceIn(0f, 1f) * 0.4f
                },
            tint = if (done) Color(0x332BB673) else Color.Unspecified,
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF2BB673), Color(0xFF4FC3F7)))),
                        contentAlignment = Alignment.Center,
                    ) { Text(c.category.emoji, fontSize = 22.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(c.category.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(c.title, style = MaterialTheme.typography.titleLarge)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(c.description, style = MaterialTheme.typography.bodyLarge)
                if (c.co2Grams > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("≈ ${c.co2Grams} g CO₂ avoided", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (repo.completeChallenge(c.id)) {
                                haptics.success()
                                particles.burst(buttonPos, ParticleKind.SPARKS, 36, 1.1f)
                                particles.burst(buttonPos, ParticleKind.LEAVES, 20, 0.9f)
                            } else haptics.reject()
                        },
                        enabled = doneToday == null,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.onGloballyPositioned { buttonPos = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) },
                    ) { Text(if (done) "Done today ✓" else "I did it!") }
                    OutlinedButton(onClick = { haptics.tap(); sharing = c }, shape = RoundedCornerShape(18.dp)) { Text("Challenge a friend") }
                }
            }
        }
    }
    if (doneToday != null) {
        Text(
            "You've completed today's challenge — come back tomorrow for more points. You can still share these with friends.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 10.dp),
        )
    }

    sharing?.let { challenge ->
        val peers = conversations.values.filter { repo.isAccepted(it.peer) }.sortedByDescending { it.lastActivity }
        AlertDialog(
            onDismissRequest = { sharing = null },
            title = { Text("Send \"${challenge.title}\" to…") },
            text = {
                Column {
                    if (peers.isEmpty()) Text("Start a conversation first, then challenge your friends.")
                    peers.take(8).forEach { conv ->
                        Text(
                            "${app.amend.messenger.ui.components.emojiFor(conv.peer)}  ${repo.displayName(conv.peer)}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                sharing = null
                                haptics.success()
                                scope.launch { repo.shareChallenge(conv.peer, challenge.id) }
                                openChat(conv.peer)
                            }.padding(vertical = 12.dp, horizontal = 8.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sharing = null }) { Text("Close") } },
        )
    }
}


/** Friends' forests: contacts who opted in share trees/points/streak, end-to-end encrypted. */
@Composable
private fun FriendsLeaderboardCard(repo: AmendRepository, openChat: (String) -> Unit) {
    val eco by repo.eco.collectAsStateWithLifecycle()
    val friends by repo.friendStats.collectAsStateWithLifecycle()
    val profiles by repo.profiles.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<String?>(null) }
    viewing?.let { key ->
        val f = friends[key]
        if (f == null) androidx.compose.runtime.LaunchedEffect(key) { viewing = null }
        else FriendForestDialog(repo.displayName(key), f, onDismiss = { viewing = null }, onMessage = { viewing = null; openChat(key) })
    }
    val rows = remember(eco, friends, profiles) {
        (friends.map { (k, f) -> Triple(k, repo.displayName(k), f) } +
            Triple(repo.me, "You", app.amend.messenger.data.FriendStats(eco.trees.size, eco.totalPoints, eco.streak, 0)))
            .sortedWith(compareByDescending<Triple<String, String, app.amend.messenger.data.FriendStats>> { it.third.trees }.thenByDescending { it.third.points })
    }
    app.amend.messenger.ui.components.GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🏆 Friends' forests", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (eco.shareStats) "Sharing your forest with your contacts (encrypted). Tap a friend to visit their forest."
                        else "Share your trees, points and streak with your contacts — encrypted, never public.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(checked = eco.shareStats, onCheckedChange = { repo.setShareStats(it) })
            }
            if (rows.size > 1) {
                Spacer(Modifier.height(10.dp))
                rows.take(10).forEachIndexed { i, (key, name, f) ->
                    val medal = when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${i + 1}." }
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .then(if (key != repo.me) Modifier.clickable { viewing = key } else Modifier)
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(medal, modifier = Modifier.width(32.dp))
                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("🌳 ${f.trees}  🌱 ${f.points}  🔥 ${f.streak}", style = MaterialTheme.typography.labelMedium)
                        if (key != repo.me) Text("  ›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else if (eco.shareStats) {
                Spacer(Modifier.height(8.dp))
                Text("No friends are sharing yet — invite them to turn this on.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


/**
 * A friend's forest, drawn from the tree seeds they shared. It's their profile, so it
 * can't be screenshotted while screenshot protection is on.
 */
@Composable
private fun FriendForestDialog(name: String, f: app.amend.messenger.data.FriendStats, onDismiss: () -> Unit, onMessage: () -> Unit) {
    val secure = app.amend.messenger.util.ScreenProtection.enabled
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            securePolicy = if (secure) androidx.compose.ui.window.SecureFlagPolicy.SecureOn else androidx.compose.ui.window.SecureFlagPolicy.SecureOff,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surface),
        ) {
            FriendForestScene(f)
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("$name's forest", style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatChip("🌳", "${f.trees}", "trees", Modifier.weight(1f))
                    StatChip("🌱", "${f.points}", "points", Modifier.weight(1f))
                    StatChip("🔥", "${f.streak}", "day streak", Modifier.weight(1f))
                }
                if (f.forest.isEmpty() && f.trees > 0) {
                    Text(
                        "$name needs the latest amend to show their trees here.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onMessage, shape = RoundedCornerShape(16.dp)) { Text("Message") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun FriendForestScene(f: app.amend.messenger.data.FriendStats) {
    val hour = rememberHourOfDay()
    val sway by rememberSway()
    val night = isNight(hour)
    val efficiency = LocalEfficiency.current
    val density = LocalDensity.current
    val spacing = with(density) { 70.dp.toPx() }
    val trees = remember(f.forest) {
        f.forest.map { seed -> Species.fromSeed(seed.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) to seed.toInt(16) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))) {
        val widthDp = maxOf(with(density) { ((trees.size + 2) * spacing).toDp() }, maxWidth)
        LivingSky(Modifier.fillMaxSize(), hour = hour, animate = !efficiency)
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            Canvas(Modifier.width(widthDp).height(260.dp)) {
                val ground = size.height * 0.8f
                drawRect(
                    Brush.verticalGradient(
                        if (night) listOf(Color(0xFF123526), Color(0xFF06140E)) else listOf(Color(0xFF5DBB6F), Color(0xFF2E8B57)),
                        startY = ground, endY = size.height,
                    ),
                    topLeft = Offset(0f, ground), size = androidx.compose.ui.geometry.Size(size.width, size.height - ground),
                )
                trees.forEachIndexed { i, (species, seed) ->
                    drawTree(species, 1f, seed, Offset(spacing * 0.6f + i * spacing, ground + 6f), size.height * 0.5f, sway * (1f + (i % 3) * 0.2f), night = night)
                }
                val ix = trees.size
                drawTree(
                    Species.all[(ix * 3 + 1) % Species.all.size], (f.growing / 100f).coerceAtLeast(0.04f), 1000 + ix,
                    Offset(spacing * 0.6f + ix * spacing, ground + 12f), size.height * 0.58f, sway, night = night,
                )
            }
        }
    }
}
