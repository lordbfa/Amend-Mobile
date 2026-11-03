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
import app.amend.messenger.data.lastDays
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

    @Test
    fun friendStatsAreKeptOnlyFromAcceptedContactsAndCanBeWithdrawn() = runBlocking {
        val bob = repo("bob-stats")
        val alice = KeyPair.generate()
        val stranger = KeyPair.generate()
        val t = Event.nowSeconds()
        bob.injectMeshEvent(wrap(stranger, bob.me, Kinds.ECO_STATS, """{"trees":99,"points":1,"streak":1}""", listOf(listOf("p", bob.me)), t).second)
        assertNull(bob.friendStats.value[stranger.publicKeyHex])
        // a first chat message makes alice a conversation; accepting her lets her stats in
        bob.injectMeshEvent(wrap(alice, bob.me, Kinds.CHAT_MESSAGE, "hi", listOf(listOf("p", bob.me)), t - 5).second)
        bob.accept(alice.publicKeyHex)
        bob.injectMeshEvent(wrap(alice, bob.me, Kinds.ECO_STATS, """{"trees":3,"points":420,"streak":7,"forest":["a1b2c3","0f0f0f","nothex","DEADBE"],"growing":20}""", listOf(listOf("p", bob.me)), t).second)
        assertEquals(3, bob.friendStats.value[alice.publicKeyHex]?.trees)
        assertEquals(7, bob.friendStats.value[alice.publicKeyHex]?.streak)
        // their forest comes along as short seeds; anything malformed is dropped
        assertEquals(listOf("a1b2c3", "0f0f0f", "deadbe"), bob.friendStats.value[alice.publicKeyHex]?.forest)
        assertEquals(20, bob.friendStats.value[alice.publicKeyHex]?.growing)
        bob.injectMeshEvent(wrap(alice, bob.me, Kinds.ECO_STATS, """{"off":true}""", listOf(listOf("p", bob.me)), t + 1).second)
        assertNull(bob.friendStats.value[alice.publicKeyHex])
        // stats never show up as a chat message
        assertTrue(bob.conversations.value[alice.publicKeyHex]!!.messages.none { it.text.contains("trees") })
    }

    @Test
    fun dailyHistoryKeepsOnlyTheLastDays() {
        val m = (1..40).associate { "2026-10-%02d".format(it % 28 + 1) + "-$it" to it.toLong() }
        assertEquals(30, m.lastDays(30).size)
        assertEquals(5, mapOf("a" to 1, "b" to 2, "c" to 3, "d" to 4, "e" to 5).lastDays(30).size)
    }

    @Test
    fun editReplacesTextOnlyFromTheAuthorInsideTheWindow() = runBlocking {
        val bob = repo("bob-edit")
        val alice = KeyPair.generate()
        val mallory = KeyPair.generate()
        val t = Event.nowSeconds()
        val (msg, w) = wrap(alice, bob.me, Kinds.CHAT_MESSAGE, "helo", listOf(listOf("p", bob.me)), t - 60)
        bob.injectMeshEvent(w)
        // someone else can't rewrite alice's words
        bob.injectMeshEvent(wrap(mallory, bob.me, Kinds.EDIT, "hacked", listOf(listOf("p", bob.me), listOf("e", msg.id)), t).second)
        fun text() = bob.conversations.value[alice.publicKeyHex]!!.messages.first { it.id == msg.id }
        assertEquals("helo", text().text)
        bob.injectMeshEvent(wrap(alice, bob.me, Kinds.EDIT, "hello", listOf(listOf("p", bob.me), listOf("e", msg.id)), t).second)
        assertEquals("hello", text().text)
        assertTrue(text().editedAt > 0)
        // an older edit arriving late doesn't undo the newer one
        bob.injectMeshEvent(wrap(alice, bob.me, Kinds.EDIT, "hullo", listOf(listOf("p", bob.me), listOf("e", msg.id)), t - 30).second)
        assertEquals("hello", text().text)
    }

    @Test
    fun editArrivingBeforeItsMessageIsAppliedWhenTheMessageLands() = runBlocking {
        val bob = repo("bob-edit-early")
        val alice = KeyPair.generate()
        val t = Event.nowSeconds()
        val (msg, w) = wrap(alice, bob.me, Kinds.CHAT_MESSAGE, "teh plan", listOf(listOf("p", bob.me)), t - 30)
        bob.injectMeshEvent(wrap(alice, bob.me, Kinds.EDIT, "the plan", listOf(listOf("p", bob.me), listOf("e", msg.id)), t).second)
        bob.injectMeshEvent(w)
        assertEquals("the plan", bob.conversations.value[alice.publicKeyHex]!!.messages.first { it.id == msg.id }.text)
    }

    @Test
    fun editWindowMatchesUnsend() {
        val bob = repo("bob-can-edit")
        val now = Event.nowSeconds()
        val fresh = ChatMessage("a", "p", fromMe = true, text = "x", createdAt = now - 60, status = MessageStatus.SENT)
        val old = fresh.copy(id = "b", createdAt = now - AmendRepository.UNSEND_WINDOW - 5)
        assertTrue(bob.canEdit(fresh))
        assertFalse(bob.canEdit(old))
        assertFalse(bob.canEdit(fresh.copy(fromMe = false)))
        assertFalse(bob.canEdit(fresh.copy(type = MessageType.VOICE)))
    }

    @Test
    fun moderatorBlocklistBlocksForEveryoneAndOnlyTheModeratorCanSignIt() = runBlocking {
        val bob = repo("bob-mod")
        val mod = KeyPair.generate()
        bob.moderatorPubkey = mod.publicKeyHex
        val bad = KeyPair.generate()
        val t = Event.nowSeconds()
        bob.injectMeshEvent(wrap(bad, bob.me, Kinds.CHAT_MESSAGE, "hey", listOf(listOf("p", bob.me)), t - 100).second)
        assertNotNull(bob.conversations.value[bad.publicKeyHex])
        // a list signed by anyone else is ignored
        val fake = KeyPair.generate()
        bob.applyModerationList(Event.sign(fake.secretKey, app.amend.messenger.data.Moderation.LIST_KIND, "", listOf(listOf("p", bad.publicKeyHex))))
        assertFalse(bob.isBlocked(bad.publicKeyHex))
        // the moderator's list removes the chat and stops new messages
        bob.applyModerationList(app.amend.messenger.data.Moderation.build(mod, listOf(bad.publicKeyHex), mod.publicKeyHex))
        assertTrue(bob.isBlocked(bad.publicKeyHex))
        assertNull(bob.conversations.value[bad.publicKeyHex])
        bob.injectMeshEvent(wrap(bad, bob.me, Kinds.CHAT_MESSAGE, "again", listOf(listOf("p", bob.me)), t).second)
        assertNull(bob.conversations.value[bad.publicKeyHex])
    }

    @Test
    fun liftingAModeratorBlockNeverUndoesYourOwnBlock() = runBlocking {
        val bob = repo("bob-mod-lift")
        val mod = KeyPair.generate()
        bob.moderatorPubkey = mod.publicKeyHex
        val a = KeyPair.generate().publicKeyHex
        val b = KeyPair.generate().publicKeyHex
        bob.block(b) // bob blocked b himself
        bob.applyModerationList(Event.sign(mod.secretKey, app.amend.messenger.data.Moderation.LIST_KIND, "", listOf(listOf("p", a), listOf("p", b)), Event.nowSeconds() - 10))
        bob.applyModerationList(Event.sign(mod.secretKey, app.amend.messenger.data.Moderation.LIST_KIND, "", emptyList(), Event.nowSeconds()))
        assertFalse(bob.isBlocked(a))
        assertTrue(bob.isBlocked(b))
    }

    @Test
    fun strangerShieldDropsRequestsButNotPeopleYouAccepted() = runBlocking {
        val bob = repo("bob-shield")
        bob.blockStrangers = true
        val stranger = KeyPair.generate()
        val friend = KeyPair.generate()
        bob.accept(friend.publicKeyHex)
        bob.injectMeshEvent(wrap(stranger, bob.me, Kinds.CHAT_MESSAGE, "hi there", listOf(listOf("p", bob.me))).second)
        bob.injectMeshEvent(wrap(friend, bob.me, Kinds.CHAT_MESSAGE, "hi bob", listOf(listOf("p", bob.me))).second)
        assertNull(bob.conversations.value[stranger.publicKeyHex])
        assertNotNull(bob.conversations.value[friend.publicKeyHex])
    }

    @Test
    fun reportEvidenceIsPlainTextOfTheLastMessages() = runBlocking {
        val bob = repo("bob-evidence")
        val alice = KeyPair.generate()
        val t = Event.nowSeconds()
        (1..12).forEach { i -> bob.injectMeshEvent(wrap(alice, bob.me, Kinds.CHAT_MESSAGE, "msg $i", listOf(listOf("p", bob.me)), t - 100 + i).second) }
        val ev = bob.reportEvidence(alice.publicKeyHex)
        assertEquals(10, ev.lines().size)
        assertTrue(ev.contains("msg 12") && !ev.contains("msg 2\n"))
        assertTrue(ev.contains(app.amend.messenger.core.crypto.Nip19.npub(alice.publicKeyHex)))
    }
}
