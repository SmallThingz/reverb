package app.smallthingz.reverb.nativeui

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * M3 1.4.0 MinimumInteractiveModifierNode reports the minimum even when size(46.dp)
 * constrains its apparent size. UI 1.11.2 Placeable first centers that real layout with
 * integer division; the minimum node then centers the visual using Float rounding.
 * An odd pixel difference therefore leaves a real one-pixel placement residual.
 */
internal fun nativeMinimumInteractiveVisualOffset(visualSize: Int, minimumSize: Int): Int {
    val realSize = max(visualSize, minimumSize)
    return (visualSize - realSize) / 2 + ((realSize - visualSize) / 2f).roundToInt()
}

/** Foundation 1.11.2 Arrangement.Center places physical-order children before rounding. */
internal fun nativeCenteredRowChildOffset(totalSize: Int, usedSize: Int, beforeChildSize: Int): Int =
    ((totalSize - usedSize) / 2f + beforeChildSize).roundToInt()
