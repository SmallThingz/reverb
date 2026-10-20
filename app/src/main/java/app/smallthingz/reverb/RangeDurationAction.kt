package app.smallthingz.reverb

/** Discrete accessible/key edits use the same pinned target transaction as the native dial. */
internal fun runRangeDurationAction(
    requestedSeconds: Double,
    maximumSeconds: Double,
    ready: Boolean,
    begin: () -> Unit,
    commit: (Float) -> Unit,
    end: () -> Unit,
): Boolean {
    if (!ready || !requestedSeconds.isFinite() || !maximumSeconds.isFinite() || maximumSeconds <= 0.0) return false
    val maximum = maximumSeconds
    val minimum = minOf(0.05, maximum)
    val seconds = requestedSeconds.coerceIn(minimum, maximum).toFloat()
    begin()
    try {
        commit(seconds)
    } finally {
        end()
    }
    return true
}

/** Float endpoints must stay inside the exact payload cap after addition/subtraction rounds. */
internal fun boundRangeDurationActionValues(
    values: RangeEditValues,
    target: RangeEditTarget,
    maximumSeconds: Double,
): RangeEditValues {
    if (!maximumSeconds.isFinite() || maximumSeconds <= 0.0 ||
        rangeSelectionDurationExactSeconds(values.startSeconds, values.endSeconds) <= maximumSeconds
    ) return values
    return when (target) {
        RangeEditTarget.END -> {
            var end = (values.startSeconds.toDouble() + maximumSeconds).toFloat()
            if (end.toDouble() - values.startSeconds.toDouble() > maximumSeconds) end = java.lang.Math.nextDown(end)
            RangeEditValues(values.startSeconds, end.coerceAtLeast(values.startSeconds))
        }
        RangeEditTarget.START -> {
            var start = (values.endSeconds.toDouble() - maximumSeconds).toFloat()
            if (values.endSeconds.toDouble() - start.toDouble() > maximumSeconds) start = java.lang.Math.nextUp(start)
            RangeEditValues(start.coerceAtMost(values.endSeconds), values.endSeconds)
        }
    }
}
