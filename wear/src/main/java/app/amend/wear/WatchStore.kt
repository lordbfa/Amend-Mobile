package app.amend.wear

import android.content.Context
import android.net.Uri
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

data class WatchMessage(val id: String, val me: Boolean, val text: String, val at: Long, val pending: Boolean = false)

data class WatchChat(
    val peer: String,
    val name: String,
    val unread: Int,
    val last: String,
    val lastFromMe: Boolean,
    val time: Long,
    val messages: List<WatchMessage>?,
)

data class WatchState(
    val chats: List<WatchChat> = emptyList(),
    val trees: Int = 0,
    val points: Int = 0,
    val growing: Int = 0,
    val perTree: Int = 100,
    val streak: Int = 0,
    val stepsToday: Int = 0,
    val stepsGoal: Int = 8_000,
    val km: Double = 0.0,
    val stepsEnabled: Boolean = true,
    val updatedAt: Long = 0,
)

/** What the phone last told the watch, kept on the watch so the app and tile open instantly. */
object WatchStore {
    const val STATE_PATH = "/amend/state"
    private const val PREFS = "amend_watch"

    private val _state = MutableStateFlow<WatchState?>(null)
    val state: StateFlow<WatchState?> = _state.asStateFlow()

    fun load(context: Context): WatchState? {
        _state.value?.let { return it }
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", null) ?: return null
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("at", 0)
        return runCatching { parse(json, at) }.getOrNull()?.also { _state.value = it }
    }

    fun save(context: Context, json: String, at: Long) {
        val s = runCatching { parse(json, at) }.getOrNull() ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("json", json).putLong("at", at).apply()
        _state.value = s
        runCatching { TileService.getUpdater(context).requestUpdate(ForestTileService::class.java) }
    }

    /** Show a reply right away, before the phone confirms it in the next update. */
    fun addPending(peer: String, text: String) {
        _state.update { s ->
            s?.copy(chats = s.chats.map { c ->
                if (c.peer != peer) c else c.copy(
                    last = text, lastFromMe = true, unread = 0,
                    messages = (c.messages ?: emptyList()) + WatchMessage("pending-${System.nanoTime()}", true, text, System.currentTimeMillis() / 1000, pending = true),
                )
            })
        }
    }

    fun markReadLocally(peer: String) {
        _state.update { s -> s?.copy(chats = s.chats.map { if (it.peer == peer) it.copy(unread = 0) else it }) }
    }

    /** Read the latest state the phone published (used when the app opens). */
    suspend fun fetch(context: Context) {
        val items = Wearable.getDataClient(context).getDataItems(Uri.parse("wear://*$STATE_PATH")).await()
        try {
            items.forEach { item ->
                val map = DataMapItem.fromDataItem(item).dataMap
                map.getString("json")?.let { save(context, it, map.getLong("at")) }
            }
        } finally {
            items.release()
        }
    }

    private fun parse(json: String, at: Long): WatchState {
        val o = JSONObject(json)
        val forest = o.optJSONObject("forest") ?: JSONObject()
        val steps = o.optJSONObject("steps") ?: JSONObject()
        val arr = o.optJSONArray("chats")
        val chats = (0 until (arr?.length() ?: 0)).map { i ->
            val c = arr!!.getJSONObject(i)
            val msgs = c.optJSONArray("messages")?.let { m ->
                (0 until m.length()).map { j ->
                    val x = m.getJSONObject(j)
                    WatchMessage(x.optString("id"), x.optBoolean("me"), x.optString("t"), x.optLong("at"))
                }
            }
            WatchChat(
                peer = c.optString("peer"), name = c.optString("name"), unread = c.optInt("unread"),
                last = c.optString("last"), lastFromMe = c.optBoolean("lastFromMe"), time = c.optLong("time"), messages = msgs,
            )
        }
        return WatchState(
            chats = chats,
            trees = forest.optInt("trees"), points = forest.optInt("points"), growing = forest.optInt("growing"),
            perTree = forest.optInt("perTree", 100).coerceAtLeast(1), streak = forest.optInt("streak"),
            stepsToday = steps.optInt("today"), stepsGoal = steps.optInt("goal", 8_000).coerceAtLeast(1),
            km = steps.optDouble("km", 0.0), stepsEnabled = steps.optBoolean("enabled", true),
            updatedAt = at,
        )
    }
}

/** Talks to amend on the phone. */
object PhoneLink {
    private const val PHONE_CAPABILITY = "amend_phone"

    private suspend fun send(context: Context, path: String, body: JSONObject): Boolean {
        val nodes = runCatching {
            Wearable.getCapabilityClient(context).getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
        }.getOrDefault(emptySet())
        if (nodes.isEmpty()) return false
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        return nodes.map { n -> runCatching { Wearable.getMessageClient(context).sendMessage(n.id, path, bytes).await() }.isSuccess }.any { it }
    }

    suspend fun reply(context: Context, peer: String, text: String): Boolean =
        send(context, "/amend/send", JSONObject().put("peer", peer).put("text", text))

    suspend fun markRead(context: Context, peer: String) = send(context, "/amend/read", JSONObject().put("peer", peer))

    suspend fun refresh(context: Context) = send(context, "/amend/refresh", JSONObject())
}

/** Receives updates from the phone, even when the watch app is closed (keeps the tile fresh). */
class WatchDataService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { e ->
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == WatchStore.STATE_PATH) {
                val map = DataMapItem.fromDataItem(e.dataItem).dataMap
                map.getString("json")?.let { WatchStore.save(this, it, map.getLong("at")) }
            }
        }
    }
}
