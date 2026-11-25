package app.amend.messenger.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.amend.messenger.net.TorProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.torproject.jni.TorService

/**
 * Tor inside amend (Guardian Project's tor-android, the library Orbot is built on): no other app
 * to install. While it starts, [TorProxy] points at a closed port, so nothing leaves the phone
 * without Tor. Once a circuit is up, [TorProxy] uses its SOCKS port and [onReady] reconnects.
 */
object EmbeddedTor {
    sealed interface State {
        data object Off : State
        /** Bootstrapping, 0–100 %. [stuck]: no progress for a minute (Tor may be blocked here). */
        data class Starting(val percent: Int, val stuck: Boolean = false) : State
        data class On(val socksPort: Int) : State
        data class Failed(val why: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var service: TorService? = null
    @Volatile private var bound = false
    private var watcher: Job? = null
    /** Called (off the main thread) whenever Tor becomes ready, so connections can be made again. */
    @Volatile var onReady: (() -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TorService.LocalBinder)?.service
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            if (TorProxy.mode == TorProxy.Mode.BUILT_IN) {
                TorProxy.port = 1
                _state.value = State.Starting(0)
            }
        }
    }

    /** Starts built-in Tor (call when the mode is [TorProxy.Mode.BUILT_IN]). Safe to call again. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (!bound) {
            bound = runCatching { app.bindService(Intent(app, TorService::class.java), connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if (!bound) { _state.value = State.Failed("Tor couldn't be started on this phone"); return }
            _state.value = State.Starting(0)
        }
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            var wasOn = false
            var lastPercent = -1
            var lastChange = System.currentTimeMillis()
            while (true) {
                val s = service
                val port = s?.socksPort ?: -1
                val circuit = s?.let { runCatching { it.getInfo("status/circuit-established") }.getOrNull() } == "1"
                if (s != null && port > 0 && circuit) {
                    if (!wasOn || TorProxy.port != port) {
                        TorProxy.port = port
                        _state.value = State.On(port)
                        wasOn = true
                        runCatching { onReady?.invoke() }
                    }
                } else {
                    if (wasOn) TorProxy.port = 1
                    wasOn = false
                    val percent = s?.let { runCatching { it.getInfo("status/bootstrap-phase") }.getOrNull() }
                        ?.let { Regex("PROGRESS=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
                    if (percent != lastPercent) { lastPercent = percent; lastChange = System.currentTimeMillis() }
                    // still trying, but tell the user after a minute without progress
                    _state.value = State.Starting(percent, stuck = System.currentTimeMillis() - lastChange > 60_000)
                }
                delay(if (wasOn) 5_000 else 1_000)
            }
        }
    }

    /**
     * Asks the Tor Project whether a request through amend's proxy really comes out of Tor.
     * Returns the exit address on success, null if it doesn't (call off the main thread).
     */
    fun check(): String? = runCatching {
        val http = okhttp3.OkHttpClient.Builder().proxySelector(TorProxy)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        http.newCall(okhttp3.Request.Builder().url("https://check.torproject.org/api/ip").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val json = app.amend.messenger.core.json.Json.parseObject(body)
            if (json["IsTor"] == true) (json["IP"] as? String ?: "") else null
        }
    }.getOrNull()

    /** Stops built-in Tor (another mode was chosen). */
    @Synchronized
    fun stop(context: Context) {
        watcher?.cancel(); watcher = null
        if (bound) runCatching { context.applicationContext.unbindService(connection) }
        bound = false
        service = null
        _state.value = State.Off
    }
}
