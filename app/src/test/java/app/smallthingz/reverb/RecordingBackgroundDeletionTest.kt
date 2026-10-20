package app.smallthingz.reverb

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.LinkedList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingBackgroundDeletionTest {
    private fun recording(id: String, identity: String = "stat:$id") = RecordingEntity(
        id = id,
        displayName = File(id).name,
        mimeType = "audio/wav",
        startedAtMillis = 1L,
        durationMillis = 2L,
        sizeBytes = 3L,
        codecSummary = "PCM",
        storageType = RecordingStorageType.FILE,
        directoryId = File(id).parent.orEmpty(),
        fileIdentity = identity,
    )

    private fun beginBackground(state: LibraryDeletionState): Long {
        assertNotNull(state.extendUndoWindow())
        return requireNotNull(state.handoffToBackground(hasPending = true))
    }

    @Test
    fun successfulPhysicalDeleteUnlocksOtherRowsDespiteConfiguredFolderFailure() = runBlocking {
        val parent = File("build/tmp/background-deletion").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "settlement-").toFile()
        val process = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val fileA = File(directory, "a.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val fileB = File(directory, "b.wav").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val a = recording(fileA.absolutePath)
            val b = recording(fileB.absolutePath)
            val state = LibraryDeletionState()
            val revision = beginBackground(state)
            val pending = linkedMapOf(a.id to a)
            var rows = listOf(a, b)
            var catalog = rows
            var failures = 0
            val receipt = process.launchRecordingBackgroundDeletion(
                previous = null,
                recordings = listOf(a),
                deleteBatch = { targets ->
                    val outcomes = runRecordingDeletionBatch(targets, replayPending = {}) { target ->
                        Files.delete(File(target.id).toPath())
                        catalog = catalog.filterNot { it.id == target.id }
                        true
                    }
                    RecordingDeletionBatchResult(outcomes, cleanupFailed = false)
                },
                reportFailure = { failures++ },
            )
            val result = receipt.await()
            assertFalse(fileA.exists())
            assertTrue(fileB.exists())
            assertEquals(listOf(b), catalog)

            // The configured tree can fail even though this batch and the local catalog settled.
            val refreshError = runCatching<List<RecordingEntity>> {
                throw ConfiguredRecordingDirectoryUnavailableException("content://storage/tree/disconnected")
            }.exceptionOrNull()
            assertTrue(refreshError is ConfiguredRecordingDirectoryUnavailableException)
            assertTrue(settleCompletedBackgroundDeletion(
                state, revision, listOf(a), result, pending, rows,
            ) { rows = it })
            assertEquals(listOf(b), rows)
            assertTrue(pending.isEmpty())
            assertTrue(state.acceptsDeletions)
            assertNotNull(state.extendUndoWindow())
            assertEquals(0, failures)
        } finally {
            process.cancel()
            check(directory.deleteRecursively())
        }
    }

    @Test
    fun partialTerminalKeepsFailedRowsAndReportsBeforeReceiptCompletes() = runBlocking {
        val a = recording("/recordings/a.wav")
        val b = recording("/recordings/b.wav")
        val untouched = recording("/recordings/untouched.wav")
        val state = LibraryDeletionState()
        val revision = beginBackground(state)
        val pending = linkedMapOf(a.id to a, b.id to b)
        var rows = listOf(a, b, untouched)
        val events = mutableListOf<String>()
        val receipt = launchRecordingBackgroundDeletion(
            previous = null,
            recordings = listOf(a, b),
            deleteBatch = { RecordingDeletionBatchResult(listOf(true, false), cleanupFailed = true) },
            reportFailure = { events += "failure" },
        )
        val result = receipt.await()
        events += "receipt"
        assertTrue(settleCompletedBackgroundDeletion(
            state, revision, listOf(a, b), result, pending, rows,
        ) { rows = it })
        assertEquals(listOf("failure", "receipt"), events)
        assertEquals(listOf(b, untouched), rows)
        assertTrue(pending.isEmpty())
        assertTrue(state.acceptsDeletions)
    }

    @Test
    fun unknownOuterFailureKeepsProtectionUntilAuthoritativeRecovery() = runBlocking {
        val process = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val a = recording("/recordings/a.wav")
            val state = LibraryDeletionState()
            val revision = beginBackground(state)
            val pending = linkedMapOf(a.id to a)
            val expected = IOException("terminal outcome unavailable")
            var failures = 0
            val receipt = process.launchRecordingBackgroundDeletion(
                previous = null,
                recordings = listOf(a),
                deleteBatch = { throw expected },
                reportFailure = { failures++ },
            )
            val observed = runCatching { receipt.await() }.exceptionOrNull()
            assertTrue(observed is IOException)
            assertEquals(expected.message, observed?.message)
            assertEquals(1, failures)
            assertTrue(state.committedInBackground)
            assertFalse(state.acceptsDeletions)
            assertEquals(a, pending[a.id])

            // A failed listing does not invoke settlement or invent a per-record outcome.
            assertTrue(runCatching { throw IOException("folder unavailable") }.isFailure)
            assertEquals(revision, requireNotNull(state.backgroundCommitRevision))
            assertEquals(a, pending[a.id])
            // Only a successful authoritative read after the exact receipt can recover this path.
            receipt.join()
            assertTrue(state.completeBackgroundCommit(revision) { pending.clear() })
            assertTrue(state.acceptsDeletions)
        } finally {
            process.cancel()
        }
    }

    @Test
    fun missingOrExtraOutcomesAreUnknownAndNeverRemoveRows() = runBlocking {
        val a = recording("/recordings/a.wav")
        for (outcomes in listOf(emptyList(), listOf(true, false))) {
            val state = LibraryDeletionState()
            val revision = beginBackground(state)
            val pending = linkedMapOf(a.id to a)
            var rows = listOf(a)
            var failures = 0
            val receipt = launchRecordingBackgroundDeletion(
                previous = null,
                recordings = listOf(a),
                deleteBatch = { RecordingDeletionBatchResult(outcomes, cleanupFailed = false) },
                reportFailure = { failures++ },
            )
            assertFalse(settleCompletedBackgroundDeletion(
                state, revision, listOf(a), receipt.await(), pending, rows,
            ) { rows = it })
            assertEquals(1, failures)
            assertEquals(listOf(a), rows)
            assertEquals(a, pending[a.id])
            assertFalse(state.acceptsDeletions)
        }
    }

    @Test
    fun disposedUiWaiterCannotCancelProcessReceiptOrLoseItsResult() = runBlocking {
        val process = CoroutineScope(coroutineContext + SupervisorJob())
        val ui = CoroutineScope(coroutineContext + Job())
        try {
            val a = recording("/recordings/a.wav")
            val b = recording("/recordings/b.wav")
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var catalog = listOf(a, b)
            var uiDelivered = false
            val receipt = process.launchRecordingBackgroundDeletion(
                previous = null,
                recordings = listOf(a),
                deleteBatch = {
                    started.complete(Unit)
                    release.await()
                    catalog = listOf(b)
                    RecordingDeletionBatchResult(listOf(true), cleanupFailed = false)
                },
                reportFailure = { error("unexpected failure") },
            )
            val waiter = ui.launch(start = CoroutineStart.UNDISPATCHED) {
                receipt.await()
                uiDelivered = true
            }
            started.await()
            ui.cancel()
            waiter.join()
            assertFalse(receipt.isCancelled)
            release.complete(Unit)
            assertEquals(listOf(true), receipt.await().outcomes)
            assertFalse(uiDelivered)

            // A new Library has no old optimistic batch; its repository first paint joins the
            // same process job before reading the durable catalog, including after UI disposal.
            receipt.join()
            val reopened = LibraryDeletionState()
            assertEquals(listOf(b), catalog)
            assertTrue(reopened.acceptsDeletions)
            assertEquals(listOf(true), receipt.await().outcomes)
        } finally {
            ui.cancel()
            process.cancel()
        }
    }

    @Test
    fun activeAndNewerBatchesRejectOlderRefreshAndReceiptCallbacks() {
        val a = recording("/recordings/a.wav")
        val b = recording("/recordings/b.wav")
        val state = LibraryDeletionState()
        val first = beginBackground(state)
        val result = RecordingDeletionBatchResult(listOf(true), cleanupFailed = false)
        val pending = linkedMapOf(a.id to a)
        var rows = listOf(a, b)
        assertNull(state.extendUndoWindow())
        assertNull(state.handoffToBackground(hasPending = true))
        assertTrue(settleCompletedBackgroundDeletion(
            state, first, listOf(a), result, pending, rows,
        ) { rows = it })

        val second = beginBackground(state)
        pending[b.id] = b
        assertFalse(state.completeBackgroundCommit(first) { error("stale refresh settled newer batch") })
        assertFalse(settleCompletedBackgroundDeletion(
            state, first, listOf(a), result, pending, rows,
        ) { error("stale receipt changed rows") })
        assertEquals(second, requireNotNull(state.backgroundCommitRevision))
        assertEquals(listOf(b), rows)
        assertEquals(b, pending[b.id])
        assertFalse(state.acceptsDeletions)
    }

    @Test
    fun successfulOldTargetCannotRemoveAReplacementIdentity() {
        val a = recording("/recordings/a.wav", "old-object")
        val replacement = a.copy(fileIdentity = "replacement-object")
        val b = recording("/recordings/b.wav")
        val state = LibraryDeletionState()
        val revision = beginBackground(state)
        val pending = linkedMapOf(a.id to replacement, b.id to b)
        var rows = listOf(replacement, b)
        assertTrue(settleCompletedBackgroundDeletion(
            state, revision, listOf(a),
            RecordingDeletionBatchResult(listOf(true), cleanupFailed = false),
            pending, rows,
        ) { rows = it })
        assertEquals(listOf(replacement, b), rows)
        assertEquals(replacement, pending[a.id])
        assertEquals(b, pending[b.id])
    }

    @Test
    fun linkedBatchUsesLastSuccessfulDuplicateAndPublishesBeforeReleasingTargets() {
        val original = recording("/recordings/a.wav", "original")
        val deletedReplacement = original.copy(fileIdentity = "deleted-replacement")
        val failedReplacement = original.copy(fileIdentity = "failed-replacement")
        val failed = recording("/recordings/b.wav")
        val deleted = recording("/recordings/c.wav")
        val untouched = recording("/recordings/untouched.wav")
        val attempted = LinkedList(listOf(original, failed, deletedReplacement, deleted, failedReplacement))
        val outcomes = LinkedList(listOf(true, false, true, true, false))
        val state = LibraryDeletionState()
        val revision = beginBackground(state)
        val pending = linkedMapOf(original.id to failedReplacement, failed.id to failed, deleted.id to deleted)
        val expectedPending = pending.toMap()
        var rows = listOf(untouched, original, deletedReplacement, failed, failedReplacement, deleted)
        var publications = 0

        assertTrue(settleCompletedBackgroundDeletion(
            state, revision, attempted,
            RecordingDeletionBatchResult(outcomes, cleanupFailed = false),
            pending, rows,
        ) {
            assertEquals(expectedPending, pending)
            assertTrue(state.committedInBackground)
            rows = it
            publications++
        })

        assertEquals(listOf(untouched, original, failed, failedReplacement), rows)
        assertEquals(1, publications)
        assertTrue(pending.isEmpty())
        assertTrue(state.acceptsDeletions)
    }
}
