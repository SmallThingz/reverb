package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Range admission is independent of real platform node/event delivery. */
class NativeVerticalAccessibilityTest {
    @Test fun forwardAtStartUsesViewportEvenWhenRemainingRangeIsShorter() {
        assertEquals(80f, nativeVerticalAccessibilityDelta(
            NativeVerticalAccessibilityDirection.FORWARD, 80f, 0, 20, true)!!, 0f)
        assertNull(nativeVerticalAccessibilityDelta(
            NativeVerticalAccessibilityDirection.BACKWARD, 80f, 0, 20, true))
    }

    @Test fun rangeEndOnlyAdmitsBackwardAndKeepsItsSign() {
        assertNull(nativeVerticalAccessibilityDelta(
            NativeVerticalAccessibilityDirection.FORWARD, 80f, 20, 20, true))
        assertEquals(-80f, nativeVerticalAccessibilityDelta(
            NativeVerticalAccessibilityDirection.BACKWARD, 80f, 20, 20, true)!!, 0f)
    }

    @Test fun disabledZeroViewportAndEmptyRangeAdmitNoAction() {
        for (direction in NativeVerticalAccessibilityDirection.entries) {
            assertNull(nativeVerticalAccessibilityDelta(direction, 80f, 10, 20, false))
            assertNull(nativeVerticalAccessibilityDelta(direction, 0f, 10, 20, true))
            assertNull(nativeVerticalAccessibilityDelta(direction, 80f, 0, 0, true))
        }
    }
}
