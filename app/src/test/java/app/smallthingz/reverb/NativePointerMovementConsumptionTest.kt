package app.smallthingz.reverb

import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.pointer.PointerType
import org.junit.Assert.*
import org.junit.Test

class NativePointerMovementConsumptionTest {
    @Test fun handledOrConsumedStationaryEventsDoNotRequestNativeParentInterception() {
        val stationary = frame(Offset(10f, 10f), Offset(10f, 10f))
        stationary.changes.single().consumption.consumed = true
        assertFalse(stationary.anyMovementConsumed())
        val moved = frame(Offset(10f, 10f), Offset(10f, 11f))
        assertFalse(moved.anyMovementConsumed())
        moved.changes.single().consumption.consumed = true
        assertTrue(moved.anyMovementConsumed())
    }

    @Test fun allNodeProjectionsShareTheCurrentEventSuppressionFlagAndLastWriteWins() {
        val frame = frame(Offset.Zero, Offset(1f, 0f))
        val first = frame.project({ it }, Offset.Zero)
        val second = frame.project({ it + Offset(100f, 0f) }, Offset(-100f, 0f))
        assertSame(first.dispatchState, second.dispatchState)
        first.changes.single().consume()
        first.dispatchState.suppressMovementConsumption = true
        assertFalse(frame.anyMovementConsumed())
        second.dispatchState.suppressMovementConsumption = false
        assertTrue(frame.anyMovementConsumed())
    }

    @Test fun suppressionIsNotRetainedIntoTheNextMotionEvent() {
        val prior = frame(Offset.Zero, Offset(1f, 0f))
        prior.dispatchState.suppressMovementConsumption = true
        val next = frame(Offset(1f, 0f), Offset(2f, 0f))
        next.changes.single().consumption.consumed = true
        assertFalse(next.dispatchState.suppressMovementConsumption)
        assertTrue(next.anyMovementConsumed())
    }

    private fun frame(old: Offset, current: Offset) = NativeRootPointerFrame(listOf(NativeRootPointerChange(
        1L, 0, 1L, current, old, true, true, PointerType.Touch, current, emptyList(),
    )))
}
