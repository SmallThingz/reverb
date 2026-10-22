package app.smallthingz.reverb.nativeui

import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.input.pointer.PointerType
import app.smallthingz.reverb.NativeDragPointerChange
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePointerConsumption
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.RangeDurationWheelInteraction
import app.smallthingz.reverb.RangeEditTarget
import app.smallthingz.reverb.RangeInputGesture
import org.junit.Assert.*
import org.junit.Test

class NativeRangeFineSeekStateTest {
    private val geometry=SpringFineSeekGeometry(300,120,1f)

    @Test fun initialDownOwnsTapBeforeChildMainAndUpHasOneTerminalOwner() {
        val h=Harness()
        val down=event(150f,60f,true,false)
        h.send(down,NativePointerPass.INITIAL)
        assertTrue(down.changes.single().consumed)
        assertFalse(h.state.dragging)
        h.send(down,NativePointerPass.MAIN)
        assertTrue(h.events.isEmpty())
        h.send(down,NativePointerPass.FINAL)
        val up=event(150f,60f,false,true)
        h.all(up)
        assertTrue(up.changes.single().consumed)
        assertEquals(listOf("old:toggle"),h.events)
        h.state.cancel()
        assertEquals(listOf("old:toggle"),h.events)
    }

    @Test fun rejectedRangeDownIsConsumedAndNeverFallsThroughToChildClick() {
        val h=Harness()
        h.allowed=false
        val down=event(150f,60f,true,false)
        h.all(down)
        assertTrue(down.changes.single().consumed)
        assertEquals(listOf("old:cancel"),h.events)
        h.all(event(150f,60f,false,true))
        assertEquals(listOf("old:cancel"),h.events)
    }

    @Test fun outsidePuckDragStartsAfterTheDownFinalPass() {
        val h=Harness()
        val down=event(260f,60f,true,false)
        h.send(down,NativePointerPass.INITIAL)
        h.send(down,NativePointerPass.MAIN)
        assertFalse(h.state.dragging)
        assertTrue(h.events.isEmpty())
        h.send(down,NativePointerPass.FINAL)
        assertTrue(h.state.dragging)
        assertEquals(listOf("old:start","old:begin"),h.events)
        h.all(event(260f,60f,false,true))
        assertEquals(listOf("old:start","old:begin","old:end"),h.events)
    }

    @Test fun completedWheelGenerationInvalidatesOldTapWithoutPausingNewPreview() {
        val h=Harness()
        h.all(event(150f,60f,true,false))
        h.wheel=h.wheel.begin(RangeEditTarget.END).end()
        h.all(event(150f,60f,false,true))
        assertTrue(h.events.isEmpty())
        assertFalse(h.state.dragging)
    }

    @Test fun outOfBoundsThenReturnCannotRestoreTapAdmission() {
        val h=Harness(slop=100f)
        h.all(event(150f,60f,true,false))
        h.all(event(183f,60f,true,true))
        h.all(event(150f,60f,true,true))
        h.all(event(150f,60f,false,true))
        assertTrue(h.events.isEmpty())
        assertFalse(h.state.dragging)
    }

    @Test fun consumedMovementKeepsTapCancelledEvenWhenReleaseIsUnconsumed() {
        val h=Harness(slop=100f)
        h.all(event(150f,60f,true,false))
        h.all(event(151f,60f,true,true,consumed=true))
        h.all(event(150f,60f,false,true))
        assertTrue(h.events.isEmpty())
    }

    @Test fun sameKeyRebindKeepsAcceptedTapCallbackAndReadsLatestCancellationCallback() {
        val h=Harness()
        h.all(event(150f,60f,true,false))
        h.state.bind(h.binding("new"),10f)
        h.all(event(150f,60f,false,true))
        assertEquals(listOf("old:toggle"),h.events)
        h.all(event(150f,60f,true,false))
        h.state.bind(h.binding("latest"),10f)
        h.state.cancel()
        assertEquals(listOf("old:toggle","latest:cancel"),h.events)
    }

    @Test fun disabledRangeWithoutPointerOwnerDoesNotInventPreviewCancellation() {
        val h=Harness(enabled=false)
        assertTrue(h.events.isEmpty())
        h.state.bind(h.binding("new",enabled=false),10f)
        assertTrue(h.events.isEmpty())
        h.state.bind(h.binding("ready"),10f)
        h.all(event(150f,60f,true,false))
        h.state.bind(h.binding("disabled",enabled=false),10f)
        assertEquals(listOf("disabled:cancel"),h.events)
    }

    private inner class Harness(val slop:Float=10f,enabled:Boolean=true) {
        val events=mutableListOf<String>()
        var allowed=true
        var wheel=RangeDurationWheelInteraction()
        val state=NativeSpringFineSeekState({}, {})
        init {state.bind(binding("old",enabled),slop)}
        fun binding(label:String,enabled:Boolean=true)=SpringFineSeekBinding(
            enabled,false,100f,"range-start",
            onTogglePlayback={events+="$label:toggle"},
            onInteractionStart={events+="$label:start"},
            onBeginFineAdjust={events+="$label:begin"},
            onFineAdjust={_,_->events+="$label:commit"},
            onUpdateFineAdjustShuttle={_,_->events+="$label:shuttle"},
            onEndFineAdjust={events+="$label:end"},
            onCancelFineAdjust={events+="$label:cancel"},
            createInputGesture={cancel->RangeInputGesture({allowed},{wheel},cancel)},
        )
        fun send(event:NativeDragPointerEvent,pass:NativePointerPass)=state.pointer(event,pass,geometry)
        fun all(event:NativeDragPointerEvent) {
            send(event,NativePointerPass.INITIAL)
            send(event,NativePointerPass.MAIN)
            send(event,NativePointerPass.FINAL)
        }
    }
    private fun event(x:Float,y:Float,pressed:Boolean,previouslyPressed:Boolean,consumed:Boolean=false)=
        NativeDragPointerEvent(listOf(NativeDragPointerChange(1,0L,Offset(x,y),Offset(x,y),
            pressed,previouslyPressed,PointerType.Touch,Offset(x,y),consumption=NativePointerConsumption(consumed))),Offset.Zero)
}
