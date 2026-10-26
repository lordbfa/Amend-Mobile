package app.amend.messenger.util

import android.annotation.SuppressLint
import android.content.Context
import app.amend.messenger.core.crypto.Rand
import app.amend.messenger.core.crypto.toHex
import app.amend.messenger.core.nostr.Event
import app.amend.messenger.core.nostr.Kinds
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.EcoCategory
import app.amend.messenger.data.EcoReport
import app.amend.messenger.data.EcoReports
import app.amend.messenger.data.Geohash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The community eco map: loads public reports around you (and your own, and any
 * a friend asked you to check), keeps their ✅ / 🧹 counts, and hands out points.
 */
@SuppressLint("StaticFieldLeak") // application context only
object EcoMap {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var context: Context
    private var repo: AmendRepository? = null
    private var listenJob: Job? = null
    private var reactionsJob: Job? = null

    private val _reports = MutableStateFlow<Map<String, EcoReport>>(emptyMap())
    /** All known reports, by address ("30419:author:d"). */
    val reports: StateFlow<Map<String, EcoReport>> = _reports.asStateFlow()

    private val pendingReactions = mutableMapOf<String, MutableList<Pair<String, String>>>()
    private val watched = mutableSetOf<String>()
    private var center: Pair<Double, Double>? = null

    private fun prefs() = context.getSharedPreferences("amend_eco_map", Context.MODE_PRIVATE)

    fun attach(appContext: Context, repository: AmendRepository) {
        context = appContext.applicationContext
        repo = repository
        listenJob?.cancel()
        listenJob = scope.launch { repository.ecoEvents.collect { onEvent(it) } }
        // our own reports and planted trees, wherever they are
        repository.subscribeEco("mine", listOf(linkedMapOf("kinds" to listOf(EcoReports.KIND), "authors" to listOf(repository.me), "limit" to 200)))
    }

    /** Load reports around a place (about 40 km × 20 km cells, plus neighbours). */
    fun loadAround(lat: Double, lon: Double) {
        val r = repo ?: return
        val c = center
        if (c != null && EcoReports.distanceKm(c.first, c.second, lat, lon) < 5) return
        center = lat to lon
        r.subscribeEco("near", listOf(linkedMapOf("kinds" to listOf(EcoReports.KIND), "#g" to Geohash.around(lat, lon, 4), "limit" to 400)))
    }

    /** A friend's planted tree from a chat card: fetch it even if it's far away. */
    fun watch(address: String) {
        val r = repo ?: return
        if (!watched.add(address)) return
        val parts = address.split(":")
        if (parts.size != 3) return
        r.subscribeEco("w" + address.hashCode().toUInt().toString(16), listOf(linkedMapOf("kinds" to listOf(EcoReports.KIND), "authors" to listOf(parts[1]), "#d" to listOf(parts[2]))))
        scheduleReactions()
    }

    private fun onEvent(ev: Event) {
        when (ev.kind) {
            EcoReports.KIND -> {
                val parsed = EcoReports.parse(ev) ?: return
                var isNew = false
                _reports.update { all ->
                    val old = all[parsed.address]
                    if (old != null && old.createdAt > parsed.createdAt) return@update all
                    isNew = old == null
                    var merged = parsed.copy(verifiedBy = old?.verifiedBy.orEmpty(), cleanedBy = old?.cleanedBy.orEmpty())
                    pendingReactions.remove(parsed.address)?.forEach { (who, what) -> merged = EcoReports.applyReaction(merged, who, what) }
                    all + (parsed.address to merged)
                }
                if (isNew) scheduleReactions()
                checkRewards()
            }
            Kinds.REACTION -> {
                val address = ev.firstTag("a") ?: return
                if (!address.startsWith("${EcoReports.KIND}:")) return
                val content = ev.content.trim()
                if (content != EcoReports.VERIFY && content != EcoReports.CLEANED) return
                val known = _reports.value[address]
                if (known == null) {
                    pendingReactions.getOrPut(address) { mutableListOf() }.add(ev.pubkey to content)
                    return
                }
                _reports.update { all -> all + (address to EcoReports.applyReaction(known, ev.pubkey, content)) }
                checkRewards()
            }
        }
    }

    /** Re-asks for ✅/🧹 reactions once new reports have arrived (batched). */
    private fun scheduleReactions() {
        reactionsJob?.cancel()
        reactionsJob = scope.launch {
            delay(1_500)
            val r = repo ?: return@launch
            val addresses = (_reports.value.keys + watched).toList().takeLast(400)
            if (addresses.isEmpty()) return@launch
            val filters = addresses.chunked(150).map { linkedMapOf<String, Any?>("kinds" to listOf(Kinds.REACTION), "#a" to it, "limit" to 2000) }
            r.subscribeEco("reactions", filters)
        }
    }

    // ------------------------------------------------------------------ actions

    /** Publishes a new report (photo optional). Returns it, or null if no relay took it. */
    suspend fun submit(category: EcoCategory, note: String, lat: Double, lon: Double, jpeg: ByteArray?): EcoReport? {
        val r = repo ?: return null
        val urls = if (jpeg != null) runCatching { r.uploadPublicImage(jpeg) }.getOrNull() else null
        if (jpeg != null && urls.isNullOrEmpty()) throw IllegalStateException("Photo upload failed")
        val sha = jpeg?.let { app.amend.messenger.core.crypto.Hashes.sha256(it).toHex() }
        val id = Rand.bytes(8).toHex()
        val (content, tags) = EcoReports.build(id, category, note, lat, lon, urls?.firstOrNull(), sha)
        val (ev, accepted) = r.publishPublic(EcoReports.KIND, content, tags)
        if (accepted == 0) return null
        // points: up to three reports a day count
        val p = prefs()
        val today = LocalDate.now().toString()
        val n = if (p.getString("reportDay", "") == today) p.getInt("reportCount", 0) else 0
        if (n < 3) {
            r.awardEco(if (category == EcoCategory.PLANTED) 15 else 10, if (category == EcoCategory.PLANTED) "Tree planted (waiting for checks)" else "Eco report")
            p.edit().putString("reportDay", today).putInt("reportCount", n + 1).apply()
        }
        return EcoReports.parse(ev)
    }

    /** ✅ "I checked, it's real" or 🧹 "it's been cleaned up". */
    suspend fun react(report: EcoReport, what: String): Boolean {
        val r = repo ?: return false
        if (report.author == r.me) return false
        val (_, accepted) = r.publishPublic(Kinds.REACTION, what, EcoReports.reactionTags(report))
        if (accepted == 0) return false
        val key = "r:${report.address}:$what"
        val p = prefs()
        if (!p.getBoolean(key, false)) {
            r.awardEco(if (what == EcoReports.CLEANED) 5 else 3, if (what == EcoReports.CLEANED) "Confirmed a clean-up" else "Checked a report")
            p.edit().putBoolean(key, true).apply()
        }
        return true
    }

    /** The reporter marks their own report as cleaned up. */
    suspend fun markCleanedByAuthor(report: EcoReport): Boolean {
        val r = repo ?: return false
        if (report.author != r.me) return false
        val (content, tags) = EcoReports.build(report.id, report.category, report.note, report.lat, report.lon, report.imageUrl, null, cleaned = true)
        return r.publishPublic(EcoReports.KIND, content, tags).second > 0
    }

    /** Clean-ups you took part in, and your confirmed real trees, earn points (once each). */
    private fun checkRewards() {
        val r = repo ?: return
        val p = prefs()
        val edit = p.edit()
        var changed = false
        _reports.value.values.forEach { rep ->
            val involved = rep.author == r.me || r.me in rep.verifiedBy || r.me in rep.cleanedBy
            if (rep.cleaned && involved) {
                val key = "clean:${rep.address}"
                if (!p.getBoolean(key, false)) {
                    r.awardEco(20, "Clean-up done 🧹")
                    edit.putBoolean(key, true); changed = true
                }
            }
            if (rep.treeConfirmed && rep.author == r.me) {
                val key = "tree:${rep.address}"
                if (!p.getBoolean(key, false)) {
                    r.awardEco(app.amend.messenger.eco.EcoPoints.PER_TREE, "Real tree confirmed 🌳")
                    edit.putBoolean(key, true); changed = true
                }
            }
        }
        if (changed) edit.apply()
    }

    fun myPlanted(): List<EcoReport> {
        val me = repo?.me ?: return emptyList()
        return _reports.value.values.filter { it.author == me && it.category == EcoCategory.PLANTED }.sortedByDescending { it.createdAt }
    }
}
