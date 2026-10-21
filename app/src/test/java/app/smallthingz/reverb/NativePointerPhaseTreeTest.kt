package app.smallthingz.reverb

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Velocity
import org.junit.Assert.*
import org.junit.Test

/** Proposed host tests, not added to source sets and not run. */
class NativePointerPhaseTreeTest {
    @Test
    fun initialMainFinalFollowSourceOrderWithCancellationDeferredUntilAfterAllPasses() {
        val calls = mutableListOf<String>()
        val tree = NativePointerPhaseTree()
        val parent = Node("parent", calls)
        val child = Node("child", calls) { _, pass ->
            if (pass == NativePointerPass.MAIN) tree.cancel()
        }
        tree.add(1, listOf(parent, child))
        val event = event(change(0, 0f, 0f, down = true))
        tree.dispatch { _, _ -> event }
        assertEquals(listOf(
            "parent:INITIAL", "child:INITIAL", "child:MAIN", "parent:MAIN",
            "parent:FINAL", "child:FINAL", "child:CANCEL", "parent:CANCEL",
        ), calls)
        assertFalse(tree.hasPaths)
    }

    @Test
    fun detachmentDuringInitialSkipsDescendantAndLaterPassesThenCancelsAtNextBoundary() {
        val calls = mutableListOf<String>()
        lateinit var parent: Node
        parent = Node("parent", calls) { _, pass ->
            if (pass == NativePointerPass.INITIAL) parent.attached = false
        }
        val child = Node("child", calls)
        val tree = NativePointerPhaseTree()
        tree.add(1, listOf(parent, child))
        tree.dispatch { _, _ -> event(change(0, 0f, 0f, down = true)) }
        assertEquals(listOf("parent:INITIAL"), calls)
        tree.pruneDetached()
        assertEquals(listOf("parent:INITIAL", "child:CANCEL", "parent:CANCEL"), calls)
        assertFalse(tree.hasPaths)
    }

    @Test
    fun eachPointerRetainsItsOwnChildPathWhileAncestorsReceiveBoth() {
        val tree = NativePointerPhaseTree()
        val calls = mutableListOf<String>()
        val ids = mutableMapOf<String, Set<Long>>()
        val parent = Node("parent", calls) { event, _ -> ids["parent"] = event.changes.mapTo(mutableSetOf()) { it.id } }
        val first = Node("first", calls) { event, _ -> ids["first"] = event.changes.mapTo(mutableSetOf()) { it.id } }
        val second = Node("second", calls) { event, _ -> ids["second"] = event.changes.mapTo(mutableSetOf()) { it.id } }
        tree.add(1, listOf(parent, first))
        tree.add(2, listOf(parent, second))
        val changes = listOf(change(10, 10f, 0f, id = 1), change(10, 20f, 0f, id = 2))
        tree.dispatch { _, retained -> NativeDragPointerEvent(changes.filter { it.id in retained }, Offset.Zero) }
        assertEquals(setOf(1L, 2L), ids["parent"])
        assertEquals(setOf(1L), ids["first"])
        assertEquals(setOf(2L), ids["second"])
        tree.release(setOf(1L))
        calls.clear()
        tree.dispatch { _, retained -> NativeDragPointerEvent(changes.filter { it.id in retained }, Offset.Zero) }
        assertFalse(calls.any { it.startsWith("first:") })
        assertFalse(calls.any { it.endsWith("CANCEL") })
    }

    @Test
    fun rowCanceledByDragDoesNotConsumePagerUpMerelyBecauseItStillHandlesAndroidStream() {
        val stops = mutableListOf<Velocity>()
        val sink = object : NativeDragSink {
            override fun start(position: Offset) = Unit
            override fun delta(physicalDelta: Offset) = Unit
            override fun stop(physicalVelocity: Velocity) { stops += physicalVelocity }
            override fun cancel() = Unit
        }
        val router = NativeDragPointerRouter(NativeDragAxis.HORIZONTAL, { 10f }, { 8000f }, { false }, sink)
        val phase = NativeClickPhaseConsumption()
        var activeClick = false
        var clickCount = 0
        val parent = object : NativePhasePointerNode {
            override val attached = true
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = router.dispatch(event, pass)
            override fun cancel() = router.reset()
        }
        val row = object : NativePhasePointerNode {
            override val attached = true
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
                if (pass == NativePointerPass.MAIN) phase.main(event) { consumed, consume ->
                    val change = event.changes.single()
                    if (change.changedToDown && !consumed) { activeClick = true; consume(false) }
                    else if (change.changedToUp && activeClick && !consumed) {
                        consume(false); clickCount++; activeClick = false
                    }
                    true // Android stream remains handled even AFTER Final canceled the click.
                }
                if (pass == NativePointerPass.FINAL) phase.final(event) { if (it) activeClick = false }
            }
            override fun cancel() { activeClick = false; phase.cancel() }
        }
        val tree = NativePointerPhaseTree()
        tree.add(1, listOf(parent, row))
        for (change in listOf(
            change(0, 0f, 0f, down = true),
            change(10, -20f, 0f),
            change(20, -40f, -20f),
            change(30, -40f, -40f, up = true),
        )) tree.dispatch { _, _ -> event(change) }
        assertFalse(activeClick)
        assertEquals(0, clickCount)
        assertEquals(1, stops.size)
        assertEquals(-2000f, stops.single().x, 0.01f)
    }

    @Test
    fun originalDownExemptionDoesNotExemptLaterConsumedChangesForSamePointer() {
        val phase = NativeClickPhaseConsumption()
        val down = event(change(0, 0f, 0f, down = true))
        phase.main(down) { _, consume -> consume(false); true }
        var cancelled = false
        phase.final(down) { cancelled = it }
        assertFalse(cancelled)
        val moved = event(change(10, 1f, 0f).also { it.consume() })
        phase.final(moved) { cancelled = it }
        assertTrue(cancelled)
    }

    private class Node(
        val name: String,
        private val calls: MutableList<String>,
        private val action: (NativeDragPointerEvent, NativePointerPass) -> Unit = { _, _ -> },
    ) : NativePhasePointerNode {
        override var attached = true
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
            calls += "$name:$pass"; action(event, pass)
        }
        override fun cancel() { calls += "$name:CANCEL" }
    }

    private fun event(change: NativeDragPointerChange) = NativeDragPointerEvent(listOf(change), Offset.Zero)
    private fun change(time: Long, x: Float, oldX: Float, down: Boolean = false, up: Boolean = false, id: Long = 1) =
        NativeDragPointerChange(id, time, Offset(x, 0f), Offset(oldX, 0f), !up, !down, PointerType.Touch, Offset(x, 0f))
}
