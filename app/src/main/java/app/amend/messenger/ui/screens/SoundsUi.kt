package app.amend.messenger.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amend.messenger.ui.Text
import app.amend.messenger.ui.components.GlassCard
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.util.SoundKind
import app.amend.messenger.util.SoundOption
import app.amend.messenger.util.Sounds

/** Settings → Sounds: pick the sound for messages, calls and other notifications. Tapping one plays it. */
@Composable
fun SoundsPage() {
    DisposableEffect(Unit) { onDispose { Sounds.stopPreview() } }
    SoundPicker(SoundKind.MESSAGE, "Messages", "When a friend writes to you.")
    SoundPicker(SoundKind.CALL, "Calls", "Rings while someone calls you on amend.")
    SoundPicker(SoundKind.OTHER, "Other notifications", "Your forest growing and walking goals.")
    Text(
        "amend's sounds are made for amend and are part of the app. SOS alerts always use your phone's loud alarm.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun SoundPicker(kind: SoundKind, title: String, subtitle: String) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var selected by remember { mutableStateOf(Sounds.choice(context, kind)) }
    val options = Sounds.options.getValue(kind)
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            options.forEachIndexed { i, option ->
                SoundRow(option, selected == option.id) {
                    haptics.tick()
                    if (selected != option.id) {
                        selected = option.id
                        Sounds.set(context, kind, option.id)
                    }
                    Sounds.preview(context, kind, option)
                }
                if (i < options.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun SoundRow(option: SoundOption, chosen: Boolean, onClick: () -> Unit) {
    val leaf = Color(0xFF2BB673)
    val bg by animateColorAsState(if (chosen) leaf else leaf.copy(alpha = 0.12f), label = "soundBg")
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Icon(
                if (chosen) Icons.Rounded.Check else Icons.Rounded.PlayArrow,
                contentDescription = null, tint = if (chosen) Color.White else leaf, modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(option.label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (chosen) FontWeight.SemiBold else null)
            if (option.raw != null) {
                Text("amend sound", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
