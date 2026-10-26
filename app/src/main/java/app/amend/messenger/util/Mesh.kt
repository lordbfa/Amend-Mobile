package app.amend.messenger.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.amend.messenger.AmendApp
import app.amend.messenger.core.crypto.Rand
import app.amend.messenger.core.crypto.toHex
import app.amend.messenger.core.json.Json
import app.amend.messenger.core.json.str
import app.amend.messenger.core.nostr.Event
import app.amend.messenger.core.nostr.Kinds
import app.amend.messenger.data.AmendRepository
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Store-and-forward pool of signed, end-to-end encrypted gift wraps. Whatever a
 * phone holds it offers to every amend phone it meets, so a message can hop
 * from phone to phone until it reaches its recipient — or a phone with
 * internet, which uploads it to the relays.
 */
class MeshStore(private val file: File) {
    class Entry(val json: String, val receivedAt: Long, var needsRelay: Boolean)

    private val entries = LinkedHashMap<String, Entry>()

    init {
        runCatching {
            if (file.exists()) {
                Json.parseArray(file.readText()).forEach { e ->
                    @Suppress("UNCHECKED_CAST")
                    val m = e as? Map<String, Any?> ?: return@forEach
                    val id = m.str("id") ?: return@forEach
                    entries[id] = Entry(m.str("e") ?: return@forEach, (m["at"] as? Number)?.toLong() ?: 0, m["relay"] == true)
                }
            }
        }
        prune()
    }

    @Synchronized fun has(id: String) = entries.containsKey(id)
    @Synchronized fun ids(limit: Int = 400): List<String> = entries.keys.toList().takeLast(limit)
    @Synchronized fun get(id: String): String? = entries[id]?.json
    @Synchronized fun size() = entries.size
    @Synchronized fun pendingRelay(): List<Pair<String, String>> = entries.filter { it.value.needsRelay }.map { it.key to it.value.json }
    @Synchronized fun markRelayed(id: String) { entries[id]?.needsRelay = false }

    /** Returns true if it was new. */
    @Synchronized fun add(id: String, json: String, needsRelay: Boolean): Boolean {
        if (entries.containsKey(id)) return false
        if (json.length > MAX_EVENT_BYTES) return false
        entries[id] = Entry(json, System.currentTimeMillis(), needsRelay)
        prune()
        return true
    }

    @Synchronized private fun prune() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        entries.entries.removeAll { it.value.receivedAt < cutoff }
        while (entries.size > MAX_EVENTS) entries.remove(entries.keys.first())
    }

    @Synchronized fun save() {
        runCatching {
            val list = entries.map { (id, e) -> linkedMapOf("id" to id, "e" to e.json, "at" to e.receivedAt, "relay" to e.needsRelay) }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Json.write(list))
            tmp.renameTo(file)
        }
    }

    companion object {
        const val MAX_EVENTS = 1500
        const val MAX_AGE_MS = 4L * 24 * 3600 * 1000
        const val MAX_EVENT_BYTES = 28_000 // fits one Nearby bytes payload
    }
}

data class MeshUi(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val peers: Int = 0,
    val holding: Int = 0,
    val delivered: Int = 0,
    val bridged: Int = 0,
    val error: String? = null,
)

/**
 * Offline mesh over Bluetooth / BLE / Wi-Fi Direct (Google Nearby Connections,
 * P2P cluster). Works with no internet at all — floods, cyclones, shutdowns.
 *
 * Nothing private leaks: phones announce a random name (not your amend ID), and
 * only gift wraps travel, which only their recipient can open.
 */
@SuppressLint("StaticFieldLeak") // application context only
object Mesh {
    private const val SERVICE_ID = "app.amend.mesh.v1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var context: Context
    private var client: ConnectionsClient? = null
    private var store: MeshStore? = null
    private var saveJob: Job? = null
    private var bridgeJob: Job? = null
    private val localName = "amend-" + Rand.bytes(3).toHex()
    private val connected = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val _ui = MutableStateFlow(MeshUi())
    val ui: StateFlow<MeshUi> = _ui.asStateFlow()

    private fun prefs(c: Context) = c.getSharedPreferences("amend_mesh", Context.MODE_PRIVATE)
    fun isEnabled(c: Context) = prefs(c).getBoolean("enabled", false)

    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION,
        )
        Build.VERSION.SDK_INT >= 31 -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermissions(c: Context) = permissions().all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

    private fun repo(): AmendRepository? = (context.applicationContext as? AmendApp)?.repository?.value

    /** Called once at app start: hooks the repository so outgoing messages also enter the mesh. */
    fun init(c: Context) {
        context = c.applicationContext
        if (store == null) store = MeshStore(File(context.filesDir, "mesh.json"))
        _ui.update { it.copy(enabled = isEnabled(context), holding = store?.size() ?: 0) }
    }

    fun attach(repository: AmendRepository) {
        repository.meshOutbox = if (isEnabled(context)) { ev -> offerOwn(ev) } else null
    }

    /** Turn the mesh on or off. Needs the nearby-devices permissions when turning on. */
    fun setEnabled(c: Context, on: Boolean) {
        init(c)
        prefs(c).edit().putBoolean("enabled", on).apply()
        _ui.update { it.copy(enabled = on, error = null) }
        repo()?.let { attach(it) }
        if (on) MeshService.start(c) else MeshService.stop(c)
    }

    /** From MainActivity.onStart: resume the mesh if the user left it on. */
    fun resumeIfEnabled(c: Context) {
        init(c)
        if (isEnabled(c) && hasPermissions(c) && !_ui.value.running) MeshService.start(c)
    }

    // ------------------------------------------------------------------ radio

    internal fun startRadio() {
        if (_ui.value.running) return
        if (!hasPermissions(context)) {
            _ui.update { it.copy(error = "Allow “Nearby devices” for amend") }
            return
        }
        val c = Nearby.getConnectionsClient(context)
        client = c
        val strategy = Strategy.P2P_CLUSTER
        c.startAdvertising(localName, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { e -> _ui.update { it.copy(error = "Advertising: ${e.message}") } }
        c.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { e -> _ui.update { it.copy(error = "Discovery: ${e.message}") } }
        _ui.update { it.copy(running = true, error = null) }
        bridgeJob?.cancel()
        bridgeJob = scope.launch {
            // every 30 s: hand what we carry to the internet (if we have it) and re-offer to neighbours
            while (isActive) {
                bridge()
                connected.forEach { sendHave(it) }
                delay(30_000)
            }
        }
    }

    internal fun stopRadio() {
        bridgeJob?.cancel()
        client?.let { c -> runCatching { c.stopAdvertising(); c.stopDiscovery(); c.stopAllEndpoints() } }
        client = null
        connected.clear()
        pending.clear()
        store?.save()
        _ui.update { it.copy(running = false, peers = 0) }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID || endpointId in connected || !pending.add(endpointId)) return
            // both phones see each other: only the one with the "smaller" name dials, to avoid two links
            if (localName > info.endpointName) { pending.remove(endpointId); return }
            client?.requestConnection(localName, endpointId, lifecycle)?.addOnFailureListener { pending.remove(endpointId) }
        }

        override fun onEndpointLost(endpointId: String) { pending.remove(endpointId) }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // No pairing code needed: everything that travels is signed and encrypted end to end.
            client?.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pending.remove(endpointId)
            if (result.status.isSuccess) {
                connected.add(endpointId)
                _ui.update { it.copy(peers = connected.size) }
                sendHave(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
            _ui.update { it.copy(peers = connected.size) }
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            scope.launch { onFrame(endpointId, bytes.toString(Charsets.UTF_8)) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    // ------------------------------------------------------------------ protocol

    private fun send(endpointId: String, frame: Map<String, Any?>) {
        val bytes = Json.write(frame).toByteArray(Charsets.UTF_8)
        if (bytes.size > 32_000) return
        client?.sendPayload(endpointId, Payload.fromBytes(bytes))
    }

    private fun sendHave(endpointId: String) {
        val ids = store?.ids() ?: return
        if (ids.isNotEmpty()) send(endpointId, linkedMapOf("t" to "have", "ids" to ids))
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun onFrame(from: String, text: String) {
        val m = runCatching { Json.parseObject(text) }.getOrNull() ?: return
        val s = store ?: return
        when (m.str("t")) {
            "have" -> {
                val want = (m["ids"] as? List<Any?>).orEmpty().mapNotNull { it as? String }.filter { it.length == 64 && !s.has(it) }
                if (want.isNotEmpty()) send(from, linkedMapOf("t" to "want", "ids" to want.take(200)))
            }
            "want" -> (m["ids"] as? List<Any?>).orEmpty().mapNotNull { it as? String }.take(200).forEach { id ->
                s.get(id)?.let { json -> send(from, linkedMapOf("t" to "ev", "e" to json)) }
            }
            "ev" -> {
                val json = m.str("e") ?: return
                val ev = runCatching { Event.fromJson(json) }.getOrNull() ?: return
                if (ev.kind != Kinds.GIFT_WRAP || !ev.isValid()) return
                if (!s.add(ev.id, json, needsRelay = true)) return
                _ui.update { it.copy(holding = s.size()) }
                scheduleSave()
                // for us? open it like any message from the relays
                val r = repo()
                if (r != null && ev.tagValues("p").contains(r.me)) {
                    r.injectMeshEvent(ev)
                    _ui.update { it.copy(delivered = it.delivered + 1) }
                }
                // tell the other neighbours we have something new
                connected.filter { it != from }.forEach { peer -> send(peer, linkedMapOf("t" to "have", "ids" to listOf(ev.id))) }
                bridge()
            }
        }
    }

    /** Our own outgoing gift wrap: keep it, offer it to neighbours, upload it later if the relays missed it. */
    private fun offerOwn(ev: Event) {
        val s = store ?: return
        if (!s.add(ev.id, ev.toJson(), needsRelay = true)) return
        _ui.update { it.copy(holding = s.size()) }
        scheduleSave()
        connected.forEach { send(it, linkedMapOf("t" to "have", "ids" to listOf(ev.id))) }
        scope.launch { delay(5_000); bridge() }
    }

    /** With internet: upload everything carried for others (already signed, so any phone can). */
    private suspend fun bridge() {
        val r = repo() ?: return
        val s = store ?: return
        if (!r.isOnline()) return
        var n = 0
        s.pendingRelay().take(100).forEach { (id, json) ->
            val ev = runCatching { Event.fromJson(json) }.getOrNull() ?: return@forEach
            if (r.publishRaw(ev) > 0) {
                s.markRelayed(id)
                n++
            }
        }
        if (n > 0) {
            _ui.update { it.copy(bridged = it.bridged + n) }
            scheduleSave()
        }
    }

    private fun scheduleSave() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch { delay(3_000); store?.save() }
    }
}

/** Keeps Bluetooth / Wi-Fi Direct scanning alive in the background while the mesh is on. */
class MeshService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, Notifications.MESH_ID, Notifications.meshRunning(this), type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        Mesh.init(this)
        Mesh.startRadio()
        return START_STICKY
    }

    override fun onDestroy() {
        Mesh.stopRadio()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshService::class.java))
        }
    }
}
