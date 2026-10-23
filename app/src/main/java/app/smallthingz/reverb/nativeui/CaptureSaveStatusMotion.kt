/*
 * Source-derived CaptureScreen1887–1917 and AndroidX animation1.11.2 EnterExitTransition.
 * Copyright The Android Open Source Project. Licensed under Apache-2.0.
 */
package app.smallthingz.reverb.nativeui

import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.AnimationVector2D
import smallthingz.ui.animation.core.Spring
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.spring
import smallthingz.ui.unit.IntOffset
import kotlin.math.roundToLong

/** Slide/fade never changes measured card height; only the completed exit removes the subtree. */
internal class CaptureSaveStatusMotion(initiallyVisible: Boolean) {
    private enum class Phase { PreEnter, Visible, PostExit }
    private var current = if (initiallyVisible) Phase.Visible else Phase.PreEnter
    private var target = current
    private var fullHeight: Int? = null
    private var slide: LibraryTransitionValue<IntOffset, AnimationVector2D>? = null
    private var fade: LibraryTransitionValue<Float, AnimationVector1D>? = null
    private var startFrame: Long? = null
    private var playTime = 0L
    private var stateUpdate = false
    var exitCompleted = false
        private set
    val running get() = slide?.running == true || fade?.running == true
    val visibleTarget get() = target == Phase.Visible
    val offsetY get() = slide?.value?.y ?: 0
    val alpha get() = fade?.value ?: if (visibleTarget) 1f else 0f

    fun setVisible(visible: Boolean) {
        val next = if (visible) Phase.Visible else if (current == Phase.PreEnter) Phase.PreEnter else Phase.PostExit
        if (target == next) return
        val previousTarget = target
        if (current != previousTarget) current = previousTarget
        target = if (!visible && current == Phase.Visible) Phase.PostExit else next
        stateUpdate = true
        exitCompleted = false
        fullHeight?.let(::measured)
    }

    fun measured(height: Int) {
        fullHeight = height
        fun offset(phase: Phase) = IntOffset(0, when (phase) {
            Phase.PreEnter -> height / 2
            Phase.Visible -> 0
            Phase.PostExit -> height
        })
        if (slide == null) {
            slide = LibraryTransitionValue(offset(current), IntOffset.VectorConverter,
                spring(visibilityThreshold = IntOffset(1, 1)), { AnimationVector2D(0f, 0f) })
            fade = LibraryTransitionValue(if (current == Phase.Visible) 1f else 0f,
                Float.VectorConverter, spring(visibilityThreshold = .01f), { AnimationVector1D(0f) })
        }
        val position = checkNotNull(slide)
        val opacity = checkNotNull(fade)
        if (current == target && target == Phase.Visible && !running && !stateUpdate) {
            position.snap(IntOffset.Zero)
            opacity.snap(1f)
            return
        }
        position.target(offset(target), spring(stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = IntOffset(1, 1)), playTime, stateUpdate)
        opacity.target(if (visibleTarget) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow),
            playTime, stateUpdate)
        stateUpdate = false
    }

    fun frame(timeNanos: Long, durationScale: Float) {
        if (!running) return
        require(durationScale >= 0f)
        val start = startFrame ?: timeNanos.also { startFrame = it }
        playTime = if (durationScale == 0f) timeNanos - start
            else ((timeNanos - start) / durationScale.toDouble()).roundToLong()
        slide?.frame(playTime, durationScale == 0f)
        fade?.frame(playTime, durationScale == 0f)
        if (!running) {
            current = target
            startFrame = null
            playTime = 0L
            if (target == Phase.PostExit) exitCompleted = true
        }
    }
}
