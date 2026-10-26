package app.amend.messenger.data

import app.amend.messenger.core.json.Json
import app.amend.messenger.core.json.long
import app.amend.messenger.core.json.str
import app.amend.messenger.eco.EcoPoints
import app.amend.messenger.eco.ForestState
import app.amend.messenger.eco.Garden
import app.amend.messenger.eco.PlantedTree

enum class MessageStatus(val rank: Int) {
    SENDING(0), SENT(1), FAILED(0), RECEIVED(0),
    /** The friend's phone got it. */
    DELIVERED(2),
    /** The friend opened the chat and saw it. */
    SEEN(3);

    /** Status only moves forward (a late "sent" never undoes "seen"). */
    fun advance(to: MessageStatus): MessageStatus = if (to.rank > rank || (this == FAILED || this == SENDING)) to else this
}

enum class MessageType { TEXT, ECO_CARD, SOS, VOICE, VIDEO, FILE, CALL, ECO_PROOF }

data class ChatMessage(
    val id: String,
    val peer: String,
    val fromMe: Boolean,
    val text: String,
    val createdAt: Long,
    val type: MessageType = MessageType.TEXT,
    val cardId: String? = null,
    val replyTo: String? = null,
    val reactions: Map<String, String> = emptyMap(), // pubkey -> emoji
    val status: MessageStatus = MessageStatus.RECEIVED,
    val relaysAccepted: Int = 0,
    /** Extra data: for SOS messages the encoded location "lat,lon,acc". */
    val extra: String? = null,
    /** For incoming messages: which receipt we already sent back (0 none, 1 delivered, 2 seen). */
    val receipt: Int = 0,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "peer" to peer, "fromMe" to fromMe, "text" to text, "createdAt" to createdAt,
        "type" to type.name, "cardId" to cardId, "replyTo" to replyTo, "reactions" to reactions,
        "status" to status.name, "relaysAccepted" to relaysAccepted, "extra" to extra,
        "receipt" to receipt,
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>) = ChatMessage(
            id = m.str("id") ?: "",
            peer = m.str("peer") ?: "",
            fromMe = m["fromMe"] == true,
            text = m.str("text") ?: "",
            createdAt = m.long("createdAt") ?: 0,
            type = runCatching { MessageType.valueOf(m.str("type") ?: "TEXT") }.getOrDefault(MessageType.TEXT),
            cardId = m.str("cardId"),
            replyTo = m.str("replyTo"),
            reactions = (m["reactions"] as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() },
            status = runCatching { MessageStatus.valueOf(m.str("status") ?: "RECEIVED") }.getOrDefault(MessageStatus.RECEIVED),
            relaysAccepted = (m.long("relaysAccepted") ?: 0).toInt(),
            extra = m.str("extra"),
            receipt = (m.long("receipt") ?: 0).toInt(),
        )
    }
}

/**
 * Public profile (kind 0). amend adds a leaf avatar emoji, a colour hue and an
 * optional tiny inline photo. [picture] is either a small `data:image/webp;base64,…`
 * photo made by amend, or an https URL set by another Nostr app.
 */
data class Profile(
    val pubkey: String,
    val name: String = "",
    val about: String = "",
    val avatar: String = "",
    val hue: Float = -1f,
    val updatedAt: Long = 0,
    val picture: String = "",
) {
    fun displayName(fallback: String): String = name.ifBlank { fallback }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "pubkey" to pubkey, "name" to name, "about" to about, "avatar" to avatar, "hue" to hue.toDouble(),
        "updatedAt" to updatedAt, "picture" to picture,
    )

    /** Content of the kind-0 metadata event. */
    fun toMetadataJson(): String {
        val m = linkedMapOf<String, Any?>(
            "name" to name, "display_name" to name, "about" to about,
            "amend_avatar" to avatar, "amend_hue" to hue.toDouble(),
        )
        when {
            // Inline photos live in amend's own field so other apps never choke on a data URI.
            picture.startsWith(DATA_PREFIX) -> m["amend_picture"] = picture
            picture.startsWith("https://") -> m["picture"] = picture
        }
        return Json.write(m)
    }

    companion object {
        const val DATA_PREFIX = "data:image/"
        /** Inline photos larger than this are ignored (keeps profiles light for relays and data plans). */
        const val MAX_INLINE_CHARS = 24_000

        fun isValidPicture(p: String): Boolean =
            (p.startsWith(DATA_PREFIX) && p.contains(";base64,") && p.length <= MAX_INLINE_CHARS) ||
                (p.startsWith("https://") && p.length <= 2_000)

        fun fromMap(m: Map<String, Any?>) = Profile(
            pubkey = m.str("pubkey") ?: "",
            name = m.str("name") ?: "",
            about = m.str("about") ?: "",
            avatar = m.str("avatar") ?: "",
            hue = (m["hue"] as? Number)?.toFloat() ?: -1f,
            updatedAt = m.long("updatedAt") ?: 0,
            picture = (m.str("picture") ?: "").takeIf { isValidPicture(it) } ?: "",
        )

        fun fromMetadata(pubkey: String, content: String, createdAt: Long): Profile? = try {
            val m = Json.parseObject(content)
            val inline = m.str("amend_picture")?.takeIf { isValidPicture(it) && it.startsWith(DATA_PREFIX) }
            val url = m.str("picture")?.trim()?.takeIf { isValidPicture(it) && it.startsWith("https://") }
            Profile(
                pubkey = pubkey,
                name = (m.str("display_name")?.takeIf { it.isNotBlank() } ?: m.str("name") ?: "").take(60),
                about = (m.str("about") ?: "").take(300),
                avatar = (m.str("amend_avatar") ?: "").take(8),
                hue = (m["amend_hue"] as? Number)?.toFloat() ?: -1f,
                updatedAt = createdAt,
                picture = inline ?: url ?: "",
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class Conversation(
    val peer: String,
    val messages: List<ChatMessage> = emptyList(),
    val unread: Int = 0,
    val pinned: Boolean = false,
    val nickname: String = "",
) {
    val lastMessage: ChatMessage? get() = messages.lastOrNull()
    val lastActivity: Long get() = lastMessage?.createdAt ?: 0
}

/** Persistent eco progress. */
data class EcoState(
    val totalPoints: Int = 0,
    val trees: List<PlantedTree> = emptyList(),
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val messagesSent: Int = 0,
    val messagesReceived: Int = 0,
    val completedChallenges: Map<String, String> = emptyMap(), // ISO date -> challenge id
    val pointsToday: Int = 0,
    val messagePointsToday: Int = 0,
    val pointsDay: String = "",
    val streak: Int = 0,
    val lastActiveDay: String = "",
    val efficiencyMode: Boolean = false,
    val carbonAwareSync: Boolean = true,
    val co2AvoidedGrams: Long = 0,
    /** Keep a live relay connection in the background for instant notifications. */
    val instantNotifications: Boolean = true,
    /** Tell friends when you've seen their messages. */
    val readReceipts: Boolean = true,
) {
    val forest: ForestState get() = ForestState(totalPoints, trees)
    val totalBytes: Long get() = bytesSent + bytesReceived

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "totalPoints" to totalPoints,
        "trees" to trees.map { linkedMapOf("index" to it.index, "seed" to it.seedHex, "grownAt" to it.grownAt) },
        "bytesSent" to bytesSent, "bytesReceived" to bytesReceived,
        "messagesSent" to messagesSent, "messagesReceived" to messagesReceived,
        "completedChallenges" to completedChallenges,
        "pointsToday" to pointsToday, "messagePointsToday" to messagePointsToday, "pointsDay" to pointsDay,
        "streak" to streak, "lastActiveDay" to lastActiveDay,
        "efficiencyMode" to efficiencyMode, "carbonAwareSync" to carbonAwareSync,
        "co2AvoidedGrams" to co2AvoidedGrams,
        "instantNotifications" to instantNotifications,
        "readReceipts" to readReceipts,
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>) = EcoState(
            totalPoints = (m.long("totalPoints") ?: 0).toInt(),
            trees = (m["trees"] as? List<Map<String, Any?>>).orEmpty().map {
                PlantedTree((it.long("index") ?: 0).toInt(), it.str("seed") ?: "00", it.long("grownAt") ?: 0)
            },
            bytesSent = m.long("bytesSent") ?: 0,
            bytesReceived = m.long("bytesReceived") ?: 0,
            messagesSent = (m.long("messagesSent") ?: 0).toInt(),
            messagesReceived = (m.long("messagesReceived") ?: 0).toInt(),
            completedChallenges = (m["completedChallenges"] as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() },
            pointsToday = (m.long("pointsToday") ?: 0).toInt(),
            messagePointsToday = (m.long("messagePointsToday") ?: 0).toInt(),
            pointsDay = m.str("pointsDay") ?: "",
            streak = (m.long("streak") ?: 0).toInt(),
            lastActiveDay = m.str("lastActiveDay") ?: "",
            efficiencyMode = m["efficiencyMode"] == true,
            carbonAwareSync = m["carbonAwareSync"] != false,
            co2AvoidedGrams = m.long("co2AvoidedGrams") ?: 0,
            instantNotifications = m["instantNotifications"] != false,
            readReceipts = m["readReceipts"] != false,
        )
    }
}

/** Result of adding points: did a tree finish growing? */
data class GrowthEvent(val pointsAdded: Int, val newTree: PlantedTree?)

object EcoRules {
    /**
     * Adds points, respecting the daily message cap, and plants a new tree
     * every [EcoPoints.PER_TREE] points.
     */
    fun addPoints(state: EcoState, today: String, amount: Int, isMessage: Boolean, seedFor: (Int) -> String, now: Long): Pair<EcoState, GrowthEvent> {
        var s = if (state.pointsDay != today) state.copy(pointsDay = today, pointsToday = 0, messagePointsToday = 0) else state
        val allowed = if (isMessage) {
            minOf(amount, (EcoPoints.DAILY_MESSAGE_CAP - s.messagePointsToday).coerceAtLeast(0))
        } else amount
        if (allowed <= 0) return s to GrowthEvent(0, null)
        s = s.copy(
            totalPoints = s.totalPoints + allowed,
            pointsToday = s.pointsToday + allowed,
            messagePointsToday = s.messagePointsToday + if (isMessage) allowed else 0,
        )
        var newTree: PlantedTree? = null
        while (s.totalPoints >= (s.trees.size + 1) * EcoPoints.PER_TREE) {
            val idx = s.trees.size
            newTree = PlantedTree(idx, seedFor(idx), now)
            s = s.copy(trees = s.trees + newTree)
        }
        return s to GrowthEvent(allowed, newTree)
    }

    fun gardenFor(me: String, conversation: Conversation): Garden =
        Garden.forPair(me, conversation.peer, conversation.messages.size)
}
