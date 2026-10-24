package app.amend.messenger

import app.amend.messenger.core.nostr.GiftWrap
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.core.nostr.Kinds
import app.amend.messenger.data.EmergencyNumbers
import app.amend.messenger.data.SosLocation
import app.amend.messenger.data.SosPhase
import app.amend.messenger.data.SosProtocol
import app.amend.messenger.util.PressPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SosTest {
    @Test
    fun sosSurvivesGiftWrapRoundTrip() {
        val alice = KeyPair.generate()
        val bob = KeyPair.generate()
        val loc = SosLocation(23.810331, 90.412521, 12f, 1_700_000_000)
        val rumor = GiftWrap.rumor(alice.publicKeyHex, Kinds.CHAT_MESSAGE, SosProtocol.content(SosPhase.START, loc), SosProtocol.tags(bob.publicKeyHex, SosPhase.START, loc))
        val opened = GiftWrap.unwrap(GiftWrap.wrap(rumor, alice, bob.publicKeyHex), bob)
        val signal = SosProtocol.parse(opened)
        assertNotNull(signal)
        assertEquals(SosPhase.START, signal!!.phase)
        assertEquals(23.810331, signal.location!!.lat, 1e-6)
        assertEquals(90.412521, signal.location!!.lon, 1e-6)
        assertEquals(12f, signal.location!!.accuracyM, 0.5f)
        assertTrue("other apps see a map link", opened.content.contains("openstreetmap.org"))
    }

    @Test
    fun normalMessagesAreNotSos() {
        val a = KeyPair.generate()
        val rumor = GiftWrap.rumor(a.publicKeyHex, Kinds.CHAT_MESSAGE, "hello", listOf(listOf("p", a.publicKeyHex)))
        assertNull(SosProtocol.parse(rumor))
    }

    @Test
    fun badLocationsAreRejected() {
        assertNull(SosLocation.decode("200,10,5", 0))
        assertNull(SosLocation.decode("abc", 0))
        assertNull(SosLocation.decode(null, 0))
        assertNotNull(SosLocation.decode("60.192059,24.945831", 0))
    }

    @Test
    fun emergencyNumbers() {
        assertEquals("999", EmergencyNumbers.forCountry("bd"))
        assertEquals("112", EmergencyNumbers.forCountry("FI"))
        assertEquals("911", EmergencyNumbers.forCountry("US"))
        assertEquals("112", EmergencyNumbers.forCountry(null))
        assertEquals("112", EmergencyNumbers.forCountry("ZZ"))
    }

    @Test
    fun fourPressesWithinWindowTrigger() {
        val p = PressPattern(presses = 4, windowMs = 3_000)
        assertFalse(p.press(0)); assertFalse(p.press(500)); assertFalse(p.press(1000))
        assertTrue(p.press(1500))
        // resets after triggering
        assertFalse(p.press(1600))
        // slow presses never trigger
        val slow = PressPattern(presses = 4, windowMs = 3_000)
        assertFalse(slow.press(0)); assertFalse(slow.press(2000)); assertFalse(slow.press(4000)); assertFalse(slow.press(6000))
    }
}
