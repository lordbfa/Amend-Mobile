package app.amend.messenger

import app.amend.messenger.core.nostr.GiftWrap
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.core.nostr.Kinds
import app.amend.messenger.util.MeshStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MeshTest {
    @Test
    fun storeKeepsWrapsAcrossRestartsAndDedupes() {
        val dir = Files.createTempDirectory("mesh").toFile()
        val file = java.io.File(dir, "mesh.json")
        val alice = KeyPair.generate()
        val bob = KeyPair.generate()
        val rumor = GiftWrap.rumor(alice.publicKeyHex, Kinds.CHAT_MESSAGE, "flood water rising, we are safe on the roof", listOf(listOf("p", bob.publicKeyHex)))
        val wrap = GiftWrap.wrap(rumor, alice, bob.publicKeyHex)
        val s = MeshStore(file)
        assertTrue(s.add(wrap.id, wrap.toJson(), needsRelay = true))
        assertFalse("same event only once", s.add(wrap.id, wrap.toJson(), needsRelay = true))
        assertEquals(1, s.pendingRelay().size)
        s.save()
        val again = MeshStore(file)
        assertTrue(again.has(wrap.id))
        assertEquals(listOf(wrap.id), again.ids())
        // the carried copy still opens for Bob only
        val opened = GiftWrap.unwrap(app.amend.messenger.core.nostr.Event.fromJson(again.get(wrap.id)!!), bob)
        assertEquals("flood water rising, we are safe on the roof", opened.content)
        again.markRelayed(wrap.id)
        assertTrue(again.pendingRelay().isEmpty())
    }

    @Test
    fun storeIsBounded() {
        val s = MeshStore(java.io.File(Files.createTempDirectory("mesh2").toFile(), "m.json"))
        repeat(MeshStore.MAX_EVENTS + 50) { i -> s.add("%064x".format(i), "{}", false) }
        assertEquals(MeshStore.MAX_EVENTS, s.size())
        assertFalse("oldest dropped first", s.has("%064x".format(0)))
        assertFalse("too big for one radio packet", s.add("f".repeat(64), "x".repeat(MeshStore.MAX_EVENT_BYTES + 1), false))
    }
}
