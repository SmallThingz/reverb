package app.smallthingz.reverb

import smallthingz.ui.platform.AccessibilityManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFeedbackTimeoutTest {
    @Test
    fun undoRemainsAvailableBeyondTheDefaultUntilTheRecommendedDeadline() = runBlocking {
        withTimeout(5_000L) {
            val clock = ManualClock()
            val manager = RecordingAccessibilityManager { 30_000L }
            val state = LibraryDeletionState()
            val revision = requireNotNull(state.extendUndoWindow())
            var commits = 0
            val timer = launch(start = CoroutineStart.UNDISPATCHED) {
                runLibraryUndoDeadline(state, revision, manager, clock::waitForDuration) { commits++ }
            }

            assertEquals(listOf(30_000L), clock.requests)
            assertEquals(TimeoutRequest(4_500L, false, true, true), manager.requests.single())
            clock.advanceBy(10_000L)
            yield()
            assertEquals(0, commits)
            assertTrue(state.undo(revision))
            timer.cancelAndJoin()
            assertEquals(0, commits)
            assertTrue(state.acceptsDeletions)
        }
    }

    @Test
    fun recommendedUndoDeadlineOwnsPhysicalCommitAndRejectsUndoAtExpiry() = runBlocking {
        withTimeout(5_000L) {
            val clock = ManualClock()
            val state = LibraryDeletionState()
            val revision = requireNotNull(state.extendUndoWindow())
            val pending = listOf("selected-a", "selected-b")
            val committedBatches = mutableListOf<List<String>>()
            val physicalCommitStarted = CompletableDeferred<Unit>()
            val finishPhysicalCommit = CompletableDeferred<Unit>()
            val timer = launch(start = CoroutineStart.UNDISPATCHED) {
                runLibraryUndoDeadline(
                    state, revision, RecordingAccessibilityManager { 30_000L }, clock::waitForDuration,
                ) {
                    committedBatches += pending
                    physicalCommitStarted.complete(Unit)
                    finishPhysicalCommit.await()
                }
            }

            clock.advanceBy(29_999L)
            yield()
            assertTrue(committedBatches.isEmpty())
            assertTrue(state.acceptsDeletions)
            clock.advanceBy(1L)
            physicalCommitStarted.await()
            assertEquals(listOf(pending), committedBatches)
            assertFalse(state.undo(revision))
            assertNull(state.handoffToBackground(hasPending = true))
            assertNull(state.extendUndoWindow())
            finishPhysicalCommit.complete(Unit)
            timer.join()
            assertTrue(state.acceptsDeletions)
            assertEquals(listOf(pending), committedBatches)
        }
    }

    @Test
    fun noManagerAndDefaultRecommendationPreserveBothExistingDurations() = runBlocking {
        for (manager in listOf(null, RecordingAccessibilityManager { it.originalTimeoutMillis })) {
            val state = LibraryDeletionState()
            val revision = requireNotNull(state.extendUndoWindow())
            var undoDuration = 0L
            var commits = 0
            runLibraryUndoDeadline(
                state, revision, manager, waitForDuration = { undoDuration = it },
            ) { commits++ }
            assertEquals(4_500L, undoDuration)
            assertEquals(1, commits)
            assertTrue(state.acceptsDeletions)

            val expected = saved("saved")
            var status: CaptureSaveStatus? = expected
            var savedDuration = 0L
            dismissSavedFeedbackAfterTimeout(
                expected, manager,
                currentStatus = { status },
                dismiss = { status = null },
                waitForDuration = { savedDuration = it },
            )
            assertEquals(1_500L, savedDuration)
            assertNull(status)
        }
    }

    @Test
    fun consecutiveDeletesExtendOneDeadlineAndCommitOnlyTheirCumulativeBatch() = runBlocking {
        withTimeout(5_000L) {
            val clock = ManualClock()
            val manager = RecordingAccessibilityManager { 30_000L }
            val state = LibraryDeletionState()
            val pending = mutableListOf("a")
            val committedBatches = mutableListOf<List<String>>()
            val firstRevision = requireNotNull(state.extendUndoWindow())
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                runLibraryUndoDeadline(state, firstRevision, manager, clock::waitForDuration) {
                    committedBatches += pending.toList()
                }
            }
            clock.advanceBy(4_000L)
            yield()
            pending += "b"
            val currentRevision = requireNotNull(state.extendUndoWindow())
            first.cancelAndJoin()
            val current = launch(start = CoroutineStart.UNDISPATCHED) {
                runLibraryUndoDeadline(state, currentRevision, manager, clock::waitForDuration) {
                    committedBatches += pending.toList()
                    pending.clear()
                }
            }

            clock.advanceBy(26_000L)
            yield()
            assertTrue(committedBatches.isEmpty())
            assertFalse(state.undo(firstRevision))
            clock.advanceBy(3_999L)
            yield()
            assertTrue(committedBatches.isEmpty())
            clock.advanceBy(1L)
            current.join()
            assertEquals(listOf(listOf("a", "b")), committedBatches)
            assertTrue(pending.isEmpty())
            assertEquals(listOf(30_000L, 30_000L), clock.requests)

            pending += "newer-c"
            val newerRevision = requireNotNull(state.extendUndoWindow())
            runLibraryUndoDeadline(
                state, currentRevision, manager, waitForDuration = {},
            ) { throw AssertionError("Old deadline committed the newer batch") }
            assertEquals(listOf("newer-c"), pending)
            assertTrue(state.undo(newerRevision))
        }
    }

    @Test
    fun stoppedOrClosedOwnerImmediatelyHandsOffAndCannotLaterCommitTheSameBatch() = runBlocking {
        withTimeout(5_000L) {
            val state = LibraryDeletionState()
            val revision = requireNotNull(state.extendUndoWindow())
            val pending = listOf("a", "b")
            val releaseRetiredTimer = CompletableDeferred<Unit>()
            var foregroundCommits = 0
            var requestedDuration = 0L
            val timer = launch(start = CoroutineStart.UNDISPATCHED) {
                runLibraryUndoDeadline(
                    state, revision, RecordingAccessibilityManager { 30_000L },
                    waitForDuration = {
                        requestedDuration = it
                        withContext(NonCancellable) { releaseRetiredTimer.await() }
                    },
                ) { foregroundCommits++ }
            }
            assertEquals(30_000L, requestedDuration)

            // FilesScreen ON_STOP/panel disposal performs this handoff and cancels its timer.
            val backgroundRevision = state.handoffToBackground(hasPending = pending.isNotEmpty())
            assertEquals(revision, backgroundRevision)
            val backgroundBatch = pending.toList()
            timer.cancel()
            assertFalse(state.undo(revision))
            assertNull(state.handoffToBackground(hasPending = true))
            releaseRetiredTimer.complete(Unit)
            timer.join()
            assertEquals(0, foregroundCommits)
            assertEquals(listOf("a", "b"), backgroundBatch)
            assertTrue(state.committedInBackground)
            assertTrue(state.completeBackgroundCommit(revision) {})
            assertNotNull(state.extendUndoWindow())
        }
    }

    @Test
    fun foregroundCommitFailureStillReleasesItsExactPhase() = runBlocking {
        val state = LibraryDeletionState()
        val revision = requireNotNull(state.extendUndoWindow())
        val expected = IllegalStateException("physical commit failed")
        val failure = runCatching {
            runLibraryUndoDeadline(state, revision, null, waitForDuration = {}) { throw expected }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertTrue(state.acceptsDeletions)
    }

    @Test
    fun savedNoticeUsesTextAndIconRecommendationUntilItsFullDeadline() = runBlocking {
        withTimeout(5_000L) {
            val clock = ManualClock()
            val manager = RecordingAccessibilityManager { 30_000L }
            val expected = saved("saved")
            var status: CaptureSaveStatus? = expected
            var dismissals = 0
            val timer = launch(start = CoroutineStart.UNDISPATCHED) {
                dismissSavedFeedbackAfterTimeout(
                    expected, manager,
                    currentStatus = { status },
                    dismiss = { dismissals++; status = null },
                    waitForDuration = clock::waitForDuration,
                )
            }
            assertEquals(TimeoutRequest(1_500L, true, true, false), manager.requests.single())
            clock.advanceBy(1_500L)
            yield()
            assertSame(expected, status)
            clock.advanceBy(28_499L)
            yield()
            assertSame(expected, status)
            assertEquals(0, dismissals)
            clock.advanceBy(1L)
            timer.join()
            assertNull(status)
            assertEquals(1, dismissals)
        }
    }

    @Test
    fun retiredSavedTimerCannotDismissEvenIfItsWaitReturnsAfterCancellation() = runBlocking {
        withTimeout(5_000L) {
            val expected = saved("saved")
            var status: CaptureSaveStatus? = expected
            val releaseRetiredTimer = CompletableDeferred<Unit>()
            val timer = launch(start = CoroutineStart.UNDISPATCHED) {
                dismissSavedFeedbackAfterTimeout(
                    expected, RecordingAccessibilityManager { 30_000L },
                    currentStatus = { status },
                    dismiss = { status = null },
                    waitForDuration = {
                        withContext(NonCancellable) { releaseRetiredTimer.await() }
                    },
                )
            }
            timer.cancel()
            releaseRetiredTimer.complete(Unit)
            timer.join()
            assertSame(expected, status)
        }
    }

    @Test
    fun savedDeadlineCannotClearANewerSavingOrSavedStatus() = runBlocking {
        val expected = saved("old")
        val replacements = listOf(CaptureSaveStatus.Saving(cancellable = true), saved("new"))
        for (replacement in replacements) {
            var status: CaptureSaveStatus? = expected
            dismissSavedFeedbackAfterTimeout(
                expected, RecordingAccessibilityManager { 30_000L },
                currentStatus = { status },
                dismiss = { throw AssertionError("Old deadline cleared the replacement") },
                waitForDuration = { status = replacement },
            )
            assertSame(replacement, status)
        }
    }

    private class ManualClock {
        private val now = MutableStateFlow(0L)
        val requests = mutableListOf<Long>()

        suspend fun waitForDuration(durationMillis: Long) {
            requests += durationMillis
            val deadline = now.value + durationMillis
            now.first { it >= deadline }
        }

        fun advanceBy(durationMillis: Long) {
            now.value += durationMillis
        }
    }

    private data class TimeoutRequest(
        val originalTimeoutMillis: Long,
        val containsIcons: Boolean,
        val containsText: Boolean,
        val containsControls: Boolean,
    )

    private class RecordingAccessibilityManager(
        val recommendation: (TimeoutRequest) -> Long,
    ) : AccessibilityManager {
        val requests = mutableListOf<TimeoutRequest>()

        override fun calculateRecommendedTimeoutMillis(
            originalTimeoutMillis: Long,
            containsIcons: Boolean,
            containsText: Boolean,
            containsControls: Boolean,
        ): Long {
            val request = TimeoutRequest(
                originalTimeoutMillis, containsIcons, containsText, containsControls,
            )
            requests += request
            return recommendation(request)
        }
    }

    private fun saved(id: String) = CaptureSaveStatus.Saved(
        RecordingEntity(
            id = id,
            displayName = id,
            mimeType = "audio/wav",
            startedAtMillis = 1L,
            durationMillis = 2L,
            sizeBytes = 3L,
            codecSummary = "PCM",
            storageType = RecordingStorageType.FILE,
            directoryId = "",
            fileIdentity = "stat:$id",
        ),
    )
}
