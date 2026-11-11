package app.smallthingz.reverb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecordingScrubAuthorityTest {
    private fun CountDownLatch.reached() = assertTrue(await(5, TimeUnit.SECONDS))
    private class Revoked : RuntimeException()
    private class Handle(val owner: PreviewTrackQuietOwner, val control: Executor) : RecordingScrubQuietHandle {
        val pauses = AtomicInteger()
        val flushes = AtomicInteger()
        override fun retire() { owner.retire() }
        override fun requestQuiet() = owner.dispatchSilence {
            control.execute { owner.silence({ pauses.incrementAndGet() }, { flushes.incrementAndGet() }) }
        }
    }

    @Test fun compatibleSuspensionPreservesLeaseButRevisionGenerationAndUnpreparedPublicationRevoke() {
        val registry = RecordingScrubAuthorities()
        val key = RecordingScrubKey(9, 4)
        val first = registry.acquire(key)
        registry.published(key)
        assertTrue(first.active)
        registry.published(key.copy(transportIntentRevision = 5))
        assertFalse(first.active)
        val second = registry.acquire(key)
        registry.published(key.copy(generation = 10))
        assertFalse(second.active)
        val third = registry.acquire(key)
        registry.published(null)
        assertFalse(third.active)
        val replacement = registry.acquire(RecordingScrubKey(10, 5))
        registry.finish(first)
        registry.finish(second)
        assertTrue(registry.owns(replacement))
        registry.finish(replacement)
        assertFalse(registry.owns(replacement))
    }

    @Test fun acceptedMainHandoffThenExternalPauseCannotPlayWhileCollectorIsWithheld() = runBlocking {
        val quiet = PreviewQuietCoordinator()
        val registry = RecordingScrubAuthorities()
        val authority = registry.acquire(RecordingScrubKey(1, 1))
        val owner = PreviewTrackQuietOwner(quiet)
        val queuedQuiet = ArrayDeque<Runnable>()
        val handle = Handle(owner, Executor { queuedQuiet.addLast(it) })
        assertTrue(authority.attach(handle))
        val mainAction = AtomicReference<() -> Unit>()
        val mainPosted = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        val allowPlay = CountDownLatch(1)
        val starts = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> {
                owner.write { 32 }
                awaitPreviewPlaybackHandoff({ if (!authority.active) throw Revoked() },
                    { action -> mainAction.set(action); mainPosted.countDown(); true }, {})
                accepted.countDown()
                allowPlay.reached()
                // Mirrors the final native-owner gate even if the preceding lease read won its race.
                owner.play { starts.incrementAndGet() }
            }
            mainPosted.reached()
            mainAction.get().invoke()
            accepted.reached()
            registry.published(RecordingScrubKey(1, 2))
            assertEquals(0, handle.pauses.get())
            allowPlay.countDown()
            assertFalse(result.get(5, TimeUnit.SECONDS))
            assertEquals(0, starts.get())
            assertEquals(1, queuedQuiet.size)
            queuedQuiet.removeFirst().run()
            assertTrue(quiet.awaitQuiet())
            assertTrue(owner.release({}, {}, {}, { throw it }))
        } finally { allowPlay.countDown(); worker.shutdownNow() }
    }

    @Test fun revocationBeforeMainCallbackRejectsHandoffWithoutCallingActivityAction() {
        val authority = RecordingScrubAuthority(RecordingScrubKey(1, 1))
        val posted = CountDownLatch(1)
        val mainAction = AtomicReference<() -> Unit>()
        val calls = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> {
                try {
                    awaitPreviewPlaybackHandoff({ if (!authority.active) throw Revoked() },
                        { action -> mainAction.set(action); posted.countDown(); true }, { calls.incrementAndGet() })
                    false
                } catch (_: Revoked) { true }
            }
            posted.reached()
            authority.revoke()
            mainAction.get().invoke()
            assertTrue(result.get(5, TimeUnit.SECONDS))
            assertEquals(0, calls.get())
        } finally { worker.shutdownNow() }
    }

    @Test fun alreadyPlayingRevocationQueuesQuietWithoutAnyUiCollector() = runBlocking {
        val quiet = PreviewQuietCoordinator()
        val authority = RecordingScrubAuthority(RecordingScrubKey(2, 3))
        val tasks = ArrayDeque<Runnable>()
        val handle = Handle(PreviewTrackQuietOwner(quiet), Executor { tasks.addLast(it) })
        assertTrue(authority.attach(handle))
        assertTrue(handle.owner.play {})
        authority.revoke()
        assertFalse(handle.owner.play { fail("Retired owner replayed") })
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(1, handle.pauses.get())
        assertEquals(1, handle.flushes.get())
        assertTrue(quiet.awaitQuiet())
        assertTrue(handle.owner.release({}, {}, {}, { throw it }))
    }

    @Test fun lateAttachIsRetiredAndOldDetachCannotDetachReplacementNativeOwner() {
        val tasks = ArrayDeque<Runnable>()
        val authority = RecordingScrubAuthority(RecordingScrubKey(2, 3))
        fun handle() = Handle(PreviewTrackQuietOwner(PreviewQuietCoordinator()), Executor { tasks.addLast(it) })
        val old = handle()
        val next = handle()
        assertTrue(authority.attach(old))
        authority.detach(old)
        assertTrue(authority.attach(next))
        authority.detach(old)
        authority.revoke()
        assertFalse(next.owner.play { fail("Replacement was not retired") })
        val late = handle()
        assertFalse(authority.attach(late))
        assertFalse(late.owner.play { fail("Late native owner played") })
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        old.owner.release({}, {}, {}, { throw it })
        next.owner.release({}, {}, {}, { throw it })
        late.owner.release({}, {}, {}, { throw it })
    }

    @Test fun revocationDoesNotWaitForAlreadyAdmittedNativePlayAndSilencesAfterward(): Unit = runBlocking {
        val quiet = PreviewQuietCoordinator()
        val authority = RecordingScrubAuthority(RecordingScrubKey(3, 4))
        val controls = Executors.newSingleThreadExecutor()
        val workers = Executors.newFixedThreadPool(2)
        val insidePlay = CountDownLatch(1)
        val allowPlayReturn = CountDownLatch(1)
        val handle = Handle(PreviewTrackQuietOwner(quiet), controls)
        assertTrue(authority.attach(handle))
        try {
            val playing = workers.submit<Boolean> { handle.owner.play {
                insidePlay.countDown(); allowPlayReturn.reached()
            } }
            insidePlay.reached()
            workers.submit { authority.revoke() }.get(5, TimeUnit.SECONDS)
            assertFalse(authority.active)
            assertEquals(0, handle.pauses.get())
            allowPlayReturn.countDown()
            assertTrue(playing.get(5, TimeUnit.SECONDS))
            controls.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(1, handle.pauses.get())
            assertFalse(handle.owner.play { fail("Second play admitted after revocation") })
            assertTrue(quiet.awaitQuiet())
            handle.owner.release({}, {}, {}, { throw it })
        } finally { allowPlayReturn.countDown(); workers.shutdownNow(); controls.shutdownNow() }
    }

    @Test fun rejectedQuietSubmissionIsRecordedBeforeConcurrentTerminalCleanupCanReport() = runBlocking {
        val quiet = PreviewQuietCoordinator()
        val owner = PreviewTrackQuietOwner(quiet)
        val submitting = CountDownLatch(1)
        val allowRejection = CountDownLatch(1)
        val terminalEntered = CountDownLatch(1)
        val rejection = RejectedExecutionException("control closed")
        val report = AtomicReference<Throwable?>()
        val workers = Executors.newFixedThreadPool(2)
        val handle = Handle(owner, Executor {
            submitting.countDown(); allowRejection.reached(); throw rejection
        })
        owner.retire()
        try {
            val dispatch = workers.submit { handle.requestQuiet() }
            submitting.reached()
            val cleanup = workers.submit<Boolean> {
                terminalEntered.countDown()
                owner.release({}, {}, {}, { report.set(it) })
            }
            terminalEntered.reached()
            assertFalse(cleanup.isDone)
            allowRejection.countDown()
            dispatch.get(5, TimeUnit.SECONDS)
            assertTrue(cleanup.get(5, TimeUnit.SECONDS))
            assertSame(rejection, report.get())
            assertTrue(quiet.awaitQuiet())
        } finally { allowRejection.countDown(); workers.shutdownNow() }
    }

    @Test fun rejectedDispatchResumesQuietWaiterOnlyAfterReleasingOwnerStateLock() = runBlocking {
        val quiet = PreviewQuietCoordinator()
        val owner = PreviewTrackQuietOwner(quiet)
        val rejection = RejectedExecutionException("control closed")
        val report = AtomicReference<Throwable?>()
        val worker = Executors.newSingleThreadExecutor()
        owner.retire()
        try {
            val waiter = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                assertFalse(quiet.awaitQuiet())
                // This continuation can run inline from coordinator.failed. Another worker must
                // be able to take stateLock before this continuation returns to the publisher.
                worker.submit<Boolean> {
                    owner.release({}, {}, {}, { report.set(it) })
                }.get(5, TimeUnit.SECONDS)
            }
            owner.dispatchSilence { throw rejection }
            assertTrue(waiter.await())
            assertSame(rejection, report.get())
            assertTrue(quiet.awaitQuiet())
        } finally { worker.shutdownNow() }
    }

    @Test fun rejectionStaysPrimaryAndNativeCleanupFailuresRemainSuppressed() {
        val owner = PreviewTrackQuietOwner(PreviewQuietCoordinator())
        val rejection = RejectedExecutionException("control closed")
        val pauseFailure = IllegalStateException("pause failed")
        val releaseFailure = IllegalStateException("release failed")
        owner.retire()
        owner.dispatchSilence { throw rejection }
        var report: Throwable? = null
        assertTrue(owner.release({ throw pauseFailure }, {}, { throw releaseFailure }, { report = it }))
        assertSame(rejection, report)
        assertArrayEquals(arrayOf(pauseFailure, releaseFailure), rejection.suppressed)
        assertFalse(owner.nativeReleaseSucceeded)
    }

    @Test fun existingTerminalCleanupOwnsSilenceWithoutDuplicateIndependentDispatch() {
        val owner = PreviewTrackQuietOwner(PreviewQuietCoordinator())
        assertTrue(owner.claimCleanupDispatch())
        owner.retire()
        owner.dispatchSilence { fail("Full cleanup already owns control dispatch") }
        assertTrue(owner.release({}, {}, {}, { throw it }))
    }
}
