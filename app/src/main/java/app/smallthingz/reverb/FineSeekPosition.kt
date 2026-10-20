package app.smallthingz.reverb

import kotlin.math.roundToInt

/** Retain only this gesture's rounding remainder; clamped motion never becomes reverse debt. */
internal class FineSeekPosition<T : Any> {
    private var target: T? = null
    private var precise = 0.0
    private var applied = Double.NaN
    private var lower = Double.NaN
    private var upper = Double.NaN

    fun reset() {
        target = null
        applied = Double.NaN
    }

    fun project(target: T, current: Double, delta: Double, minimum: Double, maximum: Double): Double {
        val origin = if (this.target == target && applied == current && lower == minimum && upper == maximum) {
            precise
        } else {
            current
        }
        return (origin + (delta.takeIf { it.isFinite() } ?: 0.0)).coerceIn(minimum, maximum)
    }

    fun advance(
        target: T,
        current: Double,
        delta: Double,
        minimum: Double,
        maximum: Double,
        quantize: (Double) -> Double,
    ): Double {
        val requested = project(target, current, delta, minimum, maximum)
        val result = quantize(requested).coerceIn(minimum, maximum)
        this.target = target
        precise = requested
        applied = result
        lower = minimum
        upper = maximum
        return result
    }
}

internal fun adjustRangeFineSeekPosition(
    position: FineSeekPosition<RangeEditTarget>,
    values: RangeEditValues,
    target: RangeEditTarget,
    deltaSeconds: Float,
    durationSeconds: Float,
): RangeEditUpdate {
    val limits = rangeEndpointLimits(durationSeconds, target) ?: return RangeEditUpdate(values)
    val requested = position.advance(
        target, editTargetValue(values, target).toDouble(), deltaSeconds.toDouble(),
        limits.start.toDouble(), limits.endInclusive.toDouble(),
        quantize = { it.toFloat().toDouble() },
    )
    return adjustRangeEditTarget(values, target, requested.toFloat(), durationSeconds)
}

internal fun projectRangeFineSeekPosition(
    position: FineSeekPosition<RangeEditTarget>,
    values: RangeEditValues,
    target: RangeEditTarget,
    pendingDeltaSeconds: Float,
    durationSeconds: Float,
): Double {
    val limits = rangeEndpointLimits(durationSeconds, target) ?: return editTargetValue(values, target).toDouble()
    return position.project(
        target, editTargetValue(values, target).toDouble(), pendingDeltaSeconds.toDouble(),
        limits.start.toDouble(), limits.endInclusive.toDouble(),
    )
}

private fun inlineFineSeekPosition(values: InlineFineSeekValues, target: InlineFineSeekTarget): Int = when (target) {
    InlineFineSeekTarget.PLAYHEAD -> values.cursorMillis
    InlineFineSeekTarget.TRIM_START -> values.startMillis
    InlineFineSeekTarget.TRIM_END -> values.endMillis
}

private fun inlineFineSeekLimits(durationMillis: Int, target: InlineFineSeekTarget): IntRange =
    if (target == InlineFineSeekTarget.PLAYHEAD) 0..durationMillis.coerceAtLeast(1)
    else requireNotNull(inlineTrimBoundaryLimits(durationMillis.coerceAtLeast(1), target))

internal fun adjustInlineFineSeekPosition(
    position: FineSeekPosition<InlineFineSeekTarget>,
    values: InlineFineSeekValues,
    target: InlineFineSeekTarget,
    deltaSeconds: Float,
    durationMillis: Int,
): InlineFineSeekValues {
    val limits = inlineFineSeekLimits(durationMillis, target)
    val current = inlineFineSeekPosition(values, target)
    val requested = position.advance(
        target, current.toDouble(), deltaSeconds.toDouble() * 1_000.0,
        limits.first.toDouble(), limits.last.toDouble(),
        quantize = { it.roundToInt().toDouble() },
    ).toInt()
    return adjustInlineFineSeekTarget(values, durationMillis, target, requested - current)
}

internal fun projectInlineFineSeekPosition(
    position: FineSeekPosition<InlineFineSeekTarget>,
    values: InlineFineSeekValues,
    target: InlineFineSeekTarget,
    pendingDeltaSeconds: Float,
    durationMillis: Int,
): Double {
    val limits = inlineFineSeekLimits(durationMillis, target)
    return position.project(
        target, inlineFineSeekPosition(values, target).toDouble(), pendingDeltaSeconds.toDouble() * 1_000.0,
        limits.first.toDouble(), limits.last.toDouble(),
    ) / 1_000.0
}
