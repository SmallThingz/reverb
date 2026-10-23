/*
 * Source-derived from Material3 1.4.0 ProgressIndicator.kt and animation-core 1.11.2
 * InfiniteTransition.kt. Copyright The Android Open Source Project. Apache-2.0.
 */
package app.smallthingz.reverb.nativeui

import smallthingz.ui.animation.core.CubicBezierEasing
import smallthingz.ui.animation.core.TargetBasedAnimation
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.infiniteRepeatable
import smallthingz.ui.animation.core.keyframes

/** Four public animation-engine tracks; no Composition or separate animation clock. */
internal class NativeIndeterminateLinearMotion {
    var firstHead = 0f
        private set
    var firstTail = 0f
        private set
    var secondHead = 0f
        private set
    var secondTail = 0f
        private set
    private var origin = Long.MIN_VALUE
    private var lastScale = 1f

    /** InfiniteTransition restarts all children at the next frame after a duration-scale change. */
    fun frame(timeNanos: Long, durationScale: Float) {
        if (origin == Long.MIN_VALUE || lastScale != durationScale) {
            origin = timeNanos
            lastScale = durationScale
        }
        if (durationScale == 0f) {
            // skipToEnd uses each child's target (1), not its value at the repeat boundary.
            firstHead = 1f
            firstTail = 1f
            secondHead = 1f
            secondTail = 1f
        } else {
            val playTime = ((timeNanos - origin) / durationScale).toLong()
            firstHead = LinearIndeterminateTracks.firstHead.getValueFromNanos(playTime)
            firstTail = LinearIndeterminateTracks.firstTail.getValueFromNanos(playTime)
            secondHead = LinearIndeterminateTracks.secondHead.getValueFromNanos(playTime)
            secondTail = LinearIndeterminateTracks.secondTail.getValueFromNanos(playTime)
        }
    }

    fun reset() {
        origin = Long.MIN_VALUE
        lastScale = 1f
        firstHead = 0f
        firstTail = 0f
        secondHead = 0f
        secondTail = 0f
    }
}

private object LinearIndeterminateTracks {
    private val easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    private fun track(delay: Int, duration: Int) = TargetBasedAnimation(
        infiniteRepeatable(animation = keyframes<Float> {
            durationMillis = 1750
            0f at delay using easing
            1f at delay + duration
        }),
        Float.VectorConverter, 0f, 1f,
    )
    val firstHead = track(0, 1000)
    val firstTail = track(250, 1000)
    val secondHead = track(650, 850)
    val secondTail = track(900, 850)
}

/**
 * Exact draw order, gaps and directed fractions from ProgressIndicator.kt 287–335.
 * The active segments intentionally pass head before tail; do not sort or clamp fractions.
 */
internal inline fun nativeIndeterminateLinearSegments(
    width: Float,
    height: Float,
    density: Float,
    firstHead: Float,
    firstTail: Float,
    secondHead: Float,
    secondTail: Float,
    draw: (start: Float, end: Float, active: Boolean) -> Unit,
) {
    val adjustedGap = if (height > width) 4f else 4f + height / density
    val gap = adjustedGap / (width / density)
    if (firstHead < 1f - gap) {
        draw(if (firstHead > 0f) firstHead + gap else 0f, 1f, false)
    }
    if (firstHead - firstTail > 0f) draw(firstHead, firstTail, true)
    if (firstTail > gap) {
        draw(if (secondHead > 0f) secondHead + gap else 0f,
            if (firstTail < 1f) firstTail - gap else 1f, false)
    }
    if (secondHead - secondTail > 0f) draw(secondHead, secondTail, true)
    if (secondTail > gap) draw(0f, if (secondTail < 1f) secondTail - gap else 1f, false)
}
