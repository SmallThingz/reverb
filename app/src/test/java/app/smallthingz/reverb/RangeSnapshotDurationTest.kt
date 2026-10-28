package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeSnapshotDurationTest {
    private val sampleRate = 48_000.0
    private val maximumFrames = 536_870_905L // WAV float stereo whole-frame payload limit.
    private val maximumSeconds = maximumFrames / sampleRate

    @Test
    fun fullSnapshotAtPayloadLimit_usesExactSnapshotEnd() {
        val renderedEnd = maximumSeconds.toFloat()
        assertTrue(renderedEnd.toDouble() > maximumSeconds)
        val selected = rangeSelectionDurationExactSeconds(0f, renderedEnd, maximumSeconds)
        assertEquals(maximumSeconds, selected, 0.0)
        assertTrue(rangeExportSelectionWithinLimit(selected, maximumSeconds))
    }

    @Test
    fun fullSnapshotOneFrameOverPayloadLimit_remainsRejected() {
        val available = (maximumFrames + 1) / sampleRate
        val selected = rangeSelectionDurationExactSeconds(0f, available.toFloat(), available)
        assertEquals(available, selected, 0.0)
        assertFalse(rangeExportSelectionWithinLimit(selected, maximumSeconds))
    }

    @Test
    fun interiorSelection_preservesExactFractionalEndpointSubtraction() {
        val start = 25_847.966796875f
        val end = 74_543.7421875f
        val maximum = 48_695.77382086168
        val selected = rangeSelectionDurationExactSeconds(start, end, 80_000.0)
        assertEquals(48_695.775390625, selected, 0.0)
        assertFalse(rangeExportSelectionWithinLimit(selected, maximum))
    }

    @Test
    fun nonzeroStartAtSnapshotEnd_matchesServiceEndpointClamping() {
        val selected = rangeSelectionDurationExactSeconds(0.25f, maximumSeconds.toFloat(), maximumSeconds)
        assertEquals(maximumSeconds - 0.25, selected, 0.0)
    }

    @Test
    fun unavailableOrEmptySnapshot_neverInventsSelection() {
        for (available in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(0.0, rangeSelectionDurationExactSeconds(0f, 1f, available), 0.0)
        }
        assertEquals(0.0, rangeSelectionDurationExactSeconds(2f, 3f, 1.0), 0.0)
        assertEquals(0.0, rangeSelectionDurationExactSeconds(1f, 0f, 2.0), 0.0)
    }
}
