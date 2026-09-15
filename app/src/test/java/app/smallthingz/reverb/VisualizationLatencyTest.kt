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
            appUiForeground = true,
        )
        val interactiveBytes = captureReadByteCount(
            sampleRate = 48_000,
            frameBytes = 2,
            capacityBytes = ReverbService.CAPTURE_SCRATCH_BYTES,
            visualizationActive = false,
            appUiForeground = true,
        )

        assertEquals(768, visibleBytes)
        assertEquals(3_840, interactiveBytes)
        assertTrue(visibleBytes < interactiveBytes)
    }

    @Test
    fun lowLatencyCaptureSizing_staysFrameAlignedAtMaximumInputFormat() {
        val bytes = captureReadByteCount(
            sampleRate = 192_000,
            frameBytes = 8,
            capacityBytes = ReverbService.CAPTURE_SCRATCH_BYTES,
            visualizationActive = true,
            appUiForeground = true,
        )

        assertEquals(12_288, bytes)
        assertEquals(0, bytes % 8)
        assertTrue(bytes <= ReverbService.CAPTURE_SCRATCH_BYTES)
    }
    @Test
    fun uiBackgroundAlwaysUsesOneSecondCaptureBatches() {
        val bytes = captureReadByteCount(
            sampleRate = 48_000,
            frameBytes = 2,
            capacityBytes = ReverbService.CAPTURE_SCRATCH_BYTES,
            visualizationActive = false,
            appUiForeground = false,
        )

        assertEquals(96_000, bytes)
    }

    @Test
    fun backgroundBatchFitsLargestSupportedPcmRate() {
        val bytes = captureReadByteCount(
            sampleRate = 96_000,
            frameBytes = 8,
            capacityBytes = ReverbService.CAPTURE_SCRATCH_BYTES,
            visualizationActive = false,
            appUiForeground = false,
        )

        assertEquals(768_000, bytes)
        assertTrue(bytes <= ReverbService.CAPTURE_SCRATCH_BYTES)
    }

}
