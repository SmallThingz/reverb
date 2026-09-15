package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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


    @Test
    fun uiForegroundOwners_staleDisposalCannotBackgroundNewActivity() {
        val tracker = UiForegroundOwnerTracker()
        val oldActivity = Any()
        val newActivity = Any()

        assertTrue(tracker.update(oldActivity, true))
        assertTrue(tracker.update(newActivity, true))
        assertTrue(tracker.update(oldActivity, false))
        assertEquals(1, tracker.size())
        assertFalse(tracker.update(newActivity, false))
    }

    @Test
    fun visualizerOwner_staleDisposalCannotClearReplacementCallback() {
        val registry = IdentityOwnerRegistry<Any>()
        val oldCallback = Any()
        val newCallback = Any()

        assertTrue(registry.register(oldCallback))
        assertTrue(registry.register(newCallback))
        assertFalse(registry.unregister(oldCallback))
        assertSame(newCallback, registry.current())
        assertTrue(registry.unregister(newCallback))
        assertNull(registry.current())
    }

    @Test
    fun visualizerOwner_activeDisposalFallsBackToStillVisibleClient() {
        val registry = IdentityOwnerRegistry<Any>()
        val first = Any()
        val second = Any()

        registry.register(first)
        registry.register(second)
        assertTrue(registry.unregister(second))
        assertSame(first, registry.current())
        assertEquals(1, registry.size())
    }

}
