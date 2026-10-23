package app.smallthingz.reverb

import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.util.VelocityTracker
import smallthingz.ui.unit.Velocity
import org.junit.Assert.*
import org.junit.Test

/** Host-test proposal; not in the source set and not run. */
class NativeDragPointerRouterTest {
    @Test
    fun exactSlopTriggersOnlyInMainAndRemovesSlopFromFirstDelta() {
        val sink = Sink()
        val router = router(sink)
        passes(router, event(change(0, 0f, 0f, down = true)))
        passes(router, event(change(10, 6f, 0f, previousX = 0f)))
        assertTrue(sink.events.isEmpty())
        val threshold = event(change(20, 10f, 0f, previousX = 6f))
        router.dispatch(threshold, NativePointerPass.INITIAL)
        assertTrue(sink.events.isEmpty())
        router.dispatch(threshold, NativePointerPass.MAIN)
        assertEquals(listOf("start", "delta"), sink.events)
        assertEquals(Offset(10f, 0f), sink.start)
        assertEquals(Offset.Zero, sink.deltas.single())
        assertTrue(threshold.changes.single().consumed)
    }

    @Test
    fun activeMotionConsumesDownInInitialButStartsMutationOnlyInMain() {
        val sink = Sink()
        val router = router(sink, immediate = true)
        val down = event(change(0, 0f, 0f, down = true))
        router.dispatch(down, NativePointerPass.INITIAL)
        assertTrue(down.changes.single().consumed)
        assertTrue(sink.events.isEmpty())
        router.dispatch(down, NativePointerPass.MAIN)
        assertEquals(listOf("start", "delta"), sink.events)
    }

    @Test
    fun verticalChildWinsSteepDiagonalButDefersShallowDiagonalToHorizontalParent() {
        fun runDiagonal(x: Float, y: Float): Pair<Sink, Sink> {
            val horizontalSink = Sink()
            val verticalSink = Sink()
            val horizontal = router(horizontalSink)
            val vertical = router(verticalSink, axis = NativeDragAxis.VERTICAL, parent = horizontal::interested)
            fun dispatch(event: NativeDragPointerEvent) {
                horizontal.dispatch(event, NativePointerPass.INITIAL)
                vertical.dispatch(event, NativePointerPass.INITIAL)
                vertical.dispatch(event, NativePointerPass.MAIN)
                horizontal.dispatch(event, NativePointerPass.MAIN)
                horizontal.dispatch(event, NativePointerPass.FINAL)
                vertical.dispatch(event, NativePointerPass.FINAL)
            }
            dispatch(event(change(0, 0f, 0f, down = true)))
            dispatch(event(change(10, x, y)))
            return horizontalSink to verticalSink
        }
        val steep = runDiagonal(20f, 40f)
        assertTrue(steep.first.events.isEmpty())
        assertEquals(listOf("start", "delta"), steep.second.events)
        val shallow = runDiagonal(40f, 20f)
        assertEquals(listOf("start", "delta"), shallow.first.events)
        assertTrue(shallow.second.events.isEmpty())
    }

    @Test
    fun lostGestureCanBePickedUpOnlyAfterUnconsumedFinalPassAndNextMain() {
        val sink = Sink()
        val router = router(sink)
        passes(router, event(change(0, 0f, 0f, down = true)))
        val lost = change(10, 20f, 0f).also { it.consume() }
        passes(router, event(lost))
        val free = event(change(20, 30f, 0f, previousX = 20f))
        passes(router, free)
        assertTrue(sink.events.isEmpty())
        passes(router, event(change(30, 35f, 0f, previousX = 30f)))
        assertEquals(listOf("start", "delta"), sink.events)
        assertEquals(Offset(25f, 0f), sink.deltas.single())
    }

    @Test
    fun pointerHandoverRetainsGestureAndCancelRemainsDistinctFromStop() {
        val sink = Sink()
        val router = router(sink, immediate = true)
        passes(router, event(change(0, 0f, 0f, down = true)))
        passes(router, NativeDragPointerEvent(listOf(
            change(10, 0f, 0f, up = true),
            change(10, 100f, 0f, previousX = 100f, id = 2),
        ), Offset.Zero))
        passes(router, event(change(20, 105f, 0f, previousX = 100f, id = 2)))
        assertEquals(Offset(5f, 0f), sink.deltas.last())
        router.reset()
        assertEquals("cancel", sink.events.last())
        assertFalse(sink.events.contains("stop"))
    }

    @Test
    fun consumedUpCancelsWithoutEmittingMeasuredReleaseVelocity() {
        val sink = Sink()
        val router = router(sink, immediate = true)
        passes(router, event(change(0, 0f, 0f, down = true)))
        val up = change(10, 30f, 0f, up = true).also { it.consume() }
        passes(router, event(up))
        assertEquals("cancel", sink.events.last())
        assertTrue(sink.stops.isEmpty())
    }

    @Test
    fun velocityUsesOriginalHistoryAndOmitsUpCoordinateExactlyLikePinnedFeed() {
        val native = NativeDragVelocity()
        native.add(change(0, 0f, 0f, down = true))
        native.add(change(10, 10f, 0f, history = listOf(NativePointerHistory(5, Offset(5f, 0f)))))
        native.add(change(20, 20f, 0f, previousX = 10f))
        native.add(change(30, 999f, 0f, previousX = 20f, up = true))
        val oracle = VelocityTracker().apply {
            addPosition(0, androidx.compose.ui.geometry.Offset.Zero); addPosition(5, androidx.compose.ui.geometry.Offset(5f, 0f))
            addPosition(10, androidx.compose.ui.geometry.Offset(10f, 0f)); addPosition(20, androidx.compose.ui.geometry.Offset(20f, 0f))
        }
        val expected = oracle.calculateVelocity(androidx.compose.ui.unit.Velocity(8000f, 8000f))
        assertEquals(Velocity(expected.x, expected.y), native.calculate(8000f))
    }

    @Test
    fun upAfterMoreThan40MillisecondsResetsVelocityButExactly40DoesNot() {
        fun velocity(gap: Long): Float {
            val native = NativeDragVelocity()
            native.add(change(0, 0f, 0f, down = true))
            native.add(change(10, 10f, 0f))
            native.add(change(20, 20f, 0f))
            native.add(change(20 + gap, 20f, 0f, up = true))
            return native.calculate(8000f).x
        }
        assertEquals(1000f, velocity(40), 0.01f)
        assertEquals(0f, velocity(41), 0f)
    }

    @Test
    fun velocityCompensatesMovingNodeWithoutUsingIntegerScreenCoordinates() {
        val native = NativeDragVelocity()
        native.add(change(0, 0f, 0f, down = true))
        native.add(change(10, 10f, 0f), Offset(5.25f, 0f))
        native.add(change(20, 20f, 0f), Offset(10.5f, 0f))
        val oracle = VelocityTracker().apply {
            addPosition(0, androidx.compose.ui.geometry.Offset.Zero)
            addPosition(10, androidx.compose.ui.geometry.Offset(15.25f, 0f))
            addPosition(20, androidx.compose.ui.geometry.Offset(30.5f, 0f))
        }
        val expected = oracle.calculateVelocity(androidx.compose.ui.unit.Velocity(8000f, 8000f))
        assertEquals(Velocity(expected.x, expected.y), native.calculate(8000f))
    }

    private fun router(
        sink: Sink,
        immediate: Boolean = false,
        axis: NativeDragAxis = NativeDragAxis.HORIZONTAL,
        parent: (NativeDragPointerChange) -> Boolean = { false },
    ) = NativeDragPointerRouter(axis, { 10f }, { 8000f }, { immediate }, sink, parent)

    private fun passes(router: NativeDragPointerRouter, event: NativeDragPointerEvent) {
        NativePointerPass.entries.forEach { router.dispatch(event, it) }
    }

    private fun event(change: NativeDragPointerChange) = NativeDragPointerEvent(listOf(change), Offset.Zero)

    private fun change(
        time: Long, x: Float, y: Float, previousX: Float = 0f, down: Boolean = false,
        up: Boolean = false, id: Long = 1, history: List<NativePointerHistory> = emptyList(),
    ) = NativeDragPointerChange(
        id, time, Offset(x, y), Offset(previousX, 0f), !up, !down, PointerType.Touch,
        Offset(x, y), history,
    )

    private class Sink : NativeDragSink {
        val events = mutableListOf<String>()
        var start = Offset.Unspecified
        val deltas = mutableListOf<Offset>()
        val stops = mutableListOf<Velocity>()
        override fun start(position: Offset) { events += "start"; start = position }
        override fun delta(physicalDelta: Offset) { events += "delta"; deltas += physicalDelta }
        override fun stop(physicalVelocity: Velocity) { events += "stop"; stops += physicalVelocity }
        override fun cancel() { events += "cancel" }
    }
}
