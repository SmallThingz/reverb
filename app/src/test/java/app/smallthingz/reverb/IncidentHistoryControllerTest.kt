package app.smallthingz.reverb

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proposed tests only; no coroutine-test dependency or wall-clock backoff waits. */
class IncidentHistoryControllerTest {
    @Test fun newerRevisionCancelsOldReadAndCannotPublishItsLateResult() = runBlocking {
        withTimeout(2_000L) {
            val access = ControlledAccess()
            val controller = IncidentHistoryController(access)
            val states = Channel<IncidentHistoryUiState>(Channel.UNLIMITED)
            val observer = launch { controller.observeHistory { states.trySend(it).getOrThrow() } }
            try {
                val obsolete = access.reads.receive()
                access.revisions.value = 1L
                val latest = access.reads.receive()
                assertEquals(1, access.cancelledReads)
                val current = history(2L)
                latest.complete(current)
                assertSame(current, states.awaitStatus(IncidentHistoryLoadStatus.READY).history)
                obsolete.complete(history(1L))
                yield()
                assertSame(current, controller.state.history)
            } finally {
                observer.cancelAndJoin()
            }
        }
    }

    @Test fun failureRetainsRowsAndNewRevisionCancelsAndResetsBackoff() = runBlocking {
        withTimeout(2_000L) {
            val access = ControlledAccess()
            val retry = ControlledRetry()
            val controller = IncidentHistoryController(access, retry::wait)
            val states = Channel<IncidentHistoryUiState>(Channel.UNLIMITED)
            val observer = launch { controller.observeHistory { states.trySend(it).getOrThrow() } }
            try {
                val first = history(1L)
                access.reads.receive().complete(first)
                states.awaitStatus(IncidentHistoryLoadStatus.READY)
                access.revisions.value = 1L
                access.reads.receive().completeExceptionally(IOException("unavailable"))
                val unavailable = states.awaitStatus(IncidentHistoryLoadStatus.UNAVAILABLE)
                assertSame(first, unavailable.history)
                assertFalse(unavailable.canMutate)
                controller.toggleAcknowledged(first.rows.single().incident)
                controller.delete(first.rows.single().incident)
                assertEquals(0, access.acceptedMutations)

                val initialWait = retry.waits.receive()
                assertEquals(500L, initialWait.millis)
                initialWait.release.complete(Unit)
                access.reads.receive().completeExceptionally(IOException("still unavailable"))
                val oldWait = retry.waits.receive()
                assertEquals(1_000L, oldWait.millis)

                access.revisions.value = 2L
                access.reads.receive().completeExceptionally(IOException("new revision unavailable"))
                val resetWait = retry.waits.receive()
                assertEquals(1, retry.cancelledWaits)
                assertEquals(500L, resetWait.millis)
                oldWait.release.complete(Unit)
                resetWait.release.complete(Unit)
                val recovered = history(3L)
                access.reads.receive().complete(recovered)
                assertSame(recovered, states.awaitStatus(IncidentHistoryLoadStatus.READY).history)
                assertTrue(controller.state.canMutate)
            } finally {
                observer.cancelAndJoin()
            }
        }
    }

    @Test fun cancellingObserverCancelsReadWithoutUnavailableAndAllowsReattachment() = runBlocking {
        withTimeout(2_000L) {
            val access = ControlledAccess()
            val controller = IncidentHistoryController(access)
            val states = Channel<IncidentHistoryUiState>(Channel.UNLIMITED)
            val firstObserver = launch { controller.observeHistory { states.trySend(it).getOrThrow() } }
            access.reads.receive()
            firstObserver.cancelAndJoin()
            assertEquals(1, access.cancelledReads)
            assertEquals(IncidentHistoryLoadStatus.LOADING, controller.state.status)
            val secondObserver = launch { controller.observeHistory { states.trySend(it).getOrThrow() } }
            try {
                val loaded = history(4L)
                access.reads.receive().complete(loaded)
                assertSame(loaded, states.awaitStatus(IncidentHistoryLoadStatus.READY).history)
            } finally {
                secondObserver.cancelAndJoin()
            }
        }
    }

    @Test fun acceptedToggleAndDeleteOutlivePresentationCancellation() = runBlocking {
        withTimeout(2_000L) {
            val processOwner = SupervisorJob()
            val processScope = CoroutineScope(coroutineContext + processOwner)
            val release = CompletableDeferred<Unit>()
            val completed = Channel<String>(Channel.UNLIMITED)
            val access = ControlledAccess().apply {
                onToggle = { processScope.launch { release.await(); completed.send("toggle") }; Unit }
                onDelete = { processScope.launch { release.await(); completed.send("delete") }; Unit }
            }
            val controller = IncidentHistoryController(access)
            val states = Channel<IncidentHistoryUiState>(Channel.UNLIMITED)
            val observer = launch { controller.observeHistory { states.trySend(it).getOrThrow() } }
            try {
                val loaded = history(5L)
                access.reads.receive().complete(loaded)
                states.awaitStatus(IncidentHistoryLoadStatus.READY)
                val incident = loaded.rows.single().incident
                controller.toggleAcknowledged(incident)
                controller.delete(incident)
                assertEquals(2, access.acceptedMutations)
                assertSame(loaded, controller.state.history) // No optimistic presentation mutation.
                observer.cancelAndJoin()
                assertTrue(processOwner.isActive)
                release.complete(Unit)
                assertEquals(setOf("toggle", "delete"), setOf(completed.receive(), completed.receive()))
            } finally {
                observer.cancelAndJoin()
                processScope.cancel()
            }
        }
    }

    @Test fun duplicateObserverFailsWithoutDetachingTheExistingObserver() = runBlocking {
        withTimeout(2_000L) {
            val access = ControlledAccess()
            val controller = IncidentHistoryController(access)
            val states = Channel<IncidentHistoryUiState>(Channel.UNLIMITED)
            val observer = launch { controller.observeHistory { states.trySend(it).getOrThrow() } }
            try {
                val current = access.reads.receive()
                var rejected = false
                try {
                    controller.observeHistory { throw AssertionError("Duplicate observer received state") }
                } catch (_: IllegalStateException) {
                    rejected = true
                }
                assertTrue(rejected)
                val loaded = history(6L)
                current.complete(loaded)
                assertSame(loaded, states.awaitStatus(IncidentHistoryLoadStatus.READY).history)
            } finally {
                observer.cancelAndJoin()
            }
        }
    }

    private class ControlledAccess : IncidentHistoryAccess {
        override val revisions = MutableStateFlow(0L)
        val reads = Channel<CompletableDeferred<IncidentHistoryPresentation>>(Channel.UNLIMITED)
        var cancelledReads = 0
        var acceptedMutations = 0
        var onToggle: (RecordingIncident) -> Unit = {}
        var onDelete: (RecordingIncident) -> Unit = {}

        override suspend fun readHistory(): IncidentHistoryPresentation {
            val response = CompletableDeferred<IncidentHistoryPresentation>()
            reads.send(response)
            return try {
                response.await()
            } catch (cancelled: CancellationException) {
                cancelledReads++
                throw cancelled
            }
        }

        override fun toggleAcknowledgedInBackground(incident: RecordingIncident) {
            acceptedMutations++
            onToggle(incident)
        }

        override fun deleteInBackground(incident: RecordingIncident) {
            acceptedMutations++
            onDelete(incident)
        }
    }

    private data class RetryWait(val millis: Long, val release: CompletableDeferred<Unit>)

    private class ControlledRetry {
        val waits = Channel<RetryWait>(Channel.UNLIMITED)
        var cancelledWaits = 0
        suspend fun wait(millis: Long) {
            val pending = RetryWait(millis, CompletableDeferred())
            waits.send(pending)
            try {
                pending.release.await()
            } catch (cancelled: CancellationException) {
                cancelledWaits++
                throw cancelled
            }
        }
    }

    private suspend fun Channel<IncidentHistoryUiState>.awaitStatus(
        status: IncidentHistoryLoadStatus,
    ): IncidentHistoryUiState {
        while (true) {
            val state = receive()
            if (state.status == status) return state
        }
    }

    private fun history(id: Long): IncidentHistoryPresentation = IncidentHistoryPresentation(
        listOf(IncidentPresentation(
            incident = RecordingIncident(occurredAtMillis = id),
            key = id.toString(),
            date = "date",
            stopSummary = "summary",
            cause = "",
            runtime = "",
            age = "",
            description = null,
        )),
    )
}
