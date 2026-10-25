package app.amend.messenger.net

import app.amend.messenger.core.nostr.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A set of relays used together. Messages are published to every relay and read
 * from every relay, so no single relay is a point of failure — this is what makes
 * amend decentralised: anyone can run a relay, and users choose their own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayPool(
    private val scope: CoroutineScope,
    private val authSigner: (challenge: String, relayUrl: String) -> Event?,
    private val onBytes: (sent: Long, received: Long) -> Unit = { _, _ -> },
    client: OkHttpClient? = null,
) {
    private val http = client ?: OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _incoming = MutableSharedFlow<RelayIncoming>(
        extraBufferCapacity = 2048,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val incoming: SharedFlow<RelayIncoming> = _incoming

    private val relays = ConcurrentHashMap<String, Relay>()
    private val _relayList = MutableStateFlow<List<Relay>>(emptyList())

    /** Live status of every relay, for the network visualisation. */
    val statuses: StateFlow<List<RelayStatus>> = _relayList
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyList())
            else combine(list.map { it.status }) { it.toList() }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val subscriptions = ConcurrentHashMap<String, List<Map<String, Any?>>>()
    @Volatile private var online = false

    fun urls(): List<String> = relays.keys.sorted()

    fun setRelays(urls: Collection<String>) {
        val wanted = urls.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
        (relays.keys - wanted).forEach { relays.remove(it)?.disconnect() }
        (wanted - relays.keys).forEach { url ->
            val r = Relay(url, http, scope, _incoming, authSigner, onBytes)
            subscriptions.forEach { (id, f) -> r.subscribe(id, f) }
            relays[url] = r
            if (online) r.connect()
        }
        _relayList.value = relays.values.sortedBy { it.url }
    }

    fun connect() {
        online = true
        relays.values.forEach { it.connect() }
    }

    fun disconnect() {
        online = false
        relays.values.forEach { it.disconnect() }
    }

    fun subscribe(id: String, filters: List<Map<String, Any?>>) {
        subscriptions[id] = filters
        relays.values.forEach { it.subscribe(id, filters) }
    }

    fun unsubscribe(id: String) {
        subscriptions.remove(id)
        relays.values.forEach { it.unsubscribe(id) }
    }

    /**
     * Publishes to all pool relays plus [extraRelays] (e.g. the recipient's inbox relays).
     * Temporary relays are connected just for this publish.
     */
    suspend fun publish(event: Event, extraRelays: Collection<String> = emptyList()): List<Relay.PublishResult> {
        val temp = extraRelays.map { normalize(it) }
            .filter { it.isNotEmpty() && !relays.containsKey(it) }
            .distinct()
            .map { Relay(it, http, scope, _incoming, authSigner, onBytes).also { r -> r.connect() } }
        val targets = relays.values.toList() + temp
        return try {
            targets.map { r -> scope.async { r.publish(event) } }.awaitAll()
        } finally {
            temp.forEach { it.disconnect() }
        }
    }

    fun onlineCount(): Int = relays.values.count { it.status.value.state == RelayState.ONLINE }

    companion object {
        fun normalize(url: String): String {
            var u = url.trim()
            if (u.isEmpty()) return ""
            if (!u.startsWith("wss://") && !u.startsWith("ws://")) u = "wss://$u"
            return u.trimEnd('/')
        }

        /**
         * Public relays verified by CI (RelayScanTest) to accept gift-wrapped private
         * messages AND return them to the recipient on a fresh connection.
         * Run by different operators, so no single one is a point of failure.
         */
        val DEFAULT_RELAYS = listOf(
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://nostr.mom",
            "wss://nostr-pub.wellorder.net",
            "wss://offchain.pub",
            "wss://auth.nostr1.com", // private-message inbox relay (NIP-42 auth)
        )

        /** The first defaults shipped in amend 2.0; installs still using them are upgraded. */
        val LEGACY_DEFAULTS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://relay.0xchat.com",
        )

        /** Every default set amend has shipped; an install still on one of them follows the new defaults. */
        val PREVIOUS_DEFAULTS = listOf(
            LEGACY_DEFAULTS,
            listOf("wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://relay.snort.social", "wss://offchain.pub", "wss://auth.nostr1.com"),
        )
    }
}

/** Collects every incoming event from the pool for side-effects. */
fun RelayPool.onEachEvent(scope: CoroutineScope, block: suspend (RelayIncoming) -> Unit) =
    incoming.onEach(block).launchIn(scope)
