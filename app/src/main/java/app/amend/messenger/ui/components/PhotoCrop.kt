package app.amend.messenger.ui.components

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.amend.messenger.util.AvatarImages
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full-screen-ish dialog to position a photo inside a circle: drag to move,
 * pinch to zoom. Returns a small data-URI photo via [onDone].
 */
@Composable
fun PhotoCropDialog(uri: Uri, onDone: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(uri) {
        val b = AvatarImages.loadForCrop(context, uri)
        if (b == null) failed = true else bitmap = b
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Position your photo", style = MaterialTheme.typography.titleLarge)
            Text(
                "Drag to move · pinch to zoom",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val b = bitmap
                when {
                    failed -> Text("Couldn't open that image", color = Color.White)
                    b == null -> CircularProgressIndicator()
                    else -> {
                        val image = remember(b) { b.asImageBitmap() }
                        Canvas(
                            Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(b) {
                                detectTransformGestures { _, pan, gestureZoom, _ ->
                                    val s = size.width.toFloat()
                                    val base = max(s / b.width, s / b.height)
                                    val newZoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                                    val dispW = b.width * base * newZoom
                                    val dispH = b.height * base * newZoom
                                    val maxX = (dispW - s) / 2f
                                    val maxY = (dispH - s) / 2f
                                    val next = offset + pan
                                    offset = Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
                                    if (newZoom != zoom && (newZoom == 1f || newZoom == 6f)) haptics.tick()
                                    zoom = newZoom
                                }
                            }
                        ) {
                            val s = size.width
                            boxSize = s
                            val base = max(s / b.width, s / b.height)
                            val dispW = b.width * base * zoom
                            val dispH = b.height * base * zoom
                            val left = s / 2f + offset.x - dispW / 2f
                            val top = s / 2f + offset.y - dispH / 2f
                            drawImage(
                                image,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(b.width, b.height),
                                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                                dstSize = IntSize(dispW.roundToInt(), dispH.roundToInt()),
                            )
                            // dim everything outside the circle
                            val scrim = Path().apply {
                                fillType = PathFillType.EvenOdd
                                addRect(androidx.compose.ui.geometry.Rect(0f, 0f, s, s))
                                addOval(androidx.compose.ui.geometry.Rect(0f, 0f, s, s))
                            }
                            drawPath(scrim, Color.Black.copy(alpha = 0.55f))
                            drawCircle(Color(0xFF5BE39B), s / 2f - 2.dp.toPx(), Offset(s / 2, s / 2), style = Stroke(3.dp.toPx()))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = bitmap != null && !saving,
                    shape = RoundedCornerShape(16.dp),
                    onClick = {
                        val b = bitmap ?: return@Button
                        val s = boxSize.takeIf { it > 0f } ?: return@Button
                        saving = true
                        scope.launch {
                            val scale = max(s / b.width, s / b.height) * zoom // screen px per bitmap px
                            val dispW = b.width * scale
                            val dispH = b.height * scale
                            val left = s / 2f + offset.x - dispW / 2f
                            val top = s / 2f + offset.y - dispH / 2f
                            val crop = Rect(
                                ((0f - left) / scale).roundToInt().coerceIn(0, b.width - 1),
                                ((0f - top) / scale).roundToInt().coerceIn(0, b.height - 1),
                                ((s - left) / scale).roundToInt().coerceIn(1, b.width),
                                ((s - top) / scale).roundToInt().coerceIn(1, b.height),
                            )
                            val encoded = AvatarImages.encode(b, crop)
                            haptics.success()
                            onDone(encoded)
                        }
                    },
                ) { Text(if (saving) "Saving…" else "Use photo") }
            }
        }
    }
}


/**
 * Large avatar with a camera badge. Tap to pick a photo (system photo picker,
 * no storage permission needed), position it, and get the result in [onPicture].
 * [picture] "" means no photo.
 */
@Composable
fun PhotoPickerAvatar(
    pubkey: String,
    profile: app.amend.messenger.data.Profile?,
    picture: String,
    onPicture: (String) -> Unit,
    size: androidx.compose.ui.unit.Dp = 96.dp,
) {
    val haptics = rememberHaptics()
    var cropUri by remember { mutableStateOf<Uri?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) cropUri = uri }
    val preview = (profile ?: app.amend.messenger.data.Profile(pubkey)).copy(picture = picture)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable {
                haptics.tap()
                picker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            }
        ) {
            LeafAvatar(pubkey, preview, size)
            Box(
                Modifier.align(Alignment.BottomEnd).padding(2.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(0xFF2BB673)).padding(6.dp)
            ) { Text("📷") }
        }
        if (picture.isNotEmpty()) {
            TextButton(onClick = { haptics.tick(); onPicture("") }) { Text("Remove photo") }
        } else {
            Text(
                "Tap to add a photo",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    cropUri?.let { uri ->
        PhotoCropDialog(uri, onDone = { onPicture(it); cropUri = null }, onCancel = { cropUri = null })
    }
}
