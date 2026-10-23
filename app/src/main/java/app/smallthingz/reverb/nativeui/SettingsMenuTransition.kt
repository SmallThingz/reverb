/*
 * Source-derived from AndroidX Material3 1.4.0 Menu.kt / MotionScheme.kt and
 * animation-core 1.11.2 Transition.kt. Copyright 2020-2023 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb.nativeui

import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.SpringSpec
import smallthingz.ui.animation.core.TargetBasedAnimation
import smallthingz.ui.animation.core.VectorConverter
import kotlin.math.roundToLong

/**
 * The released two Float transition children, sharing one scaled transition play-time.
 * Host acceptance/frame dispatch remains independently qualified; this class owns only the math.
 * This differs intentionally from Animatable: scale uses Double/roundToLong and interruptions
 * retain raw velocity vectors and the transition's play-time origin.
 */
internal class SettingsMenuTransition {
    private class Child(initial: Float, private val spec: SpringSpec<Float>) {
        var value = initial
        var target = initial
        var velocity = AnimationVector1D(0f)
        var animation: TargetBasedAnimation<Float, AnimationVector1D>? = null
        var delay = 0L
        fun retarget(next: Float, playTime: Long) {
            if (next == target) return
            target = next
            animation = TargetBasedAnimation(spec, Float.VectorConverter, value, next, velocity)
            delay = playTime
        }
        fun frame(playTime: Long, scaleToEnd: Boolean) {
            val active = animation ?: return
            val time = if (scaleToEnd) active.durationNanos else (playTime - delay).coerceAtLeast(0L)
            value = active.getValueFromNanos(time)
            velocity = active.getVelocityVectorFromNanos(time)
            if (active.isFinishedFromNanos(time)) animation = null
        }
    }
    private val scaleChild = Child(.8f, SpringSpec(dampingRatio = .9f, stiffness = 1400f))
    private val alphaChild = Child(0f, SpringSpec(dampingRatio = 1f, stiffness = 3800f))
    private var startTime: Long? = null
    private var playTime = 0L
    var targetExpanded = false
        private set
    var currentExpanded = false
        private set
    val scale get() = scaleChild.value
    val alpha get() = alphaChild.value
    val isRunning get() = scaleChild.animation != null || alphaChild.animation != null
    // Matches AndroidMenu's currentState || targetState popup lifetime, including early reversal.
    val shouldExist get() = currentExpanded || targetExpanded

    fun retarget(expanded: Boolean) {
        if (targetExpanded == expanded) return
        // AndroidMenu removes the content before Transition can process an early close.
        // Reopening a closing content, however, updates currentState to its previous target.
        if (!expanded && !currentExpanded) {
            targetExpanded = false
            return
        }
        currentExpanded = targetExpanded
        targetExpanded = expanded
        scaleChild.retarget(if (expanded) 1f else .8f, playTime)
        alphaChild.retarget(if (expanded) 1f else 0f, playTime)
    }

    fun frame(frameTimeNanos: Long, durationScale: Float) {
        if (!isRunning) return
        require(durationScale >= 0f)
        val origin = startTime ?: frameTimeNanos.also { startTime = it }
        val elapsed = frameTimeNanos - origin
        playTime = if (durationScale == 0f) elapsed else
            (elapsed / durationScale.toDouble()).roundToLong()
        scaleChild.frame(playTime, durationScale == 0f)
        alphaChild.frame(playTime, durationScale == 0f)
        if (!isRunning) {
            currentExpanded = targetExpanded
            startTime = null
            playTime = 0L
        }
    }
}
