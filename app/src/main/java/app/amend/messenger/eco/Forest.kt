package app.amend.messenger.eco

import app.amend.messenger.core.crypto.Hashes
import kotlin.math.min

/** Visual "DNA" of a procedurally drawn tree. All values are normalised 0..1 unless noted. */
data class TreeSpecies(
    val name: String,
    val emoji: String,
    val trunkHue: Float,      // degrees
    val leafHue: Float,       // degrees
    val leafSat: Float,
    val bloomHue: Float?,     // flowers/blossom colour, null = none
    val branchAngle: Float,   // degrees between child branches
    val depth: Int,           // recursion depth at full growth
    val shrink: Float,        // child branch length ratio
    val crown: Float,         // leaf cluster size
    val conifer: Boolean,
)

object Species {
    val all = listOf(
        TreeSpecies("Oak", "🌳", 28f, 112f, 0.55f, null, 26f, 8, 0.74f, 1.0f, false),
        TreeSpecies("Birch", "🌿", 40f, 88f, 0.62f, null, 18f, 8, 0.78f, 0.75f, false),
        TreeSpecies("Pine", "🌲", 22f, 150f, 0.45f, null, 0f, 7, 0.80f, 0.9f, true),
        TreeSpecies("Cherry", "🌸", 15f, 105f, 0.50f, 335f, 30f, 7, 0.72f, 1.1f, false),
        TreeSpecies("Baobab", "🌴", 30f, 95f, 0.50f, null, 40f, 6, 0.62f, 1.2f, false),
        TreeSpecies("Maple", "🍁", 20f, 20f, 0.80f, null, 28f, 8, 0.73f, 1.05f, false),
        TreeSpecies("Jacaranda", "💜", 25f, 120f, 0.40f, 270f, 32f, 7, 0.72f, 1.15f, false),
        TreeSpecies("Willow", "🌾", 35f, 80f, 0.50f, null, 14f, 8, 0.80f, 0.85f, false),
    )

    fun fromSeed(seed: ByteArray): TreeSpecies = all[(seed[0].toInt() and 0xff) % all.size]
}

enum class GrowthStage(val label: String, val emoji: String, val minProgress: Float) {
    SEED("Seed", "🌰", 0f),
    SPROUT("Sprout", "🌱", 0.1f),
    SAPLING("Sapling", "🌿", 0.3f),
    YOUNG("Young tree", "🌳", 0.6f),
    MATURE("Mature tree", "🌳", 0.9f);

    companion object {
        fun of(progress: Float): GrowthStage = entries.last { progress >= it.minProgress }
    }
}

/** One tree in the user's personal forest. */
data class PlantedTree(val index: Int, val seedHex: String, val grownAt: Long) {
    val species: TreeSpecies get() = Species.fromSeed(seedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
}

/** Points rules. Daily caps stop the forest rewarding spam — the point is mindful use. */
object EcoPoints {
    const val PER_TREE = 100
    const val MESSAGE = 1
    const val DAILY_MESSAGE_CAP = 40
    const val CHALLENGE = 25
    const val SHARE_CARD = 5
    const val EFFICIENT_DAY = 3
    const val CARBON_AWARE_DAY = 2
}

/** Personal forest computed from total points. */
data class ForestState(val totalPoints: Int, val trees: List<PlantedTree>) {
    val pointsInCurrent: Int get() = totalPoints - trees.size * EcoPoints.PER_TREE
    val currentProgress: Float get() = (pointsInCurrent.toFloat() / EcoPoints.PER_TREE).coerceIn(0f, 1f)
    val currentStage: GrowthStage get() = GrowthStage.of(currentProgress)
}

/**
 * The shared garden of one conversation. Both people see exactly the same plant,
 * because it is derived from the pair of public keys and the number of messages —
 * no extra data needs to be synced.
 */
data class Garden(val seed: ByteArray, val messageCount: Int) {
    val species: TreeSpecies get() = Species.fromSeed(seed)
    val progress: Float get() = min(1f, messageCount / MESSAGES_TO_BLOOM.toFloat())
    val stage: GrowthStage get() = GrowthStage.of(progress)
    /** Flowers appear once the plant is well established. */
    val flowers: Int get() = ((messageCount - MESSAGES_TO_BLOOM / 2).coerceAtLeast(0) / 10).coerceAtMost(12)

    override fun equals(other: Any?) = other is Garden && other.seed.contentEquals(seed) && other.messageCount == messageCount
    override fun hashCode() = seed.contentHashCode() * 31 + messageCount

    companion object {
        const val MESSAGES_TO_BLOOM = 80

        fun forPair(pubA: String, pubB: String, messageCount: Int): Garden {
            val (a, b) = if (pubA < pubB) pubA to pubB else pubB to pubA
            return Garden(Hashes.sha256("amend-garden:$a:$b".toByteArray()), messageCount)
        }
    }
}
