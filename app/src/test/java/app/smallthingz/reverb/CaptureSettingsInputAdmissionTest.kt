package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class CaptureSettingsInputAdmissionTest {
    private val retention = RetentionConfiguration(RetentionMode.SIZE, 60, 1024, 60, 2048)
    private fun mayResume(
        next: RetentionConfiguration = retention,
        loop: Boolean = true,
        one: Boolean = true,
        inputChanged: Boolean = false,
        gateChanged: Boolean = false,
        recordingLoop: Boolean = true,
        oneShotUnchanged: Boolean = true,
    ) = captureSettingsMayResumeLoopingInput(next, loop, one, 4, inputChanged, gateChanged, recordingLoop, oneShotUnchanged)

    @Test fun loopingResizeKeepsNativeDrainWhenBothDestinationsStayEnabled() {
        assertTrue(mayResume())
        assertTrue(mayResume(retention.copy(loopingSizeBytes = 4096)))
        assertTrue(mayResume(retention.copy(mode = RetentionMode.TIME)))
        assertTrue(mayResume(retention.copy(oneShotSizeBytes = 0), one = false))
    }

    @Test fun inputGateAndOneShotBoundariesRetainPause() {
        assertFalse(mayResume(inputChanged = true))
        assertFalse(mayResume(gateChanged = true))
        assertFalse(mayResume(recordingLoop = false))
        assertFalse(mayResume(oneShotUnchanged = false))
    }

    @Test fun destinationEnablementUsesEffectiveFrameAlignedRetention() {
        assertFalse(mayResume(loop = false))
        assertFalse(mayResume(one = false))
        assertFalse(mayResume(retention.copy(loopingSizeBytes = 0)))
        assertFalse(mayResume(retention.copy(loopingSizeBytes = 3)))
        assertFalse(mayResume(retention.copy(oneShotSizeBytes = 0)))
        assertFalse(mayResume(retention.copy(oneShotSizeBytes = 3)))
        assertTrue(mayResume(retention.copy(oneShotSizeBytes = 3), one = false))
        assertFalse(mayResume(retention.copy(mode = RetentionMode.TIME, oneShotSeconds = 0)))
    }
}
