package app.amend.messenger.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.amend.messenger.data.WeatherSnapshot
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * Turns "where you are + what month it is + the live weather" into a mood for the
 * forest graphics: which season the trees wear, and whether rain or snow is falling.
 *
 * Seasons are country-aware, because they don't line up around the world. Finland runs
 * the four northern seasons; Bangladesh and other monsoon countries get a rainy season
 * instead of a spring. The live weather (Open-Meteo, already fetched for the eco alerts)
 * then overrides the default: if it is actually raining or snowing where you are, the
 * forest rains or gets covered in snow, whatever the month.
 */
enum class Season { SPRING, SUMMER, AUTUMN, WINTER, MONSOON, DRY }

/** How the forest should look right now. All amounts are 0..1. */
data class SceneFx(
    val season: Season = Season.SUMMER,
    val autumn: Float = 0f, // warm/falling-leaf tint
    val snow: Float = 0f,   // snow on the trees and ground
    val rain: Float = 0f,   // rain falling over the forest
) {
    val hasPrecip get() = snow > 0.02f || rain > 0.02f
}

object SceneMood {

    /** Countries that run a South-Asian style monsoon calendar (rainy season instead of spring). */
    private val MONSOON_COUNTRIES = setOf("BD", "IN", "PK", "NP", "LK", "MM", "TH", "KH", "LA", "VN", "ID", "PH", "MY")
    /** Countries cold enough that winter normally means snow, even without a live reading. */
    private val SNOWY_COUNTRIES = setOf("FI", "NO", "SE", "IS", "EE", "LV", "LT", "RU", "CA", "GL", "KZ", "MN", "BY", "UA", "PL")

    /** The season for a country (ISO-3166) and month (1..12), using latitude for the hemisphere when known. */
    fun seasonFor(country: String, month: Int, lat: Double?): Season {
        val c = country.uppercase(Locale.US)
        if (c in MONSOON_COUNTRIES) return when (month) {
            3, 4, 5 -> Season.SUMMER      // grishmo — hot, dry
            6, 7, 8, 9 -> Season.MONSOON  // borsha — the rains
            10, 11 -> Season.AUTUMN       // shorot / hemonto
            else -> Season.WINTER         // sheet — mild winter
        }
        // Northern four-season calendar; mirror it below the equator.
        val m = if (lat != null && lat < 0) ((month + 5) % 12) + 1 else month
        return when (m) {
            12, 1, 2 -> Season.WINTER
            3, 4, 5 -> Season.SPRING
            6, 7, 8 -> Season.SUMMER
            else -> Season.AUTUMN
        }
    }

    private fun isSnowyClimate(country: String, lat: Double?, tempC: Double?): Boolean =
        (tempC != null && tempC <= 1.0) ||
            country.uppercase(Locale.US) in SNOWY_COUNTRIES ||
            (lat != null && abs(lat) >= 55.0)

    /** WMO weather code + temperature → is it raining / snowing right now? */
    private fun precipFromCode(code: Int?, tempC: Double?): Precip {
        if (code == null) return Precip.NONE
        return when (code) {
            71, 72, 73, 74, 75, 76, 77, 85, 86 -> Precip.SNOW
            in 51..67, in 80..82, 95, 96, 99 ->
                if (tempC != null && tempC <= 0.5) Precip.SNOW else Precip.RAIN
            else -> Precip.NONE
        }
    }

    private enum class Precip { NONE, RAIN, SNOW }

    /** Combine season defaults with the live reading (which wins when present). */
    fun fx(weather: WeatherSnapshot?, country: String, month: Int): SceneFx {
        val lat = weather?.lat
        val season = seasonFor(country, month, lat)
        val snowyClimate = isSnowyClimate(country, lat, weather?.tempC)

        // seasonal defaults (used when there's no live precipitation)
        var autumn = when (season) {
            Season.AUTUMN -> 1f
            Season.WINTER -> 0.35f
            else -> 0f
        }
        var snow = if (season == Season.WINTER && snowyClimate) 0.6f else 0f
        var rain = if (season == Season.MONSOON) 0.65f else 0f

        // live weather overrides the "is it precipitating" part
        when (precipFromCode(weather?.weatherCode, weather?.tempC)) {
            Precip.SNOW -> { snow = 1f; rain = 0f }
            Precip.RAIN -> { rain = maxOf(rain, 0.9f); snow = 0f }
            Precip.NONE -> {
                // clear/cloudy now: keep lingering winter snow, but no active rain
                if (weather != null) rain = 0f
            }
        }
        return SceneFx(season, autumn.coerceIn(0f, 1f), snow.coerceIn(0f, 1f), rain.coerceIn(0f, 1f))
    }

    /** The device's country (ISO-3166), from the locale. */
    fun country(): String = runCatching { Locale.getDefault().country }.getOrNull()?.ifBlank { "" } ?: ""
}

/** Live scene mood: recomputes when the weather reading changes. */
@Composable
fun rememberSceneFx(): SceneFx {
    val weather by EcoWeather.state.collectAsStateWithLifecycle()
    val country = remember { SceneMood.country() }
    val month = remember { LocalDate.now().monthValue }
    return SceneMood.fx(weather, country, month)
}
