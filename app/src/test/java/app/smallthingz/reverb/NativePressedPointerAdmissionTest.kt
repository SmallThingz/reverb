package app.smallthingz.reverb

import android.view.InputDevice
import android.view.MotionEvent
import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.pointer.PointerType
import org.junit.Assert.*
import org.junit.Test

class NativePressedPointerAdmissionTest {
    @Test fun legacyRootsKeepMouseOutsideTheirLane() {
        val lane = NativePressedPointerAdmission(false)
        assertFalse(lane.accepts(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_MOUSE, true, MotionEvent.BUTTON_PRIMARY, false))
        assertTrue(lane.accepts(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHSCREEN, false, 0, false))
        assertTrue(lane.accepts(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_STYLUS, false, 0, false))
    }

    @Test fun secondaryButtonStreamKeepsMoveAndZeroButtonUp() {
        val lane = NativePressedPointerAdmission(true)
        assertTrue(lane.accepts(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_MOUSE, true, MotionEvent.BUTTON_SECONDARY, false))
        assertTrue(lane.accepts(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_MOUSE, true, MotionEvent.BUTTON_SECONDARY, false))
        assertTrue(lane.accepts(MotionEvent.ACTION_UP, InputDevice.SOURCE_MOUSE, true, 0, false))
        assertFalse(lane.accepts(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_MOUSE, true, 0, false))
    }

    @Test fun trackpadButtonWithFingerToolIsAButtonStreamButFakeFingerIsNot() {
        val lane = NativePressedPointerAdmission(true)
        assertTrue(lane.accepts(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHPAD, false, MotionEvent.BUTTON_PRIMARY, false))
        assertTrue(lane.accepts(MotionEvent.ACTION_UP, InputDevice.SOURCE_TOUCHPAD, false, 0, false))
        assertFalse(lane.accepts(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHPAD, false, 0, false))
        assertFalse(lane.accepts(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_TOUCHPAD, false, 0, false))
        assertFalse(lane.accepts(MotionEvent.ACTION_UP, InputDevice.SOURCE_TOUCHPAD, false, 0, false))
    }

    @Test fun indirectClassificationCannotBecomeAPressedMouseDrag() {
        val lane = NativePressedPointerAdmission(true)
        assertFalse(lane.accepts(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_MOUSE, false, MotionEvent.BUTTON_PRIMARY, true))
        assertFalse(lane.accepts(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_MOUSE, false, MotionEvent.BUTTON_PRIMARY, false))
        assertFalse(lane.accepts(MotionEvent.ACTION_UP, InputDevice.SOURCE_MOUSE, false, 0, false))
    }

    @Test fun hoverWheelAndCancelDoNotEnterPressedLaneAndResetRetiresMouseStream() {
        val lane = NativePressedPointerAdmission(true)
        assertTrue(lane.accepts(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_MOUSE, true, MotionEvent.BUTTON_PRIMARY, false))
        for (action in listOf(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_SCROLL, MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_CANCEL)) {
            assertFalse(lane.accepts(action, InputDevice.SOURCE_MOUSE, true, MotionEvent.BUTTON_PRIMARY, false))
        }
        lane.reset()
        assertFalse(lane.accepts(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_MOUSE, true, MotionEvent.BUTTON_PRIMARY, false))
    }

    @Test fun projectedMouseTypeAndPrimaryButtonStateRemainDistinctFromDownAdmission() {
        fun frame(primary: Boolean) = NativeRootPointerFrame(listOf(NativeRootPointerChange(
            7L, 2, 10L, Offset(11f, 21f), Offset(11f, 21f), true, false, PointerType.Mouse,
            Offset(1f, 1f), emptyList(),
        )), primary).project({ it - Offset(10f, 20f) }, Offset(10f, 20f))
        val secondary = frame(false)
        assertEquals(PointerType.Mouse, secondary.changes.single().type)
        assertFalse(secondary.primaryMouseButtonPressed)
        assertFalse(secondary.isDown())
        assertTrue(secondary.changes.single().pressed)
        val primary = frame(true)
        assertTrue(primary.primaryMouseButtonPressed)
        assertTrue(primary.isDown())
    }
}
