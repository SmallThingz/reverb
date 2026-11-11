package app.smallthingz.reverb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PreviewTrackQuietOwnerTest {
    private fun CountDownLatch.reached() = assertTrue(await(5, TimeUnit.SECONDS))

    @Test fun blockedOldReleaseCannotDelayNextTrackPauseAndFlush(): Unit = runBlocking {
        val registry = PreviewQuietCoordinator()
        val old = PreviewTrackQuietOwner(registry)
        val next = PreviewTrackQuietOwner(registry)
        val releaseStarted = CountDownLatch(1)
        val releaseAllowed = CountDownLatch(1)
        val terminalWorker = Executors.newSingleThreadExecutor()
        try {
            old.retire()
            assertTrue(old.silence({}, {}))
            val pending = terminalWorker.submit<Boolean> {
                old.release({ fail("pause repeated") }, { fail("flush repeated") },
                    { releaseStarted.countDown(); releaseAllowed.reached() }, { throw it })
            }
            releaseStarted.reached()
            next.retire()
            var pauses = 0
            var flushes = 0
            assertTrue(next.silence({ pauses++ }, { flushes++ }))
            assertTrue(registry.awaitQuiet())
            assertEquals(1, pauses)
            assertEquals(1, flushes)
            assertFalse(pending.isDone)
            releaseAllowed.countDown()
            assertTrue(pending.get(5, TimeUnit.SECONDS))
            next.release({}, {}, {}, { throw it })
        } finally { releaseAllowed.countDown(); terminalWorker.shutdownNow() }
    }

    @Test fun retiredWriteMayReturnButCannotReplayAndReleaseWaitsForItsReturn() = runBlocking {
        val registry = PreviewQuietCoordinator()
        val owner = PreviewTrackQuietOwner(registry)
        val writeStarted = CountDownLatch(1)
        val writeAllowed = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val releases = AtomicInteger()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val write = workers.submit<Int?> {
                owner.write { writeStarted.countDown(); writeAllowed.reached(); 2 }
            }
            writeStarted.reached()
            owner.retire()
            val cleanup = workers.submit<Boolean> {
                owner.release({ paused.countDown() }, {}, { releases.incrementAndGet() }, { throw it })
            }
            paused.reached()
            assertTrue(registry.awaitQuiet())
            assertEquals(0, releases.get())
            assertFalse(owner.play { fail("Retired buffer played after quiet") })
            assertFalse(cleanup.isDone)
            writeAllowed.countDown()
            assertEquals(2, write.get(5, TimeUnit.SECONDS))
            assertTrue(cleanup.get(5, TimeUnit.SECONDS))
            assertEquals(1, releases.get())
            assertNull(owner.write { fail("Retired track accepted another write"); 0 })
        } finally { writeAllowed.countDown(); workers.shutdownNow() }
    }

    @Test fun callerAndStreamFinallyDispatchOneTerminalCleanup() {
        val owner = PreviewTrackQuietOwner(PreviewQuietCoordinator())
        assertTrue(owner.claimCleanupDispatch())
        owner.retire()
        assertFalse(owner.claimCleanupDispatch())
    }

    @Test fun positionReadCannotTouchReleasedOwner() {
        val owner = PreviewTrackQuietOwner(PreviewQuietCoordinator())
        assertEquals(7, owner.read { 7 })
        owner.release({}, {}, {}, { throw it })
        assertNull(owner.read { fail("Position sampled after release"); 0 })
    }

    @Test fun quietProofDoesNotWaitForHeavyRelease() = runBlocking {
        val registry = PreviewQuietCoordinator()
        val owner = PreviewTrackQuietOwner(registry)
        owner.retire()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { registry.awaitQuiet() }
        assertFalse(waiting.isCompleted)
        val releasing = CountDownLatch(1)
        val finishRelease = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val cleanup = worker.submit<Boolean> {
                owner.release({}, {}, { releasing.countDown(); finishRelease.reached() }, { throw it })
            }
            releasing.reached()
            assertTrue(waiting.await())
            assertFalse(cleanup.isDone)
            assertEquals(0, registry.retiringCount())
            finishRelease.countDown()
            assertTrue(cleanup.get(5, TimeUnit.SECONDS))
        } finally { finishRelease.countDown(); worker.shutdownNow() }
    }

    @Test fun duplicateClaimsShareTicketAndReleaseOnlyOnce() = runBlocking {
        val registry = PreviewQuietCoordinator()
        val owner = PreviewTrackQuietOwner(registry)
        assertSame(owner.retire(), owner.retire())
        val releases = AtomicInteger()
        assertTrue(owner.release({}, {}, { releases.incrementAndGet() }, { throw it }))
        assertFalse(owner.release({ fail("duplicate pause") }, { fail("duplicate flush") },
            { releases.incrementAndGet() }, { throw it }))
        assertEquals(1, releases.get())
        assertTrue(registry.awaitQuiet())
    }

    @Test fun cancelledBeforePlayNeverTouchesNativeStream() = runBlocking {
        val registry = PreviewQuietCoordinator()
        val owner = PreviewTrackQuietOwner(registry)
        owner.retire()
        assertFalse(owner.play { fail("retired stream started") })
        owner.release({}, {}, {}, { throw it })
        assertFalse(owner.play { fail("released stream restarted") })
        assertTrue(registry.awaitQuiet())
    }

    @Test fun retirementNeverWaitsForAnInFlightNativePlay() = runBlocking {
        val registry = PreviewQuietCoordinator()
        val owner = PreviewTrackQuietOwner(registry)
        val inPlay = CountDownLatch(1)
        val finishPlay = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val play = workers.submit<Boolean> { owner.play { inPlay.countDown(); finishPlay.reached() } }
            inPlay.reached()
            // This runs while play holds audioGate. UI retirement must still return immediately.
            owner.retire()
            val cleanup = workers.submit<Boolean> { owner.release({ paused.countDown() }, {}, {}, { throw it }) }
            assertEquals(1L, paused.count)
            finishPlay.countDown()
            assertTrue(play.get(5, TimeUnit.SECONDS))
            assertTrue(cleanup.get(5, TimeUnit.SECONDS))
            assertEquals(0L, paused.count)
            assertFalse(owner.play { fail("late play") })
            assertTrue(registry.awaitQuiet())
        } finally { finishPlay.countDown(); workers.shutdownNow() }
    }

    @Test fun rejectedWorkerFailsWaitersButFallbackCanLaterProveQuiet() = runBlocking {
        val registry = PreviewQuietCoordinator()
        val owner = PreviewTrackQuietOwner(registry)
        owner.retire()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { registry.awaitQuiet() }
        owner.cleanupRejected()
        assertFalse(waiting.await())
        assertEquals(1, registry.retiringCount())
        assertFalse(registry.awaitQuiet())
        owner.release({}, {}, {}, { throw it })
        assertTrue(registry.awaitQuiet())
        assertEquals(0, registry.retiringCount())
    }

    @Test fun failedFlushRequiresSuccessfulReleaseFallback() = runBlocking {
        val registry = PreviewQuietCoordinator()
        val owner = PreviewTrackQuietOwner(registry)
        val flushError = IllegalStateException("flush")
        var reported: Throwable? = null
        var quietDuringRelease = false
        owner.release({}, { throw flushError }, {
            // Not acknowledged before the only successful terminal native proof.
            quietDuringRelease = registry.retiringCount() == 0
        }, { reported = it })
        assertFalse(quietDuringRelease)
        assertSame(flushError, reported)
        assertTrue(registry.awaitQuiet())
    }

    @Test fun uncertainNativeReleaseKeepsUnsafeTicketWithoutRetryingOwner() = runBlocking {
        val registry = PreviewQuietCoordinator()
        val owner = PreviewTrackQuietOwner(registry)
        val pauseError = IllegalStateException("pause")
        val releaseError = IllegalStateException("release")
        var reported: Throwable? = null
        owner.release({ throw pauseError }, {}, { throw releaseError }, { reported = it })
        assertSame(pauseError, reported)
        assertArrayEquals(arrayOf(releaseError), reported!!.suppressed)
        assertFalse(registry.awaitQuiet())
        assertEquals(1, registry.retiringCount())
        assertFalse(owner.release({}, {}, { fail("uncertain native owner retried") }, {}))
    }

    @Test fun cancelledWaiterCannotConsumeAnotherWaitersProof() = runBlocking {
        val registry = PreviewQuietCoordinator()
        val ticket = registry.register()
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { registry.awaitQuiet() }
        val live = async(start = CoroutineStart.UNDISPATCHED) { registry.awaitQuiet() }
        cancelled.cancel()
        registry.quiet(ticket)
        assertTrue(live.await())
    }
    @Test fun twoRetiringTracksRequireBothIndependentQuietProofs() = runBlocking {
        val registry = PreviewQuietCoordinator()
        val first = PreviewTrackQuietOwner(registry)
        val second = PreviewTrackQuietOwner(registry)
        first.retire()
        second.retire()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { registry.awaitQuiet() }
        assertEquals(2, registry.retiringCount())
        assertFalse(waiting.isCompleted)

        first.release({}, {}, {}, { throw it })
        // Run any incorrectly resumed waiter before inspecting completion; no timing sleep is needed.
        yield()
        assertEquals(1, registry.retiringCount())
        assertFalse("One quiet track must not authorize playback over the other", waiting.isCompleted)
        assertFalse(first.release({}, {}, {}, { throw it }))
        yield()
        assertEquals(1, registry.retiringCount())
        assertFalse(waiting.isCompleted)

        second.release({}, {}, {}, { throw it })
        assertTrue(waiting.await())
        assertEquals(0, registry.retiringCount())
    }

    @Test fun duplicateReleaseDuringNativePauseCannotAcknowledgeQuiet() = runBlocking {
        val registry = PreviewQuietCoordinator()
        val owner = PreviewTrackQuietOwner(registry)
        val originalTicket = owner.retire()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { registry.awaitQuiet() }
        val pauseEntered = CountDownLatch(1)
        val finishPause = CountDownLatch(1)
        val releases = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val firstCleanup = worker.submit<Boolean> {
                owner.release(
                    { pauseEntered.countDown(); finishPause.reached() },
                    {},
                    { releases.incrementAndGet() },
                    { throw it },
                )
            }
            pauseEntered.reached()
            assertSame(originalTicket, owner.retire())
            assertFalse(owner.release(
                { fail("Duplicate caller reached native pause") },
                { fail("Duplicate caller reached native flush") },
                { fail("Duplicate caller reached native release") },
                { throw it },
            ))
            yield()
            assertFalse("Duplicate claim is not native silence proof", waiting.isCompleted)
            assertEquals(1, registry.retiringCount())
            assertEquals(0, releases.get())
            assertFalse(firstCleanup.isDone)

            finishPause.countDown()
            assertTrue(firstCleanup.get(5, TimeUnit.SECONDS))
            assertTrue(waiting.await())
            assertEquals(1, releases.get())
            assertEquals(0, registry.retiringCount())
        } finally {
            finishPause.countDown()
            worker.shutdownNow()
        }
    }
}
