package app.smallthingz.reverb.nativeui

import smallthingz.ui.animation.core.exponentialDecay
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proposal tests only: no compilation, execution or actual frame-phase qualification credited. */
class NativeVerticalScrollOwnerTest {
    @Test fun heldUserMutationRejectsDefaultFlingWithoutFinishingTheActiveDrag() = boundedTest {
        val fixture = Fixture()
        val drag = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.owner.drag { awaitCancellation() }
        }
        assertTrue(fixture.state.isScrollInProgress)
        assertTrue(fixture.owner.shouldScrollImmediately)
        val fling = launch(start = CoroutineStart.UNDISPATCHED) { fixture.owner.onScrollStopped(400f) }
        fling.join()
        assertTrue(fling.isCancelled)
        assertEquals(listOf(400f), fixture.preVelocities)
        assertTrue(fixture.postVelocities.isEmpty())
        assertFalse(fixture.owner.isFlinging)
        assertTrue(fixture.state.isScrollInProgress)
        drag.cancelAndJoin()
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test fun equalPriorityHostsOfTheSameRetainedPageRetireTheOldMutation() = boundedTest {
        val first = Fixture()
        val second = Fixture(first.state)
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            first.owner.drag { awaitCancellation() }
        }
        second.owner.drag { scrollBy(-12f) }
        old.join()
        assertTrue(old.isCancelled)
        assertEquals(12, first.state.value)
        assertFalse(first.state.isScrollInProgress)
        assertFalse(second.owner.shouldScrollImmediately)
    }

    @Test fun userDragCancelsAnActualFlingJobWithoutInventingPostFling() = boundedTest {
        val fixture = Fixture()
        val fling = launch(start = CoroutineStart.UNDISPATCHED) { fixture.owner.onScrollStopped(-1000f) }
        fixture.frames.frame(0L)
        fixture.frames.frame(16_666_667L)
        assertTrue(fixture.owner.isFlinging)
        assertTrue(fixture.state.isScrollInProgress)
        fixture.owner.drag { scrollBy(-4f) }
        fling.join()
        assertTrue(fling.isCancelled)
        assertTrue(fixture.postVelocities.isEmpty())
        assertFalse(fixture.owner.isFlinging)
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test fun suspendedAncestorPreFlingIsCancelledBeforeLocalMutation() = boundedTest {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        fixture.pre = { entered.complete(Unit); awaitCancellation() }
        val fling = launch(start = CoroutineStart.UNDISPATCHED) { fixture.owner.onScrollStopped(500f) }
        entered.await()
        assertFalse(fixture.state.isScrollInProgress)
        assertFalse(fixture.owner.isFlinging)
        fling.cancelAndJoin()
        assertTrue(fixture.postVelocities.isEmpty())
        assertEquals(0, fixture.frames.delivered)
    }

    @Test fun partialNestedConsumptionReceivesCurrentVelocityAndCorrectPhysicalSign() = boundedTest {
        val fixture = Fixture()
        fixture.pre = { 200f }
        fixture.post = { _, available -> available / 2f }
        // At offset zero, a downward physical fling cannot consume its local negative offset.
        val fling = launch(start = CoroutineStart.UNDISPATCHED) { fixture.owner.onScrollStopped(1000f) }
        fixture.frames.frame(1_000_000_000L)
        fixture.frames.frame(1_016_666_667L)
        fling.join()
        assertFalse(fling.isCancelled)
        assertEquals(listOf(1000f), fixture.preVelocities)
        val (consumed, left) = fixture.postVelocities.single()
        assertEquals(800f, consumed + left, .0001f)
        assertTrue(left > 0f && left < 800f)
        assertTrue(consumed > 0f)
        assertEquals(0, fixture.state.value)
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test fun detachedNodeLocalCancellationStillHandsVelocityToParent() = boundedTest {
        val fixture = Fixture()
        fixture.attached = false
        val fling = launch(start = CoroutineStart.UNDISPATCHED) { fixture.owner.onScrollStopped(-1000f) }
        fixture.frames.frame(0L) // Source allows the zero sample even after node detachment.
        fixture.frames.frame(16_666_667L)
        fling.join()
        assertFalse(fling.isCancelled) // The local exception did not cancel the Job.
        val (consumed, left) = fixture.postVelocities.single()
        assertEquals(-1000f, consumed + left, .0001f)
        assertTrue(left < 0f && left > -1000f)
        assertEquals(0, fixture.state.value)
        assertFalse(fixture.owner.isFlinging)
    }

    @Test fun zeroVelocityTerminalStillRunsBothNestedCallbacksWithoutAFrame() = boundedTest {
        val fixture = Fixture()
        fixture.owner.onScrollStopped(0f)
        assertEquals(listOf(0f), fixture.preVelocities)
        assertEquals(listOf(0f to 0f), fixture.postVelocities)
        assertEquals(0, fixture.frames.delivered)
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test fun suspendedPostFlingDoesNotKeepLocalMutationBusy() = boundedTest {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        fixture.post = { _, _ -> entered.complete(Unit); awaitCancellation() }
        val fling = launch(start = CoroutineStart.UNDISPATCHED) { fixture.owner.onScrollStopped(1f) }
        entered.await()
        assertFalse(fixture.state.isScrollInProgress)
        assertFalse(fixture.owner.isFlinging)
        fixture.owner.drag { scrollBy(-3f) }
        assertEquals(3, fixture.state.value)
        fling.cancelAndJoin()
    }

    @Test fun pageKeyCapturesItsViewportAtAdmissionAndLeavesUnsupportedEventsAlone() = boundedTest {
        val fixture = Fixture()
        assertTrue(fixture.owner.pageKey(true, true, false, true, this))
        assertEquals(0, fixture.state.value) // Source event returns before the launched mutation.
        fixture.state.measured(50, 500)
        yield()
        assertEquals(80, fixture.state.value) // Captured old viewport, not the later 50 px viewport.
        assertFalse(fixture.owner.pageKey(true, false, false, true, this))
        assertFalse(fixture.owner.pageKey(true, true, true, true, this))
        assertFalse(fixture.owner.pageKey(true, true, false, false, this))
        yield()
        assertEquals(80, fixture.state.value)
    }

    @Test fun descendantRawScrollCannotCompeteWithHeldMutationAndPreservesDirectionFlags() = boundedTest {
        val fixture = Fixture()
        fixture.owner.drag {
            scrollBy(-20f)
            assertEquals(0f, fixture.owner.onNestedPostScroll(10f, true), 0f)
        }
        assertTrue(fixture.state.lastScrolledForward)
        assertEquals(10f, fixture.owner.onNestedPostScroll(10f, true), 0f)
        assertEquals(10, fixture.state.value)
        assertTrue(fixture.state.lastScrolledForward) // Raw backward scroll does not rewrite flags.
        assertEquals(0f, fixture.owner.onNestedPostScroll(10f, false), 0f)
    }

    private fun boundedTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(5_000L) {
            try {
                block()
            } finally {
                coroutineContext[Job]!!.children.forEach { it.cancel() }
            }
        }
    }

    private class Fixture(val state: NativeVerticalScrollState = NativeVerticalScrollState()) {
        val frames = ControlledFrames()
        var attached = true
        var pre: suspend (Float) -> Float = { 0f }
        var post: suspend (Float, Float) -> Float = { _, _ -> 0f }
        val preVelocities = mutableListOf<Float>()
        val postVelocities = mutableListOf<Pair<Float, Float>>()
        init { state.measured(80, 500) }
        private val engine = NativeVerticalScrollEngine(state, object : NativeVerticalScrollHost {
            override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
            override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
            override fun positionChanged(value: Int) = Unit
        })
        val owner = NativeVerticalScrollOwner(
            engine, exponentialDecay(), frames,
            object : NativeVerticalFlingHost {
                override suspend fun preFling(availableY: Float): Float {
                    preVelocities += availableY
                    return pre(availableY)
                }
                override suspend fun postFling(consumedY: Float, availableY: Float): Float {
                    postVelocities += consumedY to availableY
                    return post(consumedY, availableY)
                }
                override fun isAttached() = attached
            },
        )
    }

    /** Controlled callback phase, with no wall-clock sleeps or Android frame scheduler. */
    private class ControlledFrames : MonotonicFrameClock {
        private val requests = Channel<Unit>(Channel.UNLIMITED)
        private val times = Channel<Long>(Channel.UNLIMITED)
        var delivered = 0
            private set
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            requests.send(Unit)
            val time = times.receive()
            delivered++
            return onFrame(time)
        }
        suspend fun frame(time: Long) {
            requests.receive()
            times.send(time)
            yield()
        }
    }
}
