package app.amend.messenger

import app.amend.messenger.core.nostr.Event
import app.amend.messenger.core.nostr.GiftWrap
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.core.nostr.Kinds
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.ChatMessage
import app.amend.messenger.data.Disappear
import app.amend.messenger.data.GroupInfo
import app.amend.messenger.data.MessageStatus
import app.amend.messenger.data.MessageType
import app.amend.messenger.net.Nip05
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Shared gardens (groups), unsend, disappearing and scheduled messages — exercised
 * on the receiving side by handing gift wraps straight to the repository (the same
 * path the offline mesh uses), so no relay or network is involved.
 */
class MessagingFeaturesTest {
    private fun dir(name: String): File = Files.createTempDirectory(name).toFile()
    private fun repo(name: String, keys: KeyPair = KeyPair.generate(), d: File = dir(name)) = AmendRepository(d, keys)

    /** A rumor from [from] and its gift wrap addressed to [to]. */
    private fun wrap(
        from: KeyPair, to: String, kind: Int, content: String, tags: List<List<String>>, createdAt: Long = Event.nowSeconds(),
    ): Pair<Event, Event> {
        val rumor = GiftWrap.rumor(from.publicKeyHex, kind, content, tags, createdAt)
        return rumor to GiftWrap.wrap(rumor, from, to)
    }

    @Test
    fun groupKeyIsTheSameForEveryMemberInAnyOrder() {
        val a = KeyPair.generate().publicKeyHex
        val b = KeyPair.generate().publicKeyHex
        val c = KeyPair.generate().publicKeyHex
        val k1 = GroupInfo.keyFor(listOf(a, b, c))
        assertEquals(k1, GroupInfo.keyFor(listOf(c, a, b)))
        assertEquals(k1, GroupInfo.keyFor(listOf(b, c, a, a)))
        assertTrue(GroupInfo.isKey(k1))
        assertFalse(GroupInfo.isKey(a)) // a public key is never mistaken for a garden
        assertTrue(k1 != GroupInfo.keyFor(listOf(a, b, KeyPair.generate().publicKeyHex)))
    }

    @Test
    fun groupMessageLandsInTheSharedGardenWithItsAuthor() = runBlocking {
        val bob = repo("bob")
        val alice = KeyPair.generate()
        val carol = KeyPair.generate()
        val (_, w) = wrap(
            alice, bob.me, Kinds.CHAT_MESSAGE, "hello garden",
            listOf(listOf("p", bob.me), listOf("p", carol.publicKeyHex), listOf("subject", "Family 🌻")),
        )
        bob.injectMeshEvent(w)
        val key = GroupInfo.keyFor(listOf(alice.publicKeyHex, bob.me, carol.publicKeyHex))
        val conv = bob.conversations.value[key]
        assertNotNull("message should be filed under the shared garden", conv)
        val m = conv!!.messages.single()
        assertEquals("hello garden", m.text)
        assertEquals(alice.publicKeyHex, m.author)
        assertTrue(conv.isGroup)
        assertEquals("Family 🌻", bob.groupInfo(key)!!.name)
        assertEquals("Family 🌻", bob.displayName(key))
        assertEquals(3, bob.groupInfo(key)!!.members.size)
        // a garden from a stranger waits as a request
        assertFalse(bob.isAccepted(key))
        // the room's "p" tags address everyone except me
        assertEquals(setOf(alice.publicKeyHex, carol.publicKeyHex), bob.pTags(key).map { it[1] }.toSet())
        // and it is not in the 1:1 chat with alice
        assertNull(bob.conversations.value[alice.publicKeyHex])
    }

    @Test
    fun gardensSurviveARestart() = runBlocking {
        val d = dir("persist")
        val keys = KeyPair.generate()
        val bob = repo("bob", keys, d)
        val alice = KeyPair.generate()
        val carol = KeyPair.generate()
        val (_, w) = wrap(alice, bob.me, Kinds.CHAT_MESSAGE, "hi", listOf(listOf("p", bob.me), listOf("p", carol.publicKeyHex), listOf("subject", "Crew")))
        bob.injectMeshEvent(w)
        kotlinx.coroutines.delay(1_500) // settings are written shortly after
        val again = repo("bob", keys, d)
        val key = GroupInfo.keyFor(listOf(alice.publicKeyHex, bob.me, carol.publicKeyHex))
        assertEquals("Crew", again.groupInfo(key)?.name)
        assertEquals("hi", again.conversations.value[key]?.messages?.single()?.text)
    }

    @Test
    fun unsendWithinTheWindowLeavesOnlyANote() = runBlocking {
        val bob = repo("bob")
        val alice = KeyPair.generate()
        val (msg, w) = wrap(alice, bob.me, Kinds.CHAT_MESSAGE, "oops, wrong chat", listOf(listOf("p", bob.me)))
        bob.injectMeshEvent(w)
        val (_, del) = wrap(alice, bob.me, Kinds.DELETION, "", listOf(listOf("p", bob.me), listOf("e", msg.id), listOf("k", "14")))
        bob.injectMeshEvent(del)
        val m = bob.conversations.value[alice.publicKeyHex]!!.messages.single()
        assertEquals(MessageType.UNSENT, m.type)
        assertEquals("", m.text) // the words are gone from this phone
    }

    @Test
    fun onlyTheAuthorCanUnsend() = runBlocking {
        val bob = repo("bob")
        val alice = KeyPair.generate()
        val mallory = KeyPair.generate()
        val (msg, w) = wrap(alice, bob.me, Kinds.CHAT_MESSAGE, "keep me", listOf(listOf("p", bob.me)))
        bob.injectMeshEvent(w)
        val (_, del) = wrap(mallory, bob.me, Kinds.DELETION, "", listOf(listOf("p", bob.me), listOf("e", msg.id)))
        bob.injectMeshEvent(del)
        assertEquals(MessageType.TEXT, bob.conversations.value[alice.publicKeyHex]!!.messages.single().type)
    }

    @Test
    fun unsendAfterFifteenMinutesIsIgnored() = runBlocking {
        val bob = repo("bob")
        val alice = KeyPair.generate()
        val old = Event.nowSeconds() - 25 * 60
        val (msg, w) = wrap(alice, bob.me, Kinds.CHAT_MESSAGE, "old news", listOf(listOf("p", bob.me)), createdAt = old)
        bob.injectMeshEvent(w)
        val (_, del) = wrap(alice, bob.me, Kinds.DELETION, "", listOf(listOf("p", bob.me), listOf("e", msg.id)))
        bob.injectMeshEvent(del)
        assertEquals(MessageType.TEXT, bob.conversations.value[alice.publicKeyHex]!!.messages.single().type)
    }

    @Test
    fun anUnsendThatArrivesFirstStillHidesTheMessage() = runBlocking {
        val bob = repo("bob")
        val alice = KeyPair.generate()
        val rumor = GiftWrap.rumor(alice.publicKeyHex, Kinds.CHAT_MESSAGE, "late copy", listOf(listOf("p", bob.me)))
        val (_, del) = wrap(alice, bob.me, Kinds.DELETION, "", listOf(listOf("p", bob.me), listOf("e", rumor.id)))
        bob.injectMeshEvent(del)
        bob.injectMeshEvent(GiftWrap.wrap(rumor, alice, bob.me))
        val m = bob.conversations.value[alice.publicKeyHex]!!.messages.single()
        assertEquals(MessageType.UNSENT, m.type)
    }

    @Test
    fun unsendWindowIsFifteenMinutesForMyOwnMessages() {
        val bob = repo("bob")
        val now = Event.nowSeconds()
        val fresh = ChatMessage("a", "peer", true, "hi", now - 60, status = MessageStatus.SENT)
        val stale = fresh.copy(id = "b", createdAt = now - 16 * 60)
        val theirs = fresh.copy(id = "c", fromMe = false)
        assertTrue(bob.canUnsend(fresh))
        assertFalse(bob.canUnsend(stale))
        assertFalse(bob.canUnsend(theirs))
        assertEquals(15 * 60L, AmendRepository.UNSEND_WINDOW)
    }

    @Test
    fun expiredDisappearingMessagesAreNeverShown() = runBlocking {
        val bob = repo("bob")
        val alice = KeyPair.generate()
        val past = Event.nowSeconds() - 5
        val future = Event.nowSeconds() + 3600
        bob.injectMeshEvent(wrap(alice, bob.me, Kinds.CHAT_MESSAGE, "gone", listOf(listOf("p", bob.me), listOf("expiration", past.toString()))).second)
        bob.injectMeshEvent(wrap(alice, bob.me, Kinds.CHAT_MESSAGE, "for an hour", listOf(listOf("p", bob.me), listOf("expiration", future.toString()))).second)
        val msgs = bob.conversations.value[alice.publicKeyHex]!!.messages
        assertEquals(listOf("for an hour"), msgs.map { it.text })
        assertEquals(future, msgs.single().expiresAt)
    }

    @Test
    fun aDisappearNoticeTurnsTheTimerOnForBothSides() = runBlocking {
        val bob = repo("bob")
        val alice = KeyPair.generate()
        bob.injectMeshEvent(
            wrap(alice, bob.me, Kinds.CHAT_MESSAGE, "⏳ Alice turned disappearing messages on · 1 day",
                listOf(listOf("p", bob.me), listOf("amend", "disappear", "86400"))).second
        )
        val conv = bob.conversations.value[alice.publicKeyHex]!!
        assertEquals(86400L, conv.disappearSecs)
        assertEquals(MessageType.NOTICE, conv.messages.single().type)
        // an older "off" arriving later doesn't undo the newer change
        bob.injectMeshEvent(
            wrap(alice, bob.me, Kinds.CHAT_MESSAGE, "⏳ off", listOf(listOf("p", bob.me), listOf("amend", "disappear", "0")),
                createdAt = Event.nowSeconds() - 3600).second
        )
        assertEquals(86400L, bob.conversations.value[alice.publicKeyHex]!!.disappearSecs)
    }

    @Test
    fun scheduledMessagesWaitOnThePhone() = runBlocking {
        val bob = repo("bob")
        val alice = KeyPair.generate().publicKeyHex
        val at = Event.nowSeconds() + 3600
        val m = bob.scheduleText(alice, "happy birthday!", at)
        assertEquals(MessageStatus.SCHEDULED, m.status)
        assertEquals(at, bob.nextScheduledAt())
        // offline and not due: nothing is sent
        assertEquals(0, bob.sendDueScheduled())
        assertEquals(MessageStatus.SCHEDULED, bob.conversations.value[alice]!!.messages.single().status)
        bob.cancelScheduled(alice, m.id)
        assertTrue(bob.conversations.value[alice]!!.messages.isEmpty())
        assertNull(bob.nextScheduledAt())
    }

    @Test
    fun disappearLabels() {
        assertEquals("Off", Disappear.label(0))
        assertEquals("1 hour", Disappear.label(3600))
        assertEquals("1 day", Disappear.label(86400))
        assertEquals("1 week", Disappear.label(7 * 86400))
    }

    @Test
    fun nip05AddressesAreParsedStrictly() {
        assertEquals("faisal" to "getamend.app", Nip05.parse("Faisal@GetAmend.app"))
        assertEquals("_" to "getamend.app", Nip05.parse("getamend.app"))
        assertNull(Nip05.parse("bad name@getamend.app"))
        assertNull(Nip05.parse("x@localhost"))           // must be a real domain
        assertNull(Nip05.parse("x@evil.com/../etc"))
        assertEquals("getamend.app", Nip05.display("_@getamend.app"))
    }

    @Test
    fun liveLocationStartsMovesAndStops() = runBlocking {
        val bob = repo("bob")
        val alice = KeyPair.generate()
        val until = Event.nowSeconds() + 3600
        val p = listOf(listOf("p", bob.me))
        fun live(phase: app.amend.messenger.data.LivePhase, lat: Double?, at: Long) = wrap(
            alice, bob.me, Kinds.CHAT_MESSAGE, app.amend.messenger.data.LiveProtocol.content(phase, until),
            app.amend.messenger.data.LiveProtocol.tags(p, phase, lat?.let { app.amend.messenger.data.SosLocation(it, 25.7, 20f, at) }, until), createdAt = at,
        ).second
        val t = Event.nowSeconds()
        bob.injectMeshEvent(live(app.amend.messenger.data.LivePhase.START, 62.24, t - 20))
        bob.injectMeshEvent(live(app.amend.messenger.data.LivePhase.UPDATE, 62.25, t - 10))
        val key = "${alice.publicKeyHex}|${alice.publicKeyHex}"
        assertEquals(62.25, bob.liveShares.value[key]!!.location!!.lat, 1e-6)
        val msgs = bob.conversations.value[alice.publicKeyHex]!!.messages
        assertEquals(listOf(MessageType.LIVE), msgs.map { it.type }) // updates don't add messages
        bob.injectMeshEvent(live(app.amend.messenger.data.LivePhase.STOP, null, t))
        assertNull(bob.liveShares.value[key])
        assertEquals(MessageType.NOTICE, bob.conversations.value[alice.publicKeyHex]!!.messages.last().type)
    }
}
