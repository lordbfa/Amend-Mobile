package app.amend.messenger.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI

/**
 * Optional Tor routing, so relays, file servers and the weather service never see your IP
 * address. Tor runs either inside amend ([Mode.BUILT_IN], see util/EmbeddedTor) or in Orbot,
 * the Tor Project's Android app ([Mode.ORBOT]).
 *
 * Every amend HTTP/WebSocket client uses this selector. When Tor is on it returns ONLY the
 * Tor SOCKS proxy — there is no direct fallback, so while Tor isn't ready connections fail
 * instead of leaking your address ("fail closed"). OkHttp hands the host name to the SOCKS
 * proxy unresolved, so DNS lookups go through Tor as well.
 *
 * Calls are the exception: Tor carries TCP only, so voice/video still connect directly
 * (through Cloudflare TURN when needed).
 */
object TorProxy : ProxySelector() {
    const val ORBOT_PACKAGE = "org.torproject.android"
    private const val HOST = "127.0.0.1"
    const val ORBOT_PORT = 9050
    /** A port nothing listens on: used while built-in Tor is starting, so nothing goes out directly. */
    private const val CLOSED_PORT = 1

    enum class Mode(val tag: String) { OFF("off"), BUILT_IN("builtin"), ORBOT("orbot");
        companion object { fun of(tag: String?) = entries.firstOrNull { it.tag == tag } ?: OFF }
    }

    @Volatile var mode: Mode = Mode.OFF
        set(value) {
            field = value
            port = if (value == Mode.ORBOT) ORBOT_PORT else CLOSED_PORT
        }

    /** True when everything must go through Tor (built-in or Orbot). */
    val enabled: Boolean get() = mode != Mode.OFF

    /** The SOCKS port in use: Orbot's 9050, built-in Tor's port once it's ready, or a closed port. */
    @Volatile var port: Int = CLOSED_PORT

    /** The Tor SOCKS proxy, for clients that don't take a ProxySelector (map tiles). */
    val socks: Proxy get() = Proxy(Proxy.Type.SOCKS, InetSocketAddress(HOST, port))

    override fun select(uri: URI?): List<Proxy> = if (enabled) listOf(socks) else listOf(Proxy.NO_PROXY)

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        // fail closed: never fall back to a direct connection
    }

    /** Whether Orbot's SOCKS port answers (call off the main thread). */
    fun orbotRunning(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(HOST, ORBOT_PORT), 700); true }
    }.getOrDefault(false)
}
