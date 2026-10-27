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

    @Test fun automaticallyRequestsEveryApplicablePermissionOnceEvenWhenDenied() {
        val sequence = OnboardingPermissionRequestSequence()
        for (permission in OnboardingPermission.entries) {
            assertEquals(permission, sequence.claimNext(missing, true))
            assertNull(sequence.claimNext(missing, true))
            sequence.completed(permission)
        }
        repeat(4) {
            sequence.leftForeground(); sequence.returnedToForeground()
            assertNull(sequence.claimNext(missing, true))
        }
    }

    @Test fun automaticRequestsSkipGrantedAndInapplicablePermissions() {
        val sequence = OnboardingPermissionRequestSequence()
        val status = missing.copy(microphoneAllowed = true, storageRequired = false,
            recoveryRequired = false, notificationPermissionRequired = false)
        assertEquals(OnboardingPermission.BATTERY, sequence.claimNext(status, true))
        sequence.leftForeground()
        sequence.returnedToForeground()
        assertNull(sequence.claimNext(status, true))
    }

    @Test fun automaticRequestsWaitForResumedInteractivePage() {
        val sequence = OnboardingPermissionRequestSequence()
        assertNull(sequence.claimNext(missing, false))
        assertNull(sequence.claimNext(missing.copy(interactionEnabled = false), true))
        assertEquals(0, sequence.attemptedMask)
        assertEquals(OnboardingPermission.MICROPHONE, sequence.claimNext(missing, true))
    }

    @Test fun restoredAutomaticSequenceContinuesAfterPendingResultWithoutRepeatingDenial() {
        val original = OnboardingPermissionRequestSequence()
        assertEquals(OnboardingPermission.MICROPHONE, original.claimNext(missing, true))
        val restored = OnboardingPermissionRequestSequence(
            OnboardingPermissionRequestSequence.attemptedFromMask(original.attemptedMask), original.pending)
        assertNull(restored.claimNext(missing, true))
        restored.completed(OnboardingPermission.MICROPHONE)
        assertEquals(OnboardingPermission.STORAGE, restored.claimNext(missing, true))
        restored.completed(OnboardingPermission.STORAGE)
        assertTrue(restored.claimManual(OnboardingPermission.MICROPHONE, missing, true))
        assertNull(restored.claimNext(missing, true))
    }

    @Test fun lifecycleEventsAloneNeverClaimAPermission() {
        val sequence = OnboardingPermissionRequestSequence()
        repeat(4) { sequence.leftForeground(); sequence.returnedToForeground() }
        assertNull(sequence.pending)
        assertEquals(0, sequence.attemptedMask)
    }

    @Test fun explicitRequestPreventsManualOverlap() {
        val sequence = OnboardingPermissionRequestSequence()
        assertTrue(sequence.claimManual(OnboardingPermission.MICROPHONE, missing, true))
        assertFalse(sequence.claimManual(OnboardingPermission.NOTIFICATIONS, missing, true))
        assertFalse(sequence.claimManual(OnboardingPermission.MICROPHONE, missing, true))
    }

    @Test fun completionNeverClaimsTheNextPermissionAndExplicitTapCanRetry() {
        val sequence = OnboardingPermissionRequestSequence()
        for (permission in OnboardingPermission.entries) {
            assertFalse(sequence.claimManual(permission, missing, false))
            assertTrue(sequence.claimManual(permission, missing, true))
            sequence.completed(permission)
            sequence.leftForeground(); sequence.returnedToForeground()
            assertNull(sequence.pending)
            assertTrue(sequence.claimManual(permission, missing, true))
            sequence.completed(permission)
            assertNull(sequence.pending)
        }
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
        sequence.claimManual(OnboardingPermission.MICROPHONE, missing, true)
        sequence.completed(OnboardingPermission.NOTIFICATIONS)
        assertEquals(OnboardingPermission.MICROPHONE, sequence.pending)
        assertFalse(sequence.claimManual(OnboardingPermission.MICROPHONE, missing, true))
    }

    @Test fun runtimePermissionWaitsForItsResultEvenAcrossPauseResume() {
        val sequence = OnboardingPermissionRequestSequence()
        sequence.claimManual(OnboardingPermission.MICROPHONE, missing, true)
        sequence.leftForeground()
        sequence.returnedToForeground()
        assertEquals(OnboardingPermission.MICROPHONE, sequence.pending)
        sequence.completed(OnboardingPermission.MICROPHONE)
        assertNull(sequence.pending)
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
        original.claimManual(OnboardingPermission.MICROPHONE, missing, true)
        val restored = OnboardingPermissionRequestSequence(
            OnboardingPermissionRequestSequence.attemptedFromMask(original.attemptedMask),
            original.pending, original.batteryLeftForeground)
        assertFalse(restored.claimManual(OnboardingPermission.STORAGE, missing, true))
        restored.completed(OnboardingPermission.MICROPHONE)
        assertNull(restored.pending)
    }

    @Test fun restoredPendingImpliesAttemptedEvenWhenOlderSavedMaskOmitsIt() {
        val restored = OnboardingPermissionRequestSequence(pending = OnboardingPermission.MICROPHONE)
        assertEquals(setOf(OnboardingPermission.MICROPHONE),
            OnboardingPermissionRequestSequence.attemptedFromMask(restored.attemptedMask))
        restored.completed(OnboardingPermission.MICROPHONE)
        assertNull(restored.pending)
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
        assertTrue(restored.claimManual(OnboardingPermission.BATTERY, missing, true))
    }
}
