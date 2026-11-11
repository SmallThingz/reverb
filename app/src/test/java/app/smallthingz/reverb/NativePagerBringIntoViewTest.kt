package app.smallthingz.reverb

import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.unit.Density
import smallthingz.ui.geometry.Rect
import smallthingz.ui.foundation.gestures.BringIntoViewSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class NativePagerBringIntoViewTest {
    private fun distance(proposed: Float, offset: Float, size: Float,
        first: Int = 100, rtl: Boolean = false, forward: Boolean = true) =
        nativePagerBringIntoViewDistance(proposed, offset, size, 400f, 400, first, rtl, forward)

    @Test fun overflowingRequestMovesAnIntegralPageFromCurrentPhysicalOffset() {
        assertEquals(300f, distance(250f, 450f, 50f), 0f)
        assertEquals(-100f, distance(-50f, -70f, 40f), 0f)
    }

    @Test fun rtlUsesTheOppositePhysicalFirstPageOrigin() {
        assertEquals(100f, distance(100f, 450f, 50f, rtl = true), 0f)
        assertEquals(-300f, distance(-50f, -70f, 40f, rtl = true), 0f)
    }

    @Test fun fulfilledRequestStillSnapsInTheLastLogicalDirection() {
        assertEquals(300f, distance(0f, 100f, 30f, forward = true), 0f)
        assertEquals(-100f, distance(0f, 100f, 30f, forward = false), 0f)
        assertEquals(-300f, distance(0f, 100f, 30f, rtl = true, forward = true), 0f)
        assertEquals(100f, distance(0f, 100f, 30f, rtl = true, forward = false), 0f)
    }

    @Test fun settledFullyVisibleRequestDoesNotMove() {
        assertEquals(0f, distance(0f, 100f, 30f, first = 0), 0f)
        assertEquals(0f, distance(0f, 100f, 30f, first = 0, rtl = true), 0f)
    }

    @Test fun visibilityThresholdIsThePinnedInclusiveOnePixel() {
        assertEquals(300f, distance(20f, -2f, 3f, forward = false), 0f)
        assertEquals(-100f, distance(20f, -2f, 3.001f, forward = false), 0f)
    }

    @Test fun zeroWidthCannotStartAnUnfulfillableReveal() {
        assertEquals(0f, nativePagerBringIntoViewDistance(20f, 10f, 30f, 0f, 0, 0, false, true), 0f)
    }
    @Test(timeout = 5_000) fun revokedFocusCannotConsumePendingFrameInEitherDirection() = runBlocking {
        for (rtl in listOf(false, true)) withOwner(rtl) { f ->
            var live = true
            val request = launch(start = CoroutineStart.UNDISPATCHED) {
                f.owner.bringChildIntoView { if (live) f.bounds(140f, 170f) else null }
            }
            live = false
            f.advance { request.isCompleted && !f.pager.isScrollInProgress }
            assertFalse(request.isCancelled); assertEquals(0L, f.pager.layoutAbsoluteOffsetPx)
        }
    }

    @Test(timeout = 5_000) fun reachableReplacementSurvivesStaleBoundaryDeltaInEitherDirection() = runBlocking {
        for (rtl in listOf(false, true)) withOwner(rtl) { f ->
            var live = true
            val old = launch(start = CoroutineStart.UNDISPATCHED) {
                f.owner.bringChildIntoView { if (live) f.bounds(340f, 370f) else null }
            }
            live = false
            val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
                f.owner.bringChildIntoView { f.bounds(240f, 270f) }
            }
            f.advance { replacement.isCompleted && !f.pager.isScrollInProgress }
            old.join(); assertFalse(replacement.isCancelled)
            assertEquals(200L, f.pager.layoutAbsoluteOffsetPx)
        }
    }

    @Test(timeout = 5_000) fun finalTailRetargetCompletesVisibleInEitherDirection() = runBlocking {
        for (rtl in listOf(false, true)) withOwner(rtl) { f ->
            var generation = 0
            var last: Job? = null
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                f.owner.bringChildIntoView { if (generation == 0) f.bounds(140f, 170f) else null }
            }
            f.beforeDelivery = { frame ->
                if (frame == 0) {
                    generation = 1
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        f.owner.bringChildIntoView { if (generation == 1) f.bounds(240f, 270f) else null }
                    }
                } else if (frame == 1) {
                    assertEquals(100L, f.pager.layoutAbsoluteOffsetPx)
                    generation = 2
                    last = launch(start = CoroutineStart.UNDISPATCHED) {
                        f.owner.bringChildIntoView { f.bounds(10f, 40f) }
                    }
                }
            }
            f.advance { last?.isCompleted == true && !f.pager.isScrollInProgress }
            first.join(); assertFalse(requireNotNull(last).isCancelled)
            assertEquals(0L, f.pager.layoutAbsoluteOffsetPx)
        }
    }

    private suspend fun CoroutineScope.withOwner(rtl: Boolean, block: suspend (OwnerFixture) -> Unit) {
        val fixture = OwnerFixture(this, rtl)
        try { block(fixture) } finally { fixture.scope.cancel() }
    }

    private class OwnerFixture(parent: CoroutineScope, rtl: Boolean) : MonotonicFrameClock {
        private val requested = Channel<Unit>(Channel.UNLIMITED)
        private val times = Channel<Long>(Channel.UNLIMITED)
        private var delivered = 0
        var beforeDelivery: (Int) -> Unit = {}
        val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + this +
            object : MotionDurationScale { override val scaleFactor = 0f })
        val pager = NativeOnboardingPager(scope, Density(1f), rtl).also { it.updateLayout(100) }
        val owner = NativePagerContentInViewOwner(pager, this, { scope }, { null },
            NativePagerBringIntoViewSpec(pager, object : BringIntoViewSpec {})).also { it.measured(100) }
        fun bounds(start: Float, end: Float): Rect {
            val offset = pager.layoutAbsoluteOffsetPx.toFloat()
            return if (pager.rtl) Rect(100f - end + offset, 0f, 100f - start + offset, 30f)
            else Rect(start - offset, 0f, end - offset, 30f)
        }
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            requested.send(Unit)
            val time = times.receive(); delivered++
            return onFrame(time)
        }
        suspend fun advance(done: () -> Boolean) = withTimeout(2_000) {
            repeat(180) {
                yield(); if (done()) return@withTimeout
                requested.receive(); beforeDelivery(delivered)
                times.send(delivered * 16_666_667L); yield()
            }
            error("Pager reveal did not converge within 180 frames")
        }
    }
}
