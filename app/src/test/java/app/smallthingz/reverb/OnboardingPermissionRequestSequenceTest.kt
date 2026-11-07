package app.smallthingz.reverb

import app.smallthingz.reverb.nativeui.OnboardingPermission
import app.smallthingz.reverb.nativeui.OnboardingPermissionStatus
import app.smallthingz.reverb.nativeui.isReadyForHighlightedContinue
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
        allFilesAvailable = true,
    )

    @Test fun automaticallyRequestsEveryApplicablePermissionOnceEvenWhenDenied() {
        val sequence = OnboardingPermissionRequestSequence()
        for (permission in listOf(OnboardingPermission.MICROPHONE, OnboardingPermission.STORAGE,
            OnboardingPermission.ALL_FILES, OnboardingPermission.RECOVERY,
            OnboardingPermission.NOTIFICATIONS, OnboardingPermission.BATTERY)) {
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
            recoveryRequired = false, notificationPermissionRequired = false, allFilesAvailable = false)
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

    @Test fun firstRequestAndRetryableDenialDoNotOpenSettings() {
        for (requested in listOf(false, true)) {
            for (rationale in listOf(false, true)) {
                val sequence = OnboardingPermissionRequestSequence()
                assertTrue(sequence.claimManual(OnboardingPermission.MICROPHONE, missing, true))
                assertEquals(requested && !rationale, sequence.beginSettingsReview(requested, rationale))
                assertEquals(requested && !rationale, sequence.settingsReview)
            }
        }
    }

    @Test fun settingsReviewWorksForEveryRuntimePermissionAndBlocksOverlapUntilReturn() {
        for (permission in OnboardingPermission.entries.filter { it != OnboardingPermission.BATTERY && it != OnboardingPermission.ALL_FILES }) {
            val sequence = OnboardingPermissionRequestSequence()
            assertTrue(sequence.claimManual(permission, missing, true))
            assertTrue(sequence.beginSettingsReview(previouslyRequested = true, showsRationale = false))
            sequence.returnedToForeground()
            assertEquals(permission, sequence.pending)
            assertFalse(sequence.claimManual(permission, missing, true))
            assertNull(sequence.claimNext(missing, true))
            sequence.leftForeground()
            assertTrue(sequence.settingsLeftForeground)
            sequence.returnedToForeground()
            assertNull(sequence.pending)
            assertFalse(sequence.settingsReview)
            assertFalse(sequence.settingsLeftForeground)
            assertTrue(sequence.claimManual(permission, missing, true))
        }
    }

    @Test fun settingsReviewRestoresAfterActivityRecreationAndDoesNotReopenAutomatically() {
        val original = OnboardingPermissionRequestSequence(OnboardingPermission.entries.toSet())
        original.claimManual(OnboardingPermission.MICROPHONE, missing, true)
        original.beginSettingsReview(previouslyRequested = true, showsRationale = false)
        original.leftForeground()
        val restored = OnboardingPermissionRequestSequence(
            OnboardingPermissionRequestSequence.attemptedFromMask(original.attemptedMask),
            original.pending, original.batteryLeftForeground, original.settingsReview, original.settingsLeftForeground)
        assertEquals(OnboardingPermission.MICROPHONE, restored.pending)
        restored.returnedToForeground()
        assertNull(restored.pending)
        assertNull(restored.claimNext(missing, true))
        assertTrue(restored.claimManual(OnboardingPermission.MICROPHONE, missing, true))
    }

    @Test fun failedSettingsLaunchReleasesOnlyItsClaimAndDoesNotLeaveReviewState() {
        val sequence = OnboardingPermissionRequestSequence()
        sequence.claimManual(OnboardingPermission.MICROPHONE, missing, true)
        sequence.beginSettingsReview(previouslyRequested = true, showsRationale = false)
        sequence.completed(OnboardingPermission.NOTIFICATIONS)
        assertTrue(sequence.settingsReview)
        sequence.completed(OnboardingPermission.MICROPHONE)
        assertNull(sequence.pending)
        assertFalse(sequence.settingsReview)
        sequence.leftForeground()
        sequence.returnedToForeground()
        assertFalse(sequence.settingsLeftForeground)
        assertEquals(OnboardingPermission.STORAGE, sequence.claimNext(missing, true))
    }

    @Test fun settingsReturnRereadCanMarkGrantWithoutRequestingItAgain() {
        val sequence = OnboardingPermissionRequestSequence()
        sequence.claimManual(OnboardingPermission.MICROPHONE, missing, true)
        sequence.beginSettingsReview(previouslyRequested = true, showsRationale = false)
        sequence.leftForeground()
        sequence.returnedToForeground()
        val granted = missing.copy(microphoneAllowed = true)
        assertFalse(sequence.claimManual(OnboardingPermission.MICROPHONE, granted, true))
        assertEquals(OnboardingPermission.STORAGE, sequence.claimNext(granted, true))
    }

    @Test fun absentAndBatteryPendingCannotBecomeRuntimeSettingsReview() {
        val sequence = OnboardingPermissionRequestSequence()
        assertFalse(sequence.beginSettingsReview(previouslyRequested = true, showsRationale = false))
        sequence.claimManual(OnboardingPermission.BATTERY, missing, true)
        assertFalse(sequence.beginSettingsReview(previouslyRequested = true, showsRationale = false))
        sequence.leftForeground()
        sequence.returnedToForeground()
        assertNull(sequence.pending)
    }

    @Test fun staleSavedSettingsFlagsCannotReleaseARegularRuntimeRequest() {
        val sequence = OnboardingPermissionRequestSequence(pending = OnboardingPermission.MICROPHONE,
            settingsReview = false, settingsLeftForeground = true)
        sequence.returnedToForeground()
        assertEquals(OnboardingPermission.MICROPHONE, sequence.pending)
        assertFalse(sequence.settingsLeftForeground)
    }

    @Test fun oldAttemptMaskKeepsItsMeaningAndNewAccessRunsBeforeAudioRecovery() {
        assertEquals(setOf(OnboardingPermission.MICROPHONE, OnboardingPermission.STORAGE,
            OnboardingPermission.RECOVERY, OnboardingPermission.NOTIFICATIONS, OnboardingPermission.BATTERY),
            OnboardingPermissionRequestSequence.attemptedFromMask(31))
        val sequence = OnboardingPermissionRequestSequence()
        val status = missing.copy(microphoneAllowed = true, storageRequired = false)
        assertEquals(OnboardingPermission.ALL_FILES, sequence.claimNext(status, true))
        sequence.beginAllFilesReview()
        sequence.returnedToForeground()
        assertEquals(OnboardingPermission.ALL_FILES, sequence.pending)
        sequence.leftForeground()
        sequence.returnedToForeground()
        assertEquals(OnboardingPermission.RECOVERY, sequence.claimNext(status, true))
    }

    @Test fun allFilesGrantSkipsRedundantAudioReadAndRevocationAllowsItsFallback() {
        val sequence = OnboardingPermissionRequestSequence()
        val status = missing.copy(microphoneAllowed = true, storageRequired = false,
            notificationsAllowed = true, batteryOptimizationAllowed = true)
        assertEquals(OnboardingPermission.ALL_FILES, sequence.claimNext(status, true))
        sequence.beginAllFilesReview()
        sequence.leftForeground(); sequence.returnedToForeground()
        assertNull(sequence.claimNext(status.copy(allFilesAllowed = true), true))
        assertFalse(sequence.claimManual(OnboardingPermission.RECOVERY, status.copy(allFilesAllowed = true), true))
        assertEquals(OnboardingPermission.RECOVERY, sequence.claimNext(status, true))
    }

    @Test fun allFilesDenialAfterRecreationDoesNotLoopAndCanBeRetriedManually() {
        val original = OnboardingPermissionRequestSequence(OnboardingPermission.entries.toSet())
        assertTrue(original.claimManual(OnboardingPermission.ALL_FILES, missing, true))
        original.beginAllFilesReview()
        original.leftForeground()
        val restored = OnboardingPermissionRequestSequence(
            OnboardingPermissionRequestSequence.attemptedFromMask(original.attemptedMask),
            original.pending, original.batteryLeftForeground, original.settingsReview, original.settingsLeftForeground)
        assertNull(restored.claimNext(missing, true))
        restored.returnedToForeground()
        assertNull(restored.pending)
        repeat(3) {
            restored.leftForeground(); restored.returnedToForeground()
            assertNull(restored.claimNext(missing, true))
        }
        assertTrue(restored.claimManual(OnboardingPermission.ALL_FILES, missing, true))
        assertFalse(restored.beginSettingsReview(true, false))
    }

    @Test fun unavailableAllFilesPageReleasesRequestAndContinuesWithReadPermission() {
        val sequence = OnboardingPermissionRequestSequence()
        val status = missing.copy(microphoneAllowed = true, storageRequired = false)
        assertEquals(OnboardingPermission.ALL_FILES, sequence.claimNext(status, true))
        sequence.beginAllFilesReview()
        sequence.completed(OnboardingPermission.ALL_FILES)
        assertFalse(sequence.settingsReview)
        assertFalse(sequence.settingsLeftForeground)
        assertEquals(OnboardingPermission.RECOVERY, sequence.claimNext(status, true))
    }

    @Test fun olderAndroidSkipsAllFilesWithoutChangingLegacyPermissionOrder() {
        val sequence = OnboardingPermissionRequestSequence()
        val status = missing.copy(allFilesAvailable = false)
        assertFalse(sequence.claimManual(OnboardingPermission.ALL_FILES, status, true))
        for (permission in listOf(OnboardingPermission.MICROPHONE, OnboardingPermission.STORAGE,
                OnboardingPermission.RECOVERY, OnboardingPermission.NOTIFICATIONS, OnboardingPermission.BATTERY)) {
            assertEquals(permission, sequence.claimNext(status, true))
            sequence.completed(permission)
        }
        assertNull(sequence.claimNext(status, true))
    }


    @Test fun allFilesIsOptionalForContinueAndGrantSatisfiesAudioRecovery() {
        val ready = missing.copy(microphoneAllowed = true, storageAllowed = true,
            recoveryAllowed = true, notificationsAllowed = true)
        assertTrue(ready.isReadyForHighlightedContinue())
        assertTrue(ready.copy(recoveryAllowed = false, allFilesAllowed = true).isReadyForHighlightedContinue())
        assertFalse(ready.copy(recoveryAllowed = false).isReadyForHighlightedContinue())
    }

}
