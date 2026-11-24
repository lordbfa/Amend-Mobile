package app.amend.messenger

import app.amend.messenger.core.json.Json
import app.amend.messenger.core.nostr.Event
import app.amend.messenger.core.nostr.GiftWrap
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.core.nostr.Kinds
import app.amend.messenger.core.ratchet.Ratchet
import app.amend.messenger.core.ratchet.RatchetSealer
import app.amend.messenger.core.ratchet.RatchetStore
import app.amend.messenger.data.AmendRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Forward-secret chats (docs/RATCHET.md): two (and three) phones, with gift wraps handed over
 * directly (the offline-mesh path), so no relay is needed.
 */
class ForwardSecrecyTest {
    /** Stands in for the Android Keystore key in JVM tests. */
    class TestSealer : RatchetSealer {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun seal(plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
            return c.iv + c.doFinal(plain)
        }
        override fun open(sealed: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
            return c.doFinal(sealed, 12, sealed.size - 12)
        }
    }

    private fun dir(name: String): File = Files.createTempDirectory(name).toFile()

    private class Phone(name: String, val keys: KeyPair = KeyPair.generate(), d: File = Files.createTempDirectory(name).toFile(), val sealer: TestSealer = TestSealer()) {
        val repo = AmendRepository(d, keys, ratchetSealer = sealer)
        val sent: MutableList<Event> = Collections.synchronizedList(ArrayList())
        init { repo.meshOutbox = { sent.add(it) } }
    }

    /** Sends a text and returns the gift wraps it produced (publishing to real relays is left to time out). */
    private fun send(from: Phone, to: String, text: String): List<Event> = runBlocking {
        val before = from.sent.size
        val job = CoroutineScope(Dispatchers.Default).launch { runCatching { from.repo.sendText(to, text) } }
        withTimeout(20_000) { while (from.sent.size == before) delay(20) }
        delay(200)
        job.cancel()
        from.sent.drop(before)
    }

    private suspend fun deliver(to: Phone, wraps: List<Event>) = wraps.filter { it.tagValues("p").contains(to.repo.me) }.forEach { to.repo.injectMeshEvent(it) }

    @Test
    fun protectedMessagesBothWaysAndTheNsecCannotOpenThem() = runBlocking {
        val alice = Phone("alice")
        val bob = Phone("bob")
        alice.repo.setRatchetOn(true, publish = false)
        bob.repo.setRatchetOn(true, publish = false)
        // they find each other's bundles (normally on relays)
        alice.repo.handleBundle(bob.repo.ratchetBundle()!!)
        bob.repo.handleBundle(alice.repo.ratchetBundle()!!)
        assertEquals(AmendRepository.Protection.FULL, alice.repo.protectionOf(bob.repo.me))

        val w1 = send(alice, bob.repo.me, "hello, protected")
        assertTrue(w1.isNotEmpty())
        // with only the nsec, the outside opens but the inside is a Signal message
        w1.forEach { w ->
            val outer = GiftWrap.unwrap(w, bob.keys)
            assertEquals(Kinds.RATCHET_MESSAGE, outer.kind)
            assertFalse(outer.content.contains("hello"))
        }
        deliver(bob, w1)
        assertEquals("hello, protected", bob.repo.conversations.value[alice.repo.me]!!.messages.last().text)

        val w2 = send(bob, alice.repo.me, "hi back")
        deliver(alice, w2)
        assertEquals("hi back", alice.repo.conversations.value[bob.repo.me]!!.messages.last { !it.fromMe }.text)

        // re-delivery of the same wrap: nothing new, no error notice
        val count = bob.repo.conversations.value[alice.repo.me]!!.messages.size
        deliver(bob, w1)
        assertEquals(count, bob.repo.conversations.value[alice.repo.me]!!.messages.size)
    }

    @Test
    fun plainForSomeoneWithoutProtectionButNeverADowngradeForSomeoneWithIt() = runBlocking {
        val alice = Phone("alice")
        val carol = Phone("carol") // an old app: no bundle
        alice.repo.setRatchetOn(true, publish = false)
        assertEquals(AmendRepository.Protection.BASIC, alice.repo.protectionOf(carol.repo.me))
        val w = send(alice, carol.repo.me, "plain for an old app")
        deliver(carol, w)
        assertEquals("plain for an old app", carol.repo.conversations.value[alice.repo.me]!!.messages.last().text)

        // bob has used protection; later none of his keys can be found (a relay hides them)
        val r = Ratchet(alice.repo.me, RatchetStore(File(dir("x"), "r.bin"), TestSealer()))
        r.maintain()
        val bob = Phone("bob")
        bob.repo.setRatchetOn(true, publish = false)
        val bundle = bob.repo.ratchetBundle()!!
        assertEquals(Ratchet.BundleResult.Accepted, r.acceptBundle(bundle))
        assertTrue(r.canReach(bob.repo.me))
        r.store.extra.keys.filter { it.startsWith("b:") }.toList().forEach { r.store.extra.remove(it) }
        assertFalse(r.canReach(bob.repo.me))
        // still pinned: amend won't quietly send him a plain message
        assertTrue(r.isPinned(bob.repo.me))
    }

    @Test
    fun aRatchetStateThatSurvivesARestart() = runBlocking {
        val bobKeys = KeyPair.generate()
        val bobDir = dir("bob")
        val sealer = TestSealer()
        val alice = Phone("alice")
        var bob = Phone("bob", bobKeys, bobDir, sealer)
        alice.repo.setRatchetOn(true, publish = false)
        bob.repo.setRatchetOn(true, publish = false)
        alice.repo.handleBundle(bob.repo.ratchetBundle()!!)
        deliver(bob, send(alice, bob.repo.me, "before restart"))
        // bob's phone restarts: the same files, the same Keystore key
        bob = Phone("bob", bobKeys, bobDir, sealer)
        deliver(bob, send(alice, bob.repo.me, "after restart"))
        val texts = bob.repo.conversations.value[alice.repo.me]!!.messages.map { it.text }
        assertTrue(texts.toString(), "after restart" in texts)
    }

    @Test
    fun anIdentityChangeIsShownInTheChat() = runBlocking {
        val alice = Phone("alice")
        val bobKeys = KeyPair.generate()
        val bob = Phone("bob", bobKeys)
        alice.repo.setRatchetOn(true, publish = false)
        bob.repo.setRatchetOn(true, publish = false)
        alice.repo.handleBundle(bob.repo.ratchetBundle()!!)
        deliver(bob, send(alice, bob.repo.me, "hello"))
        // bob restores from his backup key on a new phone: same nsec, new ratchet identity
        val bob2 = Phone("bob2", bobKeys)
        bob2.repo.setRatchetOn(true, publish = false)
        Thread.sleep(1100) // a newer bundle
        alice.repo.handleBundle(bob2.repo.ratchetBundle()!!)
        val notes = alice.repo.conversations.value[bob.repo.me]!!.messages.filter { it.id.startsWith("fs-change-") }
        assertEquals(1, notes.size)
        // and the next message reaches the new phone
        deliver(bob2, send(alice, bob.repo.me, "to the new phone"))
        assertNotNull(bob2.repo.conversations.value[alice.repo.me]?.messages?.firstOrNull { it.text == "to the new phone" })
    }

    @Test
    fun aBundleSignedOnlyWithTheNsecIsRejected() = runBlocking {
        val alice = Phone("alice")
        val bob = Phone("bob")
        alice.repo.setRatchetOn(true, publish = false)
        bob.repo.setRatchetOn(true, publish = false)
        val real = bob.repo.ratchetBundle()!!
        alice.repo.handleBundle(real)
        // an attacker with bob's nsec but not his ratchet identity re-signs a changed bundle
        val outer = Json.parseObject(real.content)
        val body = (outer["body"] as String).replace("\"reg\":", "\"reg\":1")
        val forgedContent = Json.write(mapOf("body" to body, "isig" to outer["isig"]))
        val forged = Event.sign(bob.keys.secretKey, Kinds.PREKEY_BUNDLE, forgedContent, real.tags, real.createdAt + 5)
        val r = Ratchet(alice.repo.me, RatchetStore(File(dir("a2"), "r.bin"), TestSealer()))
        r.maintain()
        assertEquals(Ratchet.BundleResult.Accepted, r.acceptBundle(real))
        assertTrue(r.acceptBundle(forged) is Ratchet.BundleResult.Rejected)
        // an attacker's own ratchet identity is only taken with a visible "security code changed"
        val mallory = Ratchet(bob.repo.me, RatchetStore(File(dir("m"), "r.bin"), TestSealer()), now = { real.createdAt + 60 })
        mallory.maintain()
        assertEquals(Ratchet.BundleResult.IdentityChanged, r.acceptBundle(mallory.ownBundle(bob.keys)))
    }

    @Test
    fun paddingHidesLengthsAndRoundTrips() {
        for (n in listOf(0, 1, 159, 160, 1000)) {
            val m = ByteArray(n) { 7 }
            val p = Ratchet.pad(m)
            assertEquals(0, p.size % 160)
            assertTrue(p.size > n)
            assertTrue(Ratchet.unpad(p).contentEquals(m))
        }
    }
}
