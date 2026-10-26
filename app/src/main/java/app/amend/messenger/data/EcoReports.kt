package app.amend.messenger.data

import app.amend.messenger.core.nostr.Event
import java.util.Locale

/**
 * Community eco map. Reports are PUBLIC Nostr events (anyone can see them, by
 * design): a photo, a place and what's wrong — or a tree someone planted.
 *
 *   kind 30419 (addressable)  d = report id
 *     ["l", category, "amend.eco"]   trash · water · air · trees_cut · other · planted
 *     ["g", geohash…]                 at 4, 5, 6 and 7 characters, so phones can ask for "reports near me"
 *     ["location", "lat,lon"]
 *     ["image", url] + ["imeta", "url …", "m image/jpeg", "x sha256"]
 *     ["status", "open" | "cleaned"]
 *   content = a short note
 *
 * Others react with kind 7: "✅" = "I checked, it's real", "🧹" = "it's been cleaned up".
 */
enum class EcoCategory(val tag: String, val emoji: String, val label: String, val color: Long) {
    TRASH("trash", "🗑️", "Rubbish dump", 0xFFE65100),
    WATER("water", "💧", "Water pollution", 0xFF1565C0),
    AIR("air", "🏭", "Air pollution / burning", 0xFF616161),
    TREES_CUT("trees_cut", "🪓", "Trees cut down", 0xFF6D4C41),
    OTHER("other", "⚠️", "Other problem", 0xFFF9A825),
    PLANTED("planted", "🌳", "Tree planted", 0xFF2E7D32);

    val isProblem: Boolean get() = this != PLANTED

    companion object {
        fun of(tag: String?) = entries.firstOrNull { it.tag == tag } ?: OTHER
        val problems = entries.filter { it.isProblem }
    }
}

data class EcoReport(
    val id: String,          // d tag
    val eventId: String,
    val author: String,
    val category: EcoCategory,
    val note: String,
    val lat: Double,
    val lon: Double,
    val imageUrl: String?,
    val createdAt: Long,
    val authorCleaned: Boolean,
    val verifiedBy: Set<String> = emptySet(),
    val cleanedBy: Set<String> = emptySet(),
) {
    /** The addressable-event coordinate used in reactions. */
    val address: String get() = "${EcoReports.KIND}:$author:$id"

    /** Cleaned when the reporter says so, or two other people confirm it. */
    val cleaned: Boolean get() = category.isProblem && (authorCleaned || cleanedBy.size >= 2)

    /** A planted tree counts once two other people have checked it. */
    val treeConfirmed: Boolean get() = category == EcoCategory.PLANTED && verifiedBy.size >= EcoReports.CONFIRMATIONS

    fun distanceKm(lat2: Double, lon2: Double): Double = EcoReports.distanceKm(lat, lon, lat2, lon2)
}

object EcoReports {
    const val KIND = 30419
    const val VERIFY = "✅"
    const val CLEANED = "🧹"
    const val CONFIRMATIONS = 2

    fun build(id: String, category: EcoCategory, note: String, lat: Double, lon: Double, imageUrl: String?, imageSha: String?, cleaned: Boolean = false): Pair<String, List<List<String>>> {
        val gh = Geohash.encode(lat, lon, 7)
        val tags = buildList {
            add(listOf("d", id))
            add(listOf("t", "amend-eco"))
            add(listOf("L", "amend.eco"))
            add(listOf("l", category.tag, "amend.eco"))
            listOf(4, 5, 6, 7).forEach { add(listOf("g", gh.take(it))) }
            add(listOf("location", String.format(Locale.US, "%.5f,%.5f", lat, lon)))
            if (imageUrl != null) {
                add(listOf("image", imageUrl))
                add(buildList { add("imeta"); add("url $imageUrl"); add("m image/jpeg"); imageSha?.let { add("x $it") } })
            }
            add(listOf("status", if (cleaned) "cleaned" else "open"))
            add(listOf("alt", "amend eco map: ${category.label}"))
        }
        return note.take(500) to tags
    }

    fun parse(ev: Event): EcoReport? {
        if (ev.kind != KIND) return null
        val d = ev.firstTag("d") ?: return null
        val loc = ev.firstTag("location")?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
        val (lat, lon) = if (loc != null && loc.size == 2) loc[0] to loc[1]
        else ev.tagValues("g").maxByOrNull { it.length }?.let { Geohash.decode(it) } ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val cat = ev.tags.firstOrNull { it.size >= 2 && it[0] == "l" && (it.size < 3 || it[2] == "amend.eco") }?.get(1)
        return EcoReport(
            id = d, eventId = ev.id, author = ev.pubkey, category = EcoCategory.of(cat), note = ev.content.take(500),
            lat = lat, lon = lon, imageUrl = ev.firstTag("image")?.takeIf { it.startsWith("https://") },
            createdAt = ev.createdAt, authorCleaned = ev.firstTag("status") == "cleaned",
        )
    }

    /** Applies a kind-7 reaction. Reactions by the report's own author don't count. */
    fun applyReaction(r: EcoReport, reactor: String, content: String): EcoReport = when {
        reactor == r.author -> r
        content == VERIFY -> r.copy(verifiedBy = r.verifiedBy + reactor)
        content == CLEANED -> r.copy(cleanedBy = r.cleanedBy + reactor)
        else -> r
    }

    fun reactionTags(r: EcoReport): List<List<String>> = listOf(
        listOf("a", r.address), listOf("e", r.eventId), listOf("p", r.author), listOf("k", KIND.toString()), listOf("t", "amend-eco"),
    )

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) * kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/** Standard geohash (base32), used for "near me" queries without revealing exact searches. */
object Geohash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(lat: Double, lon: Double, precision: Int): String {
        var latLo = -90.0; var latHi = 90.0; var lonLo = -180.0; var lonHi = 180.0
        val sb = StringBuilder()
        var bit = 0; var ch = 0; var even = true
        while (sb.length < precision) {
            if (even) {
                val mid = (lonLo + lonHi) / 2
                if (lon >= mid) { ch = ch or (16 shr bit); lonLo = mid } else lonHi = mid
            } else {
                val mid = (latLo + latHi) / 2
                if (lat >= mid) { ch = ch or (16 shr bit); latLo = mid } else latHi = mid
            }
            even = !even
            if (bit < 4) bit++ else { sb.append(BASE32[ch]); bit = 0; ch = 0 }
        }
        return sb.toString()
    }

    fun decode(hash: String): Pair<Double, Double>? {
        var latLo = -90.0; var latHi = 90.0; var lonLo = -180.0; var lonHi = 180.0
        var even = true
        for (c in hash) {
            val v = BASE32.indexOf(c).takeIf { it >= 0 } ?: return null
            for (b in 4 downTo 0) {
                val on = (v shr b) and 1 == 1
                if (even) { val mid = (lonLo + lonHi) / 2; if (on) lonLo = mid else lonHi = mid }
                else { val mid = (latLo + latHi) / 2; if (on) latLo = mid else latHi = mid }
                even = !even
            }
        }
        return (latLo + latHi) / 2 to (lonLo + lonHi) / 2
    }

    /** The cell around a point plus its 8 neighbours (so a report just over a border isn't missed). */
    fun around(lat: Double, lon: Double, precision: Int): List<String> {
        val center = encode(lat, lon, precision)
        val (cLat, cLon) = decode(center)!!
        // cell size at this precision
        val lonBits = (precision * 5 + 1) / 2
        val latBits = precision * 5 / 2
        val dLon = 360.0 / (1L shl lonBits)
        val dLat = 180.0 / (1L shl latBits)
        val out = LinkedHashSet<String>()
        for (i in -1..1) for (j in -1..1) {
            val la = (cLat + i * dLat).coerceIn(-89.999, 89.999)
            var lo = cLon + j * dLon
            if (lo > 180) lo -= 360
            if (lo < -180) lo += 360
            out += encode(la, lo, precision)
        }
        return out.toList()
    }
}
