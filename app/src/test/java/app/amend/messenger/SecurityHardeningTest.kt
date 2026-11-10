package app.amend.messenger

import app.amend.messenger.core.crypto.PureSecp256k1
import app.amend.messenger.core.crypto.Rand
import app.amend.messenger.core.nostr.Event
import app.amend.messenger.core.nostr.GiftWrap
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.core.nostr.Kinds
import app.amend.messenger.data.AmendEvent
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.ChatMessage
import app.amend.messenger.data.SosPhase
import app.amend.messenger.data.SosProtocol
import app.amend.messenger.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Regression tests for the security hardening: forged events, SOS abuse, files at rest, key operations. */
class SecurityHardeningTest {
    private fun dir(name: String): File = Files.createTempDirectory(name).toFile()

    private fun sos(from: KeyPair, to: String, phase: SosPhase, createdAt: Long = Event.nowSeconds()): Event {
        val rumor = GiftWrap.rumor(from.publicKeyHex, Kinds.CHAT_MESSAGE, SosProtocol.content(phase, null), SosProtocol.tags(to, phase, null), createdAt)
        return GiftWrap.wrap(rumor, from, to)
    }

    // ---- forged events ---------------------------------------------------------------

    @Test
    fun aForgedCopyOfAWrapIdCannotBlockTheRealMessage() = runBlocking {
        val bob = AmendRepository(dir("bob"), KeyPair.generate())
        val alice = KeyPair.generate()
        val real = GiftWrap.wrap(GiftWrap.rumor(alice.publicKeyHex, Kinds.CHAT_MESSAGE, "the real one", listOf(listOf("p", bob.me))), alice, bob.me)
        // same id, but the content (and so the id check) and the signature are wrong
        bob.injectMeshEvent(real.copy(content = "garbage"))
        bob.injectMeshEvent(real.copy(sig = "0".repeat(128)))
        assertNull(bob.conversations.value[alice.publicKeyHex])
        bob.injectMeshEvent(real)
        assertEquals("the real one", bob.conversations.value[alice.publicKeyHex]?.messages?.single()?.text)
        // and the real one is still only taken once
        bob.injectMeshEvent(real)
        assertEquals(1, bob.conversations.value[alice.publicKeyHex]!!.messages.size)
    }

    // ---- SOS abuse -------------------------------------------------------------------

    @Test
    fun aStrangersSosIsARequestNotAnAlarm() = runBlocking {
        val bob = AmendRepository(dir("bob"), KeyPair.generate())
        val stranger = KeyPair.generate()
        bob.injectMeshEvent(sos(stranger, bob.me, SosPhase.START))
        // the message is there to read, as a request…
        assertEquals(1, bob.conversations.value[stranger.publicKeyHex]?.messages?.size)
        assertFalse(bob.isAccepted(stranger.publicKeyHex))
        // …but no alarm was raised
        assertNull(bob.incomingSos.value[stranger.publicKeyHex])

        // once bob knows them, their SOS does raise the alarm
        bob.accept(stranger.publicKeyHex)
        bob.injectMeshEvent(sos(stranger, bob.me, SosPhase.START))
        assertTrue(bob.incomingSos.value[stranger.publicKeyHex]?.active == true)
    }

    @Test
    fun oneFriendCannotRaiseAnAlarmStorm() = runBlocking {
        val bob = AmendRepository(dir("bob"), KeyPair.generate())
        val alice = KeyPair.generate()
        bob.accept(alice.publicKeyHex)
        val phases = java.util.Collections.synchronizedList(ArrayList<SosPhase>())
        val listener = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            bob.events.collect { if (it is AmendEvent.SosReceived) phases.add(it.phase) }
        }
        val now = Event.nowSeconds()
        val n = AmendRepository.SOS_ALARMS_PER_WINDOW
        // each start a second after the one before, the newest right now
        for (i in 0 until n + 4) bob.injectMeshEvent(sos(alice, bob.me, SosPhase.START, now - (n + 3 - i)))
        kotlinx.coroutines.delay(300)
        // only the first few sound the alarm; the rest arrive quietly, as updates do
        assertEquals(n, phases.count { it == SosPhase.START })
        assertEquals(4, phases.count { it == SosPhase.UPDATE })
        // nothing was dropped: the state follows the newest one
        assertEquals(now, bob.incomingSos.value[alice.publicKeyHex]!!.updatedAt)
        assertTrue(bob.incomingSos.value[alice.publicKeyHex]!!.active)
        // and an "I'm safe" always gets through
        bob.injectMeshEvent(sos(alice, bob.me, SosPhase.STOP, now + 1))
        assertFalse(bob.incomingSos.value[alice.publicKeyHex]!!.active)
        listener.cancel()
    }

    @Test
    fun anSosFromTheFutureIsIgnored() = runBlocking {
        val bob = AmendRepository(dir("bob"), KeyPair.generate())
        val alice = KeyPair.generate()
        bob.accept(alice.publicKeyHex)
        bob.injectMeshEvent(sos(alice, bob.me, SosPhase.START, Event.nowSeconds() + 7 * 24 * 3600))
        assertNull(bob.incomingSos.value[alice.publicKeyHex])
        // so a real one that follows still works
        bob.injectMeshEvent(sos(alice, bob.me, SosPhase.START))
        assertTrue(bob.incomingSos.value[alice.publicKeyHex]?.active == true)
    }

    // ---- files at rest ---------------------------------------------------------------

    private val peer = "a".repeat(64)
    private fun message(text: String) = ChatMessage(id = "b".repeat(64), peer = peer, fromMe = true, text = text, createdAt = 1)

    @Test
    fun chatsAreSealedOnDiskAndOpenAgainWithTheSameKey() {
        val d = dir("sealed")
        val key = Store.keyFor(KeyPair.generate().secretKey)
        Store(d, key).saveChat(peer, listOf(message("meet me at the old oak")))
        val raw = File(d, "chats/$peer.json").readBytes()
        assertFalse("the words must not be on disk", String(raw, Charsets.ISO_8859_1).contains("old oak"))
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(peer))
        assertEquals("meet me at the old oak", Store(d, key).loadChat(peer)!!.single().text)
        assertEquals("meet me at the old oak", Store(d, key).loadChats()[peer]!!.single().text)
        // every save uses a fresh nonce
        Store(d, key).saveChat(peer, listOf(message("meet me at the old oak")))
        assertFalse(raw.contentEquals(File(d, "chats/$peer.json").readBytes()))
    }

    @Test
    fun filesFromOlderVersionsAreReadAndThenSealed() {
        val d = dir("legacy")
        val key = Store.keyFor(KeyPair.generate().secretKey)
        Store(d).saveChat(peer, listOf(message("written before sealing"))) // plain, as older versions did
        val file = File(d, "chats/$peer.json")
        assertTrue(file.readText().contains("written before sealing"))
        val store = Store(d, key)
        assertEquals("written before sealing", store.loadChat(peer)!!.single().text)
        assertEquals(1, store.sealExisting())
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("written before sealing"))
        assertEquals("written before sealing", store.loadChat(peer)!!.single().text)
        assertEquals(0, store.sealExisting()) // nothing left to do
    }

    @Test
    fun aFileThatCannotBeOpenedIsKeptAsideNotOverwritten() {
        val d = dir("wrongkey")
        Store(d, Store.keyFor(KeyPair.generate().secretKey)).saveChat(peer, listOf(message("mine")))
        val sealed = File(d, "chats/$peer.json").readBytes()
        val other = Store(d, Store.keyFor(KeyPair.generate().secretKey))
        assertNull(other.loadChat(peer))
        other.saveChat(peer, listOf(message("theirs")))
        assertArrayEquals(sealed, File(d, "chats/$peer.json.unreadable").readBytes())
    }

    @Test
    fun aSealedFileCannotBeSwappedForAnother() {
        val d = dir("swap")
        val key = Store.keyFor(KeyPair.generate().secretKey)
        val other = "c".repeat(64)
        val store = Store(d, key)
        store.saveChat(peer, listOf(message("for peer a")))
        File(d, "chats/$peer.json").copyTo(File(d, "chats/$other.json"))
        assertNull(store.loadChat(other))
    }

    @Test
    fun tamperedFilesAreRejected() {
        val d = dir("tamper")
        val key = Store.keyFor(KeyPair.generate().secretKey)
        Store(d, key).saveChat(peer, listOf(message("untouched")))
        val f = File(d, "chats/$peer.json")
        val bytes = f.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 1).toByte()
        f.writeBytes(bytes)
        assertNull(Store(d, key).loadChat(peer))
    }

    @Test
    fun aRepositoryKeepsItsChatsSealedAndReadsThemBackAfterARestart() = runBlocking {
        val d = dir("repo")
        val keys = KeyPair.generate()
        val alice = KeyPair.generate()
        val bob = AmendRepository(d, keys)
        bob.injectMeshEvent(GiftWrap.wrap(GiftWrap.rumor(alice.publicKeyHex, Kinds.CHAT_MESSAGE, "a very private note", listOf(listOf("p", bob.me))), alice, bob.me))
        kotlinx.coroutines.delay(1_500) // settings are written shortly after
        val files = d.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.toList()
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val raw = String(f.readBytes(), Charsets.ISO_8859_1)
            assertFalse("${f.name} holds readable text", raw.contains("private note") || raw.contains(alice.publicKeyHex))
        }
        assertEquals("a very private note", AmendRepository(d, keys).conversations.value[alice.publicKeyHex]?.messages?.single()?.text)
    }

    // ---- key operations in the pure-Kotlin fallback ------------------------------------

    @Test
    fun blindedKeyOperationsStayCorrect() {
        repeat(12) {
            val a = KeyPair.generate()
            val b = KeyPair.generate()
            val pa = PureSecp256k1.xOnlyPublicKey(a.secretKey)
            val pb = PureSecp256k1.xOnlyPublicKey(b.secretKey)
            // the same key every time, whatever the blinding
            assertArrayEquals(pa, PureSecp256k1.xOnlyPublicKey(a.secretKey))
            // both sides derive the same shared secret
            assertArrayEquals(PureSecp256k1.sharedX(a.secretKey, pb), PureSecp256k1.sharedX(b.secretKey, pa))
            val msg = Rand.bytes(32)
            val sig = PureSecp256k1.signSchnorr(msg, a.secretKey, Rand.bytes(32))
            assertTrue(PureSecp256k1.verifySchnorr(sig, msg, pa))
            assertFalse(PureSecp256k1.verifySchnorr(sig, msg, pb))
        }
        // edge scalars: 1 and N-1
        val one = ByteArray(32).also { it[31] = 1 }
        assertEquals("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", PureSecp256k1.xOnlyPublicKey(one).joinToString("") { "%02x".format(it) })
        val nMinus1 = PureSecp256k1.N.subtract(java.math.BigInteger.ONE).toByteArray().takeLast(32).toByteArray()
        assertArrayEquals(PureSecp256k1.xOnlyPublicKey(one), PureSecp256k1.xOnlyPublicKey(nMinus1))
        assertNotNull(PureSecp256k1.sharedX(nMinus1, PureSecp256k1.xOnlyPublicKey(one)))
    }
}
