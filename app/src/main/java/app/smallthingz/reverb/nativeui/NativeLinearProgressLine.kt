/*
 * drawLinearIndicator specialization from Material3 1.4.0 ProgressIndicator.kt 399–436.
 * Copyright The Android Open Source Project. Apache-2.0.
 */
package app.smallthingz.reverb.nativeui

import kotlin.math.abs

/** Shared determinate/indeterminate default round-cap line geometry, with the source Butt fallback. */
internal inline fun nativeLinearProgressLine(
    width: Float,
    height: Float,
    rtl: Boolean,
    start: Float,
    end: Float,
    draw: (startX: Float, endX: Float, y: Float, round: Boolean) -> Unit,
) {
    val a = (if (rtl) 1f - end else start) * width
    val b = (if (rtl) 1f - start else end) * width
    if (height > width) {
        draw(a, b, height / 2f, false)
    } else if (abs(end - start) > 0f) {
        val radius = height / 2f
        draw(a.coerceIn(radius, width - radius), b.coerceIn(radius, width - radius), height / 2f, true)
    }
}
