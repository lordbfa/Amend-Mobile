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
    /** Share of branch tips that carry flowers (0 = none, 1 = covered). */
    val bloomDensity: Float = 0.6f,
    /** Flower colour saturation / lightness (white blossom = low sat, high light). */
    val bloomSat: Float = 0.75f,
    val bloomLight: Float = 0.78f,
    /** Flowers' centre colour hue (e.g. yellow eye of frangipani), null = same as petals. */
    val bloomEyeHue: Float? = null,
    /** How full the leaf canopy is (bare-branched bloomers like silk cotton use less). */
    val foliage: Float = 1f,
    /** Branches droop (willow, wisteria). */
    val weeping: Boolean = false,
    /** Ball-shaped blooms (kadam) instead of five-petal flowers. */
    val ballBlooms: Boolean = false,
) {
    val flowering: Boolean get() = bloomHue != null
}

object Species {
    /** The original eight. Trees grown before amend 4.2 keep their species through these. */
    val classic = listOf(
        TreeSpecies("Oak", "🌳", 28f, 112f, 0.55f, null, 26f, 8, 0.74f, 1.0f, false),
        TreeSpecies("Birch", "🌿", 40f, 88f, 0.62f, null, 18f, 8, 0.78f, 0.75f, false),
        TreeSpecies("Pine", "🌲", 22f, 150f, 0.45f, null, 0f, 7, 0.80f, 0.9f, true),
        TreeSpecies("Cherry", "🌸", 15f, 105f, 0.50f, 335f, 30f, 7, 0.72f, 1.1f, false),
        TreeSpecies("Baobab", "🌴", 30f, 95f, 0.50f, null, 40f, 6, 0.62f, 1.2f, false),
        TreeSpecies("Maple", "🍁", 20f, 20f, 0.80f, null, 28f, 8, 0.73f, 1.05f, false),
        TreeSpecies("Jacaranda", "💜", 25f, 120f, 0.40f, 270f, 32f, 7, 0.72f, 1.15f, false),
        TreeSpecies("Willow", "🌾", 35f, 80f, 0.50f, null, 14f, 8, 0.80f, 0.85f, false, weeping = true),
    )

    /** Flowering trees (amend 4.2), many from Bangladesh and South Asia. */
    val flowering = listOf(
        TreeSpecies("Sakura", "🌸", 12f, 110f, 0.35f, 338f, 30f, 7, 0.72f, 1.05f, false, bloomDensity = 0.95f, bloomSat = 0.7f, bloomLight = 0.84f, foliage = 0.45f),
        TreeSpecies("Krishnachura", "🔥", 22f, 115f, 0.55f, 8f, 40f, 7, 0.70f, 1.25f, false, bloomDensity = 0.9f, bloomSat = 0.9f, bloomLight = 0.52f, foliage = 0.8f),
        TreeSpecies("Radhachura", "💛", 24f, 105f, 0.55f, 48f, 34f, 7, 0.72f, 1.05f, false, bloomDensity = 0.75f, bloomSat = 0.95f, bloomLight = 0.58f),
        TreeSpecies("Sonalu", "🌼", 26f, 100f, 0.50f, 52f, 26f, 8, 0.74f, 1.0f, false, bloomDensity = 0.85f, bloomSat = 0.95f, bloomLight = 0.6f, weeping = true),
        TreeSpecies("Jarul", "💐", 20f, 118f, 0.45f, 292f, 28f, 7, 0.73f, 1.05f, false, bloomDensity = 0.8f, bloomSat = 0.55f, bloomLight = 0.7f),
        TreeSpecies("Shimul", "🌺", 30f, 110f, 0.45f, 2f, 44f, 6, 0.70f, 0.9f, false, bloomDensity = 0.9f, bloomSat = 0.85f, bloomLight = 0.5f, foliage = 0.2f),
        TreeSpecies("Kadam", "🟠", 28f, 120f, 0.55f, 38f, 30f, 7, 0.73f, 1.15f, false, bloomDensity = 0.5f, bloomSat = 0.95f, bloomLight = 0.6f, ballBlooms = true),
        TreeSpecies("Magnolia", "🤍", 18f, 120f, 0.40f, 330f, 32f, 6, 0.72f, 1.1f, false, bloomDensity = 0.8f, bloomSat = 0.45f, bloomLight = 0.9f, foliage = 0.5f),
        TreeSpecies("Champa", "🏵️", 30f, 125f, 0.45f, 48f, 36f, 6, 0.68f, 1.0f, false, bloomDensity = 0.7f, bloomSat = 0.25f, bloomLight = 0.95f, bloomEyeHue = 50f),
        TreeSpecies("Bougainvillea", "🌷", 22f, 115f, 0.50f, 318f, 38f, 6, 0.70f, 0.95f, false, bloomDensity = 0.95f, bloomSat = 0.85f, bloomLight = 0.6f),
        TreeSpecies("Wisteria", "🪻", 26f, 100f, 0.40f, 268f, 22f, 7, 0.76f, 0.95f, false, bloomDensity = 0.9f, bloomSat = 0.55f, bloomLight = 0.72f, weeping = true),
        TreeSpecies("Dogwood", "🌼", 20f, 112f, 0.45f, 0f, 30f, 7, 0.72f, 1.0f, false, bloomDensity = 0.8f, bloomSat = 0.05f, bloomLight = 0.96f, bloomEyeHue = 70f),
        TreeSpecies("Apple blossom", "🍎", 18f, 108f, 0.50f, 350f, 30f, 7, 0.72f, 1.05f, false, bloomDensity = 0.8f, bloomSat = 0.45f, bloomLight = 0.9f),
        TreeSpecies("Rhododendron", "🌺", 24f, 130f, 0.45f, 328f, 40f, 5, 0.66f, 1.2f, false, bloomDensity = 0.85f, bloomSat = 0.7f, bloomLight = 0.68f),
    )

    /** Every species there is. */
    val all = classic + flowering

    /** Seeds made from amend 4.2 on carry this marker in bytes 1–2 and pick from [all]. */
    private const val MARK_1 = 0xA7
    private const val MARK_2 = 0x3E

    fun fromSeed(seed: ByteArray): TreeSpecies {
        val b0 = seed[0].toInt() and 0xff
        val marked = seed.size >= 3 && (seed[1].toInt() and 0xff) == MARK_1 && (seed[2].toInt() and 0xff) == MARK_2
        return if (marked) all[b0 % all.size] else classic[b0 % classic.size]
    }

    /** Marks a 32-byte hash so it chooses from every species (two in three are flowering). */
    fun markSeed(hash: ByteArray): ByteArray = hash.copyOf().also {
        // favour flowers: 2/3 of new trees bloom
        val b0 = it[0].toInt() and 0xff
        val idx = if (b0 % 3 == 0) b0 % classic.size else classic.size + (b0 % flowering.size)
        it[0] = idx.toByte(); it[1] = MARK_1.toByte(); it[2] = MARK_2.toByte()
    }
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
    /**
     * How many trees this chat has fully grown. When one reaches 100 %, a new tree of a
     * different species is planted next to it and starts growing from a seed.
     */
    val generation: Int get() = messageCount / MESSAGES_TO_BLOOM
    /** Species of tree 0..[generation], worked out once. */
    private val chain: List<TreeSpecies> by lazy {
        val out = ArrayList<TreeSpecies>(generation + 1)
        out.add(Species.fromSeed(seed))
        for (gen in 1..generation) {
            val prev = out.last()
            var pick = prev
            for (i in 0..8) {
                pick = Species.fromSeed(Species.markSeed(Hashes.sha256(seed + "gen:$gen:$i".toByteArray())))
                if (pick.name != prev.name) break
            }
            out.add(pick)
        }
        out
    }

    /** The tree growing now. The very first keeps its original species. */
    val species: TreeSpecies get() = chain.last()
    val progress: Float get() = (messageCount % MESSAGES_TO_BLOOM) / MESSAGES_TO_BLOOM.toFloat()
    val stage: GrowthStage get() = GrowthStage.of(progress)
    /** Fully grown trees, oldest first. */
    val grown: List<TreeSpecies> get() = chain.dropLast(1)
    /** Flowers appear once the plant is well established. */
    val flowers: Int get() = ((messageCount % MESSAGES_TO_BLOOM - MESSAGES_TO_BLOOM / 2).coerceAtLeast(0) / 10).coerceAtMost(12) + if (generation > 0) 3 else 0

    fun speciesFor(gen: Int): TreeSpecies = chain[gen.coerceIn(0, chain.lastIndex)]

    /** A stable drawing seed for the [gen]-th tree. */
    fun drawSeed(gen: Int): Int = (seed[2].toInt() and 0xff) + gen * 97

    override fun equals(other: Any?) = other is Garden && other.seed.contentEquals(seed) && other.messageCount == messageCount
    override fun hashCode() = seed.contentHashCode() * 31 + messageCount

    companion object {
        const val MESSAGES_TO_BLOOM = 80

        fun forPair(pubA: String, pubB: String, messageCount: Int): Garden {
            val (a, b) = if (pubA < pubB) pubA to pubB else pubB to pubA
            return Garden(Hashes.sha256("amend-garden:$a:$b".toByteArray()), messageCount)
        }

        /**
         * A shared garden (group): one plant for everyone, from the group's id. A group
         * talks more, so it grows at half speed and needs twice as many messages to bloom.
         */
        fun forGroup(groupKey: String, messageCount: Int): Garden =
            Garden(Hashes.sha256("amend-garden-group:$groupKey".toByteArray()), messageCount / 2)
    }
}
