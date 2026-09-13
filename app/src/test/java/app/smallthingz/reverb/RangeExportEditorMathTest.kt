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
    fun snappedTargetsCanMoveAwayWithSubThresholdFineSteps() {
        val cursorLeavesStart = adjustRangeEditTarget(
            values = RangeEditValues(startSeconds = 0f, cursorSeconds = 0f, endSeconds = 100f),
            target = RangeEditTarget.CURSOR,
            requestedSeconds = 0.01f,
            durationSeconds = 100f,
            snapThresholdSeconds = 0.04f,
        )
        assertEquals(0.01f, cursorLeavesStart.values.cursorSeconds, 0f)
        assertEquals(null, cursorLeavesStart.snappedTo)

        val cursorKeepsLeavingStart = adjustRangeEditTarget(
            values = cursorLeavesStart.values,
            target = RangeEditTarget.CURSOR,
            requestedSeconds = 0.02f,
            durationSeconds = 100f,
            snapThresholdSeconds = 0.04f,
        )
        assertEquals(0.02f, cursorKeepsLeavingStart.values.cursorSeconds, 0f)
        assertEquals(null, cursorKeepsLeavingStart.snappedTo)

        val cursorApproachesStart = adjustRangeEditTarget(
            values = RangeEditValues(startSeconds = 0f, cursorSeconds = 0.2f, endSeconds = 100f),
            target = RangeEditTarget.CURSOR,
            requestedSeconds = 0.01f,
            durationSeconds = 100f,
            snapThresholdSeconds = 0.04f,
        )
        assertEquals(0f, cursorApproachesStart.values.cursorSeconds, 0f)
        assertEquals(RangeEditTarget.START, cursorApproachesStart.snappedTo)
    }

    @Test
    fun fineTuneHasNearZeroDeadZoneAndAggressiveHorizontalEdges() {
        assertEquals(0f, rangeFineTuneTimelineRate(0f, 0f), 0f)
        assertTrue(rangeFineTuneTimelineRate(0.006f, 0f) > 0f)
        assertTrue(rangeFineTuneTimelineRate(-0.006f, 0f) < 0f)
        val near = rangeFineTuneTimelineRate(0.05f, 0f)
        val middle = rangeFineTuneTimelineRate(0.5f, 0f)
        val edge = rangeFineTuneTimelineRate(1f, 0f)
        assertTrue(middle > near * 2f)
        assertTrue(edge > middle * 8f)
    }

    @Test
    fun fineTuneVerticalAxisControlsPrecisionAndSpeed() {
        val center = rangeFineTuneTimelineRate(0.45f, 0f)
        val fastUp = rangeFineTuneTimelineRate(0.45f, -0.65f)
        val preciseDown = rangeFineTuneTimelineRate(0.45f, 0.65f)
        assertTrue(fastUp > center * 2f)
        assertTrue(preciseDown < center * 0.35f)
        assertTrue(rangeFineTuneTimelineRate(-0.45f, -0.65f) < -center * 2f)
    }

    @Test
    fun fineTuneMovementIsTimelineRelative() {
        val shortDelta = rangeFineTuneDeltaSeconds(
            horizontalPull = 0.6f,
            verticalPull = 0f,
            durationSeconds = 30f,
            dtSeconds = 0.25f,
        )
        val longDelta = rangeFineTuneDeltaSeconds(
            horizontalPull = 0.6f,
            verticalPull = 0f,
            durationSeconds = 300f,
            dtSeconds = 0.25f,
        )
        assertTrue(shortDelta > 0f)
        assertEquals(shortDelta * 10f, longDelta, 0.0001f)
        assertEquals(
            shortDelta / 30f,
            longDelta / 300f,
            0.000001f,
        )
    }

    @Test
    fun fineTuneGestureSensitivityFavorsHorizontalOverVertical() {
        val horizontal = rangeFineTuneDragPull(
            startHorizontal = 0f,
            startRawVertical = 0f,
            dragDeltaX = 30f,
            dragDeltaY = 0f,
            horizontalTravel = 100f * 0.62f,
            verticalTravel = 40f * 2.35f,
        )
        val vertical = rangeFineTuneDragPull(
            startHorizontal = 0f,
            startRawVertical = 0f,
            dragDeltaX = 0f,
            dragDeltaY = 30f,
            horizontalTravel = 100f * 0.62f,
            verticalTravel = 40f * 2.35f,
        )
        assertTrue(horizontal.horizontal > 0.45f)
        assertTrue(vertical.rawVertical < 0.35f)
    }

    @Test
    fun fineTuneVerticalForceFieldGetsStifferNearHorizontalEdges() {
        val rawY = 0.9f
        val centerY = rangeFineTuneConstrainedY(rawY, 0f)
        val edgeY = rangeFineTuneConstrainedY(rawY, 0.95f)
        assertTrue(centerY > 0f)
        assertTrue(edgeY > 0f)
        assertTrue(edgeY < centerY)
        assertTrue(rangeFineTuneConstrainedY(-rawY, 0.95f) < 0f)
    }

    @Test
    fun fineTuneDragUsesRelativeDeltaWithoutSnappingToPointer() {
        val unchanged = rangeFineTuneDragPull(
            startHorizontal = 0.31f,
            startRawVertical = -0.22f,
            dragDeltaX = 0f,
            dragDeltaY = 0f,
            horizontalTravel = 100f,
            verticalTravel = 40f,
        )
        assertEquals(0.31f, unchanged.horizontal, 0f)
        assertEquals(-0.22f, unchanged.rawVertical, 0f)

        val moved = rangeFineTuneDragPull(
            startHorizontal = 0.31f,
            startRawVertical = -0.22f,
            dragDeltaX = 10f,
            dragDeltaY = 8f,
            horizontalTravel = 100f,
            verticalTravel = 40f,
        )
        assertEquals(0.41f, moved.horizontal, 0.0001f)
        assertEquals(-0.02f, moved.rawVertical, 0.0001f)
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
