package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeExportEditorMathTest {
    @Test
    fun cursorRemainsIndependentOfSelectedRange() {
        val update = adjustRangeEditTarget(
            values = RangeEditValues(startSeconds = 20f, cursorSeconds = 30f, endSeconds = 80f),
            target = RangeEditTarget.CURSOR,
            requestedSeconds = 5f,
            durationSeconds = 100f,
            snapThresholdSeconds = 1f,
        )
        assertEquals(20f, update.values.startSeconds, 0f)
        assertEquals(5f, update.values.cursorSeconds, 0f)
        assertEquals(80f, update.values.endSeconds, 0f)
        assertEquals(null, update.snappedTo)
    }

    @Test
    fun cursorAndRangeEdgesSnapBidirectionally() {
        val cursorToStart = adjustRangeEditTarget(
            RangeEditValues(20f, 40f, 80f),
            RangeEditTarget.CURSOR,
            requestedSeconds = 20.4f,
            durationSeconds = 100f,
            snapThresholdSeconds = 0.5f,
        )
        assertEquals(20f, cursorToStart.values.cursorSeconds, 0f)
        assertEquals(RangeEditTarget.START, cursorToStart.snappedTo)

        val startToCursor = adjustRangeEditTarget(
            RangeEditValues(20f, 40f, 80f),
            RangeEditTarget.START,
            requestedSeconds = 39.7f,
            durationSeconds = 100f,
            snapThresholdSeconds = 0.5f,
        )
        assertEquals(40f, startToCursor.values.startSeconds, 0f)
        assertEquals(RangeEditTarget.CURSOR, startToCursor.snappedTo)
    }

    @Test
    fun fineTuneHasNearZeroDeadZoneAndAggressiveEdges() {
        assertEquals(0f, rangeFineTuneVelocity(0f), 0f)
        assertTrue(rangeFineTuneVelocity(0.006f) > 0f)
        assertTrue(rangeFineTuneVelocity(-0.006f) < 0f)
        val near = rangeFineTuneVelocity(0.05f)
        val middle = rangeFineTuneVelocity(0.5f)
        val edge = rangeFineTuneVelocity(1f)
        assertTrue(middle > near * 2f)
        assertTrue(edge > middle * 10f)
    }

    @Test
    fun waveformNormalizationIsBoundedAndPreservesShape() {
        val normalized = normalizeWaveformEnvelope(floatArrayOf(0f, 0.25f, 1f, 0.25f, 0f))
        assertEquals(5, normalized.size)
        assertTrue(normalized.all { it in 0f..1f })
        assertTrue(normalized[2] > normalized[1])
        assertTrue(normalized[1] > normalized[0])
    }
}
