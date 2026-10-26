/*
 * Copyright 2020 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 * Source-derived from foundation 1.11.2 ScrollableState.kt / Scrollable.kt.
 */
package app.smallthingz.reverb.nativeui

import smallthingz.ui.animation.core.DecayAnimationSpec
import smallthingz.ui.animation.core.TargetBasedAnimation
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.spring
import smallthingz.ui.foundation.MutatePriority
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.MotionDurationScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** The existing ancestor chain owns these real suspendable continuations. Units are physical Y. */
internal interface NativeVerticalFlingHost {
    suspend fun preFling(availableY: Float): Float
    suspend fun postFling(consumedY: Float, availableY: Float): Float
    fun isAttached(): Boolean
}

internal fun interface NativeVerticalDragScope {
    /** Physical finger delta: down is positive. Legal only inside the admitted drag mutation. */
    fun scrollBy(physicalDeltaY: Float): Float
}

/**
 * One owner per actual scroll host, with mutation state shared per retained page.
 *
 * No private CoroutineScope, frame scheduler or gesture detector is created. The actual node scope
 * owns drag/settling jobs and cancels them at its source-equivalent disposal boundary. A completed
 * or cancelled pointer drag starts onScrollStopped outside the completed UserInput mutation; pointer
 * cancellation supplies zero velocity. Disabling new input alone does not cancel existing jobs.
 *
 * The supplied frame clock must execute its block in the real animation frame phase. Returning a
 * timestamp from an arbitrary posted callback and resuming later is not an equivalent substitute.
 */
internal class NativeVerticalScrollOwner(
    private val engine: NativeVerticalScrollEngine,
    private val decay: DecayAnimationSpec<Float>,
    private val frames: MonotonicFrameClock,
    private val flingHost: NativeVerticalFlingHost,
    private val overscroll: NativeVerticalOverscroll? = null,
) {
    val state: NativeVerticalScrollState get() = engine.state

    var isFlinging = false
        private set

    val shouldScrollImmediately: Boolean
        get() = state.isScrollInProgress || overscroll?.isInProgress == true

    private val dragScope = NativeVerticalDragScope { physicalDeltaY ->
        scrollWithOverscroll(physicalDeltaY, NativeVerticalScrollSource.USER_INPUT)
    }
    private val plainScrollScope = NativeVerticalDragScope { physicalDeltaY ->
        engine.dispatch(physicalDeltaY, NativeVerticalScrollSource.USER_INPUT)
    }

    /** The shared input channel invokes this once per admitted drag, not once per delta. */
    suspend fun drag(block: suspend NativeVerticalDragScope.() -> Unit) {
        state.mutate(MutatePriority.UserInput) { dragScope.block() }
    }

    /** Source semantics/focus path uses nested scroll but deliberately creates no edge pull. */
    suspend fun scrollWithoutOverscroll(block: suspend NativeVerticalDragScope.() -> Unit) {
        state.mutate(MutatePriority.Default) { plainScrollScope.block() }
    }

    /** Scrollable.semanticsScrollBy: default spring, logical deltas, consumed-distance accumulator. */
    suspend fun semanticsScrollBy(logicalDelta: Float): Float {
        var consumedTotal = 0f
        scrollWithoutOverscroll {
            val animation = TargetBasedAnimation(spring<Float>(), Float.VectorConverter, 0f, logicalDelta)
            var firstFrame: Long? = null
            var finished = false
            while (!finished) {
                // Source reads scale before requesting each frame, rather than forcing fling's 1f.
                val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
                check(scale >= 0f) { "negative scale factor" }
                frames.withFrameNanos { frame ->
                    val start = firstFrame ?: frame.also { firstFrame = it }
                    val playTime = if (scale == 0f) animation.durationNanos
                    else ((frame - start) / scale).toLong()
                    val current = animation.getValueFromNanos(playTime)
                    val delta = current - consumedTotal
                    consumedTotal += -scrollBy(-delta)
                    finished = animation.isFinishedFromNanos(playTime)
                }
            }
        }
        return consumedTotal
    }

    /**
     * Call only for PageUp/PageDown. Source captures the viewport at the key event, returns true
     * immediately, and launches a separate UserInput mutation for each event. No overscroll wrapper.
     */
    fun pageKey(
        pageDown: Boolean,
        keyDown: Boolean,
        ctrlPressed: Boolean,
        enabled: Boolean,
        nodeScope: CoroutineScope,
    ): Boolean {
        if (!enabled || !keyDown || ctrlPressed) return false
        val amount = if (pageDown) -state.viewportSize.toFloat() else state.viewportSize.toFloat()
        nodeScope.launch {
            state.mutate(MutatePriority.UserInput) {
                engine.dispatch(amount, NativeVerticalScrollSource.USER_INPUT)
            }
        }
        return true
    }

    /**
     * Released ScrollableNestedScrollConnection's local post-scroll contribution. Raw dispatch
     * deliberately does not acquire a mutation or update ordinary last-scroll direction flags.
     */
    fun onNestedPostScroll(availableY: Float, enabled: Boolean): Float =
        if (enabled && !state.isScrollInProgress) engine.dispatchRaw(availableY) else 0f

    /**
     * The direct drag terminal. Pre-fling happens before the Default mutation, post-fling after it.
     * There is intentionally no finally that invents post-fling or edge release after cancellation.
     */
    suspend fun onScrollStopped(physicalVelocityY: Float) {
        val edge = overscroll
        val start = if (edge != null && (state.canScrollForward || state.canScrollBackward)) {
            edge.beginFling(physicalVelocityY)
        } else null
        val available = start?.remainingVelocity ?: physicalVelocityY
        val leftover = performNestedFling(available)
        if (start != null) edge!!.finishFling(start, leftover)
    }

    private suspend fun performNestedFling(physicalVelocityY: Float): Float {
        val consumedPre = flingHost.preFling(physicalVelocityY)
        val available = physicalVelocityY - consumedPre
        val leftover = doFlingAnimation(available)
        val consumedPost = flingHost.postFling(available - leftover, leftover)
        return leftover - consumedPost
    }

    /**
     * Source's physical-axis fling and mutation boundary. A competing higher/equal priority
     * mutation cancels this coroutine; its withContext/coroutineScope boundaries still propagate
     * that cancellation even though the inner default fling catches CancellationException.
     */
    suspend fun doFlingAnimation(availablePhysicalY: Float): Float {
        var result = availablePhysicalY
        isFlinging = true
        try {
            state.mutate(MutatePriority.Default) {
                result = -performDefaultFling(-availablePhysicalY)
            }
        } finally {
            isFlinging = false
        }
        return result
    }

    private suspend fun performDefaultFling(logicalVelocity: Float): Float =
        withContext(DefaultNativeScrollMotionDurationScale) {
            var leftover = logicalVelocity
            val fling = NativeVerticalFling(
                decay = decay,
                consume = { logicalDelta ->
                    // This source-local cancellation does not cancel the coroutine Job. Its
                    // current velocity therefore reaches nested post-fling if the node scope lives.
                    if (kotlin.math.abs(logicalDelta) != 0f && !flingHost.isAttached()) {
                        throw NativeDetachedScrollFlingCancellation()
                    }
                    -scrollWithOverscroll(-logicalDelta, NativeVerticalScrollSource.SIDE_EFFECT)
                },
                finished = { leftover = it },
            )
            fling.start(logicalVelocity)
            try {
                while (fling.isRunning) {
                    frames.withFrameNanos { fling.frame(it) }
                }
            } catch (_: CancellationException) {
                leftover = fling.remainingVelocity
            } finally {
                // Retires only this invocation's sampler. This never runs nested callbacks or
                // releases an edge effect while the source coroutine may have been cancelled.
                fling.cancel()
            }
            leftover
        }

    private fun scrollWithOverscroll(physicalY: Float, source: NativeVerticalScrollSource): Float {
        val edge = overscroll
        return if (edge != null) engine.dispatchWithOverscroll(physicalY, source, edge)
        else engine.dispatch(physicalY, source)
    }
}

private object DefaultNativeScrollMotionDurationScale : MotionDurationScale {
    override val scaleFactor: Float get() = 1f
}

private class NativeDetachedScrollFlingCancellation : CancellationException(
    "The scroll node detached during a nonzero fling sample"
)
