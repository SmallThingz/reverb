package app.smallthingz.reverb

import app.smallthingz.reverb.nativeui.CaptureSaveStatusMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePendingSavePresentationTest {
    @Test fun elapsedAnimationAndStaleIdlePollCannotDismissUnacknowledgedSave() {
        var saving = true
        var status: CaptureSaveStatus? = CaptureSaveStatus.Saving(false)
        val motion = CaptureSaveStatusMotion(false)
        motion.setVisible(true)
        motion.measured(80)
        val gate = SaveUiCallbackGate(
            setSaving = { saving = it },
            onStatus = { status = it; motion.setVisible(it != null) },
            onError = {}, onSaved = {},
        )
        for (frame in 0..600) {
            status = reconcileCaptureExportStatus(false, gate.attached, status, false)
            saving = captureExportUiBusy(false, gate.attached, status)
            motion.setVisible(status != null)
            motion.frame(frame * 16_666_667L, 1f)
        }
        assertTrue(saving)
        assertTrue(motion.visibleTarget)
        assertFalse(motion.exitCompleted)
        assertEquals(1f, motion.alpha, .001f)

        // This callback is emitted only after source pins and the queued manifest are durable.
        gate.queued()
        assertFalse(saving)
        assertFalse(motion.visibleTarget)
        for (frame in 601..720) motion.frame(frame * 16_666_667L, 1f)
        assertTrue(motion.exitCompleted)
    }
}
