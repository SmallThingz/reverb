package app.smallthingz.reverb.nativeui

import kotlin.math.roundToInt

/** Foundation 1.11.2 Arrangement.Center places physical-order children before rounding. */
internal fun nativeCenteredRowChildOffset(totalSize: Int, usedSize: Int, beforeChildSize: Int): Int =
    ((totalSize - usedSize) / 2f + beforeChildSize).roundToInt()
