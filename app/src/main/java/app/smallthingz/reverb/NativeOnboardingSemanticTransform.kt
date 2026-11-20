package app.smallthingz.reverb

import smallthingz.ui.graphics.Matrix

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
    val values = target.values
    if (values.size < 16) return
    val pivotX = width / 2f
    val pivotY = height / 2f
    // The source zero-angle rotation leaves signed zero coefficients. Keep them in
    // the dot products and retain the source additions around the pivot.
    val scaledZeroX = 0f * scaleX
    val scaledZeroY = 0f * scaleY
    // In the source rotation sequence, a nonfinite translation can also affect
    // the X and Z coefficients through these zero-angle products.
    val translationZAfterX = translationY * 0f + 0f
    val translationXAfterY = translationX * 1f + translationZAfterX * 0f
    val translationYAfterX = translationY * 1f - 0f
    val translationZAfterY = -translationX * 0f + translationZAfterX * 1f
    values[0] = scaleX
    values[1] = scaledZeroX
    values[2] = scaledZeroX
    values[3] = 0f
    values[4] = scaledZeroY
    values[5] = scaleY
    values[6] = scaledZeroY
    values[7] = 0f
    values[8] = 0f
    values[9] = -0f
    values[10] = 1f
    values[11] = 0f
    values[12] = -pivotX * scaleX - pivotY * scaledZeroY + translationXAfterY + pivotX
    values[13] = -pivotX * scaledZeroX - pivotY * scaleY + translationYAfterX + pivotY
    values[14] = -pivotX * scaledZeroX - pivotY * scaledZeroY + translationZAfterY
    values[15] = 1f
}
