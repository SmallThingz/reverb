package app.smallthingz.reverb.nativeui

import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.input.pointer.PointerType
import app.smallthingz.reverb.NativeDragPointerChange
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePointerPass
import org.junit.Assert.*
import org.junit.Test

class SettingsCalibrationGestureTest {
    @Test fun startsOnDownAndShortReleaseConvertsSameSampleToTimed() {
        val fixture=Fixture()
        fixture.initial(fixture.change(0,true,false))
        assertEquals(listOf("down","begin"),fixture.events)
        val up=fixture.change(100,false,true)
        fixture.initial(up)
        assertTrue(up.consumed)
        assertEquals(listOf("down","begin","tap","up"),fixture.events)
    }

    @Test fun longReleaseFinishesHeldWithoutStartingTimedSample() {
        val fixture=Fixture()
        fixture.initial(fixture.change(0,true,false))
        fixture.initial(fixture.change(500,false,true))
        assertEquals(listOf("down","begin","finish","up"),fixture.events)
    }

    @Test fun parentScrollConsumptionCancelsOnceAndStillConsumesRelease() {
        val fixture=Fixture()
        fixture.initial(fixture.change(0,true,false))
        val move=fixture.change(20,true,true,x=12f,previousX=10f)
        fixture.initial(move)
        move.consume()
        fixture.dispatchFinal(move)
        val up=fixture.change(40,false,true,x=12f,previousX=12f)
        fixture.initial(up)
        assertTrue(up.consumed)
        assertEquals(listOf("down","begin","cancel","up"),fixture.events)
    }

    @Test fun secondPointerCancelsAndCannotBeginAnotherBeforeAllPointersRelease() {
        val fixture=Fixture()
        fixture.initial(fixture.change(0,true,false))
        val first=fixture.change(10,true,true)
        val second=fixture.change(10,true,false,id=2)
        fixture.initial(first,second)
        fixture.initial(fixture.change(20,false,true),fixture.change(20,true,true,id=2))
        fixture.initial(fixture.change(30,true,false,id=3),fixture.change(30,true,true,id=2))
        assertEquals(listOf("down","begin","cancel","up"),fixture.events)
        fixture.initial(fixture.change(40,false,true,id=2),fixture.change(40,false,true,id=3))
        fixture.initial(fixture.change(50,true,false,id=4))
        assertEquals(2,fixture.events.count {it=="begin"})
    }

    @Test fun declinedBeginStillSuppressesButtonClickButDoesNotCancelUnownedSample() {
        val fixture=Fixture().apply {accepted=false}
        fixture.initial(fixture.change(0,true,false))
        val up=fixture.change(100,false,true)
        fixture.initial(up)
        assertTrue(up.consumed)
        assertEquals(listOf("down","begin","up"),fixture.events)
    }

    @Test fun detachCancellationRetiresOwnedSampleAndPointerStateExactlyOnce() {
        val fixture=Fixture()
        fixture.initial(fixture.change(0,true,false))
        fixture.gesture.cancel();fixture.gesture.cancel()
        assertFalse(fixture.gesture.pointerDown)
        assertEquals(listOf("down","begin","cancel","up"),fixture.events)
    }

    private class Fixture {
        val events=mutableListOf<String>()
        var accepted=true
        val gesture=SettingsCalibrationGesture({true},{events.add("begin");accepted},
            {events.add("tap")},{events.add("finish")},{events.add("cancel")},
            {events.add(if(it)"down"else"up")},{100},{48},{8f},{500L})
        fun change(time:Long,pressed:Boolean,previous:Boolean,x:Float=10f,previousX:Float=x,id:Long=1)=
            NativeDragPointerChange(id,time,Offset(x,10f),Offset(previousX,10f),pressed,previous,
                PointerType.Touch,Offset(x,10f))
        fun initial(vararg changes:NativeDragPointerChange)=gesture.dispatch(
            NativeDragPointerEvent(changes.toList(),Offset.Zero),NativePointerPass.INITIAL)
        fun dispatchFinal(vararg changes:NativeDragPointerChange)=gesture.dispatch(
            NativeDragPointerEvent(changes.toList(),Offset.Zero),NativePointerPass.FINAL)
    }
}
