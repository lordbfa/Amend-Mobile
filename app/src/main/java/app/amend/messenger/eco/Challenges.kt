package app.amend.messenger.eco

import java.time.LocalDate

enum class EcoCategory(val emoji: String, val label: String) {
    ENERGY("⚡", "Energy"),
    TRANSPORT("🚲", "Transport"),
    FOOD("🥗", "Food"),
    WASTE("♻️", "Waste"),
    WATER("💧", "Water"),
    DIGITAL("📱", "Digital"),
    NATURE("🌳", "Nature"),
}

/**
 * A small, doable action. [co2Grams] is an order-of-magnitude estimate of what
 * one day of the action avoids, used only to make impact tangible.
 */
data class Challenge(
    val id: String,
    val category: EcoCategory,
    val title: String,
    val description: String,
    val co2Grams: Int,
)

object Challenges {
    val all: List<Challenge> = listOf(
        Challenge("c01", EcoCategory.ENERGY, "Hunt the vampires", "Switch off chargers and devices on standby at the wall before bed.", 150),
        Challenge("c02", EcoCategory.ENERGY, "Cooler wash", "Run today's laundry at 30 °C instead of 40 °C or more.", 300),
        Challenge("c03", EcoCategory.ENERGY, "Lid on the pot", "Cook with lids on and match the pan to the hob size.", 80),
        Challenge("c04", EcoCategory.ENERGY, "One degree", "Lower the heating (or raise the AC) by just one degree today.", 400),
        Challenge("c05", EcoCategory.ENERGY, "Air-dry day", "Skip the tumble dryer — hang your clothes to dry.", 1000),
        Challenge("c06", EcoCategory.TRANSPORT, "Short trip, no car", "Walk or cycle one trip under 3 km you'd normally drive.", 700),
        Challenge("c07", EcoCategory.TRANSPORT, "Ride together", "Share a ride or take public transport for one journey.", 1200),
        Challenge("c08", EcoCategory.TRANSPORT, "Tyre check", "Check your bike or car tyre pressure — soft tyres waste energy.", 100),
        Challenge("c09", EcoCategory.TRANSPORT, "Meet online", "Swap one short work trip for a call.", 2000),
        Challenge("c10", EcoCategory.FOOD, "Plant-powered lunch", "Make today's lunch fully plant-based.", 1500),
        Challenge("c11", EcoCategory.FOOD, "Leftover hero", "Turn leftovers into a meal instead of throwing them away.", 600),
        Challenge("c12", EcoCategory.FOOD, "Shop the season", "Buy one local, seasonal fruit or vegetable instead of an air-freighted one.", 300),
        Challenge("c13", EcoCategory.FOOD, "Tap over bottle", "Drink tap water from a reusable bottle all day.", 150),
        Challenge("c14", EcoCategory.FOOD, "Fridge check", "Plan meals around what's already in your fridge.", 500),
        Challenge("c15", EcoCategory.WASTE, "Bring your bag", "Take a reusable bag or box for today's shopping.", 50),
        Challenge("c16", EcoCategory.WASTE, "Fix it", "Repair something instead of replacing it.", 2000),
        Challenge("c17", EcoCategory.WASTE, "Sort it right", "Learn one recycling rule for your area you weren't sure about.", 100),
        Challenge("c18", EcoCategory.WASTE, "Pass it on", "Give away or sell one thing you no longer use.", 1000),
        Challenge("c19", EcoCategory.WASTE, "Coffee in a cup", "Use your own cup or mug for takeaway drinks.", 60),
        Challenge("c20", EcoCategory.WATER, "Four-minute shower", "Keep your shower to four minutes.", 300),
        Challenge("c21", EcoCategory.WATER, "Full loads only", "Only run the dishwasher or washing machine when full.", 400),
        Challenge("c22", EcoCategory.WATER, "Tap off", "Turn the tap off while brushing your teeth.", 20),
        Challenge("c23", EcoCategory.DIGITAL, "Inbox detox", "Unsubscribe from five newsletters you never read.", 30),
        Challenge("c24", EcoCategory.DIGITAL, "Cloud clean-up", "Delete old duplicate photos and videos from your cloud storage.", 50),
        Challenge("c25", EcoCategory.DIGITAL, "Lower the stream", "Watch videos in standard definition on your phone today.", 100),
        Challenge("c26", EcoCategory.DIGITAL, "Keep it longer", "Check your phone's battery health and turn on battery protection — devices that last longer save the most.", 500),
        Challenge("c27", EcoCategory.DIGITAL, "Dark side", "Use dark mode on your OLED phone all day.", 10),
        Challenge("c28", EcoCategory.NATURE, "Notice nature", "Spend ten minutes outside and spot three different plants or birds.", 0),
        Challenge("c29", EcoCategory.NATURE, "Water a tree", "Water a young street tree or a houseplant that needs it.", 0),
        Challenge("c30", EcoCategory.NATURE, "Share the idea", "Tell a friend about one change you made this week.", 0),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): Challenge? = byId[id]

    /** Today's challenge; [seed] personalises the rotation per user. */
    fun forDay(day: LocalDate = LocalDate.now(), seed: Int = 0): Challenge {
        val idx = Math.floorMod(day.toEpochDay() + seed, all.size.toLong()).toInt()
        return all[idx]
    }

    /** A few extra challenges to swipe through after today's. */
    fun deck(day: LocalDate = LocalDate.now(), seed: Int = 0, size: Int = 5): List<Challenge> =
        (0 until size).map { forDay(day.plusDays(it.toLong() * 7), seed + it * 3) }.distinctBy { it.id }
}
