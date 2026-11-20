package app.amend.messenger.data

import app.amend.messenger.core.json.Json
import java.util.Locale

/** What the Forest tab shows about the weather and air where you are. */
data class WeatherSnapshot(
    val lat: Double,
    val lon: Double,
    val place: String = "",
    val fetchedAt: Long = 0,
    val tempC: Double? = null,
    val feelsLikeC: Double? = null,
    val humidity: Double? = null,
    val windKmh: Double? = null,
    val gustKmh: Double? = null,
    val weatherCode: Int? = null,
    val todayMaxFeelsC: Double? = null,
    val rainTodayMm: Double? = null,
    val rainTomorrowMm: Double? = null,
    val maxGustKmh: Double? = null,
    val uvMax: Double? = null,
    val usAqi: Int? = null,
    val pm25: Double? = null,
    val riverNow: Double? = null,
    val riverPeak7d: Double? = null,
    /** ISO country code of the weather location (from the phone's geocoder), e.g. "BD", "FI". */
    val country: String = "",
)

enum class AlertLevel { INFO, WARNING, DANGER }

data class EcoAlert(val id: String, val level: AlertLevel, val emoji: String, val title: String, val advice: String)

/**
 * Turns MET Norway forecasts into plain-language alerts. The thresholds follow common
 * public-health guidance (heat index, US EPA AQI bands, WMO wind scale). They are
 * an early nudge, not an official warning service.
 */
object EcoAlerts {
    fun alerts(w: WeatherSnapshot): List<EcoAlert> = buildList {
        val feels = w.todayMaxFeelsC ?: w.feelsLikeC
        if (feels != null) {
            when {
                feels >= 46 -> add(EcoAlert("heat3", AlertLevel.DANGER, "🔥", "Extreme heat — feels like ${feels.r()}°C",
                    "Stay indoors in the hottest hours, drink water often, check on elderly neighbours and children."))
                feels >= 40 -> add(EcoAlert("heat2", AlertLevel.WARNING, "🌡️", "Heatwave — feels like ${feels.r()}°C",
                    "Avoid the midday sun, drink water before you're thirsty, rest in shade."))
                feels >= 35 -> add(EcoAlert("heat1", AlertLevel.INFO, "☀️", "Very hot — feels like ${feels.r()}°C", "Keep water with you and take breaks in the shade."))
            }
            if (feels <= -25) add(EcoAlert("cold", AlertLevel.WARNING, "🥶", "Severe cold — feels like ${feels.r()}°C", "Cover skin, limit time outside."))
        }
        val rain = maxOf(w.rainTodayMm ?: 0.0, w.rainTomorrowMm ?: 0.0)
        when {
            rain >= 100 -> add(EcoAlert("rain3", AlertLevel.DANGER, "🌊", "Extremely heavy rain — ${rain.r()} mm expected",
                "Flooding is likely. Move valuables and documents up high, avoid low roads, keep your phone charged."))
            rain >= 50 -> add(EcoAlert("rain2", AlertLevel.WARNING, "🌧️", "Heavy rain — ${rain.r()} mm expected",
                "Local flooding and waterlogging possible. Avoid walking through flood water."))
        }
        val river = w.riverNow
        val peak = w.riverPeak7d
        if (river != null && peak != null && peak >= 100 && peak >= river * 1.8) {
            add(EcoAlert("river", AlertLevel.WARNING, "🏞️", "River rising — up to ${peak.r()} m³/s this week",
                "The nearest river is forecast to rise sharply. Follow official flood warnings and know your way to higher ground."))
        }
        val gust = maxOf(w.maxGustKmh ?: 0.0, w.gustKmh ?: 0.0)
        when {
            gust >= 90 -> add(EcoAlert("wind2", AlertLevel.DANGER, "🌀", "Storm winds — gusts up to ${gust.r()} km/h",
                "Stay inside, away from windows. If a cyclone warning is issued, go to a shelter early."))
            gust >= 62 -> add(EcoAlert("wind1", AlertLevel.WARNING, "💨", "Strong wind — gusts up to ${gust.r()} km/h", "Secure loose objects; take care near trees."))
        }
        val aqi = w.usAqi
        if (aqi != null) {
            when {
                aqi > 200 -> add(EcoAlert("air3", AlertLevel.DANGER, "😷", "Very unhealthy air (AQI $aqi)",
                    "Stay indoors with windows closed. Wear an N95 mask if you must go out."))
                aqi > 150 -> add(EcoAlert("air2", AlertLevel.WARNING, "😷", "Unhealthy air (AQI $aqi)", "Limit time outdoors, especially exercise. A mask helps."))
                aqi > 100 -> add(EcoAlert("air1", AlertLevel.INFO, "🌫️", "Air unhealthy for sensitive groups (AQI $aqi)",
                    "Children, older people and anyone with asthma should take it easy outside."))
            }
        }
        val uv = w.uvMax
        if (uv != null && uv >= 11) add(EcoAlert("uv", AlertLevel.INFO, "🧴", "Extreme UV (${uv.r()})", "Cover up and use sunscreen between 10 and 16."))
    }

    fun aqiLabel(aqi: Int): String = when {
        aqi <= 50 -> "Good"
        aqi <= 100 -> "Moderate"
        aqi <= 150 -> "Unhealthy for sensitive groups"
        aqi <= 200 -> "Unhealthy"
        aqi <= 300 -> "Very unhealthy"
        else -> "Hazardous"
    }

    /** WMO weather code → emoji + words. */
    fun describe(code: Int?): Pair<String, String> = when (code) {
        0 -> "☀️" to "Clear"
        1, 2 -> "🌤️" to "Partly cloudy"
        3 -> "☁️" to "Cloudy"
        45, 48 -> "🌫️" to "Fog"
        51, 53, 55, 56, 57 -> "🌦️" to "Drizzle"
        61, 63, 66, 80, 81 -> "🌧️" to "Rain"
        65, 67, 82 -> "🌧️" to "Heavy rain"
        71, 73, 75, 77, 85, 86 -> "🌨️" to "Snow"
        95, 96, 99 -> "⛈️" to "Thunderstorm"
        else -> "🌍" to "—"
    }

    /** A ready-to-send "I'm safe" message. */
    fun safeMessage(alerts: List<EcoAlert>, place: String): String {
        val where = if (place.isNotBlank()) " in $place" else ""
        val what = alerts.firstOrNull()?.title?.substringBefore(" —")?.lowercase(Locale.ENGLISH)
        return if (what != null) "💚 I'm safe. There's a $what alert$where, but I'm okay. How are you?"
        else "💚 I'm safe and okay$where. How are you?"
    }

    // ------------------------------------------------------------------ MET Norway parsing

    /**
     * Reads MET Norway's Locationforecast 2.0 "complete" JSON. Wind comes in m/s and is
     * shown in km/h. "Feels like" uses MET's apparent_air_temperature when it is there,
     * otherwise Steadman's apparent temperature (temperature, humidity, wind).
     */
    @Suppress("UNCHECKED_CAST")
    fun parseMet(lat: Double, lon: Double, json: String?, now: Long, place: String = "", country: String = ""): WeatherSnapshot {
        var w = WeatherSnapshot(lat, lon, place, now, country = country)
        runCatching {
            val root = Json.parseObject(json ?: "")
            val props = root["properties"] as? Map<String, Any?> ?: return w
            val series = (props["timeseries"] as? List<Any?>).orEmpty().mapNotNull { it as? Map<String, Any?> }
            if (series.isEmpty()) return w
            fun timeOf(e: Map<String, Any?>) = runCatching { java.time.Instant.parse(e["time"] as String).toEpochMilli() }.getOrNull()
            fun data(e: Map<String, Any?>) = e["data"] as? Map<String, Any?> ?: emptyMap()
            fun instant(e: Map<String, Any?>) = (data(e)["instant"] as? Map<String, Any?>)?.get("details") as? Map<String, Any?> ?: emptyMap()
            fun period(e: Map<String, Any?>, key: String) = data(e)[key] as? Map<String, Any?>
            fun num(m: Map<String, Any?>?, k: String) = (m?.get(k) as? Number)?.toDouble()
            fun feels(d: Map<String, Any?>): Double? {
                num(d, "apparent_air_temperature")?.let { return it }
                val t = num(d, "air_temperature") ?: return null
                val rh = num(d, "relative_humidity") ?: return t
                val ws = num(d, "wind_speed") ?: 0.0
                val e = rh / 100.0 * 6.105 * kotlin.math.exp(17.27 * t / (237.7 + t))
                return t + 0.33 * e - 0.70 * ws - 4.00
            }
            // the entry closest to now
            val first = series.minByOrNull { kotlin.math.abs((timeOf(it) ?: Long.MAX_VALUE / 2) - now) } ?: series.first()
            val cur = instant(first)
            val symbol = (period(first, "next_1_hours") ?: period(first, "next_6_hours"))
                ?.let { (it["summary"] as? Map<String, Any?>)?.get("symbol_code") as? String }
            val day = 24 * 3_600_000L
            val next24 = series.filter { val t = timeOf(it); t != null && t >= now - 3_600_000 && t < now + day }
            val next48 = series.filter { val t = timeOf(it); t != null && t >= now - 3_600_000 && t < now + 2 * day }
            // precipitation per day without counting hours twice (hourly steps first, 6-hourly later)
            fun rain(from: Long, to: Long): Double? {
                var sum = 0.0
                var covered = from
                var any = false
                series.forEach { e ->
                    val t = timeOf(e) ?: return@forEach
                    if (t < covered || t >= to) return@forEach
                    val one = num(period(e, "next_1_hours")?.get("details") as? Map<String, Any?>, "precipitation_amount")
                    val six = num(period(e, "next_6_hours")?.get("details") as? Map<String, Any?>, "precipitation_amount")
                    when {
                        one != null -> { sum += one; covered = t + 3_600_000; any = true }
                        six != null -> { sum += six; covered = t + 6 * 3_600_000; any = true }
                    }
                }
                return if (any) sum else null
            }
            val gusts = next48.mapNotNull { num(instant(it), "wind_speed_of_gust") }
            w = w.copy(
                tempC = num(cur, "air_temperature"),
                feelsLikeC = feels(cur),
                humidity = num(cur, "relative_humidity"),
                windKmh = num(cur, "wind_speed")?.times(3.6),
                gustKmh = num(cur, "wind_speed_of_gust")?.times(3.6),
                weatherCode = symbolToCode(symbol),
                todayMaxFeelsC = next24.mapNotNull { feels(instant(it)) }.maxOrNull(),
                rainTodayMm = rain(now, now + day),
                rainTomorrowMm = rain(now + day, now + 2 * day),
                maxGustKmh = gusts.maxOrNull()?.times(3.6),
                uvMax = next24.mapNotNull { num(instant(it), "ultraviolet_index_clear_sky") }.maxOrNull(),
            )
        }
        return w
    }

    /** MET Norway symbol_code (e.g. "lightrainshowers_day") → the WMO-style code [describe] understands. */
    fun symbolToCode(symbol: String?): Int? {
        val s = symbol?.substringBefore('_') ?: return null
        return when {
            s == "clearsky" -> 0
            s == "fair" || s == "partlycloudy" -> 2
            s == "cloudy" -> 3
            s == "fog" -> 45
            "thunder" in s -> 95
            "snow" in s || "sleet" in s -> 73
            s.startsWith("heavyrain") -> 65
            "drizzle" in s || s.startsWith("lightrain") -> 53
            "rain" in s -> 63
            else -> null
        }
    }

    /** MET Norway asks every app to identify itself with a contact. */
    fun metUserAgent(version: String) = "amend/$version (https://getamend.app; lordbfa@getamend.app)"

    /** Locationforecast 2.0, complete set (gusts, UV, humidity). */
    fun metUrl(lat: Double, lon: Double) =
        "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=${lat.f()}&lon=${lon.f()}"

    /** Rounded to ~1 km: enough for weather, and it doesn't send your exact position. */
    private fun Double.f() = String.format(Locale.US, "%.2f", this)
    private fun Double.r() = String.format(Locale.US, "%.0f", this)
}
