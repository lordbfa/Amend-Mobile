package app.amend.messenger.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import app.amend.messenger.core.crypto.Hashes
import app.amend.messenger.core.crypto.toHex
import app.amend.messenger.data.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Profile photos.
 *
 * amend stores your photo as a tiny 128×128 WebP inside your public profile
 * (≈5–10 KB), so it travels through the same relays as everything else — no
 * separate picture server, and very little data. Photos set in other Nostr apps
 * are https links; those are downloaded once and cached.
 */
object AvatarImages {
    const val SIZE_PX = 128
    private const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024

    // up to 48 MB, but never more than an eighth of what this phone gives the app
    private val memory = object : LruCache<String, ImageBitmap>(minOf(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).proxySelector(app.amend.messenger.net.TorProxy).build()
    }

    private fun key(picture: String) = Hashes.sha256(picture.toByteArray()).toHex()

    /**
     * Whose https profile photos may be fetched. Downloading a link tells its server your IP
     * address and that you looked, so it's only done for you and people you've accepted;
     * strangers (message requests) show their leaf avatar. Set by AmendApp.
     */
    @Volatile var mayFetch: (pubkey: String) -> Boolean = { false }

    /** Images with a side longer than this are refused before decoding. */
    private const val MAX_SIDE = 16_384

    /**
     * Decodes untrusted image bytes to at most [maxSide] px on the longer side. Reads the size
     * first: a tiny PNG can claim 20 000 × 20 000 px, and decoding that in full needs over a
     * gigabyte — an OutOfMemoryError (not an Exception) that would crash the app.
     */
    fun decodeBounded(bytes: ByteArray, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || maxOf(bounds.outWidth, bounds.outHeight) > MAX_SIDE) null
        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds, maxSide) })
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun sampleFor(bounds: BitmapFactory.Options, maxSide: Int): Int {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        return sample
    }

    // ------------------------------------------------------------- encode

    /** Loads a picked image, scaled down so even huge camera photos stay light in memory. */
    suspend fun loadForCrop(context: Context, uri: Uri, maxSide: Int = 1600): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                // ImageDecoder also applies the photo's EXIF rotation.
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = maxSide.toFloat() / maxOf(w, h)
                    if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Cuts [crop] (in [source] pixels) out of the image and encodes it as a small data URI. */
    suspend fun encode(source: Bitmap, crop: Rect): String = withContext(Dispatchers.Default) {
        val out = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(source, crop, Rect(0, 0, SIZE_PX, SIZE_PX), paint)
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        var quality = 80
        var encoded: String
        do {
            val bytes = ByteArrayOutputStream().also { out.compress(format, quality, it) }.toByteArray()
            encoded = "data:image/webp;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            quality -= 12
        } while (encoded.length > 16_000 && quality > 20)
        out.recycle()
        memory.put(key(encoded), decodeDataUri(encoded) ?: return@withContext encoded)
        encoded
    }

    // ------------------------------------------------------------- decode

    private fun decodeDataUri(picture: String): ImageBitmap? = try {
        val bytes = Base64.decode(picture.substringAfter(";base64,"), Base64.DEFAULT)
        decodeBounded(bytes, 512)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }

    private fun decodeFileScaled(file: File, target: Int = 256): ImageBitmap? =
        runCatching { decodeBounded(file.readBytes(), target)?.asImageBitmap() }.getOrNull()

    /**
     * Returns the picture as an image, from memory, disk cache, inline data or the web.
     * [pubkey] is whose photo it is: https photos are only fetched when [mayFetch] allows it.
     */
    suspend fun load(context: Context, picture: String, pubkey: String? = null): ImageBitmap? {
        if (!Profile.isValidPicture(picture)) return null
        val k = key(picture)
        memory.get(k)?.let { return it }
        val image = withContext(Dispatchers.IO) {
            if (picture.startsWith(Profile.DATA_PREFIX)) return@withContext decodeDataUri(picture)
            val dir = File(context.cacheDir, "avatars").apply { mkdirs() }
            val file = File(dir, k)
            if (!file.exists()) {
                if (pubkey == null || !mayFetch(pubkey)) return@withContext null
                try {
                    http.newCall(Request.Builder().url(picture).build()).execute().use { resp ->
                        val body = resp.body ?: return@withContext null
                        if (!resp.isSuccessful || body.contentLength() > MAX_DOWNLOAD_BYTES) return@withContext null
                        val bytes = body.byteStream().use { input ->
                            val buf = ByteArrayOutputStream()
                            val chunk = ByteArray(16 * 1024)
                            while (true) {
                                val n = input.read(chunk)
                                if (n < 0) break
                                buf.write(chunk, 0, n)
                                if (buf.size() > MAX_DOWNLOAD_BYTES) return@withContext null
                            }
                            buf.toByteArray()
                        }
                        file.writeBytes(bytes)
                    }
                } catch (e: Exception) {
                    return@withContext null
                }
            }
            decodeFileScaled(file)
        }
        image?.let { memory.put(k, it) }
        return image
    }
}

/** The decoded avatar for [picture] (the photo of [pubkey]), or null while loading / if there is none. */
@Composable
fun rememberAvatar(picture: String?, pubkey: String? = null): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, picture, pubkey) {
        value = if (picture.isNullOrBlank()) null else AvatarImages.load(context, picture, pubkey)
    }
}
