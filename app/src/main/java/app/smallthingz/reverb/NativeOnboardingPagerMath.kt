package app.smallthingz.reverb

internal typealias NativePagerSnapBounds = smallthingz.ui.reverb.onboarding.NativePagerSnapBounds

internal fun nativePagerCurrentPage(offsetPx: Long, pageWidthPx: Int): Int =
    smallthingz.ui.reverb.onboarding.nativePagerCurrentPage(offsetPx, pageWidthPx)

internal fun nativePagerPositionThresholdFraction(pageWidthPx: Int, density: Float): Float =
    smallthingz.ui.reverb.onboarding.nativePagerPositionThresholdFraction(pageWidthPx, density)

internal fun nativePagerSnapBounds(
    offsetPx: Long,
    pageWidthPx: Int,
    canScrollForward: Boolean,
    canScrollBackward: Boolean,
    scrollingForward: Boolean,
): NativePagerSnapBounds = smallthingz.ui.reverb.onboarding.nativePagerSnapBounds(
    offsetPx, pageWidthPx, canScrollForward, canScrollBackward, scrollingForward,
)

internal fun nativePagerFinalSnapDistance(
    bounds: NativePagerSnapBounds,
    logicalVelocity: Float,
    downUpX: Float,
    downUpY: Float,
    pageWidthPx: Int,
    density: Float,
    rtl: Boolean,
): Float = smallthingz.ui.reverb.onboarding.nativePagerFinalSnapDistance(
    bounds, logicalVelocity, downUpX, downUpY, pageWidthPx, density, rtl,
)

internal fun nativePagerCoerceAnimationValue(value: Float, target: Float): Float =
    smallthingz.ui.reverb.onboarding.nativePagerCoerceAnimationValue(value, target)
