package app.amend.messenger.net

import app.amend.messenger.core.json.Json
import app.amend.messenger.core.nostr.Event
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class RelayState { OFFLINE, CONNECTING, ONLINE, ERROR }

data class RelayStatus(
    val url: String,
    val state: RelayState = RelayState.OFFLINE,
    val latencyMs: Long? = null,
    val authenticated: Boolean = false,
    val lastError: String? = null,
    val eventsReceived: Int = 0,
)

sealed class RelayIncoming {
    data class EventMsg(val relay: String, val subscription: String, val event: Event) : RelayIncoming()
    data class EndOfStored(val relay: String, val subscription: String) : RelayIncoming()
    data class Closed(val relay: String, val subscription: String, val reason: String) : RelayIncoming()
    data class Notice(val relay: String, val message: String) : RelayIncoming()
}

/**
 * One WebSocket connection to one Nostr relay (NIP-01), with:
 *  - automatic reconnect with exponential back-off while [keepAlive] is set
 *  - re-sending of open subscriptions after a reconnect
 *  - NIP-42 authentication when the relay asks for it
 *  - byte counting, so the app can estimate its own data footprint
 */
class Relay(
    val url: String,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val incoming: MutableSharedFlow<RelayIncoming>,
    private val authSigner: (challenge: String, relayUrl: String) -> Event?,
    private val onBytes: (sent: Long, received: Long) -> Unit,
) {
    private val _status = MutableStateFlow(RelayStatus(url))
    val status: StateFlow<RelayStatus> = _status

    @Volatile private var socket: WebSocket? = null
    @Volatile private var keepAlive = false
    private var reconnectJob: Job? = null
    private var attempt = 0
    private var connectStartedAt = 0L

    private val subscriptions = ConcurrentHashMap<String, List<Map<String, Any?>>>()
    private val pendingPublishes = ConcurrentHashMap<String, Pair<String, CompletableDeferred<PublishResult>>>()
    private val pendingAuth = ConcurrentHashMap<String, Boolean>()

    data class PublishResult(val relay: String, val accepted: Boolean, val message: String)

    fun connect() {
        keepAlive = true
        if (socket != null) return
        openSocket()
    }

    fun disconnect() {
        keepAlive = false
        reconnectJob?.cancel()
        socket?.close(1000, "bye")
        socket = null
        _status.value = _status.value.copy(state = RelayState.OFFLINE, authenticated = false)
    }

    private fun openSocket() {
        _status.value = _status.value.copy(state = RelayState.CONNECTING)
        connectStartedAt = System.currentTimeMillis()
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, Listener())
    }

    private fun scheduleReconnect() {
        if (!keepAlive) return
        reconnectJob?.cancel()
        val delayMs = (1000L shl attempt.coerceAtMost(6)).coerceAtMost(60_000L)
        attempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            if (keepAlive && isActive) openSocket()
        }
    }

    private fun sendRaw(text: String): Boolean {
        val ws = socket ?: return false
        val ok = ws.send(text)
        if (ok) onBytes(text.toByteArray(Charsets.UTF_8).size.toLong(), 0)
        return ok
    }

    fun subscribe(id: String, filters: List<Map<String, Any?>>) {
        subscriptions[id] = filters
        if (_status.value.state == RelayState.ONLINE) sendReq(id, filters)
    }

    fun unsubscribe(id: String) {
        if (subscriptions.remove(id) != null) sendRaw(Json.write(listOf("CLOSE", id)))
    }

    private fun sendReq(id: String, filters: List<Map<String, Any?>>) {
        sendRaw(Json.write(listOf("REQ", id) + filters))
    }

    /** Publishes an event and waits (up to [timeoutMs]) for the relay's OK. */
    suspend fun publish(event: Event, timeoutMs: Long = 10_000): PublishResult {
        val json = Json.write(listOf("EVENT", event.toMap()))
        val deferred = CompletableDeferred<PublishResult>()
        pendingPublishes[event.id] = json to deferred
        if (_status.value.state == RelayState.ONLINE) sendRaw(json)
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pendingPublishes.remove(event.id)
        return result ?: PublishResult(url, false, "timeout")
    }

    private fun handleOpen() {
        attempt = 0
        _status.value = _status.value.copy(
            state = RelayState.ONLINE,
            latencyMs = System.currentTimeMillis() - connectStartedAt,
            lastError = null,
        )
        subscriptions.forEach { (id, filters) -> sendReq(id, filters) }
        pendingPublishes.values.forEach { (json, _) -> sendRaw(json) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleText(text: String) {
        onBytes(0, text.toByteArray(Charsets.UTF_8).size.toLong())
        val msg = try {
            Json.parseArray(text)
        } catch (e: Exception) {
            return
        }
        when (msg.getOrNull(0)) {
            "EVENT" -> {
                val sub = msg.getOrNull(1) as? String ?: return
                val ev = (msg.getOrNull(2) as? Map<String, Any?>)?.let {
                    try { Event.fromMap(it) } catch (e: Exception) { null }
                } ?: return
                _status.value = _status.value.copy(eventsReceived = _status.value.eventsReceived + 1)
                incoming.tryEmit(RelayIncoming.EventMsg(url, sub, ev))
            }
            "EOSE" -> (msg.getOrNull(1) as? String)?.let { incoming.tryEmit(RelayIncoming.EndOfStored(url, it)) }
            "OK" -> {
                val id = msg.getOrNull(1) as? String ?: return
                val accepted = msg.getOrNull(2) == true
                val message = msg.getOrNull(3) as? String ?: ""
                if (pendingAuth.remove(id) != null) {
                    if (accepted) {
                        _status.value = _status.value.copy(authenticated = true)
                        // Now that we're authenticated, retry everything that may have been refused.
                        subscriptions.forEach { (sid, filters) -> sendReq(sid, filters) }
                        pendingPublishes.values.forEach { (json, _) -> sendRaw(json) }
                    }
                    return
                }
                if (!accepted && message.startsWith("auth-required") && !_status.value.authenticated) {
                    return // will be retried after AUTH succeeds
                }
                pendingPublishes[id]?.second?.complete(PublishResult(url, accepted || message.startsWith("duplicate"), message))
            }
            "AUTH" -> {
                val challenge = msg.getOrNull(1) as? String ?: return
                val authEvent = authSigner(challenge, url) ?: return
                pendingAuth[authEvent.id] = true
                sendRaw(Json.write(listOf("AUTH", authEvent.toMap())))
            }
            "CLOSED" -> {
                val sub = msg.getOrNull(1) as? String ?: return
                val reason = msg.getOrNull(2) as? String ?: ""
                incoming.tryEmit(RelayIncoming.Closed(url, sub, reason))
            }
            "NOTICE" -> (msg.getOrNull(1) as? String)?.let { incoming.tryEmit(RelayIncoming.Notice(url, it)) }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket === socket) handleOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket === socket) handleText(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            socket = null
            _status.value = _status.value.copy(state = RelayState.OFFLINE, authenticated = false)
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            socket = null
            _status.value = _status.value.copy(
                state = RelayState.ERROR,
                authenticated = false,
                lastError = t.message ?: t.javaClass.simpleName,
            )
            scheduleReconnect()
        }
    }

    companion object {
        private val counter = AtomicLong()
        fun newSubscriptionId(prefix: String) = "$prefix-${counter.incrementAndGet()}"
    }
}
