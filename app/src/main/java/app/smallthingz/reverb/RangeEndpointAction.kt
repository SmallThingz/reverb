package app.smallthingz.reverb

/** Reachable endpoint bounds use the same minimum gap and corner pushing as pointer edits. */
internal fun rangeEndpointLimits(
    durationSeconds: Float,
    target: RangeEditTarget,
): ClosedFloatingPointRange<Float>? {
    if (!durationSeconds.isFinite() || durationSeconds <= 0f) return null
    val fullRange = RangeEditValues(0f, durationSeconds)
    fun bound(requested: Float): Float {
        val values = adjustRangeEditTarget(fullRange, target, requested, durationSeconds).values
        return when (target) {
            RangeEditTarget.START -> values.startSeconds
            RangeEditTarget.END -> values.endSeconds
        }
    }
    return bound(0f)..bound(durationSeconds)
}

/** Selection and discrete edits share live admission; rejection never pauses a newer owner. */
internal fun runRangeEndpointAction(
    values: RangeEditValues,
    durationSeconds: Float,
    target: RangeEditTarget,
    requestedSeconds: Double?,
    gesture: RangeInputGesture,
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

/** Auto-repeat retains its original wheel generation, including a complete intervening scroll. */
internal class RangeEndpointKeyOwner {
    private var keyCode: Int? = null
    private var gesture: RangeInputGesture? = null

    fun keyDown(keyCode: Int, repeatCount: Int, create: () -> RangeInputGesture): RangeInputGesture? {
        if (repeatCount == 0) {
            clear()
            this.keyCode = keyCode
            gesture = create()
        }
        return gesture.takeIf { this.keyCode == keyCode }
    }

    fun keyUp(keyCode: Int) {
        if (this.keyCode == keyCode) clear()
    }

    fun clear() {
        gesture?.cancel()
        gesture = null
        keyCode = null
    }
}
