package app.amend.messenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.core.crypto.SafetyCode
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.ui.Text
import app.amend.messenger.ui.components.GlassCard
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.util.Verified

/**
 * Check that the person you're chatting with really is who they say: compare the security
 * code, or scan the QR code on their phone (amend → You). If someone swapped keys along the
 * way, the codes won't match and the QR belongs to a different account.
 */
@Composable
fun VerifyScreen(repo: AmendRepository, peer: String, onClose: () -> Unit) {
    val haptics = rememberHaptics()
    val code = remember(peer) { SafetyCode.of(repo.me, peer) }
    val set by Verified.hashes.collectAsStateWithLifecycle()
    val verified = Verified.isVerified(peer, set)
    var scanning by remember { mutableStateOf(false) }
    /** null: not scanned yet; true: the QR was this contact; false: someone else's. */
    var scanResult by remember { mutableStateOf<Boolean?>(null) }
    val name = repo.displayName(peer)

    FullScreenDialog(onDismissRequest = { if (scanning) scanning = false else onClose() }) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(
                Modifier.fillMaxSize().safeTop().safeBottom().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    Text("Verify security code", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Compare this code with $name in person or on a call. If both phones show the same numbers, nobody is pretending to be either of you and nobody can read your messages in between.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 20.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        code.split(" ").chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                row.forEach { g ->
                                    androidx.compose.material3.Text(g, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
                StatusCard(verified, scanResult)
                Button(
                    onClick = { scanResult = null; scanning = true },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan their QR code")
                }
                Text(
                    "Their QR code is in amend under You. Scanning it is the quickest way to check.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { haptics.success(); Verified.set(peer, !verified); scanResult = null },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                ) { Text(if (verified) "Clear verification" else "The codes match: mark as verified") }
                Spacer(Modifier.height(16.dp))
            }
            if (scanning) {
                QrScannerScreen(
                    myKey = repo.me,
                    onFound = { key ->
                        scanning = false
                        val match = key == peer
                        scanResult = match
                        if (match) { Verified.set(peer, true); haptics.success() } else haptics.tap()
                    },
                    onClose = { scanning = false },
                )
            }
        }
    }
}

@Composable
private fun StatusCard(verified: Boolean, scanResult: Boolean?) {
    val (icon, color, text) = when {
        scanResult == false -> Triple(
            Icons.Rounded.GppMaybe, MaterialTheme.colorScheme.error,
            "That QR code belongs to a different account. The person in this chat may not be who they say they are. Don't share anything private until you've checked again.",
        )
        verified -> Triple(Icons.Rounded.Verified, Color(0xFF2BB673), "Verified ✓ You checked this contact's security code.")
        else -> Triple(Icons.Rounded.GppMaybe, MaterialTheme.colorScheme.onSurfaceVariant, "Not verified yet.")
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}
