package app.amend.messenger.data

import app.amend.messenger.core.nostr.Event

/**
 * Live location sharing in a chat (like "share live location for 1 hour"). Sent as
 * ordinary gift-wrapped chat messages, so only the chat's members can read it:
 *
 *   ["amend", "live", "start" | "update" | "stop"]
 *   ["amend-loc", "lat,lon,acc"]
 *   ["amend-until", unix seconds]
 *
 * Start and stop become messages; updates only move the pin on the start bubble.
 * Unlike an SOS it raises no alarm — it's for "I'm on my way" moments.
 */
enum class LivePhase(val tag: String) {
    START("start"), UPDATE("update"), STOP("stop");

    companion object {
        fun of(tag: String?) = entries.firstOrNull { it.tag == tag }
    }
}

data class LiveSignal(val phase: LivePhase, val location: SosLocation?, val until: Long)

/** A live location someone is sharing in a chat right now. */
data class LiveShare(
    val chat: String,
    val from: String,
    val location: SosLocation?,
    val until: Long,
    val startedAt: Long,
) {
    val active: Boolean get() = until > Event.nowSeconds()
}

object LiveProtocol {
    fun tags(pTags: List<List<String>>, phase: LivePhase, location: SosLocation?, until: Long): List<List<String>> = buildList {
        addAll(pTags)
        add(listOf("amend", "live", phase.tag))
        location?.let { add(listOf("amend-loc", it.encode())) }
        add(listOf("amend-until", until.toString()))
    }

    fun content(phase: LivePhase, until: Long, nowSecs: Long = Event.nowSeconds()): String = when (phase) {
        LivePhase.START -> "📍 Sharing live location for " + Disappear.label((until - nowSecs).coerceAtLeast(60)).lowercase()
        LivePhase.UPDATE -> "📍 Live location"
        LivePhase.STOP -> "📍 Stopped sharing live location"
    }

    fun parse(rumor: Event): LiveSignal? {
        val phase = LivePhase.of(rumor.tags.firstOrNull { it.size >= 3 && it[0] == "amend" && it[1] == "live" }?.get(2)) ?: return null
        val loc = SosLocation.decode(rumor.firstTag("amend-loc"), rumor.createdAt)
        val until = rumor.firstTag("amend-until")?.toLongOrNull() ?: 0L
        return LiveSignal(phase, loc, until)
    }
}
