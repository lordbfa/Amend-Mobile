package app.amend.messenger.util

import android.content.Context
import android.util.Log
import app.amend.messenger.AmendApp
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.ChatMessage
import app.amend.messenger.data.MessageType
import app.amend.messenger.eco.EcoPoints
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the amend watch app up to date. The private key and all networking stay on
 * the phone: the phone sends the watch a small summary (recent chats, forest, steps)
 * over the Wear OS data layer, and the watch sends replies back for the phone to
 * encrypt and send. Nothing is sent unless a watch with amend installed is nearby.
 */
object WearSync {
    const val STATE_PATH = "/amend/state"
    const val SEND_PATH = "/amend/send"
    const val READ_PATH = "/amend/read"
    const val REFRESH_PATH = "/amend/refresh"
    private const val WATCH_CAPABILITY = "amend_watch"
    private const val TAG = "WearSync"

    private const val MAX_CHATS = 15
    private const val THREADS = 8
    private const val MESSAGES_PER_THREAD = 25

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @OptIn(FlowPreview::class)
    fun attach(context: Context, repo: AmendRepository) {
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            combine(repo.conversations, repo.profiles, repo.eco, StepTracker.ui) { _, _, _, _ -> Unit }
                .debounce(1500)
                .collect { runCatching { push(app, repo) }.onFailure { Log.w(TAG, "push failed", it) } }
        }
    }

    /** Send the summary now (when the watch asks for it). */
    fun refresh(context: Context) {
        val repo = (context.applicationContext as? AmendApp)?.repository?.value ?: return
        scope.launch { runCatching { push(context.applicationContext, repo, force = true) } }
    }

    private fun watchNearby(context: Context): Boolean = runCatching {
        Tasks.await(Wearable.getCapabilityClient(context).getCapability(WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE))
            .nodes.isNotEmpty()
    }.getOrDefault(false)

    private fun push(context: Context, repo: AmendRepository, force: Boolean = false) {
        if (!force && !watchNearby(context)) return
        val json = summary(repo)
        val req = PutDataMapRequest.create(STATE_PATH).apply {
            dataMap.putString("json", json)
            dataMap.putLong("at", System.currentTimeMillis()) // makes every push a change
        }.asPutDataRequest().setUrgent()
        Tasks.await(Wearable.getDataClient(context).putDataItem(req))
    }

    private fun preview(m: ChatMessage): String = when (m.type) {
        MessageType.VOICE -> "🎤 Voice message"
        MessageType.VIDEO -> "🎬 Video message"
        MessageType.FILE -> "📷 Photo"
        MessageType.ECO_CARD -> "🌱 Eco challenge"
        MessageType.SOS -> if (m.cardId == "stop") "✅ SOS ended" else "🆘 SOS"
        MessageType.CALL -> "📞 Call"
        MessageType.ECO_PROOF -> "🌳 A tree they planted"
        MessageType.TEXT -> m.text
    }.take(300)

    private fun summary(repo: AmendRepository): String {
        val chats = repo.conversations.value.values
            .filter { it.messages.isNotEmpty() && repo.isAccepted(it.peer) }
            .sortedByDescending { it.lastActivity }
            .take(MAX_CHATS)
        val eco = repo.eco.value
        val steps = StepTracker.ui.value
        val trees = eco.trees.size
        val inCurrent = (eco.totalPoints - trees * EcoPoints.PER_TREE).coerceIn(0, EcoPoints.PER_TREE)
        val root = JSONObject()
            .put("v", 1)
            .put("forest", JSONObject().put("trees", trees).put("points", eco.totalPoints)
                .put("growing", inCurrent).put("perTree", EcoPoints.PER_TREE).put("streak", eco.streak))
            .put("steps", JSONObject().put("today", steps.today).put("goal", steps.goal)
                .put("km", steps.todayKm).put("enabled", steps.enabled && steps.hasSensor))
        val arr = JSONArray()
        chats.forEachIndexed { i, c ->
            val last = c.lastMessage!!
            val o = JSONObject()
                .put("peer", c.peer).put("name", repo.displayName(c.peer)).put("unread", c.unread)
                .put("last", preview(last)).put("lastFromMe", last.fromMe).put("time", last.createdAt)
            if (i < THREADS) {
                val msgs = JSONArray()
                c.messages.takeLast(MESSAGES_PER_THREAD).forEach { m ->
                    msgs.put(JSONObject().put("id", m.id).put("me", m.fromMe).put("t", preview(m)).put("at", m.createdAt)
                        .put("s", m.status.name))
                }
                o.put("messages", msgs)
            }
            arr.put(o)
        }
        root.put("chats", arr)
        return root.toString()
    }

    /** A reply or "read" from the watch. Runs in the listener service's background thread. */
    fun handle(context: Context, event: MessageEvent) {
        val app = context.applicationContext as? AmendApp ?: return
        val repo = app.repository.value ?: return
        val body = runCatching { JSONObject(String(event.data, Charsets.UTF_8)) }.getOrNull()
        when (event.path) {
            SEND_PATH -> {
                val peer = body?.optString("peer").orEmpty()
                val text = body?.optString("text").orEmpty().trim()
                if (peer.isBlank() || text.isBlank() || repo.conversations.value[peer] == null) return
                runBlocking { withTimeoutOrNull(25_000) { runCatching { repo.sendText(peer, text) } } }
                repo.markRead(peer)
            }
            READ_PATH -> body?.optString("peer")?.takeIf { it.isNotBlank() }?.let { repo.markRead(it) }
            REFRESH_PATH -> runCatching { push(app, repo, force = true) }
        }
    }
}

/** Receives messages from the watch, even when amend isn't open on the phone. */
class WearMessageService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        WearSync.handle(this, event)
    }
}
