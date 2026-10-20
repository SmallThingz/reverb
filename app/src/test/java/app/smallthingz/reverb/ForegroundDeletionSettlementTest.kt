package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundDeletionSettlementTest {
    private fun recording(id: String, identity: String = "stat:$id") = RecordingEntity(
        id = id,
        displayName = id.substringAfterLast('/'),
        mimeType = "audio/wav",
        startedAtMillis = 1L,
        durationMillis = 2L,
        sizeBytes = 3L,
        codecSummary = "PCM",
        storageType = RecordingStorageType.FILE,
        directoryId = "/recordings",
        fileIdentity = identity,
        createdAtMillis = 1L,
        lastSeenAtMillis = 1L,
    )

    @Test
    fun newerRefreshReplacementSurvivesTerminalAndStaleFollowup() {
        val old = recording("/recordings/a.wav", "old-object")
        val replacement = old.copy(fileIdentity = "restored-object")
        val deletedSibling = recording("/recordings/b.wav")
        val failed = recording("/recordings/c.wav")
        val untouched = recording("/recordings/d.wav")
        val attempted = listOf(old, deletedSibling, failed)
        val pending = attempted.associateByTo(linkedMapOf()) { it.id }
        val generation = LibraryRefreshGeneration { }
        val physicalGeneration = generation.invalidate()

        // A pull-to-refresh completes while the physical batch still owns its final result.
        val refreshedGeneration = generation.request(showSpinner = true)
        var rows = listOf(replacement, deletedSibling, failed, untouched)
        generation.complete(refreshedGeneration)
        pending.remove(old.id) // The fresh snapshot invalidates the old optimistic target.

        rows = recordingsAfterForegroundDeletion(rows, listOf(old, deletedSibling))
        releaseCompletedForegroundDeletionTargets(pending, attempted)
        assertEquals(listOf(replacement, failed, untouched), rows)
        assertTrue(pending.isEmpty())

        val followupCatalog = listOf(replacement, failed, untouched)
        assertFalse(generation.isCurrent(physicalGeneration))
        if (generation.isCurrent(physicalGeneration)) rows = followupCatalog
        assertEquals(listOf(replacement, failed, untouched), rows)
    }

    @Test
    fun mixedPhysicalOutcomesKeepFailedAndUnrelatedRows() {
        val deleted = recording("/recordings/a.wav")
        val failed = recording("/recordings/b.wav")
        val untouched = recording("/recordings/c.wav")
        val pending = linkedMapOf(deleted.id to deleted, failed.id to failed)
        val rows = recordingsAfterForegroundDeletion(listOf(deleted, failed, untouched), listOf(deleted))
        releaseCompletedForegroundDeletionTargets(pending, listOf(deleted, failed))
        assertEquals(listOf(failed, untouched), rows)
        assertTrue(pending.isEmpty())
        assertTrue(deletionBatchFailed(requestedCount = 2, deletedCount = 1, hadError = false))
    }

    @Test
    fun cumulativeUndoWindowStillCommitsEverySuccessfulSelectedObject() {
        val a = recording("/recordings/a.wav")
        val b = recording("/recordings/b.wav")
        val untouched = recording("/recordings/c.wav")
        val state = LibraryDeletionState()
        val first = requireNotNull(state.extendUndoWindow())
        val second = requireNotNull(state.extendUndoWindow())
        assertFalse(state.beginForegroundCommit(first))
        assertTrue(state.beginForegroundCommit(second))
        val pending = linkedMapOf(a.id to a, b.id to b)
        val rows = recordingsAfterForegroundDeletion(listOf(a, b, untouched), listOf(a, b))
        releaseCompletedForegroundDeletionTargets(pending, listOf(a, b))
        state.completeForegroundCommit()
        assertEquals(listOf(untouched), rows)
        assertTrue(pending.isEmpty())
        assertTrue(state.acceptsDeletions)
        assertFalse(deletionBatchFailed(requestedCount = 2, deletedCount = 2, hadError = false))
    }

    @Test
    fun refreshedDerivedMetadataDoesNotProtectTheDeletedObject() {
        val deleted = recording("/recordings/a.wav")
        val refreshed = deleted.copy(lastSeenAtMillis = 20L, waveformData = "refreshed")
        assertTrue(recordingsAfterForegroundDeletion(listOf(refreshed), listOf(deleted)).isEmpty())
    }

    @Test
    fun sameIdWithDifferentStorageAndEmptySuccessesRemainVisible() {
        val old = recording("/recordings/a.wav")
        val differentStorage = old.copy(storageType = RecordingStorageType.DOCUMENT)
        val failed = recording("/recordings/b.wav")
        assertEquals(
            listOf(differentStorage, failed),
            recordingsAfterForegroundDeletion(listOf(differentStorage, failed), listOf(old)),
        )
        assertEquals(listOf(old, failed), recordingsAfterForegroundDeletion(listOf(old, failed), emptyList()))
    }
}
