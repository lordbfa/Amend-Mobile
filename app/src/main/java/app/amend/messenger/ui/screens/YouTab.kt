package app.amend.messenger.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import app.amend.messenger.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import app.amend.messenger.net.Nip05
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
import app.amend.messenger.util.Notifications
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
fun YouTab(app: AmendApp, repo: AmendRepository, openSettings: () -> Unit = {}) {
    val profiles by repo.profiles.collectAsStateWithLifecycle()
    val me = profiles[repo.me]
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    val npub = repo.keys.npub

    var bigQr by remember { mutableStateOf(false) }
    Column(
        Modifier.verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // title + settings
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("You", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            androidx.compose.material3.IconButton(onClick = { haptics.tap(); openSettings() }) {
                androidx.compose.material3.Icon(Icons.Rounded.Settings, contentDescription = "Settings")
            }
        }

        // profile
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LeafAvatar(repo.me, me, 96.dp, modifier = Modifier.clickable { editing = true })
                Spacer(Modifier.height(12.dp))
                Text(me?.name?.ifBlank { null } ?: "Set your name", style = MaterialTheme.typography.headlineSmall)
                if (!me?.nip05.isNullOrBlank()) Nip05Badge(me!!.nip05, repo.me)
                if (!me?.about.isNullOrBlank()) {
                    Text(
                        me!!.about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { editing = true }, shape = RoundedCornerShape(16.dp)) { Text("Edit profile") }
            }
        }

        // amend ID: compact, tap the code for a big one
        GlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).clickable { haptics.tap(); bigQr = true }.padding(6.dp)) {
                    LeafQr("nostr:$npub", Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Your amend ID", style = MaterialTheme.typography.titleMedium)
                    Text(
                        npub.take(14) + "…" + npub.takeLast(6), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { clipboard.setText(AnnotatedString(npub)); haptics.success() }, shape = RoundedCornerShape(14.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text("Copy") }
                        OutlinedButton(onClick = {
                            haptics.tap()
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Chat with me privately on amend 🌱 — my ID: $npub")
                            }
                            context.startActivity(Intent.createChooser(send, "Share your amend ID"))
                        }, shape = RoundedCornerShape(14.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text("Share") }
                    }
                }
            }
        }
        InstantArchiveCard(repo)
        Text(
            "Friends scan your code or paste your ID to start a private chat.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(24.dp))
    }

    if (bigQr) {
        AlertDialog(
            onDismissRequest = { bigQr = false },
            confirmButton = { TextButton(onClick = { bigQr = false }) { Text("Done") } },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    LeafQr("nostr:$npub", Modifier.size(260.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(npub, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }

    if (editing) {
        var name by remember { mutableStateOf(me?.name ?: "") }
        var about by remember { mutableStateOf(me?.about ?: "") }
        var avatar by remember { mutableStateOf(me?.avatar ?: "") }
        var picture by remember { mutableStateOf(me?.picture ?: "") }
        var nip05 by remember { mutableStateOf(me?.nip05 ?: "") }
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
                    OutlinedTextField(
                        nip05, { nip05 = it.trim().lowercase().take(100) }, label = { Text("Name address (optional)") },
                        placeholder = { Text("you@getamend.app") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                        isError = nip05.isNotEmpty() && Nip05.parse(nip05) == null,
                    )
                    Text(
                        "A name@domain address friends can type instead of your ID. The domain must list your public key in /.well-known/nostr.json — amend shows ✓ only when it does.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { clipboard.setText(AnnotatedString(repo.me)); haptics.success() }) { Text("Copy public key (hex)") }
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
                    val addr = nip05.takeIf { it.isEmpty() || Nip05.parse(it) != null } ?: (me?.nip05 ?: "")
                    scope.launch { repo.updateProfile(name, about, avatar, me?.hue ?: -1f, picture, addr) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
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
internal fun RelayEditor(relays: List<String>, onChange: (List<String>) -> Unit) {
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
internal fun BackupCard(nsec: String) {
    var revealed by remember { mutableStateOf(false) }
    val blur by animateFloatAsState(if (revealed) 0f else 1f, tween(250), label = "reveal")
    val haptics = rememberHaptics()
    var copied by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // the secret key can be revealed here: never in a screenshot or screen recording
    app.amend.messenger.util.SecureScreen(always = true)
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
                    app.amend.messenger.util.SensitiveClipboard.copySecret(context, "amend backup key", nsec)
                    copied = true
                    haptics.success()
                }) { Text(if (copied) "Copied — store it somewhere safe and clear your clipboard" else "Copy backup key") }
            }
            TextButton(onClick = { haptics.tap(); linking = true }) { Text("📱 Link another phone") }
        }
    }
    if (linking) {
        var show by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { linking = false },
            confirmButton = { TextButton(onClick = { linking = false }) { Text("Done") } },
            // a dialog is its own window: protect the QR code (it holds the secret key) separately
            properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
            title = { Text("Link another phone") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "On the other phone install amend, choose \"I already have a key\" → \"Scan from my other phone\", then hold the box below. " +
                            "Both phones will share your identity and messages. Only do this with your own phones.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier.size(240.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (show) Color.White else MaterialTheme.colorScheme.surfaceVariant)
                            .pointerInput(Unit) {
                                detectTapGestures(onPress = { haptics.heavy(); show = true; tryAwaitRelease(); show = false })
                            }
                            .padding(10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (show) LeafQr(LINK_PREFIX + nsec, Modifier.fillMaxSize())
                        else Text("Hold to show the link code", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            },
        )
    }
}

/**
 * "made with 💚 by Ahmed Bhuiyan Faisal" — a tappable credit that opens the maker's
 * website (getamend.app). The heart beats (lub-dub) and the pill gently glows to invite a tap.
 */
@Composable
internal fun MadeWithLove(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val beat = androidx.compose.animation.core.rememberInfiniteTransition(label = "madeby")
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
    // a slow breathing glow + nudging arrow, so the credit reads as something to tap
    val glow by beat.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            tween(1700), androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "glow",
    )
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier.padding(top = 14.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(22.dp))
                .border(1.dp, primary.copy(alpha = 0.22f + 0.30f * glow), RoundedCornerShape(22.dp))
                .background(primary.copy(alpha = 0.05f + 0.06f * glow))
                .clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://getamend.app")))
                    }
                }
                .padding(horizontal = 16.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("made with ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("💚", fontSize = 14.sp, modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
            Text(" by Ahmed Bhuiyan Faisal", style = MaterialTheme.typography.bodySmall, color = primary)
            Text(
                " ↗",
                style = MaterialTheme.typography.bodySmall,
                color = primary,
                modifier = Modifier.graphicsLayer { translationX = 1.5f * glow; translationY = -1.5f * glow },
            )
        }
    }
}

/**
 * Notification settings: instant delivery (live connection), whether the
 * system lets amend notify at all, and the battery-optimisation exemption that
 * keeps the connection alive on aggressive phones.
 */
@Composable
internal fun NotificationsCard(app: AmendApp, repo: AmendRepository) {
    val eco by repo.eco.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // re-check permission/battery state whenever the user comes back from system settings
    var refresh by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val enabled = remember(refresh) { Notifications.areEnabled(context) }
    val power = context.getSystemService(android.os.PowerManager::class.java)
    val unrestricted = remember(refresh) { power?.isIgnoringBatteryOptimizations(context.packageName) == true }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Notifications 🔔", style = MaterialTheme.typography.titleMedium)
            if (!enabled) {
                Text(
                    "Notifications are turned off for amend, so new messages can't appear in your notification panel.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = {
                    haptics.tap()
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    context.startActivity(intent)
                }, shape = RoundedCornerShape(16.dp)) { Text("Turn on notifications") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Instant notifications", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (eco.instantNotifications)
                            "amend keeps one small connection open so messages arrive right away — no Google servers involved. A quiet \"listening\" notification shows while it runs; you can hide it in system settings."
                        else
                            "amend checks for messages every 15–60 minutes instead. Uses less battery and data, but notifications can be late.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                androidx.compose.material3.Switch(checked = eco.instantNotifications, onCheckedChange = {
                    haptics.tick()
                    repo.setInstantNotifications(it)
                    app.rescheduleBackground(repo)
                })
            }
            if (eco.instantNotifications && !unrestricted) {
                Text(
                    "Some phones stop background connections to save battery. Let amend run in the background so notifications keep coming.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = {
                    haptics.tap()
                    @android.annotation.SuppressLint("BatteryLife")
                    val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:" + context.packageName))
                    runCatching { context.startActivity(intent) }.onFailure {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }, shape = RoundedCornerShape(16.dp)) { Text("Allow background activity") }
            }
        }
    }
}

/** Appearance settings: colour theme + the sun-timed launcher icon. Layout never changes. */
@Composable
internal fun AppearancePage() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ThemeCard()
        IconStyleCard()
    }
}

/** A grid of colour themes (festivals included). Tapping one recolours the whole app at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeCard() {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val dark = app.amend.messenger.ui.theme.LocalDark.current
    val current = app.amend.messenger.ui.theme.AppThemes.current
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pick a colour mood — the layout stays the same. Perfect for festivals.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                app.amend.messenger.ui.theme.AppThemes.all.forEach { t ->
                    val accent = if (dark) t.accentDark else t.accentLight
                    val selected = t.id == current.id
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(62.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { haptics.tick(); app.amend.messenger.ui.theme.AppThemes.select(context, t.id) }
                            .padding(vertical = 4.dp),
                    ) {
                        Box(
                            Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(accent)
                                .border(
                                    if (selected) 3.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) { Text(t.emoji, fontSize = 20.sp) }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            t.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The launcher icon changes with the sun: white by day, grey at dusk, dark at night. */
@Composable
internal fun IconStyleCard() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(app.amend.messenger.util.DynamicIcon.isEnabled(context)) }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Icon follows the sun ☀️🌙", style = MaterialTheme.typography.titleMedium)
                Text(
                    "amend's icon is white by day, turns light grey before sunset, grey at dusk and dark at night — timed to sunrise and sunset where you are. " +
                        "Some launchers briefly refresh the home-screen icon when it changes.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            androidx.compose.material3.Switch(checked = on, onCheckedChange = {
                on = it
                app.amend.messenger.util.DynamicIcon.setEnabled(context, it)
            })
        }
    }
}

@Composable
internal fun ReadReceiptsCard(repo: AmendRepository) {
    val eco by repo.eco.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Read receipts", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Your little message tree turns 🌱 green when sent, 🌳 deep green when it reaches your friend's phone, and 🌸 blue with blossoms when they've seen it. " +
                            if (eco.readReceipts) "Friends also see when you've read theirs." else "Off: friends won't see when you read their messages.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                androidx.compose.material3.Switch(checked = eco.readReceipts, onCheckedChange = { haptics.tick(); repo.setReadReceipts(it) })
            }
        }
    }
}



/** "✓ name@domain" when the domain vouches for [pubkey], otherwise the address greyed out. */
@Composable
fun Nip05Badge(address: String, pubkey: String, modifier: Modifier = Modifier) {
    var verified by remember(address, pubkey) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(address, pubkey) { verified = Nip05.verify(address, pubkey) }
    val shown = Nip05.display(address)
    Text(
        when (verified) { true -> "✓ $shown"; false -> "$shown · not verified"; null -> shown },
        style = MaterialTheme.typography.labelLarge,
        color = if (verified == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 2.dp),
    )
}
