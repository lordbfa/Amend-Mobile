package app.amend.messenger

import app.amend.messenger.data.StepLog
import app.amend.messenger.data.StepSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class StepsTest {
    private val zone = ZoneId.of("Europe/Helsinki")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun countsDeltasAndSurvivesReboot() {
        val log = StepLog()
        assertEquals(0, log.record(10_000, ms(2026, 10, 5, 8), zone)) // first reading is the baseline
        assertEquals(1_500, log.record(11_500, ms(2026, 10, 5, 9), zone))
        assertEquals(300, log.record(300, ms(2026, 10, 5, 12), zone))     // phone restarted: counter from 0
        assertEquals(1_800, log.steps(LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun aGlitchyZeroReadingIsNotMistakenForARestart() {
        // Some Android 10 phones report 0 for a moment when the sensor is switched on.
        val boot = ms(2026, 10, 5, 6)
        val log = StepLog()
        log.record(25_000, ms(2026, 10, 5, 8), zone, boot)
        assertEquals(0, log.record(0, ms(2026, 10, 5, 9), zone, boot))        // same boot: ignored
        assertEquals(10, log.record(25_010, ms(2026, 10, 5, 10), zone, boot))  // not 25 010 phantom steps
        assertEquals(10, log.steps(LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun aRealRestartIsStillCounted() {
        val log = StepLog()
        log.record(25_000, ms(2026, 10, 5, 8), zone, ms(2026, 10, 5, 6))
        // the phone restarted at 11:00 and has counted 400 steps since
        assertEquals(400, log.record(400, ms(2026, 10, 5, 12), zone, ms(2026, 10, 5, 11)))
        // and a restart after which more steps were counted than before still counts from 0
        assertEquals(30_000, log.record(30_000, ms(2026, 10, 5, 20), zone, ms(2026, 10, 5, 13)))
    }

    @Test
    fun stepsAcrossMidnightAreSplitByTime() {
        val log = StepLog()
        log.record(0, ms(2026, 10, 5, 23), zone)
        log.record(2_000, ms(2026, 10, 6, 1), zone)  // 1 h before midnight, 1 h after
        assertEquals(1_000, log.steps(LocalDate.of(2026, 10, 5)))
        assertEquals(1_000, log.steps(LocalDate.of(2026, 10, 6)))
    }

    @Test
    fun monthlyReportIsDueOnceAfterTheMonthEnds() {
        val settings = StepSettings(heightCm = 180, dailyGoal = 5_000)
        val log = StepLog()
        log.record(0, ms(2026, 9, 1, 7), zone)
        var c = 0L
        for (d in 1..30) {
            c += if (d % 2 == 0) 6_000 else 2_000
            log.record(c, ms(2026, 9, d, 21), zone)
        }
        assertTrue(log.dueReports(LocalDate.of(2026, 9, 30), settings, 0).isEmpty())
        val due = log.dueReports(LocalDate.of(2026, 10, 1), settings, 0)
        assertEquals(1, due.size)
        val r = due[0].report
        assertEquals(120_000L, r.totalSteps)
        assertEquals(15, r.goalDays)
        assertEquals(30, r.activeDays)
        assertEquals(120_000 * 1.80 * 0.415 / 1000, r.km, 0.01)
        assertTrue(due[0].text.contains("September 2026"))
        assertTrue(log.dueReports(LocalDate.of(2026, 10, 2), settings, 0).isEmpty()) // not twice

        // survives saving and loading
        val back = StepLog.fromJson(log.toJson(), settings)
        assertEquals(120_000L, back.monthTotal(YearMonth.of(2026, 9)))
        assertEquals(1, back.reports.size)
        assertTrue(back.dueReports(LocalDate.of(2026, 10, 3), settings, 0).isEmpty())
    }
}
