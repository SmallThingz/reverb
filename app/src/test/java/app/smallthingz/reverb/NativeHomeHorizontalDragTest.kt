package app.smallthingz.reverb

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import org.junit.Assert.*
import org.junit.Test

class NativeHomeHorizontalDragTest {
    private class Harness {
        val events = mutableListOf<String>()
        val amounts = mutableListOf<Float>()
        val detector = NativeHomeHorizontalDrag({10f},
            { events += "start" }, { events += "delta"; amounts += it },
            { events += "end" }, { events += "cancel" })
        fun frame(vararg changes: NativeDragPointerChange) {
            val event = NativeDragPointerEvent(changes.toList(), Offset.Zero)
            NativePointerPass.entries.forEach { detector.dispatch(event, it) }
        }
    }
    private fun change(x: Float, previousX: Float = 0f, id: Long = 1,
        down: Boolean = false, up: Boolean = false, consumed: Boolean = false) =
        NativeDragPointerChange(id, 0L, Offset(x,0f), Offset(previousX,0f), !up, !down,
            PointerType.Touch, Offset(x,0f)).also { if (consumed) it.consume() }

    @Test fun consumedDownStillStartsAndFirstDeltaExcludesOnlyHorizontalSlop() {
        val h = Harness()
        h.frame(change(0f,down=true,consumed=true))
        h.frame(change(6f))
        assertTrue(h.events.isEmpty())
        val crossed = change(15f,6f)
        h.frame(crossed)
        assertEquals(listOf("start","delta"),h.events)
        assertEquals(5f,h.amounts.single(),0f)
        assertTrue(crossed.consumed)
        h.frame(change(15f,15f,up=true))
        assertEquals("end",h.events.last())
    }

    @Test fun finalPassConsumptionLosesGestureWithoutDraggablePickup() {
        val h = Harness()
        h.frame(change(0f,down=true))
        val event = NativeDragPointerEvent(listOf(change(6f)),Offset.Zero)
        h.detector.dispatch(event,NativePointerPass.MAIN)
        event.changes.single().consume()
        h.detector.dispatch(event,NativePointerPass.FINAL)
        h.frame(change(100f,6f))
        h.frame(change(110f,100f))
        assertTrue(h.events.isEmpty())
        h.frame(change(110f,110f,up=true))
        h.frame(change(0f,down=true))
        h.frame(change(-12f))
        assertEquals(-2f,h.amounts.single(),0f)
    }

    @Test fun slopPointerHandoffPreservesAccumulatedDistance() {
        val h = Harness()
        h.frame(change(0f,down=true))
        h.frame(change(6f))
        h.frame(change(6f,6f,up=true),change(100f,100f,id=2))
        h.frame(change(105f,100f,id=2))
        assertEquals(1f,h.amounts.single(),0f)
    }

    @Test fun dragHandoffIgnoresConsumedLiftBeforeCheckingNextPointersMovement() {
        val h = Harness()
        h.frame(change(0f,down=true));h.frame(change(12f))
        h.frame(change(12f,12f,up=true,consumed=true),change(100f,100f,id=2))
        h.frame(change(104f,100f,id=2))
        assertEquals(listOf(2f,4f),h.amounts)
        h.frame(change(104f,104f,id=2,up=true))
        assertEquals("end",h.events.last())
        assertFalse(h.events.contains("cancel"))
    }

    @Test fun consumedStationaryFrameDoesNotCancelUntilAnAwaitedHorizontalMoveOrFinalUp() {
        val h = Harness()
        h.frame(change(0f,down=true));h.frame(change(12f))
        h.frame(change(12f,12f,consumed=true))
        assertFalse(h.events.contains("cancel"))
        h.frame(change(13f,12f,consumed=true))
        assertEquals("cancel",h.events.last())
    }

    @Test fun keyRestartDoesNotInventCancelButActualCanceledStreamDoes() {
        val h = Harness()
        h.frame(change(0f,down=true));h.frame(change(12f))
        h.detector.restart()
        assertFalse(h.events.contains("cancel"))
        h.frame(change(13f,12f))
        assertEquals(listOf(2f),h.amounts)
        h.frame(change(13f,13f,up=true))
        h.frame(change(0f,down=true));h.frame(change(12f))
        h.detector.cancel()
        assertEquals("cancel",h.events.last())
    }
}
