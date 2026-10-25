package app.amend.messenger.data

import app.amend.messenger.core.json.Json
import app.amend.messenger.core.json.long
import app.amend.messenger.core.json.str
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Walking stays on the phone: nothing in this file is ever sent anywhere. */
data class StepSettings(
    val enabled: Boolean = true,
    val heightCm: Int = 170,
    val dailyGoal: Int = 8_000,
) {
    /** Typical stride ≈ 41.5 % of height. */
    val strideMeters: Double get() = heightCm.coerceIn(100, 230) * 0.415 / 100.0
}

/** One monthly report delivered to the amend chat. */
data class StepReportMessage(val month: String, val createdAt: Long, val text: String, val report: MonthReport)

data class MonthReport(
    val month: String,          // yyyy-MM
    val totalSteps: Long,
    val km: Double,
    val activeDays: Int,        // days with ≥ 1 000 steps
    val goalDays: Int,
    val daysInMonth: Int,
    val bestDay: String?,
    val bestSteps: Int,
    val averagePerDay: Int,
    val co2SavedKg: Double,     // if those km had been driven instead
    val previousSteps: Long?,   // previous month, for comparison
    val daily: List<Int>,       // steps for each day of the month
)

/**
 * Turns the phone's step counter (a running total since boot) into steps per day.
 * Steps between two readings that span midnight are split by time.
 */
class StepLog(
    val days: MutableMap<String, Int> = mutableMapOf(),
    var lastCounter: Long = -1,
    var lastAtMs: Long = 0,
    val reported: MutableSet<String> = mutableSetOf(),
    val reports: MutableList<StepReportMessage> = mutableListOf(),
    var startedMonth: String? = null,
) {
    fun steps(day: LocalDate): Int = days[day.toString()] ?: 0

    /**
     * Records a counter reading. Returns the number of new steps.
     * A smaller value than last time means the phone restarted (the counter resets to 0).
     */
    fun record(counter: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        if (counter < 0) return 0
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        if (startedMonth == null) startedMonth = YearMonth.from(today).toString()
        if (lastCounter < 0 || nowMs < lastAtMs) {
            lastCounter = counter
            lastAtMs = nowMs
            return 0
        }
        val delta = if (counter >= lastCounter) counter - lastCounter else counter
        // sanity: nobody walks 100 000 steps between two readings
        val steps = delta.coerceIn(0, 100_000).toInt()
        if (steps > 0) addSpread(steps, lastAtMs, nowMs, zone)
        lastCounter = counter
        lastAtMs = nowMs
        return steps
    }

    private fun addSpread(steps: Int, fromMs: Long, toMs: Long, zone: ZoneId) {
        val endDay = Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate()
        var day = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
        val span = (toMs - fromMs).coerceAtLeast(1)
        // more than a week apart: we can't tell when they walked, so it all goes to today
        if (day.isBefore(endDay.minusDays(7))) {
            add(endDay, steps)
            return
        }
        var left = steps
        while (day.isBefore(endDay)) {
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val dayStart = max(fromMs, day.atStartOfDay(zone).toInstant().toEpochMilli())
            val part = (steps.toLong() * (dayEnd - dayStart) / span).toInt().coerceAtMost(left)
            add(day, part)
            left -= part
            day = day.plusDays(1)
        }
        add(endDay, left)
    }

    private fun add(day: LocalDate, steps: Int) {
        if (steps <= 0) return
        val key = day.toString()
        days[key] = (days[key] ?: 0) + steps
    }

    /** Keeps about 13 months of daily totals. */
    fun prune(today: LocalDate) {
        val cutoff = today.minusDays(400).toString()
        days.keys.removeAll { it < cutoff }
    }

    fun monthTotal(month: YearMonth): Long =
        (1..month.lengthOfMonth()).sumOf { steps(month.atDay(it)).toLong() }

    fun monthReport(month: YearMonth, settings: StepSettings): MonthReport {
        val daily = (1..month.lengthOfMonth()).map { steps(month.atDay(it)) }
        val total = daily.sumOf { it.toLong() }
        val km = total * settings.strideMeters / 1000.0
        val bestIndex = daily.indices.maxByOrNull { daily[it] }
        val previous = month.minusMonths(1)
        val hasPrevious = days.keys.any { it.startsWith(previous.toString()) }
        return MonthReport(
            month = month.toString(),
            totalSteps = total,
            km = km,
            activeDays = daily.count { it >= 1_000 },
            goalDays = daily.count { it >= settings.dailyGoal },
            daysInMonth = daily.size,
            bestDay = bestIndex?.takeIf { daily[it] > 0 }?.let { month.atDay(it + 1).toString() },
            bestSteps = bestIndex?.let { daily[it] } ?: 0,
            averagePerDay = (total / daily.size).toInt(),
            co2SavedKg = km * CAR_CO2_KG_PER_KM,
            previousSteps = if (hasPrevious) monthTotal(previous) else null,
            daily = daily,
        )
    }

    /**
     * Months that have ended and have not been reported yet (only months the
     * counter was running in). Marks them as reported and returns the new messages.
     */
    fun dueReports(today: LocalDate, settings: StepSettings, nowMs: Long): List<StepReportMessage> {
        val start = startedMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: return emptyList()
        val current = YearMonth.from(today)
        val out = mutableListOf<StepReportMessage>()
        var m = start
        while (m.isBefore(current)) {
            val key = m.toString()
            if (key !in reported) {
                reported += key
                val r = monthReport(m, settings)
                val msg = StepReportMessage(key, nowMs / 1000, reportText(r, settings), r)
                reports += msg
                out += msg
            }
            m = m.plusMonths(1)
        }
        return out
    }

    fun toJson(): String = Json.write(
        linkedMapOf(
            "days" to days.toSortedMap(),
            "lastCounter" to lastCounter,
            "lastAt" to lastAtMs,
            "reported" to reported.sorted(),
            "started" to startedMonth,
            "reports" to reports.map { mapOf("month" to it.month, "at" to it.createdAt) },
        )
    )

    companion object {
        /** Average new car, tailpipe only (EEA). */
        const val CAR_CO2_KG_PER_KM = 0.17

        @Suppress("UNCHECKED_CAST")
        fun fromJson(s: String?, settings: StepSettings): StepLog {
            if (s.isNullOrBlank()) return StepLog()
            return try {
                val m = Json.parseObject(s)
                val log = StepLog(
                    days = ((m["days"] as? Map<String, Any?>).orEmpty()).mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toInt() } }.toMap().toMutableMap(),
                    lastCounter = m.long("lastCounter") ?: -1,
                    lastAtMs = m.long("lastAt") ?: 0,
                    reported = (m["reported"] as? List<Any?>).orEmpty().mapNotNull { it as? String }.toMutableSet(),
                    startedMonth = m.str("started"),
                )
                // reports are rebuilt from the stored days, so their text follows the current settings
                (m["reports"] as? List<Any?>).orEmpty().forEach { e ->
                    val r = e as? Map<String, Any?> ?: return@forEach
                    val month = r.str("month")?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: return@forEach
                    val report = log.monthReport(month, settings)
                    log.reports += StepReportMessage(month.toString(), r.long("at") ?: 0, reportText(report, settings), report)
                }
                log
            } catch (e: Exception) {
                StepLog()
            }
        }

        fun monthName(month: String): String = runCatching {
            val ym = YearMonth.parse(month)
            ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.year
        }.getOrDefault(month)

        fun formatKm(km: Double): String = if (km < 10) "%.2f".format(Locale.US, km) else "%.1f".format(Locale.US, km)

        fun reportText(r: MonthReport, settings: StepSettings): String = buildString {
            append("👣 Your walking report for ${monthName(r.month)}\n\n")
            append("Steps: ${"%,d".format(Locale.US, r.totalSteps)}\n")
            append("Distance: ${formatKm(r.km)} km\n")
            append("Daily average: ${"%,d".format(Locale.US, r.averagePerDay)} steps\n")
            append("Active days: ${r.activeDays} of ${r.daysInMonth}\n")
            append("Goal (${"%,d".format(Locale.US, settings.dailyGoal)}) reached: ${r.goalDays} days\n")
            r.bestDay?.let {
                val d = LocalDate.parse(it)
                append("Best day: ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} — ${"%,d".format(Locale.US, r.bestSteps)} steps\n")
            }
            r.previousSteps?.let { prev ->
                if (prev > 0) {
                    val change = ((r.totalSteps - prev) * 100.0 / prev).roundToInt()
                    append(if (change >= 0) "📈 $change% more than last month\n" else "📉 ${-change}% less than last month\n")
                }
            }
            append("\n🌍 Walking instead of driving those ${formatKm(r.km)} km would avoid about ${"%.1f".format(Locale.US, r.co2SavedKg)} kg of CO₂.")
            append(
                when {
                    r.totalSteps == 0L -> "\n\nNo steps were counted this month. Is step counting allowed for amend?"
                    r.goalDays * 2 >= r.daysInMonth -> "\n\n🌳 Amazing month — keep it up!"
                    r.activeDays * 2 >= r.daysInMonth -> "\n\n🌿 Good rhythm. A short walk each day adds up."
                    else -> "\n\n🌱 Every walk counts. Try a 10-minute walk after a meal."
                }
            )
        }
    }
}
