package app.amend.messenger.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.BuildConfig
import app.amend.messenger.core.crypto.Nip19
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.Moderation
import app.amend.messenger.ui.Text
import app.amend.messenger.util.AgeCheck
import kotlinx.coroutines.launch

const val TERMS_URL = "https://getamend.app/terms/"
const val CHILD_SAFETY_URL = "https://getamend.app/child-safety/"
private const val INHOPE_URL = "https://www.inhope.org/EN"

enum class ReportReason(val label: String) {
    CHILD("Child sexual abuse or exploitation"),
    TRAFFICKING("Human trafficking or exploitation"),
    DRUGS("Selling drugs, weapons or other illegal goods"),
    VIOLENCE("Threats, violence or terrorism"),
    HARASSMENT("Harassment, bullying or hate"),
    SCAM("Spam, scam or fraud"),
    OTHER("Something else"),
}

/**
 * Report someone (or a public post). amend can't read messages, so a report is an email the
 * reporter sends themselves — with the last messages of the chat attached as text only if they
 * choose. Reporting always blocks / hides on this phone too.
 */
@Composable
fun ReportDialog(
    what: String,
    reportedKey: String,
    evidence: String?,
    onDismiss: () -> Unit,
    onReported: () -> Unit,
    extraContext: String = "",
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    var attach by remember { mutableStateOf(evidence != null) }
    var details by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report $what") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().background(Color(0x22E53935), RoundedCornerShape(14.dp)).padding(12.dp)) {
                    Text(
                        "If someone is in danger right now, call your emergency number (112 in Europe). " +
                            "Never forward images of child abuse to anyone, including us — that is a crime. Describe them instead.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("What's wrong?", style = MaterialTheme.typography.labelLarge)
                ReportReason.entries.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { reason = r },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == r, onClick = { reason = r })
                        Text(r.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(
                    details, { details = it.take(2000) }, Modifier.fillMaxWidth(),
                    label = { Text("Details (optional)") }, shape = RoundedCornerShape(14.dp), minLines = 2,
                )
                if (evidence != null) {
                    Row(Modifier.fillMaxWidth().clickable { attach = !attach }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = attach, onCheckedChange = { attach = it })
                        Text("Include the last 10 messages as evidence (text only)", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "Your email app opens with the report; you send it to amend's safety team. We check every report, " +
                        "can block the account for everyone on amend, and pass child-safety reports to the authorities.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (reason == ReportReason.CHILD) {
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(INHOPE_URL))) } }) {
                        Text("Also report to your country's child-abuse hotline ↗")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = reason != null, onClick = {
                val r = reason ?: return@TextButton
                val body = buildString {
                    appendLine("Reason: ${r.label}")
                    appendLine("Reported: $what")
                    appendLine("Account: ${Nip19.npub(reportedKey)}")
                    if (extraContext.isNotBlank()) appendLine(extraContext)
                    if (details.isNotBlank()) { appendLine(); appendLine("Details:"); appendLine(details) }
                    if (attach && evidence != null) { appendLine(); appendLine("Last messages (copied from the reporter's phone):"); appendLine(evidence) }
                    appendLine(); appendLine("— amend ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                }
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL"))
                    .putExtra(Intent.EXTRA_SUBJECT, "amend safety report: ${r.label}")
                    .putExtra(Intent.EXTRA_TEXT, body)
                val opened = runCatching { context.startActivity(intent) }.isSuccess
                if (!opened) clipboard.setText(AnnotatedString("To: $SUPPORT_EMAIL\n\n$body"))
                onReported()
            }) { Text("Report & block", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Moderator tools (hidden: tap the version in App info 7 times). Needs the amend moderator
 * secret key, which is only kept in memory while this dialog is open.
 */
@Composable
fun ModeratorDialog(repo: AmendRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val current by repo.modBlocked.collectAsStateWithLifecycle()
    var nsec by remember { mutableStateOf("") }
    val key = remember(nsec) { Nip19.parseSecretKey(nsec.trim())?.let { runCatching { KeyPair(it) }.getOrNull() } }
    val ok = key?.publicKeyHex == Moderation.MODERATOR
    val list = remember(current) { mutableStateListOf<String>().apply { addAll(current.sorted()) } }
    var adding by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Moderator tools") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    nsec, { nsec = it }, Modifier.fillMaxWidth(), label = { Text("Moderator key (nsec1…)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp),
                    isError = nsec.isNotBlank() && !ok,
                    supportingText = { if (nsec.isNotBlank() && !ok) Text("That isn't the amend moderator key") },
                )
                Text("Blocked for everyone: ${list.size}", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(adding, { adding = it.trim() }, Modifier.weight(1f), label = { Text("npub1… to block") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    TextButton(onClick = {
                        Nip19.parsePublicKey(adding)?.let { if (it !in list && it != Moderation.MODERATOR) list.add(0, it); adding = "" }
                    }) { Text("Add") }
                }
                list.toList().forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Nip19.npub(k).let { it.take(16) + "…" + it.takeLast(6) }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { list.remove(k) }) { Text("Remove") }
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                val k = key ?: return@TextButton
                status = "Publishing…"
                scope.launch {
                    val n = repo.publishModerationList(k, list.toList())
                    status = if (n > 0) "Published to $n relays ✓" else "No relay took it — check your connection"
                }
            }) { Text("Publish list") }
        },
        dismissButton = { TextButton(onClick = { nsec = ""; onDismiss() }) { Text("Close") } },
    )
}

/** One-time age question, before anything else. Stays on this phone. */
@Composable
fun AgeGateScreen() {
    val context = LocalContext.current
    var tooYoung by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0F2E21)).statusBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (tooYoung) "🌱" else "🌳", fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))
        if (tooYoung) {
            Text("amend is for people 13 and older", style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                "Ask a parent or carer about a messenger that's made for your age. Thanks for being honest!",
                color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = { tooYoung = false }) { Text("Back", color = Color.White) }
            return@Column
        }
        Text("How old are you?", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            "This stays on your phone. Under-18s get extra protection: only people they accept can message them.",
            color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        val green = ButtonDefaults.buttonColors(containerColor = Color(0xFF2BB673), contentColor = Color.White)
        Button(onClick = { AgeCheck.set(context, AgeCheck.Group.ADULT) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(27.dp), colors = green) { Text("18 or older") }
        Spacer(Modifier.height(10.dp))
        Button(onClick = { AgeCheck.set(context, AgeCheck.Group.TEEN) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(27.dp), colors = green) { Text("13 – 17") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { tooYoung = true }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(27.dp)) { Text("Under 13", color = Color.White) }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("By continuing you agree to the", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
        }
        Row {
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TERMS_URL))) } }) { Text("Terms of Use", color = Color(0xFF8FE3B5)) }
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CHILD_SAFETY_URL))) } }) { Text("Child safety", color = Color(0xFF8FE3B5)) }
        }
    }
}
