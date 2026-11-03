package app.amend.messenger.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.MediaInfo
import app.amend.messenger.data.MediaKind
import app.amend.messenger.data.Story
import app.amend.messenger.ui.Text
import app.amend.messenger.ui.components.LeafAvatar
import app.amend.messenger.ui.components.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Gradients for text stories — the forest at different times of day. */
val STORY_BACKGROUNDS = listOf(
    listOf(Color(0xFF1C7A4E), Color(0xFF2BB673), Color(0xFF4FC3F7)),
    listOf(Color(0xFFFFB347), Color(0xFFFF7E79), Color(0xFF8E54E9)),
    listOf(Color(0xFF0F2E21), Color(0xFF1B3A2D), Color(0xFF5BE39B)),
    listOf(Color(0xFF0C7C96), Color(0xFF4FC3F7), Color(0xFFBDEBC9)),
    listOf(Color(0xFF7F0000), Color(0xFFE53935), Color(0xFFFFC857)),
    listOf(Color(0xFF3A1C71), Color(0xFFD76D77), Color(0xFFFFAF7B)),
)

/** Ring around an avatar: green gradient when there's something new, grey when all watched. */
@Composable
fun StoryRing(unseen: Boolean, size: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 3.dp.toPx()
            drawCircle(
                if (unseen) Brush.sweepGradient(listOf(Color(0xFF2BB673), Color(0xFF4FC3F7), Color(0xFFFFC857), Color(0xFF2BB673)))
                else Brush.linearGradient(listOf(Color(0x55888888), Color(0x55888888))),
                radius = size.toPx() / 2 - w / 2, style = Stroke(w),
            )
        }
        Box(Modifier.size(size - 10.dp).clip(CircleShape)) { content() }
    }
}

/** The row of stories at the top of Chats: yours first, then friends with something new. */
@Composable
fun StoriesTray(repo: AmendRepository) {
    val stories by repo.stories.collectAsStateWithLifecycle()
    val profiles by repo.profiles.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    var composing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<List<String>?>(null) }
    var viewIndex by remember { mutableIntStateOf(0) }
    val mine = stories.filter { it.author == repo.me }
    val others = stories.filter { it.author != repo.me }.groupBy { it.author }
        .entries.sortedWith(compareBy<Map.Entry<String, List<Story>>> { e -> e.value.all { it.seen } }.thenByDescending { e -> e.value.maxOf { it.createdAt } })
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "me") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp).clickable {
                haptics.tap()
                if (mine.isEmpty()) composing = true else { viewing = listOf(repo.me); viewIndex = 0 }
            }) {
                Box {
                    StoryRing(unseen = mine.isNotEmpty(), size = 64.dp) { LeafAvatar(repo.me, profiles[repo.me], 54.dp) }
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(Color(0xFF2BB673))
                            .clickable { haptics.tap(); composing = true },
                        contentAlignment = Alignment.Center,
                    ) { Text("+", color = Color.White, fontSize = 15.sp) }
                }
                Text("Your story", fontSize = 11.sp, maxLines = 1)
            }
        }
        items(others, key = { it.key }) { (author, list) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp).clickable {
                haptics.tap()
                viewing = others.map { it.key }
                viewIndex = others.indexOfFirst { it.key == author }.coerceAtLeast(0)
            }) {
                StoryRing(unseen = list.any { !it.seen }, size = 64.dp) { LeafAvatar(author, profiles[author], 54.dp) }
                Text(repo.displayName(author), fontSize = 11.sp, maxLines = 1)
            }
        }
    }
    if (composing) StoryComposer(repo, onClose = { composing = false })
    viewing?.let { authors ->
        StoryViewer(repo, authors, viewIndex, onClose = { viewing = null })
    }
}

/** Make a story: a photo/video from the gallery or camera, or words on a gradient. */
@Composable
fun StoryComposer(repo: AmendRepository, onClose: () -> Unit, presetPhoto: File? = null) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var uri by remember { mutableStateOf<Uri?>(presetPhoto?.let { Uri.fromFile(it) }) }
    var text by remember { mutableStateOf("") }
    var bg by remember { mutableIntStateOf(0) }
    var closeOnly by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editFriends by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    val isVideo = remember(uri) { uri?.let { u -> u.scheme != "file" && context.contentResolver.getType(u)?.startsWith("video/") == true } == true }
    LaunchedEffect(uri) {
        preview = uri?.let { u ->
            if (isVideo) null else app.amend.messenger.util.AvatarImages.loadForCrop(context, u, 1200)?.asImageBitmap()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { if (it != null) uri = it }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) uri = cameraUri }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onClose, properties = mediaDialogProperties()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(STORY_BACKGROUNDS[bg]))) {
            when {
                preview != null -> Image(preview!!, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                isVideo -> Text("🎬 Video ready", color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
                else -> TextField(
                    text, { text = it.take(Story.MAX_TEXT) },
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp),
                    placeholder = { Text("Share a thought, a win for the planet…", color = Color.White.copy(alpha = 0.7f), fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = Color.White, textAlign = TextAlign.Center),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Color.White,
                    ),
                )
            }
            Row(Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("✕", color = Color.White, fontSize = 20.sp) }
                Spacer(Modifier.weight(1f))
                if (uri == null) {
                    TextButton(onClick = { haptics.tick(); bg = (bg + 1) % STORY_BACKGROUNDS.size }) { Text("🎨", fontSize = 20.sp) }
                } else {
                    TextButton(onClick = { uri = null }) { Text("Remove", color = Color.White) }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (uri != null && !isVideo) {
                    OutlinedTextField(
                        text, { text = it.take(Story.MAX_TEXT) }, Modifier.fillMaxWidth(),
                        placeholder = { Text("Add a caption…", color = Color.White.copy(alpha = 0.7f)) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White), shape = RoundedCornerShape(20.dp), maxLines = 3,
                    )
                }
                if (uri == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StoryChip("🖼 Gallery") { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                        StoryChip("📷 Camera") {
                            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                            val u = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", File(dir, "story-${System.currentTimeMillis()}.jpg"))
                            cameraUri = u
                            runCatching { camera.launch(u) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StoryChip(if (closeOnly) "⭐ Close friends (${repo.closeFriends.size})" else "🌍 All contacts", selected = true) { closeOnly = !closeOnly }
                    if (closeOnly) StoryChip("Edit") { editFriends = true }
                    Spacer(Modifier.weight(1f))
                    val canPost = !posting && (uri != null || text.isNotBlank())
                    Box(
                        Modifier.clip(RoundedCornerShape(24.dp)).background(if (canPost) Color.White else Color.White.copy(alpha = 0.4f))
                            .clickable(enabled = canPost) {
                                posting = true
                                error = null
                                val u = uri
                                scope.launch {
                                    val n = runCatching {
                                        when {
                                            u == null -> repo.postStory(null, text = text, bg = bg, closeFriendsOnly = closeOnly)
                                            isVideo -> {
                                                val v = app.amend.messenger.util.MediaPrep.video(context, u) { error = it }
                                                if (v == null) -1 else {
                                                    val bytes = v.file.readBytes(); v.file.delete()
                                                    repo.postStory(bytes, MediaKind.CLIP, v.mime, v.width, v.height, v.durationMs, closeFriendsOnly = closeOnly)
                                                }
                                            }
                                            else -> {
                                                val p = app.amend.messenger.util.MediaPrep.photo(context, u) ?: return@runCatching -1
                                                repo.postStory(p.jpeg, MediaKind.PHOTO, "image/jpeg", p.width, p.height, text = text, closeFriendsOnly = closeOnly)
                                            }
                                        }
                                    }.getOrElse { -2 }
                                    posting = false
                                    when {
                                        n >= 0 -> { haptics.success(); onClose() }
                                        n == -2 -> error = "Couldn't post — check your connection"
                                        error == null -> error = "Couldn't read that photo"
                                    }
                                }
                            }.padding(horizontal = 22.dp, vertical = 12.dp),
                    ) {
                        if (posting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF1C7A4E))
                        else Text("Share", color = Color(0xFF1C7A4E), fontSize = 16.sp)
                    }
                }
                error?.let { Text(it, color = Color.White, fontSize = 13.sp) }
                Text("Encrypted for each person. Disappears after 24 hours.", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
            }
        }
    }
    if (editFriends) CloseFriendsDialog(repo, onDismiss = { editFriends = false })
}

@Composable
private fun StoryChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = if (selected) 0.28f else 0.18f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    ) { Text(label, color = Color.White, fontSize = 14.sp) }
}

/** Pick your close friends (who get Instants and close-friends stories). */
@Composable
fun CloseFriendsDialog(repo: AmendRepository, onDismiss: () -> Unit) {
    val contacts = remember { repo.contactKeys() }
    var chosen by remember { mutableStateOf(repo.closeFriends) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⭐ Close friends") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (contacts.isEmpty()) Text("Accept a chat first — close friends are picked from your contacts.")
                contacts.forEach { k ->
                    Row(Modifier.fillMaxWidth().clickable { chosen = if (k in chosen) chosen - k else chosen + k }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = k in chosen, onCheckedChange = { chosen = if (it) chosen + k else chosen - k })
                        Text(repo.displayName(k))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { repo.setCloseFriends(chosen); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Full-screen stories: bars at the top, tap right/left for next/previous, hold to pause.
 * Moves on to the next person's stories when one person's are done.
 */
@Composable
fun StoryViewer(repo: AmendRepository, authors: List<String>, startAuthor: Int, onClose: () -> Unit) {
    val stories by repo.stories.collectAsStateWithLifecycle()
    val profiles by repo.profiles.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var authorIdx by remember { mutableIntStateOf(startAuthor) }
    val author = authors.getOrNull(authorIdx)
    val list = remember(stories, author) { stories.filter { it.author == author } }
    var idx by remember(author) { mutableIntStateOf(list.indexOfFirst { !it.seen }.takeIf { it >= 0 } ?: 0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var showViewers by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (author == null || list.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    val story = list[idx.coerceIn(0, list.lastIndex)]
    val mine = story.author == repo.me
    fun next() {
        if (idx < list.lastIndex) { idx++; progress = 0f }
        else if (authorIdx < authors.lastIndex) { authorIdx++; progress = 0f }
        else onClose()
    }
    fun prev() {
        if (idx > 0) { idx--; progress = 0f } else if (authorIdx > 0) { authorIdx--; progress = 0f } else progress = 0f
    }
    val media = remember(story.media) { story.media?.let { MediaInfo.fromJson(it) } }
    val (file, loading, _) = if (media != null) rememberMediaFile(repo, media, autoLoad = true) else Triple(null, false, {})
    var image by remember(story.id, file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) { if (file != null && media?.kind != MediaKind.CLIP) image = decodeImage(file, 1600) }
    LaunchedEffect(story.id) { scope.launch { repo.markStorySeen(story.id) } }
    val duration = if (media?.kind == MediaKind.CLIP) media.durationMs.coerceIn(3_000, 60_000) else 5_000L
    val ready = media == null || file != null
    LaunchedEffect(story.id, paused, ready, showViewers, reply.isNotEmpty()) {
        if (!ready || paused || showViewers || reply.isNotEmpty()) return@LaunchedEffect
        val step = 50L
        while (progress < 1f) { delay(step); progress += step.toFloat() / duration }
        next()
    }

    Dialog(onDismissRequest = onClose, properties = mediaDialogProperties()) {
        Box(Modifier.fillMaxSize().background(if (media == null) Brush.verticalGradient(STORY_BACKGROUNDS[story.bg.coerceIn(0, STORY_BACKGROUNDS.lastIndex)]) else Brush.verticalGradient(listOf(Color.Black, Color.Black)))) {
            when {
                media == null -> Text(
                    story.text, color = Color.White, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(28.dp),
                )
                media.kind == MediaKind.CLIP && file != null -> StoryVideo(file, paused)
                image != null -> Image(image!!, contentDescription = "Story", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            if (media != null && story.text.isNotBlank()) {
                Text(
                    story.text, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp, start = 20.dp, end = 20.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Color(0x88000000)).padding(10.dp),
                )
            }
            // tap zones: left third back, rest forward; hold to pause
            Box(Modifier.fillMaxSize().pointerInput(story.id) {
                detectTapGestures(
                    onPress = { paused = true; tryAwaitRelease(); paused = false },
                    onTap = { pos -> haptics.tick(); if (pos.x < size.width / 3f) prev() else next() },
                )
            })
            Column(Modifier.statusBarsPadding().padding(10.dp).fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    list.forEachIndexed { i, _ ->
                        val p = when { i < idx -> 1f; i == idx -> progress.coerceIn(0f, 1f); else -> 0f }
                        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f))) {
                            Box(Modifier.fillMaxWidth(p).height(3.dp).background(Color.White))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LeafAvatar(story.author, profiles[story.author], 36.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (mine) "Your story" else repo.displayName(story.author), color = Color.White, fontSize = 15.sp)
                        Text(
                            ago(story.createdAt) + if (story.closeFriends) " · ⭐ close friends" else "",
                            color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = onClose) { Text("✕", color = Color.White, fontSize = 20.sp) }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp)) {
                if (mine) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StoryChip("👁 ${story.viewers.size} of ${story.audience.size}") { showViewers = true }
                        Spacer(Modifier.weight(1f))
                        StoryChip("🗑 Delete") { confirmDelete = true }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("💚", "🌳", "😂", "😮", "🔥").forEach { e ->
                            Text(e, fontSize = 28.sp, modifier = Modifier.clip(CircleShape).clickable {
                                haptics.success()
                                scope.launch { repo.replyToStory(story.id, e) }
                            }.padding(4.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            reply, { reply = it.take(1000) }, Modifier.weight(1f),
                            placeholder = { Text("Reply to ${repo.displayName(story.author)}…", color = Color.White.copy(alpha = 0.7f)) },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White), shape = RoundedCornerShape(24.dp), singleLine = true,
                        )
                        if (reply.isNotBlank()) TextButton(onClick = {
                            val t = reply; reply = ""; haptics.success()
                            scope.launch { repo.replyToStory(story.id, t) }
                        }) { Text("Send", color = Color.White) }
                    }
                }
            }
        }
    }
    if (showViewers) {
        AlertDialog(
            onDismissRequest = { showViewers = false },
            title = { Text("Watched by ${story.viewers.size}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (story.viewers.isEmpty()) Text("Nobody yet. People who turned off read receipts don't show here.")
                    story.viewers.entries.sortedByDescending { it.value }.forEach { (k, t) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            LeafAvatar(k, profiles[k], 32.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(repo.displayName(k), modifier = Modifier.weight(1f))
                            Text(ago(t), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showViewers = false }) { Text("Done") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this story?") },
            text = { Text("It disappears for everyone it was shared with.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { repo.deleteStory(story.id) } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun StoryVideo(file: File, paused: Boolean) {
    val context = LocalContext.current
    val player = remember(file) {
        AudioPlayback.stop()
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }
    LaunchedEffect(paused) { player.playWhenReady = !paused }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx -> androidx.media3.ui.PlayerView(ctx).apply { this.player = player; useController = false } },
        modifier = Modifier.fillMaxSize(),
    )
}

internal fun ago(t: Long): String {
    val s = (System.currentTimeMillis() / 1000 - t).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        else -> "${s / 3600} h ago"
    }
}
