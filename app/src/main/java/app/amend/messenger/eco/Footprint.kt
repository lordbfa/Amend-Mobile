package app.amend.messenger.eco

/**
 * Rough, transparent estimates of the carbon cost of amend's own data use.
 *
 * Published estimates of the energy intensity of internet data transfer vary
 * by more than 10x depending on method and year, so amend uses a middle value
 * and shows its assumptions in the Impact screen rather than pretending precision.
 */
object Footprint {
    /** Network + data-centre electricity per gigabyte transferred (kWh/GB). */
    const val KWH_PER_GB = 0.03

    /** Average grid carbon intensity (g CO2e per kWh). */
    const val GRID_G_PER_KWH = 450.0

    /** A 10 W LED bulb burning for one hour (Wh). */
    const val LED_BULB_WH_PER_HOUR = 10.0

    /** Rough CO2 a growing tree absorbs per day (g), ~21 kg per year. */
    const val TREE_G_PER_DAY = 21_000.0 / 365.0

    fun gramsCo2(bytes: Long): Double = bytes / 1e9 * KWH_PER_GB * GRID_G_PER_KWH

    fun wattHours(bytes: Long): Double = bytes / 1e9 * KWH_PER_GB * 1000.0

    /** How many seconds a 10 W LED could run on the same energy. */
    fun ledSeconds(bytes: Long): Double = wattHours(bytes) / LED_BULB_WH_PER_HOUR * 3600.0

    /** How many minutes of a tree's daily CO2 uptake would offset it. */
    fun treeMinutes(bytes: Long): Double = gramsCo2(bytes) / TREE_G_PER_DAY * 24 * 60

    /** Human-friendly mass, e.g. "0.4 mg", "12 g". */
    fun formatGrams(g: Double): String = when {
        g < 0.001 -> String.format("%.1f µg", g * 1_000_000)
        g < 1.0 -> String.format("%.1f mg", g * 1000)
        g < 1000 -> String.format("%.1f g", g)
        else -> String.format("%.2f kg", g / 1000)
    }

    fun formatBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format("%.1f MB", b / 1024.0 / 1024.0)
        else -> String.format("%.2f GB", b / 1024.0 / 1024.0 / 1024.0)
    }
}
