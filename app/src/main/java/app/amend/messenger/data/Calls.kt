package app.amend.messenger.data

import app.amend.messenger.core.json.Json
import app.amend.messenger.core.json.long
import app.amend.messenger.core.json.str
import app.amend.messenger.core.nostr.Event

/**
 * One step of setting up a call, sent end-to-end encrypted inside a gift wrap.
 *
 *   offer   caller → callee   WebRTC offer (SDP with all ICE candidates)
 *   ringing callee → caller   "the phone is ringing"
 *   answer  callee → caller   WebRTC answer
 *   reject  callee → caller   declined
 *   busy    callee → caller   already in another call
 *   hangup  either way        call ended (or cancelled before it was answered)
 *   missed  caller → callee   stored note, so a phone that was offline still shows the missed call
 *
 * The SDP carries the DTLS fingerprints that encrypt the call itself. Because it
 * travels inside the gift wrap, nobody in the middle can swap them.
 */
data class CallSignal(
    val peer: String,
    val type: String,
    val callId: String,
    val video: Boolean = false,
    val sdp: String? = null,
    val createdAt: Long = Event.nowSeconds(),
    val live: Boolean = true,
) {
    fun contentJson(): String = Json.write(
        linkedMapOf("type" to type, "call" to callId, "video" to video, "sdp" to sdp)
    )

    /** An offer is only worth ringing for while it's fresh. */
    fun isFresh(now: Long = Event.nowSeconds()): Boolean = now - createdAt <= MAX_RING_SECONDS

    companion object {
        const val OFFER = "offer"
        const val RINGING = "ringing"
        const val ANSWER = "answer"
        const val REJECT = "reject"
        const val BUSY = "busy"
        const val HANGUP = "hangup"
        const val MISSED = "missed"
        const val MAX_RING_SECONDS = 45L
        private val TYPES = setOf(OFFER, RINGING, ANSWER, REJECT, BUSY, HANGUP, MISSED)

        fun parse(peer: String, rumor: Event, ephemeral: Boolean): CallSignal? = try {
            val m = Json.parseObject(rumor.content)
            val type = m.str("type")?.takeIf { it in TYPES }
            val id = m.str("call")?.takeIf { it.length in 8..64 }
            if (type == null || id == null) null
            else CallSignal(peer, type, id, m["video"] == true, m.str("sdp")?.take(20_000), rumor.createdAt, ephemeral)
        } catch (e: Exception) {
            null
        }
    }
}

enum class CallOutcome { COMPLETED, MISSED, DECLINED, NO_ANSWER, BUSY, FAILED, CANCELLED }

/** A call in the chat history. */
data class CallLog(
    val callId: String,
    val outgoing: Boolean,
    val video: Boolean,
    val outcome: CallOutcome,
    val startedAt: Long,
    val durationSec: Long = 0,
) {
    fun label(): String {
        val kind = if (video) "video call" else "voice call"
        val icon = if (video) "🎥" else "📞"
        return when (outcome) {
            CallOutcome.COMPLETED -> "$icon ${if (outgoing) "Outgoing" else "Incoming"} $kind · ${formatCallDuration(durationSec)}"
            CallOutcome.MISSED -> "$icon Missed $kind"
            CallOutcome.DECLINED -> if (outgoing) "$icon $kind declined".replaceFirstChar { it.uppercase() } else "$icon You declined a $kind"
            CallOutcome.NO_ANSWER -> "$icon No answer · $kind"
            CallOutcome.BUSY -> "$icon Busy · $kind"
            CallOutcome.FAILED -> "$icon Couldn't connect · $kind"
            CallOutcome.CANCELLED -> "$icon Cancelled $kind"
        }
    }

    fun toJson(): String = Json.write(
        linkedMapOf(
            "call" to callId, "out" to outgoing, "video" to video, "outcome" to outcome.name,
            "at" to startedAt, "dur" to durationSec,
        )
    )

    companion object {
        fun fromJson(s: String?): CallLog? = try {
            val m = Json.parseObject(s ?: "")
            CallLog(
                callId = m.str("call") ?: "",
                outgoing = m["out"] == true,
                video = m["video"] == true,
                outcome = runCatching { CallOutcome.valueOf(m.str("outcome") ?: "") }.getOrDefault(CallOutcome.COMPLETED),
                startedAt = m.long("at") ?: 0,
                durationSec = m.long("dur") ?: 0,
            )
        } catch (e: Exception) {
            null
        }
    }
}

fun formatCallDuration(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
