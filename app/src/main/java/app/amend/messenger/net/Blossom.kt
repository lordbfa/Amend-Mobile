package app.amend.messenger.net

import app.amend.messenger.core.crypto.Hashes
import app.amend.messenger.core.crypto.toHex
import app.amend.messenger.core.json.Json
import app.amend.messenger.core.json.str
import app.amend.messenger.core.nostr.Event
import app.amend.messenger.core.nostr.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Minimal Blossom client (BUD-01/02): content-addressed file servers used by
 * Nostr apps. Files are addressed by their SHA-256, so anyone downloading can
 * verify they got exactly the bytes that were uploaded. amend only ever
 * uploads already-encrypted files.
 */
class Blossom(private val client: OkHttpClient = defaultClient) {

    data class Blob(val url: String, val sha256: String, val size: Long)

    class BlossomException(message: String) : Exception(message)

    /** kind-24242 authorization event, sent base64-encoded in the Authorization header. */
    private fun authHeader(keys: KeyPair, sha256: String, verb: String): String {
        val ev = Event.sign(
            keys.secretKey, KIND_AUTH, "$verb amend media",
            listOf(listOf("t", verb), listOf("x", sha256), listOf("expiration", (Event.nowSeconds() + 600).toString())),
        )
        return "Nostr " + Base64.getEncoder().encodeToString(ev.toJson().toByteArray(Charsets.UTF_8))
    }

    /** Uploads to one server. Throws on failure. */
    suspend fun upload(server: String, data: ByteArray, keys: KeyPair): Blob = withContext(Dispatchers.IO) {
        val sha = Hashes.sha256(data).toHex()
        val base = server.trimEnd('/')
        val req = Request.Builder()
            .url("$base/upload")
            .put(data.toRequestBody("application/octet-stream".toMediaType()))
            .header("Authorization", authHeader(keys, sha, "upload"))
            .header("X-SHA-256", sha)
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw BlossomException("$base: HTTP ${resp.code} ${resp.header("X-Reason") ?: body.take(120)}")
            }
            val m = runCatching { Json.parseObject(body) }.getOrNull()
            val url = m?.str("url")?.takeIf { it.startsWith("https://") } ?: "$base/$sha"
            val returned = m?.str("sha256")
            if (returned != null && returned != sha) throw BlossomException("$base: server returned a different hash")
            Blob(url, sha, data.size.toLong())
        }
    }

    /**
     * Tries servers in order until [copies] uploads succeed (or the list runs out).
     * Returns at least one blob, or throws if every server failed.
     */
    suspend fun uploadAny(servers: List<String>, data: ByteArray, keys: KeyPair, copies: Int = 2): List<Blob> {
        val done = mutableListOf<Blob>()
        val errors = mutableListOf<String>()
        for (s in servers) {
            if (done.size >= copies) break
            try {
                done += upload(s, data, keys)
            } catch (e: Exception) {
                errors += e.message ?: s
            }
        }
        if (done.isEmpty()) throw BlossomException("upload failed on every server: " + errors.joinToString("; "))
        return done
    }

    /** Downloads and checks the SHA-256. Tries each URL in turn. */
    suspend fun download(urls: List<String>, expectedSha256: String?, maxBytes: Long = MAX_BYTES): ByteArray = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (url in urls.filter { it.startsWith("https://") }) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw BlossomException("HTTP ${resp.code}")
                    val body = resp.body ?: throw BlossomException("empty body")
                    if (body.contentLength() > maxBytes) throw BlossomException("file too large")
                    val out = ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > maxBytes) throw BlossomException("file too large")
                        }
                    }
                    val bytes = out.toByteArray()
                    if (expectedSha256 != null && Hashes.sha256(bytes).toHex() != expectedSha256) {
                        throw BlossomException("hash mismatch")
                    }
                    return@withContext bytes
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: BlossomException("no download URL")
    }

    companion object {
        const val KIND_AUTH = 24242
        const val MAX_BYTES = 60L * 1024 * 1024

        private val defaultClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        }

        /** Free public Blossom servers, best first. Verified by CI (BlobScanTest). */
        val DEFAULT_SERVERS = listOf(
            "https://blossom.primal.net",
            "https://blossom.band",
            "https://nostr.download",
            "https://cdn.nostrcheck.me",
            "https://blossom.oxtr.dev",
        )
    }
}
