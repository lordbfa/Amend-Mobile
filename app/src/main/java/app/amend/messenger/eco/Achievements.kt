package app.amend.messenger.eco

/**
 * Achievements for the Forest and Impact tabs. Each one has up to four tiers
 * (bronze → silver → gold → emerald); a tier is unlocked when its number is reached.
 * Unlocks are kept on the phone as "id:tier" → time.
 */
enum class AchievementTab { FOREST, IMPACT }

enum class Tier(val label: String) { BRONZE("Bronze"), SILVER("Silver"), GOLD("Gold"), EMERALD("Emerald") }

/** Numbers the achievements are measured against. */
data class AchievementStats(
    val trees: Long = 0,
    val points: Long = 0,
    val streak: Long = 0,
    val challenges: Long = 0,
    val co2AvoidedGrams: Long = 0,
    /** CO₂ avoided ÷ amend's data footprint, ×1 (0 when there's no footprint yet). */
    val offsetRatio: Long = 0,
    val gardens: Long = 0,
    val forestFriends: Long = 0,
    val mapPosts: Long = 0,
    val walkedKm: Long = 0,
    val messages: Long = 0,
)

data class Achievement(
    val id: String,
    val tab: AchievementTab,
    val emoji: String,
    val title: String,
    /** "{n}" is replaced by the tier's number. */
    val description: String,
    val thresholds: List<Long>,
    val unit: String,
    val metric: (AchievementStats) -> Long,
) {
    fun threshold(t: Tier): Long? = thresholds.getOrNull(t.ordinal)
    val tiers: List<Tier> get() = Tier.entries.take(thresholds.size)
    fun describe(t: Tier) = description.replace("{n}", formatCount(threshold(t) ?: 0))

    /** Highest tier reached for [stats], or null. */
    fun reached(stats: AchievementStats): Tier? = tiers.lastOrNull { metric(stats) >= (threshold(it) ?: Long.MAX_VALUE) }

    /** 0..1 towards the next tier (1 when all are done). */
    fun progress(stats: AchievementStats): Float {
        val v = metric(stats)
        val next = tiers.firstOrNull { v < (threshold(it) ?: 0) } ?: return 1f
        val prev = tiers.getOrNull(next.ordinal - 1)?.let { threshold(it) } ?: 0
        val goal = threshold(next) ?: return 1f
        return ((v - prev).toFloat() / (goal - prev).coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    fun nextTier(stats: AchievementStats): Tier? = tiers.firstOrNull { metric(stats) < (threshold(it) ?: 0) }

    private fun formatCount(n: Long): String = when {
        unit == "kg" -> "${n / 1000} kg"
        unit == "×" -> "$n×"
        else -> "%,d".format(n)
    }
}

object Achievements {
    val all: List<Achievement> = listOf(
        Achievement("trees", AchievementTab.FOREST, "🌳", "Forest grower", "Grow {n} trees", listOf(1, 5, 25, 100), "trees") { it.trees },
        Achievement("streak", AchievementTab.FOREST, "🔥", "Deep roots", "Keep a {n}-day streak", listOf(3, 7, 30, 100), "days") { it.streak },
        Achievement("challenges", AchievementTab.FOREST, "🏅", "Challenge champion", "Complete {n} daily challenges", listOf(1, 10, 50, 150), "challenges") { it.challenges },
        Achievement("points", AchievementTab.FOREST, "✨", "Forest keeper", "Earn {n} points", listOf(100, 1_000, 5_000, 20_000), "points") { it.points },
        Achievement("gardens", AchievementTab.FOREST, "🌿", "Shared gardener", "Grow {n} shared gardens", listOf(1, 3, 10), "gardens") { it.gardens },
        Achievement("friends", AchievementTab.FOREST, "🤝", "Forest friends", "{n} friends share their forest with you", listOf(1, 5, 15), "friends") { it.forestFriends },
        Achievement("co2", AchievementTab.IMPACT, "🌍", "Climate saver", "Avoid {n} of CO₂", listOf(1_000, 10_000, 50_000, 200_000), "kg") { it.co2AvoidedGrams },
        Achievement("offset", AchievementTab.IMPACT, "⚖️", "Net positive", "Offset amend's footprint {n}", listOf(1, 10, 100, 1_000), "×") { it.offsetRatio },
        Achievement("mapper", AchievementTab.IMPACT, "🗺️", "Eco mapper", "Post {n} reports or trees on the eco map", listOf(1, 5, 20, 50), "posts") { it.mapPosts },
        Achievement("walker", AchievementTab.IMPACT, "🚶", "Car-free walker", "Walk {n} km in a month", listOf(10, 50, 150, 300), "km") { it.walkedKm },
        Achievement("messages", AchievementTab.IMPACT, "💬", "Green messenger", "Send and receive {n} messages", listOf(100, 1_000, 10_000), "messages") { it.messages },
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    fun key(a: Achievement, t: Tier) = "${a.id}:${t.name}"

    /** "trees:GOLD" → (achievement, tier), or null. */
    fun parse(key: String): Pair<Achievement, Tier>? {
        val (id, tier) = key.split(":").takeIf { it.size == 2 } ?: return null
        val a = byId(id) ?: return null
        val t = runCatching { Tier.valueOf(tier) }.getOrNull()?.takeIf { it in a.tiers } ?: return null
        return a to t
    }

    /** Every "id:tier" that [stats] has reached (all lower tiers included). */
    fun earned(stats: AchievementStats): Set<String> = all.flatMap { a ->
        val top = a.reached(stats) ?: return@flatMap emptyList()
        a.tiers.filter { it.ordinal <= top.ordinal }.map { key(a, it) }
    }.toSet()
}
