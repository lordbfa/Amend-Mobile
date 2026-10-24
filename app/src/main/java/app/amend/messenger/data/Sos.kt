package app.amend.messenger.data

import app.amend.messenger.core.nostr.Event
import java.util.Locale

/** A location fix shared during an SOS. */
data class SosLocation(val lat: Double, val lon: Double, val accuracyM: Float, val time: Long) {
    fun encode(): String = String.format(Locale.US, "%.6f,%.6f,%.0f", lat, lon, accuracyM)

    /** Works in any browser, no map app or Google account needed. */
    fun mapLink(): String = String.format(Locale.US, "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=17/%.6f/%.6f", lat, lon, lat, lon)

    /** Opens whatever maps app the phone has. */
    fun geoUri(): String = String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f", lat, lon, lat, lon)

    companion object {
        fun decode(s: String?, time: Long): SosLocation? {
            val parts = s?.split(",") ?: return null
            if (parts.size < 2) return null
            val lat = parts[0].trim().toDoubleOrNull() ?: return null
            val lon = parts[1].trim().toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val acc = parts.getOrNull(2)?.trim()?.toFloatOrNull() ?: 0f
            return SosLocation(lat, lon, acc, time)
        }
    }
}

enum class SosPhase(val tag: String) {
    START("start"), UPDATE("update"), STOP("stop");

    companion object {
        fun of(tag: String?) = entries.firstOrNull { it.tag == tag }
    }
}

/** What a receiver knows about a friend's SOS. */
data class IncomingSos(
    val peer: String,
    val active: Boolean,
    val startedAt: Long,
    val updatedAt: Long,
    val location: SosLocation?,
)

/** A parsed SOS signal from a rumor. */
data class SosSignal(val phase: SosPhase, val location: SosLocation?)

/**
 * SOS messages are ordinary private (gift-wrapped) chat messages with extra tags:
 *   ["amend", "sos", "start" | "update" | "stop"]
 *   ["amend-loc", "lat,lon,accuracy"]
 * The text is human-readable with a map link, so even other Nostr apps show something useful.
 */
object SosProtocol {
    fun tags(peer: String, phase: SosPhase, location: SosLocation?): List<List<String>> {
        val t = mutableListOf(listOf("p", peer), listOf("amend", "sos", phase.tag))
        location?.let { t.add(listOf("amend-loc", it.encode())) }
        return t
    }

    fun content(phase: SosPhase, location: SosLocation?): String = when (phase) {
        SosPhase.START -> "🆘 SOS — I need help!" + (location?.let { " My live location: ${it.mapLink()}" } ?: " (finding my location…)")
        SosPhase.UPDATE -> "📍 SOS live location: " + (location?.mapLink() ?: "unavailable")
        SosPhase.STOP -> "✅ I'm safe now. SOS ended."
    }

    fun parse(rumor: Event): SosSignal? {
        val phase = rumor.tags.firstOrNull { it.size >= 3 && it[0] == "amend" && it[1] == "sos" }?.let { SosPhase.of(it[2]) }
            ?: return null
        val loc = SosLocation.decode(rumor.firstTag("amend-loc"), rumor.createdAt)
        return SosSignal(phase, loc)
    }
}

/**
 * National emergency numbers by ISO country code. The number is used to open the
 * phone's dialer; the user can change it in Safety settings. 112 is the fallback —
 * it works on most mobile networks worldwide and in the whole EU.
 */
object EmergencyNumbers {
    private val byCountry = mapOf(
        "BD" to "999", "IN" to "112", "PK" to "15", "NP" to "100", "LK" to "119",
        "US" to "911", "CA" to "911", "MX" to "911", "AR" to "911", "BR" to "190",
        "GB" to "999", "IE" to "112", "AU" to "000", "NZ" to "111",
        "JP" to "110", "CN" to "110", "KR" to "112", "TW" to "110", "HK" to "999",
        "SA" to "911", "AE" to "999", "QA" to "999", "KW" to "112", "BH" to "999", "OM" to "9999",
        "MY" to "999", "SG" to "999", "PH" to "911", "ID" to "112", "TH" to "191", "VN" to "113",
        "ZA" to "10111", "NG" to "112", "KE" to "999", "EG" to "122", "TR" to "112", "RU" to "112",
    )

    fun forCountry(iso: String?): String =
        iso?.uppercase()?.let { byCountry[it] } ?: "112"
}
