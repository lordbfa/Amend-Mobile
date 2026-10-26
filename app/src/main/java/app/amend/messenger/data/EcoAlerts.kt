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
)

enum class AlertLevel { INFO, WARNING, DANGER }

data class EcoAlert(val id: String, val level: AlertLevel, val emoji: String, val title: String, val advice: String)

/**
 * Turns Open-Meteo data into plain-language alerts. The thresholds follow common
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

    // ------------------------------------------------------------------ Open-Meteo parsing

    @Suppress("UNCHECKED_CAST")
    fun parse(lat: Double, lon: Double, forecast: String?, air: String?, flood: String?, now: Long, place: String = ""): WeatherSnapshot {
        var w = WeatherSnapshot(lat, lon, place, now)
        runCatching {
            val f = Json.parseObject(forecast ?: "")
            val cur = f["current"] as? Map<String, Any?> ?: emptyMap()
            val daily = f["daily"] as? Map<String, Any?> ?: emptyMap()
            fun d(key: String, i: Int) = ((daily[key] as? List<Any?>)?.getOrNull(i) as? Number)?.toDouble()
            w = w.copy(
                tempC = (cur["temperature_2m"] as? Number)?.toDouble(),
                feelsLikeC = (cur["apparent_temperature"] as? Number)?.toDouble(),
                humidity = (cur["relative_humidity_2m"] as? Number)?.toDouble(),
                windKmh = (cur["wind_speed_10m"] as? Number)?.toDouble(),
                gustKmh = (cur["wind_gusts_10m"] as? Number)?.toDouble(),
                weatherCode = (cur["weather_code"] as? Number)?.toInt(),
                todayMaxFeelsC = d("apparent_temperature_max", 0),
                rainTodayMm = d("precipitation_sum", 0),
                rainTomorrowMm = d("precipitation_sum", 1),
                maxGustKmh = listOfNotNull(d("wind_gusts_10m_max", 0), d("wind_gusts_10m_max", 1)).maxOrNull(),
                uvMax = d("uv_index_max", 0),
            )
        }
        runCatching {
            val a = Json.parseObject(air ?: "")
            val cur = a["current"] as? Map<String, Any?> ?: emptyMap()
            w = w.copy(usAqi = (cur["us_aqi"] as? Number)?.toInt(), pm25 = (cur["pm2_5"] as? Number)?.toDouble())
        }
        runCatching {
            val fl = Json.parseObject(flood ?: "")
            val daily = fl["daily"] as? Map<String, Any?> ?: emptyMap()
            val q = (daily["river_discharge"] as? List<Any?>).orEmpty().mapNotNull { (it as? Number)?.toDouble() }
            if (q.isNotEmpty()) w = w.copy(riverNow = q.first(), riverPeak7d = q.take(7).maxOrNull())
        }
        return w
    }

    fun forecastUrl(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=${lat.f()}&longitude=${lon.f()}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_gusts_10m" +
            "&daily=apparent_temperature_max,precipitation_sum,wind_gusts_10m_max,uv_index_max&forecast_days=2&timezone=auto"

    fun airUrl(lat: Double, lon: Double) =
        "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${lat.f()}&longitude=${lon.f()}&current=us_aqi,pm2_5,pm10"

    fun floodUrl(lat: Double, lon: Double) =
        "https://flood-api.open-meteo.com/v1/flood?latitude=${lat.f()}&longitude=${lon.f()}&daily=river_discharge&forecast_days=8"

    /** Rounded to ~1 km: enough for weather, and it doesn't send your exact position. */
    private fun Double.f() = String.format(Locale.US, "%.2f", this)
    private fun Double.r() = String.format(Locale.US, "%.0f", this)
}
