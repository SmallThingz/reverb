package app.smallthingz.reverb.nativeui

import smallthingz.ui.animation.core.exponentialDecay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proposal tests: not run or integrated. Stateful boundaries, not copies of the sampler math. */
class NativeVerticalScrollEngineTest {
    @Test fun fractionalInputSurvivesIntegerRenderingAndReversal() {
        val state = NativeVerticalScrollState()
        state.measured(10, 100)
        assertEquals(.25f, state.scrollBy(.25f), 0f)
        assertEquals(0, state.value)
        assertEquals(.25f, state.scrollBy(.25f), 0f)
        assertEquals(1, state.value)
        assertEquals(-.25f, state.scrollBy(-.25f), 0f)
        assertEquals(0, state.value)
    }

    @Test fun boundConsumptionReportsOnlyAvailableDistance() {
        val state = NativeVerticalScrollState(4)
        state.measured(10, 15)
        assertEquals(1f, state.scrollBy(1.25f), 0f)
        assertEquals(5, state.value)
        assertFalse(state.canScrollForward)
        assertEquals(0f, state.scrollBy(.25f), 0f)
        assertEquals(-.25f, state.scrollBy(-.25f), 0f)
        assertEquals(5, state.value)
    }

    @Test fun measureClampRetainsFractionUntilNextActualMutation() {
        val state = NativeVerticalScrollState(8)
        state.measured(10, 100)
        state.scrollBy(.25f)
        state.measured(10, 11)
        assertEquals(1, state.value)
        state.scrollBy(-.5f)
        state.scrollBy(-.25f)
        assertEquals(1, state.value) // Clearing the fraction during measure incorrectly yields zero.
    }

    @Test fun saverContainsIntegerOnlyWhileRawDeltaDoesNotUpdateDirectionFlags() {
        val state = NativeVerticalScrollState(20)
        state.measured(10, 100)
        state.scrollBy(.25f)
        assertTrue(state.lastScrolledForward)
        state.dispatchRawDelta(-.25f)
        assertTrue(state.lastScrolledForward)
        val restored = NativeVerticalScrollState(state.save())
        restored.measured(10, 100)
        assertEquals(state.value, restored.value)
        state.scrollBy(Float.NaN)
        assertTrue(state.lastScrolledForward)
        state.scrollBy(0f)
        assertFalse(state.lastScrolledForward)
        assertFalse(state.lastScrolledBackward)
    }

    @Test fun nestedConsumptionUsesPhysicalDirectionAndPreSelfPostOrder() {
        val state = NativeVerticalScrollState(100).apply { measured(80, 500) }
        val log = mutableListOf<String>()
        val engine = NativeVerticalScrollEngine(state, object : NativeVerticalScrollHost {
            override fun preScroll(availableY: Float, source: NativeVerticalScrollSource): Float {
                log += "pre:$availableY"
                return 5f
            }
            override fun positionChanged(value: Int) { log += "position:$value" }
            override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource): Float {
                log += "post:$consumedY:$availableY"
                return 0f
            }
        })
        assertEquals(20f, engine.dispatch(20f, NativeVerticalScrollSource.USER_INPUT), 0f)
        assertEquals(listOf("pre:20.0", "position:85", "post:15.0:0.0"), log)
    }

    @Test fun pageKeysAreImmediateViewportDeltasAndRespectExactAdmission() {
        val state = NativeVerticalScrollState(20).apply { measured(80, 500) }
        val engine = NativeVerticalScrollEngine(state, noParents())
        assertTrue(engine.pageKey(pageDown = true, keyDown = true, ctrlPressed = false, enabled = true))
        assertEquals(100, state.value)
        assertFalse(engine.pageKey(true, true, true, true))
        assertFalse(engine.pageKey(true, false, false, true))
        assertFalse(engine.pageKey(true, true, false, false))
        assertEquals(100, state.value)
        assertTrue(engine.pageKey(false, true, false, true))
        assertEquals(20, state.value)
    }

    @Test fun flingThresholdReturnsVelocityWithoutSchedulingFrames() {
        val completed = mutableListOf<Float>()
        val fling = NativeVerticalFling(exponentialDecay(), { error("No frame expected") }, completed::add)
        fling.start(1f)
        fling.start(-1f)
        fling.start(Float.NaN)
        assertEquals(listOf(1f, -1f), completed.take(2))
        assertTrue(completed[2].isNaN())
        assertFalse(fling.isRunning)
    }

    @Test fun blockedFlingReturnsCurrentVelocityOnceAndCancelsFutureFrames() {
        val completed = mutableListOf<Float>()
        val deltas = mutableListOf<Float>()
        val fling = NativeVerticalFling(exponentialDecay(), { deltas += it; 0f }, completed::add)
        fling.start(1000f)
        fling.frame(1_000_000_000L)
        assertEquals(listOf(0f), deltas)
        assertTrue(fling.isRunning)
        fling.frame(1_016_666_667L)
        assertFalse(fling.isRunning)
        assertTrue(deltas.last() > .5f)
        assertEquals(1, completed.size)
        assertTrue(completed.single() in 0f..1000f)
        fling.frame(1_033_333_334L)
        fling.cancel()
        assertEquals(1, completed.size)
    }

    @Test fun reentrantConsumptionCancellationCannotDeliverTerminalTwice() {
        val completed = mutableListOf<Float>()
        lateinit var fling: NativeVerticalFling
        fling = NativeVerticalFling(exponentialDecay(), { delta ->
            if (delta != 0f) fling.cancel()
            0f
        }, completed::add)
        fling.start(1000f)
        fling.frame(0L)
        fling.frame(16_666_667L)
        assertEquals(1, completed.size)
        assertFalse(fling.isRunning)
    }

    @Test fun eachDrawnHostObservesIntegerMovementAndMeasureClampButNotFractionOnlyChanges() {
        val state = NativeVerticalScrollState().apply { measured(10, 100) }
        val visible = mutableListOf<Int>()
        val preview = mutableListOf<Int>()
        val visibleBinding = state.observePosition { visible += state.value }
        val previewBinding = state.observePosition { preview += state.value }
        state.scrollBy(.25f)
        assertTrue(visible.isEmpty())
        state.scrollBy(.75f)
        state.measured(10, 10)
        assertEquals(listOf(1, 0), visible)
        assertEquals(visible, preview)
        previewBinding.close()
        state.measured(10, 100)
        state.scrollBy(1f)
        assertEquals(listOf(1, 0, 1), visible)
        assertEquals(listOf(1, 0), preview)
        visibleBinding.close()
    }

    @Test fun retiringAHostDuringDispatchDoesNotLoseAnotherCurrentFrameCallback() {
        val state = NativeVerticalScrollState().apply { measured(10, 100) }
        val calls = mutableListOf<String>()
        lateinit var second: AutoCloseable
        val first = state.observePosition { calls += "first"; second.close() }
        second = state.observePosition { calls += "second" }
        state.scrollBy(1f)
        assertEquals(listOf("first", "second"), calls)
        state.scrollBy(1f)
        assertEquals(listOf("first", "second", "first"), calls)
        first.close()
    }

    private fun noParents() = object : NativeVerticalScrollHost {
        override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
        override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
        override fun positionChanged(value: Int) = Unit
    }
}
