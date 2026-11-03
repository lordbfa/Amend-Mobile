package app.amend.messenger.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI

/**
 * Optional Tor routing through Orbot (the Tor Project's Android app), so relays, file
 * servers and the weather service never see your IP address.
 *
 * Every amend HTTP/WebSocket client uses this selector. When Tor is on it returns ONLY the
 * Orbot SOCKS proxy — there is no direct fallback, so if Orbot isn't running connections
 * fail instead of leaking your address ("fail closed"). OkHttp hands the host name to the
 * SOCKS proxy unresolved, so DNS lookups go through Tor as well.
 *
 * Calls are the exception: Tor carries TCP only, so voice/video still connect directly
 * (through Cloudflare TURN when needed).
 */
object TorProxy : ProxySelector() {
    const val ORBOT_PACKAGE = "org.torproject.android"
    private const val HOST = "127.0.0.1"
    private const val PORT = 9050

    @Volatile var enabled: Boolean = false

    private val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress(HOST, PORT))

    override fun select(uri: URI?): List<Proxy> = if (enabled) listOf(socks) else listOf(Proxy.NO_PROXY)

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        // fail closed: never fall back to a direct connection
    }

    /** Whether Orbot's SOCKS port answers (call off the main thread). */
    fun orbotRunning(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(HOST, PORT), 700); true }
    }.getOrDefault(false)
}
