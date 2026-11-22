package app.amend.messenger.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.core.crypto.Rand
import app.amend.messenger.core.crypto.toHex
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.CareNote
import app.amend.messenger.data.CareTopic
import app.amend.messenger.data.Pet
import app.amend.messenger.data.PetKind
import app.amend.messenger.ui.Text
import app.amend.messenger.ui.components.GlassCard
import app.amend.messenger.ui.components.InfoTitle
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.ui.tr

private val Leaf = Color(0xFF2BB673)

private fun newId() = Rand.bytes(8).toHex()

/** Animal welfare in the Welfare tab: your animals, what they have, and how to care for them. */
@Composable
fun AnimalWelfareCard(repo: AmendRepository) {
    val pets by repo.pets.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }
    val haptics = rememberHaptics()
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoTitle(
                "Animal welfare",
                info = "Add your cat, dog or any animal you care for. Write down what they have and how to look after them: food, health, vet, daily care. It stays on this phone, encrypted, and you can share an animal's card with anyone you choose — for example someone who looks after them while you're away.",
                style = MaterialTheme.typography.titleMedium,
            )
            if (pets.isEmpty()) {
                Text(
                    "Do you have a cat, a dog or another animal? Add them here and keep their care notes in one place.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { haptics.tap(); adding = true }, shape = RoundedCornerShape(16.dp)) { Text("Add an animal") }
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pets.forEach { p ->
                        Column(
                            Modifier.width(84.dp).clip(RoundedCornerShape(16.dp)).clickable { haptics.tap(); openId = p.id }.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(64.dp).clip(CircleShape).background(Leaf.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) { Text(p.kind.emoji, fontSize = 30.sp) }
                            androidx.compose.material3.Text(
                                p.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(
                                if (p.notes.isEmpty()) "No notes yet" else if (p.notes.size == 1) "1 note" else "${p.notes.size} notes",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).border(2.dp, Leaf, CircleShape).clickable { haptics.tap(); adding = true },
                        contentAlignment = Alignment.Center,
                    ) { Text("＋", color = Leaf, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
    if (adding) {
        PetEditDialog(
            pet = null,
            onDismiss = { adding = false },
            onSave = { p -> repo.savePet(p); adding = false; openId = p.id },
        )
    }
    openId?.let { id ->
        val pet = pets.firstOrNull { it.id == id }
        if (pet == null) androidx.compose.runtime.LaunchedEffect(id) { openId = null }
        else PetDialog(repo, pet, onClose = { openId = null })
    }
}

/** One animal: what they have, their care notes, and share / edit / remove. */
@Composable
private fun PetDialog(repo: AmendRepository, pet: Pet, onClose: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var editing by remember { mutableStateOf(false) }
    var noteEdit by remember { mutableStateOf<CareNote?>(null) }
    var newNote by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    FullScreenDialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeTop()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("✕", fontSize = 20.sp) }
                Text("Animal welfare", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // who they are
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(96.dp).clip(CircleShape).background(Leaf.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                            Text(pet.kind.emoji, fontSize = 48.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        androidx.compose.material3.Text(pet.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        androidx.compose.material3.Text(
                            listOf(tr(pet.kind.label), pet.breed, pet.age).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                haptics.tap()
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, pet.name)
                                    putExtra(Intent.EXTRA_TEXT, shareText(pet))
                                }
                                context.startActivity(Intent.createChooser(send, tr("Share ${pet.name}'s care card")))
                            }, shape = RoundedCornerShape(14.dp)) { Text("Share") }
                            OutlinedButton(onClick = { editing = true }, shape = RoundedCornerShape(14.dp)) { Text("Edit") }
                        }
                    }
                }

                // what they have
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoTitle(
                            "What they have",
                            info = "Vaccines, a microchip, their food, toys, a favourite place — anything that's theirs.",
                        )
                        if (pet.has.isBlank()) {
                            Text("Nothing written yet. Tap Edit to add it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            androidx.compose.material3.Text(pet.has, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // care notes
                InfoTitle(
                    "Care notes",
                    info = "How to look after them: how to feed them, their health, the vet, grooming and daily care. Tap a note to change it.",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (pet.notes.isEmpty()) {
                    Text(
                        "No notes yet. Write how to feed them, their health and the vet, so anyone can look after them.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                pet.notes.sortedBy { it.topic.ordinal }.forEach { n ->
                    GlassCard(Modifier.fillMaxWidth().clickable { noteEdit = n }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(n.topic.label, style = MaterialTheme.typography.labelLarge, color = Leaf, fontWeight = FontWeight.SemiBold)
                            androidx.compose.material3.Text(n.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (pet.notes.size < Pet.MAX_NOTES) {
                    Button(onClick = { haptics.tap(); newNote = true }, shape = RoundedCornerShape(16.dp)) { Text("Add a note") }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Remove ${pet.name}", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (editing) {
        PetEditDialog(pet, onDismiss = { editing = false }, onSave = { repo.savePet(it); editing = false })
    }
    if (newNote || noteEdit != null) {
        NoteEditDialog(
            note = noteEdit,
            onDismiss = { newNote = false; noteEdit = null },
            onSave = { n ->
                val notes = if (pet.notes.any { it.id == n.id }) pet.notes.map { if (it.id == n.id) n else it } else pet.notes + n
                repo.savePet(pet.copy(notes = notes))
                newNote = false; noteEdit = null
            },
            onDelete = noteEdit?.let { old ->
                {
                    repo.savePet(pet.copy(notes = pet.notes.filterNot { it.id == old.id }))
                    noteEdit = null
                }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${pet.name}?") },
            text = { Text("Their care notes are removed from this phone too.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; repo.deletePet(pet.id); onClose() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Add or change an animal: kind, name, breed, age and what they have. */
@Composable
private fun PetEditDialog(pet: Pet?, onDismiss: () -> Unit, onSave: (Pet) -> Unit) {
    var kind by remember { mutableStateOf(pet?.kind ?: PetKind.CAT) }
    var name by remember { mutableStateOf(pet?.name ?: "") }
    var breed by remember { mutableStateOf(pet?.breed ?: "") }
    var age by remember { mutableStateOf(pet?.age ?: "") }
    var has by remember { mutableStateOf(pet?.has ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (pet == null) "Add an animal" else "Edit") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PetKind.entries.forEach { k ->
                        val on = k == kind
                        Column(
                            Modifier.clip(RoundedCornerShape(14.dp))
                                .background(if (on) Leaf.copy(alpha = 0.2f) else Color.Transparent)
                                .border(1.dp, if (on) Leaf else Color(0x33888888), RoundedCornerShape(14.dp))
                                .clickable { kind = k }.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(k.emoji, fontSize = 24.sp)
                            Text(k.label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(breed, { breed = it.take(60) }, label = { Text("Breed (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(age, { age = it.take(40) }, label = { Text("Age or birthday (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    has, { has = it.take(Pet.MAX_TEXT) },
                    label = { Text("What they have") },
                    placeholder = { Text("Vaccines, microchip, food, toys…") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(
                    (pet ?: Pet(newId(), name, kind, createdAt = System.currentTimeMillis() / 1000))
                        .copy(name = name, kind = kind, breed = breed, age = age, has = has),
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Write or change a care note: pick what it's about, then write it. */
@Composable
private fun NoteEditDialog(note: CareNote?, onDismiss: () -> Unit, onSave: (CareNote) -> Unit, onDelete: (() -> Unit)?) {
    var topic by remember { mutableStateOf(note?.topic ?: CareTopic.FOOD) }
    var text by remember { mutableStateOf(note?.text ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (note == null) "Add a note" else "Care note") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CareTopic.entries.forEach { t ->
                        val on = t == topic
                        Box(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (on) Leaf.copy(alpha = 0.2f) else Color.Transparent)
                                .border(1.dp, if (on) Leaf else Color(0x33888888), RoundedCornerShape(50))
                                .clickable { topic = t }.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text(t.label, style = MaterialTheme.typography.labelLarge) }
                    }
                }
                Text(topic.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    text, { text = it.take(Pet.MAX_TEXT) },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                onSave((note ?: CareNote(newId(), topic, text, 0)).copy(topic = topic, text = text, updatedAt = System.currentTimeMillis() / 1000))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** The care card that goes out through the share menu. */
private fun shareText(p: Pet): String = buildString {
    append(p.kind.emoji).append(' ').append(p.name)
    val about = listOf(tr(p.kind.label), p.breed, p.age).filter { it.isNotBlank() }.joinToString(" · ")
    if (about.isNotBlank()) append(" (").append(about).append(')')
    append('\n')
    if (p.has.isNotBlank()) append('\n').append(tr("What they have")).append(":\n").append(p.has).append('\n')
    if (p.notes.isNotEmpty()) {
        append('\n').append(tr("Care notes")).append(":\n")
        p.notes.sortedBy { it.topic.ordinal }.forEach { n -> append("• ").append(tr(n.topic.label)).append(": ").append(n.text).append('\n') }
    }
    append('\n').append(tr("Shared from amend")).append(" · ").append(PLAY_STORE_URL)
}
