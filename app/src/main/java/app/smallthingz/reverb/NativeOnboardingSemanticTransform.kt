package app.smallthingz.reverb

import app.smallthingz.nativeui.graphics.Matrix

/**
 * Released preview graphicsLayer has the default center pivot, translation and uniform scale.
 * UI1.11.2 GraphicsLayerOwnerLayer408-428 computes semantic coordinates with this public method,
 * whose Float operation order differs slightly from Android View.matrix. Drawing stays native.
 */
internal fun populateOnboardingPreviewSemanticTransform(
    target: Matrix,
    width: Int,
    height: Int,
    translationX: Float,
    translationY: Float,
    scaleX: Float,
    scaleY: Float,
) {
    target.resetToPivotedTransform(
        pivotX = width / 2f, pivotY = height / 2f,
        translationX = translationX, translationY = translationY,
        scaleX = scaleX, scaleY = scaleY,
    )
}
