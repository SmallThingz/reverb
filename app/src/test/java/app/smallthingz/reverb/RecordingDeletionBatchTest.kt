package app.smallthingz.reverb

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDeletionBatchTest {
    @Test
    fun failedSingleJournalRetirementKeepsPhysicalSuccessAndReportsFailure() = runBlocking {
        val events = mutableListOf<String>()
        val reports = mutableListOf<Exception?>()
        val outcome = completeRecordingDeletionMetadata(
            "deleted", persistDeleted = { events += "phase"; true },
            retireCatalog = { events += "catalog" },
            retireJournal = { events += "retirement"; false }, journalBatch = null,
            onCleanupFailure = { reports += it },
        )
        assertTrue(outcome)
        assertEquals(listOf("phase", "catalog", "retirement"), events)
        assertEquals(listOf<Exception?>(null), reports)
    }

    @Test
    fun throwingPhaseAndCatalogCleanupKeepPhysicalSuccessAndReportOriginalError() = runBlocking {
        for (stage in listOf("phase", "catalog")) {
            val failure = IOException("$stage failed")
            val events = mutableListOf<String>()
            val failures = mutableListOf<Exception?>()
            val batch = RecordingDeletionJournalBatch { events += "retire"; true }
            val outcome = completeRecordingDeletionMetadata(
                "deleted",
                persistDeleted = { events += "phase"; if (stage == "phase") throw failure; true },
                retireCatalog = { events += "catalog"; throw failure },
                retireJournal = { error("unexpected single retirement") },
                journalBatch = batch,
                onCleanupFailure = { failures += it },
            )
            batch.flush()
            assertEquals(if (stage == "phase") listOf("phase") else listOf("phase", "catalog"), events)
            assertEquals(listOf(failure), failures)
            assertEquals(listOf(true), batch.result(listOf(outcome)).outcomes)
            assertTrue(batch.result(listOf(outcome)).cleanupFailed)
        }
    }

    @Test
    fun throwingSingleJournalRetirementKeepsPhysicalSuccessAndIsObservable() = runBlocking {
        val failure = IOException("single retirement failed")
        val reports = mutableListOf<Exception?>()
        val outcome = completeRecordingDeletionMetadata(
            "deleted", persistDeleted = { true }, retireCatalog = {},
            retireJournal = { throw failure }, journalBatch = null,
            onCleanupFailure = { reports += it },
        )
        assertTrue(outcome)
        assertEquals(listOf(failure), reports)
    }

    @Test
    fun postPhysicalCancellationPropagatesFromEveryMetadataStage() = runBlocking {
        for (stage in listOf("phase", "catalog", "retirement")) {
            val cancellation = CancellationException("cancelled $stage")
            val reports = mutableListOf<Exception?>()
            try {
                completeRecordingDeletionMetadata(
                    "deleted", persistDeleted = { if (stage == "phase") throw cancellation; true },
                    retireCatalog = { if (stage == "catalog") throw cancellation },
                    retireJournal = { throw cancellation }, journalBatch = null,
                    onCleanupFailure = { reports += it },
                )
                error("cancellation was swallowed")
            } catch (error: CancellationException) {
                assertTrue(error === cancellation)
            }
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun successfulSiblingDoesNotEraseEarlierThrownMetadataFailure() = runBlocking {
        val reports = mutableListOf<Exception?>()
        val batch = RecordingDeletionJournalBatch { true }
        val outcomes = runRecordingDeletionBatch(listOf(1, 2), replayPending = {}) { target ->
            completeRecordingDeletionMetadata(
                "deleted:$target", persistDeleted = { if (target == 1) throw IOException("phase failed"); true },
                retireCatalog = {}, retireJournal = { true }, journalBatch = batch,
                onCleanupFailure = { reports += it },
            )
        }
        batch.flush()
        assertEquals(listOf(true, true), batch.result(outcomes).outcomes)
        assertTrue(batch.result(outcomes).cleanupFailed)
        assertEquals(1, reports.size)
    }

    @Test
    fun throwingChunkRetirementReportsOnceAndKeepsPhysicalOutcome() = runBlocking {
        val failure = IOException("chunk retirement failed")
        val reports = mutableListOf<Exception?>()
        val batch = RecordingDeletionJournalBatch(chunkSize = 1, onCleanupFailure = { reports += it }) {
            throw failure
        }
        val outcome = completeRecordingDeletionMetadata(
            "deleted", persistDeleted = { true }, retireCatalog = {}, retireJournal = { true }, journalBatch = batch,
            onCleanupFailure = { error("duplicate failure report") },
        )
        assertEquals(listOf(failure), reports)
        assertEquals(listOf(true), batch.result(listOf(outcome)).outcomes)
        assertTrue(batch.result(listOf(outcome)).cleanupFailed)
    }

    @Test
    fun throwingDiagnosticCannotConvertPhysicalSuccessIntoFailure() = runBlocking {
        val failure = IOException("phase failed")
        val reportingFailure = IOException("diagnostic failed")
        val batch = RecordingDeletionJournalBatch { true }
        val outcome = completeRecordingDeletionMetadata(
            "deleted", persistDeleted = { throw failure }, retireCatalog = {}, retireJournal = { true }, journalBatch = batch,
            onCleanupFailure = { throw reportingFailure },
        )
        assertTrue(outcome)
        assertTrue(batch.result(listOf(outcome)).cleanupFailed)
        assertEquals(listOf(reportingFailure), failure.suppressed.toList())
    }

    @Test
    fun failedDeletedPhaseCommitKeepsReplayableCatalogAndPhysicalSuccess() = runBlocking {
        val events = mutableListOf<String>()
        val batch = RecordingDeletionJournalBatch { events += "retire journal"; true }
        val outcome = completeRecordingDeletionMetadata(
            deletedEntry = "deleted",
            persistDeleted = { events += "phase commit"; false },
            retireCatalog = { events += "retire catalog" },
            retireJournal = { events += "single retirement"; true },
            journalBatch = batch,
        )
        batch.flush()
        assertEquals(listOf("phase commit"), events)
        assertEquals(listOf(true), batch.result(listOf(outcome)).outcomes)
        assertTrue(batch.result(listOf(outcome)).cleanupFailed)
    }

    @Test
    fun laterSuccessfulPhaseDoesNotEraseEarlierCleanupFailure() = runBlocking {
        val events = mutableListOf<String>()
        val batch = RecordingDeletionJournalBatch { events += "retire journal"; true }
        val outcomes = (1..2).map { target ->
            completeRecordingDeletionMetadata(
                deletedEntry = "deleted:$target",
                persistDeleted = { target == 2 },
                retireCatalog = { events += "retire catalog:$target" },
                retireJournal = { error("unexpected single retirement") },
                journalBatch = batch,
            )
        }
        batch.flush()
        assertEquals(listOf("retire catalog:2", "retire journal"), events)
        assertEquals(listOf(true, true), batch.result(outcomes).outcomes)
        assertTrue(batch.result(outcomes).cleanupFailed)
    }

    @Test
    fun cleanupFailurePreservesPhysicalSuccessForTerminalUiRelease() {
        val batch = RecordingDeletionJournalBatch { false }
        batch.defer("physically-deleted")
        batch.flush()
        val result = batch.result(listOf(true, false))
        assertEquals(listOf(true, false), result.outcomes)
        assertTrue(result.cleanupFailed)
        val visibleTargets = listOf("deleted", "failed").filterIndexed { index, _ -> !result.outcomes[index] }
        assertEquals(listOf("failed"), visibleTargets)
    }

    @Test
    fun cancelledRetirementPropagatesAndRetainsFailureEvidence() {
        val cancellation = CancellationException("cancelled retirement")
        val batch = RecordingDeletionJournalBatch { throw cancellation }
        batch.defer("completed")
        try {
            batch.flush()
            error("cancellation was swallowed")
        } catch (error: CancellationException) {
            assertTrue(error === cancellation)
        }
        assertEquals(setOf("completed"), batch.failedEntries)
        assertEquals(listOf(true), batch.result(listOf(true)).outcomes)
        assertTrue(batch.result(listOf(true)).cleanupFailed)
    }

    @Test
    fun capacityFlushNeverEvictsFailedDeletionAuthorityAcrossChunks() {
        val journal = mutableSetOf("unresolved")
        val batch = RecordingDeletionJournalBatch(chunkSize = 3) { entries ->
            journal.removeAll(entries)
            true
        }
        journal += "completed-one"
        batch.defer("completed-one")
        journal += "completed-two"
        batch.defer("completed-two")
        batch.prepareForAddition { journal.size < 3 }
        assertEquals(setOf("unresolved"), journal)
        journal += "completed-three"
        batch.defer("completed-three")
        batch.flush()
        assertEquals(setOf("unresolved"), journal)
        assertTrue(!batch.result(listOf(true, false, true)).cleanupFailed)
    }

    @Test
    fun successfulJournalRetirementUsesOneCommitPerBoundedChunk() {
        val journal = mutableSetOf<String>()
        var retirementCommits = 0
        val batch = RecordingDeletionJournalBatch { entries ->
            assertTrue(entries.size <= 16)
            retirementCommits++
            journal.removeAll(entries)
            true
        }
        repeat(1000) { index ->
            val entry = "deleted:$index"
            journal += entry
            batch.defer(entry)
        }
        batch.flush()
        assertEquals(63, retirementCommits)
        assertTrue(journal.isEmpty())
        assertTrue(batch.failedEntries.isEmpty())
    }

    @Test
    fun capacityPressureFlushesOnlyCompletedEntriesBeforePlanning() {
        val journal = mutableSetOf("unresolved", "completed")
        var commits = 0
        val batch = RecordingDeletionJournalBatch { entries ->
            commits++
            journal.removeAll(entries)
            true
        }
        batch.defer("completed")
        batch.prepareForAddition { journal.size < 2 }
        assertEquals(1, commits)
        assertEquals(setOf("unresolved"), journal)
        assertTrue(journal.size < 2)
    }

    @Test
    fun retirementCannotRemoveAConcurrentNewerEntryForTheSameTarget() {
        val journal = mutableSetOf("target:old:deleted")
        val batch = RecordingDeletionJournalBatch { entries ->
            journal.removeAll(entries)
            true
        }
        batch.defer("target:old:deleted")
        journal.clear()
        journal += "target:new:planned"
        batch.flush()
        assertEquals(setOf("target:new:planned"), journal)
    }

    @Test
    fun failedRetirementKeepsDurableEvidenceAndReportsExactFailedEntries() {
        val journal = mutableSetOf("completed")
        val batch = RecordingDeletionJournalBatch { false }
        batch.defer("completed")
        batch.flush()
        assertEquals(setOf("completed"), journal)
        assertEquals(setOf("completed"), batch.failedEntries)
    }

    @Test
    fun throwingRetirementDoesNotAbortIndependentLaterChunks() {
        var commits = 0
        val batch = RecordingDeletionJournalBatch(chunkSize = 1) {
            if (++commits == 1) throw IOException("journal commit failed")
            true
        }
        batch.defer("failed")
        batch.defer("succeeded")
        assertEquals(2, commits)
        assertEquals(setOf("failed"), batch.failedEntries)
    }

    @Test
    fun sufficientCapacityDoesNotFlushTheCurrentChunkEarly() {
        var commits = 0
        val batch = RecordingDeletionJournalBatch { commits++; true }
        batch.defer("completed")
        batch.prepareForAddition { true }
        assertEquals(0, commits)
        batch.flush()
        assertEquals(1, commits)
    }

    @Test
    fun largeBatchReplaysRecoveryOnceBeforeAnyDelete() = runBlocking {
        val events = mutableListOf<Int>()
        val recordings = (1..1000).toList()
        val results = runRecordingDeletionBatch(
            recordings,
            replayPending = { events += 0 },
            delete = { events += it; true },
        )
        assertEquals(listOf(0) + recordings, events)
        assertEquals(1000, results.size)
        assertTrue(results.all { it })
    }

    @Test
    fun failedAttemptsStayIndependentAndAreNotReplayedWithinBatch() = runBlocking {
        var replays = 0
        val attempted = mutableListOf<Int>()
        val results = runRecordingDeletionBatch(
            listOf(1, 2, 3, 4),
            replayPending = { replays++ },
            delete = {
                attempted += it
                if (it == 2) throw IOException("uncertain delete")
                it != 3
            },
        )
        assertEquals(1, replays)
        assertEquals(listOf(1, 2, 3, 4), attempted)
        assertEquals(listOf(true, false, false, true), results)
    }

    @Test
    fun recoveryFailurePreventsEveryPhysicalAttempt() = runBlocking {
        var attempts = 0
        val results = runRecordingDeletionBatch(
            listOf(1, 2),
            replayPending = { throw IOException("journal unavailable") },
            delete = { attempts++; true },
        )
        assertEquals(0, attempts)
        assertEquals(listOf(false, false), results)
    }

    @Test
    fun emptyBatchDoesNoRecoveryWork() = runBlocking {
        val results = runRecordingDeletionBatch<Int>(
            emptyList(),
            replayPending = { error("unexpected recovery") },
            delete = { error("unexpected delete") },
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun cancellationStopsFurtherAttempts() = runBlocking {
        val attempted = mutableListOf<Int>()
        val cancellation = CancellationException("cancelled")
        try {
            runRecordingDeletionBatch(
                listOf(1, 2, 3),
                replayPending = {},
                delete = {
                    attempted += it
                    if (it == 2) throw cancellation
                    true
                },
            )
            error("cancellation was swallowed")
        } catch (error: CancellationException) {
            assertTrue(error === cancellation)
        }
        assertEquals(listOf(1, 2), attempted)
    }
}
