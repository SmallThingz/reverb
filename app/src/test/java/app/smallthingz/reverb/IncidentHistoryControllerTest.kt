package app.smallthingz.reverb

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proposed tests only; no coroutine-test dependency or wall-clock backoff waits. */
class IncidentHistoryControllerTest {
    @Test fun duplicatePreparationUsesUnavailablePathOnInitialLoadAndRefresh() = runBlocking {
        withTimeout(2_000L) {
            for (previouslyLoaded in listOf(false, true)) {
                val pending = RecordingIncident(10_000L, resumedAtMillis = 0L, pid = 42,
                    processStartedAtMillis = 5_000L, captureArmedAtMillis = 9_000L)
                val duplicate = listOf(pending, pending.copy(occurredAtMillis = 11_000L))
                var stored = if (previouslyLoaded) listOf(pending) else duplicate
                var mutations = 0
                val access = object : IncidentHistoryAccess {
                    override val revisions = MutableStateFlow(0L)
                    override suspend fun readHistory(zone: ZoneId) = prepareIncidentHistory(stored, zone = zone)
                    override fun toggleAcknowledgedInBackground(incident: RecordingIncident) { mutations++ }
                    override fun deleteInBackground(incidents: List<RecordingIncident>) { mutations++ }
                }
                val retry = ControlledRetry()
                val controller = IncidentHistoryController(access, retry::wait)
                val states = Channel<IncidentHistoryUiState>(Channel.UNLIMITED)
                val observer = launch { controller.observeHistory { states.trySend(it).getOrThrow() } }
                try {
                    val retained = if (previouslyLoaded) {
                        states.awaitStatus(IncidentHistoryLoadStatus.READY).history.also {
                            stored = duplicate
                            access.revisions.value++
                        }
                    } else controller.state.history
                    val failed = states.awaitStatus(IncidentHistoryLoadStatus.UNAVAILABLE)
                    assertSame(retained, failed.history)
                    assertFalse(failed.canMutate)
                    assertFalse(failed.showEmptyHistory)
                    assertEquals(previouslyLoaded, failed.history.hasAlert)
                    controller.toggleAcknowledged(pending)
                    controller.delete(pending)
                    assertEquals(0, mutations)
                    assertEquals(duplicate, stored)
                    assertTrue(observer.isActive)

                    val firstRetry = retry.waits.receive()
                    assertEquals(500L, firstRetry.millis)
                    firstRetry.release.complete(Unit)
                    val secondRetry = retry.waits.receive()
                    assertEquals(1_000L, secondRetry.millis)
                    assertSame(retained, controller.state.history)
                    assertEquals(duplicate, stored)
                    val resumed = pending.copy(resumedAtMillis = 12_000L)
                    stored = listOf(resumed)
                    secondRetry.release.complete(Unit)
                    val recovered = states.awaitStatus(IncidentHistoryLoadStatus.READY)
                    assertEquals(listOf(resumed), recovered.history.rows.map { it.incident })
                    assertTrue(recovered.canMutate)
                    assertTrue(observer.isActive)
                } finally {
                    observer.cancelAndJoin()
                }
            }
        }
    }

    @Test fun changedTimeZoneRegroupsRetainedHistoryAtSameRevisionAndSkipsDuplicates() = runBlocking {
        withTimeout(2_000L) {
            val beforeMidnight = RecordingIncident(
                Instant.parse("2026-11-22T23:59:00Z").toEpochMilli(), pid = 10, description = "Same topic",
            )
            val afterMidnight = beforeMidnight.copy(
                occurredAtMillis = Instant.parse("2026-11-23T00:01:00Z").toEpochMilli(), pid = 11,
            )
            var zone = ZoneOffset.UTC
            val reads = Channel<ZoneId>(Channel.UNLIMITED)
            val access = object : IncidentHistoryAccess {
                override val revisions = MutableStateFlow(0L)
                override suspend fun readHistory(zone: ZoneId): IncidentHistoryPresentation {
                    reads.send(zone)
                    return prepareIncidentHistory(listOf(beforeMidnight, afterMidnight), Locale.US, zone)
                }
                override fun toggleAcknowledgedInBackground(incident: RecordingIncident) = error("Unexpected mutation")
                override fun deleteInBackground(incidents: List<RecordingIncident>) = error("Unexpected mutation")
            }
            val controller = IncidentHistoryController(access, displayTimeZone = { zone })
            val states = Channel<IncidentHistoryUiState>(Channel.UNLIMITED)
            val observer = launch { controller.observeHistory { states.trySend(it).getOrThrow() } }
            try {
                assertEquals(ZoneOffset.UTC, reads.receive())
                val initial = states.awaitStatus(IncidentHistoryLoadStatus.READY).history
                assertEquals(2, initial.days.size)
                assertEquals(listOf("12:01:00 AM", "11:59:00 PM"), initial.rows.map { it.startLabel })

                zone = ZoneOffset.ofHours(2)
                controller.refreshDisplayTimeZone()
                assertEquals(zone, reads.receive())
                val refreshed = states.awaitStatus(IncidentHistoryLoadStatus.READY).history
                val group = refreshed.days.single().groups.single()
                assertEquals("Same topic", group.message)
                assertEquals(initial.rows.map { it.key }, group.occurrences.map { it.key })
                assertEquals(listOf("2:01:00 AM", "1:59:00 AM"), refreshed.rows.map { it.startLabel })
                assertEquals(0L, access.revisions.value)

                controller.refreshDisplayTimeZone()
                assertEquals(null, withTimeoutOrNull(100L) { reads.receive() })
                assertSame(refreshed, controller.state.history)
            } finally {
                observer.cancelAndJoin()
            }
        }
    }

    @Test fun unobservedTimeZoneChangeKeepsRetryZoneUntilRefreshCancelsRead() = runBlocking {
        withTimeout(2_000L) {
            var zone = ZoneOffset.UTC
            val access = ControlledAccess()
            val retry = ControlledRetry()
            val controller = IncidentHistoryController(access, retry::wait, displayTimeZone = { zone })
            val states = Channel<IncidentHistoryUiState>(Channel.UNLIMITED)
            val observer = launch { controller.observeHistory { states.trySend(it).getOrThrow() } }
            try {
                val initial = access.reads.receive()
                assertEquals(ZoneOffset.UTC, access.lastReadZone)
                initial.completeExceptionally(IOException("History temporarily unavailable"))
                val backoff = retry.waits.receive()

                zone = ZoneOffset.ofHours(2)
                backoff.release.complete(Unit)
                val obsolete = access.reads.receive()
                assertEquals(ZoneOffset.UTC, access.lastReadZone)

                controller.refreshDisplayTimeZone()
                val latest = access.reads.receive()
                assertEquals(zone, access.lastReadZone)
                assertEquals(1, access.cancelledReads)
                val refreshed = history(2L)
                latest.complete(refreshed)
                assertSame(refreshed, states.awaitStatus(IncidentHistoryLoadStatus.READY).history)
                obsolete.complete(history(1L))
                assertSame(refreshed, controller.state.history)
                assertEquals(0L, access.revisions.value)
            } finally {
                observer.cancelAndJoin()
            }
        }
    }

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

        var lastReadZone: ZoneId? = null
            private set

        override suspend fun readHistory(zone: ZoneId): IncidentHistoryPresentation {
            lastReadZone = zone
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

        override fun deleteInBackground(incidents: List<RecordingIncident>) {
            acceptedMutations++
            incidents.forEach(onDelete)
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
