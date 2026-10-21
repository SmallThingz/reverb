package app.smallthingz.reverb.nativeui

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCalibrationProgressTest {
    @Test fun nonFiniteSamplesNeverBecomeCompleteProgress() {
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
            assertEquals(0f,calibrationProgressValue(value),0f)
        }
        assertEquals(0f,calibrationProgressValue(-.1f),0f)
        assertEquals(1f,calibrationProgressValue(1.1f),0f)
    }
    @Test fun accessibilityReservesZeroAndHundredForExactEndpoints() {
        assertEquals(0,calibrationProgressPercent(0f))
        assertEquals(1,calibrationProgressPercent(Float.MIN_VALUE))
        assertEquals(99,calibrationProgressPercent(Math.nextDown(1f)))
        assertEquals(100,calibrationProgressPercent(1f))
    }
    @Test fun accessibilityRoundsInteriorPercentageAtSourceHalfBoundary() {
        assertEquals(12,calibrationProgressPercent(.124f))
        assertEquals(13,calibrationProgressPercent(.125f))
        assertEquals(50,calibrationProgressPercent(.5f))
    }
}
