package app.smallthingz.reverb.nativeui

import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.input.pointer.PointerType
import app.smallthingz.reverb.NativeDragPointerChange
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePointerConsumption
import org.junit.Assert.*
import org.junit.Test

class InlineWaveformPointerOwnerTest {
    private val key = InlineWaveformPointerKey("original", 10000, true, false)
    private fun event(id: Long = 1, x: Float = 12f, pressed: Boolean = true,
                      wasPressed: Boolean = false, consumed: Boolean = false): NativeDragPointerEvent =
        NativeDragPointerEvent(listOf(NativeDragPointerChange(id, 1L, Offset(x, 0f), Offset.Zero,
            pressed, wasPressed, PointerType.Touch, Offset(x, 0f), consumption = NativePointerConsumption(consumed))), Offset.Zero)
    private fun binding(events: MutableList<String>, key: InlineWaveformPointerKey = this.key, saving: Boolean = false) =
        InlineWaveformPointerBinding(key, saving, { x, w -> events += "begin:$x/$w" },
            { x, w -> events += "move:$x/$w" }, { events += "finish:$it" })

    @Test fun immediateDownConsumesThenNormalReleaseCommitsExactlyOnce() {
        val calls = mutableListOf<String>(); val owner = InlineWaveformPointerOwner()
        owner.bind(binding(calls))
        val down = event(); owner.dispatch(down, 100)
        assertTrue(down.changes.single().consumed)
        val move = event(x = 72f, wasPressed = true); owner.dispatch(move, 100)
        assertTrue(move.changes.single().consumed)
        val up = event(x = 73f, pressed = false, wasPressed = true); owner.dispatch(up, 100)
        assertFalse(up.changes.single().consumed)
        owner.cancel()
        assertEquals(listOf("begin:12.0/100.0", "move:72.0/100.0", "finish:true"), calls)
    }

    @Test fun consumedReleaseRevokesResumeButDoesNotDropTerminalSeek() {
        val calls = mutableListOf<String>(); val owner = InlineWaveformPointerOwner()
        owner.bind(binding(calls)); owner.dispatch(event(consumed = true), 100)
        owner.dispatch(event(pressed = false, wasPressed = true, consumed = true), 100)
        assertEquals(listOf("begin:12.0/100.0", "finish:false"), calls)
    }

    @Test fun replacingSourceCancelsOldOwnerBeforeInstallingNewActions() {
        val original = mutableListOf<String>(); val replacement = mutableListOf<String>()
        val owner = InlineWaveformPointerOwner()
        owner.bind(binding(original)); owner.dispatch(event(), 100)
        owner.bind(binding(replacement, key.copy(recordingRevisionKey = "replacement")))
        owner.dispatch(event(pressed = false, wasPressed = true), 100)
        assertEquals(listOf("begin:12.0/100.0", "finish:false"), original)
        assertTrue(replacement.isEmpty())
        owner.dispatch(event(id = 2), 100)
        assertEquals(listOf("begin:12.0/100.0"), replacement)
    }

    @Test fun sameKeyRefreshKeepsSourceHandlerButReadsLiveSaving() {
        val original = mutableListOf<String>(); val current = mutableListOf<String>()
        val owner = InlineWaveformPointerOwner()
        owner.bind(binding(original)); owner.dispatch(event(), 100)
        owner.bind(binding(current, saving = true))
        owner.dispatch(event(x = 44f, wasPressed = true), 100)
        owner.dispatch(event(pressed = false, wasPressed = true), 100)
        assertEquals(listOf("begin:12.0/100.0", "move:44.0/100.0", "finish:true"), original)
        assertTrue(current.isEmpty())
    }

    @Test fun rejectedSavingDownWaitsForAllUpWithoutConsuming() {
        val calls = mutableListOf<String>(); val owner = InlineWaveformPointerOwner()
        owner.bind(binding(calls, saving = true))
        val down = event(); owner.dispatch(down, 100); assertFalse(down.changes.single().consumed)
        owner.bind(binding(calls))
        owner.dispatch(event(wasPressed = true), 100)
        owner.dispatch(event(pressed = false, wasPressed = true), 100)
        assertTrue(calls.isEmpty())
        owner.dispatch(event(id = 2), 100)
        assertEquals(listOf("begin:12.0/100.0"), calls)
    }

    @Test fun disappearingPointerAndExplicitDetachEachFinishOnlyTheirOwnedGesture() {
        val calls = mutableListOf<String>(); val owner = InlineWaveformPointerOwner()
        owner.bind(binding(calls)); owner.dispatch(event(), 100)
        owner.dispatch(event(id = 2, wasPressed = true), 100)
        owner.cancel(); owner.cancel()
        assertEquals(listOf("begin:12.0/100.0", "finish:false"), calls)
        owner.dispatch(event(id = 3), 100); owner.cancel(); owner.cancel()
        assertEquals(listOf("begin:12.0/100.0", "finish:false", "begin:12.0/100.0", "finish:false"), calls)
    }
    @Test fun detachedHandlerIsRecreatedFromTheLatestBinding() {
        val first = mutableListOf<String>(); val latest = mutableListOf<String>()
        val owner = InlineWaveformPointerOwner()
        owner.bind(binding(first)); owner.dispatch(event(), 100)
        owner.bind(binding(latest)); owner.detached()
        owner.dispatch(event(id = 2), 100)
        assertEquals(listOf("begin:12.0/100.0", "finish:false"), first)
        assertEquals(listOf("begin:12.0/100.0"), latest)
    }

    @Test fun secondaryMouseDownIsAdmittedWithoutPrimaryButtonRestriction() {
        val calls = mutableListOf<String>(); val owner = InlineWaveformPointerOwner()
        owner.bind(binding(calls))
        val down = NativeDragPointerEvent(listOf(NativeDragPointerChange(7, 1L,
            Offset(36f, 0f), Offset.Zero, true, false, PointerType.Mouse, Offset(36f, 0f))),
            Offset.Zero, primaryMouseButtonPressed = false)
        owner.dispatch(down, 100)
        assertTrue(down.changes.single().consumed)
        val up = NativeDragPointerEvent(listOf(NativeDragPointerChange(7, 2L,
            Offset(36f, 0f), Offset(36f, 0f), false, true, PointerType.Mouse, Offset(36f, 0f))),
            Offset.Zero, primaryMouseButtonPressed = false)
        owner.dispatch(up, 100)
        assertEquals(listOf("begin:36.0/100.0", "finish:true"), calls)
    }

}
