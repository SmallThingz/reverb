package app.smallthingz.reverb

import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.pointer.PointerType
import org.junit.Assert.*
import org.junit.Test

class NativeAndroidInteropDispatchTest {
    private class Harness {
        val calls = mutableListOf<String>()
        val suppression = mutableListOf<Boolean>()
        var accept = true
        var duringDispatch: () -> Unit = {}
        val owner = NativeAndroidInteropDispatch(
            { cancel -> calls += if (cancel) "cancel" else "event"; duringDispatch(); accept },
            { calls += "empty-cancel" },
            suppression::add,
        )
        fun passes(event: NativeDragPointerEvent, down: Boolean = false) {
            NativePointerPass.entries.forEach { owner.dispatch(event, it, down) }
        }
        fun down() = event(pressed = true, previous = false).also { passes(it, true) }
    }

    @Test fun acceptedDownAndUpConsumeAndAllUpResetsOwnership() {
        val h = Harness()
        assertTrue(h.down().changes.single().consumed)
        val up = event(pressed = false, previous = true)
        h.passes(up)
        assertTrue(up.changes.single().consumed)
        h.owner.cancel()
        assertEquals(listOf("event", "event"), h.calls)
        h.down()
        h.owner.cancel()
        h.owner.cancel()
        assertEquals(listOf("event", "event", "event", "empty-cancel"), h.calls)
    }

    @Test fun rejectedDownThenCancelAdmitsNextStreamWithoutWaitingForUp() {
        val h = Harness()
        h.accept = false
        assertFalse(h.down().changes.single().consumed)
        h.passes(event())
        h.owner.cancel()
        h.owner.cancel()
        assertEquals(listOf("event"), h.calls)
        h.accept = true
        assertTrue(h.down().changes.single().consumed)
        assertEquals(listOf("event", "event"), h.calls)
    }

    @Test fun parentWinThenCancelAdmitsNextStreamWithoutWaitingForUp() {
        val h = Harness()
        h.down()
        val move = event()
        h.owner.dispatch(move, NativePointerPass.INITIAL, false)
        assertFalse(move.changes.single().consumed)
        h.owner.dispatch(move, NativePointerPass.MAIN, false)
        move.changes.single().consume() // ancestor's later Main drag wins source ownership
        h.owner.dispatch(move, NativePointerPass.FINAL, false)
        h.passes(event())
        h.owner.cancel()
        h.owner.cancel()
        assertEquals(listOf("event", "event", "cancel"), h.calls)
        assertTrue(h.down().changes.single().consumed)
        assertEquals(listOf("event", "event", "cancel", "event"), h.calls)
        h.passes(event(pressed = false, previous = true))
        h.owner.cancel()
        assertEquals(listOf("event", "event", "cancel", "event", "event"), h.calls)
    }

    @Test fun viewDisallowRequestDuringInitialMoveConsumesBeforeParentMain() {
        val h = Harness()
        h.down()
        h.duringDispatch = { h.owner.disallowIntercept = true }
        val move = event()
        h.owner.dispatch(move, NativePointerPass.INITIAL, false)
        assertFalse(move.changes.single().consumed)
        h.owner.dispatch(move, NativePointerPass.MAIN, false)
        assertTrue(move.changes.single().consumed)
        h.owner.dispatch(move, NativePointerPass.FINAL, false)
        assertEquals(listOf("event", "event"), h.calls)
        assertEquals(listOf(true, false), h.suppression)
    }

    @Test fun inheritedConsumptionCancelsWithoutDeliveringOrdinaryMove() {
        val h = Harness()
        h.down()
        val move = event().also { it.changes.single().consume() }
        h.passes(move)
        assertEquals(listOf("event", "cancel"), h.calls)
    }

    @Test fun ordinaryMoveConsumesOnlyFinalAndFalseMoveReturnDoesNotRetireStream() {
        val h = Harness()
        h.down()
        h.accept = false // only ACTION_DOWN's return changes ownership
        val move = event()
        h.owner.dispatch(move, NativePointerPass.INITIAL, false)
        h.owner.dispatch(move, NativePointerPass.MAIN, false)
        assertFalse(move.changes.single().consumed)
        h.owner.dispatch(move, NativePointerPass.FINAL, false)
        assertTrue(move.changes.single().consumed)
        h.owner.cancel()
        assertEquals(listOf("event", "event", "empty-cancel"), h.calls)
    }

    @Test fun equalEventWrappersAreNotMistakenForSameInitialEventIdentity() {
        val h = Harness()
        h.down()
        val initial = event()
        h.owner.dispatch(initial, NativePointerPass.INITIAL, false)
        val other = initial.copy() // data equality; pinned PointerEvent has identity equality
        h.owner.dispatch(other, NativePointerPass.FINAL, false)
        assertFalse(other.changes.single().consumed)
        assertEquals(listOf("event", "event"), h.calls)
    }

    private companion object {
        fun event(pressed: Boolean = true, previous: Boolean = true) =
            NativeDragPointerEvent(listOf(NativeDragPointerChange(
                1, 10, Offset(2f, 3f), Offset(1f, 2f), pressed, previous,
                PointerType.Touch, Offset(2f, 3f),
            )), Offset.Zero)
    }
}
