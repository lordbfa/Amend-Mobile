package app.amend.messenger.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import app.amend.messenger.ui.Text
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.amend.messenger.core.crypto.Nip19
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.util.AvatarImages
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** Decodes QR codes with ZXing — open source, no Google Play Services needed. */
private object QrDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    fun decode(source: LuminanceSource): String? {
        val reader = MultiFormatReader().apply { setHints(hints) }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (e: Exception) {
            // also try the inverted image (light code on dark background)
            try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
            } catch (e2: Exception) {
                null
            }
        }
    }

    /** Uses only the brightness (Y) plane of a camera frame. */
    fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val data = ByteArray(rowStride * image.height)
        buffer.rewind()
        buffer.get(data, 0, minOf(buffer.remaining(), data.size))
        return decode(PlanarYUVLuminanceSource(data, rowStride, image.height, 0, 0, image.width, image.height, false))
    }

    fun decode(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return decode(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
    }
}

/** QR payload for "Link another phone": the secret key, marked so it's never mistaken for an ID. */
const val LINK_PREFIX = "amend-link:"

/**
 * Full-screen QR scanner for amend IDs. Calls [onFound] with the hex public key
 * once a valid npub is seen. [myKey] is used to say "that's you".
 */
@Composable
fun QrScannerScreen(myKey: String, onFound: (String) -> Unit, onClose: () -> Unit, linkMode: Boolean = false) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var hint by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var done by remember { mutableStateOf(false) }

    BackHandler { onClose() }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }
    LaunchedEffect(Unit) { if (!hasPermission) permission.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(hint) { if (hint != null) { delay(2200); hint = null } }

    /** Handles any decoded text; returns true when it was a usable amend ID. */
    fun handle(text: String): Boolean {
        if (done) return true
        if (linkMode) {
            val nsec = text.trim().removePrefix(LINK_PREFIX)
            if (Nip19.parseSecretKey(nsec) == null) { hint = "That isn't an amend link code"; return false }
            done = true
            haptics.success()
            onFound(nsec)
            return true
        }
        val key = Nip19.parsePublicKey(text)
        return when {
            key == null -> { hint = "That QR code isn't an amend ID"; false }
            key == myKey -> { hint = "That's your own ID 🙂"; false }
            else -> {
                done = true
                haptics.success()
                onFound(key)
                true
            }
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bitmap = AvatarImages.loadForCrop(context, uri, maxSide = 1600)
            val text = bitmap?.let { b -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { QrDecoder.decode(b) } }
            if (text == null) { haptics.reject(); hint = "No QR code found in that picture" } else if (!handle(text)) haptics.reject()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
            DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        var lastHintAt = 0L
                        analysis.setAnalyzer(analysisExecutor) { image ->
                            val text = try { QrDecoder.decode(image) } finally { image.close() }
                            if (text != null) {
                                ContextCompat.getMainExecutor(ctx).execute {
                                    val now = System.currentTimeMillis()
                                    if (!handle(text) && now - lastHintAt > 2500) {
                                        lastHintAt = now
                                        haptics.reject()
                                    }
                                }
                            }
                        }
                        try {
                            provider.unbindAll()
                            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        } catch (e: Exception) {
                            hint = "Couldn't start the camera"
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
            )
            ScanOverlay()
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("📷", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "amend needs the camera to scan a friend's QR code. Nothing is recorded or uploaded.",
                    color = Color.White, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, shape = RoundedCornerShape(18.dp)) { Text("Allow camera") }
            }
        }

        // top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
            Text("Scan an amend ID", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (hasPermission && camera?.cameraInfo?.hasFlashUnit() == true) {
                IconButton(onClick = {
                    torch = !torch
                    haptics.tick()
                    camera?.cameraControl?.enableTorch(torch)
                }) {
                    Icon(if (torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, contentDescription = "Flashlight", tint = Color.White)
                }
            }
        }

        // bottom: hint + gallery
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(hint ?: "Point at the QR code in your friend's You tab", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    haptics.tap()
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF), contentColor = Color.White),
            ) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text("Scan from a picture")
            }
        }
    }
}

/** Dimmed frame with leafy-green corners and a sweeping scan line. */
@Composable
private fun ScanOverlay() {
    val t = rememberInfiniteTransition(label = "scan")
    val line by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), label = "line")
    val pulse by t.animateFloat(0.96f, 1.02f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "pulse")
    Canvas(Modifier.fillMaxSize()) {
        val side = minOf(size.width, size.height) * 0.68f * pulse
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f - 40.dp.toPx()
        val radius = 28.dp.toPx()
        val hole = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
            addRoundRect(androidx.compose.ui.geometry.RoundRect(left, top, left + side, top + side, CornerRadius(radius, radius)))
        }
        drawPath(hole, Color.Black.copy(alpha = 0.55f))
        // corners
        val c = Color(0xFF5BE39B)
        val len = side * 0.16f
        val w = 5.dp.toPx()
        listOf(
            Triple(Offset(left, top), 1f, 1f), Triple(Offset(left + side, top), -1f, 1f),
            Triple(Offset(left, top + side), 1f, -1f), Triple(Offset(left + side, top + side), -1f, -1f),
        ).forEach { (p, dx, dy) ->
            drawLine(c, p, Offset(p.x + dx * len, p.y), w, StrokeCap.Round)
            drawLine(c, p, Offset(p.x, p.y + dy * len), w, StrokeCap.Round)
        }
        // scan line with glow
        val y = top + side * (0.08f + 0.84f * line)
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, c.copy(alpha = 0.35f), Color.Transparent), startY = y - 24.dp.toPx(), endY = y + 24.dp.toPx()),
            topLeft = Offset(left + 12.dp.toPx(), y - 24.dp.toPx()),
            size = Size(side - 24.dp.toPx(), 48.dp.toPx()),
        )
        drawLine(c, Offset(left + 16.dp.toPx(), y), Offset(left + side - 16.dp.toPx(), y), 2.5.dp.toPx(), StrokeCap.Round)
        drawRoundRect(c.copy(alpha = 0.25f), Offset(left, top), Size(side, side), CornerRadius(radius, radius), style = Stroke(1.5.dp.toPx()))
    }
}
