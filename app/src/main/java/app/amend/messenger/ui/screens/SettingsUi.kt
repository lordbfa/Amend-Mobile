package app.amend.messenger.ui.screens

import app.amend.messenger.net.TorProxy
import app.amend.messenger.util.ScreenProtection
import app.amend.messenger.util.AppLock
import app.amend.messenger.util.findActivity
import app.amend.messenger.util.WearSync
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
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Check
import app.amend.messenger.ui.I18n
import androidx.compose.material.icons.rounded.MusicNote
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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Switch
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
const val SUPPORT_EMAIL = "lordbfa@getamend.app"
const val SUPPORT_NAME = "Ahmed Bhuiyan Faisal"
const val SUPPORT_ADDRESS = "40700, Jyväskylä, Finland"

/** amend's public source code, security policy and threat model. */
const val SOURCE_URL = "https://github.com/lordbfa/Amend-Mobile"
const val THREAT_MODEL_URL = "$SOURCE_URL/blob/main/docs/THREAT_MODEL.md"
const val SECURITY_POLICY_URL = "$SOURCE_URL/blob/main/SECURITY.md"

private enum class Section(val id: String, val title: String, val icon: ImageVector, val color: Long) {
    NOTIFICATIONS("notifications", "Notifications", Icons.Rounded.Notifications, 0xFFE53935),
    SOUNDS("sounds", "Sounds", Icons.Rounded.MusicNote, 0xFF6D4C9F),
    PRIVACY("privacy", "Privacy & security", Icons.Rounded.Lock, 0xFF1E88E5),
    SAFETY("safety", "Safety & SOS", Icons.Rounded.HealthAndSafety, 0xFFD81B60),
    CALLS("calls", "Calls", Icons.Rounded.Call, 0xFF2BB673),
    MESH("mesh", "Offline mesh", Icons.Rounded.WifiTethering, 0xFF00897B),
    NETWORK("network", "Your network & relays", Icons.Rounded.Hub, 0xFF8E24AA),
    LANGUAGE("language", "Language", Icons.Rounded.Language, 0xFF0097A7),
    APPEARANCE("appearance", "Appearance", Icons.Rounded.Palette, 0xFFFB8C00),
    ABOUT("about", "About amend", Icons.Rounded.Spa, 0xFF2E7D32),
    SECURITY("security", "Security & transparency", Icons.Rounded.VerifiedUser, 0xFF00796B),
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
        Section.SOUNDS -> app.amend.messenger.util.Sounds.option(context, app.amend.messenger.util.SoundKind.MESSAGE).label
        Section.PRIVACY -> if (eco.readReceipts) "Read receipts on" else "Read receipts off"
        Section.CALLS -> if (CallPrefs(context).hasTurn) "Works across networks" else "Direct only"
        Section.MESH -> if (mesh.enabled) (if (mesh.peers > 0) "${mesh.peers} nearby" else "On") else "Off"
        Section.NETWORK -> "$online of ${statuses.size} relays online"
        Section.APPEARANCE -> app.amend.messenger.ui.theme.AppThemes.current.let { t -> t.emoji + " " + t.label }
        Section.LANGUAGE -> I18n.languages.firstOrNull { it.code == I18n.current }?.native
        Section.INFO -> "v${BuildConfig.VERSION_NAME}"
        else -> null
    }
    Group(listOf(Section.NOTIFICATIONS, Section.SOUNDS, Section.PRIVACY, Section.SAFETY), ::status, onOpen)
    Group(listOf(Section.CALLS, Section.MESH, Section.NETWORK), ::status, onOpen)
    Group(listOf(Section.LANGUAGE, Section.APPEARANCE), ::status, onOpen)
    Group(listOf(Section.ABOUT, Section.SECURITY, Section.POLICY, Section.CONTACT, Section.INFO), ::status, onOpen)
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
        Section.SAFETY -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SafetyCard(repo)
            CheckInCard()
            NearbySosCard()
            FakeCallCard()
        }
        Section.CALLS -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            HideIpCallsCard()
            CallsCard()
        }
        Section.MESH -> MeshCard()
        Section.NETWORK -> {
            TorCard(repo)
            NetworkSection(repo)
            val relays by repo.relayUrls.collectAsStateWithLifecycle()
            RelayEditor(relays) { repo.setRelays(it) }
        }
        Section.APPEARANCE -> AppearancePage()
        Section.SOUNDS -> SoundsPage()
        Section.LANGUAGE -> LanguagePage()
        Section.ABOUT -> AboutPage()
        Section.SECURITY -> SecurityPage()
        Section.POLICY -> PolicyPage()
        Section.CONTACT -> ContactPage()
        Section.INFO -> AppInfoPage(app, repo)
    }
}

/** Who sees your Instants: everyone in your chat list, or only people you pick. */
@Composable
private fun InstantsAudienceCard(repo: AmendRepository) {
    var all by remember { mutableStateOf(repo.instantsToAll) }
    var edit by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("📸 Who sees your Instants", style = MaterialTheme.typography.titleMedium)
            listOf(true to "Everyone in my chats", false to "Only people I choose (${repo.closeFriends.size})").forEach { (v, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { all = v; repo.setInstantsToAll(v) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(selected = all == v, onClick = { all = v; repo.setInstantsToAll(v) })
                    Text(label)
                }
            }
            if (!all) TextButton(onClick = { edit = true }) { Text("Choose people") }
            Text(
                "Instants show at the top of their chat list. You can still change who gets one before you send it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (edit) CloseFriendsDialog(repo, onDismiss = { edit = false })
}

/** The age setting: 13–17 turns the stranger shield on. Changing to 18+ asks first. */
@Composable
private fun AgeCard() {
    val context = LocalContext.current
    var confirmAdult by remember { mutableStateOf(false) }
    val teen = app.amend.messenger.util.AgeCheck.group == app.amend.messenger.util.AgeCheck.Group.TEEN
    SettingSwitch(
        "Stranger shield (under 18)",
        if (teen) "On: only people you've accepted, or chats you started, can message you. Photos, links and locations from requests stay covered."
        else "Off. Turn on for anyone under 18 using this phone.",
        teen,
    ) { on ->
        if (on) app.amend.messenger.util.AgeCheck.set(context, app.amend.messenger.util.AgeCheck.Group.TEEN) else confirmAdult = true
    }
    if (confirmAdult) {
        AlertDialog(
            onDismissRequest = { confirmAdult = false },
            title = { Text("Are you 18 or older?") },
            text = { Text("The stranger shield protects people under 18. Only turn it off if you're an adult.") },
            confirmButton = { TextButton(onClick = { confirmAdult = false; app.amend.messenger.util.AgeCheck.set(context, app.amend.messenger.util.AgeCheck.Group.ADULT) }) { Text("I'm 18 or older") } },
            dismissButton = { TextButton(onClick = { confirmAdult = false }) { Text("Keep it on") } },
        )
    }
}

/** Screen lock: fingerprint, face or the phone's PIN to open amend. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ScreenLockCard() {
    val context = LocalContext.current
    val secure = remember { AppLock.deviceSecure(context) }
    var on by remember { mutableStateOf(AppLock.enabled) }
    var timeout by remember { mutableStateOf(AppLock.timeoutMs) }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Screen lock", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (on) "On: amend asks for your fingerprint, face or phone PIN before anyone can see your chats. Recent apps shows a blank preview."
                        else "Off: anyone holding your unlocked phone can open amend.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = on, enabled = secure || on, onCheckedChange = {
                    AppLock.setEnabled(context, it)
                    on = AppLock.enabled
                    context.findActivity()?.let { a -> ScreenProtection.applyRecents(a) }
                })
            }
            if (!secure) {
                Text(
                    "Set up a screen lock on your phone first (PIN, pattern, password or fingerprint).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }
            if (on) {
                Text("Lock again after", style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLock.TIMEOUTS.forEach { ms ->
                        androidx.compose.material3.FilterChip(
                            selected = timeout == ms,
                            onClick = { timeout = ms; AppLock.setTimeout(context, ms) },
                            label = {
                                Text(
                                    when (ms) {
                                        0L -> "Immediately"
                                        60_000L -> "1 minute"
                                        5 * 60_000L -> "5 minutes"
                                        else -> "30 minutes"
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** How much a message notification shows: name and text, name only, or nothing. */
@Composable
private fun NotificationPreviewCard() {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(app.amend.messenger.util.Notifications.preview(context)) }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Notification content", style = MaterialTheme.typography.titleMedium)
            Text(
                "What other people and other apps can see in your notifications. On the lock screen, the text is always hidden.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf(
                app.amend.messenger.util.Notifications.PREVIEW_FULL to "Name and message",
                app.amend.messenger.util.Notifications.PREVIEW_NAME to "Name only",
                app.amend.messenger.util.Notifications.PREVIEW_NONE to "No name or message",
            ).forEach { (value, label) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                        mode = value; app.amend.messenger.util.Notifications.setPreview(context, value)
                    }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(selected = mode == value, onClick = {
                        mode = value; app.amend.messenger.util.Notifications.setPreview(context, value)
                    })
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Screenshot protection on/off: chats and other people's profiles vs. your own screens. */
@Composable
private fun ScreenshotCard() {
    val context = LocalContext.current
    run {
        SettingSwitch(
            "Screenshot protection",
            if (ScreenProtection.enabled) "On: nobody can screenshot or record a chat or someone's profile or forest — yours included. Your own screens (chat list, forest, you, settings) can still be captured."
            else "Off: screenshots and screen recordings are allowed everywhere in amend.",
            ScreenProtection.enabled,
        ) { ScreenProtection.set(context, it) }
    }
}

/** Whether the words of your messages are sent to a paired watch, or only that there is one. */
@Composable
private fun WatchTextCard() {
    val context = LocalContext.current
    val on = WearSync.showText
    SettingSwitch(
        "Message text on your watch",
        if (on) "On: a watch with amend shows the words of your recent messages."
        else "Off: the watch shows that there is a message, never what it says.",
        on,
    ) { WearSync.setShowText(context, it) }
}

/** Burner identity: a fresh, unlinked key that can delete itself after a while. */
@Composable
private fun BurnerCard(app: AmendApp) {
    var ask by remember { mutableStateOf(false) }
    val expires = remember { app.burnerExpiresAt() }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🔥 Burner identity", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    expires > 0 -> "You're using a burner. It deletes itself " +
                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(expires * 1000)) + "."
                    app.isBurner() -> "You're using a burner identity."
                    else -> "Start over with a brand-new identity nobody can link to this one — for a trip, a sale or a one-off chat."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { ask = true }, shape = RoundedCornerShape(16.dp)) { Text("Start a burner identity") }
        }
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("Start a burner identity?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Your current identity and all its messages will be deleted from this phone. Copy your backup key first if you want it back later.")
                    Text("The burner deletes itself after:", style = MaterialTheme.typography.labelLarge)
                    listOf(24 * 3600L to "24 hours", 7 * 24 * 3600L to "7 days", 30 * 24 * 3600L to "30 days", 0L to "Never (keep it)").forEach { (secs, label) ->
                        TextButton(onClick = { ask = false; app.startBurner(secs) }) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PrivacyPage(app: AmendApp, repo: AmendRepository) {
    var confirm by remember { mutableStateOf(false) }
    ScreenLockCard()
    NotificationPreviewCard()
    ReadReceiptsCard(repo)
    AgeCard()
    InstantsAudienceCard(repo)
    ScreenshotCard()
    WatchTextCard()
    BackupCard(repo.keys.nsec)
    BurnerCard(app)
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

/** What amend protects, what it can't hide, and how to check it yourself. No overclaiming. */
@Composable
private fun SecurityPage() {
    val context = LocalContext.current
    val commit = BuildConfig.GIT_COMMIT
    Text("How amend protects you", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    TextCard(
        "End-to-end encryption" to "Messages are encrypted on your phone (NIP-44) and sealed in an envelope signed by a throwaway key (NIP-17/59), so relays can't read them or see who sent them. Calls are encrypted with DTLS-SRTP; photos, videos and voice notes with AES-256-GCM before they are uploaded.",
        "On your phone" to "Your key is kept in Android's hardware-backed keystore. Messages and settings are stored encrypted. Nothing is backed up off the phone, and amend writes nothing about you to the system log.",
        "No account, no tracking" to "No phone number, email or real name is needed. amend has no servers that store your messages, no ads and no analytics.",
    )
    Text("What amend can't hide", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    TextCard(
        "Relays see some metadata" to "A relay can see the recipient's public key, a rough time (shifted by up to two days) and the IP address that connects to it. Turn on Tor (Orbot) under Your network & relays to hide your IP address from relays.",
        "Calls" to "By default calls connect phone to phone, so the other person can see your IP address. Turn on Hide my IP address in calls under Calls to always use the relay.",
        "No forward secrecy yet" to "Messages are encrypted with your long-term key. If someone ever steals that key, they could read old messages that relays still store. Disappearing messages limit this: relays that honour the expiry delete them.",
        "Not audited yet" to "amend hasn't had an independent security audit. We welcome one and will publish the results.",
    )
    Text("Check it yourself", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    TextCard(
        "Open source" to ("All of amend's code is public under GPL-3.0. " +
            (if (commit.length == 40) "This copy was built from commit ${commit.take(12)}." else "")).trim(),
        "Verify your contacts" to "Open a chat, tap the name and choose Verify security code. If the codes match on both phones, nobody is in between.",
        "Found a problem?" to "Please tell us privately first, so we can fix it before anyone misuses it.",
    )
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open(if (commit.length == 40) "$SOURCE_URL/tree/$commit" else SOURCE_URL) }, shape = RoundedCornerShape(16.dp)) { Text("Source code") }
        OutlinedButton(onClick = { open(THREAT_MODEL_URL) }, shape = RoundedCornerShape(16.dp)) { Text("Threat model") }
    }
    Button(
        onClick = {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL"))
                .putExtra(Intent.EXTRA_SUBJECT, "amend security report")
            runCatching { context.startActivity(i) }.onFailure { open(SECURITY_POLICY_URL) }
        },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
    ) { Text("Report a security problem") }
}

@Composable
private fun PolicyPage() {
    TextCard(
        null to "amend has no accounts, no ads and no analytics. This is what happens with your data:",
        "Your key" to "Your identity is a key created on this phone and stored in Android's secure storage. We never see it. If you lose it without a backup, nobody can recover it.",
        "Messages, calls and media" to "Encrypted on your phone before they leave it (NIP-17/44 for messages, DTLS-SRTP for calls, AES-256-GCM for voice and video). Relays and file servers only see unreadable data, plus the recipient's public key and an approximate time.",
        "What is public" to "Your profile (name, about, photo) and anything you post on the community eco map (photo, place, note). Photos are stripped of hidden camera data before upload.",
        "Location" to "Used only when you ask: SOS (sent only to the people you chose), weather (rounded to about 1 km and sent to Open-Meteo), the eco map and the icon's sunrise time (on the phone).",
        "Photos, videos, stories & Instants" to "Encrypted on your phone before upload; photos lose their hidden location data. Stories go only to your contacts (or close friends) and disappear after 24 hours. Instants disappear once opened and can't be screenshotted; your own copies stay only on this phone for a year.",
        "Shared gardens (groups)" to "Each message is encrypted separately for every member. Relays can't tell it's a group.",
        "Live location" to "Only when you start it, only to that chat, and it stops by itself at the time you choose.",
        "Nearby SOS (optional, off by default)" to "Sends an approximate location (about 100 m) to amend phones close by over Bluetooth/Wi-Fi, signed with a one-time key that isn't linked to you.",
        "Friends' forests (optional)" to "If you turn it on, your tree count, points and streak are sent encrypted to your contacts — never publicly.",
        "On your phone only" to "Steps, walking reports, check-ins, fake calls, scheduled messages, your forest, points and settings never leave the device.",
        "Services amend talks to" to "Nostr relays (messages), Blossom file servers (encrypted media), Cloudflare TURN (relaying encrypted calls on strict networks), Open-Meteo (weather), OpenStreetMap (map tiles), Google STUN (finding a call path), and the website behind a name@domain address when you look one up. Each sees your IP address, like any website, unless you use Tor.",
        "Tor (optional)" to "With Orbot running, amend can route everything except calls through Tor.",
        "Limits" to "What amend can and can't hide is explained in Settings → Security & transparency.",
        "Translate & transcribe" to "Done on your phone. The first time you translate a language, its model is downloaded once from Google; your messages and voice notes are never sent.",
        "Deleting everything" to "Settings → Privacy & security → Remove identity deletes your key and all messages from this phone.",
        "Reports" to "When you report someone, your email app opens with the report and, only if you tick it, the last 10 messages of that chat as text. You decide whether to send it.",
        "Age" to "Your answer to the age question stays on this phone and is never sent.",
    )
    LinkButtons()
}

@Composable
private fun LinkButtons() {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TERMS_URL))) } }, shape = RoundedCornerShape(16.dp)) { Text("Terms of Use") }
        OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CHILD_SAFETY_URL))) } }, shape = RoundedCornerShape(16.dp)) { Text("Child safety") }
    }
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
                Text(SUPPORT_NAME, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(SUPPORT_EMAIL, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(SUPPORT_ADDRESS, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL"))
                            .putExtra(Intent.EXTRA_SUBJECT, "amend feedback").putExtra(Intent.EXTRA_TEXT, "\n\n—\n$info")
                        runCatching { context.startActivity(i) }
                    }, shape = RoundedCornerShape(16.dp)) { Text("Email us") }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(SUPPORT_EMAIL)); haptics.success() }) { Text("Copy email") }
                }
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
private fun AppInfoPage(app: AmendApp, repo: AmendRepository) {
    val context = LocalContext.current
    var taps by remember { mutableStateOf(0) }
    var moderator by remember { mutableStateOf(false) }
    if (moderator) ModeratorDialog(repo, onDismiss = { moderator = false; taps = 0 })
    // hidden moderator tools: tap the version 7 times
    Text(
        "amend ${BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable { taps++; if (taps >= 7) moderator = true },
    )
    TextCard(
        "Version" to "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
        "Encryption" to if (app.nativeCrypto) "Native secp256k1 (fast)" else "Portable Kotlin crypto",
        "Built with" to "Kotlin & Jetpack Compose · Nostr (NIP-01/17/44/59) · WebRTC · Nearby Connections · osmdroid / OpenStreetMap · Open-Meteo · ZXing",
        "Source" to (if (BuildConfig.GIT_COMMIT.length == 40) "Built from commit ${BuildConfig.GIT_COMMIT.take(12)}" else "Built from local source"),
        "Licence" to "Open source (GPL-3.0).",
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



/** Settings → Network: hide your IP from relays by routing through Orbot (Tor). */
@Composable
private fun TorCard(repo: AmendRepository) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(TorProxy.enabled) }
    var running by remember { mutableStateOf<Boolean?>(null) }
    val installed = remember {
        context.packageManager.getLaunchIntentForPackage(TorProxy.ORBOT_PACKAGE) != null
    }
    LaunchedEffect(on) {
        while (on) {
            running = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { TorProxy.orbotRunning() }
            kotlinx.coroutines.delay(4_000)
        }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Route through Tor (Orbot) 🧅", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Relays, file servers and the weather service see a Tor address instead of yours. Slower, but nobody can link your messages to your IP. If Orbot isn't running, amend stays offline rather than connecting without Tor. Calls still connect directly.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = on, onCheckedChange = {
                    on = it
                    haptics.tick()
                    TorProxy.enabled = it
                    (context.applicationContext as? app.amend.messenger.AmendApp)?.applyMapProxy()
                    context.getSharedPreferences("amend_net", android.content.Context.MODE_PRIVATE).edit().putBoolean("tor", it).apply()
                    // reconnect so every open connection takes the new route
                    scope.launch { repo.goOffline(); kotlinx.coroutines.delay(300); repo.goOnline() }
                })
            }
            if (on) {
                when {
                    !installed -> {
                        Text("Orbot isn't installed.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + TorProxy.ORBOT_PACKAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + TorProxy.ORBOT_PACKAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                        }, shape = RoundedCornerShape(16.dp)) { Text("Get Orbot") }
                    }
                    running == true -> Text("✅ Connected through Tor", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    else -> {
                        Text("Orbot isn't running yet — amend is waiting offline.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = {
                            context.sendBroadcast(
                                Intent("org.torproject.android.intent.action.START")
                                    .setPackage(TorProxy.ORBOT_PACKAGE)
                                    .putExtra("org.torproject.android.intent.extra.PACKAGE_NAME", context.packageName)
                            )
                            runCatching {
                                context.packageManager.getLaunchIntentForPackage(TorProxy.ORBOT_PACKAGE)
                                    ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                        }, shape = RoundedCornerShape(16.dp)) { Text("Start Orbot") }
                    }
                }
            }
        }
    }
}
