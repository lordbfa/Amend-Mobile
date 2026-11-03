package app.amend.messenger.data

import app.amend.messenger.core.crypto.toHex
import app.amend.messenger.core.json.Json
import app.amend.messenger.core.json.long
import app.amend.messenger.core.json.str
import app.amend.messenger.eco.EcoPoints
import app.amend.messenger.eco.ForestState
import app.amend.messenger.eco.Garden
import app.amend.messenger.eco.PlantedTree

enum class MessageStatus(val rank: Int) {
    SENDING(0), SENT(1), FAILED(0), RECEIVED(0),
    /** Waiting on this phone to be sent at [ChatMessage.scheduledAt]. */
    SCHEDULED(0),
    /** The friend's phone got it. */
    DELIVERED(2),
    /** The friend opened the chat and saw it. */
    SEEN(3);

    /** Status only moves forward (a late "sent" never undoes "seen"). */
    fun advance(to: MessageStatus): MessageStatus = if (to.rank > rank || (this == FAILED || this == SENDING)) to else this
}

enum class MessageType { TEXT, ECO_CARD, SOS, VOICE, VIDEO, FILE, CALL, ECO_PROOF,
    /** A message its sender took back within the unsend window: only a "message unsent" note remains. */
    UNSENT,
    /** A small centred note inside a chat ("⏳ Disappearing messages: 1 day", "Faisal named the garden …"). */
    NOTICE,
    /** Live location being shared for a while (see LiveProtocol). [ChatMessage.scheduledAt] holds "until". */
    LIVE,
}

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
    /** Who wrote it, for messages in a shared garden (group). Null in one-to-one chats. */
    val author: String? = null,
    /** Disappearing messages: unix time after which both phones delete it (0 = keeps). */
    val expiresAt: Long = 0,
    /** Scheduled messages: unix time it will be sent at (status [MessageStatus.SCHEDULED]). */
    val scheduledAt: Long = 0,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "peer" to peer, "fromMe" to fromMe, "text" to text, "createdAt" to createdAt,
        "type" to type.name, "cardId" to cardId, "replyTo" to replyTo, "reactions" to reactions,
        "status" to status.name, "relaysAccepted" to relaysAccepted, "extra" to extra,
        "receipt" to receipt, "author" to author, "expiresAt" to expiresAt, "scheduledAt" to scheduledAt,
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
            author = m.str("author"),
            expiresAt = m.long("expiresAt") ?: 0,
            scheduledAt = m.long("scheduledAt") ?: 0,
        )
    }
}

/**
 * A shared garden: an end-to-end encrypted group chat (NIP-17 — every message is
 * gift-wrapped to each member separately, so relays never see who's in it). The room
 * is defined by its members, exactly as in NIP-17; [name] travels as the "subject" tag.
 */
data class GroupInfo(val key: String, val members: List<String>, val name: String = "", val createdBy: String = "") {
    fun toMap(): Map<String, Any?> = linkedMapOf("key" to key, "members" to members, "name" to name, "createdBy" to createdBy)

    companion object {
        /** Room id from its members (order-free), so every member derives the same one. */
        fun keyFor(members: Collection<String>): String {
            val sorted = members.filter { it.length == 64 }.distinct().sorted().joinToString(",")
            return "g" + app.amend.messenger.core.crypto.Hashes.sha256("amend-group:$sorted".toByteArray()).toHex().take(31)
        }

        fun isKey(s: String) = s.length == 32 && s.startsWith("g")

        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>) = GroupInfo(
            key = m.str("key") ?: "",
            members = (m["members"] as? List<Any?>).orEmpty().mapNotNull { it as? String },
            name = m.str("name") ?: "",
            createdBy = m.str("createdBy") ?: "",
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
    /** NIP-05 address, e.g. faisal@getamend.app (verified against its domain before showing ✓). */
    val nip05: String = "",
) {
    fun displayName(fallback: String): String = name.ifBlank { fallback }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "pubkey" to pubkey, "name" to name, "about" to about, "avatar" to avatar, "hue" to hue.toDouble(),
        "updatedAt" to updatedAt, "picture" to picture, "nip05" to nip05,
    )

    /** Content of the kind-0 metadata event. */
    fun toMetadataJson(): String {
        val m = linkedMapOf<String, Any?>(
            "name" to name, "display_name" to name, "about" to about,
            "amend_avatar" to avatar, "amend_hue" to hue.toDouble(),
        )
        if (nip05.isNotBlank()) m["nip05"] = nip05
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
            nip05 = m.str("nip05") ?: "",
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
                nip05 = (m.str("nip05") ?: "").trim().take(100),
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
    /** Disappearing-message timer for this chat, in seconds (0 = off). */
    val disappearSecs: Long = 0,
) {
    val isGroup: Boolean get() = GroupInfo.isKey(peer)
    /** The latest real message (a scheduled one only if nothing else is there yet). */
    val lastMessage: ChatMessage? get() = messages.lastOrNull { it.status != MessageStatus.SCHEDULED } ?: messages.lastOrNull()
    val lastActivity: Long get() = lastMessage?.createdAt ?: 0
}

/** Disappearing-message timer choices. */
object Disappear {
    val options: List<Long> = listOf(0L, 3600L, 8 * 3600L, 24 * 3600L, 7 * 24 * 3600L, 30 * 24 * 3600L)

    fun label(secs: Long): String = when {
        secs <= 0 -> "Off"
        secs < 3600 -> "${secs / 60} minutes"
        secs == 3600L -> "1 hour"
        secs < 24 * 3600 -> "${secs / 3600} hours"
        secs == 24 * 3600L -> "1 day"
        secs == 7 * 24 * 3600L -> "1 week"
        secs == 30 * 24 * 3600L -> "30 days"
        else -> "${secs / (24 * 3600)} days"
    }
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

    fun gardenFor(me: String, conversation: Conversation): Garden {
        val count = conversation.messages.count { it.type != MessageType.NOTICE && it.type != MessageType.UNSENT && it.status != MessageStatus.SCHEDULED }
        return if (conversation.isGroup) Garden.forGroup(conversation.peer, count) else Garden.forPair(me, conversation.peer, count)
    }
}
