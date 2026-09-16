package app.smallthingz.reverb

import kotlin.math.ceil
import kotlin.math.floor

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

internal const val RANGE_DURATION_WHEEL_ERROR_HOUR = 1
internal const val RANGE_DURATION_WHEEL_ERROR_MINUTE = 1 shl 1
internal const val RANGE_DURATION_WHEEL_ERROR_SECOND = 1 shl 2

internal fun rangeDurationWheelErrorMask(
    hours: Int,
    minutes: Int,
    seconds: Int,
    maximumWholeSeconds: Int,
): Int {
    val maximum = splitRangeDurationWheelSeconds(maximumWholeSeconds)
    return when {
        hours > maximum.hours ->
            RANGE_DURATION_WHEEL_ERROR_HOUR or
                RANGE_DURATION_WHEEL_ERROR_MINUTE or
                RANGE_DURATION_WHEEL_ERROR_SECOND
        hours < maximum.hours -> 0
        minutes > maximum.minutes ->
            RANGE_DURATION_WHEEL_ERROR_MINUTE or RANGE_DURATION_WHEEL_ERROR_SECOND
        minutes < maximum.minutes -> 0
        seconds > maximum.seconds -> RANGE_DURATION_WHEEL_ERROR_SECOND
        else -> 0
    }
}

internal fun rangeDurationWheelWholeLimitSeconds(exactSeconds: Double): Int {
    if (!exactSeconds.isFinite() || exactSeconds <= 0.0) return 0
    return floor(exactSeconds)
        .coerceAtMost(Int.MAX_VALUE.toDouble())
        .toInt()
}

internal fun rangeDurationWheelDisplaySeconds(actualSeconds: Double, exactLimitSeconds: Double): Int {
    if (!actualSeconds.isFinite() || actualSeconds <= 0.0) return 0
    val rounded = if (actualSeconds > exactLimitSeconds) ceil(actualSeconds) else floor(actualSeconds)
    return rounded.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
}

internal fun rangeDurationWheelCandidateIsOverLimit(
    hours: Int,
    minutes: Int,
    seconds: Int,
    maximumDurationSecondsExact: Double,
): Boolean {
    if (!maximumDurationSecondsExact.isFinite() || maximumDurationSecondsExact < 0.0) return true
    val candidateSeconds = hours.coerceAtLeast(0).toLong() * 3_600L +
        minutes.coerceIn(0, 59).toLong() * 60L +
        seconds.coerceIn(0, 59).toLong()
    return candidateSeconds.toDouble() > maximumDurationSecondsExact
}

internal fun rangeDurationWheelValues(
    step: Int,
    maxInclusive: Int,
    currentValue: Int,
    includeMaximumBoundary: Boolean = true,
): IntArray {
    require(step > 0)
    val max = maxInclusive.coerceAtLeast(0)
    val current = currentValue.coerceAtLeast(0)
    val values = ArrayList<Int>(max / step + 3)
    var value = 0
    while (value <= max) {
        values += value
        if (value > Int.MAX_VALUE - step) break
        value += step
    }
    if (includeMaximumBoundary && values.lastOrNull() != max) values += max
    if (current !in values) values += current
    values.sort()
    return values.distinct().toIntArray()
}
