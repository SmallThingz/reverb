package app.smallthingz.reverb

import smallthingz.ui.geometry.Rect
import smallthingz.ui.geometry.Size

internal fun nativeOnboardingMinimumTouchBounds(size: Size, minimum: Size): Rect =
    smallthingz.ui.reverb.onboarding.nativeOnboardingMinimumTouchBounds(size, minimum)

internal fun nativeOnboardingClipTouchBounds(bounds: Rect, layer: Size, minimum: Size): Rect =
    smallthingz.ui.reverb.onboarding.nativeOnboardingClipTouchBounds(bounds, layer, minimum)
