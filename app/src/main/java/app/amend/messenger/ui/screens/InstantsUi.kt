package app.amend.messenger.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.ArchivedInstant
import app.amend.messenger.data.ChatMessage
import app.amend.messenger.data.MediaInfo
import app.amend.messenger.data.MessageStatus
import app.amend.messenger.ui.Text
import app.amend.messenger.ui.components.GlassCard
import app.amend.messenger.ui.components.rememberHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Instants: a photo taken right now with the camera — no gallery, no editing, just an
 * optional caption — sent to your close friends or chosen people. It disappears for each
 * person once they open it, and after 24 hours if they don't. It can't be screenshotted.
 */
@Composable
fun InstantCameraScreen(repo: AmendRepository, presetRecipients: List<String>, groupPeer: String? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var hasCamera by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }
    var front by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var caption by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val preset = presetRecipients.isNotEmpty() || groupPeer != null
    // default comes from Settings → Privacy & security → Who sees your Instants
    var audience by remember { mutableStateOf(if (preset) "chat" else if (repo.instantsToAll) "all" else "close") }
    var editFriends by remember { mutableStateOf(false) }

    FullScreenDialog(onDismissRequest = onClose) {
        Box(Modifier.fillMaxSize().background(Color(0xFF06140E))) {
            val s = shot
            if (s == null) {
                if (hasCamera) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } },
                        update = { view ->
                            val useFront = front
                            val future = ProcessCameraProvider.getInstance(context)
                            future.addListener({
                                val provider = future.get()
                                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                                val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                                runCatching {
                                    provider.unbindAll()
                                    provider.bindToLifecycle(lifecycleOwner, if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, cap)
                                    capture = cap
                                }
                            }, ContextCompat.getMainExecutor(context))
                        },
                    )
                } else {
                    Text("amend needs the camera for Instants.", color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
                CameraBar(
                    Modifier.align(Alignment.BottomCenter).safeBottom().padding(bottom = 28.dp),
                    captureEnabled = capture != null,
                    onFlip = { front = !front },
                    onCapture = {
                                val cap = capture ?: return@CameraBar
                                haptics.heavy()
                                cap.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val rot = image.imageInfo.rotationDegrees
                                        val raw = image.toBitmap()
                                        image.close()
                                        val m = Matrix().apply { postRotate(rot.toFloat()); if (front) postScale(-1f, 1f) }
                                        shot = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        status = "Couldn't take the photo"
                                    }
                                })
                    },
                )
            } else {
                Image(s.asImageBitmap(), contentDescription = "Your Instant", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeBottomAndIme().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        caption, { caption = it.take(200) }, Modifier.fillMaxWidth(),
                        placeholder = { Text("Add a caption…", color = Color.White.copy(alpha = 0.7f)) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White), shape = RoundedCornerShape(20.dp), singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (preset) InstantChip("This chat", true) {}
                        else {
                            InstantChip("Everyone in my chats", audience == "all") { audience = "all" }
                            InstantChip("Chosen people (${repo.closeFriends.size})", audience == "close") { audience = "close" }
                            if (audience == "close") InstantChip("Edit", false) { editFriends = true }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { shot = null; caption = "" }) { Text("Retake", color = Color.White) }
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).clickable(enabled = !sending) {
                                val targets = when {
                                    groupPeer != null -> listOf(groupPeer)
                                    preset -> presetRecipients
                                    audience == "close" -> repo.contactKeys().filter { it in repo.closeFriends }
                                    else -> repo.contactKeys()
                                }
                                if (targets.isEmpty()) { status = if (audience == "close") "Pick some close friends first" else "Accept a chat first"; return@clickable }
                                sending = true
                                status = null
                                val bmp = s
                                scope.launch {
                                    val n = runCatching {
                                        val scaled = withContext(Dispatchers.Default) {
                                            val f = app.amend.messenger.util.MediaPrep.MAX_PHOTO_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
                                            if (f < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true) else bmp
                                        }
                                        val p = app.amend.messenger.util.MediaPrep.encode(scaled, 82)
                                        repo.sendInstant(targets, p.jpeg, p.width, p.height, caption)
                                    }.getOrDefault(0)
                                    sending = false
                                    if (n > 0) { haptics.success(); onClose() } else status = "Couldn't send — check your connection"
                                }
                            }.padding(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF1C7A4E))
                            else Text("Send Instant", color = Color(0xFF1C7A4E), fontSize = 16.sp)
                        }
                    }
                    Text("Disappears once it's opened, or after 24 hours. Can't be screenshotted.", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                }
            }
            Row(Modifier.safeTop().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OverlayIconButton(Icons.Rounded.Close, "Close", onClose)
                Spacer(Modifier.width(10.dp))
                Text("Instant", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            status?.let {
                Text(it, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).safeTop().padding(top = 56.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xCCB71C1C)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
    if (editFriends) CloseFriendsDialog(repo, onDismiss = { editFriends = false })
}

@Composable
private fun InstantChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(if (selected) Color(0xFF2BB673) else Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    ) { Text(label, color = Color.White, fontSize = 14.sp) }
}

/**
 * An Instant in a chat. Sender: "Delivered / Opened". Receiver: a covered card — tap to view
 * once, full screen and screenshot-proof; closing it makes it disappear.
 */
@Composable
fun InstantBubble(repo: AmendRepository, message: ChatMessage, textColor: Color) {
    val media = remember(message.extra) { MediaInfo.fromJson(message.extra) } ?: return
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    if (message.fromMe) {
        Column {
            Text("📸 Instant", color = textColor, style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    message.cardId == AmendRepository.INSTANT_OPENED_MARK -> "Opened"
                    message.status == MessageStatus.SENDING -> "Sending…"
                    message.status == MessageStatus.FAILED -> "Not sent"
                    else -> "Delivered · not opened yet"
                },
                color = textColor.copy(alpha = 0.8f), fontSize = 12.sp,
            )
        }
        return
    }
    var viewing by remember { mutableStateOf(false) }
    val (file, loading, load) = rememberMediaFile(repo, media, autoLoad = true)
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = file != null || !loading) {
            haptics.tap()
            if (file == null) load() else viewing = true
        }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2BB673)), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            else Text("📸", fontSize = 22.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Instant", color = textColor, style = MaterialTheme.typography.titleSmall)
            Text("Tap to view · disappears after", color = textColor.copy(alpha = 0.75f), fontSize = 12.sp, fontStyle = FontStyle.Italic)
        }
    }
    if (viewing && file != null) {
        InstantViewerDialog(file, message, onClose = {
            viewing = false
            scope.launch { repo.consumeInstant(message.peer, message.id) }
        })
    }
}

/** Shows an Instant once, full screen and screenshot-proof. Closing it is the end of it. */
@Composable
fun InstantViewerDialog(file: File, message: ChatMessage, onClose: () -> Unit) {
    val label = remember(message.extra) { MediaInfo.fromJson(message.extra)?.kind?.label }
    FullScreenDialog(onDismissRequest = onClose, alwaysSecure = true) {
        var image by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(file) { image = decodeImage(file, 2400) }
        Box(Modifier.fillMaxSize().background(Color.Black).clickable { onClose() }) {
            image?.let { Image(it, contentDescription = "Instant", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                ?: CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            if (message.text != label && message.text.isNotBlank()) {
                Text(
                    message.text, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).safeBottom().padding(24.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Color(0x88000000)).padding(10.dp),
                )
            }
            Text(
                "Tap to close — it disappears", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopCenter).safeTop().padding(top = 16.dp),
            )
        }
    }
}

/** Opens an unopened Instant from the tray: downloads it, shows it once, then it's gone. */
@Composable
fun OpenInstantFromTray(repo: AmendRepository, message: ChatMessage, onDone: () -> Unit) {
    val media = remember(message.extra) { MediaInfo.fromJson(message.extra) } ?: run { onDone(); return }
    val scope = rememberCoroutineScope()
    val (file, loading, _) = rememberMediaFile(repo, media, autoLoad = true)
    if (file != null) {
        InstantViewerDialog(file, message, onClose = {
            scope.launch { repo.consumeInstant(message.peer, message.id) }
            onDone()
        })
    } else if (!loading) {
        LaunchedEffect(Unit) { onDone() }
    } else {
        FullScreenDialog(onDismissRequest = onDone, alwaysSecure = true) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        }
    }
}

/** Your sent Instants, kept privately on this phone for a year. Tap one to view or add it to your story. */
@Composable
fun InstantArchiveCard(repo: AmendRepository) {
    val archive by repo.instantArchive.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth().clickable { open = true }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📸", fontSize = 26.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Your Instants", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (archive.isEmpty()) "Instants you send are kept here, only on this phone, for a year."
                    else "${archive.size} kept privately on this phone",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
    if (open) InstantArchiveDialog(repo, archive, onClose = { open = false })
}

@Composable
private fun InstantArchiveDialog(repo: AmendRepository, archive: List<ArchivedInstant>, onClose: () -> Unit) {
    var selected by remember { mutableStateOf<ArchivedInstant?>(null) }
    var toStory by remember { mutableStateOf<File?>(null) }
    FullScreenDialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeTop()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("✕", fontSize = 20.sp) }
                Text("Your Instants", style = MaterialTheme.typography.titleLarge)
            }
            if (archive.isEmpty()) Text("Nothing yet. Tap 📸 in Chats to send your first Instant.", modifier = Modifier.padding(20.dp))
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(4.dp)) {
                items(archive, key = { it.id }) { a ->
                    var thumb by remember(a.id) { mutableStateOf<ImageBitmap?>(null) }
                    LaunchedEffect(a.id) { thumb = decodeImage(repo.archivedInstantFile(a), 360) }
                    Box(Modifier.padding(3.dp).aspectRatio(0.75f).clip(RoundedCornerShape(12.dp)).background(Color(0x22000000)).clickable { selected = a }) {
                        thumb?.let { Image(it, contentDescription = a.caption, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        Text(
                            java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(a.createdAt * 1000)),
                            color = Color.White, fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x88000000)).padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
    selected?.let { a ->
        val f = repo.archivedInstantFile(a)
        FullScreenDialog(onDismissRequest = { selected = null }) {
            var image by remember { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(a.id) { image = decodeImage(f, 2000) }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                image?.let { Image(it, contentDescription = a.caption, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                Row(Modifier.align(Alignment.BottomCenter).safeBottom().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InstantChip("Add to story", true) { toStory = f; selected = null }
                    InstantChip("Close", false) { selected = null }
                }
                if (a.caption.isNotBlank()) Text(a.caption, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).safeTop().padding(16.dp))
            }
        }
    }
    toStory?.let { f -> StoryComposer(repo, onClose = { toStory = null }, presetPhoto = f) }
}

