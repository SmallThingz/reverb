package app.smallthingz.reverb

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCommandUiOwnerTest {
    @Test
    fun clickTransfersWithoutWaitingForWorkerAndDisposalPreservesAcceptedWork() {
        val worker = Executors.newSingleThreadExecutor()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val commands = AtomicInteger()
        val ui = AtomicInteger()
        val admission = CaptureCommandAdmission()
        val owner = CaptureCommandUiOwner<Int>()
        try {
            worker.execute { blocked.countDown(); release.await() }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            assertTrue(owner.submit(
                transfer = { feedback ->
                    admission.submit(
                        retainLifetime = { true },
                        enqueue = { work -> worker.execute(work); true },
                        onIdle = { finished.countDown() },
                    ) {
                        commands.incrementAndGet()
                        feedback.deliver(1)
                    }
                },
                onResult = { ui.incrementAndGet(); true },
            ))
            // The durability worker is still blocked when the click adapter returns.
            assertEquals(0, commands.get())
            assertTrue(admission.isActive())
            owner.retire()
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(1, commands.get())
            assertEquals(0, ui.get())
            assertFalse(admission.isActive())
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun rapidTapsRemainDistinctAndResolveStartSwitchStopOnWorker() {
        val worker = ArrayDeque<Runnable>()
        val main = ArrayDeque<Runnable>()
        val admission = CaptureCommandAdmission()
        val owner = CaptureCommandUiOwner<CaptureBlobTapAction>()
        val actions = mutableListOf<CaptureBlobTapAction>()
        val feedbackActions = mutableListOf<CaptureBlobTapAction>()
        var listening = false
        var active: ReverbService.BufferSlot? = null
        for (slot in listOf(
            ReverbService.BufferSlot.ONE_SHOT,
            ReverbService.BufferSlot.LOOPING,
            ReverbService.BufferSlot.LOOPING,
        )) {
            assertTrue(owner.submit(
                transfer = { feedback ->
                    admission.submit(
                        retainLifetime = { true },
                        enqueue = { worker.add(it) },
                        onIdle = {},
                    ) {
                        val action = captureBlobTapAction(slot, listening, active, true, false, true)
                        actions += action
                        listening = action != CaptureBlobTapAction.STOP
                        active = slot
                        feedback.postIfCurrent(action, { main.add(it) }, { true })
                    }
                },
                onResult = { feedbackActions += it; true },
            ))
        }
        assertTrue(actions.isEmpty())
        while (worker.isNotEmpty()) worker.removeFirst().run()
        while (main.isNotEmpty()) main.removeFirst().run()
        val expected = listOf(CaptureBlobTapAction.START, CaptureBlobTapAction.SWITCH, CaptureBlobTapAction.STOP)
        assertEquals(expected, actions)
        assertEquals(expected, feedbackActions)
        assertFalse(admission.isActive())
    }

    @Test
    fun queuedCompletionCannotReenterRetiredUiOrReplaceNewOwner() {
        val main = ArrayDeque<Runnable>()
        val oldOwner = CaptureCommandUiOwner<Int>()
        val newOwner = CaptureCommandUiOwner<Int>()
        var shown = 0
        assertTrue(oldOwner.submit(
            transfer = { it.postIfCurrent(1, { main.add(it) }, { true }) },
            onResult = { shown = it; true },
        ))
        oldOwner.retire()
        assertTrue(newOwner.submit(
            transfer = { it.deliver(2) },
            onResult = { shown = it; true },
        ))
        main.removeFirst().run()
        assertEquals(2, shown)
        assertFalse(oldOwner.submit(transfer = { error("Retired UI must not submit") }, onResult = { true }))
    }

    @Test
    fun serviceOwnerIsCheckedAtDeliveryAndFeedbackIsTerminalOnce() {
        val main = ArrayDeque<Runnable>()
        var current = true
        var deliveries = 0
        val feedback = CaptureCommandFeedback<Int> { deliveries++; true }
        assertTrue(feedback.postIfCurrent(1, { main.add(it) }, { current }))
        current = false
        main.removeFirst().run()
        assertFalse(feedback.deliver(2))
        assertEquals(0, deliveries)
    }

    @Test
    fun failedPostAndRejectedLifetimeNeverDeliverUiSuccess() {
        var deliveries = 0
        val feedback = CaptureCommandFeedback<Int> { deliveries++; true }
        assertFalse(feedback.postIfCurrent(1, { false }, { true }))
        assertFalse(feedback.deliver(2))
        val owner = CaptureCommandUiOwner<Int>()
        val admission = CaptureCommandAdmission()
        assertFalse(owner.submit(
            transfer = { result ->
                admission.submit(
                    retainLifetime = { false },
                    enqueue = { error("Unowned command must not queue") },
                    onIdle = {},
                ) { result.deliver(3) }
            },
            onResult = { deliveries++; true },
        ))
        assertEquals(0, deliveries)
        assertFalse(admission.isActive())
    }

    @Test
    fun acceptedFailureAfterUiRetirementTransfersToProcessFeedbackBeforeLifetimeEnds() {
        val worker = ArrayDeque<Runnable>()
        val main = ArrayDeque<Runnable>()
        val admission = CaptureCommandAdmission()
        val owner = CaptureCommandUiOwner<CaptureCommandOutcome<Boolean>>()
        val errors = RecorderErrorState()
        val processErrors = mutableListOf<String>()
        var uiCalls = 0
        var settled = false
        assertTrue(owner.submit(
            transfer = { feedback ->
                admission.submit(
                    retainLifetime = { true },
                    enqueue = { worker.add(it) },
                    onIdle = { main.add(Runnable { settled = true }) },
                ) {
                    val outcome = errors.captureCommand {
                        errors.report("Start persistence failed")
                        false
                    }
                    feedback.postIfCurrent(
                        outcome,
                        enqueue = { main.add(it) },
                        isOwnerCurrent = { true },
                        onUndelivered = { it.errorMessage?.let(processErrors::add) },
                    )
                }
            },
            onResult = { uiCalls++; true },
        ))
        // The accepted command has not run, just as when durable storage owns the worker.
        assertTrue(admission.isActive())
        assertTrue(processErrors.isEmpty())
        owner.retire()
        while (worker.isNotEmpty()) worker.removeFirst().run()
        assertFalse(admission.isActive())
        main.removeFirst().run()
        assertEquals(listOf("Start persistence failed"), processErrors)
        assertFalse(settled)
        while (main.isNotEmpty()) main.removeFirst().run()
        assertTrue(settled)
        assertEquals(0, uiCalls)
        assertNull(errors.consumePending())
    }

    @Test
    fun staleServiceOrUiResultTransfersActualFailureExactlyOnce() {
        for (serviceCurrent in listOf(false, true)) {
            val main = ArrayDeque<Runnable>()
            val processErrors = mutableListOf<String>()
            var uiCalls = 0
            val feedback = CaptureCommandFeedback<CaptureCommandOutcome<Boolean>> {
                uiCalls++
                false // The Service may be live while this screen's binding is stale.
            }
            val outcome = CaptureCommandOutcome(false, "Start failed")
            val fallback: (CaptureCommandOutcome<Boolean>) -> Unit = {
                it.errorMessage?.let(processErrors::add)
            }
            assertTrue(feedback.postIfCurrent(outcome, { main.add(it) }, { serviceCurrent }, fallback))
            main.removeFirst().run()
            assertFalse(feedback.deliver(outcome, fallback))
            assertEquals(if (serviceCurrent) 1 else 0, uiCalls)
            assertEquals(listOf("Start failed"), processErrors)
        }
    }

    @Test
    fun rejectedOrThrowingMainPostTransfersActualFailureExactlyOnce() {
        for (throwOnPost in listOf(false, true)) {
            val processErrors = mutableListOf<String>()
            val feedback = CaptureCommandFeedback<CaptureCommandOutcome<Boolean>> {
                throw AssertionError("Rejected post must not enter UI")
            }
            val outcome = CaptureCommandOutcome(false, "Start failed")
            val fallback: (CaptureCommandOutcome<Boolean>) -> Unit = {
                it.errorMessage?.let(processErrors::add)
            }
            assertFalse(feedback.postIfCurrent(
                outcome,
                enqueue = { if (throwOnPost) throw IllegalStateException("queue closed") else false },
                isOwnerCurrent = { true },
                onUndelivered = fallback,
            ))
            assertFalse(feedback.deliver(outcome, fallback))
            assertEquals(listOf("Start failed"), processErrors)
        }
    }

    @Test
    fun currentUiReceivesActualErrorEvenWhenIntentWasAcceptedWithoutDuplicateFallback() {
        val errors = RecorderErrorState()
        val uiErrors = mutableListOf<String>()
        val processErrors = mutableListOf<String>()
        val outcome = errors.captureCommand {
            // Durable intent may succeed while synchronous runtime startup reports failure.
            errors.report("Microphone start failed")
            true
        }
        val feedback = CaptureCommandFeedback<CaptureCommandOutcome<Boolean>> {
            assertTrue(it.result)
            it.errorMessage?.let(uiErrors::add)
            true
        }
        val fallback: (CaptureCommandOutcome<Boolean>) -> Unit = {
            it.errorMessage?.let(processErrors::add)
        }
        assertTrue(feedback.deliver(outcome, fallback))
        assertFalse(feedback.deliver(outcome, fallback))
        assertEquals(listOf("Microphone start failed"), uiErrors)
        assertTrue(processErrors.isEmpty())
        assertNull(errors.consumePending())
    }

    @Test
    fun detachedModeledRejectionAndSuccessfulCommandDoNotInventErrors() {
        for (accepted in listOf(false, true)) {
            val errors = RecorderErrorState()
            val processErrors = mutableListOf<String>()
            val outcome = errors.captureCommand { accepted }
            val feedback = CaptureCommandFeedback<CaptureCommandOutcome<Boolean>> { true }
            feedback.retire()
            assertFalse(feedback.deliver(outcome) { it.errorMessage?.let(processErrors::add) })
            assertTrue(processErrors.isEmpty())
            assertNull(errors.consumePending())
        }
    }

    @Test
    fun nativeStartFailureAfterPlatformContinuationSurvivesCompletedCommandAndRetiredUi() {
        for (retireUi in listOf(false, true)) {
            verifyDeferredTerminalFailure(deferredThroughPlatform = true, retireUi = retireUi)
        }
    }

    @Test
    fun stopSealFailureSurvivesCommandScopeWhileItsFifoTailStillOwnsLifetime() {
        for (retireUi in listOf(false, true)) {
            verifyDeferredTerminalFailure(deferredThroughPlatform = false, retireUi = retireUi)
        }
    }

    private fun verifyDeferredTerminalFailure(deferredThroughPlatform: Boolean, retireUi: Boolean) {
        val worker = ArrayDeque<Runnable>()
        val platform = ArrayDeque<Runnable>()
        val main = ArrayDeque<Runnable>()
        val admission = CaptureCommandAdmission()
        val owner = CaptureCommandUiOwner<CaptureCommandOutcome<Boolean>>()
        val errors = RecorderErrorState()
        val processErrors = mutableListOf<String>()
        val uiResults = mutableListOf<CaptureCommandOutcome<Boolean>>()
        val failure = if (deferredThroughPlatform) "Native start failed" else "Stop seal failed"
        var settled = false
        errors.report("Unrelated pending error")
        val deferredFailure = Runnable {
            // Start's platform callback can arrive after its command ticket completes; Stop's
            // already-enqueued seal tail instead runs before that ticket releases ownership.
            assertEquals(!deferredThroughPlatform, admission.isActive())
            errors.reportTerminalCaptureFailure(failure, processErrors::add)
        }
        assertTrue(owner.submit(
            transfer = { feedback ->
                admission.submit(
                    retainLifetime = { true },
                    enqueue = { worker.add(it) },
                    onIdle = { main.add(Runnable { settled = true }) },
                ) {
                    val outcome = errors.captureCommand {
                        if (deferredThroughPlatform) {
                            platform.add(Runnable { worker.add(deferredFailure) })
                        } else {
                            worker.add(deferredFailure)
                        }
                        true
                    }
                    feedback.postIfCurrent(
                        outcome,
                        enqueue = { main.add(it) },
                        isOwnerCurrent = { true },
                        onUndelivered = { it.errorMessage?.let(processErrors::add) },
                    )
                }
            },
            onResult = { uiResults.add(it); true },
        ))
        worker.removeFirst().run()
        if (retireUi) owner.retire()
        main.removeFirst().run()
        assertEquals(if (retireUi) 0 else 1, uiResults.size)
        uiResults.forEach { assertTrue(it.result); assertNull(it.errorMessage) }
        assertTrue(processErrors.isEmpty())
        assertFalse(settled)
        while (worker.isNotEmpty()) worker.removeFirst().run()
        while (main.isNotEmpty()) main.removeFirst().run()
        assertTrue(settled)
        assertFalse(admission.isActive())
        if (deferredThroughPlatform) {
            assertTrue(processErrors.isEmpty())
            platform.removeFirst().run()
            worker.removeFirst().run()
        }
        assertEquals(listOf(failure), processErrors)
        assertEquals("Unrelated pending error", errors.consumePending())
        assertNull(errors.consumePending())
    }

    @Test
    fun synchronousTerminalFailureKeepsCommandPayloadAndOnceOnlyRetiredUiFallback() {
        val errors = RecorderErrorState()
        val processErrors = mutableListOf<String>()
        errors.report("Unrelated pending error")
        val outcome = errors.captureCommand {
            errors.reportTerminalCaptureFailure("Foreground start failed", processErrors::add)
            true
        }
        assertEquals("Foreground start failed", outcome.errorMessage)
        assertTrue(processErrors.isEmpty())
        val feedback = CaptureCommandFeedback<CaptureCommandOutcome<Boolean>> {
            throw AssertionError("Retired UI must not receive a terminal failure")
        }
        feedback.retire()
        val fallback: (CaptureCommandOutcome<Boolean>) -> Unit = {
            it.errorMessage?.let(processErrors::add)
        }
        assertFalse(feedback.deliver(outcome, fallback))
        assertFalse(feedback.deliver(outcome, fallback))
        assertEquals(listOf("Foreground start failed"), processErrors)
        assertEquals("Unrelated pending error", errors.consumePending())
        assertNull(errors.consumePending())
    }

    @Test
    fun terminalFailureKeepsCombinedPrimaryAndSealMessageOutOfOrdinaryPendingSlot() {
        val errors = RecorderErrorState()
        val processErrors = mutableListOf<String>()
        errors.report("Unrelated pending error")
        val message = combineRecorderTerminalFailureMessages("Native start failed", "Stop seal failed")
        errors.reportTerminalCaptureFailure(message, processErrors::add)
        assertEquals(listOf(message), processErrors)
        assertTrue(message.contains("Native start failed"))
        assertTrue(message.contains("Stop seal failed"))
        assertEquals("Unrelated pending error", errors.consumePending())
        assertNull(errors.consumePending())
    }

    @Test
    fun commandFailureNeverConsumesNewerUnrelatedPendingError() {
        val errors = RecorderErrorState()
        val processErrors = mutableListOf<String>()
        val outcome = errors.captureCommand {
            errors.report("Start failed")
            false
        }
        errors.report("Unrelated newer failure")
        val feedback = CaptureCommandFeedback<CaptureCommandOutcome<Boolean>> { false }
        feedback.deliver(outcome) { it.errorMessage?.let(processErrors::add) }
        assertEquals(listOf("Start failed"), processErrors)
        assertEquals("Unrelated newer failure", errors.consumePending())
        assertNull(errors.consumePending())
    }

    @Test
    fun sameTextInExistingPendingSlotIsDistinctFromCapturedCommandError() {
        val errors = RecorderErrorState()
        errors.report("Persistence failed")
        val outcome = errors.captureCommand {
            errors.report("Persistence failed")
            false
        }
        assertEquals("Persistence failed", outcome.errorMessage)
        assertEquals("Persistence failed", errors.consumePending())
        assertNull(errors.consumePending())
    }

    @Test
    fun capturedErrorsStayOnTheirThreadAndNestedScopesRestoreAfterFailure() {
        val errors = RecorderErrorState()
        val expected = IllegalStateException("nested command failed")
        val outer = errors.captureCommand {
            errors.report("Outer before")
            val other = Thread { errors.report("Other thread") }
            other.start()
            other.join()
            assertSame(expected, assertThrows(IllegalStateException::class.java) {
                errors.captureCommand {
                    errors.report("Nested failure")
                    throw expected
                }
            })
            errors.report("Outer after")
            false
        }
        assertEquals("Outer after", outer.errorMessage)
        assertEquals("Other thread", errors.consumePending())
        errors.report("After scope")
        assertEquals("After scope", errors.consumePending())
    }

    @Test
    fun throwingUiCallbackTransfersActualErrorAndPreservesOriginalException() {
        val main = ArrayDeque<Runnable>()
        val expected = IllegalStateException("UI failed")
        val fallbackFailure = IllegalArgumentException("Fallback failed")
        var fallbackCalls = 0
        val feedback = CaptureCommandFeedback<CaptureCommandOutcome<Boolean>> { throw expected }
        val outcome = CaptureCommandOutcome(false, "Start failed")
        assertTrue(feedback.postIfCurrent(
            outcome,
            enqueue = { main.add(it) },
            isOwnerCurrent = { true },
            onUndelivered = {
                assertEquals("Start failed", it.errorMessage)
                fallbackCalls++
                throw fallbackFailure
            },
        ))
        assertSame(expected, assertThrows(IllegalStateException::class.java) { main.removeFirst().run() })
        assertEquals(listOf(fallbackFailure), expected.suppressed.toList())
        assertFalse(feedback.deliver(outcome) { fallbackCalls++ })
        assertEquals(1, fallbackCalls)
    }
}
