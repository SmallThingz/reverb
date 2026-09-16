package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Test

class RangeDurationWheelMathTest {
    @Test
    fun splitAndCompose_preserveDurations() {
        val values = listOf(0, 1, 59, 60, 3_599, 3_600, 86_400, 172_815, Int.MAX_VALUE)
        values.forEach { seconds ->
            val parts = splitRangeDurationWheelSeconds(seconds)
            assertEquals(
                seconds,
                composeRangeDurationWheelSeconds(parts.hours, parts.minutes, parts.seconds),
            )
        }
    }

    @Test
    fun profileSteps_matchPickerContract() {
        assertEquals(1, rangeDurationWheelProfileStep(0))
        assertEquals(5, rangeDurationWheelProfileStep(1))
        assertEquals(15, rangeDurationWheelProfileStep(2))
    }

    @Test
    fun hoursAreNotClockBounded() {
        val parts = splitRangeDurationWheelSeconds(86_400)
        assertEquals(24, parts.hours)
        assertEquals(0, parts.minutes)
        assertEquals(0, parts.seconds)
    }
    @Test
    fun normalProfileRing_doesNotInventFiftyNineAsAStep() {
        assertEquals(
            listOf(0, 15, 30, 45),
            rangeDurationWheelValues(
                step = 15,
                maxInclusive = 59,
                currentValue = 0,
                includeMaximumBoundary = false,
            ).toList(),
        )
    }

    @Test
    fun reachableTenMinuteBoundary_doesNotExposeElevenMinutes() {
        val values = rangeDurationWheelValues(
            step = 1,
            maxInclusive = 10,
            currentValue = 10,
        )
        assertEquals((0..10).toList(), values.toList())
    }

    @Test
    fun limitAwareRings_includeExactBoundaryRegardlessOfProfile() {
        assertEquals(
            listOf(0, 15, 24),
            rangeDurationWheelValues(step = 15, maxInclusive = 24, currentValue = 24).toList(),
        )
        assertEquals(
            listOf(0, 15, 16),
            rangeDurationWheelValues(step = 15, maxInclusive = 16, currentValue = 16).toList(),
        )
    }

    @Test
    fun limitAwareRings_preserveAbnormalCurrentValueUntilUserLeavesIt() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 13),
            rangeDurationWheelValues(step = 1, maxInclusive = 6, currentValue = 13).toList(),
        )
        assertEquals(
            listOf(0, 15, 24, 25),
            rangeDurationWheelValues(step = 15, maxInclusive = 24, currentValue = 25).toList(),
        )
    }

    @Test
    fun fractionalLimitDisplay_neverShowsValidOverflowAsSafe() {
        val max = 6 * 3_600.0 + 24 * 60.0 + 16.75
        assertEquals(6 * 3_600 + 24 * 60 + 16, rangeDurationWheelWholeLimitSeconds(max))
        assertEquals(6 * 3_600 + 24 * 60 + 16, rangeDurationWheelDisplaySeconds(max, max))
        assertEquals(6 * 3_600 + 24 * 60 + 17, rangeDurationWheelDisplaySeconds(max + 0.01, max))
    }

    @Test
    fun candidateFaceRedStateReflectsResultOfSelectingThatFace() {
        val maximum = 6 * 3_600.0 + 24 * 60.0 + 16.75

        // With an abnormal hour selected, moving the hour back into range can clear overage.
        assertEquals(false, rangeDurationWheelCandidateIsOverLimit(6, 0, 0, maximum))
        assertEquals(false, rangeDurationWheelCandidateIsOverLimit(5, 59, 59, maximum))
        assertEquals(true, rangeDurationWheelCandidateIsOverLimit(13, 0, 0, maximum))

        // Changing another field cannot clear an hour overage.
        assertEquals(true, rangeDurationWheelCandidateIsOverLimit(13, 15, 0, maximum))
        assertEquals(true, rangeDurationWheelCandidateIsOverLimit(13, 0, 15, maximum))

        // At the maximum hour, only candidates whose resulting full time still exceeds M are red.
        assertEquals(false, rangeDurationWheelCandidateIsOverLimit(6, 24, 16, maximum))
        assertEquals(true, rangeDurationWheelCandidateIsOverLimit(6, 24, 17, maximum))
        assertEquals(true, rangeDurationWheelCandidateIsOverLimit(6, 25, 0, maximum))
    }

    @Test
    fun errorMask_isHierarchicalAtExportBoundary() {
        val max = 6 * 3_600 + 24 * 60 + 16
        assertEquals(0, rangeDurationWheelErrorMask(5, 59, 59, max))
        assertEquals(0, rangeDurationWheelErrorMask(6, 24, 16, max))
        assertEquals(
            RANGE_DURATION_WHEEL_ERROR_SECOND,
            rangeDurationWheelErrorMask(6, 24, 17, max),
        )
        assertEquals(
            RANGE_DURATION_WHEEL_ERROR_MINUTE or RANGE_DURATION_WHEEL_ERROR_SECOND,
            rangeDurationWheelErrorMask(6, 25, 0, max),
        )
        assertEquals(
            RANGE_DURATION_WHEEL_ERROR_HOUR or
                RANGE_DURATION_WHEEL_ERROR_MINUTE or
                RANGE_DURATION_WHEEL_ERROR_SECOND,
            rangeDurationWheelErrorMask(13, 0, 0, max),
        )
    }

}
