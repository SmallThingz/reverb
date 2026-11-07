package app.smallthingz.reverb

import java.nio.file.NoSuchFileException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDeletionFeedbackCauseTest {
    @Test fun identityVerificationFailureRemainsDistinctInMixedBatchSummary() {
        val result = RecordingDeletionBatchResult(listOf(true, false), cleanupFailed = false,
            failures = mapOf(1 to RecordingDeletionFailure(RecordingDeletionFailureReason.IDENTITY_UNVERIFIED)))
        val summary = recordingDeletionSummary(listOf("deleted", "unverified"), result)
        assertEquals(1, summary.deleted)
        assertEquals(1, summary.failed)
        assertEquals(setOf(RecordingDeletionFailureReason.IDENTITY_UNVERIFIED), summary.reasons)
        assertTrue(recordingDeletionSupplementalReasons(result).isEmpty())
    }

    @Test fun missingCleanupMetadataDoesNotClaimSelectedRecordingChanged() {
        val result = RecordingDeletionBatchResult(listOf(true, true), cleanupFailed = true,
            cleanupFailure = NoSuchFileException("private-journal"))
        val summary = recordingDeletionSummary(listOf("one", "two"), result)
        assertEquals(2, summary.deleted)
        assertEquals(0, summary.failed)
        assertEquals(setOf(RecordingDeletionFailureReason.METADATA_CLEANUP), summary.reasons)
        assertTrue(recordingDeletionSupplementalReasons(result).isEmpty())
    }

    @Test fun missingJournalAdmissionRetainsJournalReasonWithoutSelectionDiagnosis() {
        val result = RecordingDeletionBatchResult(listOf(false, true), cleanupFailed = false,
            failures = mapOf(0 to RecordingDeletionFailure(RecordingDeletionFailureReason.JOURNAL_FAILED,
                NoSuchFileException("private-journal"))))
        val summary = recordingDeletionSummary(listOf("one", "two"), result)
        assertEquals(setOf(RecordingDeletionFailureReason.JOURNAL_FAILED), summary.reasons)
        assertFalse(AppFailureReason.MISSING in recordingDeletionSupplementalReasons(result))
    }

    @Test fun provenSelectionMismatchStillComesFromAuthoritativeDomainReason() {
        val result = RecordingDeletionBatchResult(listOf(false, true), cleanupFailed = false,
            failures = mapOf(0 to RecordingDeletionFailure(RecordingDeletionFailureReason.IDENTITY_CHANGED,
                NoSuchFileException("selected-recording"))))
        val summary = recordingDeletionSummary(listOf("one", "two"), result)
        assertEquals(setOf(RecordingDeletionFailureReason.IDENTITY_CHANGED), summary.reasons)
        assertTrue(recordingDeletionSupplementalReasons(result).isEmpty())
    }

    @Test fun independentPermissionCauseStillAddsActionableSupplement() {
        val result = RecordingDeletionBatchResult(listOf(false, true), cleanupFailed = false,
            failures = mapOf(0 to RecordingDeletionFailure(RecordingDeletionFailureReason.PROVIDER_REJECTED,
                SecurityException("denied"))))
        assertEquals(setOf(AppFailureReason.PERMISSION), recordingDeletionSupplementalReasons(result))
    }
}
