package app.smallthingz.reverb

import app.smallthingz.reverb.nativeui.OnboardingPermission
import app.smallthingz.reverb.nativeui.OnboardingPermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPermissionRequestSequenceTest {
    private val missing = OnboardingPermissionStatus(
        microphoneAllowed = false,
        storageRequired = true,
        storageAllowed = false,
        recoveryRequired = true,
        recoveryAllowed = false,
        notificationPermissionRequired = true,
        notificationsAllowed = false,
        batteryOptimizationAllowed = false,
        interactionEnabled = true,
    )

    @Test fun automaticRequestsOnlyRunOnReadyVisibleForegroundPage() {
        val sequence = OnboardingPermissionRequestSequence()
        assertNull(sequence.claimNext(missing, pageVisible = false, resumed = true))
        assertNull(sequence.claimNext(missing, pageVisible = true, resumed = false))
        assertNull(sequence.claimNext(missing.copy(interactionEnabled = false), true, true))
        assertEquals(0, sequence.attemptedMask)
        assertEquals(OnboardingPermission.MICROPHONE, sequence.claimNext(missing, true, true))
    }

    @Test fun pendingRequestPreventsAutomaticAndManualOverlap() {
        val sequence = OnboardingPermissionRequestSequence()
        assertEquals(OnboardingPermission.MICROPHONE, sequence.claimNext(missing, true, true))
        assertNull(sequence.claimNext(missing, true, true))
        assertFalse(sequence.claimManual(OnboardingPermission.NOTIFICATIONS, missing, true))
        assertFalse(sequence.claimManual(OnboardingPermission.MICROPHONE, missing, true))
    }

    @Test fun everyDenialAdvancesToNextDistinctPermissionWithoutAutomaticRetry() {
        val sequence = OnboardingPermissionRequestSequence()
        for (permission in OnboardingPermission.entries) {
            assertEquals(permission, sequence.claimNext(missing, true, true))
            sequence.completed(permission)
        }
        repeat(4) { assertNull(sequence.claimNext(missing, true, true)) }
    }

    @Test fun allowedAndUnsupportedPermissionsAreSkipped() {
        val sequence = OnboardingPermissionRequestSequence()
        val status = missing.copy(microphoneAllowed = true, storageRequired = false,
            recoveryRequired = false, notificationPermissionRequired = false)
        assertEquals(OnboardingPermission.BATTERY, sequence.claimNext(status, true, true))
        sequence.completed(OnboardingPermission.BATTERY)
        assertNull(sequence.claimNext(status, true, true))
    }

    @Test fun explicitRowTapCanRetryDeniedPermissionWithoutRestartingAutomaticSequence() {
        val sequence = OnboardingPermissionRequestSequence(setOf(OnboardingPermission.MICROPHONE))
        assertFalse(sequence.claimManual(OnboardingPermission.MICROPHONE, missing, false))
        assertTrue(sequence.claimManual(OnboardingPermission.MICROPHONE, missing, true))
        sequence.completed(OnboardingPermission.MICROPHONE)
        assertEquals(OnboardingPermission.STORAGE, sequence.claimNext(missing, true, true))
    }

    @Test fun manualRequestsRespectReadinessAndApplicability() {
        val sequence = OnboardingPermissionRequestSequence()
        assertFalse(sequence.claimManual(OnboardingPermission.MICROPHONE,
            missing.copy(microphoneAllowed = true), true))
        assertFalse(sequence.claimManual(OnboardingPermission.STORAGE,
            missing.copy(storageRequired = false), true))
        assertFalse(sequence.claimManual(OnboardingPermission.NOTIFICATIONS,
            missing.copy(interactionEnabled = false), true))
        assertEquals(0, sequence.attemptedMask)
    }

    @Test fun unrelatedCallbackCannotReleaseCurrentRequest() {
        val sequence = OnboardingPermissionRequestSequence()
        sequence.claimNext(missing, true, true)
        sequence.completed(OnboardingPermission.NOTIFICATIONS)
        assertEquals(OnboardingPermission.MICROPHONE, sequence.pending)
        assertNull(sequence.claimNext(missing, true, true))
    }

    @Test fun runtimePermissionWaitsForItsResultEvenAcrossPauseResume() {
        val sequence = OnboardingPermissionRequestSequence()
        sequence.claimNext(missing, true, true)
        sequence.leftForeground()
        sequence.returnedToForeground()
        assertEquals(OnboardingPermission.MICROPHONE, sequence.pending)
        sequence.completed(OnboardingPermission.MICROPHONE)
        assertEquals(OnboardingPermission.STORAGE, sequence.claimNext(missing, true, true))
    }

    @Test fun batteryReviewRequiresRealDepartureBeforeReturnCompletesIt() {
        val sequence = OnboardingPermissionRequestSequence()
        assertTrue(sequence.claimManual(OnboardingPermission.BATTERY, missing, true))
        sequence.returnedToForeground()
        assertEquals(OnboardingPermission.BATTERY, sequence.pending)
        sequence.leftForeground()
        assertTrue(sequence.batteryLeftForeground)
        sequence.returnedToForeground()
        assertNull(sequence.pending)
        assertFalse(sequence.batteryLeftForeground)
    }

    @Test fun recreationRetainsAttemptedAndPendingStateWithoutDuplicateLaunch() {
        val original = OnboardingPermissionRequestSequence()
        original.claimNext(missing, true, true)
        val restored = OnboardingPermissionRequestSequence(
            OnboardingPermissionRequestSequence.attemptedFromMask(original.attemptedMask),
            original.pending, original.batteryLeftForeground)
        assertNull(restored.claimNext(missing, true, true))
        restored.completed(OnboardingPermission.MICROPHONE)
        assertEquals(OnboardingPermission.STORAGE, restored.claimNext(missing, true, true))
    }

    @Test fun restoredPendingImpliesAttemptedEvenWhenOlderSavedMaskOmitsIt() {
        val restored = OnboardingPermissionRequestSequence(pending = OnboardingPermission.MICROPHONE)
        restored.completed(OnboardingPermission.MICROPHONE)
        assertEquals(OnboardingPermission.STORAGE, restored.claimNext(missing, true, true))
    }

    @Test fun restoredBatteryReturnDoesNotRelaunchTheDeniedReview() {
        val original = OnboardingPermissionRequestSequence(OnboardingPermission.entries.toSet())
        assertTrue(original.claimManual(OnboardingPermission.BATTERY, missing, true))
        original.leftForeground()
        val restored = OnboardingPermissionRequestSequence(
            OnboardingPermissionRequestSequence.attemptedFromMask(original.attemptedMask),
            original.pending, original.batteryLeftForeground)
        restored.returnedToForeground()
        assertNull(restored.pending)
        assertNull(restored.claimNext(missing, true, true))
    }
}
