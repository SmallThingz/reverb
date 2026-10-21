package app.smallthingz.reverb

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeMotionEventSubsetTest {
    @Test fun removedActionPointerBecomesMove() {
        assertEquals(MotionEvent.ACTION_MOVE,
            nativePointerSubsetAction(MotionEvent.ACTION_POINTER_DOWN or (2 shl 8), 0, intArrayOf(0, 1)))
    }

    @Test fun retainedActionPointerIndexIsRemappedInSourceOrder() {
        assertEquals(MotionEvent.ACTION_POINTER_UP or (1 shl 8),
            nativePointerSubsetAction(MotionEvent.ACTION_POINTER_UP or (3 shl 8), 0, intArrayOf(1, 3)))
    }

    @Test fun singleRetainedActionPointerBecomesDownOrUp() {
        assertEquals(MotionEvent.ACTION_DOWN,
            nativePointerSubsetAction(MotionEvent.ACTION_POINTER_DOWN or (2 shl 8), 0, intArrayOf(2)))
        assertEquals(MotionEvent.ACTION_UP,
            nativePointerSubsetAction(MotionEvent.ACTION_POINTER_UP or (2 shl 8), 0, intArrayOf(2)))
    }

    @Test fun canceledLastPointerBecomesCancelOnlyWhenThatPointerIsRetained() {
        assertEquals(MotionEvent.ACTION_CANCEL,
            nativePointerSubsetAction(MotionEvent.ACTION_POINTER_UP or (1 shl 8), MotionEvent.FLAG_CANCELED, intArrayOf(1)))
        assertEquals(MotionEvent.ACTION_MOVE,
            nativePointerSubsetAction(MotionEvent.ACTION_POINTER_UP or (1 shl 8), MotionEvent.FLAG_CANCELED, intArrayOf(0)))
        assertEquals(MotionEvent.ACTION_POINTER_UP or (1 shl 8),
            nativePointerSubsetAction(MotionEvent.ACTION_POINTER_UP or (2 shl 8), MotionEvent.FLAG_CANCELED, intArrayOf(0, 2)))
    }

    @Test fun nonPointerActionsRemainUnchanged() {
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
            assertEquals(action, nativePointerSubsetAction(action, 0, intArrayOf(0)))
        }
    }
}
