package app.amend.messenger.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Check
import app.amend.messenger.ui.I18n
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import app.amend.messenger.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.AmendApp
import app.amend.messenger.BuildConfig
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.net.RelayState
import app.amend.messenger.ui.components.GlassCard
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.util.CallPrefs
import app.amend.messenger.util.DynamicIcon
import app.amend.messenger.util.Mesh

/** Route ids: "amend:settings" is the list, "amend:settings:<section>" one page. */
const val SETTINGS_ROUTE = "amend:settings"

/** Where people can reach the team. Empty until an address is chosen; the Contact page adapts. */
const val SUPPORT_EMAIL = ""

private enum class Section(val id: String, val title: String, val icon: ImageVector, val color: Long) {
    NOTIFICATIONS("notifications", "Notifications", Icons.Rounded.Notifications, 0xFFE53935),
    PRIVACY("privacy", "Privacy & security", Icons.Rounded.Lock, 0xFF1E88E5),
    SAFETY("safety", "Safety & SOS", Icons.Rounded.HealthAndSafety, 0xFFD81B60),
    CALLS("calls", "Calls", Icons.Rounded.Call, 0xFF2BB673),
    MESH("mesh", "Offline mesh", Icons.Rounded.WifiTethering, 0xFF00897B),
    NETWORK("network", "Your network & relays", Icons.Rounded.Hub, 0xFF8E24AA),
    LANGUAGE("language", "Language", Icons.Rounded.Language, 0xFF0097A7),
    APPEARANCE("appearance", "Appearance", Icons.Rounded.Palette, 0xFFFB8C00),
    ABOUT("about", "About amend", Icons.Rounded.Spa, 0xFF2E7D32),
    POLICY("policy", "Privacy policy", Icons.Rounded.Policy, 0xFF546E7A),
    CONTACT("contact", "Contact us", Icons.Rounded.Email, 0xFF3949AB),
    INFO("info", "App info", Icons.Rounded.Info, 0xFF757575);

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
}

/** [route] is SETTINGS_ROUTE or SETTINGS_ROUTE + ":" + section. [open] pushes another route. */
@Composable
fun SettingsScreen(app: AmendApp, repo: AmendRepository, route: String, open: (String) -> Unit, onBack: () -> Unit) {
    val section = Section.of(route.removePrefix(SETTINGS_ROUTE).removePrefix(":").ifBlank { null })
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(section?.title ?: "Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (section == null) SettingsList(repo) { open("$SETTINGS_ROUTE:${it.id}") }
            else SectionPage(app, repo, section)
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ======================================================================= the list

@Composable
private fun SettingsList(repo: AmendRepository, onOpen: (Section) -> Unit) {
    val context = LocalContext.current
    val eco by repo.eco.collectAsStateWithLifecycle()
    val statuses by repo.relayStatuses.collectAsStateWithLifecycle()
    val mesh by Mesh.ui.collectAsStateWithLifecycle()
    val online = statuses.count { it.state == RelayState.ONLINE }
    fun status(s: Section): String? = when (s) {
        Section.NOTIFICATIONS -> if (eco.instantNotifications) "Instant" else "Every 15–60 min"
        Section.PRIVACY -> if (eco.readReceipts) "Read receipts on" else "Read receipts off"
        Section.CALLS -> if (CallPrefs(context).hasTurn) "Works across networks" else "Direct only"
        Section.MESH -> if (mesh.enabled) (if (mesh.peers > 0) "${mesh.peers} nearby" else "On") else "Off"
        Section.NETWORK -> "$online of ${statuses.size} relays online"
        Section.APPEARANCE -> app.amend.messenger.ui.theme.AppThemes.current.let { t -> t.emoji + " " + t.label }
        Section.LANGUAGE -> I18n.languages.firstOrNull { it.code == I18n.current }?.native
        Section.INFO -> "v${BuildConfig.VERSION_NAME}"
        else -> null
    }
    Group(listOf(Section.NOTIFICATIONS, Section.PRIVACY, Section.SAFETY), ::status, onOpen)
    Group(listOf(Section.CALLS, Section.MESH, Section.NETWORK), ::status, onOpen)
    Group(listOf(Section.LANGUAGE, Section.APPEARANCE), ::status, onOpen)
    Group(listOf(Section.ABOUT, Section.POLICY, Section.CONTACT, Section.INFO), ::status, onOpen)
}

@Composable
private fun Group(items: List<Section>, status: (Section) -> String?, onOpen: (Section) -> Unit) {
    val haptics = rememberHaptics()
    GlassCard(Modifier.fillMaxWidth()) {
        Column {
            items.forEachIndexed { i, s ->
                Row(
                    Modifier.fillMaxWidth().clickable { haptics.tap(); onOpen(s) }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Color(s.color)), contentAlignment = Alignment.Center) {
                        Icon(s.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(s.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    status(s)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (i < items.lastIndex) HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

// ======================================================================= the pages

@Composable
private fun SectionPage(app: AmendApp, repo: AmendRepository, section: Section) {
    when (section) {
        Section.NOTIFICATIONS -> NotificationsCard(app, repo)
        Section.PRIVACY -> PrivacyPage(app, repo)
        Section.SAFETY -> SafetyCard(repo)
        Section.CALLS -> CallsCard()
        Section.MESH -> MeshCard()
        Section.NETWORK -> {
            NetworkSection(repo)
            val relays by repo.relayUrls.collectAsStateWithLifecycle()
            RelayEditor(relays) { repo.setRelays(it) }
        }
        Section.APPEARANCE -> AppearancePage()
        Section.LANGUAGE -> LanguagePage()
        Section.ABOUT -> AboutPage()
        Section.POLICY -> PolicyPage()
        Section.CONTACT -> ContactPage()
        Section.INFO -> AppInfoPage(app)
    }
}

@Composable
private fun PrivacyPage(app: AmendApp, repo: AmendRepository) {
    var confirm by remember { mutableStateOf(false) }
    ReadReceiptsCard(repo)
    BackupCard(repo.keys.nsec)
    OutlinedButton(
        onClick = { confirm = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text("Remove identity from this phone") }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove your identity?") },
            text = { Text("Your key and all messages will be deleted from this phone. Without your backup key you can't get this identity back.") },
            confirmButton = { TextButton(onClick = { confirm = false; app.signOut() }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TextCard(vararg blocks: Pair<String?, String>) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEach { (title, body) ->
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AboutPage() {
    TextCard(
        "🌱 amend" to "A private messenger that helps you and the people you talk to take better care of the planet — and of each other.",
        "Private by design" to "No phone number, no email. Messages are end-to-end encrypted and travel through independent Nostr relays, so no single company holds your conversations.",
        "Kinder to the planet" to "amend measures its own data footprint, grows a forest from mindful use, and turns real actions — walking, clean-ups, planting trees — into points.",
        "Safe when it matters" to "SOS with live location, weather and air alerts, and an offline mesh that keeps messages moving when the internet goes down.",
        "Open" to "amend is built on the open Nostr protocol. Your key works in any Nostr app.",
    )
    MadeWithLove(Modifier.fillMaxWidth())
}

@Composable
private fun PolicyPage() {
    TextCard(
        null to "amend has no accounts, no ads and no analytics. This is what happens with your data:",
        "Your key" to "Your identity is a key created on this phone and stored in Android's secure storage. We never see it. If you lose it without a backup, nobody can recover it.",
        "Messages, calls and media" to "Encrypted on your phone before they leave it (NIP-17/44 for messages, DTLS-SRTP for calls, AES-256-GCM for voice and video). Relays and file servers only see unreadable data, plus the recipient's public key and an approximate time.",
        "What is public" to "Your profile (name, about, photo) and anything you post on the community eco map (photo, place, note). Photos are stripped of hidden camera data before upload.",
        "Location" to "Used only when you ask: SOS (sent only to the people you chose), weather (rounded to about 1 km and sent to Open-Meteo), the eco map and the icon's sunrise time (on the phone).",
        "On your phone only" to "Steps, walking reports, your forest, points and settings never leave the device.",
        "Services amend talks to" to "Nostr relays (messages), Blossom file servers (encrypted media), Cloudflare TURN (relaying encrypted calls on strict networks), Open-Meteo (weather), OpenStreetMap (map tiles), Google STUN (finding a call path). Each sees your IP address, like any website.",
        "Deleting everything" to "Settings → Privacy & security → Remove identity deletes your key and all messages from this phone.",
    )
}

@Composable
private fun ContactPage() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = rememberHaptics()
    var copied by remember { mutableStateOf(false) }
    val info = "amend ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (SUPPORT_EMAIL.isNotBlank()) {
                Text("We'd love to hear from you — ideas, problems or just hello.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = {
                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL"))
                        .putExtra(Intent.EXTRA_SUBJECT, "amend feedback").putExtra(Intent.EXTRA_TEXT, "\n\n—\n$info")
                    runCatching { context.startActivity(i) }
                }, shape = RoundedCornerShape(16.dp)) { Text("Email us") }
            } else {
                Text("A contact address is coming soon.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Found a problem? Copy your app details below and include them when you tell us — it helps us fix things faster.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(info, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { clipboard.setText(AnnotatedString(info)); copied = true; haptics.success() }) {
                Text(if (copied) "Copied ✓" else "Copy app details")
            }
        }
    }
}

@Composable
private fun AppInfoPage(app: AmendApp) {
    val context = LocalContext.current
    TextCard(
        "Version" to "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
        "Encryption" to if (app.nativeCrypto) "Native secp256k1 (fast)" else "Portable Kotlin crypto",
        "Built with" to "Kotlin & Jetpack Compose · Nostr (NIP-01/17/44/59) · WebRTC · Nearby Connections · osmdroid / OpenStreetMap · Open-Meteo · ZXing",
        "Licence" to "Open source.",
    )
    OutlinedButton(onClick = {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
    }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Text("Android app settings (storage, permissions)") }
}

/** Pick the app's language. Changes everything at once — no restart. */
@Composable
private fun LanguagePage() {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val choice = I18n.choice
    val systemName = I18n.languages.firstOrNull { it.code == I18n.resolve("system") }?.native ?: "English"
    GlassCard(Modifier.fillMaxWidth()) {
        Column {
            val rows = listOf(Triple("system", "System default", systemName)) +
                I18n.languages.map { Triple(it.code, it.native, if (it.native != it.english) it.english else "") }
            rows.forEachIndexed { i, (code, title, sub) ->
                Row(
                    Modifier.fillMaxWidth().clickable { haptics.tick(); I18n.select(context, code) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        // language names are always shown in their own script, never translated
                        androidx.compose.material3.Text(if (code == "system") I18n.tr(title) else title, style = MaterialTheme.typography.bodyLarge)
                        if (sub.isNotBlank()) androidx.compose.material3.Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (choice == code) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color(0xFF2BB673))
                }
                if (i < rows.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

