package app.smallthingz.reverb.nativeui

import app.smallthingz.nativeui.animation.core.exponentialDecay
import app.smallthingz.nativeui.foundation.gestures.BringIntoViewSpec
import app.smallthingz.nativeui.runtime.MonotonicFrameClock
import app.smallthingz.nativeui.MotionDurationScale
import app.smallthingz.nativeui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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

/** Host continuation tests; these do not qualify View geometry or actual Android frame phases. */
class NativeContentInViewOwnerTest {
    @Test(timeout = 5_000) fun offscreenRequestMovesActualRetainedPositionThenCompletes() = runBlocking {
        val fixture = Fixture(this)
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.content.bringChildIntoView { fixture.bounds(140f, 170f) }
        }
        assertTrue(fixture.state.isScrollInProgress)
        fixture.frames.advanceUntil { request.isCompleted && !fixture.content.isAnimationRunning }
        request.join()
        assertFalse(request.isCancelled)
        assertEquals(90, fixture.state.value)
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test(timeout = 5_000) fun newerNonOverlappingRequestCancelsOnlyOldRequesterAndRetargetsAnimation() = runBlocking {
        val fixture = Fixture(this)
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.content.bringChildIntoView { fixture.bounds(140f, 170f) }
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.content.bringChildIntoView { fixture.bounds(240f, 270f) }
        }
        first.join()
        assertTrue(first.isCancelled)
        assertTrue(fixture.content.isAnimationRunning)
        fixture.frames.advanceUntil { second.isCompleted && !fixture.content.isAnimationRunning }
        assertFalse(second.isCancelled)
        assertEquals(190, fixture.state.value)
    }

    @Test(timeout = 5_000) fun userMutationPreemptsFocusAnimationAndRetiresPendingRequest() = runBlocking {
        val fixture = Fixture(this)
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.content.bringChildIntoView { fixture.bounds(140f, 170f) }
        }
        val drag = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.owner.drag { scrollBy(-7f); awaitCancellation() }
        }
        request.join()
        yield()
        assertTrue(request.isCancelled)
        assertFalse(fixture.content.isAnimationRunning)
        assertTrue(fixture.state.isScrollInProgress)
        assertEquals(7, fixture.state.value)
        drag.cancelAndJoin()
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test(timeout = 5_000) fun unconsumableRequestCancelsInsteadOfSpinningAtRangeEnd() = runBlocking {
        val fixture = Fixture(this)
        fixture.state.measured(80, 100)
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.content.bringChildIntoView { fixture.bounds(180f, 200f) }
        }
        fixture.frames.advanceUntil { request.isCompleted && !fixture.content.isAnimationRunning }
        assertTrue(request.isCancelled)
        assertEquals(20, fixture.state.value)
        assertFalse(fixture.state.isScrollInProgress)
        assertTrue(fixture.frames.delivered <= 2)
    }

    @Test(timeout = 5_000) fun disappearingChildCompletesWithoutAnInventedReplacementRequest() = runBlocking {
        val fixture = Fixture(this)
        var present = true
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.content.bringChildIntoView { if (present) fixture.bounds(140f, 170f) else null }
        }
        present = false
        fixture.frames.advanceUntil { request.isCompleted && !fixture.content.isAnimationRunning }
        assertFalse(request.isCancelled)
        // The already accepted sample may run once; source checks detachment after that frame.
        assertTrue(fixture.frames.delivered <= 2)
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test(timeout = 5_000) fun shrinkingViewportKeepsPreviouslyVisibleFocusInView() = runBlocking {
        val fixture = Fixture(this)
        fixture.focus = { fixture.bounds(60f, 80f) }
        fixture.state.measured(50, 500)
        fixture.content.measured(50)
        assertTrue(fixture.content.isAnimationRunning)
        fixture.frames.advanceUntil { !fixture.content.isAnimationRunning }
        assertEquals(30, fixture.state.value)
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test(timeout = 5_000) fun shrinkingViewportDoesNotInventTrackingForAlreadyClippedFocus() = runBlocking {
        val fixture = Fixture(this)
        fixture.focus = { fixture.bounds(100f, 130f) }
        fixture.state.measured(50, 500)
        fixture.content.measured(50)
        assertFalse(fixture.content.isAnimationRunning)
        assertEquals(0, fixture.state.value)
        assertEquals(0, fixture.frames.delivered)
    }

    @Test(timeout = 5_000) fun nullOrVisibleRequestsNeedNoMutationOrFrame() = runBlocking {
        val fixture = Fixture(this)
        fixture.content.bringChildIntoView { null }
        fixture.content.bringChildIntoView { fixture.bounds(10f, 30f) }
        assertFalse(fixture.state.isScrollInProgress)
        assertEquals(0, fixture.frames.delivered)
    }

    @Test(timeout = 5_000) fun semanticsUsesConsumedDistanceAndMotionScaleInsteadOfRequestedDistance() = runBlocking {
        val fixture = Fixture(this)
        fixture.state.measured(80, 100)
        val result = async(NoMotion, start = CoroutineStart.UNDISPATCHED) {
            fixture.owner.semanticsScrollBy(80f)
        }
        fixture.frames.advanceUntil { result.isCompleted }
        assertEquals(20f, result.await(), 0f)
        assertEquals(20, fixture.state.value)
        assertEquals(1, fixture.frames.delivered)
        assertFalse(fixture.state.isScrollInProgress)
    }

    @Test(timeout = 5_000) fun userInputCancelsSemanticsDefaultMutationWithoutFinishingTheDrag() = runBlocking {
        val fixture = Fixture(this)
        val action = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.owner.semanticsScrollBy(80f)
        }
        val drag = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.owner.drag { awaitCancellation() }
        }
        action.join()
        assertTrue(action.isCancelled)
        assertTrue(fixture.state.isScrollInProgress)
        drag.cancelAndJoin()
        assertFalse(fixture.state.isScrollInProgress)
    }

    private class Fixture(scope: CoroutineScope) {
        val state = NativeVerticalScrollState().also { it.measured(80, 500) }
        val frames = ControlledFrames()
        var focus: () -> Rect? = { null }
        private val nodeScope = CoroutineScope(scope.coroutineContext + frames + NoMotion)
        private val engine = NativeVerticalScrollEngine(state, object : NativeVerticalScrollHost {
            override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
            override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
            override fun positionChanged(value: Int) = Unit
        })
        val owner = NativeVerticalScrollOwner(engine, exponentialDecay(), frames,
            object : NativeVerticalFlingHost {
                override suspend fun preFling(availableY: Float) = 0f
                override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
                override fun isAttached() = true
            })
        val content = NativeContentInViewOwner(owner, frames, { nodeScope }, { focus() },
            object : BringIntoViewSpec {}).also { it.measured(80) }
        fun bounds(top: Float, bottom: Float) = Rect(0f, top - state.value, 40f, bottom - state.value)
    }

    private object NoMotion : MotionDurationScale { override val scaleFactor = 0f }

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
        suspend fun advanceUntil(done: () -> Boolean) = withTimeout(2_000) {
            repeat(180) {
                yield()
                if (done()) return@withTimeout
                requests.receive()
                times.send(delivered * 16_666_667L)
                yield()
            }
            error("Animation failed to reach a terminal state within 180 controlled frames")
        }
    }
}
