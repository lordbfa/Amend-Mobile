package app.amend.messenger.ui.screens

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.MediaKind
import app.amend.messenger.ui.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * GIFs: animated images from the keyboard (Gboard, GIPHY, SwiftKey: Tenor and Giphy GIFs),
 * from the gallery, and Tenor / Giphy links in messages.
 *
 * GIFs are sent like photos (encrypted, uploaded to Blossom) but keep their original bytes,
 * so they stay animated. GIF and WebP files carry no EXIF location data.
 */
object Gifs {
    /** Largest GIF we send or download. */
    const val MAX_BYTES = 15L * 1024 * 1024

    /** The real type of an image, from its first bytes (keyboards don't always say). */
    fun sniff(head: ByteArray): String? = when {
        head.size >= 4 && head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == '8'.code.toByte() -> "image/gif"
        head.size >= 12 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        head.size >= 4 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte() -> "image/png"
        head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> "image/jpeg"
        head.size >= 12 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "video/mp4"
        else -> null
    }

    fun isAnimatedMime(mime: String?): Boolean = mime == "image/gif" || mime == "image/webp"

    fun isAnimatedFile(file: File): Boolean = runCatching {
        val head = ByteArray(16)
        val n = file.inputStream().use { it.read(head) }
        isAnimatedMime(sniff(head.copyOf(maxOf(n, 0))))
    }.getOrDefault(false)

    /** Size of an image without decoding it. */
    fun bounds(bytes: ByteArray): Pair<Int, Int> {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth.coerceAtLeast(0) to o.outHeight.coerceAtLeast(0)
    }

    /** Content the keyboard (or a paste) handed to the message box, read right away while we may. */
    class Received(val bytes: ByteArray, val mime: String)

    fun read(context: Context, uri: Uri): Received? {
        // only content the keyboard or clipboard granted us; never our own private files
        if (uri.scheme != "content") return null
        if (uri.authority.orEmpty().startsWith(context.packageName)) return null
        return runCatching { readOrNull(context, uri) }.getOrNull()
    }

    private fun readOrNull(context: Context, uri: Uri): Received? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return null
        val mime = sniff(bytes.copyOf(minOf(16, bytes.size))) ?: return null
        return Received(bytes, mime)
    }

    /**
     * Sends what the keyboard gave us. GIF, WebP and PNG (stickers) go as they are; JPEG is
     * re-encoded so any hidden location data is dropped; MP4 "GIFs" go as short videos.
     * Returns an error to show, if any.
     */
    suspend fun send(context: Context, repo: AmendRepository, peer: String, item: Received, replyTo: String?, caption: String = ""): String? {
        return when (item.mime) {
            "image/gif", "image/webp", "image/png" -> {
                val (w, h) = bounds(item.bytes)
                runCatching { repo.sendMedia(peer, item.bytes, MediaKind.PHOTO, item.mime, width = w, height = h, replyTo = replyTo, caption = caption) }
                    .exceptionOrNull()?.let { "Couldn't send the GIF — check your connection and tap ⟳ to retry" }
            }
            "image/jpeg" -> {
                val bmp = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size) }
                    ?: return "Couldn't read that image"
                val p = app.amend.messenger.util.MediaPrep.encode(bmp)
                runCatching { repo.sendMedia(peer, p.jpeg, MediaKind.PHOTO, "image/jpeg", width = p.width, height = p.height, replyTo = replyTo, caption = caption) }
                    .exceptionOrNull()?.let { "Couldn't send the photo — check your connection and tap ⟳ to retry" }
            }
            "video/mp4" -> {
                val dir = File(context.cacheDir, "keyboard").apply { mkdirs() }
                val tmp = File(dir, "kbd-${System.nanoTime()}.mp4")
                try {
                    withContext(Dispatchers.IO) { tmp.writeBytes(item.bytes) }
                    var err: String? = null
                    val v = app.amend.messenger.util.MediaPrep.video(context, Uri.fromFile(tmp)) { err = it } ?: return err ?: "Couldn't read that video"
                    val bytes = withContext(Dispatchers.IO) { v.file.readBytes() }
                    v.file.delete()
                    runCatching { repo.sendMedia(peer, bytes, MediaKind.CLIP, v.mime, v.durationMs, width = v.width, height = v.height, replyTo = replyTo, caption = caption) }
                        .exceptionOrNull()?.let { "Couldn't send the GIF — check your connection and tap ⟳ to retry" }
                } finally {
                    tmp.delete()
                }
            }
            else -> "That kind of file can't be sent from the keyboard"
        }
    }
}

/**
 * Draws an image file; GIF and animated WebP play (Android 9+). [maxSide] limits the decoded
 * size so long chats stay light on memory.
 */
@Composable
fun AnimatedImage(file: File, contentDescription: String?, fit: Boolean, maxSide: Int, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < 28) {
        var image by remember(file) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(file) { image = decodeImage(file, maxSide) }
        image?.let { Image(it, contentDescription, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop, modifier = modifier) }
        return
    }
    var drawable by remember(file) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(file, maxSide) {
        drawable = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    var sample = 1
                    while (maxOf(info.size.width, info.size.height) / (sample * 2) >= maxSide) sample *= 2
                    decoder.setTargetSampleSize(sample)
                }
            }.getOrNull()
        }
    }
    DisposableEffect(drawable) {
        (drawable as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
        onDispose { (drawable as? AnimatedImageDrawable)?.stop() }
    }
    val d = drawable ?: return
    AndroidView(
        factory = { ctx -> ImageView(ctx).apply { contentDescription?.let { this.contentDescription = it } } },
        update = { v ->
            v.scaleType = if (fit) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            if (v.drawable !== d) v.setImageDrawable(d)
        },
        modifier = modifier,
    )
}

/** A small "GIF" label on animated images. */
@Composable
fun GifBadge(modifier: Modifier = Modifier) {
    Text(
        "GIF", color = Color.White, fontSize = 10.sp,
        modifier = modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** A Tenor or Giphy link found in a message. */
data class GifLink(val source: String, val url: String, val needsResolve: Boolean)

object GifLinks {
    private val TENOR_MEDIA = Regex("""^media\d*\.tenor\.com$|^c\.tenor\.com$""")
    private val GIPHY_MEDIA = Regex("""^media\d*\.giphy\.com$|^i\.giphy\.com$""")
    private val TENOR_IN_PAGE = Regex("""https://media\d*\.tenor\.com/[A-Za-z0-9_\-/.%]+?\.gif""")

    /** The message is just one Tenor / Giphy GIF link (nothing else). */
    fun parse(text: String): GifLink? {
        val t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() } || t.length > 400) return null
        val uri = runCatching { Uri.parse(t) }.getOrNull() ?: return null
        if (uri.scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty()
        val segs = uri.pathSegments.orEmpty()
        val isAnim = path.endsWith(".gif", true) || path.endsWith(".webp", true)
        return when {
            TENOR_MEDIA.matches(host) && isAnim -> GifLink("Tenor", t, false)
            GIPHY_MEDIA.matches(host) && isAnim -> GifLink("GIPHY", t, false)
            (host == "giphy.com" || host == "www.giphy.com") && segs.size >= 2 && (segs[0] == "gifs" || segs[0] == "stickers") -> {
                val id = segs[1].substringAfterLast('-')
                if (id.matches(Regex("[A-Za-z0-9]{6,40}"))) GifLink("GIPHY", "https://i.giphy.com/$id.gif", false) else null
            }
            (host == "tenor.com" || host == "www.tenor.com") && segs.contains("view") -> GifLink("Tenor", t, true)
            else -> null
        }
    }

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .proxySelector(app.amend.messenger.net.TorProxy).build()
    }

    private fun get(url: String, limit: Long): ByteArray? {
        http.newCall(Request.Builder().url(url).header("User-Agent", "amend").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body ?: return null
            if (body.contentLength() > limit) return null
            val src = body.byteStream()
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) return null
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    /** Downloads (once) the GIF behind a link. Only real GIF / WebP files from Tenor or Giphy are kept. */
    suspend fun fetch(context: Context, link: GifLink): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "gif-links").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-256").digest(link.url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(dir, "$name.gif")
        if (file.exists() && file.length() > 0) return@withContext file
        runCatching {
            val mediaUrl = if (!link.needsResolve) link.url else {
                val page = get(link.url, 3L * 1024 * 1024)?.toString(Charsets.UTF_8) ?: return@runCatching null
                TENOR_IN_PAGE.find(page)?.value ?: return@runCatching null
            }
            val host = Uri.parse(mediaUrl).host?.lowercase().orEmpty()
            if (!TENOR_MEDIA.matches(host) && !GIPHY_MEDIA.matches(host)) return@runCatching null
            val bytes = get(mediaUrl, Gifs.MAX_BYTES) ?: return@runCatching null
            if (!Gifs.isAnimatedMime(Gifs.sniff(bytes.copyOf(minOf(16, bytes.size))))) return@runCatching null
            val tmp = File(dir, "$name.part")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
            file
        }.getOrNull()
    }
}

/**
 * A message that is just a Tenor / Giphy link, shown as the GIF. Loading it connects to
 * Tenor or Giphy, so other people's GIF links load only when tapped.
 */
@Composable
fun GifLinkBubble(link: GifLink, autoLoad: Boolean, captionColor: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember(link) { mutableStateOf<File?>(null) }
    var loading by remember(link) { mutableStateOf(false) }
    var failed by remember(link) { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    val load: () -> Unit = {
        if (file == null && !loading) scope.launch {
            loading = true; failed = false
            file = GifLinks.fetch(context, link)
            loading = false
            failed = file == null
        }
    }
    LaunchedEffect(link, autoLoad) { if (autoLoad) load() }
    Box(
        Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1.3f).clip(RoundedCornerShape(16.dp))
            .background(Color(0x33000000)).clickable { if (file == null) load() else full = true },
        contentAlignment = Alignment.Center,
    ) {
        val f = file
        if (f != null) {
            AnimatedImage(f, "GIF from ${link.source}", fit = false, maxSide = 700, modifier = Modifier.fillMaxSize())
            GifBadge(Modifier.align(Alignment.BottomStart))
        } else if (loading) {
            CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(
                if (failed) "Couldn't load the GIF · tap to retry" else "🎞 GIF · ${link.source} — tap to load",
                color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(12.dp),
            )
        }
    }
    Text(link.source, color = captionColor.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    val f = file
    if (full && f != null) FullScreenPhoto(f, onClose = { full = false })
}
