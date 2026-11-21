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
    // This runs on every custom-View draw while the wheel is moving. Keep the maximum split in
    // primitive locals instead of allocating RangeDurationWheelTimeParts every frame.
    val maximum = maximumWholeSeconds.coerceAtLeast(0)
    val maximumHours = maximum / 3_600
    val maximumMinutes = maximum % 3_600 / 60
    val maximumSeconds = maximum % 60
    return when {
        hours > maximumHours ->
            RANGE_DURATION_WHEEL_ERROR_HOUR or
                RANGE_DURATION_WHEEL_ERROR_MINUTE or
                RANGE_DURATION_WHEEL_ERROR_SECOND
        hours < maximumHours -> 0
        minutes > maximumMinutes ->
            RANGE_DURATION_WHEEL_ERROR_MINUTE or RANGE_DURATION_WHEEL_ERROR_SECOND
        minutes < maximumMinutes -> 0
        seconds > maximumSeconds -> RANGE_DURATION_WHEEL_ERROR_SECOND
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
    val lastRegularIndex = max / step
    // Preserve rejection of a progression too large for the former list's capacity.
    require(lastRegularIndex <= Int.MAX_VALUE - 3)
    val regularCount = lastRegularIndex + 1
    val addMaximum = includeMaximumBoundary && max % step != 0
    val addCurrent = (current > max || current % step != 0) && !(addMaximum && current == max)
    val filledCount = regularCount + if (addMaximum) 1 else 0
    val values = IntArray(filledCount + if (addCurrent) 1 else 0)
    for (index in 0 until regularCount) values[index] = index * step
    if (addMaximum) values[regularCount] = max
    if (addCurrent) {
        val index = if (current > max) filledCount else current / step + 1
        System.arraycopy(values, index, values, index + 1, filledCount - index)
        values[index] = current
    }
    return values
}
