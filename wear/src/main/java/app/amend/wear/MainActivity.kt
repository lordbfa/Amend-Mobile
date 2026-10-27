package app.amend.wear

import android.app.RemoteInput
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper
import kotlinx.coroutines.launch

private val Green = Color(0xFF2BB673)
private val Leaf = Color(0xFF1F7A4E)
private val Night = Color(0xFF14121F)
private val Card = Color(0xFF26233A)
private val Muted = Color(0xFFB9C4BD)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchStore.load(this)
        setContent { AmendWatch() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            runCatching { WatchStore.fetch(this@MainActivity) }
            runCatching { PhoneLink.refresh(this@MainActivity) }
        }
    }
}

@Composable
private fun AmendWatch() {
    val nav = rememberSwipeDismissableNavController()
    MaterialTheme(colors = Colors(primary = Green, primaryVariant = Leaf, secondary = Green, background = Night, surface = Card, onPrimary = Color.White)) {
        SwipeDismissableNavHost(navController = nav, startDestination = "chats") {
            composable("chats") { ChatList(onOpen = { nav.navigate("chat/$it") }) }
            composable("chat/{peer}") { entry -> Thread(entry.arguments?.getString("peer").orEmpty()) }
        }
    }
}

@Composable
private fun ChatList(onOpen: (String) -> Unit) {
    val state by WatchStore.state.collectAsState()
    val list = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(list) }, modifier = Modifier.background(Night)) {
        ScalingLazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("amend", fontWeight = FontWeight.Bold, color = Green) } }
            val s = state
            if (s != null) item { ForestLine(s) }
            if (s == null || s.chats.isEmpty()) {
                item {
                    Text(
                        if (s == null) "Open amend on your phone to connect this watch." else "No chats yet. Start one on your phone.",
                        textAlign = TextAlign.Center, color = Muted, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(s.chats, key = { it.peer }) { c ->
                    Chip(
                        onClick = { onOpen(c.peer) },
                        label = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.Normal) },
                        secondaryLabel = {
                            Text((if (c.lastFromMe) "You: " else "") + c.last, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        icon = { Avatar(c.name, c.unread) },
                        colors = ChipDefaults.secondaryChipColors(backgroundColor = Card),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ForestLine(s: WatchState) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        Text("🌳 ${s.trees}", fontSize = 13.sp, color = Muted)
        Text("🌱 ${s.points}", fontSize = 13.sp, color = Muted)
        if (s.stepsEnabled) Text("👣 %,d".format(s.stepsToday), fontSize = 13.sp, color = Muted)
    }
}

@Composable
private fun Avatar(name: String, unread: Int) {
    Box(Modifier.size(ChipDefaults.LargeIconSize).clip(CircleShape).background(Leaf), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifBlank { "•" }, fontWeight = FontWeight.Bold, color = Color.White)
        if (unread > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).size(14.dp).clip(CircleShape).background(Green),
                contentAlignment = Alignment.Center,
            ) { Text(if (unread > 9) "9+" else "$unread", fontSize = 8.sp, color = Color.White) }
        }
    }
}

private val QuickReplies = listOf("👍", "On my way", "Call you later", "Thanks! 💚")

@Composable
private fun Thread(peer: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by WatchStore.state.collectAsState()
    val chat = state?.chats?.firstOrNull { it.peer == peer }
    val messages = chat?.messages ?: emptyList()
    val list = rememberScalingLazyListState(initialCenterItemIndex = messages.size)

    LaunchedEffect(peer) {
        WatchStore.markReadLocally(peer)
        runCatching { PhoneLink.markRead(context, peer) }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        WatchStore.addPending(peer, t)
        scope.launch {
            val ok = runCatching { PhoneLink.reply(context, peer, t) }.getOrDefault(false)
            if (!ok) Toast.makeText(context, "Phone not reachable — reply not sent", Toast.LENGTH_SHORT).show()
        }
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        RemoteInput.getResultsFromIntent(data)?.getCharSequence("reply")?.toString()?.let(::send)
    }

    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(list) }, modifier = Modifier.background(Night)) {
        ScalingLazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text(chat?.name ?: "Chat", maxLines = 1, overflow = TextOverflow.Ellipsis, color = Green) } }
            if (chat?.messages == null) {
                item {
                    Text("Open this chat on your phone to see older messages.", fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center)
                }
            }
            items(messages, key = { it.id }) { m -> Bubble(m) }
            item {
                Chip(
                    onClick = {
                        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder("reply").setLabel("Reply").build()))
                        voice.launch(intent)
                    },
                    label = { Text("Reply") },
                    secondaryLabel = { Text("Speak, type or emoji") },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            items(QuickReplies) { q ->
                CompactChip(onClick = { send(q) }, label = { Text(q) }, colors = ChipDefaults.secondaryChipColors(backgroundColor = Card))
            }
        }
    }
}

@Composable
private fun Bubble(m: WatchMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.me) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 170.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (m.me) Leaf else Card)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(m.text, fontSize = 14.sp, color = Color.White)
            if (m.pending) Text("Sending…", fontSize = 10.sp, color = Muted)
        }
    }
}
