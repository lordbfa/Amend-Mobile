package app.amend.messenger.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.AmendApp
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.ui.components.GlassCard
import app.amend.messenger.ui.components.LeafAvatar
import app.amend.messenger.ui.components.PhotoPickerAvatar
import app.amend.messenger.ui.components.rememberHaptics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch

private val AVATAR_CHOICES = listOf("🌿", "🍃", "🌸", "🌻", "🌵", "🍄", "🌾", "🌺", "🍀", "🌼", "🌳", "🪴", "🐝", "🦋", "🐢", "🦊")

@Composable
fun YouTab(app: AmendApp, repo: AmendRepository) {
    val profiles by repo.profiles.collectAsStateWithLifecycle()
    val relays by repo.relayUrls.collectAsStateWithLifecycle()
    val me = profiles[repo.me]
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val npub = repo.keys.npub

    Column(Modifier.verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LeafAvatar(repo.me, me, 76.dp, modifier = Modifier.clickable { editing = true })
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(me?.name?.ifBlank { null } ?: "Set your name", style = MaterialTheme.typography.headlineSmall)
                if (!me?.about.isNullOrBlank()) Text(me!!.about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { editing = true }) { Text("Edit profile") }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Your amend ID", style = MaterialTheme.typography.titleMedium)
                Text("Friends scan or paste this to message you", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                LeafQr("nostr:$npub", Modifier.size(220.dp))
                Spacer(Modifier.height(12.dp))
                Text(npub, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { clipboard.setText(AnnotatedString(npub)); haptics.success() }, shape = RoundedCornerShape(16.dp)) { Text("Copy") }
                    OutlinedButton(onClick = {
                        haptics.tap()
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Chat with me privately on amend 🌱 — my ID: $npub")
                        }
                        context.startActivity(Intent.createChooser(send, "Share your amend ID"))
                    }, shape = RoundedCornerShape(16.dp)) { Text("Share") }
                }
            }
        }

        RelayEditor(relays) { repo.setRelays(it) }

        BackupCard(repo.keys.nsec)

        OutlinedButton(
            onClick = { confirmSignOut = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Remove identity from this phone") }

        Text(
            "amend is open source and built on the Nostr protocol. Your key works in any Nostr app.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MadeWithLove(Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }

    if (editing) {
        var name by remember { mutableStateOf(me?.name ?: "") }
        var about by remember { mutableStateOf(me?.about ?: "") }
        var avatar by remember { mutableStateOf(me?.avatar ?: "") }
        var picture by remember { mutableStateOf(me?.picture ?: "") }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Your profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PhotoPickerAvatar(repo.me, me?.copy(avatar = avatar), picture, onPicture = { picture = it })
                    }
                    Text(
                        "Your photo is part of your public profile — anyone with your amend ID can see it. It's shrunk to a tiny image to save data.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(about, { about = it.take(140) }, label = { Text("About") }, shape = RoundedCornerShape(14.dp))
                    Text(if (picture.isEmpty()) "Avatar" else "Avatar (shown when there's no photo)", style = MaterialTheme.typography.labelLarge)
                    AVATAR_CHOICES.chunked(8).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { e ->
                                Box(
                                    Modifier.size(32.dp).clip(RoundedCornerShape(10.dp))
                                        .background(if (avatar == e) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                        .clickable { haptics.tick(); avatar = e },
                                    contentAlignment = Alignment.Center,
                                ) { Text(e, fontSize = 18.sp) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    haptics.success()
                    scope.launch { repo.updateProfile(name, about, avatar, me?.hue ?: -1f, picture) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Remove your identity?") },
            text = { Text("Your key and all messages will be deleted from this phone. Without your backup key you can't get this identity back.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; app.signOut() }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

/** QR code drawn with rounded "leaf" modules instead of squares. */
@Composable
fun LeafQr(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1))
    }
    val fg = Color(0xFF0B2A1C)
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(10.dp)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val n = matrix.width
            val cell = size.width / n
            fun finder(x: Int, y: Int) = (x < 8 && y < 8) || (x >= n - 8 && y < 8) || (x < 8 && y >= n - 8)
            for (y in 0 until n) for (x in 0 until n) {
                if (!matrix.get(x, y) || finder(x, y)) continue
                drawRoundRect(
                    Color.hsl(140f + (x + y) * 1.2f % 40f, 0.55f, 0.32f),
                    topLeft = Offset(x * cell + cell * 0.08f, y * cell + cell * 0.08f),
                    size = Size(cell * 0.84f, cell * 0.84f),
                    cornerRadius = CornerRadius(cell * 0.42f, cell * 0.42f),
                )
            }
            // finder patterns drawn as soft rounded squares
            listOf(Offset(1f, 1f), Offset(n - 8f, 1f), Offset(1f, n - 8f)).forEach { o ->
                drawRoundRect(fg, Offset(o.x * cell, o.y * cell), Size(cell * 7, cell * 7), CornerRadius(cell * 2, cell * 2))
                drawRoundRect(Color.White, Offset((o.x + 1) * cell, (o.y + 1) * cell), Size(cell * 5, cell * 5), CornerRadius(cell * 1.4f, cell * 1.4f))
                drawRoundRect(Color(0xFF2BB673), Offset((o.x + 2) * cell, (o.y + 2) * cell), Size(cell * 3, cell * 3), CornerRadius(cell, cell))
            }
        }
    }
}

@Composable
private fun RelayEditor(relays: List<String>, onChange: (List<String>) -> Unit) {
    var adding by remember { mutableStateOf("") }
    val haptics = rememberHaptics()
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Relays 🍄", style = MaterialTheme.typography.titleMedium)
            Text(
                "Independent servers that pass on your encrypted messages. Use several so no single one matters. You can run your own.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            relays.forEach { url ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(url.removePrefix("wss://"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { haptics.tick(); onChange(relays - url) }, enabled = relays.size > 1) { Text("Remove") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(adding, { adding = it.trim() }, Modifier.weight(1f), label = { Text("wss://relay…") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (adding.contains('.')) { haptics.success(); onChange(relays + adding); adding = "" } else haptics.reject()
                }, shape = RoundedCornerShape(14.dp)) { Text("Add") }
            }
        }
    }
}

/** Secret key backup, revealed only while the user holds their finger on it. */
@Composable
private fun BackupCard(nsec: String) {
    var revealed by remember { mutableStateOf(false) }
    val blur by animateFloatAsState(if (revealed) 0f else 1f, tween(250), label = "reveal")
    val clipboard = LocalClipboardManager.current
    val haptics = rememberHaptics()
    var copied by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Backup key 🔑", style = MaterialTheme.typography.titleMedium)
            Text(
                "This is the only way to restore your identity. Anyone with it can read your messages — never share it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            haptics.heavy()
                            revealed = true
                            tryAwaitRelease()
                            revealed = false
                        })
                    }
                    .padding(14.dp),
            ) {
                Text(
                    if (blur < 0.5f) nsec else "•".repeat(32) + "   hold to reveal",
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                )
            }
            AnimatedVisibility(true) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(nsec))
                    copied = true
                    haptics.success()
                }) { Text(if (copied) "Copied — store it somewhere safe and clear your clipboard" else "Copy backup key") }
            }
        }
    }
}

/** "With 💚, Ahmed Bhuiyan Faisal" — the heart beats like a real heartbeat (lub-dub, pause). */
@Composable
private fun MadeWithLove(modifier: Modifier = Modifier) {
    val beat = androidx.compose.animation.core.rememberInfiniteTransition(label = "heartbeat")
    val scale by beat.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.keyframes {
                durationMillis = 1100
                1f at 0
                1.32f at 110   // lub
                1f at 240
                1.22f at 340   // dub
                1f at 500
                1f at 1100     // rest
            }
        ),
        label = "heartScale",
    )
    Row(modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("With ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("💚", fontSize = 14.sp, modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
        Text(", Ahmed Bhuiyan Faisal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
