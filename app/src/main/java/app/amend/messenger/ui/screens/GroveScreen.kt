package app.amend.messenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.core.nostr.Event
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.ui.Text
import app.amend.messenger.ui.components.GlassCard
import app.amend.messenger.ui.components.LeafAvatar
import app.amend.messenger.ui.components.rememberHaptics
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val GROVE_ROUTE = "amend:grove"

/**
 * The Grove: public Nostr notes from you and the people you chat with. Unlike chats,
 * everything here is PUBLIC — the composer says so every time.
 */
@Composable
fun GroveScreen(repo: AmendRepository, onBack: () -> Unit, openChat: (String) -> Unit) {
    val feed by repo.feed.collectAsStateWithLifecycle()
    val profiles by repo.profiles.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var draft by remember { mutableStateOf("") }
    var posting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Event?>(null) }
    DisposableEffect(Unit) {
        repo.openFeed()
        onDispose { repo.closeFeed() }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text("🌿 The Grove", style = MaterialTheme.typography.titleLarge)
                Text("Public notes from you and your contacts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            draft, { draft = it.take(2000) }, Modifier.fillMaxWidth(),
                            placeholder = { Text("Share something with the Grove…") }, shape = RoundedCornerShape(16.dp), minLines = 2,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                status ?: "🌍 Public — anyone on Nostr can read this. Chats stay private.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = draft.isNotBlank() && !posting,
                                onClick = {
                                    posting = true
                                    val text = draft
                                    scope.launch {
                                        val n = repo.postNote(text)
                                        posting = false
                                        if (n > 0) { draft = ""; haptics.success(); status = "Posted to $n relays 🌱" }
                                        else { haptics.reject(); status = "Couldn't post — check your connection" }
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                            ) { Text(if (posting) "Posting…" else "Post") }
                        }
                    }
                }
            }
            if (feed.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet. Notes your contacts post on Nostr — from amend or any other Nostr app — show up here.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(feed, key = { it.id }) { note ->
                val mine = note.pubkey == repo.me
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.then(if (!mine) Modifier.clickable { openChat(note.pubkey) } else Modifier)) {
                                LeafAvatar(note.pubkey, profiles[note.pubkey], 36.dp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (mine) "You" else repo.displayName(note.pubkey), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(
                                    Instant.ofEpochSecond(note.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm")),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (mine) TextButton(onClick = { deleting = note }) { Text("Delete") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(linkify(note.content.take(4000), MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }

    deleting?.let { note ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this note?") },
            text = { Text("amend asks every relay to delete it. Copies others already saved may remain.") },
            confirmButton = { TextButton(onClick = { deleting = null; scope.launch { repo.deleteNote(note.id) } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
