package app.smallthingz.reverb

import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.input.pointer.PointerType
import org.junit.Assert.*
import org.junit.Test

class NativeLibraryEdgeDismissTest {
    private class Harness {
        var dismissed = 0
        val detector = NativeLibraryEdgeDismiss({ 1000f }, { 10f }, { 64f }) { dismissed++ }
        fun frame(vararg changes: NativeDragPointerChange) {
            val event = NativeDragPointerEvent(changes.toList(), Offset.Zero)
            NativePointerPass.entries.forEach { detector.dispatch(event, it) }
        }
    }
    private fun change(y: Float, old: Float = y, x: Float = 20f, id: Long = 1,
                       down: Boolean = false, up: Boolean = false, consumed: Boolean = false) =
        NativeDragPointerChange(id, 0L, Offset(x,y), Offset(x,old), !up, !down,
            PointerType.Touch, Offset(x,y)).also { if (consumed) it.consume() }

    @Test fun edgeDismissCountsPostSlopOnlyAndCommitsOnlyOnNormalRelease() {
        val h = Harness()
        h.frame(change(0f,down=true,consumed=true))
        h.frame(change(10f,0f)) // Exact threshold is declined because overSlop is zero.
        h.frame(change(85f,10f)) // New threshold:65px accepted.
        assertEquals(0,h.dismissed)
        h.frame(change(85f,up=true))
        assertEquals(1,h.dismissed)
    }
    @Test fun centerAndInsufficientDragNeverDismiss() {
        val h = Harness()
        h.frame(change(0f,x=500f,down=true))
        h.frame(change(100f,0f,x=500f));h.frame(change(100f,x=500f,up=true))
        h.frame(change(0f,down=true));h.frame(change(70f,0f));h.frame(change(70f,up=true))
        assertEquals(0,h.dismissed)
    }
    @Test fun consumedFinalBeforeSlopRetiresUntilAllPointersUp() {
        val h = Harness()
        h.frame(change(0f,down=true))
        val event = NativeDragPointerEvent(listOf(change(5f,0f)),Offset.Zero)
        h.detector.dispatch(event,NativePointerPass.MAIN)
        event.changes.single().consume()
        h.detector.dispatch(event,NativePointerPass.FINAL)
        h.frame(change(100f,5f));h.frame(change(100f,up=true))
        assertEquals(0,h.dismissed)
    }
    @Test fun upwardRejectedCrossingResetsSlopAndReversalSubtractsAcceptedDistance() {
        val h = Harness()
        h.frame(change(0f,down=true));h.frame(change(-30f,0f))
        val accepted=change(60f,-30f);h.frame(accepted)
        assertTrue(accepted.consumed) //80px post-slop
        h.frame(change(35f,60f));h.frame(change(35f,up=true)) //55px remains
        assertEquals(0,h.dismissed)
    }
    @Test fun replacementPointerRetainsDistanceButConsumedTerminalDoesNotCommit() {
        val h = Harness()
        h.frame(change(0f,down=true));h.frame(change(50f,0f))
        h.frame(change(50f,up=true,consumed=true),change(500f,id=2))
        h.frame(change(530f,500f,id=2));h.frame(change(530f,id=2,up=true,consumed=true))
        assertEquals(0,h.dismissed)
    }
    @Test fun sourceAndroidSecondaryMouseDownIsAdmittedAndCancellationNeverCommits() {
        val h = Harness()
        val down=NativeDragPointerChange(1,0L,Offset(20f,0f),Offset(20f,0f),true,false,PointerType.Mouse,Offset(20f,0f))
        val event=NativeDragPointerEvent(listOf(down),Offset.Zero,primaryMouseButtonPressed=false)
        assertFalse(event.isDown())
        NativePointerPass.entries.forEach { h.detector.dispatch(event,it) }
        val move=NativeDragPointerChange(1,1L,Offset(20f,65f),Offset(20f,0f),true,true,PointerType.Mouse,Offset(20f,65f))
        h.frame(move)
        assertTrue(move.consumed)
        h.detector.restart()
        h.frame(change(65f,up=true))
        assertEquals(0,h.dismissed)
    }
}
