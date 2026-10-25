package app.amend.messenger

import app.amend.messenger.core.nostr.Event
import app.amend.messenger.core.nostr.GiftWrap
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.core.nostr.Kinds
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.EcoRules
import app.amend.messenger.data.EcoState
import app.amend.messenger.eco.Challenges
import app.amend.messenger.eco.EcoPoints
import app.amend.messenger.eco.Footprint
import app.amend.messenger.eco.Garden
import app.amend.messenger.eco.GrowthStage
import app.amend.messenger.net.RelayIncoming
import app.amend.messenger.net.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EcoTest {
    private fun seed(i: Int) = "%064x".format(i)

    @Test
    fun treesGrowEveryHundredPoints() {
        var s = EcoState()
        var trees = 0
        repeat(9) {
            val (ns, ev) = EcoRules.addPoints(s, "2026-01-01", EcoPoints.CHALLENGE, false, ::seed, 0)
            s = ns
            if (ev.newTree != null) trees++
        }
        assertEquals(225, s.totalPoints)
        assertEquals(2, s.trees.size)
        assertEquals(2, trees)
        assertEquals(25, s.forest.pointsInCurrent)
    }

    @Test
    fun messagePointsAreCappedDaily() {
        var s = EcoState()
        repeat(100) { s = EcoRules.addPoints(s, "2026-01-01", 1, true, ::seed, 0).first }
        assertEquals(EcoPoints.DAILY_MESSAGE_CAP, s.totalPoints)
        // challenges still count after the message cap
        s = EcoRules.addPoints(s, "2026-01-01", EcoPoints.CHALLENGE, false, ::seed, 0).first
        assertEquals(EcoPoints.DAILY_MESSAGE_CAP + EcoPoints.CHALLENGE, s.totalPoints)
        // a new day resets the cap
        s = EcoRules.addPoints(s, "2026-01-02", 1, true, ::seed, 0).first
        assertEquals(EcoPoints.DAILY_MESSAGE_CAP + EcoPoints.CHALLENGE + 1, s.totalPoints)
    }

    @Test
    fun gardenIsTheSameForBothPeople() {
        val a = KeyPair.generate().publicKeyHex
        val b = KeyPair.generate().publicKeyHex
        assertEquals(Garden.forPair(a, b, 12), Garden.forPair(b, a, 12))
        assertEquals(GrowthStage.SEED, Garden.forPair(a, b, 0).stage)
        assertEquals(GrowthStage.MATURE, Garden.forPair(a, b, Garden.MESSAGES_TO_BLOOM).stage)
    }

    @Test
    fun challengesRotateDaily() {
        val d = java.time.LocalDate.of(2026, 10, 24)
        assertTrue(Challenges.forDay(d) != Challenges.forDay(d.plusDays(1)))
        assertEquals(30, Challenges.all.map { it.id }.toSet().size)
    }

    @Test
    fun profilePhotoTravelsInMetadata() {
        val pub = KeyPair.generate().publicKeyHex
        val photo = "data:image/webp;base64," + "A".repeat(5000)
        val json = app.amend.messenger.data.Profile(pub, "Faisal", "hi", "🌿", 120f, 1, photo).toMetadataJson()
        val m = app.amend.messenger.core.json.Json.parseObject(json)
        assertEquals(photo, m["amend_picture"])
        assertTrue("inline photos must not go in the standard picture field", m["picture"] == null)
        val back = app.amend.messenger.data.Profile.fromMetadata(pub, json, 1)!!
        assertEquals(photo, back.picture)
        assertEquals("Faisal", back.name)

        // https photos from other Nostr apps are accepted; junk and oversized data are not
        val url = app.amend.messenger.data.Profile.fromMetadata(pub, """{"name":"x","picture":"https://example.com/a.jpg"}""", 1)!!
        assertEquals("https://example.com/a.jpg", url.picture)
        val http = app.amend.messenger.data.Profile.fromMetadata(pub, """{"picture":"http://insecure/a.jpg"}""", 1)!!
        assertEquals("", http.picture)
        val huge = "data:image/webp;base64," + "A".repeat(30_000)
        val big = app.amend.messenger.data.Profile.fromMetadata(pub, """{"amend_picture":"$huge"}""", 1)!!
        assertEquals("", big.picture)
    }

    @Test
    fun footprintIsSmallButPositive() {
        val g = Footprint.gramsCo2(1_000_000_000L) // 1 GB
        assertEquals(13.5, g, 0.001)
        assertTrue(Footprint.formatGrams(0.005).endsWith("mg"))
        assertTrue(Footprint.formatGrams(0.0005).endsWith("µg"))
    }
}

/**
 * End-to-end test against real public relays. Skipped unless run with
 * -Damend.liveRelays=true (the CI workflow does this in a separate step).
 */
class LiveRelayTest {
    private val live = System.getProperty("amend.liveRelays") == "true"

    private suspend fun <T> poll(timeoutMs: Long, check: () -> T?): T? = withTimeoutOrNull(timeoutMs) {
        var value = check()
        while (value == null) {
            delay(500)
            value = check()
        }
        value
    }

    private fun report(line: String) {
        println("RELAY-REPORT: $line")
        File("build").mkdirs()
        File("build/relay-report.txt").appendText(line + "\n")
    }

    @Test
    fun eachDefaultRelayAcceptsAndReturnsGiftWraps(): Unit = runBlocking {
        assumeTrue(live)
        val alice = KeyPair.generate()
        val bob = KeyPair.generate()
        var working = 0
        for (url in RelayPool.DEFAULT_RELAYS) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val pool = RelayPool(scope, authSigner = { c, u ->
                Event.sign(bob.secretKey, Kinds.CLIENT_AUTH, "", listOf(listOf("relay", u), listOf("challenge", c)))
            })
            pool.setRelays(listOf(url))
            pool.connect()
            val rumor = GiftWrap.rumor(alice.publicKeyHex, Kinds.CHAT_MESSAGE, "relay check $url", listOf(listOf("p", bob.publicKeyHex)))
            val wrap = GiftWrap.wrap(rumor, alice, bob.publicKeyHex)
            val published = pool.publish(wrap).firstOrNull()
            pool.subscribe("check", listOf(mapOf("kinds" to listOf(Kinds.GIFT_WRAP), "#p" to listOf(bob.publicKeyHex))))
            val got = withTimeoutOrNull(15_000) {
                pool.incoming.filterIsInstance<RelayIncoming.EventMsg>().first { it.event.id == wrap.id }
            }
            val ok = published?.accepted == true && got != null
            if (ok) working++
            report("$url publish=${published?.accepted}(${published?.message}) read=${got != null}")
            pool.disconnect()
            scope.cancel()
        }
        report("working relays: $working/${RelayPool.DEFAULT_RELAYS.size}")
        assertTrue("at least two default relays must work", working >= 2)
    }

    @Test
    fun aliceSendsBobAMessageThroughTheNetwork(): Unit = runBlocking {
        assumeTrue(live)
        val dirA = Files.createTempDirectory("alice").toFile()
        val dirB = Files.createTempDirectory("bob").toFile()
        val alice = AmendRepository(dirA, KeyPair.generate())
        val bob = AmendRepository(dirB, KeyPair.generate())
        bob.goOnline()
        alice.goOnline()
        assertTrue(alice.awaitOnline(20_000))
        assertTrue(bob.awaitOnline(20_000))
        delay(2_000)

        val photo = "data:image/webp;base64,UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoBAAEAAwA0JaQAA3AA/vuUAAA="
        alice.updateProfile("Alice", "", "🌸", -1f, photo)
        alice.addContact(bob.me, "Bob")
        val sent = alice.sendText(bob.me, "Hello from the forest 🌳")
        report("alice→bob accepted by ${sent.relaysAccepted} relays")

        val received = poll(30_000) {
            bob.conversations.value[alice.me]?.messages?.firstOrNull { it.text == "Hello from the forest 🌳" }
        }
        report("bob received: ${received != null}")
        assertNotNull("Bob should receive Alice's message", received)
        assertEquals(alice.me, received!!.peer)
        assertTrue(!received.fromMe)

        // reaction round trip
        bob.react(alice.me, received.id, "💚")
        val reacted = poll(20_000) {
            alice.conversations.value[bob.me]?.messages?.firstOrNull { it.id == received.id }
                ?.takeIf { it.reactions[bob.me] == "💚" }
        }
        report("reaction received: ${reacted != null}")

        // Bob fetches Alice's public profile, photo included
        val profile = poll(20_000) { bob.profiles.value[alice.me]?.takeIf { it.picture == photo } }
        report("profile photo received: ${profile != null}")
        assertNotNull("Bob should see Alice's profile photo", profile)

        // SOS: start with a location, a live update, then stop
        val recipients = alice.sosRecipients(emptySet())
        assertTrue("Bob is an SOS recipient", bob.me in recipients)
        val loc1 = app.amend.messenger.data.SosLocation(60.192059, 24.945831, 15f, 0)
        val loc2 = app.amend.messenger.data.SosLocation(60.193000, 24.946500, 10f, 0)
        alice.sendSos(app.amend.messenger.data.SosPhase.START, loc1, recipients)
        val started = poll(30_000) { bob.incomingSos.value[alice.me]?.takeIf { it.active && it.location != null } }
        report("sos start received: ${started != null}")
        assertNotNull("Bob should get Alice's SOS", started)
        delay(1_500) // created_at has 1 s resolution
        alice.sendSos(app.amend.messenger.data.SosPhase.UPDATE, loc2, recipients)
        val moved = poll(30_000) { bob.incomingSos.value[alice.me]?.takeIf { kotlin.math.abs(it.location!!.lat - 60.193) < 1e-5 } }
        report("sos live update received: ${moved != null}")
        assertNotNull("Bob should see the updated location", moved)
        delay(1_500)
        alice.sendSos(app.amend.messenger.data.SosPhase.STOP, null, recipients)
        val stopped = poll(30_000) { bob.incomingSos.value[alice.me]?.takeIf { !it.active } }
        report("sos stop received: ${stopped != null}")
        assertNotNull("Bob should see that Alice is safe", stopped)

        // Voice message: encrypted upload to Blossom, file message, Bob downloads + decrypts
        val voice = ByteArray(40_000) { (it * 31 % 251).toByte() }
        val wave = ByteArray(64) { (it * 4).toByte() }
        val mediaMsg = runCatching {
            alice.sendMedia(bob.me, voice, app.amend.messenger.data.MediaKind.VOICE, "audio/mp4", 3_200, wave)
        }.onFailure { report("media upload failed: ${it.message}") }.getOrNull()
        report("voice uploaded: ${mediaMsg != null} relays=${mediaMsg?.relaysAccepted}")
        assertNotNull("Alice should upload the voice message", mediaMsg)
        val gotVoice = poll(30_000) {
            bob.conversations.value[alice.me]?.messages?.firstOrNull { it.type == app.amend.messenger.data.MessageType.VOICE }
        }
        report("voice message received: ${gotVoice != null}")
        assertNotNull("Bob should get the voice message", gotVoice)
        val info = app.amend.messenger.data.MediaInfo.fromJson(gotVoice!!.extra)!!
        assertEquals(3_200L, info.durationMs)
        assertEquals(64, info.waveform.size)
        val file = bob.fetchMedia(info)
        assertTrue("decrypted voice matches", file.readBytes().contentEquals(voice))
        report("voice downloaded+decrypted from ${info.urls.size} server(s): ok")
        alice.goOffline()
        bob.goOffline()
        dirA.deleteRecursively()
        dirB.deleteRecursively()
    }
}
