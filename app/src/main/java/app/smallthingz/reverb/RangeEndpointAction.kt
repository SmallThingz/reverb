package app.smallthingz.reverb

import smallthingz.ui.common.UiInputGesture

/** Reachable endpoint bounds use the same minimum gap and corner pushing as pointer edits. */
internal fun rangeEndpointLimits(
    durationSeconds: Float,
    target: RangeEditTarget,
): ClosedFloatingPointRange<Float>? {
    if (!durationSeconds.isFinite() || durationSeconds <= 0f) return null
    val minimum = minOf(0.05f, durationSeconds)
    return when (target) {
        RangeEditTarget.START -> {
            val latest = durationSeconds - minimum
            // Match the pointer edit's collapsed-Float repair at very long durations.
            0f..(if (latest < durationSeconds) latest else java.lang.Math.nextDown(durationSeconds))
        }
        RangeEditTarget.END -> minimum..durationSeconds
    }
}

/** Selection and discrete edits share live admission; rejection never pauses a newer owner. */
internal fun runRangeEndpointAction(
    values: RangeEditValues,
    durationSeconds: Float,
    target: RangeEditTarget,
    requestedSeconds: Double?,
    gesture: UiInputGesture,
    pause: () -> Unit,
    apply: (RangeEditUpdate) -> Unit,
): Boolean {
    val limits = rangeEndpointLimits(durationSeconds, target) ?: return false
    if (!values.startSeconds.isFinite() || !values.endSeconds.isFinite()) return false
    if (requestedSeconds != null && !requestedSeconds.isFinite()) return false
    val update = if (requestedSeconds == null) {
        RangeEditUpdate(values)
    } else {
        adjustRangeEditTarget(
            values = values,
            target = target,
            requestedSeconds = requestedSeconds
                .coerceIn(limits.start.toDouble(), limits.endInclusive.toDouble()).toFloat(),
            durationSeconds = durationSeconds,
        )
    }
    return gesture.perform {
        pause()
        apply(update)
    }
}

internal typealias RangeEndpointKeyOwner = smallthingz.ui.reverb.RangeEndpointKeyOwner<RangeInputGesture>
