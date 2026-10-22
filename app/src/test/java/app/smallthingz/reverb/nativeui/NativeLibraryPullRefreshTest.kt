package app.smallthingz.reverb.nativeui

import app.smallthingz.nativeui.runtime.MonotonicFrameClock
import kotlinx.coroutines.suspendCancellableCoroutine
import app.smallthingz.nativeui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLibraryPullRefreshTest {
    @Test fun exactThresholdDoesNotRefresh_andFirstGreaterFloatDoes() {
        val drag = NativePullRefreshDrag(80)
        assertEquals(160f, drag.consume(160f, refreshing = false), 0f)
        assertEquals(80f, drag.verticalOffset, 0f)
        assertFalse(drag.shouldRefresh)
        drag.consume(Math.ulp(160f), refreshing = false)
        assertTrue(drag.shouldRefresh)
    }

    @Test fun upwardConsumptionReleasesOnlyTheHeldDistance() {
        val drag = NativePullRefreshDrag(80)
        drag.consume(60f, false)
        assertEquals(-25f, drag.consume(-25f, false), 0f)
        assertEquals(17.5f, drag.verticalOffset, 0f)
        assertEquals(-35f, drag.consume(-90f, false), 0f)
        assertEquals(0f, drag.distancePulled, 0f)
        assertEquals(0f, drag.consume(-10f, false), 0f)
    }

    @Test fun tensionCapsVisibleOffsetButPreservesAllConsumedPull() {
        val drag = NativePullRefreshDrag(80)
        drag.consume(320f, false) // adjusted160 -> overshoot1 -> tension.75.
        assertEquals(140f, drag.verticalOffset, 0f)
        assertEquals(680f, drag.consume(680f, false), 0f)
        assertEquals(160f, drag.verticalOffset, 0f)
        assertEquals(1000f, drag.distancePulled, 0f)
    }

    @Test fun refreshingDoesNotConsume_andReleasePreservesUpwardFling() {
        val drag = NativePullRefreshDrag(80)
        assertEquals(0f, drag.consumedReleaseVelocity(400f), 0f)
        drag.consume(25f, false)
        assertEquals(0f, drag.consume(20f, true), 0f)
        assertEquals(25f, drag.distancePulled, 0f)
        assertEquals(0f, drag.consumedReleaseVelocity(-400f), 0f)
        assertEquals(400f, drag.consumedReleaseVelocity(400f), 0f)
        drag.atThreshold()
        // Source finally stores threshold (not twice threshold) in the raw accumulator.
        assertEquals(80f, drag.distancePulled, 0f)
        assertEquals(80f, drag.verticalOffset, 0f)
    }

    @Test fun arrowDiscardsFirstFortyPercent_andTensionSaturates() {
        assertEquals(0f, nativePullRefreshArrow(.4f).scale, 0f)
        val full = nativePullRefreshArrow(1f)
        assertEquals(1f, full.scale, 0f)
        assertEquals(288f, full.endAngle - full.startAngle, .0001f)
        assertEquals(nativePullRefreshArrow(3f), nativePullRefreshArrow(10f))
    }

    @Test fun nestedOrder_parentPreBeforeLocal_andLocalPostBeforeParent() {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + clock)
        val calls = mutableListOf<String>()
        val ancestor = object : NativeVerticalNestedHost {
            override fun preScroll(y: Float, source: NativeVerticalScrollSource): Float {
                calls += "pre:$y"; return if (y < 0f) -10f else 0f
            }
            override fun postScroll(consumed: Float, available: Float, source: NativeVerticalScrollSource): Float {
                calls += "post:$consumed:$available"; return 0f
            }
            override suspend fun preFling(y: Float) = 0f
            override suspend fun postFling(consumed: Float, available: Float) = 0f
        }
        val owner = NativeLibraryPullRefreshOwner(scope, Density(1f), false, ancestor, {})
        try {
            owner.attach()
            assertEquals(30f, owner.nested.postScroll(7f, 30f, NativeVerticalScrollSource.USER_INPUT), 0f)
            assertEquals("post:37.0:0.0", calls.last())
            assertEquals(-40f, owner.nested.preScroll(-50f, NativeVerticalScrollSource.USER_INPUT), 0f)
            assertEquals("pre:-50.0", calls.last())
            assertEquals(0f, owner.nested.postScroll(0f, 90f, NativeVerticalScrollSource.SIDE_EFFECT), 0f)
            assertEquals("post:0.0:90.0", calls.last())
        } finally { owner.close(); scope.cancel() }
    }

    @Test fun releaseInvokesLatestCallback_thenWaitsForHiddenSpringBeforeVelocityReturn() = runBlocking {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + clock)
        var oldCalls = 0
        var currentCalls = 0
        val owner = NativeLibraryPullRefreshOwner(scope, Density(1f), false, noAncestor, { oldCalls++ })
        try {
            owner.attach()
            owner.bind(false) { currentCalls++ }
            owner.nested.postScroll(0f, 200f, NativeVerticalScrollSource.USER_INPUT)
            assertTrue(owner.distanceFraction > 1f)
            val release = scope.async(start = CoroutineStart.UNDISPATCHED) { owner.nested.preFling(500f) }
            assertEquals(0, oldCalls)
            assertEquals(1, currentCalls)
            assertFalse(release.isCompleted)
            assertEquals(0f, owner.nested.postScroll(0f, 30f, NativeVerticalScrollSource.USER_INPUT), 0f)
            repeat(240) { clock.sendFrame(it * 16_666_667L) }
            assertTrue("Default spring must settle before returned consumed velocity", release.isCompleted)
            assertEquals(500f, release.await(), 0f)
            assertEquals(0f, owner.distanceFraction, 0f)
        } finally { owner.close(); scope.cancel() }
    }

    @Test fun alreadyRefreshingNeverRequestsAgainOrConsumesFling() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + BroadcastFrameClock())
        var calls = 0
        val owner = NativeLibraryPullRefreshOwner(scope, Density(1f), true, noAncestor, { calls++ })
        try {
            owner.attach()
            assertEquals(1f, owner.distanceFraction, 0f)
            assertEquals(0f, owner.nested.postScroll(0f, 200f, NativeVerticalScrollSource.USER_INPUT), 0f)
            assertEquals(0f, owner.nested.preFling(500f), 0f)
            assertEquals(0, calls)
            owner.detach()
            assertEquals(0f, owner.nested.postScroll(0f, 200f, NativeVerticalScrollSource.USER_INPUT), 0f)
        } finally { owner.close(); scope.cancel() }
    }

    private val noAncestor = object : NativeVerticalNestedHost {
        override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
        override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
        override suspend fun preFling(availableY: Float) = 0f
        override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
    }
}

/** Test clock: broadcasts one timestamp to the current waiters only. */
private class BroadcastFrameClock : MonotonicFrameClock {
    private val callbacks = ArrayDeque<(Long) -> Unit>()
    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = suspendCancellableCoroutine { continuation ->
        callbacks.addLast { time ->
            if (continuation.isActive) {
                continuation.resumeWith(runCatching { onFrame(time) })
            }
        }
    }
    fun sendFrame(timeNanos: Long) {
        val waiting = callbacks.size
        repeat(waiting) { callbacks.removeFirst()(timeNanos) }
    }
}
