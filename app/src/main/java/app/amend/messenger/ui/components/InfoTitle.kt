package app.amend.messenger.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amend.messenger.ui.Text
import app.amend.messenger.ui.tr

/**
 * A heading with a small ⓘ button next to it. The explanation that used to sit under the
 * heading opens in a dialog instead, so the screen stays clean.
 */
@Composable
fun InfoTitle(
    title: String,
    info: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    fontWeight: FontWeight? = null,
) {
    var open by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = style, fontWeight = fontWeight, modifier = Modifier.weight(1f, fill = false))
        if (info.isNotBlank()) {
            IconButton(onClick = { haptics.tap(); open = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Info, contentDescription = tr("More about this"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { Text(info, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { open = false }) { Text("OK") } },
        )
    }
}
