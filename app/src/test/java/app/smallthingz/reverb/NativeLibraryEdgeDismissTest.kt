package app.smallthingz.reverb

import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.pointer.PointerType
import smallthingz.ui.unit.Velocity
import org.junit.Assert.*
import org.junit.Test

class NativeLibraryEdgeDismissTest {
    /** The retained child path exists before ancestor INITIAL, just as in the window dispatcher. */
    private class NestedHarness(initiallyFlinging: Boolean) {
        var dismissed = 0
        var stopped = 0
        var starts = 0
        var deltas = 0
        var observerEvents = 0
        var clicks = 0
        private var clickActive = false
        private val clickPhase = NativeClickPhaseConsumption()
        var flinging = initiallyFlinging
        private val sink = object : NativeDragSink {
            override fun start(position: Offset) { starts++; flinging = false }
            override fun delta(physicalDelta: Offset) { if (physicalDelta.y != 0f) deltas++ }
            override fun stop(physicalVelocity: Velocity) = Unit
            override fun cancel() = Unit
        }
        private fun newRouter() = NativeDragPointerRouter(
            NativeDragAxis.VERTICAL, { 10f }, { 8000f }, { flinging }, sink,
        )
        private var router = newRouter()
        val edge = NativeLibraryEdgeDismiss({ 1000f }, { 10f }, { 64f }, onEdgeDown = {
            stopped++
            flinging = false
            router.reset()
            router = newRouter() // Production stop replaces the attached router during ancestor INITIAL.
        }) { dismissed++ }
        private val ancestor = object : NativePhasePointerNode {
            override val attached = true
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = edge.dispatch(event, pass)
            override fun cancel() = edge.restart()
        }
        private val observer = object : NativePhasePointerNode {
            override val attached = true
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
                if (pass == NativePointerPass.INITIAL) observerEvents++
            }
            override fun cancel() = Unit
        }
        private val child = object : NativePhasePointerNode {
            override val attached = true
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) =
                edge.dispatchScroll(event, pass) { current, phase -> router.dispatch(current, phase) }
            override fun cancel() = router.reset()
        }
        private val click = object : NativePhasePointerNode {
            override val attached = true
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
                if (pass == NativePointerPass.MAIN) clickPhase.main(event) { consumed, consume ->
                    val change = event.changes.first()
                    if (change.changedToDown && !consumed) { clickActive = true; consume(false) }
                    else if (change.changedToUp && clickActive && !consumed) {
                        clicks++; clickActive = false; consume(false)
                    }
                    true
                }
                if (pass == NativePointerPass.FINAL) clickPhase.final(event) { if (it) clickActive = false }
            }
            override fun cancel() { clickActive = false; clickPhase.cancel() }
        }
        val tree = NativePointerPhaseTree()
        var includeClick = false
        fun frame(vararg changes: NativeDragPointerChange) {
            changes.filter { it.changedToDown }.forEach {
                tree.add(it.id, if (includeClick) listOf(ancestor, observer, child, click)
                    else listOf(ancestor, observer, child))
            }
            tree.dispatch { _, ids -> NativeDragPointerEvent(changes.filter { it.id in ids }, Offset.Zero) }
            tree.release(changes.filter { it.changedToUp }.mapTo(mutableSetOf()) { it.id })
        }
    }

    @Test fun reservedEdgeTapsClickButUpwardAndDownwardDragsCancelDescendantClicks() {
        for (distance in listOf(-100f, -10f, 10f, 100f, 5f)) {
            val h = NestedHarness(true).also { it.includeClick = true }
            h.frame(change(0f, down=true))
            h.frame(change(distance, 0f))
            h.frame(change(distance, up=true))
            assertEquals("distance=$distance", if (distance == 5f) 1 else 0, h.clicks)
            assertEquals(if (distance == 100f) 1 else 0, h.dismissed)
            assertEquals(0, h.starts)
        }
    }

    @Test fun reservedEdgesBeatIdleAndFlingingChildOnTheActualThreePassTree() {
        for (flinging in listOf(false, true)) for (x in listOf(-30f, 0f, 130f, 870f, 1000f, 1030f)) {
            val h = NestedHarness(flinging)
            val down = change(0f, x=x, down=true)
            h.frame(down)
            assertFalse("edge DOWN reached immediate child at $x", down.consumed)
            assertFalse(h.flinging)
            h.frame(change(100f, 0f, x=x))
            h.frame(change(100f, x=x, up=true))
            assertEquals(1, h.dismissed)
            assertEquals(1, h.stopped)
            assertEquals(0, h.starts)
            assertEquals(0, h.deltas)
            assertEquals(3, h.observerEvents)
            assertFalse(h.edge.reservesScroll)
        }
    }

    @Test fun centerStillInterruptsFlingAndScrollsWithoutEdgeStopOrDismissal() {
        for (flinging in listOf(false, true)) for (x in listOf(131f, 500f, 869f)) {
            val h = NestedHarness(flinging)
            val down = change(0f, x=x, down=true)
            h.frame(down)
            assertEquals(flinging, down.consumed)
            h.frame(change(100f, 0f, x=x))
            h.frame(change(100f, x=x, up=true))
            assertEquals(1, h.starts)
            assertEquals(1, h.deltas)
            assertEquals(0, h.stopped)
            assertEquals(0, h.dismissed)
        }
    }

    @Test fun edgeReservationSurvivesReversalCenterCrossingAndReplacementUntilAllUp() {
        val h = NestedHarness(true)
        h.frame(change(0f, down=true))
        h.frame(change(-30f, 0f, x=500f))
        h.frame(change(-30f, x=500f, up=true), change(200f, x=500f, id=2, down=true))
        assertTrue(h.edge.reservesScroll)
        h.frame(change(300f, 200f, x=500f, id=2))
        h.frame(change(300f, x=500f, id=2, up=true))
        assertEquals(1, h.stopped)
        assertEquals(1, h.dismissed)
        assertEquals(0, h.starts)
        assertFalse(h.edge.reservesScroll)
    }

    @Test fun cancellationAndShortReleaseClearReservationWithoutDismissOrLateChildPickup() {
        val h = NestedHarness(true)
        h.frame(change(0f, down=true)); h.frame(change(60f, 0f))
        h.tree.cancel()
        assertFalse(h.edge.reservesScroll)
        assertEquals(0, h.dismissed)
        h.frame(change(0f, down=true)); h.frame(change(70f, 0f)); h.frame(change(70f, up=true))
        assertEquals(0, h.dismissed)
        assertEquals(0, h.starts)
        h.frame(change(0f, x=500f, down=true)); h.frame(change(100f, 0f, x=500f))
        assertEquals(1, h.starts)
        assertEquals(2, h.stopped)
    }

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
