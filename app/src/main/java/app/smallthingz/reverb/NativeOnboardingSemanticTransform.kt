package app.smallthingz.reverb

import smallthingz.ui.graphics.Matrix

internal fun populateOnboardingPreviewSemanticTransform(
    target: Matrix,
    width: Int,
    height: Int,
    translationX: Float,
    translationY: Float,
    scaleX: Float,
    scaleY: Float,
) = smallthingz.ui.reverb.onboarding.populateOnboardingPreviewSemanticTransform(
    target, width, height, translationX, translationY, scaleX, scaleY,
)
