package app.smallthingz.reverb.nativeui

import org.junit.Assert.*
import org.junit.Test

class NativeLibraryScrollAccumulatorTest {
    @Test fun subpixelDeltasAccumulateUntilSourceHalfPixelThreshold() {
        val state = NativeLibraryScrollAccumulator()
        val requests = mutableListOf<Int>()
        val consume: (Int) -> Int = { requests += it; it }
        assertEquals(.25f, state.dispatch(.25f, true, true, consume), 0f)
        assertEquals(.25f, state.dispatch(.25f, true, true, consume), 0f)
        assertTrue(requests.isEmpty())
        assertEquals(.1f, state.dispatch(.1f, true, true, consume), 0f)
        assertEquals(listOf(1), requests)
        assertEquals(-.2f, state.dispatch(-.2f, true, true, consume), 0f)
        assertEquals(listOf(1, -1), requests)
    }
    @Test fun unconsumedEdgeIsReturnedAndDoesNotPoisonNextDirection() {
        val state = NativeLibraryScrollAccumulator()
        assertEquals(-2f, state.dispatch(-4f, true, true) { -2 }, 0f)
        assertEquals(1f, state.dispatch(1f, true, true) { it }, 0f)
    }
    @Test fun knownBoundaryDoesNotInvokeNativeScroll() {
        val state = NativeLibraryScrollAccumulator()
        assertEquals(0f, state.dispatch(-10f, false, true) { error("blocked forward") }, 0f)
        assertEquals(0f, state.dispatch(10f, true, false) { error("blocked backward") }, 0f)
    }
    @Test fun noMovementLeavesNoClaimedConsumption() {
        val state = NativeLibraryScrollAccumulator()
        assertEquals(0f, state.dispatch(2f, true, true) { 0 }, 0f)
        assertEquals(3f, state.dispatch(3f, true, true) { it }, 0f)
    }
    @Test fun nanIsIgnoredBeforeNativeDispatch() {
        val state = NativeLibraryScrollAccumulator()
        assertEquals(0f, state.dispatch(Float.NaN, true, true) { error("nan") }, 0f)
    }
}
