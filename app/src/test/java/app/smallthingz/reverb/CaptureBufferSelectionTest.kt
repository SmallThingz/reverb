package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureBufferSelectionTest {
    private val oneShot = ReverbService.BufferSlot.ONE_SHOT
    private val looping = ReverbService.BufferSlot.LOOPING

    @Test
    fun externalRunningBufferSwitch_followsNewActiveBuffer() {
        assertEquals(
            oneShot,
            captureSelectedBufferAfterRecorderState(
                currentSelection = looping,
                wasListening = true,
                previousActiveBuffer = looping,
                listening = true,
                activeBuffer = oneShot,
            ),
        )
    }

    @Test
    fun externalStart_followsStartedBuffer() {
        assertEquals(
            looping,
            captureSelectedBufferAfterRecorderState(
                currentSelection = oneShot,
                wasListening = false,
                previousActiveBuffer = looping,
                listening = true,
                activeBuffer = looping,
            ),
        )
    }

    @Test
    fun steadyRecordingPoll_preservesManualBrowsingSelection() {
        assertEquals(
            looping,
            captureSelectedBufferAfterRecorderState(
                currentSelection = looping,
                wasListening = true,
                previousActiveBuffer = oneShot,
                listening = true,
                activeBuffer = oneShot,
            ),
        )
    }

    @Test
    fun stoppedRecorderState_preservesCurrentSelection() {
        assertEquals(
            oneShot,
            captureSelectedBufferAfterRecorderState(
                currentSelection = oneShot,
                wasListening = true,
                previousActiveBuffer = looping,
                listening = false,
                activeBuffer = looping,
            ),
        )
    }

    @Test
    fun unavailableActiveBuffer_preservesCurrentSelection() {
        assertEquals(
            looping,
            captureSelectedBufferAfterRecorderState(
                currentSelection = looping,
                wasListening = false,
                previousActiveBuffer = null,
                listening = true,
                activeBuffer = null,
            ),
        )
    }
}
