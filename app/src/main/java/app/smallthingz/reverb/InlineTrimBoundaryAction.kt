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

internal fun inlineTrimBoundaryLimits(durationMillis: Int, target: InlineFineSeekTarget): IntRange? =
    smallthingz.ui.reverb.inlineTrimBoundaryLimits(durationMillis, target)

internal fun inlineTrimBoundaryProgressMillis(seconds: Float, durationMillis: Int, target: InlineFineSeekTarget): Double? =
    smallthingz.ui.reverb.inlineTrimBoundaryProgressMillis(seconds, durationMillis, target)

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
