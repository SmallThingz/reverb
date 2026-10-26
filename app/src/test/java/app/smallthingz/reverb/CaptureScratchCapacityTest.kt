package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureScratchCapacityTest {
    @Test
    fun fortyEightKhzMonoPcm16NeedsOnlyOneSecondOfStorage() {
        assertEquals(96_000, captureScratchCapacityBytes(48_000, 2))
        assertEquals(192_000, captureScratchCapacityBytes(48_000, 2) * 2)
    }

    @Test
    fun supportedFormatsKeepEveryExistingForegroundAndBackgroundReadSize() {
        for (rate in standardSampleRates()) {
            for (channels in ChannelMode.entries) {
                for (format in PcmSampleFormat.entries) {
                    val frameBytes = channels.channelCount * format.bytesPerSample
                    val capacity = captureScratchCapacityBytes(rate, frameBytes)
                    assertTrue(capacity >= frameBytes)
                    assertTrue(capacity <= ReverbService.CAPTURE_SCRATCH_BYTES)
                    assertEquals(0, capacity % frameBytes)
                    for (visualizer in listOf(false, true)) {
                        for (foreground in listOf(false, true)) {
                            val original = captureReadByteCount(rate, frameBytes,
                                ReverbService.CAPTURE_SCRATCH_BYTES, visualizer, foreground)
                            val resized = captureReadByteCount(rate, frameBytes,
                                capacity, visualizer, foreground)
                            assertEquals("$rate Hz ${channels.name} ${format.name} visualizer=$visualizer foreground=$foreground",
                                original, resized)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun capacityRetainsExistingCapAndFrameAlignmentAtHighRates() {
        assertEquals(768_000, captureScratchCapacityBytes(96_000, 8))
        assertEquals(786_432, captureScratchCapacityBytes(192_000, 8))
        assertEquals(786_429, captureScratchCapacityBytes(192_000, 7))
        assertEquals(786_432, captureScratchCapacityBytes(Int.MAX_VALUE, 8))
        for (frameBytes in listOf(7, 8)) {
            val capacity = captureScratchCapacityBytes(192_000, frameBytes)
            for (visualizer in listOf(false, true)) {
                for (foreground in listOf(false, true)) {
                    assertEquals(captureReadByteCount(192_000, frameBytes,
                        ReverbService.CAPTURE_SCRATCH_BYTES, visualizer, foreground),
                        captureReadByteCount(192_000, frameBytes, capacity, visualizer, foreground))
                }
            }
        }
    }

    @Test
    fun minimumInputsRetainAtLeastOneCompleteFrame() {
        assertEquals(1, captureScratchCapacityBytes(0, 0))
        assertEquals(1, captureScratchCapacityBytes(-1, -1))
        assertEquals(8, captureScratchCapacityBytes(1, 8))
        assertEquals(7_350, captureScratchCapacityBytes(7_350, 1))
    }
}
