package app.smallthingz.reverb

import app.smallthingz.nativeui.runtime.MonotonicFrameClock
import app.smallthingz.nativeui.MotionDurationScale
import app.smallthingz.nativeui.unit.Density
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOnboardingPagerTest {
    @Test
    fun requestedFractionRoundsPhysicalOffsetBeforeNegationAtHalfPixels() = runBlocking {
        withPager { pager, _, _ ->
            for ((fraction, expectedOffset) in listOf(0.5f to 4L, -0.5f to 1L)) {
                pager.requestScrollToPage(1, fraction)
                pager.updateLayout(3)
                assertEquals(expectedOffset, pager.layoutAbsoluteOffsetPx)
                assertEquals(if (fraction > 0f) 1 else 0, pager.currentPage)
                assertEquals(1f / 3f, pager.currentPageOffsetFraction, 0f)

                pager.scrollToPage(1, fraction)
                assertEquals(expectedOffset, pager.layoutAbsoluteOffsetPx)
                assertEquals(1f / 3f, pager.currentPageOffsetFraction, 0f)
            }

            pager.updateLayout(1000)
            for (fraction in listOf(-0.5f, -0.25f, 0.25f, 0.5f)) {
                pager.scrollToPage(1, fraction)
                assertEquals(1000L + (fraction * 1000).toLong(), pager.layoutAbsoluteOffsetPx)
                assertEquals(if (fraction == -0.5f) 0 else 1, pager.currentPage)
                assertEquals(if (fraction == -0.5f) 0.5f else fraction,
                    pager.currentPageOffsetFraction, 0f)
            }
        }
    }

    @Test
    fun zeroWidthMeasureSelectsFinalPageFromEveryPageAndFractionThenRetainsItOnResize() = runBlocking {
        withPager { pager, _, _ ->
            for (page in 0..2) {
                for (fraction in listOf(-0.5f, -0.25f, 0f, 0.25f, 0.5f)) {
                    pager.updateLayout(1000)
                    pager.scrollToPage(page, fraction)
                    pager.updateLayout(0)
                    assertEquals(2, pager.currentPage)
                    assertEquals(2, pager.firstVisiblePage)
                    assertEquals(2, pager.settledPage)
                    assertEquals(0f, pager.currentPageOffsetFraction, 0f)
                    assertEquals(0L, pager.layoutAbsoluteOffsetPx)
                    assertTrue(pager.canScrollBackward)
                    assertFalse(pager.canScrollForward)

                    pager.updateLayout(777)
                    assertEquals(2, pager.currentPage)
                    assertEquals(2, pager.firstVisiblePage)
                    assertEquals(0f, pager.currentPageOffsetFraction, 0f)
                    assertEquals(1554L, pager.layoutAbsoluteOffsetPx)
                }
            }
        }
    }

    @Test
    fun initialZeroWidthIsUnmeasuredUntilLayoutAndPendingRequestsDoNotChangeVisibleState() = runBlocking {
        withPager(layout = false) { pager, _, _ ->
            assertEquals(0, pager.currentPage)
            assertEquals(0, pager.firstVisiblePage)
            assertFalse(pager.canScrollBackward)
            assertFalse(pager.canScrollForward)
            pager.requestScrollToPage(1, 0.25f)
            assertEquals(1, pager.currentPage)
            assertEquals(0, pager.firstVisiblePage)
            assertFalse(pager.canScrollBackward)

            pager.updateLayout(0)
            assertEquals(2, pager.currentPage)
            assertEquals(2, pager.firstVisiblePage)
            assertTrue(pager.canScrollBackward)
            assertFalse(pager.needsRemeasure)

            pager.requestScrollToPage(0, 0.5f)
            assertEquals(0, pager.currentPage)
            assertEquals(2, pager.firstVisiblePage)
            assertTrue(pager.canScrollBackward)
            pager.updateLayout(0)
            assertEquals(2, pager.currentPage)
            assertEquals(0f, pager.currentPageOffsetFraction, 0f)
            pager.scrollToPage(1, -0.25f)
            assertEquals(2, pager.currentPage)
            assertEquals(2, pager.firstVisiblePage)
        }
    }

    @Test
    fun scrollWaitsForFirstLayoutAndClampsRequestedPage() = runBlocking {
        withPager(layout = false) { pager, scope, _ ->
            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { pager.scrollToPage(20) }
            assertFalse(job.isCompleted)
            assertFalse(pager.isScrollInProgress)
            pager.updateLayout(1000)
            withTimeout(1000) { job.join() }
            assertEquals(2, pager.currentPage)
            assertEquals(2000L, pager.layoutAbsoluteOffsetPx)
            assertEquals(2, pager.settledPage)
        }
    }

    @Test
    fun currentTargetAndSettledPagesKeepDifferentMeaningsDuringDrag() = runBlocking {
        withPager { pager, scope, _ ->
            val release = CompletableDeferred<Unit>()
            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                pager.drag {
                    scrollBy(56f)
                    assertEquals(0, pager.currentPage)
                    assertEquals(1, pager.targetPage)
                    scrollBy(500f)
                    release.await()
                }
            }
            assertEquals(1, pager.currentPage)
            assertEquals(-0.444f, pager.currentPageOffsetFraction, 0.000001f)
            assertEquals(1, pager.targetPage)
            assertEquals(0, pager.settledPage)
            assertTrue(pager.isScrollInProgress)
            release.complete(Unit)
            withTimeout(1000) { job.join() }
            assertEquals(1, pager.settledPage)
            assertFalse(pager.isScrollInProgress)
        }
    }

    @Test
    fun fractionalDeltasAccumulateAndBoundariesReturnOnlyConsumedDistance() = runBlocking {
        withPager { pager, _, _ ->
            repeat(4) { assertEquals(0.25f, pager.dispatchRawDelta(0.25f), 0f) }
            assertEquals(1L, pager.layoutAbsoluteOffsetPx)
            assertTrue(pager.lastScrolledForward)
            assertEquals(-1f, pager.dispatchRawDelta(-50f), 0f)
            assertEquals(0L, pager.layoutAbsoluteOffsetPx)
            assertTrue(pager.lastScrolledBackward)
            assertEquals(0f, pager.dispatchRawDelta(-50f), 0f)
            assertFalse(pager.canScrollBackward)
            assertEquals(2000f, pager.dispatchRawDelta(2500f), 0f)
            assertFalse(pager.canScrollForward)
        }
    }

    @Test
    fun rtlChangesPhysicalMotionAndPlacementWithoutReversingLogicalPages() = runBlocking {
        withPager { pager, _, _ ->
            assertEquals(50f, pager.physicalToLogicalDelta(-50f), 0f)
            assertEquals(1000f, pager.pageTranslationX(1), 0f)
            pager.rtl = true
            assertEquals(50f, pager.physicalToLogicalDelta(50f), 0f)
            assertEquals(-1000f, pager.pageTranslationX(1), 0f)
            pager.scrollToPage(1, 0.25f)
            assertEquals(1, pager.currentPage)
            assertEquals(250f, pager.pageTranslationX(1), 0f)
        }
    }

    @Test
    fun aUserDragInterruptsButtonAnimationButDefaultNavigationCannotInterruptHeldDrag() = runBlocking {
        withPager { pager, scope, _ ->
            val animation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                pager.animateScrollToPage(2)
            }
            assertEquals(2, pager.targetPage)
            val dragStarted = CompletableDeferred<Unit>()
            val drag = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                pager.drag {
                    scrollBy(100f)
                    dragStarted.complete(Unit)
                    awaitCancellation()
                }
            }
            withTimeout(1000) { dragStarted.await() }
            assertTrue(animation.isCancelled)
            assertTrue(pager.isScrollInProgress)
            val rejected = scope.launch(start = CoroutineStart.UNDISPATCHED) { pager.scrollToPage(1) }
            withTimeout(1000) { rejected.join() }
            assertTrue(rejected.isCancelled)
            assertTrue(drag.isActive)
            drag.cancel()
            withTimeout(1000) { drag.join() }
            assertFalse(pager.isScrollInProgress)
        }
    }

    @Test
    fun equalPriorityReplacementCancelsOldTargetAndSettlesOnlyAtNewDestination() = runBlocking {
        withPager { pager, scope, frames ->
            val first = scope.launch(start = CoroutineStart.UNDISPATCHED) { pager.animateScrollToPage(2) }
            frames.send(0L)
            yield()
            frames.send(100_000_000L)
            yield()
            assertTrue(pager.layoutAbsoluteOffsetPx > 0L)
            val replacement = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                pager.animateScrollToPage(1)
            }
            withTimeout(1000) {
                while (pager.targetPage != 1) yield()
            }
            assertTrue(first.isCancelled)
            assertEquals(1, pager.targetPage)
            finishAnimation(replacement, frames, 200_000_000L)
            assertEquals(1, pager.currentPage)
            assertEquals(1, pager.settledPage)
            assertEquals(1000L, pager.layoutAbsoluteOffsetPx)
            assertEquals(0f, pager.currentPageOffsetFraction, 0f)
        }
    }

    @Test
    fun requestScrollChangesStateBeforeLayoutAndCancelsTheOldMutation() = runBlocking {
        withPager { pager, scope, _ ->
            val old = scope.launch(start = CoroutineStart.UNDISPATCHED) { pager.animateScrollToPage(2) }
            pager.requestScrollToPage(1)
            assertEquals(1, pager.currentPage)
            assertEquals(0L, pager.layoutAbsoluteOffsetPx)
            assertTrue(pager.needsRemeasure)
            withTimeout(1000) { old.join() }
            assertTrue(old.isCancelled)
            pager.updateLayout(1000)
            assertEquals(1000L, pager.layoutAbsoluteOffsetPx)
            assertFalse(pager.needsRemeasure)
        }
    }

    @Test
    fun sameCurrentPageEarlyReturnDoesNotCancelAnAlreadyAcceptedAnimation() = runBlocking {
        withPager { pager, scope, _ ->
            val old = scope.launch(start = CoroutineStart.UNDISPATCHED) { pager.animateScrollToPage(2) }
            pager.animateScrollToPage(0)
            assertTrue(old.isActive)
            assertEquals(2, pager.targetPage)
            old.cancel()
            withTimeout(1000) { old.join() }
        }
    }

    @Test
    fun callerScaleZeroSkipsButtonAnimationButSnapFlingStillUsesScaleOne() = runBlocking {
        withPager { pager, scope, frames ->
            val zeroScale = object : MotionDurationScale { override val scaleFactor = 0f }
            val button = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(zeroScale) { pager.animateScrollToPage(1) }
            }
            frames.send(0L)
            withTimeout(1000) { button.join() }
            assertEquals(1000L, pager.layoutAbsoluteOffsetPx)
            pager.scrollToPage(0)
            pager.dispatchRawDelta(560f)
            pager.beginPointerGesture()
            pager.endPointerGesture(-560f)
            var unconsumed = Float.NaN
            val fling = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(zeroScale) { unconsumed = pager.fling(0f) }
            }
            frames.send(100_000_000L)
            yield()
            assertTrue(fling.isActive)
            assertEquals(560L, pager.layoutAbsoluteOffsetPx)
            finishAnimation(fling, frames, 200_000_000L)
            if (pager.needsRemeasure) pager.updateLayout(1000)
            assertEquals(1000L, pager.layoutAbsoluteOffsetPx)
            assertEquals(0f, unconsumed, 0f)
        }
    }

    private suspend fun finishAnimation(job: Job, frames: Channel<Long>, startNanos: Long) {
        var frame = 0
        while (!job.isCompleted && frame < 240) {
            frames.send(startNanos + frame * 16_666_667L)
            frame++
            yield()
        }
        withTimeout(1000) { job.join() }
        assertFalse("Animation must finish without cancellation", job.isCancelled)
    }

    private suspend fun CoroutineScope.withPager(
        layout: Boolean = true,
        block: suspend (NativeOnboardingPager, CoroutineScope, Channel<Long>) -> Unit,
    ) {
        val frames = Channel<Long>(Channel.UNLIMITED)
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(frames.receive())
        }
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner + clock)
        val pager = NativeOnboardingPager(scope, Density(1f))
        if (layout) pager.updateLayout(1000)
        try {
            withTimeout(5000) { block(pager, scope, frames) }
        } finally {
            scope.cancel()
            frames.close()
        }
    }
}
