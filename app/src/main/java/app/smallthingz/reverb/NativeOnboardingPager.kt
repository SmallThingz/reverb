/*
 * State/scroll subset adapted from AndroidX foundation 1.11.2 PagerState,
 * PagerMeasureResult, PagerWrapperFlingBehavior and SnapFlingBehavior.
 * Copyright 2022-2024 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb

import androidx.annotation.MainThread
import smallthingz.ui.animation.core.AnimationSpec
import smallthingz.ui.animation.core.AnimationState
import smallthingz.ui.animation.core.Spring
import smallthingz.ui.animation.core.animate
import smallthingz.ui.animation.core.animateTo
import smallthingz.ui.animation.core.spring
import smallthingz.ui.foundation.MutatePriority
import smallthingz.ui.foundation.MutatorMutex
import smallthingz.ui.foundation.gestures.ScrollScope
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.unit.Density
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Three Fill pages, Start snap, zero padding/spacing and reverseLayout=false.
 * Ordinary state plus pinned low-level animation/mutation primitives; no Composition or renderer.
 *
 * The future host supplies a main-thread MonotonicFrameClock and owns input dispatch, touch slop,
 * velocity tracking, nested scroll, overscroll, layout, accessibility and physical frame phase.
 * These host behaviors are not qualified by this engine.
 */
@MainThread
internal class NativeOnboardingPager(
    private val ownerScope: CoroutineScope,
    private var density: Density,
    rtl: Boolean = false,
    private val onChanged: () -> Unit = {},
) {
    var rtl: Boolean = rtl
        set(value) {
            if (field == value) return
            field = value
            onChanged()
        }

    val pageCount: Int get() = NATIVE_ONBOARDING_PAGE_COUNT
    var pageWidthPx: Int = 0
        private set
    var currentPage: Int = 0
        private set
    var currentPageOffsetFraction: Float = 0f
        private set
    var isScrollInProgress: Boolean = false
        private set
    var lastScrolledForward: Boolean = false
        private set
    var lastScrolledBackward: Boolean = false
        private set
    var needsRemeasure: Boolean = false
        private set
    var layoutAbsoluteOffsetPx: Long = 0L
        private set

    private var settledPageState = 0
    private var programmaticTarget = -1
    private var accumulator = 0f
    private var downUpX = 0f
    private var downUpY = 0f
    private val firstLayout = CompletableDeferred<Unit>()
    private val scrollMutex = MutatorMutex()
    private val scrollScope = object : ScrollScope {
        override fun scrollBy(pixels: Float): Float =
            if (pixels.isNaN()) 0f else performScroll(pixels)
    }

    val settledPage: Int get() = if (isScrollInProgress) settledPageState else currentPage
    var firstVisiblePage: Int = 0
        private set
    val canScrollForward: Boolean get() = layoutAbsoluteOffsetPx < maximumOffset()
    val canScrollBackward: Boolean get() = firstVisiblePage != 0 || layoutAbsoluteOffsetPx > 0L
    val targetPage: Int
        get() {
            val page = if (!isScrollInProgress) currentPage else if (programmaticTarget != -1) {
                programmaticTarget
            } else if (abs(currentPageOffsetFraction) >= abs(
                    nativePagerPositionThresholdFraction(pageWidthPx, density.density),
                )) {
                if (lastScrolledForward) firstVisiblePage + 1 else firstVisiblePage
            } else currentPage
            return page.coerceIn(0, pageCount - 1)
        }

    /** Called by the actual host measure, including after requestScrollToPage. */
    fun updateLayout(widthPx: Int, currentDensity: Density = density) {
        require(widthPx >= 0)
        density = currentDensity
        pageWidthPx = widthPx
        measureFromAbsolute(requestedMeasureOffset().coerceIn(0L, maximumOffset()))
        needsRemeasure = false
        firstLayout.complete(Unit)
        onChanged()
    }

    /** Physical page translation for the future Fill-page host; logical indexes do not reverse. */
    fun pageTranslationX(page: Int): Float =
        ((page.toLong() * pageWidthPx - layoutAbsoluteOffsetPx).toFloat()) * if (rtl) -1f else 1f

    fun physicalToLogicalDelta(deltaX: Float): Float = if (rtl) deltaX else -deltaX
    fun beginPointerGesture() {
        downUpX = 0f
        downUpY = 0f
    }

    /** Full down→up displacement, including slop, as source dragDirectionDetector supplies. */
    fun endPointerGesture(displacementX: Float, displacementY: Float = 0f) {
        downUpX = displacementX
        downUpY = displacementY
    }

    suspend fun scroll(
        priority: MutatePriority = MutatePriority.Default,
        block: suspend ScrollScope.() -> Unit,
    ) {
        firstLayout.await()
        if (!isScrollInProgress) settledPageState = currentPage
        scrollMutex.mutateWith(scrollScope, priority) {
            isScrollInProgress = true
            onChanged()
            try {
                block()
            } finally {
                isScrollInProgress = false
                onChanged()
            }
        }
        // Deliberately not finally: this is PagerState.scroll's released cancellation behavior.
        programmaticTarget = -1
    }

    suspend fun drag(block: suspend ScrollScope.() -> Unit) =
        scroll(MutatePriority.UserInput, block)

    fun dispatchRawDelta(logicalDeltaPx: Float): Float = performScroll(logicalDeltaPx)

    suspend fun scrollToPage(page: Int, pageOffsetFraction: Float = 0f) = scroll {
        require(pageOffsetFraction in -0.5f..0.5f)
        snapToPage(page.coerceIn(0, pageCount - 1), pageOffsetFraction, remeasureNow = true)
    }

    /**
     * State changes now; pixel placement changes on the next host measure. Source cancels via
     * its layout scope, so this intentionally schedules Default-priority cancellation likewise.
     */
    fun requestScrollToPage(page: Int, pageOffsetFraction: Float = 0f) {
        if (isScrollInProgress) ownerScope.launch { scroll {} }
        snapToPage(page, pageOffsetFraction, remeasureNow = false)
    }

    suspend fun animateScrollToPage(
        page: Int,
        pageOffsetFraction: Float = 0f,
        animationSpec: AnimationSpec<Float> = spring(),
    ) {
        if (page == currentPage && currentPageOffsetFraction == pageOffsetFraction) return
        firstLayout.await()
        require(pageOffsetFraction in -0.5f..0.5f)
        val target = page.coerceIn(0, pageCount - 1)
        val targetOffset = pageOffsetFraction * pageWidthPx
        scroll {
            updateTargetPage(target)
            // The released >=3-page pre-jump can never run in this fixed three-page pager.
            val distance = ((target - currentPage) * pageWidthPx -
                currentPageOffsetFraction * pageWidthPx).roundToInt()
            val absolute = currentAbsoluteOffset()
            val boundedDistance = (absolute + distance).coerceIn(0L, maximumOffset()) - absolute
            val displacement = boundedDistance.toFloat() + targetOffset
            var consumed = 0f
            animate(0f, displacement, animationSpec = animationSpec) { value, _ ->
                consumed += scrollBy(value - consumed)
            }
        }
    }

    /** Called after the drag mutation ends. Returns unconsumed PHYSICAL velocity. */
    suspend fun fling(
        physicalVelocityX: Float,
        deliverLogicalDelta: ScrollScope.(Float) -> Float = { scrollBy(it) },
    ): Float {
        val velocity = physicalToLogicalDelta(physicalVelocityX)
        var remainingVelocity = 0f
        scroll {
            remainingVelocity = withContext(PinnedPagerFlingDurationScale) {
                // atMost(1) makes abs(proposedOffset)-pageWidth <=0 for every decay target.
                // Therefore no decay/approach animation runs; source starts the snap at velocity.
                updateFlingTarget(0f)
                val distance = snapDistance(velocity)
                var remaining = distance
                updateFlingTarget(remaining)
                var consumed = 0f
                val animation = AnimationState(initialValue = 0f, initialVelocity = velocity)
                animation.animateTo(
                    distance,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 1f),
                    sequentialAnimation = velocity != 0f,
                ) {
                    val realValue = nativePagerCoerceAnimationValue(value, distance)
                    val delta = realValue - consumed
                    var used = 0f
                    try {
                        used = deliverLogicalDelta(delta)
                    } catch (_: CancellationException) {
                        cancelAnimation()
                    }
                    remaining -= used
                    updateFlingTarget(remaining)
                    if (abs(delta - used) > 0.5f || realValue != value) cancelAnimation()
                    consumed += used
                }
                val correctedVelocity = nativePagerCoerceAnimationValue(animation.velocity, velocity)
                if (distance - consumed == 0f) 0f else correctedVelocity
            }
            updateFlingTarget(0f)
            if (currentPageOffsetFraction != 0f && abs(currentPageOffsetFraction) < 1e-3f) {
                requestScrollToPage(currentPage)
            }
        }
        return physicalToLogicalDelta(remainingVelocity)
    }

    internal fun snapDistance(logicalVelocity: Float): Float {
        val forward = nativePagerScrollingForward(logicalVelocity, downUpX, downUpY)
        return nativePagerFinalSnapDistance(
            nativePagerSnapBounds(
                layoutAbsoluteOffsetPx, pageWidthPx, canScrollForward, canScrollBackward, forward,
            ),
            logicalVelocity, downUpX, downUpY, pageWidthPx, density.density, rtl,
        )
    }

    private fun updateFlingTarget(remaining: Float) {
        val displacement = if (pageWidthPx == 0) 0f else remaining / pageWidthPx
        updateTargetPage(displacement.roundToInt() + currentPage)
    }

    private fun updateTargetPage(page: Int) {
        programmaticTarget = page.coerceIn(0, pageCount - 1)
        onChanged()
    }

    private fun snapToPage(page: Int, fraction: Float, remeasureNow: Boolean) {
        currentPage = page
        currentPageOffsetFraction = fraction
        if (remeasureNow) {
            measureFromAbsolute(requestedMeasureOffset().coerceIn(0L, maximumOffset()))
            needsRemeasure = false
        } else needsRemeasure = true
        onChanged()
    }

    private fun maximumOffset(): Long = (pageCount - 1).toLong() * pageWidthPx
    // PagerMeasurePolicy rounds the physical Start-snap offset before reversing its sign.
    // Rounding the positive logical fraction instead changes exact half-pixel requests.
    private fun requestedMeasureOffset(): Long =
        currentPage.toLong() * pageWidthPx - (-currentPageOffsetFraction * pageWidthPx).roundToInt()

    private fun currentAbsoluteOffset(): Long =
        currentPage.toLong() * pageWidthPx + (currentPageOffsetFraction * pageWidthPx).roundToLong()

    private fun performScroll(delta: Float): Float {
        val oldAbsolute = currentAbsoluteOffset()
        val decimal = delta + accumulator
        val rounded = decimal.roundToLong()
        accumulator = decimal - rounded
        if (abs(delta) < 1e-4f) return delta
        val requested = oldAbsolute + rounded
        val coerced = requested.coerceIn(0L, maximumOffset())
        val applied = coerced - oldAbsolute
        if (applied != 0L) {
            lastScrolledForward = applied > 0L
            lastScrolledBackward = applied < 0L
        }
        if (canApplyWithoutRemeasure(applied.toInt())) {
            layoutAbsoluteOffsetPx += applied
            currentPageOffsetFraction += applied.toInt() / pageWidthPx.toFloat()
        } else measureFromAbsolute(coerced)
        onChanged()
        return if (requested != coerced) applied.toFloat() else delta
    }

    /** Exact Fill/zero-padding specialization of copyWithScrollDeltaWithoutRemeasure. */
    private fun canApplyWithoutRemeasure(scrollDelta: Int): Boolean {
        if (pageWidthPx == 0 || needsRemeasure) return false
        val firstOffset = (layoutAbsoluteOffsetPx % pageWidthPx).toInt()
        if (firstOffset + scrollDelta !in 0 until pageWidthPx) return false
        val fraction = currentPageOffsetFraction + scrollDelta / pageWidthPx.toFloat()
        if (fraction >= 0.5f || fraction <= -0.5f) return false
        val firstPosition = -firstOffset
        val lastPosition = if (firstOffset == 0 || firstVisiblePage == pageCount - 1) firstPosition
            else pageWidthPx - firstOffset
        return if (scrollDelta > 0) {
            minOf(firstPosition + pageWidthPx, lastPosition) > scrollDelta
        } else {
            minOf(-firstPosition, pageWidthPx - lastPosition) > -scrollDelta
        }
    }

    private fun measureFromAbsolute(offset: Long) {
        layoutAbsoluteOffsetPx = offset
        currentPage = nativePagerCurrentPage(offset, pageWidthPx)
        firstVisiblePage = if (pageWidthPx == 0) currentPage else (offset / pageWidthPx).toInt()
        currentPageOffsetFraction = if (pageWidthPx == 0) 0f else
            ((offset - currentPage.toLong() * pageWidthPx) / pageWidthPx.toFloat()).coerceIn(-0.5f, 0.5f)
    }
}

/** Pinned SnapFlingBehavior overrides caller animation scale to 1, unlike animateScrollToPage. */
private object PinnedPagerFlingDurationScale : MotionDurationScale {
    override val scaleFactor: Float = 1f
}
