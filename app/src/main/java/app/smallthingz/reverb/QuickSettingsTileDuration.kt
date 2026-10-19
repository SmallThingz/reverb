package app.smallthingz.reverb

/** The persisted tile format intentionally uses an amount and one unit, never rounded wire values. */
internal fun quickTileRepresentableSeconds(seconds: Int): Int = when {
    seconds <= 999 -> seconds.coerceAtLeast(1)
    seconds <= 999 * 60 -> maxOf(999, seconds / 60 * 60)
    else -> maxOf(999 * 60, seconds.coerceAtMost(86_400) / 3600 * 3600)
}

internal fun quickTileAmountAndUnit(seconds: Int): Pair<Int, QuickSaveUnit> {
    require(seconds in 1..86_400)
    return when {
        seconds % 3600 == 0 -> seconds / 3600 to QuickSaveUnit.HOURS
        seconds % 60 == 0 && seconds / 60 <= 999 -> seconds / 60 to QuickSaveUnit.MINUTES
        seconds <= 999 -> seconds to QuickSaveUnit.SECONDS
        else -> throw IllegalArgumentException("Tile duration must use whole minutes or hours")
    }
}
