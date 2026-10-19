package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class SilenceCurveGestureTest {
    private val original = SilenceGateConfig().points
    private val plot = SilenceCurvePlot(37f, 31f, 397f, 223f)
    private fun state(selected: Int = 1) = SilenceCurveGesture().apply { bind(original, selected, true) }
    private fun SilenceCurveGesture.grab(index: Int = 1, dx: Float = 0f, dy: Float = 0f) =
        begin(plot.x(points[index].hz) + dx, plot.y(points[index].db) + dy, plot, 32f, 8f)

    @Test fun repeatedMissesAndEmptyCurveDragsNeverInsertPoints() {
        val state = state()
        repeat(20) {
            assertFalse(state.begin(plot.x(650), plot.y(-5), plot, 32f, 8f))
            state.move(plot.x(1_000), plot.y(-30))
            assertNull(state.finish())
            assertEquals(original, state.points)
        }
        assertFalse(state.begin(plot.x(650), plot.y(-50), plot, 32f, 8f))
        assertEquals(original.size, state.points.size)
    }

    @Test fun nearEdgeGrabAndSubSlopMotionSelectWithoutChangingTheCurve() {
        val state = state(selected = 2)
        assertTrue(state.grab(dx = 24f, dy = 10f))
        state.move(plot.x(200) + 27f, plot.y(-50) + 12f)
        assertEquals(original, state.points)
        val result = requireNotNull(state.finish())
        assertFalse(result.changed)
        assertEquals(1, result.selected)
    }

    @Test fun dragRetainsGrabOffsetAndCanReverseSmoothlyAfterSlop() {
        val state = state()
        assertTrue(state.grab(dx = 23f, dy = -8f))
        state.move(plot.x(1_500) + 23f, plot.y(-20) - 8f)
        state.move(plot.x(900) + 23f, plot.y(-36) - 8f)
        val result = requireNotNull(state.finish())
        assertTrue(result.changed)
        assertEquals(SilencePoint(900, -36), result.points[1])
        assertEquals(original.size, result.points.size)
        assertEquals(original.filterIndexed { index, _ -> index != 1 }, result.points.filterIndexed { index, _ -> index != 1 })
    }

    @Test fun nearestHandleWinsWhenHitRegionsOverlap() {
        val points = listOf(SilencePoint(20, -50), SilencePoint(200, -50), SilencePoint(250, -50), SilencePoint(20_000, -50))
        val state = SilenceCurveGesture().apply { bind(points, 1, true) }
        assertTrue(state.begin(plot.x(250) - 1f, plot.y(-50), plot, 32f, 8f))
        assertEquals(2, requireNotNull(state.finish()).selected)
        assertEquals(points, state.points)
    }

    @Test fun horizontalCrossingClampsWithinNeighborsAndCutoffStaysInRange() {
        val state = state()
        assertTrue(state.grab())
        state.move(-10_000f, -10_000f)
        assertEquals(SilencePoint(21, 0), state.points[1])
        state.move(10_000f, 10_000f)
        assertEquals(SilencePoint(1_999, -96), requireNotNull(state.finish()).points[1])
        assertEquals(original.size, state.points.size)
    }

    @Test fun endpointFrequencyRemainsFixedDuringFreeVerticalMovement() {
        for (index in listOf(0, original.lastIndex)) {
            val state = state()
            assertTrue(state.grab(index))
            state.move(plot.x(original[index].hz) + 100f, plot.y(-30))
            val point = requireNotNull(state.finish()).points[index]
            assertEquals(original[index].hz, point.hz)
            assertEquals(-30, point.db)
        }
    }

    @Test fun cancelRestoresOriginalPointAndSelectionAndNextGestureWorks() {
        val state = state(selected = 2)
        assertTrue(state.grab())
        state.move(plot.x(900), plot.y(-20))
        state.cancel()
        assertEquals(original, state.points)
        assertEquals(2, state.selected)
        assertNull(state.finish())
        assertTrue(state.grab())
        state.move(plot.x(500), plot.y(-40))
        assertEquals(SilencePoint(500, -40), requireNotNull(state.finish()).points[1])
    }

    @Test fun unchangedBindDoesNotOverwriteAnOwnedPointerPreview() {
        val state = state(selected = 2)
        assertTrue(state.grab())
        state.move(plot.x(900), plot.y(-20))
        val preview = state.points
        state.bind(original.toList(), 2, true)
        assertTrue(state.active)
        assertEquals(preview, state.points)
        assertEquals(1, state.selected)
        assertEquals(preview, requireNotNull(state.finish()).points)
    }

    @Test fun changedModelRevokesOldGestureAndLateUpCannotOverwriteIt() {
        val state = state()
        assertTrue(state.grab())
        state.move(plot.x(900), plot.y(-20))
        val replacement = original.toMutableList().apply { set(1, SilencePoint(400, -60)) }
        state.bind(replacement, 1, true)
        assertFalse(state.active)
        state.move(plot.x(1_000), plot.y(-10))
        assertNull(state.finish())
        assertEquals(replacement, state.points)
    }

    @Test fun changedSelectionRevokesGestureWithoutRestoringTheOldSelection() {
        val state = state()
        assertTrue(state.grab())
        state.move(plot.x(900), plot.y(-20))
        state.bind(original, 3, true)
        assertNull(state.finish())
        assertEquals(3, state.selected)
        assertEquals(original, state.points)
    }

    @Test fun disablingMidGestureRollsBackAndReenabledOwnerCanStartAgain() {
        val state = state()
        assertTrue(state.grab())
        state.move(plot.x(900), plot.y(-20))
        state.bind(original, 1, false)
        assertFalse(state.active)
        assertEquals(original, state.points)
        assertNull(state.finish())
        assertFalse(state.grab())
        state.bind(original, 1, true)
        assertTrue(state.grab())
        state.move(plot.x(500), plot.y(-40))
        assertTrue(requireNotNull(state.finish()).changed)
    }

    @Test fun acceptedEditIsTheBaselineForAnImmediateSecondGesture() {
        val state = state()
        assertTrue(state.grab())
        state.move(plot.x(500), plot.y(-40))
        val accepted = requireNotNull(state.finish())
        assertTrue(state.grab())
        state.move(plot.x(900), plot.y(-30))
        state.bind(accepted.points, accepted.selected, true)
        assertTrue(state.active)
        assertEquals(SilencePoint(900, -30), requireNotNull(state.finish()).points[1])
    }

    @Test fun nonFinitePointerCancelsAndMissingGeometryRejectsAdmission() {
        val state = state()
        assertFalse(state.begin(0f, 0f, SilenceCurvePlot(0f, 0f, 0f, 0f), 32f, 8f))
        assertTrue(state.grab())
        state.move(Float.NaN, 10f)
        assertEquals(original, state.points)
        assertNull(state.finish())
    }

    @Test fun accessibleSelectionAndCutoffChangesPreserveTopologyAndFrequency() {
        val state = state()
        assertTrue(state.selectPoint(2))
        val increased = requireNotNull(state.setCutoff(-49))
        assertEquals(SilencePoint(2_000, -49), increased.points[2])
        assertEquals(original.size, increased.points.size)
        assertEquals(0, requireNotNull(state.setCutoff(10)).points[2].db)
        assertNull(state.setCutoff(0))
        assertEquals(-96, requireNotNull(state.setCutoff(-100)).points[2].db)
    }

    @Test fun relativeControlTakeoverKeepsDisplayedPointAndRollsBackPointerPreview() {
        val state = state(selected = 2)
        assertTrue(state.grab())
        state.move(plot.x(900), plot.y(-20))
        val edit = requireNotNull(state.adjustCutoff(1))
        assertEquals(1, edit.selected)
        assertEquals(SilencePoint(200, -49), edit.points[1])
        assertEquals(original[2], edit.points[2])
        assertFalse(state.active)
        state.move(plot.x(1_000), plot.y(-10))
        assertNull(state.finish())
        assertEquals(edit.points, state.points)
    }

    @Test fun absoluteControlTakeoverPublishesSelectionEvenWhenCutoffIsUnchanged() {
        val state = state(selected = 2)
        assertTrue(state.grab())
        state.move(plot.x(900), plot.y(-20))
        val edit = requireNotNull(state.setCutoff(-50))
        assertFalse(edit.changed)
        assertEquals(1, edit.selected)
        assertEquals(original, edit.points)
        assertNull(state.finish())
        state.bind(edit.points, edit.selected, true)
        assertEquals(1, state.selected)
        assertNull(state.setCutoff(-50))
    }

    @Test fun frequencyControlsChangeOnlyTheSelectedFrequency() {
        val state = state()
        assertTrue(state.canAdjustFrequency(-1))
        assertTrue(state.canAdjustFrequency(1))
        val raised = requireNotNull(state.adjustFrequency(1))
        assertEquals(SilencePoint(214, -50), raised.points[1])
        assertEquals(original.filterIndexed { index, _ -> index != 1 }, raised.points.filterIndexed { index, _ -> index != 1 })
        assertEquals(1, raised.selected)
        assertTrue(raised.changed)
        assertEquals(original, requireNotNull(state.adjustFrequency(-1)).points)
        assertEquals(SilencePoint(187, -50), requireNotNull(state.adjustFrequency(-1)).points[1])
    }

    @Test fun frequencyControlsRespectNeighborsEndpointsAndDisabledState() {
        val state = state()
        repeat(100) { state.adjustFrequency(1) }
        assertEquals(1_999, state.points[1].hz)
        assertFalse(state.canAdjustFrequency(1))
        assertNull(state.adjustFrequency(1))
        repeat(100) { state.adjustFrequency(-1) }
        assertEquals(21, state.points[1].hz)
        assertFalse(state.canAdjustFrequency(-1))
        assertNull(state.adjustFrequency(-1))
        for (index in listOf(0, original.lastIndex)) {
            state.bind(original, index, true)
            for (direction in listOf(-1, 1)) {
                assertFalse(state.canAdjustFrequency(direction))
                assertNull(state.adjustFrequency(direction))
                assertEquals(original, state.points)
            }
        }
        state.bind(original, 1, false)
        assertFalse(state.canAdjustFrequency(1))
        assertNull(state.adjustFrequency(1))
        assertEquals(original, state.points)
    }

    @Test fun frequencyControlTakeoverRollsBackPreviewAndCannotCommitAgainOnPointerUp() {
        val state = state(selected = 2)
        assertTrue(state.grab())
        state.move(plot.x(900), plot.y(-20))
        val edit = requireNotNull(state.adjustFrequency(1))
        assertEquals(1, edit.selected)
        assertEquals(SilencePoint(214, -50), edit.points[1])
        assertEquals(original[2], edit.points[2])
        assertFalse(state.active)
        state.move(plot.x(1_000), plot.y(-10))
        assertNull(state.finish())
        assertEquals(edit.points, state.points)
    }

    @Test fun endpointFrequencyControlTakeoverPublishesSelectionWithoutPreviewChanges() {
        val state = state()
        assertTrue(state.grab(index = 0))
        state.move(plot.x(20), plot.y(-20))
        val edit = requireNotNull(state.adjustFrequency(1))
        assertFalse(edit.changed)
        assertEquals(0, edit.selected)
        assertEquals(original, edit.points)
        assertNull(state.finish())
        assertNull(state.adjustFrequency(1))
    }
}
