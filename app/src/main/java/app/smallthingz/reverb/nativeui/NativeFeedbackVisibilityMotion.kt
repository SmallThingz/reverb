/*
 * Source-derived from AndroidX Compose Animation 1.11.2 EnterExitTransition/Transition.
 * Copyright The Android Open Source Project. Apache-2.0.
 */
package app.smallthingz.reverb.nativeui

import app.smallthingz.nativeui.animation.core.AnimationVector1D
import app.smallthingz.nativeui.animation.core.AnimationVector2D
import app.smallthingz.nativeui.animation.core.Spring
import app.smallthingz.nativeui.animation.core.TargetBasedAnimation
import app.smallthingz.nativeui.animation.core.VectorConverter
import app.smallthingz.nativeui.animation.core.spring
import app.smallthingz.nativeui.unit.IntOffset
import kotlin.math.roundToLong

/**
 * Only the released slide(height / 2) + fade channels. No frame scheduler or rendering tree.
 * AppFeedbackHost removes its FeedbackCard when event becomes null, even while the enclosing
 * transition exits. The host therefore supplies height=0 during that content-free exit.
 */
internal class NativeFeedbackVisibilityMotion {
    private var firstFrame = Long.MIN_VALUE
    private var playTime = 0L
    private var alphaStart = 0L
    private var offsetStart = 0L
    private var fade: TargetBasedAnimation<Float, AnimationVector1D>? = null
    private var slide: TargetBasedAnimation<IntOffset, AnimationVector2D>? = null
    private var alphaVelocity = AnimationVector1D(0f)
    private var offsetVelocity = AnimationVector2D(0f, 0f)
    private var fadeFinished = true
    private var slideFinished = true
    private var shown = false
    var composed = false
        private set
    var alpha = 0f
        private set
    var offset = IntOffset.Zero
        private set
    val running get() = composed && (!fadeFinished || !slideFinished)

    fun target(visible: Boolean, contentHeight: Int) {
        if (!composed) {
            if (!visible) return
            composed = true
            shown = true
            firstFrame = Long.MIN_VALUE
            playTime = 0L
            alphaStart = 0L
            offsetStart = 0L
            alpha = 0f
            offset = IntOffset(0, contentHeight / 2)
            alphaVelocity = AnimationVector1D(0f)
            offsetVelocity = AnimationVector2D(0f, 0f)
            fade = fadeTo(1f)
            slide = slideTo(IntOffset.Zero)
            fadeFinished = false
            slideFinished = false
            return
        }
        val targetAlpha = if (visible) 1f else 0f
        val targetOffset = if (visible) IntOffset.Zero else IntOffset(0, contentHeight / 2)
        val changeFade = shown != visible
        val changeSlide = slide?.targetValue != targetOffset
        if ((changeFade || changeSlide) && !running) {
            firstFrame = Long.MIN_VALUE
            playTime = 0L
            alphaStart = 0L
            offsetStart = 0L
        }
        shown = visible
        if (changeFade) {
            fade = fadeTo(targetAlpha)
            alphaStart = playTime
            fadeFinished = false
        }
        if (changeSlide) {
            slide = slideTo(targetOffset)
            offsetStart = playTime
            slideFinished = false
        }
    }

    private fun fadeTo(value: Float) = TargetBasedAnimation(
        spring<Float>(stiffness = Spring.StiffnessMediumLow),
        Float.VectorConverter, alpha, value, alphaVelocity,
    )
    private fun slideTo(value: IntOffset) = TargetBasedAnimation(
        spring<IntOffset>(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1)),
        IntOffset.VectorConverter, offset, value, offsetVelocity,
    )

    fun frame(timeNanos: Long, durationScale: Float) {
        if (!running) return
        if (firstFrame == Long.MIN_VALUE) firstFrame = timeNanos
        val delta = timeNanos - firstFrame
        playTime = if (durationScale == 0f) delta else (delta / durationScale.toDouble()).roundToLong()
        if (!fadeFinished) {
            val animation = checkNotNull(fade)
            val time = if (durationScale == 0f) animation.durationNanos else (playTime - alphaStart).coerceAtLeast(0L)
            alpha = animation.getValueFromNanos(time)
            alphaVelocity = animation.getVelocityVectorFromNanos(time)
            fadeFinished = animation.isFinishedFromNanos(time)
        }
        if (!slideFinished) {
            val animation = checkNotNull(slide)
            val time = if (durationScale == 0f) animation.durationNanos else (playTime - offsetStart).coerceAtLeast(0L)
            offset = animation.getValueFromNanos(time)
            offsetVelocity = animation.getVelocityVectorFromNanos(time)
            slideFinished = animation.isFinishedFromNanos(time)
        }
        if (!running && !shown) composed = false
    }

    fun reset() {
        composed = false
        shown = false
        fadeFinished = true
        slideFinished = true
        fade = null
        slide = null
        firstFrame = Long.MIN_VALUE
        playTime = 0L
        alpha = 0f
        offset = IntOffset.Zero
        alphaVelocity = AnimationVector1D(0f)
        offsetVelocity = AnimationVector2D(0f, 0f)
    }
}
