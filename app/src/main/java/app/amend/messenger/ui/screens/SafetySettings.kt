package app.amend.messenger.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.EmergencyNumbers
import app.amend.messenger.ui.components.GlassCard
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.util.SosManager
import app.amend.messenger.util.SosPrefs

/**
 * "Safety · SOS" settings: readiness checklist (permissions), emergency number,
 * triggers, who receives the SOS, and a practice run.
 */
@Composable
fun SafetyCard(repo: AmendRepository) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val prefs = remember { SosPrefs(context) }
    val eco by repo.eco.collectAsStateWithLifecycle()
    val conversations by repo.conversations.collectAsStateWithLifecycle()

    // Re-check permissions whenever the user returns from a system screen
    val lifecycle = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val hasLocation = remember(refresh) { SosManager.hasLocationPermission(context) }
    val hasBackground = remember(refresh) { Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    var enabled by remember { mutableStateOf(prefs.enabled) }
    var volume by remember { mutableStateOf(prefs.volumeTrigger) }
    var power by remember { mutableStateOf(prefs.powerTrigger) }
    var autoDial by remember { mutableStateOf(prefs.autoDial) }
    var number by remember { mutableStateOf(prefs.customNumber) }
    var excluded by remember { mutableStateOf(prefs.excluded) }
    var showPeople by remember { mutableStateOf(false) }
    val country = remember { SosManager.countryIso(context) }
    val detected = remember(country) { EmergencyNumbers.forCountry(country) }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Safety · SOS 🆘", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "In danger? Press a volume button 4 times while amend is open, the power button 4 times (even when locked), or tap the red SOS button 4 times. " +
                            "After a 5-second countdown your live location goes to your contacts until you stop it, and the emergency dialer opens.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = enabled, onCheckedChange = { enabled = it; prefs.enabled = it; haptics.tick() })
            }

            AnimatedVisibility(enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Readiness checklist
                    CheckRow("Location access", hasLocation, "Allow") {
                        locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        CheckRow("Location \"all the time\" (for the power-button trigger when amend is closed)", hasBackground, "Open settings") {
                            if (!hasLocation) {
                                locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            } else {
                                // Android only allows "all the time" from the app's settings page
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    }
                    CheckRow("Instant notifications on (needed for the power-button trigger)", eco.instantNotifications, null) {}

                    // Emergency number
                    OutlinedTextField(
                        value = number,
                        onValueChange = { v -> number = v.filter { it.isDigit() || it == '+' }.take(15); prefs.customNumber = number },
                        label = { Text("Emergency number") },
                        placeholder = { Text(detected) },
                        supportingText = {
                            Text(if (number.isBlank()) "Using $detected${if (country.isNotBlank()) " for $country" else ""}. Change it if that's wrong where you are." else "Custom number")
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    ToggleRow("Open emergency dialer automatically", autoDial) { autoDial = it; prefs.autoDial = it }
                    ToggleRow("Volume button ×4 (amend open)", volume) { volume = it; prefs.volumeTrigger = it }
                    ToggleRow("Power button ×4 (works when locked)", power) { power = it; prefs.powerTrigger = it }

                    // Who gets the SOS
                    val people = conversations.values.filter { repo.isAccepted(it.peer) || it.messages.any { m -> m.fromMe } }.map { it.peer }
                    val included = people.count { it !in excluded }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showPeople = !showPeople }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Who gets my SOS", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "$included of ${people.size} people you've chatted with. Untick anyone you don't want to see your location.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(if (showPeople) "▲" else "▼")
                    }
                    AnimatedVisibility(showPeople) {
                        Column {
                            people.forEach { peer ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = peer !in excluded, onCheckedChange = { on ->
                                        excluded = if (on) excluded - peer else excluded + peer
                                        prefs.excluded = excluded
                                        haptics.tick()
                                    })
                                    Text(repo.displayName(peer), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (people.isEmpty()) Text("Start a chat first — your SOS goes to people you've talked with.", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    OutlinedButton(
                        onClick = { haptics.tap(); SosManager.trigger(context, practice = true) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Practice SOS (nothing is sent)") }

                    Text(
                        "Tip: also turn on your phone's own Emergency SOS (Settings → Safety & emergency). It can place the emergency call by itself, which apps aren't allowed to do.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, action: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (ok) Color(0xFF2BB673) else Color(0xFFE53935)),
            contentAlignment = Alignment.Center,
        ) { Text(if (ok) "✓" else "!", color = Color.White, style = MaterialTheme.typography.labelMedium) }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (!ok && action != null) {
            Button(onClick = onAction, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))) {
                Text(action)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
