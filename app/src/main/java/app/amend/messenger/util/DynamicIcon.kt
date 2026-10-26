package app.amend.messenger.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** The four looks of the launcher icon through the day. */
enum class IconPhase(val alias: String) {
    DAY("app.amend.messenger.IconDay"),       // white
    EVENING("app.amend.messenger.IconEvening"), // light grey (golden hour / early morning)
    DUSK("app.amend.messenger.IconDusk"),     // grey
    NIGHT("app.amend.messenger.IconNight"),   // dark forest
}

/** Sunrise and sunset (local time, minutes after midnight) for a place and date — NOAA's simplified solar equations. */
object SunTimes {
    /** Returns (sunrise, sunset) in minutes, or null in polar day / polar night. */
    fun minutes(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): Pair<Double, Double>? {
        val n = date.dayOfYear
        val gamma = 2 * Math.PI / 365.0 * (n - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) - 0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val latR = Math.toRadians(lat)
        val cosHa = cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (cosHa < -1 || cosHa > 1) return null
        val ha = Math.toDegrees(acos(cosHa))
        val offsetMin = zone.rules.getOffset(date.atStartOfDay(zone).toInstant()).totalSeconds / 60.0
        val noon = 720 - 4 * lon - eqTime + offsetMin
        return (noon - 4 * ha) to (noon + 4 * ha)
    }

    /** Polar summer (sun never sets) vs polar winter, from the sun's noon height. */
    fun isPolarDay(date: LocalDate, lat: Double): Boolean {
        val gamma = 2 * Math.PI / 365.0 * (date.dayOfYear - 1)
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma)
        return asin(sin(Math.toRadians(lat)) * sin(decl) + cos(Math.toRadians(lat)) * cos(decl)) > 0
    }
}

object DynamicIcon {
    private const val WORK = "amend-icon"
    private fun prefs(c: Context) = c.getSharedPreferences("amend_icon", Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = prefs(c).getBoolean("enabled", true)

    /**
     * Which look fits this moment:
     *   day      from 30 min after sunrise until an hour before sunset
     *   evening  the last hour before sunset (and the first half hour after sunrise)
     *   dusk     the hour after sunset (and the 45 min before sunrise)
     *   night    the rest
     */
    fun phaseAt(minuteOfDay: Double, sunrise: Double, sunset: Double): IconPhase = when {
        minuteOfDay >= sunrise + 30 && minuteOfDay < sunset - 60 -> IconPhase.DAY
        minuteOfDay >= sunset - 60 && minuteOfDay < sunset + 15 -> IconPhase.EVENING
        minuteOfDay >= sunrise && minuteOfDay < sunrise + 30 -> IconPhase.EVENING
        minuteOfDay >= sunset + 15 && minuteOfDay < sunset + 75 -> IconPhase.DUSK
        minuteOfDay >= sunrise - 45 && minuteOfDay < sunrise -> IconPhase.DUSK
        else -> IconPhase.NIGHT
    }

    fun currentPhase(c: Context, now: ZonedDateTime = ZonedDateTime.now()): IconPhase {
        val minute = now.hour * 60 + now.minute + now.second / 60.0
        val loc = SosManager.lastKnownLocation(c)
        val p = prefs(c)
        // remember the last place, so it works even when the phone has no fresh location
        val lat = loc?.lat ?: p.getString("lat", null)?.toDoubleOrNull()
        val lon = loc?.lon ?: p.getString("lon", null)?.toDoubleOrNull()
        if (loc != null) p.edit().putString("lat", loc.lat.toString()).putString("lon", loc.lon.toString()).apply()
        if (lat == null || lon == null) return phaseAt(minute, 7 * 60.0, 19 * 60.0)
        val sun = SunTimes.minutes(now.toLocalDate(), lat, lon, now.zone)
            ?: return if (SunTimes.isPolarDay(now.toLocalDate(), lat)) IconPhase.DAY else IconPhase.NIGHT
        return phaseAt(minute, sun.first, sun.second)
    }

    /** Switches the launcher icon if the time of day has moved on. Cheap to call often. */
    fun update(c: Context) {
        val want = if (isEnabled(c)) currentPhase(c) else IconPhase.NIGHT
        val p = prefs(c)
        if (p.getString("phase", null) == want.name && aliasEnabled(c, want)) return
        val pm = c.packageManager
        // enable the new look first, then hide the others: there is always one launcher entry
        pm.setComponentEnabledSetting(ComponentName(c.packageName, want.alias), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        IconPhase.entries.filter { it != want }.forEach {
            pm.setComponentEnabledSetting(ComponentName(c.packageName, it.alias), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        p.edit().putString("phase", want.name).apply()
    }

    private fun aliasEnabled(c: Context, phase: IconPhase): Boolean {
        val s = c.packageManager.getComponentEnabledSetting(ComponentName(c.packageName, phase.alias))
        return s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || (s == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && phase == IconPhase.DAY)
    }

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("enabled", on).apply()
        update(c)
        schedule(c)
    }

    /** Checks every 15 minutes (the shortest regular interval Android allows). */
    fun schedule(c: Context) {
        val req = PeriodicWorkRequestBuilder<IconWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class IconWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { DynamicIcon.update(applicationContext) }
        return Result.success()
    }
}
