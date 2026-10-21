package app.smallthingz.reverb

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import org.junit.Assert.*
import org.junit.Test

/** Same retained nodes and consumption are delivered through one parent/child pass tree. */
class NativeHomeNestedPointerTest {
    @Test fun verticalRevealCancelsBlobClickAndHorizontalSlopInFinal() {
        val harness = Harness()
        harness.send(Offset.Zero, down = true)
        harness.send(Offset(2f, 9f))
        harness.send(Offset(2f, 19f))
        harness.send(Offset(2f, 19f), up = true)
        assertEquals(listOf("vertical:start", "vertical:5.0", "vertical:10.0", "vertical:end"), harness.calls)
        assertEquals(0, harness.clicks)
        assertEquals(1, harness.clickCancellations)
    }

    @Test fun horizontalChildWinsWhenBothAxesCrossSlopInSameMainPass() {
        val harness = Harness()
        harness.send(Offset.Zero, down = true)
        harness.send(Offset(9f, 9f))
        harness.send(Offset(19f, 19f))
        harness.send(Offset(19f, 19f), up = true)
        assertEquals(listOf("horizontal:start", "horizontal:5.0", "horizontal:10.0", "horizontal:end"), harness.calls)
        assertEquals(0, harness.clicks)
        assertEquals(1, harness.clickCancellations)
    }

    @Test fun childButtonTapSurvivesConsumedDownAndDoesNotRevealEitherPanel() {
        val harness = Harness()
        harness.send(Offset.Zero, down = true)
        harness.send(Offset.Zero, up = true)
        assertTrue(harness.calls.isEmpty())
        assertEquals(1, harness.clicks)
        assertEquals(0, harness.clickCancellations)
    }

    @Test fun actualStreamCancellationCancelsOneActiveParentDrag() {
        val harness = Harness()
        harness.send(Offset.Zero, down = true)
        harness.send(Offset(0f, -9f))
        harness.cancel()
        assertEquals(listOf("vertical:start", "vertical:-5.0", "vertical:cancel"), harness.calls)
        assertEquals(0, harness.clicks)
        assertEquals(1, harness.clickCancellations)
    }

    @Test fun pointerInputKeyReplacementDoesNotInventDragCancellationCallback() {
        val harness = Harness()
        harness.send(Offset.Zero, down = true)
        harness.send(Offset(0f, 9f))
        harness.vertical.restart()
        harness.send(Offset(0f, 9f), up = true)
        assertEquals(listOf("vertical:start", "vertical:5.0"), harness.calls)
        assertEquals(0, harness.clicks)
    }

    private class Harness {
        val calls = mutableListOf<String>()
        var clicks = 0
        var clickCancellations = 0
        private var clicking = false
        private val clickPhase = NativeClickPhaseConsumption()
        val vertical = NativeHomeVerticalDrag({ 4f },
            { calls += "vertical:start" }, { calls += "vertical:$it" },
            { calls += "vertical:end" }, { calls += "vertical:cancel" })
        private val horizontal = NativeHomeHorizontalDrag({ 4f },
            { calls += "horizontal:start" }, { calls += "horizontal:$it" },
            { calls += "horizontal:end" }, { calls += "horizontal:cancel" })
        private val tree = NativePointerPhaseTree()
        private var old = Offset.Zero
        private var time = 0L
        init {
            val parent = node(vertical::dispatch, vertical::cancel)
            val child = node(horizontal::dispatch, horizontal::cancel)
            val button = node({ event, pass ->
                when (pass) {
                    NativePointerPass.MAIN -> clickPhase.main(event) { consumed, consume ->
                        val change = event.changes.single()
                        if (change.changedToDown && !consumed) { clicking = true; consume(false) }
                        else if (change.changedToUp && clicking && !consumed) {
                            consume(false)
                            clicks++
                            clicking = false
                        }
                        true // Android stream handling never becomes movement consumption.
                    }
                    NativePointerPass.FINAL -> clickPhase.final(event) {
                        if (it && clicking) { clicking = false; clickCancellations++ }
                    }
                    NativePointerPass.INITIAL -> Unit
                }
            }, {
                if (clicking) { clicking = false; clickCancellations++ }
                clickPhase.cancel()
            })
            tree.add(1L, listOf(parent, child, button))
        }

        fun send(position: Offset, down: Boolean = false, up: Boolean = false) {
            val event = NativeDragPointerEvent(listOf(NativeDragPointerChange(1L, time, position,
                old, !up, !down, PointerType.Touch, position)), Offset.Zero)
            tree.dispatch { _, _ -> event }
            if (up) tree.release(setOf(1L))
            old = position
            time += 10
        }
        fun cancel() = tree.cancel()
        private fun node(deliver: (NativeDragPointerEvent, NativePointerPass) -> Unit, cancelInput: () -> Unit) =
            object : NativePhasePointerNode {
                override val attached = true
                override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = deliver(event, pass)
                override fun cancel() = cancelInput()
            }
    }
}
