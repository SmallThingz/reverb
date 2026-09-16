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
    fun steppedValues_chooseNearestCircularValue() {
        assertEquals(0, nearestRangeDurationWheelSteppedValue(59, 5))
        assertEquals(55, nearestRangeDurationWheelSteppedValue(54, 5))
        assertEquals(15, nearestRangeDurationWheelSteppedValue(17, 15))
        assertEquals(0, nearestRangeDurationWheelSteppedValue(59, 15))
    }

    @Test
    fun hoursAreNotClockBounded() {
        val parts = splitRangeDurationWheelSeconds(86_400)
        assertEquals(24, parts.hours)
        assertEquals(0, parts.minutes)
        assertEquals(0, parts.seconds)
    }
}
