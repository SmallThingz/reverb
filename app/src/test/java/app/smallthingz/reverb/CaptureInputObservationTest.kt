package app.smallthingz.reverb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CaptureInputObservationTest {
    private val observationRoot = File("build/tmp/capture-observation-owner").absoluteFile
    @Test fun initialSilencingAndKnownRestorationDefineEpisodes() {
        val episode = CapturePolicyEpisode()
        assertEquals(listOf(false, true, false, false, false, false, true),
            listOf(null, true, true, null, true, false, true).map(episode::observe))
        assertTrue(CapturePolicyEpisode().observe(true))
    }

    @Test fun observationRoundTripDoesNotBecomeShutdownOrMergeDifferentReasons() {
        val first = RecordingIncident(100, kind = RecordingIncidentKind.CAPTURE_INPUT_OBSERVATION,
            pid = 7, processStartedAtMillis = 50, observationId = 1,
            description = CaptureInputObservationReason.POLICY_SILENCED.description)
        val other = first.copy(observationId = 2, description = CaptureInputObservationReason.INPUT_RESTARTED.description)
        assertEquals(3.toByte(), first.kind.storageCode)
        assertFalse(first.recoveryPending)
        assertFalse(recordingIncidentsShareCaptureSession(first, other))
        assertTrue(recordingIncidentReferenceMatches(first, first.copy()))
        assertFalse(recordingIncidentReferenceMatches(first, other))
        assertFalse(recordingIncidentReferenceMatches(first, first.copy(pid = 8)))
        val bytes = ByteArrayOutputStream()
        RecordingIncidentStore.writeHistoryPayload(DataOutputStream(bytes), listOf(first, other))
        val decoded = DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use {
            RecordingIncidentStore.readHistoryPayload(it)
        }
        assertEquals(listOf(first, other), decoded)
        val deleted = first.copy(acknowledgedAtMillis = Long.MIN_VALUE)
        assertTrue(mergeRecordingIncidentEvidence(deleted, first).deleted)
    }

    @Test fun publicationRetryRetainsSameFactAndStopsAfterSuccess() = runBlocking {
        val fact = RecordingIncident(123, kind = RecordingIncidentKind.CAPTURE_INPUT_OBSERVATION,
            description = "Microphone input silenced by Android", observationId = 7)
        val attempted = mutableListOf<RecordingIncident>()
        val waits = mutableListOf<Long>()
        persistCaptureInputObservation(persist = {
            attempted += fact
            if (attempted.size < 3) throw IOException("directory sync")
        }, waitBeforeRetry = { waits += it }, onFailure = { error("Successful retry reported failure") })
        assertEquals(listOf(100L, 500L), waits)
        assertEquals(3, attempted.size)
        assertTrue(attempted.all { it === fact && it.occurredAtMillis == 123L })
    }

    @Test fun exhaustedPersistenceReportsOnceWithoutRecursion() = runBlocking {
        var attempts = 0
        var failures = 0
        val waits = mutableListOf<Long>()
        val failure = IOException("storage unavailable")
        persistCaptureInputObservation(persist = { attempts++; throw failure },
            waitBeforeRetry = { waits += it }, onFailure = {
                assertSame(failure, it); failures++; throw IOException("feedback unavailable")
            })
        assertEquals(3, attempts)
        assertEquals(1, failures)
        assertEquals(listOf(100L, 500L), waits)
    }

    @Test fun callbackEpisodesSurviveSuspendedSnapshotAndStopAfterAdmission() {
        val work = java.util.ArrayDeque<() -> Unit>()
        val admitted = mutableListOf<Long>()
        val subscription = CapturePolicyWitnesses(work::addLast, { false to 50L }, admitted::add,
            { throw AssertionError(it) })
        subscription.activate()
        subscription.observe(true, 10)
        subscription.observe(false, 11)
        subscription.observe(true, 12)
        subscription.close()
        subscription.observe(true, 13)
        while (work.isNotEmpty()) work.removeFirst()()
        assertEquals(listOf(10L, 12L), admitted)
    }

    @Test fun onlyExplicitCallbackStatesCanHintReminderRecheck() {
        val work = java.util.ArrayDeque<() -> Unit>()
        val admitted = mutableListOf<Long>()
        val wakes = mutableListOf<Long>()
        val subscription = CapturePolicyWitnesses(work::addLast, { false to 10L }, admitted::add,
            { throw AssertionError(it) }, deliverUnsilenced = wakes::add)
        subscription.activate()
        work.removeFirst()()
        assertTrue(wakes.isEmpty())
        subscription.observe(false, 11)
        subscription.observe(null, 12)
        subscription.observe(false, 13)
        subscription.observe(true, 14)
        subscription.observe(null, 15)
        subscription.observe(false, 16)
        subscription.close()
        subscription.observe(false, 17)
        assertEquals(listOf(11L, 13L, 16L), wakes)
        assertEquals(listOf(14L), admitted)
    }

    @Test fun currentSilencingUsesLatestCallbackAndNeverTheInitialSnapshot() {
        val work = java.util.ArrayDeque<() -> Unit>()
        val admitted = mutableListOf<Long>()
        val failures = mutableListOf<Exception>()
        val liveStates = mutableListOf<Boolean?>()
        lateinit var subscription: CapturePolicyWitnesses
        subscription = CapturePolicyWitnesses(work::addLast, {
            subscription.observe(false, 20)
            true to 19L
        }, admitted::add, failures::add, callbackStateChanged = { liveStates += subscription.callbackSilenced })

        subscription.observe(true, 10)
        subscription.observe(false, 11)
        assertNull(subscription.callbackSilenced)
        subscription.activate()
        assertEquals(false, subscription.callbackSilenced)
        work.removeFirst()()
        assertEquals(listOf(10L), admitted)
        assertEquals(listOf(false), liveStates)

        subscription.observe(true, 21)
        subscription.observe(true, 22)
        subscription.observe(null, 23)
        subscription.observe(true, 24)
        assertEquals(listOf(10L, 21L), admitted)
        assertEquals(listOf(false, true, null, true), liveStates)

        val failure = IOException("observer failed")
        subscription.report(failure)
        subscription.observe(false, 25)
        assertNull(subscription.callbackSilenced)
        assertEquals(listOf(false, true, null, true, null), liveStates)
        assertEquals(listOf(failure), failures)
    }

    @Test fun observerCanAcceptARestorationWhileTheMutedWakeIsInFlight() {
        val work = java.util.ArrayDeque<() -> Unit>()
        val entered = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        lateinit var subscription: CapturePolicyWitnesses
        subscription = CapturePolicyWitnesses(work::addLast, { null to 0L }, {}, { throw AssertionError(it) },
            callbackStateChanged = {
                if (subscription.callbackSilenced == true) {
                    entered.countDown()
                    check(allowReturn.await(5, TimeUnit.SECONDS))
                }
            })
        subscription.activate()
        val mute = FutureTask { subscription.observe(true, 10) }
        Thread(mute, "muted-callback").start()
        val restore = FutureTask { subscription.observe(false, 11) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            Thread(restore, "restored-callback").start()
            restore.get(5, TimeUnit.SECONDS)
            assertEquals(false, subscription.callbackSilenced)
        } finally {
            allowReturn.countDown()
            mute.get(5, TimeUnit.SECONDS)
            subscription.close()
        }
        assertNull(subscription.callbackSilenced)
        subscription.observe(true, 12)
        assertNull(subscription.callbackSilenced)
    }

    @Test fun provisionalDropoutEndsRemainInCallbackOrderAndUnknownIsNeverRestoration() {
        val work = java.util.ArrayDeque<() -> Unit>()
        val dropouts = mutableListOf<CapturePolicyDropout>()
        lateinit var subscription: CapturePolicyWitnesses
        subscription = CapturePolicyWitnesses(work::addLast, { true to 50L }, {}, { throw AssertionError(it) },
            dropoutsAvailable = { while (true) dropouts.add(subscription.pollDropout() ?: break) })

        subscription.observe(true, 200, 10)
        subscription.observe(false, 150, 11)
        subscription.observe(true, 130, 12)
        subscription.observe(null, 120, 13)
        subscription.observe(true, 110, 14)
        subscription.observe(false, 100, 15)
        assertTrue(dropouts.isEmpty())
        subscription.activate()
        assertEquals(listOf(10L, 12L), dropouts.map { it.elapsedMillis })
        assertEquals(listOf(11L, -1L), dropouts.map { it.endedAtElapsedMillis })

        subscription.observe(true, 100, 16)
        assertEquals(16L, dropouts.last().elapsedMillis)
        assertNull(dropouts.last().endedAtElapsedMillis)
        work.removeFirst()()
        assertNull(dropouts.last().endedAtElapsedMillis)
        subscription.observe(false, 100, 18)
        assertEquals(18L, dropouts.last().endedAtElapsedMillis)
    }

    @Test fun dropoutDurationUsesMonotonicEvidenceAndItsFirstEnd() = runBlocking {
        val duration = CaptureObservationDuration(20L)
        duration.finish(40L)
        duration.finish(10_000L)
        assertFalse(duration.isOngoing)
        assertEquals(100_020L, duration.historyEndAtMillis(100_000L))
        assertEquals(Long.MAX_VALUE, duration.historyEndAtMillis(Long.MAX_VALUE - 10L))
        val unknown = CaptureObservationDuration(50L)
        unknown.finish(49L)
        assertEquals(-1L, unknown.historyEndAtMillis(100_000L))
    }

    @Test fun cutoffKeepsAnAlreadyWitnessedRestorationAndRejectsLaterCallbacks() {
        fun subscription() = CapturePolicyWitnesses({}, { error("No startup snapshot") }, {}, { throw AssertionError(it) },
            dropoutsAvailable = {}).apply { activate() }

        val restored = subscription()
        restored.observe(true, 100, 1)
        val first = checkNotNull(restored.pollDropout())
        // The restoration has been observed but its service wake has not been consumed.
        restored.observe(false, 110, 2)
        restored.sealDropouts { error("Known restoration must win at owner retirement") }
        restored.observe(true, 120, 3)
        assertEquals(2L, first.endedAtElapsedMillis)
        assertNull(restored.pollDropout())

        val stopped = subscription()
        stopped.observe(true, 100, 1)
        val second = checkNotNull(stopped.pollDropout())
        stopped.sealDropouts { 4L }
        stopped.observe(false, 120, 5)
        assertEquals(4L, second.endedAtElapsedMillis)
    }

    @Test fun provisionalEpisodesAdoptOnlyAfterSuccessfulActivation() {
        val work = java.util.ArrayDeque<() -> Unit>()
        val admitted = mutableListOf<Long>()
        val subscription = CapturePolicyWitnesses(work::addLast, { false to 50L }, admitted::add,
            { throw AssertionError(it) })
        subscription.observe(true, 10)
        subscription.observe(false, 11)
        assertTrue(admitted.isEmpty())
        subscription.activate()
        while (work.isNotEmpty()) work.removeFirst()()
        subscription.observe(true, 51)
        assertEquals(listOf(10L, 51L), admitted)
        val abandoned = CapturePolicyWitnesses(work::addLast, { true to 60L }, admitted::add,
            { throw AssertionError(it) })
        abandoned.observe(true, 55)
        abandoned.close()
        abandoned.activate()
        assertTrue(work.isEmpty())
        assertEquals(listOf(10L, 51L), admitted)
    }

    @Test fun callbackDuringInitialSnapshotPreventsStaleSnapshotReversal() {
        val work = java.util.ArrayDeque<() -> Unit>()
        val admitted = mutableListOf<Long>()
        lateinit var subscription: CapturePolicyWitnesses
        subscription = CapturePolicyWitnesses(work::addLast, {
            subscription.observe(true, 20)
            false to 19L
        }, admitted::add, { throw AssertionError(it) })
        subscription.activate()
        work.removeFirst()()
        subscription.observe(true, 21)
        assertEquals(listOf(20L), admitted)
    }

    @Test fun positiveInitialSnapshotSurvivesRacingRestorationWithoutReplacingCallbackState() {
        val work = java.util.ArrayDeque<() -> Unit>()
        val admitted = mutableListOf<Long>()
        lateinit var subscription: CapturePolicyWitnesses
        subscription = CapturePolicyWitnesses(work::addLast, {
            subscription.observe(false, 20)
            true to 19L
        }, admitted::add, { throw AssertionError(it) })
        subscription.activate()
        work.removeFirst()()
        assertEquals(listOf(19L), admitted)
        subscription.observe(true, 21)
        assertEquals(listOf(19L, 21L), admitted)
    }

    @Test fun racedPositiveSnapshotDoesNotDuplicateAnAlreadyObservedEpisode() {
        val work = java.util.ArrayDeque<() -> Unit>()
        val admitted = mutableListOf<Long>()
        lateinit var subscription: CapturePolicyWitnesses
        subscription = CapturePolicyWitnesses(work::addLast, {
            subscription.observe(true, 20)
            subscription.observe(false, 21)
            true to 19L
        }, admitted::add, { throw AssertionError(it) })
        subscription.activate()
        work.removeFirst()()
        assertEquals(listOf(20L), admitted)
        subscription.observe(true, 22)
        assertEquals(listOf(20L, 22L), admitted)
    }

    @Test fun snapshotAfterFalseMayAddOneInitialWitnessButRepeatedTrueCallbacksDoNotMultiplyIt() {
        val work = java.util.ArrayDeque<() -> Unit>()
        val admitted = mutableListOf<Long>()
        lateinit var subscription: CapturePolicyWitnesses
        subscription = CapturePolicyWitnesses(work::addLast, {
            subscription.observe(false, 18)
            true to 19L
        }, admitted::add, { throw AssertionError(it) })
        subscription.activate()
        work.removeFirst()()
        subscription.observe(true, 20)
        subscription.observe(true, 21)
        subscription.observe(true, 22)
        // The snapshot may describe the same physical episode; there is no cross-API sequence.
        assertEquals(listOf(19L, 20L), admitted)
        subscription.observe(false, 23)
        subscription.observe(true, 24)
        subscription.observe(true, 25)
        assertEquals(listOf(19L, 20L, 24L), admitted)
    }

    @Test fun unknownCallbackCannotDiscardInitialPositiveOrInventRestoration() {
        val work = java.util.ArrayDeque<() -> Unit>()
        val admitted = mutableListOf<Long>()
        lateinit var subscription: CapturePolicyWitnesses
        subscription = CapturePolicyWitnesses(work::addLast, {
            subscription.observe(null, 20)
            true to 19L
        }, admitted::add, { throw AssertionError(it) })
        subscription.activate()
        work.removeFirst()()
        subscription.observe(true, 21)
        assertEquals(listOf(19L), admitted)
    }

    @Test fun initialSnapshotAndProvisionalOverflowAreExplicit() {
        val work = java.util.ArrayDeque<() -> Unit>()
        val admitted = mutableListOf<Long>()
        val failures = mutableListOf<Exception>()
        val initial = CapturePolicyWitnesses(work::addLast, { true to 7L }, admitted::add, failures::add)
        initial.activate()
        work.removeFirst()()
        assertEquals(listOf(7L), admitted)
        val saturated = CapturePolicyWitnesses(work::addLast, { false to 999L }, admitted::add, failures::add)
        repeat(65) { saturated.observe(true, it.toLong() + 100); saturated.observe(false, 200) }
        saturated.activate()
        while (work.isNotEmpty()) work.removeFirst()()
        assertEquals(65, admitted.size)
        assertEquals(1, failures.size)
        assertTrue(failures.single().message!!.contains("queue exhausted"))
    }

    @Test fun sameClockAndRollbackNeverInheritAnotherObservationTombstone() {
        val first = RecordingIncident(100, kind = RecordingIncidentKind.CAPTURE_INPUT_OBSERVATION,
            pid = 7, processStartedAtMillis = 50, observationId = 1)
        val deleted = first.copy(acknowledgedAtMillis = Long.MIN_VALUE)
        assertTrue(recordingIncidentReferenceMatches(deleted, first))
        assertFalse(recordingIncidentReferenceMatches(deleted, first.copy(observationId = 2)))
        assertFalse(recordingIncidentReferenceMatches(deleted, first.copy(observationId = 3, occurredAtMillis = 90)))
        val armed = first.copy(captureArmedAtMillis = 60)
        assertFalse(recordingIncidentsShareCaptureSession(armed, armed.copy(observationId = 2)))
        assertFalse(recordingIncidentReferenceMatches(armed, armed.copy(observationId = 2)))
    }

    @Test fun legacyHistoryStillDecodesWithoutObservationIdentity() {
        for (version in 1..3) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x52494831); output.writeByte(version); output.writeShort(1)
                output.writeByte(1); output.writeLong(100)
                if (version >= 2) {
                    output.writeLong(-1); output.writeLong(0)
                    output.writeInt(0); output.writeInt(Int.MIN_VALUE); output.writeInt(7); output.writeInt(Int.MIN_VALUE)
                    output.writeLong(-1); output.writeLong(-1); output.writeLong(50); output.writeLong(-1)
                    output.writeUTF("legacy")
                }
            }
            val decoded = RecordingIncidentStore.readHistoryPayload(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
            assertEquals(1, decoded.size)
            assertEquals(0L, decoded.single().observationId)
            assertEquals(100L, decoded.single().occurredAtMillis)
        }
    }
    @Test fun rejectedInitialSnapshotReportsOnceWithoutEscapingActivation() {
        val rejection = java.util.concurrent.RejectedExecutionException("snapshot queue full")
        var failures = 0
        val subscription = CapturePolicyWitnesses(execute = { throw rejection },
            snapshot = { error("Rejected snapshot must not execute") },
            deliver = { error("Failed observer must not publish") }, failed = {
                assertSame(rejection, it)
                failures++
                throw IOException("Feedback unavailable")
            })
        subscription.activate()
        subscription.observe(true, 10)
        subscription.activate()
        subscription.close()
        assertEquals(1, failures)
    }
    @Test fun blockedPersistenceBoundsAllocationAndReportsSaturationOnce() = runBlocking {
        val admission = CaptureObservationAdmission(2)
        val owner = kotlinx.coroutines.SupervisorJob()
        val scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Unconfined)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var created = 0
        var started = 0
        val failures = mutableListOf<Exception>()
        fun submit() = admission.submit(scope, observationRoot, create = { created++; RecordingIncident(created.toLong()) },
            persist = { incident ->
                assertTrue(admission.incidents(observationRoot).contains(incident))
                started++; gate.await()
            }, onFailure = failures::add)
        try {
            val first = checkNotNull(submit())
            val second = checkNotNull(submit())
            assertNull(submit()); assertNull(submit())
            assertEquals(2, created); assertEquals(2, started)
            assertEquals(listOf(1L, 2L), admission.incidents(observationRoot).map { it.occurredAtMillis })
            assertEquals(1, failures.size)
            assertTrue(failures.single().message!!.contains("could not be saved"))
            first.cancel(); first.join()
            assertEquals(listOf(2L), admission.incidents(observationRoot).map { it.occurredAtMillis })
            val third = checkNotNull(submit())
            assertEquals(3, created)
            assertNull(submit()); assertEquals(2, failures.size)
            gate.complete(Unit); second.join(); third.join()
            checkNotNull(submit()).join()
            assertEquals(4, created)
            assertTrue(admission.incidents(observationRoot).isEmpty())
        } finally { owner.cancel() }
    }

    @Test fun failedCreationLaunchAndPersistenceReleaseObservationCapacity() = runBlocking {
        val admission = CaptureObservationAdmission(1)
        val owner = kotlinx.coroutines.SupervisorJob()
        val scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Unconfined)
        val failures = mutableListOf<Exception>()
        val brokenScope = object : kotlinx.coroutines.CoroutineScope {
            override val coroutineContext: kotlin.coroutines.CoroutineContext
                get() = throw IllegalStateException("launch unavailable")
        }
        try {
            assertNull(admission.submit(scope, observationRoot, { throw IOException("fact unavailable") }, {}, failures::add))
            assertTrue(admission.incidents(observationRoot).isEmpty())
            assertNull(admission.submit(brokenScope, observationRoot, { RecordingIncident(1) }, {}, failures::add))
            assertTrue(admission.incidents(observationRoot).isEmpty())
            checkNotNull(admission.submit(scope, observationRoot, { RecordingIncident(2) },
                { throw IOException("persistence unavailable") }, failures::add)).join()
            assertEquals(listOf("fact unavailable", "launch unavailable", "persistence unavailable"),
                failures.map { it.message })
            val fatal = LinkageError("fatal factory failure")
            assertSame(fatal, assertThrows(LinkageError::class.java) {
                admission.submit(scope, observationRoot, { throw fatal }, {}, failures::add)
            })
            assertEquals(3, failures.size)
            assertTrue(admission.incidents(observationRoot).isEmpty())
            var persisted = false
            checkNotNull(admission.submit(scope, observationRoot, { RecordingIncident(3) }, { persisted = true }, failures::add)).join()
            assertTrue(persisted)
            checkNotNull(admission.submit(scope, observationRoot, { RecordingIncident(4) },
                { throw IOException("write failed") }, { throw IOException("feedback failed") })).join()
            assertTrue(admission.incidents(observationRoot).isEmpty())
        } finally { owner.cancel() }
    }

    @Test fun cancellationBeforeBodyReleasesCapacityWithoutReportingCaptureFailure() = runBlocking {
        val admission = CaptureObservationAdmission(1)
        val cancelled = kotlinx.coroutines.SupervisorJob().apply { cancel() }
        val cancelledScope = kotlinx.coroutines.CoroutineScope(cancelled + kotlinx.coroutines.Dispatchers.Unconfined)
        val failures = mutableListOf<Exception>()
        var ran = false
        val job = checkNotNull(admission.submit(cancelledScope, observationRoot, { RecordingIncident(1) }, { ran = true }, failures::add))
        job.join()
        assertTrue(job.isCancelled); assertFalse(ran); assertTrue(failures.isEmpty())
        assertTrue(admission.incidents(observationRoot).isEmpty())
        val owner = kotlinx.coroutines.SupervisorJob()
        try {
            val scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Unconfined)
            checkNotNull(admission.submit(scope, observationRoot, { RecordingIncident(2) }, { ran = true }, failures::add)).join()
            assertTrue(ran); assertTrue(failures.isEmpty())
        } finally { owner.cancel() }
    }

    @Test fun exactOwnersAreRootScopedAndReleaseOnlyTheirOwnJob() = runBlocking {
        val admission = CaptureObservationAdmission(3)
        val owner = kotlinx.coroutines.SupervisorJob()
        val scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Unconfined)
        val otherRoot = File(observationRoot, "other")
        val fact = RecordingIncident(1)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fun submit(root: File) = checkNotNull(admission.submit(scope, root, { fact }, { gate.await() },
            { throw AssertionError(it) }))
        try {
            val first = submit(observationRoot)
            val second = submit(observationRoot)
            val other = submit(otherRoot)
            val snapshot = admission.incidents(observationRoot)
            assertEquals(listOf(fact, fact), snapshot)
            assertEquals(listOf(fact), admission.incidents(otherRoot))
            first.cancel(); first.join()
            assertEquals(listOf(fact), admission.incidents(observationRoot))
            assertEquals(listOf(fact), admission.incidents(otherRoot))
            assertEquals(listOf(fact, fact), snapshot)
            gate.complete(Unit); second.join(); other.join()
            assertTrue(admission.incidents(observationRoot).isEmpty())
            assertTrue(admission.incidents(otherRoot).isEmpty())
        } finally { owner.cancel() }
    }

    @Test fun fatalPersistenceAlsoRetiresExactObservationOwner() = runBlocking {
        val admission = CaptureObservationAdmission(1)
        val owner = kotlinx.coroutines.SupervisorJob()
        val fatal = LinkageError("fatal persistence failure")
        var reported: Throwable? = null
        val scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Unconfined +
            kotlinx.coroutines.CoroutineExceptionHandler { _, error -> reported = error })
        try {
            checkNotNull(admission.submit(scope, observationRoot, { RecordingIncident(1) }, { throw fatal },
                { throw AssertionError("Not an ordinary failure", it) })).join()
            assertSame(fatal, reported)
            assertTrue(admission.incidents(observationRoot).isEmpty())
        } finally { owner.cancel() }
    }
}
