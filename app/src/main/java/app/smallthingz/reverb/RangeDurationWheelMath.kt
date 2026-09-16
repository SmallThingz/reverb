package app.smallthingz.reverb

import kotlin.math.abs

internal const val RANGE_DURATION_WHEEL_MAX_HOURS: Int = Int.MAX_VALUE / 3_600

internal data class RangeDurationWheelTimeParts(
    val hours: Int,
    val minutes: Int,
    val seconds: Int,
)

internal fun splitRangeDurationWheelSeconds(totalSeconds: Int): RangeDurationWheelTimeParts {
    val safe = totalSeconds.coerceAtLeast(0)
    return RangeDurationWheelTimeParts(
        hours = safe / 3_600,
        minutes = safe % 3_600 / 60,
        seconds = safe % 60,
    )
}

internal fun composeRangeDurationWheelSeconds(hours: Int, minutes: Int, seconds: Int): Int {
    val total = hours.coerceAtLeast(0).toLong() * 3_600L +
        minutes.coerceIn(0, 59).toLong() * 60L +
        seconds.coerceIn(0, 59).toLong()
    return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun rangeDurationWheelProfileStep(profileIndex: Int): Int = when (profileIndex) {
    1 -> 5
    2 -> 15
    else -> 1
}

internal fun nearestRangeDurationWheelSteppedValue(value: Int, step: Int): Int {
    require(step > 0 && 60 % step == 0)
    val safe = ((value % 60) + 60) % 60
    var best = 0
    var bestDistance = Int.MAX_VALUE
    var candidate = 0
    while (candidate < 60) {
        val direct = abs(candidate - safe)
        val distance = minOf(direct, 60 - direct)
        if (distance < bestDistance) {
            best = candidate
            bestDistance = distance
        }
        candidate += step
    }
    return best
}
