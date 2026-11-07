package app.smallthingz.reverb

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecordingDeletionJournalStageTest {
    @Test fun capacityAndWriteFailuresKeepJournalStageAndOriginalException() {
        for (error in listOf(IllegalStateException("journal full"), IOException("journal write failed"),
            SecurityException("journal access denied"))) {
            var failure = RecordingDeletionFailure(RecordingDeletionFailureReason.PROVIDER_REJECTED)
            try {
                recordingDeletionJournalStage<Unit>({ failure = it }) { throw error }
                fail("Expected the original journal exception")
            } catch (caught: Exception) {
                assertSame(error, caught)
                failure = recordingDeletionFailureAfterException(failure, caught)
            }
            assertEquals(RecordingDeletionFailureReason.JOURNAL_FAILED, failure.reason)
            assertSame(error, failure.cause)
        }
    }

    @Test fun cancellationAndFatalFailurePropagateWithoutJournalDiagnosis() {
        for (error in listOf(CancellationException("cancelled"), AssertionError("fatal"))) {
            var reported = false
            try {
                recordingDeletionJournalStage<Unit>({ reported = true }) { throw error }
                fail("Expected cancellation or fatal failure")
            } catch (caught: Throwable) {
                assertSame(error, caught)
            }
            assertFalse(reported)
        }
    }

    @Test fun failedRetirementCannotHandOffConsent() {
        val error = IOException("retirement failed")
        var handedOff = false
        var failure: RecordingDeletionFailure? = null
        try {
            handOffRecordingDeletionConsent(
                stillSelectedAsset = { true },
                retireUncommittedIntent = {
                    recordingDeletionJournalStage({ failure = it }) { throw error }
                },
                handOff = { handedOff = true },
            )
            fail("Expected the original retirement exception")
        } catch (caught: IOException) {
            assertSame(error, caught)
        }
        assertFalse(handedOff)
        assertEquals(RecordingDeletionFailureReason.JOURNAL_FAILED, failure?.reason)
        assertSame(error, failure?.cause)
    }

    @Test fun journalStageDoesNotPromoteFalseAdmissionOrAlterSuccessfulAdmission() {
        var reported = false
        assertFalse(recordingDeletionJournalStage({ reported = true }) { false })
        assertTrue(recordingDeletionJournalStage({ reported = true }) { true })
        assertFalse(reported)
    }

    @Test fun journalDiagnosisCannotAttachToDifferentLaterException() {
        val previous = RecordingDeletionFailure(RecordingDeletionFailureReason.JOURNAL_FAILED, IOException("old"))
        val later = SecurityException("new failure")
        val result = recordingDeletionFailureAfterException(previous, later)
        assertEquals(RecordingDeletionFailureReason.PERMISSION_REQUIRED, result.reason)
        assertSame(later, result.cause)
    }
}
