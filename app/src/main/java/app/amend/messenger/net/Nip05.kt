package app.amend.messenger.net

import app.amend.messenger.core.crypto.isHex64
import app.amend.messenger.core.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * NIP-05: human-readable names like `faisal@getamend.app` instead of npub keys.
 * The domain publishes `/.well-known/nostr.json?name=faisal` → `{"names":{"faisal":"<hex>"}}`.
 * Looked up only when the user types an address or opens a profile; no redirects are followed
 * (NIP-05 forbids it), so a name can only be vouched for by its own domain.
 */
object Nip05 {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
    private val HOST = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
    private val NAME = Regex("^[a-z0-9._-]{1,64}$")
    /** address → (pubkey or "" for not found, checked at) */
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()

    /** "name@domain" (or just "domain", meaning "_@domain") → (name, domain), or null if malformed. */
    fun parse(address: String): Pair<String, String>? {
        val a = address.trim().lowercase().removePrefix("nostr:")
        val (name, domain) = if ("@" in a) a.substringBefore("@") to a.substringAfter("@") else "_" to a
        if (!NAME.matches(name) || !HOST.matches(domain)) return null
        return name to domain
    }

    /** The public key (hex) behind [address], or null. Results are cached for an hour. */
    suspend fun resolve(address: String): String? = withContext(Dispatchers.IO) {
        val (name, domain) = parse(address) ?: return@withContext null
        val key = "$name@$domain"
        cache[key]?.let { (pub, at) -> if (System.currentTimeMillis() - at < 3_600_000) return@withContext pub.ifEmpty { null } }
        val url = "https://$domain/.well-known/nostr.json?name=" + URLEncoder.encode(name, "UTF-8")
        val pub = runCatching {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val body = r.body?.string()?.take(200_000) ?: return@use null
                @Suppress("UNCHECKED_CAST")
                val names = Json.parseObject(body)["names"] as? Map<String, Any?>
                (names?.get(name) as? String)?.lowercase()?.takeIf { it.isHex64() }
            }
        }.getOrNull()
        cache[key] = (pub ?: "") to System.currentTimeMillis()
        pub
    }

    /** True when [address] really belongs to [pubkey] (the ✓ next to a name). */
    suspend fun verify(address: String, pubkey: String): Boolean = address.isNotBlank() && resolve(address) == pubkey.lowercase()

    /** Shown form: "_@domain" is just "domain". */
    fun display(address: String): String = address.trim().removePrefix("_@")
}
