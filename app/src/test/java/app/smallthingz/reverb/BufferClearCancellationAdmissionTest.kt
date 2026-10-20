package app.smallthingz.reverb

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferClearCancellationAdmissionTest {
    @Test
    fun cancelReturnsWhileDurableStepHoldsIntentLockAndPreventsNextStep() {
        assertEquals(BufferClearPhase.CANCELLED, cancelDuringDurableStep())
    }

    @Test
    fun cancellationDoesNotRelabelAnAlreadyFinishingLastChunk() {
        assertEquals(BufferClearPhase.COMPLETED, cancelDuringDurableStep(lastChunk = true))
    }

    @Test
    fun inFlightStorageFailureRemainsFailedAfterAcceptedUserCancel() {
        assertEquals(BufferClearPhase.FAILED, cancelDuringDurableStep(failStep = true))
    }

    @Test
    fun staleOperationIdCannotCancelItsReplacement() {
        val owner = ClearOwner()
        val old = owner.start(1L)
        owner.finish(old, BufferClearPhase.COMPLETED)
        val replacement = owner.start(2L)

        assertFalse(owner.cancel(old.id))
        assertFalse(old.cancelRequested.get())
        assertFalse(replacement.cancelRequested.get())
        assertEquals(BufferClearPhase.RUNNING, owner.phase)
        assertTrue(owner.cancel(replacement.id))
        assertTrue(replacement.cancelRequested.get())
    }

    @Test
    fun completedAndAbsentOwnersRejectCancellationWithoutChangingTerminalState() {
        val owner = ClearOwner()
        assertFalse(owner.cancel(1L))
        val operation = owner.start(1L)
        owner.finish(operation, BufferClearPhase.COMPLETED)

        assertFalse(owner.cancel(operation.id))
        assertFalse(operation.cancelRequested.get())
        assertEquals(BufferClearPhase.COMPLETED, owner.phase)
    }

    @Test
    fun closedAdmissionRejectsCancelBeforeDestroyCanAcquireIntentLock() {
        val owner = ClearOwner()
        val operation = owner.start(1L)
        // Service teardown closes this short lock before waiting for its intent/storage lock.
        owner.closeAdmission()

        assertFalse(owner.cancel(operation.id))
        assertFalse(operation.cancelRequested.get())
        assertEquals(BufferClearPhase.RUNNING, owner.phase)
        assertTrue(bufferClearTeardownBeginsFailure(
            cancelRequested = operation.cancelRequested.get(),
            failureAlreadyRequested = false,
        ))
    }

    @Test
    fun userCancelAcceptedBeforeTeardownKeepsItsNeutralTerminal() {
        val owner = ClearOwner()
        val operation = owner.start(1L)
        assertTrue(owner.cancel(operation.id))
        owner.closeAdmission()

        assertFalse(owner.cancel(operation.id))
        assertFalse(bufferClearTeardownBeginsFailure(
            cancelRequested = operation.cancelRequested.get(),
            failureAlreadyRequested = false,
        ))
        assertEquals(BufferClearPhase.CANCELLED, bufferClearCancellationTerminal(
            reportFailure = false,
            cancelRequested = operation.cancelRequested.get(),
            serviceDestroying = true,
        ))
    }

    @Test
    fun lateProgressCannotUndoCancellingButFinalCompletionStillWins() {
        val owner = ClearOwner()
        val operation = owner.start(1L)
        val earlierSample = BufferClearPhase.RUNNING

        assertTrue(owner.cancel(operation.id))
        owner.publishProgress(operation, earlierSample)
        assertEquals(BufferClearPhase.CANCELLING, owner.phase)
        owner.finish(operation, BufferClearPhase.COMPLETED)
        owner.publishProgress(operation, earlierSample)
        assertEquals(BufferClearPhase.COMPLETED, owner.phase)
        assertFalse(owner.cancel(operation.id))
    }

    /**
     * Uses the production cancellation-admission and continuation policies with a real worker-held
     * intent lock. The latch represents a current checksum/fsync step; no timeout releases it.
     * Cancellation must complete before the test releases that step, without interrupting it.
     */
    private fun cancelDuringDurableStep(
        lastChunk: Boolean = false,
        failStep: Boolean = false,
    ): BufferClearPhase {
        val owner = ClearOwner()
        val operation = owner.start(1L)
        val intentLock = Any()
        val stepEntered = CountDownLatch(1)
        val releaseStep = CountDownLatch(1)
        val finishedDurableSteps = AtomicInteger()
        val attemptedSteps = AtomicInteger()
        val interrupted = AtomicBoolean(false)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val worker = threads.submit(Callable {
                val terminal = try {
                    synchronized(intentLock) {
                        check(mayContinue(operation))
                        attemptedSteps.incrementAndGet()
                        stepEntered.countDown()
                        try {
                            releaseStep.await()
                        } catch (error: InterruptedException) {
                            interrupted.set(true)
                            throw error
                        }
                        if (failStep) throw IOException("in-flight durability step failed")
                        finishedDurableSteps.incrementAndGet()
                    }
                    // A sample captured before Cancel is deliberately published late.
                    owner.publishProgress(operation, BufferClearPhase.RUNNING)
                    if (lastChunk) {
                        BufferClearPhase.COMPLETED
                    } else {
                        synchronized(intentLock) {
                            if (mayContinue(operation)) attemptedSteps.incrementAndGet()
                        }
                        bufferClearCancellationTerminal(
                            reportFailure = false,
                            cancelRequested = operation.cancelRequested.get(),
                        )
                    }
                } catch (_: IOException) {
                    bufferClearTerminalAfterStepFailure()
                }
                owner.finish(operation, terminal)
                terminal
            })
            assertTrue("worker must enter the current durable step", stepEntered.await(5, TimeUnit.SECONDS))
            val cancel = threads.submit(Callable { owner.cancel(operation.id) })

            // The worker still owns intentLock. A cancellation path using it times out here.
            assertTrue(cancel.get(5, TimeUnit.SECONDS))
            assertEquals(1L, releaseStep.count)
            assertEquals(0, finishedDurableSteps.get())
            assertFalse(worker.isDone)
            assertTrue(operation.cancelRequested.get())
            assertEquals(BufferClearPhase.CANCELLING, owner.phase)
            assertFalse(interrupted.get())

            releaseStep.countDown()
            val terminal = worker.get(5, TimeUnit.SECONDS)
            assertEquals(if (failStep) 0 else 1, finishedDurableSteps.get())
            assertEquals("no later chunk may start after accepted Cancel", 1, attemptedSteps.get())
            assertEquals(terminal, owner.phase)
            assertFalse(owner.cancel(operation.id))
            assertFalse(interrupted.get())
            return terminal
        } finally {
            // Always let the admitted step settle, including if an assertion failed.
            releaseStep.countDown()
            threads.shutdown()
            assertTrue("owned workers must terminate", threads.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun mayContinue(operation: Operation): Boolean = bufferClearCanContinue(
        cancelRequested = operation.cancelRequested.get(),
        serviceDestroying = false,
        requestedBuffer = ReverbService.BufferSlot.LOOPING,
        activeBuffer = ReverbService.BufferSlot.ONE_SHOT,
        listeningIntentEnabled = true,
        acceptedGeneration = 7L,
        currentGeneration = 7L,
    )

    private class Operation(val id: Long) {
        val cancelRequested = AtomicBoolean(false)
    }

    /** Injects the same short ownership monitor used by the Service, separate from worker storage. */
    private class ClearOwner {
        private val admissionLock = Any()
        private var closed = false
        private var active: Operation? = null
        @Volatile var phase: BufferClearPhase? = null
            private set

        fun start(id: Long): Operation = synchronized(admissionLock) {
            check(active == null && !closed)
            Operation(id).also {
                active = it
                phase = BufferClearPhase.RUNNING
            }
        }

        fun cancel(id: Long): Boolean = requestCurrentBufferClearCancellation(
            admissionLock = admissionLock,
            admissionClosed = { closed },
            currentOperation = { active },
            matches = { it.id == id },
            request = {
                it.cancelRequested.set(true)
                phase = BufferClearPhase.CANCELLING
            },
        )

        fun closeAdmission() = synchronized(admissionLock) { closed = true }

        fun publishProgress(operation: Operation, sample: BufferClearPhase) = synchronized(admissionLock) {
            if (active === operation) {
                phase = bufferClearProgressPhase(sample, operation.cancelRequested.get())
            }
        }

        fun finish(operation: Operation, terminal: BufferClearPhase) = synchronized(admissionLock) {
            if (active === operation) {
                phase = terminal
                active = null
            }
        }
    }
}
