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
            boundClientPresent = true,
            deviceInteractive = true,
        )
        val interactiveBytes = captureReadByteCount(
            sampleRate = 48_000,
            frameBytes = 2,
            capacityBytes = ReverbService.CAPTURE_SCRATCH_BYTES,
            visualizationActive = false,
            boundClientPresent = true,
            deviceInteractive = true,
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
            boundClientPresent = true,
            deviceInteractive = true,
        )

        assertEquals(12_288, bytes)
        assertEquals(0, bytes % 8)
        assertTrue(bytes <= ReverbService.CAPTURE_SCRATCH_BYTES)
    }
    @Test
    fun unboundBackground_batchesCaptureWorkAggressively() {
        val screenOnBytes = captureReadByteCount(
            sampleRate = 48_000,
            frameBytes = 2,
            capacityBytes = ReverbService.CAPTURE_SCRATCH_BYTES,
            visualizationActive = false,
            boundClientPresent = false,
            deviceInteractive = true,
        )
        val screenOffBytes = captureReadByteCount(
            sampleRate = 48_000,
            frameBytes = 2,
            capacityBytes = ReverbService.CAPTURE_SCRATCH_BYTES,
            visualizationActive = false,
            boundClientPresent = false,
            deviceInteractive = false,
        )

        assertEquals(24_000, screenOnBytes)
        assertEquals(96_000, screenOffBytes)
        assertEquals(4, screenOffBytes / screenOnBytes)
    }

    @Test
    fun screenOffBatchFitsLargestSupportedPcmRate() {
        val bytes = captureReadByteCount(
            sampleRate = 96_000,
            frameBytes = 8,
            capacityBytes = ReverbService.CAPTURE_SCRATCH_BYTES,
            visualizationActive = false,
            boundClientPresent = false,
            deviceInteractive = false,
        )

        assertEquals(768_000, bytes)
        assertTrue(bytes <= ReverbService.CAPTURE_SCRATCH_BYTES)
    }

}
