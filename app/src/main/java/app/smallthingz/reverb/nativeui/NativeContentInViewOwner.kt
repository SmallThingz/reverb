/*
 * Copyright 2022 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 * Vertical, non-reversed adaptation of foundation 1.11.2 ContentInViewNode.
 */
package app.smallthingz.reverb.nativeui

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Concrete local responder. The View host performs real coordinate/ancestor mapping separately. */
internal class NativeContentInViewOwner(
    private val scroll: NativeVerticalScrollOwner,
    private val frames: MonotonicFrameClock,
    private val nodeScope: () -> CoroutineScope,
    private val focusedBounds: () -> Rect?,
    private val spec: BringIntoViewSpec,
) {
    private val requests = NativeBringIntoViewRequestQueue()
    private var viewportHeight: Int? = null
    private var trackingFocusedChild = false
    var isAnimationRunning = false
        private set

    fun calculateRectForParent(localRect: Rect): Rect {
        check(viewportHeight != null) { "Bring-into-view requires a measured parent" }
        return localRect.translate(Offset(0f, -distance(localRect)))
    }

    suspend fun bringChildIntoView(currentBounds: () -> Rect?) {
        if (currentBounds()?.let { isMaxVisible(it) } != false) return
        suspendCancellableCoroutine { continuation ->
            if (requests.enqueue(Request(currentBounds, continuation)) && !isAnimationRunning) {
                launchAnimation()
            }
        }
    }

    /** Source only tracks an already-visible focused child when a vertical viewport shrinks. */
    fun measured(height: Int) {
        val previous = viewportHeight ?: 0
        viewportHeight = height
        if (height >= previous) return
        val focused = focusedBounds() ?: return
        if (!isAnimationRunning && !trackingFocusedChild &&
            isMaxVisible(focused, previous) && !isMaxVisible(focused)
        ) {
            trackingFocusedChild = true
            launchAnimation()
        }
    }

    private fun launchAnimation() {
        check(!isAnimationRunning) { "A content-in-view animation already owns this host" }
        // The released node still reads this deprecated source property. Its shipped default is spring.
        @Suppress("DEPRECATION")
        val animation = NativeUpdatableScrollAnimation(spec.scrollAnimationSpec)
        nodeScope().launch(frames, start = CoroutineStart.UNDISPATCHED) {
            var cancellation: CancellationException? = null
            val animationJob = coroutineContext.job
            try {
                isAnimationRunning = true
                scroll.scrollWithoutOverscroll {
                    animation.value = calculateScrollDelta()
                    animation.animateToZero(
                        beforeFrame = { delta ->
                            val consumed = -scrollBy(-delta)
                            if (abs(consumed) < abs(delta)) {
                                animationJob.cancel(
                                    "Scroll animation cancelled because scroll was not consumed ($consumed < $delta)"
                                )
                            }
                        },
                        afterFrame = {
                            requests.resumeAndRemoveWhile { bounds -> bounds == null || isMaxVisible(bounds) }
                            if (trackingFocusedChild && focusedBounds()?.let { isMaxVisible(it) } == true) {
                                trackingFocusedChild = false
                            }
                            animation.value = calculateScrollDelta()
                        },
                    )
                }
                requests.resumeAndRemoveAll()
            } catch (failure: CancellationException) {
                cancellation = failure
                throw failure
            } finally {
                isAnimationRunning = false
                requests.cancelAndRemoveAll(cancellation)
                trackingFocusedChild = false
            }
        }
    }

    private fun calculateScrollDelta(): Float {
        val bounds = findRequest() ?: (if (trackingFocusedChild) focusedBounds() else null) ?: return 0f
        return distance(bounds)
    }

    /** Source chooses the largest nested request whose main-axis size fits the viewport. */
    private fun findRequest(): Rect? {
        var chosen: Rect? = null
        requests.forEachFromSmallest { bounds ->
            if (bounds != null) {
                if (bounds.height <= (viewportHeight ?: 0).toFloat()) chosen = bounds
                else return chosen ?: bounds
            }
        }
        return chosen
    }

    private fun distance(bounds: Rect, height: Int = viewportHeight ?: 0): Float =
        spec.calculateScrollDistance(bounds.top, bounds.height, height.toFloat())

    private fun isMaxVisible(bounds: Rect, height: Int = viewportHeight ?: 0): Boolean =
        abs(distance(bounds, height)) <= .5f

    internal class Request(
        val currentBounds: () -> Rect?,
        val continuation: CancellableContinuation<Unit>,
    )
}
