/*
 * Horizontal specialization of foundation 1.11.2 ContentInViewNode and PagerBringIntoViewSpec.
 * Copyright 2022-2025 The Android Open Source Project; Apache License 2.0.
 */
package app.smallthingz.reverb

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.smallthingz.reverb.nativeui.NativeBringIntoViewRequestQueue
import app.smallthingz.reverb.nativeui.NativeContentInViewOwner
import app.smallthingz.reverb.nativeui.NativeUpdatableScrollAnimation
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Exact Fill/Start/three-page source distance; zero-width hosts cannot reveal any child. */
internal fun nativePagerBringIntoViewDistance(
    proposed: Float, offset: Float, size: Float, container: Float,
    pageWidth: Int, firstOffset: Int, rtl: Boolean, lastForward: Boolean,
): Float {
    if (pageWidth == 0) return 0f
    val awareOffset = if (rtl) -firstOffset + pageWidth else firstOffset
    val outside = if (offset > 0f) offset + size > container else offset + size <= 1f
    if (abs(proposed) != 0f && outside) {
        var corrected = -awareOffset.toFloat()
        while (proposed > 0f && corrected < proposed) corrected += pageWidth
        while (proposed < 0f && corrected > proposed) corrected -= pageWidth
        return corrected
    }
    if (abs(firstOffset.toFloat()) < 1e-6f) return 0f
    val reversed = -awareOffset.toFloat()
    return (if (rtl) {
        if (lastForward) reversed else reversed + pageWidth
    } else {
        if (lastForward) reversed + pageWidth else reversed
    }).coerceIn(-container, container)
}

internal class NativePagerBringIntoViewSpec(
    private val pager: NativeOnboardingPager,
    private val default: BringIntoViewSpec,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        nativePagerBringIntoViewDistance(
            default.calculateScrollDistance(offset, size, containerSize),
            offset, size, containerSize, pager.pageWidthPx,
            (pager.layoutAbsoluteOffsetPx - pager.firstVisiblePage.toLong() * pager.pageWidthPx).toInt(),
            pager.rtl, pager.lastScrolledForward,
        )
}

/** Same request queue/updatable animation as vertical; horizontal/RTL source signs are explicit. */
internal class NativePagerContentInViewOwner(
    private val pager: NativeOnboardingPager,
    private val frames: MonotonicFrameClock,
    private val nodeScope: () -> CoroutineScope,
    private val focusedBounds: () -> Rect?,
    private val spec: BringIntoViewSpec,
) {
    private val requests = NativeBringIntoViewRequestQueue()
    private var viewportWidth: Int? = null
    private var trackingFocusedChild = false
    private var isAnimationRunning = false

    fun calculateRectForParent(localRect: Rect): Rect {
        check(viewportWidth != null)
        return localRect.translate(Offset(-distance(localRect), 0f))
    }

    suspend fun bringChildIntoView(bounds: () -> Rect?) {
        if (bounds()?.let { isMaxVisible(it) } != false) return
        suspendCancellableCoroutine { continuation ->
            if (requests.enqueue(NativeContentInViewOwner.Request(bounds, continuation)) && !isAnimationRunning)
                launchAnimation()
        }
    }

    fun measured(width: Int) {
        val previous = viewportWidth ?: 0
        viewportWidth = width
        if (width >= previous) return
        val focused = focusedBounds() ?: return
        // Horizontal RTL has reverseDirection=false; source adjusts pre-layout child coordinates.
        val adjustment = if (pager.rtl) previous - width else 0
        if (!isAnimationRunning && !trackingFocusedChild &&
            isMaxVisible(focused, previous) && !isMaxVisible(focused, adjustment = adjustment)) {
            trackingFocusedChild = true
            launchAnimation(adjustment)
        }
    }

    private fun launchAnimation(viewportAdjustment: Int = 0) {
        check(!isAnimationRunning)
        @Suppress("DEPRECATION")
        val animation = NativeUpdatableScrollAnimation(spec.scrollAnimationSpec)
        nodeScope().launch(frames, start = CoroutineStart.UNDISPATCHED) {
            var cancellation: CancellationException? = null
            val animationJob = coroutineContext.job
            try {
                isAnimationRunning = true
                // Released focus scrolling owns Default mutation, never pulls overscroll.
                pager.scroll {
                    animation.value = calculateScrollDelta(viewportAdjustment)
                    animation.animateToZero(
                        beforeFrame = { delta ->
                            val consumed = if (pager.rtl) -scrollBy(-delta) else scrollBy(delta)
                            if (abs(consumed) < abs(delta)) animationJob.cancel(
                                "Scroll animation cancelled because scroll was not consumed ($consumed < $delta)")
                        },
                        afterFrame = {
                            requests.resumeAndRemoveWhile { it == null || isMaxVisible(it) }
                            if (trackingFocusedChild && focusedBounds()?.let { isMaxVisible(it) } == true)
                                trackingFocusedChild = false
                            animation.value = calculateScrollDelta(viewportAdjustment)
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

    private fun calculateScrollDelta(adjustment: Int): Float {
        val bounds = findRequest() ?: (if (trackingFocusedChild) focusedBounds() else null) ?: return 0f
        return distance(bounds, adjustment = adjustment)
    }

    private fun findRequest(): Rect? {
        var chosen: Rect? = null
        requests.forEachFromSmallest { bounds ->
            if (bounds != null) {
                if (bounds.width <= (viewportWidth ?: 0)) chosen = bounds
                else return chosen ?: bounds
            }
        }
        return chosen
    }

    private fun distance(bounds: Rect, width: Int = viewportWidth ?: 0, adjustment: Int = 0) =
        spec.calculateScrollDistance(bounds.left - adjustment, bounds.width, width.toFloat())
    private fun isMaxVisible(bounds: Rect, width: Int = viewportWidth ?: 0, adjustment: Int = 0) =
        abs(distance(bounds, width, adjustment)) <= .5f
}
