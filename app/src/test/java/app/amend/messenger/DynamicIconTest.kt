package app.amend.messenger

import app.amend.messenger.util.DynamicIcon
import app.amend.messenger.util.IconPhase
import app.amend.messenger.util.SunTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DynamicIconTest {
    private fun hm(min: Double) = "%02d:%02d".format((min / 60).toInt(), (min % 60).toInt())

    @Test
    fun sunTimesMatchAlmanac() {
        // Helsinki, midsummer: sunrise ≈ 03:54, sunset ≈ 22:50 (EEST)
        val hel = SunTimes.minutes(LocalDate.of(2026, 6, 21), 60.17, 24.94, ZoneId.of("Europe/Helsinki"))!!
        assertTrue("Helsinki sunrise ${hm(hel.first)}", kotlin.math.abs(hel.first - (3 * 60 + 54)) < 12)
        assertTrue("Helsinki sunset ${hm(hel.second)}", kotlin.math.abs(hel.second - (22 * 60 + 50)) < 12)
        // Dhaka, 1 January: sunrise ≈ 06:41, sunset ≈ 17:22 (UTC+6)
        val dha = SunTimes.minutes(LocalDate.of(2026, 1, 1), 23.81, 90.41, ZoneId.of("Asia/Dhaka"))!!
        assertTrue("Dhaka sunrise ${hm(dha.first)}", kotlin.math.abs(dha.first - (6 * 60 + 41)) < 10)
        assertTrue("Dhaka sunset ${hm(dha.second)}", kotlin.math.abs(dha.second - (17 * 60 + 22)) < 10)
        // Utsjoki (far north Finland) in December: no sunrise at all
        assertNull(SunTimes.minutes(LocalDate.of(2026, 12, 21), 69.9, 27.0, ZoneId.of("Europe/Helsinki")))
        assertTrue(!SunTimes.isPolarDay(LocalDate.of(2026, 12, 21), 69.9))
        assertTrue(SunTimes.isPolarDay(LocalDate.of(2026, 6, 21), 69.9))
    }

    @Test
    fun phasesThroughTheDay() {
        val rise = 7 * 60.0; val set = 19 * 60.0
        assertEquals(IconPhase.NIGHT, DynamicIcon.phaseAt(3 * 60.0, rise, set))
        assertEquals(IconPhase.DUSK, DynamicIcon.phaseAt(6 * 60.0 + 30, rise, set))
        assertEquals(IconPhase.EVENING, DynamicIcon.phaseAt(7 * 60.0 + 10, rise, set))
        assertEquals(IconPhase.DAY, DynamicIcon.phaseAt(12 * 60.0, rise, set))
        assertEquals(IconPhase.EVENING, DynamicIcon.phaseAt(18 * 60.0 + 30, rise, set))
        assertEquals(IconPhase.DUSK, DynamicIcon.phaseAt(19 * 60.0 + 40, rise, set))
        assertEquals(IconPhase.NIGHT, DynamicIcon.phaseAt(21 * 60.0, rise, set))
    }
}
