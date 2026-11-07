package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDeletionSummaryTest {
    @Test fun largeMixedBatchBoundsNamesAndKeepsEveryFailureReason() {
        val names = List(1000) { "recording-$it-" + "x".repeat(1000) }
        val reasons = RecordingDeletionFailureReason.entries
        val result = RecordingDeletionBatchResult(
            outcomes = List(1000) { it < 100 }, cleanupFailed = true,
            consentIndices = (100 until 150).toSet(),
            failures = (150 until 1000).associateWith { RecordingDeletionFailure(reasons[it % reasons.size]) },
        )
        val summary = recordingDeletionSummary(names, result)
        assertEquals(100, summary.deleted)
        assertEquals(850, summary.failed)
        assertEquals(50, summary.awaitingConsent)
        assertEquals(2, summary.names.size)
        assertTrue(summary.names.all { it.length <= 48 })
        assertEquals(848, summary.additionalNames)
        assertEquals(reasons.toSet(), summary.reasons)
        // Presentation compaction never drops the diagnostic data for any exact failed row.
        assertEquals(850, result.failures.size)
    }

    @Test fun missingOutcomeIsFailureAndConsentIsNeverCountedAsDeleted() {
        val result = RecordingDeletionBatchResult(listOf(true, false), false, setOf(1))
        val summary = recordingDeletionSummary(listOf("deleted", "approval", "unknown"), result)
        assertEquals(1, summary.deleted)
        assertEquals(1, summary.awaitingConsent)
        assertEquals(1, summary.failed)
        assertEquals(listOf("unknown"), summary.names)
        assertEquals(setOf(RecordingDeletionFailureReason.CATALOG_UNAVAILABLE), summary.reasons)
    }
}
