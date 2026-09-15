package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualizationLatencyTest {
    @Test
    fun visibleVisualizer_usesEightMillisecondCaptureSlices() {
        val visibleBytes = captureReadByteCount(
            sampleRate = 48_000,
            frameBytes = 2,
            capacityBytes = ReverbService.CAPTURE_SCRATCH_BYTES,
            visualizationActive = true,
        )
        val backgroundBytes = captureReadByteCount(
            sampleRate = 48_000,
            frameBytes = 2,
            capacityBytes = ReverbService.CAPTURE_SCRATCH_BYTES,
            visualizationActive = false,
        )

        assertEquals(768, visibleBytes)
        assertEquals(3_840, backgroundBytes)
        assertTrue(visibleBytes < backgroundBytes)
    }

    @Test
    fun lowLatencyCaptureSizing_staysFrameAlignedAtMaximumInputFormat() {
        val bytes = captureReadByteCount(
            sampleRate = 192_000,
            frameBytes = 8,
            capacityBytes = ReverbService.CAPTURE_SCRATCH_BYTES,
            visualizationActive = true,
        )

        assertEquals(12_288, bytes)
        assertEquals(0, bytes % 8)
        assertTrue(bytes <= ReverbService.CAPTURE_SCRATCH_BYTES)
    }
}
