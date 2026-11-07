package app.smallthingz.reverb

import android.app.AppOpsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationMicrophoneAvailabilityTest {
    @Test fun explicitDenialAndFrameworkSilencingRejectCalibration() {
        assertTrue(calibrationMicrophoneBlocked(false, AppOpsManager.MODE_ALLOWED, false))
        assertTrue(calibrationMicrophoneBlocked(true, AppOpsManager.MODE_IGNORED, false))
        assertTrue(calibrationMicrophoneBlocked(true, AppOpsManager.MODE_ERRORED, false))
        assertTrue(calibrationMicrophoneBlocked(true, AppOpsManager.MODE_ALLOWED, true))
    }

    @Test fun foregroundDefaultAndUnavailablePolicyAreNotInventedDenials() {
        for (mode in listOf(null, AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_FOREGROUND)) {
            assertFalse(calibrationMicrophoneBlocked(true, mode, false))
        }
    }
}
