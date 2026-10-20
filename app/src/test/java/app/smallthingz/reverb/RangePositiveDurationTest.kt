package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RangePositiveDurationTest {
    private val longDuration = 1_123_200f

    private fun assertPositive(values: RangeEditValues, duration: Float) {
        assertTrue(values.startSeconds.isFinite())
        assertTrue(values.endSeconds.isFinite())
        assertTrue(values.startSeconds >= 0f)
        assertTrue(values.startSeconds < values.endSeconds)
        assertTrue(values.endSeconds <= duration)
    }

    @Test
    fun advertisedEndpointEndRemainsNonemptyAcrossFloatPrecisionBoundary() {
        for (duration in listOf(
            java.lang.Math.nextDown(1_048_576f),
            1_048_576f,
            java.lang.Math.nextUp(1_048_576f),
            longDuration,
            31_536_000f,
        )) {
            for (target in RangeEditTarget.values()) {
                val limits = requireNotNull(rangeEndpointLimits(duration, target))
                var values = RangeEditValues(0f, duration)
                val gesture = RangeInputGesture({ true }, { RangeDurationWheelInteraction() }, {})
                assertTrue(runRangeEndpointAction(
                    values, duration, target, limits.endInclusive.toDouble(), gesture, {},
                    { values = it.values },
                ))
                assertPositive(values, duration)
                val selected = if (target == RangeEditTarget.START) values.startSeconds else values.endSeconds
                assertEquals(limits.endInclusive, selected, 0f)
            }
            assertPositive(adjustRangeEditTarget(
                RangeEditValues(0f, duration), RangeEditTarget.START, duration, duration,
            ).values, duration)
        }
    }

    @Test
    fun cornerPushingPreservesTheRequestedEndpointWhenItsNeighbourExists() {
        val point = longDuration
        val duration = point + 10f
        val movedStart = adjustRangeEditTarget(
            RangeEditValues(point - 1f, point), RangeEditTarget.START, point, duration,
        ).values
        assertEquals(RangeEditValues(point, java.lang.Math.nextUp(point)), movedStart)
        val movedEnd = adjustRangeEditTarget(
            RangeEditValues(point, point + 1f), RangeEditTarget.END, point, duration,
        ).values
        assertEquals(RangeEditValues(java.lang.Math.nextDown(point), point), movedEnd)
        assertPositive(movedStart, duration)
        assertPositive(movedEnd, duration)
    }

    @Test
    fun zeroAndNegativeDurationRequestsKeepTheOppositeBoundaryAtLongOffsets() {
        val original = RangeEditValues(longDuration - 1f, longDuration)
        for (requested in listOf(0f, -1f, Float.MIN_VALUE, 0.05f)) {
            val startEdit = resizeRangeSelectionDuration(
                original, RangeEditTarget.START, requested, longDuration,
            ).values
            assertEquals(RangeEditValues(java.lang.Math.nextDown(original.endSeconds), original.endSeconds), startEdit)
            val endEdit = resizeRangeSelectionDuration(
                original, RangeEditTarget.END, requested, longDuration,
            ).values
            assertEquals(RangeEditValues(original.startSeconds, java.lang.Math.nextUp(original.startSeconds)), endEdit)
            assertPositive(startEdit, longDuration)
            assertPositive(endEdit, longDuration)
        }
    }

    @Test
    fun discreteDurationActionRetainsItsPinnedTargetAndPositiveExactCap() {
        val original = RangeEditValues(longDuration - 1f, longDuration)
        for (target in RangeEditTarget.values()) {
            var owner = RangeDurationWheelInteraction()
            var values = original
            val maximum = rangeSelectionDurationWheelLimitSeconds(original, target, longDuration, 0.125)
            assertTrue(runRangeDurationAction(
                0.0, maximum, true,
                { owner = owner.begin(target) },
                { requested ->
                    val active = requireNotNull(owner.commitTarget())
                    val resized = resizeRangeSelectionDuration(values, active, requested, longDuration).values
                    values = boundRangeDurationActionValues(resized, active, maximum)
                },
                { owner = owner.end() },
            ))
            assertFalse(owner.active)
            assertPositive(values, longDuration)
            assertTrue(rangeExportSelectionWithinLimit(
                rangeSelectionDurationExactSeconds(values.startSeconds, values.endSeconds), maximum,
            ))
            if (target == RangeEditTarget.START) assertEquals(original.endSeconds, values.endSeconds, 0f)
            else assertEquals(original.startSeconds, values.startSeconds, 0f)
        }
    }

    @Test
    fun anAlreadyCollapsedSelectionIsNormalizedByEitherEditPath() {
        val duration = longDuration + 10f
        for (point in listOf(0f, longDuration, duration)) {
            for (target in RangeEditTarget.values()) {
                val collapsed = RangeEditValues(point, point)
                assertPositive(adjustRangeEditTarget(collapsed, target, point, duration).values, duration)
                assertPositive(resizeRangeSelectionDuration(collapsed, target, 0f, duration).values, duration)
            }
        }
    }

    @Test
    fun longTimelineDoesNotIncreaseTheGapNearItsOrigin() {
        assertEquals(RangeEditValues(1f, 1.05f), adjustRangeEditTarget(
            RangeEditValues(0f, 0.05f), RangeEditTarget.START, 1f, longDuration,
        ).values)
        assertEquals(RangeEditValues(1f, 1.05f), resizeRangeSelectionDuration(
            RangeEditValues(1f, 2f), RangeEditTarget.END, 0f, longDuration,
        ).values)
        assertEquals(RangeEditValues(0f, 0.05f), resizeRangeSelectionDuration(
            RangeEditValues(0f, 0.05f), RangeEditTarget.START, -1f, longDuration,
        ).values)
    }

    @Test
    fun minusculePositiveSourcesRemainSelectableAndExportable() {
        for (duration in listOf(Float.MIN_VALUE, java.lang.Math.nextUp(Float.MIN_VALUE), 1e-30f, 0.02f)) {
            val full = RangeEditValues(0f, duration)
            for (target in RangeEditTarget.values()) {
                assertEquals(full, adjustRangeEditTarget(full, target, duration, duration).values)
                assertEquals(full, resizeRangeSelectionDuration(full, target, 0f, duration).values)
            }
            assertTrue(rangeExportSelectionWithinLimit(
                rangeSelectionDurationExactSeconds(full.startSeconds, full.endSeconds), duration.toDouble(),
            ))
        }
    }

    @Test
    fun saveAdmissionRejectsEmptyReversedAndNonfiniteSelectionsWithoutLosingExactCaps() {
        for (selected in listOf(0.0, -0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(rangeExportSelectionWithinLimit(selected, 60.0))
        }
        for (values in listOf(
            RangeEditValues(longDuration, longDuration),
            RangeEditValues(2f, 1f),
            RangeEditValues(Float.NaN, 1f),
            RangeEditValues(0f, Float.POSITIVE_INFINITY),
        )) {
            assertFalse(rangeExportSelectionWithinLimit(
                rangeSelectionDurationExactSeconds(values.startSeconds, values.endSeconds), 60.0,
            ))
        }
        assertTrue(rangeExportSelectionWithinLimit(0.125, 0.125))
        assertFalse(rangeExportSelectionWithinLimit(java.lang.Math.nextUp(0.125), 0.125))
        for (maximum in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(rangeExportSelectionWithinLimit(0.125, maximum))
        }
    }
}
