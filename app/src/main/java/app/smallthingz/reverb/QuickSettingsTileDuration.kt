package app.smallthingz.reverb

/** The persisted tile format intentionally uses an amount and one unit, never rounded wire values. */
internal fun quickTileRepresentableSeconds(seconds: Int): Int = when {
    seconds <= 999 -> seconds.coerceAtLeast(1)
    seconds <= 999 * 60 -> maxOf(999, seconds / 60 * 60)
    else -> maxOf(999 * 60, seconds.coerceAtMost(86_400) / 3600 * 3600)
}

/** Step to a distinct wire-representable duration, without crossing the current capacity. */
internal fun quickTileStepSeconds(seconds: Int, maximum: Int, forward: Boolean): Int {
    val current = seconds.coerceIn(1, 86_400)
    val limit = quickTileRepresentableSeconds(maximum.coerceIn(1, 86_400))
    val stepped = if (forward) when {
        current < 999 -> current + 1
        current < 999 * 60 -> (current / 60 + 1) * 60
        else -> (current / 3600 + 1) * 3600
    } else quickTileRepresentableSeconds(current - 1)
    return stepped.coerceIn(1, limit)
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
