package app.amend.messenger.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.ChatMessage
import app.amend.messenger.data.MediaInfo
import app.amend.messenger.ui.Text
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.util.ScreenProtection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Decodes an image file no larger than [maxSide] px (keeps memory low in long chats). */
internal suspend fun decodeImage(file: File, maxSide: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, b)
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}

/** Dialog flags for showing someone's media full screen (blocked from screenshots when protection is on). */
internal fun mediaDialogProperties(alwaysSecure: Boolean = false) = DialogProperties(
    usePlatformDefaultWidth = false,
    // Fit the dialog between the status bar and the navigation bar on every phone (gesture
    // or 3-button navigation, any size): edge-to-edge dialogs didn't get insets, so buttons
    // ended up under the home button.
    decorFitsSystemWindows = true,
    securePolicy = if (alwaysSecure || ScreenProtection.enabled) SecureFlagPolicy.SecureOn else SecureFlagPolicy.SecureOff,
)

/** Loads (downloading + decrypting if needed) the file behind a media message. */
@Composable
internal fun rememberMediaFile(repo: AmendRepository, media: MediaInfo, autoLoad: Boolean): Triple<File?, Boolean, () -> Unit> {
    val scope = rememberCoroutineScope()
    var file by remember(media) { mutableStateOf(repo.localMediaFile(media).takeIf { it.exists() && it.length() > 0 }) }
    var loading by remember(media) { mutableStateOf(false) }
    var failed by remember(media) { mutableStateOf(false) }
    val load: () -> Unit = {
        if (file == null && !loading) scope.launch {
            loading = true; failed = false
            file = runCatching { repo.fetchMedia(media) }.getOrNull()
            loading = false
            failed = file == null
        }
    }
    LaunchedEffect(media, autoLoad) { if (autoLoad) load() }
    return Triple(file, loading || (file == null && !failed && autoLoad), load)
}

/** A photo in a chat: sized to its shape, tap for full screen with pinch-zoom. */
@Composable
fun PhotoBubble(repo: AmendRepository, message: ChatMessage, captionColor: Color) {
    val media = remember(message.extra) { MediaInfo.fromJson(message.extra) } ?: return
    val (file, loading, load) = rememberMediaFile(repo, media, autoLoad = media.size in 1..4_000_000)
    var image by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    var full by remember { mutableStateOf(false) }
    LaunchedEffect(file) { file?.let { image = decodeImage(it, 900) } }
    val ratio = if (media.width > 0 && media.height > 0) (media.width.toFloat() / media.height).coerceIn(0.5f, 2f) else 1f
    Box(
        Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(16.dp))
            .background(Color(0x33000000)).clickable { if (file == null) load() else full = true },
        contentAlignment = Alignment.Center,
    ) {
        image?.let { Image(it, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (image == null) {
            if (loading) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("📷 Tap to load · ${formatSize(media.size)}", color = Color.White, fontSize = 12.sp)
        }
    }
    if (message.text != media.kind.label && message.text.isNotBlank()) {
        Text(linkify(message.text, captionColor), color = captionColor, modifier = Modifier.padding(top = 6.dp).widthIn(max = 260.dp))
    }
    if (full && file != null) FullScreenPhoto(file, onClose = { full = false })
}

@Composable
fun FullScreenPhoto(file: File, onClose: () -> Unit, alwaysSecure: Boolean = false) {
    var image by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) { image = decodeImage(file, 2400) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = mediaDialogProperties(alwaysSecure)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            image?.let {
                Image(
                    it, contentDescription = "Photo", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale <= 1f) Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                )
            } ?: CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopStart)) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

/** A gallery video in a chat: poster frame with a play button, full screen player on tap. */
@Composable
fun ClipBubble(repo: AmendRepository, message: ChatMessage, captionColor: Color) {
    val media = remember(message.extra) { MediaInfo.fromJson(message.extra) } ?: return
    val haptics = rememberHaptics()
    val (file, loading, load) = rememberMediaFile(repo, media, autoLoad = false)
    var poster by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    var playing by remember { mutableStateOf(false) }
    var wantPlay by remember { mutableStateOf(false) }
    LaunchedEffect(file) { file?.let { poster = thumbnail(it) }; if (file != null && wantPlay) { playing = true; wantPlay = false } }
    val ratio = if (media.width > 0 && media.height > 0) (media.width.toFloat() / media.height).coerceIn(0.56f, 1.8f) else 16f / 9f
    Box(
        Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0D2219)).clickable {
                haptics.tap()
                if (file == null) { wantPlay = true; load() } else playing = true
            },
        contentAlignment = Alignment.Center,
    ) {
        poster?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.size(54.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(Icons.Rounded.PlayArrow, contentDescription = "Play video", tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Text(
            formatDuration(media.durationMs) + if (file == null) " · ${formatSize(media.size)}" else "",
            color = Color.White, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(8.dp))
                .background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
    if (message.text != media.kind.label && message.text.isNotBlank()) {
        Text(linkify(message.text, captionColor), color = captionColor, modifier = Modifier.padding(top = 6.dp).widthIn(max = 260.dp))
    }
    if (playing && file != null) FullScreenVideo(file, onClose = { playing = false })
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun FullScreenVideo(file: File, onClose: () -> Unit, alwaysSecure: Boolean = false) {
    val context = LocalContext.current
    val player = remember(file) {
        AudioPlayback.stop()
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Dialog(onDismissRequest = onClose, properties = mediaDialogProperties(alwaysSecure)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx -> androidx.media3.ui.PlayerView(ctx).apply { this.player = player; useController = true } },
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopStart)) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1_000_000 -> "${bytes / 1000} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
}

/** Preview of the photos/videos you picked, with one caption, before sending. */
@Composable
fun MediaSendDialog(uris: List<android.net.Uri>, toName: String, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val context = LocalContext.current
    var caption by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (uris.size == 1) "Send to $toName" else "Send ${uris.size} to $toName") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    items(uris.size) { i ->
                        val uri = uris[i]
                        var thumb by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
                        val isVideo = remember(uri) { context.contentResolver.getType(uri)?.startsWith("video/") == true }
                        LaunchedEffect(uri) {
                            thumb = if (isVideo) withContext(Dispatchers.IO) {
                                runCatching {
                                    val r = android.media.MediaMetadataRetriever()
                                    try { r.setDataSource(context, uri); r.getFrameAtTime(0)?.asImageBitmap() } finally { r.release() }
                                }.getOrNull()
                            } else app.amend.messenger.util.AvatarImages.loadForCrop(context, uri, 300)?.asImageBitmap()
                        }
                        Box(Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x22000000)), contentAlignment = Alignment.Center) {
                            thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            if (isVideo) Icon(Icons.Rounded.PlayArrow, contentDescription = "Video", tint = Color.White)
                        }
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    caption, { caption = it.take(1000) }, Modifier.fillMaxWidth(),
                    placeholder = { Text("Add a caption…") }, shape = RoundedCornerShape(16.dp), maxLines = 4,
                )
                Text(
                    "Photos are shrunk and their hidden location data is removed. Everything is encrypted before it leaves your phone.",
                    fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSend(caption) }) { Text("Send") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Prepares and sends picked photos/videos one by one (caption on the first). Returns an error to show, if any. */
suspend fun sendPickedMedia(context: android.content.Context, repo: AmendRepository, peer: String, uris: List<android.net.Uri>, caption: String, replyTo: String?): String? {
    var error: String? = null
    uris.forEachIndexed { i, uri ->
        val cap = if (i == 0) caption else ""
        val reply = if (i == 0) replyTo else null
        val isVideo = context.contentResolver.getType(uri)?.startsWith("video/") == true
        if (isVideo) {
            val v = app.amend.messenger.util.MediaPrep.video(context, uri) { error = it } ?: return@forEachIndexed
            val bytes = withContext(Dispatchers.IO) { v.file.readBytes() }
            v.file.delete()
            runCatching { repo.sendMedia(peer, bytes, app.amend.messenger.data.MediaKind.CLIP, v.mime, v.durationMs, width = v.width, height = v.height, replyTo = reply, caption = cap) }
                .onFailure { error = "Couldn't send the video — check your connection and tap ⟳ to retry" }
        } else {
            val p = app.amend.messenger.util.MediaPrep.photo(context, uri)
            if (p == null) { error = "Couldn't read that photo"; return@forEachIndexed }
            runCatching { repo.sendMedia(peer, p.jpeg, app.amend.messenger.data.MediaKind.PHOTO, "image/jpeg", width = p.width, height = p.height, replyTo = reply, caption = cap) }
                .onFailure { error = "Couldn't send the photo — check your connection and tap ⟳ to retry" }
        }
    }
    return error
}
