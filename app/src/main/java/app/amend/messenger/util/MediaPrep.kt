package app.amend.messenger.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/**
 * Gets photos and videos ready to send: smaller, and without hidden data.
 *
 *  - Photos are decoded (applying their rotation), shrunk to at most 2048 px and saved
 *    again as JPEG, which drops all EXIF metadata — including GPS position.
 *  - Videos bigger than about 12 MB or taller than 1280 px are re-encoded to 720p H.264.
 *    Re-encoding also drops the original's location metadata.
 */
object MediaPrep {
    const val MAX_PHOTO_SIDE = 2048
    /** Largest file we upload (after compression). */
    const val MAX_VIDEO_BYTES = 64L * 1024 * 1024

    data class Photo(val jpeg: ByteArray, val width: Int, val height: Int)
    data class Video(val file: File, val durationMs: Long, val width: Int, val height: Int, val mime: String)

    suspend fun photo(context: Context, uri: Uri): Photo? {
        val bmp = AvatarImages.loadForCrop(context, uri, MAX_PHOTO_SIDE) ?: return null
        return encode(bmp)
    }

    suspend fun encode(bmp: Bitmap, quality: Int = 85): Photo = withContext(Dispatchers.Default) {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        Photo(out.toByteArray(), bmp.width, bmp.height)
    }

    private fun probe(context: Context, uri: Uri): Triple<Long, Int, Int> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            Triple(dur, w, h)
        } catch (_: Exception) {
            Triple(0L, 0, 0)
        } finally {
            runCatching { r.release() }
        }
    }

    private fun sizeOf(context: Context, uri: Uri): Long =
        runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L

    /** Ready-to-send copy of a gallery video, or null with [onError] told why. */
    suspend fun video(context: Context, uri: Uri, onError: (String) -> Unit = {}): Video? {
        val (dur, w, h) = withContext(Dispatchers.IO) { probe(context, uri) }
        val size = sizeOf(context, uri)
        val dir = File(context.cacheDir, "outgoing").apply { mkdirs() }
        val out = File(dir, "clip-${System.nanoTime()}.mp4")
        val needsShrink = size < 0 || size > 12L * 1024 * 1024 || maxOf(w, h) > 1280
        if (needsShrink) {
            val ok = transcode(context, uri, out, targetHeight = if (h > w) 1280 else 720)
            if (ok && out.length() in 1..MAX_VIDEO_BYTES) {
                val (d2, w2, h2) = withContext(Dispatchers.IO) { probe(context, Uri.fromFile(out)) }
                return Video(out, if (d2 > 0) d2 else dur, if (w2 > 0) w2 else w, if (h2 > 0) h2 else h, "video/mp4")
            }
            out.delete()
        }
        if (size in 1..MAX_VIDEO_BYTES) {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            if (out.length() > 0) {
                val mime = context.contentResolver.getType(uri)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
                return Video(out, dur, w, h, mime)
            }
        }
        onError(if (size > MAX_VIDEO_BYTES) "That video is too long to send. Try a shorter one." else "Couldn't read that video.")
        return null
    }

    /** Re-encodes to H.264 at 720p (1280 px tall for portrait), on the main thread as Media3 requires. */
    @OptIn(UnstableApi::class)
    private suspend fun transcode(context: Context, uri: Uri, out: File, targetHeight: Int): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resume(false)
                    }
                })
                .build()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(targetHeight))))
                .build()
            try {
                transformer.start(item, out.absolutePath)
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(false)
            }
            cont.invokeOnCancellation { runCatching { transformer.cancel() } }
        }
    }
}
