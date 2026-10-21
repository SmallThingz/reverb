/*
 * Adapted from AndroidX foundation 1.11.2 PagerState/PagerMeasure and
 * PagerSnapLayoutInfoProvider. Copyright 2023-2024 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb

import kotlin.math.abs

internal const val NATIVE_ONBOARDING_PAGE_COUNT = 3

/** Fill pages, Start snap, no spacing/padding/reverseLayout: the released onboarding subset. */
internal fun nativePagerCurrentPage(offsetPx: Long, pageWidthPx: Int): Int {
    // PagerMeasure skips zero-size nonlast pages, leaving only the final page visible.
    // This is a measured result, distinct from the engine's initial unmeasured page 0.
    if (pageWidthPx == 0) return NATIVE_ONBOARDING_PAGE_COUNT - 1
    val first = (offsetPx / pageWidthPx).toInt()
    val remainder = offsetPx % pageWidthPx
    // PagerMeasure.fastMaxBy retains the FIRST visible page on equal snap distance.
    return (first + if (remainder * 2 > pageWidthPx) 1 else 0)
        .coerceIn(0, NATIVE_ONBOARDING_PAGE_COUNT - 1)
}

internal fun nativePagerPositionThresholdFraction(pageWidthPx: Int, density: Float): Float =
    minOf(56f * density, pageWidthPx / 2f) / pageWidthPx.toFloat()

internal data class NativePagerSnapBounds(val lower: Float, val upper: Float)

internal fun nativePagerSnapBounds(
    offsetPx: Long,
    pageWidthPx: Int,
    canScrollForward: Boolean,
    canScrollBackward: Boolean,
    scrollingForward: Boolean,
): NativePagerSnapBounds {
    if (pageWidthPx == 0) return NativePagerSnapBounds(0f, 0f)
    val first = (offsetPx / pageWidthPx).toInt().coerceIn(0, 2)
    val remainder = (offsetPx % pageWidthPx).toInt()
    var lower = (-remainder).toFloat()
    var upper = if (remainder == 0 || first == 2) lower else (pageWidthPx - remainder).toFloat()
    if (!canScrollForward) {
        upper = 0f
        if (scrollingForward) lower = 0f
    }
    if (!canScrollBackward) {
        lower = 0f
        if (!scrollingForward) upper = 0f
    }
    return NativePagerSnapBounds(lower, upper)
}

/** Same physical down/up displacement test as PagerState.isNotGestureAction. */
internal fun nativePagerScrollingForward(
    logicalVelocity: Float,
    downUpX: Float,
    downUpY: Float,
): Boolean {
    val notGesture = downUpX.toInt() == 0 && downUpY.toInt() == 0
    return !((if (notGesture) -logicalVelocity else downUpX) > 0f)
}

internal fun nativePagerFinalSnapDistance(
    bounds: NativePagerSnapBounds,
    logicalVelocity: Float,
    downUpX: Float,
    downUpY: Float,
    pageWidthPx: Int,
    density: Float,
    rtl: Boolean,
): Float {
    val scrollingForward = nativePagerScrollingForward(logicalVelocity, downUpX, downUpY)
    val forward = if (rtl) !scrollingForward else scrollingForward
    val displacement = if (pageWidthPx == 0) 0f else downUpX / pageWidthPx.toFloat()
    val overflow = displacement - displacement.toInt().toFloat()
    if (abs(logicalVelocity) >= 400f * density) {
        return if (logicalVelocity > 0f) bounds.upper else bounds.lower
    }
    return if (abs(overflow) > 0.5f) {
        if (forward) bounds.upper else bounds.lower
    } else if (abs(displacement) >= abs(nativePagerPositionThresholdFraction(pageWidthPx, density))) {
        if (forward) bounds.lower else bounds.upper
    } else {
        if (abs(bounds.lower) < abs(bounds.upper)) bounds.lower else bounds.upper
    }
}

internal fun nativePagerCoerceAnimationValue(value: Float, target: Float): Float =
    if (target == 0f) 0f else if (target > 0f) value.coerceAtMost(target) else value.coerceAtLeast(target)
