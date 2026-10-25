package app.amend.messenger

import app.amend.messenger.core.nostr.GiftWrap
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.core.nostr.Kinds
import app.amend.messenger.data.CallLog
import app.amend.messenger.data.CallOutcome
import app.amend.messenger.data.CallSignal
import app.amend.messenger.data.formatCallDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTest {
    @Test
    fun signalSurvivesAnEphemeralGiftWrap() {
        val alice = KeyPair.generate()
        val bob = KeyPair.generate()
        val sig = CallSignal(bob.publicKeyHex, CallSignal.OFFER, "abcdef0123456789", video = true, sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1")
        val rumor = GiftWrap.rumor(alice.publicKeyHex, Kinds.CALL_SIGNAL, sig.contentJson(), listOf(listOf("p", bob.publicKeyHex)))
        val wrap = GiftWrap.wrap(rumor, alice, bob.publicKeyHex, ephemeral = true)
        assertEquals(Kinds.EPHEMERAL_GIFT_WRAP, wrap.kind)
        assertTrue("live wraps use the real time", kotlin.math.abs(wrap.createdAt - System.currentTimeMillis() / 1000) < 5)
        val opened = GiftWrap.unwrap(wrap, bob)
        val back = CallSignal.parse(alice.publicKeyHex, opened, ephemeral = true)!!
        assertEquals(CallSignal.OFFER, back.type)
        assertEquals(sig.callId, back.callId)
        assertEquals(sig.sdp, back.sdp)
        assertTrue(back.video && back.live && back.isFresh())
    }

    @Test
    fun staleOffersDontRing() {
        val old = CallSignal("p", CallSignal.OFFER, "abcdef0123456789", createdAt = System.currentTimeMillis() / 1000 - 120)
        assertFalse(old.isFresh())
    }

    @Test
    fun junkIsIgnored() {
        val k = KeyPair.generate()
        val bad = GiftWrap.rumor(k.publicKeyHex, Kinds.CALL_SIGNAL, """{"type":"explode","call":"abcdef0123456789"}""", emptyList())
        assertNull(CallSignal.parse(k.publicKeyHex, bad, true))
        val short = GiftWrap.rumor(k.publicKeyHex, Kinds.CALL_SIGNAL, """{"type":"offer","call":"x"}""", emptyList())
        assertNull(CallSignal.parse(k.publicKeyHex, short, true))
    }

    @Test
    fun callLogLabels() {
        val done = CallLog("id", outgoing = true, video = false, outcome = CallOutcome.COMPLETED, startedAt = 1, durationSec = 154)
        assertEquals("📞 Outgoing voice call · 2:34", done.label())
        assertEquals(done, CallLog.fromJson(done.toJson()))
        assertEquals("🎥 Missed video call", CallLog("id", false, true, CallOutcome.MISSED, 1).label())
        assertEquals("1:01:05", formatCallDuration(3665))
    }
}
