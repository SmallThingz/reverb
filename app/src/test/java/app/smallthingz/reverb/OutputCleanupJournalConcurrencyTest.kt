package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputCleanupJournalConcurrencyTest {
    @Test
    fun staleReplayRemoval_cannotRemoveNewerRecordForSameId() {
        val old = encodePendingOutputCleanupRecord(
            PendingOutputCleanupRecord(
                storageType = RecordingStorageType.FILE,
                id = "/recordings/clip.wav",
                byteCount = 10L,
                sha256Hex = "11".repeat(32),
                fileKey = "old-key",
            ),
        )
        val newer = encodePendingOutputCleanupRecord(
            PendingOutputCleanupRecord(
                storageType = RecordingStorageType.FILE,
                id = "/recordings/clip.wav",
                byteCount = 20L,
                sha256Hex = "22".repeat(32),
                fileKey = "new-key",
            ),
        )

        assertEquals(
            setOf(newer),
            pendingOutputCleanupEntriesAfterExactRemoval(setOf(newer), old),
        )
        assertEquals(
            setOf(newer),
            pendingOutputCleanupEntriesAfterExactRemoval(setOf(old, newer), old),
        )
        assertTrue(pendingOutputCleanupEntriesAfterExactRemoval(setOf(old), old).isEmpty())
    }
}
