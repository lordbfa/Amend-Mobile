package app.amend.messenger.data

/**
 * What rubbish people actually find depends on where they live, so the eco map shows a
 * local list. The region comes from the country of the weather location (see EcoWeather).
 *
 * Sources for the choices (checked November 2026):
 * - Bangladesh / South Asia: plastic polythene bags and PET bottles dominate street and
 *   drain litter (e.g. ~140 t of plastic littered daily in Chattogram, TBS News 2022);
 *   open dumping and burning are common; betel (paan) spit is a widespread street problem.
 * - Finland / Nordics: deposit-return systems (pantti / pant) recover almost all bottles and
 *   cans, so they're rarely littered. Cigarette butts are the most common litter (City of
 *   Helsinki), and snus / nicotine pouches are the fast-growing second (Keep Sweden Tidy 2024).
 * - Rest of Europe: cigarette butts, plastic bottles and caps, food packaging and bags lead
 *   the EU's beach litter counts (EEA Marine Litter Watch).
 */
data class EcoItem(val category: EcoCategory, val hint: String, val tip: String)

data class EcoRegion(val id: String, val name: String, val items: List<EcoItem>) {
    fun tipFor(c: EcoCategory): String? = items.firstOrNull { it.category == c }?.tip?.takeIf { it.isNotBlank() }
    fun hintFor(c: EcoCategory): String = items.firstOrNull { it.category == c }?.hint ?: c.hint
}

object EcoRegions {
    private val SOUTH_ASIA = setOf("BD", "IN", "PK", "NP", "LK", "BT", "MV", "MM", "AF")
    private val NORDICS = setOf("FI", "SE", "NO", "DK", "IS", "AX", "FO", "GL")
    private val EUROPE = setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "EE", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
        "PL", "PT", "RO", "SK", "SI", "ES", "GB", "CH", "LI", "UA", "MD", "RS", "BA", "ME", "MK", "AL", "XK",
    )

    val southAsia = EcoRegion(
        "south_asia", "South Asia", listOf(
            EcoItem(EcoCategory.PLASTIC, "Polythene bags, chip and shampoo packets", "Carry a cloth or jute bag. Thin polythene bags are banned in Bangladesh."),
            EcoItem(EcoCategory.BOTTLES, "PET bottles and caps", "Bottles have value: give them to a local recycler (vangari / feriwala) instead of the bin."),
            EcoItem(EcoCategory.TRASH, "A pile or roadside dumping spot", "Report it to your city corporation or pourashava; ask neighbours to use the official collection point."),
            EcoItem(EcoCategory.DRAIN, "Rubbish blocking a drain or canal", "Blocked drains cause waterlogging and dengue. Never sweep rubbish into drains."),
            EcoItem(EcoCategory.SPIT, "Paan or gutkha spit on roads, stairs or walls", "Spitting spreads germs and stains for years. Use a bin or a tissue."),
            EcoItem(EcoCategory.AIR, "Burning rubbish or plastic", "Burning plastic releases toxic smoke. Collection or composting is safer."),
            EcoItem(EcoCategory.MEDICAL, "Syringes, masks, medicine strips", "Medical waste can injure waste pickers. Keep it apart and hand it to a clinic or pharmacy."),
            EcoItem(EcoCategory.DEBRIS, "Bricks, sand or rubble on the road", "Builders must keep debris covered and off the road."),
            EcoItem(EcoCategory.WATER, "Dirty river, canal or pond", ""),
            EcoItem(EcoCategory.TREES_CUT, "Felled or damaged trees", ""),
            EcoItem(EcoCategory.OTHER, "Something else", ""),
        ),
    )

    val nordics = EcoRegion(
        "nordics", "Nordic countries", listOf(
            EcoItem(EcoCategory.BUTTS, "On streets, stops and beaches", "The most common litter here. Each butt leaks chemicals and microplastic: use an ashtray or a pocket ashtray."),
            EcoItem(EcoCategory.SNUS, "Used pouches on the ground", "Pouches don't biodegrade fast. Put them in the bin, not on the ground."),
            EcoItem(EcoCategory.TAKEAWAY, "Cups, pizza boxes, wrappers", "Cardboard goes to the cardboard bin, plastic packaging to plastic collection."),
            EcoItem(EcoCategory.PLASTIC, "Bags, wrappers, plastic bits", "Clean plastic packaging can go to the plastic collection point."),
            EcoItem(EcoCategory.SCOOTER, "Blocking a path or thrown in water", "Report it to the operator's app so they collect it."),
            EcoItem(EcoCategory.DOG, "Bags or mess left behind", "Dog waste bags go in any mixed-waste bin."),
            EcoItem(EcoCategory.TRASH, "Dumped furniture or rubbish bags", "Bulky waste goes to a recycling centre or a flea market, never the forest."),
            EcoItem(EcoCategory.WATER, "Oil, foam or rubbish in water", ""),
            EcoItem(EcoCategory.TREES_CUT, "Felled or damaged trees", ""),
            EcoItem(EcoCategory.OTHER, "Something else", "Found bottles or cans? Return them for the deposit (pantti / pant)."),
        ),
    )

    val europe = EcoRegion(
        "europe", "Europe", listOf(
            EcoItem(EcoCategory.BUTTS, "On streets, stops and beaches", "The most common litter item in Europe. Use an ashtray."),
            EcoItem(EcoCategory.BOTTLES, "Bottles, cans and caps", "Where there's a deposit, return them to the shop."),
            EcoItem(EcoCategory.TAKEAWAY, "Cups, boxes, wrappers", ""),
            EcoItem(EcoCategory.PLASTIC, "Bags, wrappers, plastic bits", ""),
            EcoItem(EcoCategory.TRASH, "Dumped rubbish or furniture", "Bulky waste goes to a recycling centre."),
            EcoItem(EcoCategory.DOG, "Bags or mess left behind", ""),
            EcoItem(EcoCategory.SCOOTER, "Blocking a path or thrown in water", "Report it to the operator's app."),
            EcoItem(EcoCategory.WATER, "Oil, foam or rubbish in water", ""),
            EcoItem(EcoCategory.AIR, "Smoke or burning", ""),
            EcoItem(EcoCategory.TREES_CUT, "Felled or damaged trees", ""),
            EcoItem(EcoCategory.OTHER, "Something else", ""),
        ),
    )

    /** Everywhere else, until there's a local list. */
    val world = EcoRegion(
        "world", "Your area", listOf(
            EcoItem(EcoCategory.PLASTIC, "Bags, packets, wrappers", ""),
            EcoItem(EcoCategory.BOTTLES, "Bottles and cans", ""),
            EcoItem(EcoCategory.TRASH, "A pile or dumping spot", ""),
            EcoItem(EcoCategory.BUTTS, "Cigarette butts", ""),
            EcoItem(EcoCategory.DRAIN, "Rubbish blocking a drain", ""),
            EcoItem(EcoCategory.AIR, "Smoke or burning", ""),
            EcoItem(EcoCategory.WATER, "Polluted water", ""),
            EcoItem(EcoCategory.TREES_CUT, "Felled or damaged trees", ""),
            EcoItem(EcoCategory.OTHER, "Something else", ""),
        ),
    )

    fun forCountry(iso: String?): EcoRegion = when (iso?.uppercase()) {
        in SOUTH_ASIA -> southAsia
        in NORDICS -> nordics
        in EUROPE -> europe
        else -> world
    }
}
