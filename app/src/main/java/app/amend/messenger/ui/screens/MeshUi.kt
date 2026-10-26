package app.amend.messenger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.ui.components.GlassCard
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.util.Mesh

/** Settings card: the offline mesh, on or off, and what it's doing. */
@Composable
fun MeshCard() {
    val context = LocalContext.current
    val ui by Mesh.ui.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) Mesh.setEnabled(context, true)
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Radar(ui.running, ui.peers)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Offline mesh 📡", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !ui.enabled -> "Off"
                            !ui.running -> ui.error ?: "Starting…"
                            ui.peers == 0 -> "Looking for amend phones nearby…"
                            else -> "Connected to ${ui.peers} phone" + if (ui.peers == 1) "" else "s"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ui.enabled && ui.running) Color(0xFF2BB673) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = ui.enabled, onCheckedChange = { on ->
                    haptics.tick()
                    if (!on) Mesh.setEnabled(context, false)
                    else if (Mesh.hasPermissions(context)) Mesh.setEnabled(context, true)
                    else launcher.launch(Mesh.permissions())
                })
            }
            Text(
                "When there's no internet — floods, cyclones, shutdowns — nearby amend phones pass messages and SOS alerts to each other over Bluetooth and Wi-Fi Direct, " +
                    "hop by hop, until they reach your friend or a phone that's online, which uploads them. Messages stay end-to-end encrypted: phones that carry them can't read them, " +
                    "and your amend ID is never broadcast. Uses some extra battery while on.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Stat("📦", "${ui.holding}", "carrying")
                    Stat("📥", "${ui.delivered}", "arrived via mesh")
                    Stat("☁️", "${ui.bridged}", "uploaded for others")
                }
            }
        }
    }
}

@Composable
private fun Stat(emoji: String, value: String, label: String) {
    Column {
        Text("$emoji $value", style = MaterialTheme.typography.labelLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A small radar: sweeping rings while searching, a dot per connected phone. */
@Composable
private fun Radar(running: Boolean, peers: Int) {
    val t = rememberInfiniteTransition(label = "radar")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "p")
    Box(Modifier.size(44.dp)) {
        Canvas(Modifier.size(44.dp)) {
            val c = Color(0xFF2BB673)
            drawCircle(c.copy(alpha = 0.15f))
            if (running) {
                repeat(2) { i ->
                    val q = (p + i * 0.5f) % 1f
                    drawCircle(c.copy(alpha = (1 - q) * 0.7f), radius = size.minDimension / 2 * q, style = Stroke(2.dp.toPx()))
                }
                for (i in 0 until peers.coerceAtMost(6)) {
                    val a = i * 1.047f + 0.4f
                    val r = size.minDimension * 0.32f
                    drawCircle(c, 3.dp.toPx(), center.copy(x = center.x + kotlin.math.cos(a) * r, y = center.y + kotlin.math.sin(a) * r))
                }
            }
            drawCircle(c, 4.dp.toPx())
        }
    }
}
