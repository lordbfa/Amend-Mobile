package app.amend.messenger.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import app.amend.messenger.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.ChatMessage
import app.amend.messenger.data.MediaInfo
import app.amend.messenger.ui.components.rememberHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

// ======================================================================= voice recording

/**
 * Records voice messages: AAC in MP4, 48 kHz mono at 64 kbps — clear speech
 * at about 0.5 MB per minute. Samples the loudness for the waveform.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val samples = ArrayList<Int>()
    private var sampler: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _level = MutableStateFlow(0f)
    /** Current loudness 0..1 for the live animation. */
    val level: StateFlow<Float> = _level.asStateFlow()

    val elapsedMs: Long get() = if (startedAt == 0L) 0 else System.currentTimeMillis() - startedAt

    fun start(): Boolean {
        stopQuietly()
        val out = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(64_000)
            r.setMaxDuration(5 * 60 * 1000)
            r.setOutputFile(out.path)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = System.currentTimeMillis()
            samples.clear()
            sampler = scope.launch {
                while (isActive) {
                    val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
                    samples.add(amp)
                    _level.value = min(1f, amp / 18_000f)
                    delay(60)
                }
            }
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            false
        }
    }

    /** Stops and returns (file, duration, 64-point waveform), or null if too short / failed. */
    fun stop(): Triple<File, Long, ByteArray>? {
        val r = recorder ?: return null
        val f = file
        val duration = elapsedMs
        sampler?.cancel()
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        recorder = null
        startedAt = 0
        _level.value = 0f
        if (!ok || f == null || duration < 700) {
            f?.delete()
            return null
        }
        return Triple(f, duration, waveform(samples))
    }

    fun cancel() {
        stopQuietly()
    }

    private fun stopQuietly() {
        sampler?.cancel()
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete()
        file = null
        startedAt = 0
        _level.value = 0f
    }

    companion object {
        /** Squeezes loudness samples into 64 bars (0..255), normalised to the loudest. */
        fun waveform(samples: List<Int>, bars: Int = 64): ByteArray {
            if (samples.isEmpty()) return ByteArray(bars)
            val chunk = max(1, samples.size / bars)
            val values = (0 until bars).map { i ->
                val from = min(samples.size - 1, i * samples.size / bars)
                val to = min(samples.size, from + chunk)
                samples.subList(from, max(from + 1, to)).maxOrNull() ?: 0
            }
            val peak = max(1, values.maxOrNull() ?: 1)
            return values.map { (it * 255 / peak).coerceIn(8, 255).toByte() }.toByteArray()
        }
    }
}

// ======================================================================= shared audio player

data class PlaybackState(val id: String? = null, val playing: Boolean = false, val positionMs: Int = 0, val durationMs: Int = 0, val speed: Float = 1f)

/** One voice message plays at a time, across the whole app. */
object AudioPlayback {
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun toggle(id: String, file: File) {
        val s = _state.value
        val p = player
        if (s.id == id && p != null) {
            if (p.isPlaying) { p.pause(); _state.value = s.copy(playing = false) }
            else { p.start(); _state.value = s.copy(playing = true); tick() }
            return
        }
        stop()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.path)
            mp.prepare()
            if (Build.VERSION.SDK_INT >= 23) mp.playbackParams = mp.playbackParams.setSpeed(s.speed)
            mp.setOnCompletionListener { _state.value = _state.value.copy(playing = false, positionMs = 0); ticker?.cancel() }
            mp.start()
            player = mp
            _state.value = PlaybackState(id, true, 0, mp.duration, s.speed)
            tick()
        } catch (e: Exception) {
            mp.release()
        }
    }

    fun cycleSpeed() {
        val next = when (_state.value.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        player?.let { p -> runCatching { val wasPlaying = p.isPlaying; p.playbackParams = p.playbackParams.setSpeed(next); if (!wasPlaying) p.pause() } }
        _state.value = _state.value.copy(speed = next)
    }

    fun seek(id: String, fraction: Float) {
        val p = player ?: return
        if (_state.value.id != id) return
        val pos = (p.duration * fraction).toInt()
        p.seekTo(pos)
        _state.value = _state.value.copy(positionMs = pos)
    }

    fun stop() {
        ticker?.cancel()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        _state.value = _state.value.copy(id = null, playing = false, positionMs = 0)
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                _state.value = _state.value.copy(positionMs = runCatching { p.currentPosition }.getOrDefault(0))
                delay(50)
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

// ======================================================================= voice bubble

@Composable
fun VoiceBubble(repo: AmendRepository, message: ChatMessage, tint: Color) {
    val media = remember(message.extra) { MediaInfo.fromJson(message.extra) } ?: return
    val playback by AudioPlayback.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val mine = playback.id == message.id
    val progress = if (mine && playback.durationMs > 0) playback.positionMs / playback.durationMs.toFloat() else 0f

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(250.dp)) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(tint.copy(alpha = 0.22f)).clickable(enabled = !loading) {
                haptics.tap()
                scope.launch {
                    loading = true
                    failed = false
                    val file = runCatching { repo.fetchMedia(media) }.getOrNull()
                    loading = false
                    if (file == null) failed = true else AudioPlayback.toggle(message.id, file)
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> CircularProgressIndicator(Modifier.size(22.dp), color = tint, strokeWidth = 2.dp)
                mine && playback.playing -> Icon(Icons.Rounded.Pause, contentDescription = "Pause", tint = tint)
                else -> Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = tint)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Waveform(
                media.waveform, progress, tint,
                Modifier.fillMaxWidth().height(30.dp).clickable(enabled = mine) { },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (failed) "Couldn't load — tap to retry"
                    else if (mine) formatDuration(playback.positionMs.toLong()) + " / " + formatDuration(media.durationMs)
                    else formatDuration(media.durationMs),
                    color = tint.copy(alpha = 0.85f), fontSize = 11.sp, modifier = Modifier.weight(1f),
                )
                if (mine) {
                    Text(
                        "${playback.speed}×".replace(".0×", "×"),
                        color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.18f))
                            .clickable { haptics.tick(); AudioPlayback.cycleSpeed() }.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** Bars from the recorded loudness; the played part is filled brighter. */
@Composable
fun Waveform(samples: ByteArray, progress: Float, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val bars = if (samples.isEmpty()) ByteArray(48) { 40 } else samples
        val gap = 2.dp.toPx()
        val w = ((size.width - gap * (bars.size - 1)) / bars.size).coerceAtLeast(1f)
        bars.forEachIndexed { i, b ->
            val v = (b.toInt() and 0xff) / 255f
            val h = max(3.dp.toPx(), size.height * v)
            val x = i * (w + gap)
            val played = (i + 0.5f) / bars.size <= progress
            drawRoundRect(
                color.copy(alpha = if (played) 1f else 0.4f),
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}

/** Shown in place of the text field while recording: pulsing dot, timer, live level, cancel hint. */
@Composable
fun RecordingBar(recorder: VoiceRecorder, cancelling: Boolean, modifier: Modifier = Modifier) {
    val level by recorder.level.collectAsStateWithLifecycle()
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { elapsed = recorder.elapsedMs; delay(100) } }
    val t = rememberInfiniteTransition(label = "rec")
    val dot by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
    Row(
        modifier.clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFE53935).copy(alpha = dot)))
        Spacer(Modifier.width(8.dp))
        Text(formatDuration(elapsed), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(10.dp))
        // live level meter
        Canvas(Modifier.weight(1f).height(22.dp)) {
            val bars = 18
            val w = size.width / (bars * 1.6f)
            repeat(bars) { i ->
                val phase = kotlin.math.sin((elapsed / 90.0 + i) * 0.9).toFloat() * 0.25f + 0.75f
                val h = max(3.dp.toPx(), size.height * level * phase)
                drawRoundRect(
                    Color(0xFF2BB673),
                    topLeft = Offset(i * w * 1.6f, (size.height - h) / 2f),
                    size = Size(w, h), cornerRadius = CornerRadius(w / 2, w / 2),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (cancelling) "Release to cancel" else "‹ Slide to cancel",
            style = MaterialTheme.typography.labelMedium,
            color = if (cancelling) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ======================================================================= video bubble

/** First frame of a video file, for the bubble before playback. */
internal suspend fun thumbnail(file: File): ImageBitmap? = withContext(Dispatchers.IO) {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(file.path)
        r.getFrameAtTime(0)?.asImageBitmap()
    } catch (e: Exception) {
        null
    } finally {
        runCatching { r.release() }
    }
}

/**
 * Round video message. Downloads (and decrypts) on first tap, then plays inline,
 * muted thumbnails before that. Uses a TextureView so the circle clip works.
 */
@Composable
fun VideoBubble(repo: AmendRepository, message: ChatMessage) {
    val media = remember(message.extra) { MediaInfo.fromJson(message.extra) } ?: return
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var file by remember { mutableStateOf<File?>(repo.localMediaFile(media).takeIf { it.exists() && it.length() > 0 }) }
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(file) { file?.let { thumb = thumbnail(it) } }

    Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(200.dp).clip(CircleShape).background(Color(0xFF0D2219)).clickable {
                haptics.tap()
                val f = file
                if (f == null) {
                    if (loading) return@clickable
                    scope.launch {
                        loading = true
                        failed = false
                        playError = null
                        file = runCatching { repo.fetchMedia(media) }
                            .onFailure { android.util.Log.w("amend", "video download failed", it) }
                            .getOrNull()
                        loading = false
                        if (file != null) playing = true else failed = true
                    }
                } else {
                    playError = null
                    playing = !playing
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            val f = file
            if (playing && f != null) {
                RoundVideoPlayer(
                    f, onProgress = { progress = it }, onEnd = { playing = false; progress = 0f },
                    onError = { code ->
                        playing = false
                        progress = 0f
                        playError = code
                        // a damaged download: delete it so the next tap fetches it again
                        if (!message.fromMe) runCatching { f.delete() }.onSuccess { file = null }
                    },
                )
            } else {
                thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.size(54.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.PlayArrow, contentDescription = "Play video", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                if (failed || playError != null) {
                    Text(
                        if (failed) "Couldn't load · tap to retry" else "Can't play this video · tap to retry",
                        color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
                            .clip(RoundedCornerShape(8.dp)).background(Color(0xCCB71C1C)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        // progress ring around the circle
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            drawArc(Color(0x33FFFFFF), -90f, 360f, false, Offset(stroke, stroke), Size(size.width - 2 * stroke, size.height - 2 * stroke), style = Stroke(stroke))
            if (progress > 0f) {
                drawArc(Color(0xFF5BE39B), -90f, 360f * progress, false, Offset(stroke, stroke), Size(size.width - 2 * stroke, size.height - 2 * stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Text(
            formatDuration(media.durationMs),
            color = Color.White, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Plays a round video with Media3 ExoPlayer: it handles the camera's rotation
 * flag, starts fast, and tells us when a file can't be played.
 * The video is zoomed to fill the circle.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun RoundVideoPlayer(file: File, onProgress: (Float) -> Unit, onEnd: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val player = remember {
        AudioPlayback.stop()
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
            playWhenReady = true
            prepare()
        }
    }
    val view = remember { arrayOfNulls<TextureView>(1) }
    val videoAspect = remember { floatArrayOf(0f) }
    /** Centre-crop: the TextureView stretches the frame to the (square) view, so undo the stretch on one axis. */
    fun crop() {
        val t = view[0] ?: return
        val a = videoAspect[0]
        if (a <= 0f || t.width == 0 || t.height == 0) return
        val viewAspect = t.width.toFloat() / t.height
        val m = android.graphics.Matrix()
        if (a > viewAspect) m.setScale(a / viewAspect, 1f, t.width / 2f, t.height / 2f)
        else m.setScale(1f, viewAspect / a, t.width / 2f, t.height / 2f)
        t.setTransform(m)
    }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED) onEnd()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.w("amend", "video playback failed", error)
                onError(error.errorCodeName)
            }
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    // a 90°/270° flag the decoder didn't apply means width and height are swapped on screen
                    val rotated = size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270
                    val w = size.width * size.pixelWidthHeightRatio
                    videoAspect[0] = if (rotated) size.height / w else w / size.height
                    crop()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            val d = player.duration
            if (d > 0) onProgress((player.currentPosition.toFloat() / d).coerceIn(0f, 1f))
            delay(50)
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            TextureView(ctx).apply {
                view[0] = this
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> crop() }
                player.setVideoTextureView(this)
            }
        },
    )
}

// ======================================================================= video recorder

/**
 * Full-screen recorder for short round video messages (up to 60 s), front camera
 * by default. Tap the big button to start, tap again to send. Encoded at about
 * 1.2 Mbit/s (480p) — sharp on a phone, light on data.
 */
@SuppressLint("MissingPermission") // CAMERA and RECORD_AUDIO are checked before this screen opens
@Composable
fun VideoRecorderScreen(onDone: (File, Long, Int, Int) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = rememberHaptics()
    var front by remember { mutableStateOf(true) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val maxMs = 60_000L
    LaunchedEffect(recording) {
        while (recording != null) {
            now = System.currentTimeMillis()
            delay(50)
        }
    }

    fun stopAndSend() {
        recording?.stop()
        recording = null
        haptics.success()
    }

    BackHandler {
        recording?.stop()
        recording = null
        onClose()
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF06140E))) {
        // Size the round preview to the screen: phones, big phones, tablets and landscape
        val ring = minOf(maxWidth * 0.78f, maxHeight * 0.46f, 460.dp)
        Column(
            // safeDrawing = status bar + navigation bar + display cutouts, on every screen size
            Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { recording?.stop(); recording = null; onClose() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
                Text("Video message", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (recording == null) {
                    IconButton(onClick = { haptics.tick(); front = !front }) {
                        Icon(Icons.Rounded.Cameraswitch, contentDescription = "Switch camera", tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // round preview with a progress ring
            Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                AndroidView(
                    modifier = Modifier.size(ring - 20.dp).clip(CircleShape),
                    factory = { ctx -> PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } },
                    update = { view ->
                        // read the camera choice here, so flipping it re-runs this block and re-binds the camera
                        val useFront = front
                        val future = ProcessCameraProvider.getInstance(context)
                        future.addListener({
                            val provider = future.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                            val recorder = Recorder.Builder()
                                .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                                .setTargetVideoEncodingBitRate(1_200_000)
                                .build()
                            val capture = VideoCapture.withOutput(recorder)
                            try {
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview, capture,
                                )
                                videoCapture = capture
                            } catch (e: Exception) {
                                videoCapture = null
                            }
                        }, ContextCompat.getMainExecutor(context))
                    },
                )
                val elapsed = if (recording != null) now - startedAt else 0L
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 6.dp.toPx()
                    drawArc(Color(0x33FFFFFF), -90f, 360f, false, Offset(stroke, stroke), Size(size.width - 2 * stroke, size.height - 2 * stroke), style = Stroke(stroke))
                    drawArc(
                        Color(0xFFE53935), -90f, 360f * (elapsed / maxMs.toFloat()).coerceIn(0f, 1f), false,
                        Offset(stroke, stroke), Size(size.width - 2 * stroke, size.height - 2 * stroke), style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (recording != null) formatDuration(now - startedAt) + " / 1:00" else "Tap to record · up to 60 seconds",
                color = Color.White, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            // record / send button
            val t = rememberInfiniteTransition(label = "recBtn")
            val pulse by t.animateFloat(0.9f, 1.05f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "p")
            Box(
                Modifier.padding(bottom = 32.dp).size(84.dp).clip(CircleShape).background(Color.White).padding(6.dp).clip(CircleShape)
                    .background(Color(0xFFE53935))
                    .clickable(enabled = videoCapture != null) {
                        val cap = videoCapture ?: return@clickable
                        if (recording != null) {
                            stopAndSend()
                            return@clickable
                        }
                        haptics.heavy()
                        val out = File(context.cacheDir, "video-${System.currentTimeMillis()}.mp4")
                        val options = FileOutputOptions.Builder(out).setDurationLimitMillis(maxMs).build()
                        startedAt = System.currentTimeMillis()
                        recording = cap.output.prepareRecording(context, options)
                            .withAudioEnabled()
                            .start(ContextCompat.getMainExecutor(context)) { event ->
                                if (event is VideoRecordEvent.Finalize) {
                                    recording = null
                                    if (!event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) {
                                        val r = MediaMetadataRetriever()
                                        var w = 0; var h = 0; var dur = 0L
                                        runCatching {
                                            r.setDataSource(out.path)
                                            w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                                            h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                                            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                        }
                                        runCatching { r.release() }
                                        if (dur >= 700 && out.length() > 0) onDone(out, dur, w, h) else out.delete()
                                    } else {
                                        out.delete()
                                    }
                                }
                            }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (recording != null) {
                    Box(Modifier.size((26 * pulse).dp).clip(RoundedCornerShape(6.dp)).background(Color.White))
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
}

@Suppress("unused")
private val keepArrangement = Arrangement.Center
