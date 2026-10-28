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
    snapshotDurationSeconds: Double? = null,
): RangeEditValues {
    fun duration(candidate: RangeEditValues): Double = if (snapshotDurationSeconds != null) {
        rangeSelectionDurationExactSeconds(candidate.startSeconds, candidate.endSeconds, snapshotDurationSeconds)
    } else rangeSelectionDurationExactSeconds(candidate.startSeconds, candidate.endSeconds)
    if (!maximumSeconds.isFinite() || maximumSeconds <= 0.0 ||
        duration(values) <= maximumSeconds
    ) return values
    return when (target) {
        RangeEditTarget.END -> {
            val exactStart = snapshotDurationSeconds?.let {
                rangeSnapshotStartSeconds(it, values.startSeconds, values.endSeconds)
            } ?: values.startSeconds.toDouble()
            var end = (exactStart + maximumSeconds).toFloat()
            if (duration(RangeEditValues(values.startSeconds, end)) > maximumSeconds) end = java.lang.Math.nextDown(end)
            RangeEditValues(values.startSeconds, end.coerceAtLeast(values.startSeconds))
        }
        RangeEditTarget.START -> {
            val exactEnd = snapshotDurationSeconds?.let {
                rangeSnapshotEndSeconds(it, values.startSeconds, values.endSeconds)
            } ?: values.endSeconds.toDouble()
            var start = (exactEnd - maximumSeconds).toFloat()
            if (duration(RangeEditValues(start, values.endSeconds)) > maximumSeconds) start = java.lang.Math.nextUp(start)
            RangeEditValues(start.coerceAtMost(values.endSeconds), values.endSeconds)
        }
    }
}
