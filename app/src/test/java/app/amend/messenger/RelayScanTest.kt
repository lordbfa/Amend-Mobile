package app.amend.messenger

import app.amend.messenger.core.nostr.Event
import app.amend.messenger.core.nostr.GiftWrap
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.core.nostr.Kinds
import app.amend.messenger.net.RelayIncoming
import app.amend.messenger.net.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Probes candidate relays: can a fresh key publish a gift wrap, and can the
 * recipient (authenticating with NIP-42 if asked) read it back from a NEW connection?
 * Run with -Damend.relayScan=true. Writes build/relay-scan.txt.
 */
class RelayScanTest {
    private val candidates = listOf(
        "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://relay.0xchat.com",
        "wss://nostr.mom", "wss://relay.snort.social", "wss://offchain.pub", "wss://relay.nostr.band",
        "wss://nostr.oxtr.dev", "wss://relay.nostr.net", "wss://nostr-pub.wellorder.net", "wss://nostr.bitcoiner.social",
        "wss://relay.nsec.app", "wss://auth.nostr1.com", "wss://nostr21.com", "wss://relay.lumina.rocks",
        "wss://nostr.fmt.wiz.biz", "wss://relay.nostr.bg", "wss://nostr.einundzwanzig.space", "wss://relay.mostr.pub",
        "wss://nostr.data.haus", "wss://relay.nostromo.social", "wss://nostr.lu.ke", "wss://relay.orangepill.ovh",
    )

    private data class Result(val url: String, val published: Boolean, val readBack: Boolean, val ms: Long, val note: String)

    private suspend fun probe(url: String): Result {
        val alice = KeyPair.generate()
        val bob = KeyPair.generate()
        val signer = { kp: KeyPair -> { c: String, u: String -> Event.sign(kp.secretKey, Kinds.CLIENT_AUTH, "", listOf(listOf("relay", u), listOf("challenge", c))) } }
        val start = System.currentTimeMillis()
        // 1) Alice publishes
        val s1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pubPool = RelayPool(s1, signer(alice)).apply { setRelays(listOf(url)); connect() }
        val rumor = GiftWrap.rumor(alice.publicKeyHex, Kinds.CHAT_MESSAGE, "scan", listOf(listOf("p", bob.publicKeyHex)))
        val wrap = GiftWrap.wrap(rumor, alice, bob.publicKeyHex)
        val pub = pubPool.publish(wrap).firstOrNull()
        pubPool.disconnect(); s1.cancel()
        if (pub?.accepted != true) return Result(url, false, false, System.currentTimeMillis() - start, pub?.message ?: "no result")
        // 2) Bob reads from a fresh connection (like another phone)
        val s2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val readPool = RelayPool(s2, signer(bob)).apply { setRelays(listOf(url)); connect() }
        readPool.subscribe("scan", listOf(mapOf("kinds" to listOf(Kinds.GIFT_WRAP), "#p" to listOf(bob.publicKeyHex))))
        val got = withTimeoutOrNull(15_000) {
            readPool.incoming.filterIsInstance<RelayIncoming.EventMsg>().first { it.event.id == wrap.id }
        }
        val ms = System.currentTimeMillis() - start
        readPool.disconnect(); s2.cancel()
        return Result(url, true, got != null, ms, if (got == null) "not returned" else "ok")
    }

    @Test
    fun scanCandidateRelays(): Unit = runBlocking {
        assumeTrue(System.getProperty("amend.relayScan") == "true")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val results = candidates.map { url ->
            scope.async { runCatching { probe(url) }.getOrElse { Result(url, false, false, 0, it.javaClass.simpleName) } }
        }.awaitAll()
        File("build").mkdirs()
        val out = File("build/relay-scan.txt")
        out.writeText("")
        results.sortedWith(compareByDescending<Result> { it.readBack }.thenBy { it.ms }).forEach {
            val line = "${if (it.readBack) "PASS" else "FAIL"} ${it.url} publish=${it.published} read=${it.readBack} ${it.ms}ms ${it.note}"
            println("RELAY-SCAN: $line")
            out.appendText(line + "\n")
        }
        scope.cancel()
    }
}
