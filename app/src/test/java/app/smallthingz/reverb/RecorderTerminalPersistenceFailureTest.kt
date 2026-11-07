package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderTerminalPersistenceFailureTest {
    @Test fun failedBooleanCommitPreservesNativeCause() {
        assertEquals("Microphone failed: dead object. Persistence failed.",
            combineRecorderTerminalPersistenceFailures("Microphone failed: dead object.", false,
                "Persistence failed.", emptyList(), null))
    }
    @Test fun commitRollbackAndSealDetailsFollowOriginalCauseWithOneSummary() {
        assertEquals("Microphone failed. Persistence failed. Commit denied. Rollback read-only. Seal full.",
            combineRecorderTerminalPersistenceFailures("Microphone failed.", false, "Persistence failed.",
                listOf("Commit denied.", "Rollback read-only."), "Seal full."))
    }
    @Test fun identicalPersistenceDetailsAreNotRepeated() {
        assertEquals("Microphone failed. Persistence failed. Disk full.",
            combineRecorderTerminalPersistenceFailures("Microphone failed.", false, "Persistence failed.",
                listOf("Disk full.", "Disk full."), "Disk full."))
    }
    @Test fun successfulIntentStillReportsSealFailure() {
        assertEquals("Microphone failed. Persistence failed. Seal denied.",
            combineRecorderTerminalPersistenceFailures("Microphone failed.", true, "Persistence failed.",
                emptyList(), "Seal denied."))
    }
    @Test fun successfulCleanupLeavesOriginalMessageUntouched() {
        assertEquals("Microphone failed.", combineRecorderTerminalPersistenceFailures(
            "Microphone failed.", true, "Persistence failed.", emptyList(), null))
    }
}
