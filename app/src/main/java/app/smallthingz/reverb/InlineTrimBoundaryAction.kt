package app.smallthingz.reverb

import kotlin.math.roundToInt

internal fun inlineTrimBoundaryActionAllowed(
    prepared: Boolean,
    trimMode: Boolean,
    saving: Boolean,
    released: Boolean,
    visible: Boolean,
    resumed: Boolean,
    gestureActive: Boolean,
): Boolean = prepared && trimMode && !saving && !released && visible && resumed && !gestureActive

internal fun inlineTrimBoundaryLimits(durationMillis: Int, target: InlineFineSeekTarget): IntRange? {
    if (durationMillis <= 0) return null
    val minimumRange = minOf(50, durationMillis)
    return when (target) {
        InlineFineSeekTarget.TRIM_START -> 0..(durationMillis - minimumRange)
        InlineFineSeekTarget.TRIM_END -> minimumRange..durationMillis
        InlineFineSeekTarget.PLAYHEAD -> null
    }
}

/** Advertised Float endpoints retain the exact source bounds even on multi-day recordings. */
internal fun inlineTrimBoundaryProgressMillis(
    seconds: Float,
    durationMillis: Int,
    target: InlineFineSeekTarget,
): Double? {
    if (!seconds.isFinite()) return null
    val limits = inlineTrimBoundaryLimits(durationMillis, target) ?: return null
    return when {
        seconds <= limits.first.toFloat() / 1000f -> limits.first.toDouble()
        seconds >= limits.last.toFloat() / 1000f -> limits.last.toDouble()
        else -> seconds.toDouble() * 1000.0
    }
}

/** Discrete input uses the same boundary policy as dragging, without borrowing a scrub owner. */
internal fun runInlineTrimBoundaryAction(
    values: InlineFineSeekValues,
    durationMillis: Int,
    target: InlineFineSeekTarget,
    requestedMillis: Double,
    ready: Boolean,
    pause: () -> Unit,
    apply: (InlineFineSeekValues) -> Unit,
): Boolean {
    if (!ready || !requestedMillis.isFinite()) return false
    val limits = inlineTrimBoundaryLimits(durationMillis, target) ?: return false
    val requested = requestedMillis.coerceIn(limits.first.toDouble(), limits.last.toDouble()).roundToInt()
    val adjusted = adjustInlineTrimTarget(values, durationMillis, target, requested)
    pause()
    apply(adjusted)
    return true
}
