package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BootResumeActivityTest {
    @Test fun initialSleepPauseCanWaitForActualForegroundWithoutBindingWhileHidden() {
        val admission = BootResumeActivityAdmission(createdAtMillis = 100L)
        // First resume, queued sleep pause, then second resume before the first real draw.
        assertFalse(admission.observeForeground(visible = false, nowMillis = 110L))
        assertFalse(admission.departed(120L))
        assertFalse(admission.admit(visible = false, nowMillis = 130L))
        assertFalse(admission.departed(140L))
        assertFalse(admission.admitted)
        assertTrue(admission.observeForeground(visible = true, nowMillis = 150L))
        assertTrue(admission.admit(visible = true, nowMillis = 160L))
        assertFalse(admission.admit(visible = true, nowMillis = 170L))
    }

    @Test fun departureDuringDurableReadCannotAdmitAfterFocusReturns() {
        val admission = BootResumeActivityAdmission(createdAtMillis = 100L)
        assertTrue(admission.observeForeground(visible = true, nowMillis = 110L))
        assertFalse(admission.admitted) // The durable read has not returned yet.
        assertTrue(admission.departed(120L))
        assertFalse(admission.admit(visible = true, nowMillis = 130L))
    }

    @Test fun delayedWakeCallbacksCannotExtendOriginalDeadline() {
        val admission = BootResumeActivityAdmission(createdAtMillis = 100L)
        assertFalse(admission.departed(110L))
        assertEquals(10L, admission.remainingMillis(15_090L))
        // No Handler timeout has run while asleep; the resume itself checks elapsed time.
        assertFalse(admission.withinDeadline(15_100L)) // Also used by the queued Start admission callback.
        assertFalse(admission.observeForeground(visible = true, nowMillis = 15_100L))
        assertFalse(admission.admit(visible = true, nowMillis = 15_101L))
        assertEquals(0L, admission.remainingMillis(20_000L))
        assertFalse(admission.current(120L)) // A stale earlier callback cannot resurrect it.
    }

    @Test fun stopRevokesWaitingLaunchBeforeAnyVisibleAdmission() {
        val admission = BootResumeActivityAdmission(createdAtMillis = 100L)
        var allowed = true
        val launch = BootResumeLaunch("boot", 100L, { allowed }, null)
        assertFalse(admission.departed(110L))
        allowed = false
        assertFalse(admission.current(120L, launch.current(0L)))
        allowed = true
        assertFalse(admission.admit(visible = true, nowMillis = 130L))
    }

    @Test fun mainVisibilityTakeoverStaysTerminalAfterMainBecomesHidden() {
        val owners = StartedMainOwners()
        var visible = false
        owners.start(Any()) { visible }
        val launch = BootResumeLaunch("boot", 100L, { true }, null, owners.visibilityRevision)
        val admission = BootResumeActivityAdmission(createdAtMillis = 100L)
        assertFalse(owners.refreshVisibility())
        assertTrue(admission.current(110L, launch.current(owners.visibilityRevision)))
        visible = true
        assertTrue(owners.refreshVisibility())
        val revision = owners.visibilityRevision
        assertTrue(owners.refreshVisibility())
        assertEquals(revision, owners.visibilityRevision)
        visible = false
        assertFalse(owners.refreshVisibility())
        assertFalse(admission.current(120L, launch.current(owners.visibilityRevision)))
        assertFalse(admission.admit(visible = true, nowMillis = 130L))
    }

    @Test fun backOrDestructionDuringInitialWaitRemainsTerminal() {
        val admission = BootResumeActivityAdmission(createdAtMillis = 100L)
        assertFalse(admission.departed(110L))
        admission.abandon()
        assertFalse(admission.observeForeground(visible = true, nowMillis = 120L))
        assertFalse(admission.admit(visible = true, nowMillis = 130L))
    }

    @Test fun hiddenWindowCannotConsumeAdmission() {
        val admission = BootResumeActivityAdmission()
        assertFalse(admission.admit(visible = false))
        assertFalse(admission.admitted)
        assertTrue(admission.admit(visible = true))
    }

    @Test fun repeatedDrawOrResumeCannotCreateAnotherBinding() {
        val admission = BootResumeActivityAdmission()
        assertTrue(admission.admit(visible = true))
        assertFalse(admission.admit(visible = true))
    }

    @Test fun visibilityLossOrUserDismissalIsTerminalEvenAfterFocusReturns() {
        for (initiallyVisible in listOf(false, true)) {
            val admission = BootResumeActivityAdmission()
            admission.admit(initiallyVisible)
            admission.abandon()
            assertFalse(admission.admit(visible = true))
        }
    }

    @Test fun positiveSilentPcmCanConfirmOnlyTheCurrentReadBoundary() {
        val confirmation = BootCaptureConfirmation(100L) {}
        confirmation.arm(3L, 4L)
        assertFalse(confirmation.accepts(3L, 4L, 99L, 64))
        assertFalse(confirmation.accepts(3L, 4L, 101L, 0))
        assertFalse(confirmation.accepts(3L, 4L, 101L, -1))
        // Content amplitude is deliberately absent: valid silent PCM is still capture.
        assertTrue(confirmation.accepts(3L, 4L, 101L, 64))
    }

    @Test fun stopRestartOrDestinationChangeCannotReuseEarlierGenerationProof() {
        val confirmation = BootCaptureConfirmation(100L) {}
        confirmation.arm(3L, 4L)
        assertFalse(confirmation.accepts(4L, 4L, 101L, 64))
        assertFalse(confirmation.accepts(3L, 5L, 101L, 64))
    }

    @Test fun activityDisposalRevokesAlreadyQueuedPcmDelivery() {
        var callbacks = 0
        val confirmation = BootCaptureConfirmation(100L) { callbacks++ }
        confirmation.arm(3L, 4L)
        assertTrue(confirmation.accepts(3L, 4L, 101L, 64))
        confirmation.close()
        confirmation.deliver(current = true)
        assertEquals(0, callbacks)
        assertFalse(confirmation.active)
    }

    @Test fun stopOrTeardownAtMainDeliveryRejectsPreviouslyAcceptedPcm() {
        var callbacks = 0
        val confirmation = BootCaptureConfirmation(100L) { callbacks++ }
        confirmation.arm(3L, 4L)
        assertTrue(confirmation.accepts(3L, 4L, 101L, 64))
        confirmation.deliver(current = false)
        confirmation.deliver(current = true)
        assertEquals(0, callbacks)
    }

    @Test fun confirmedCaptureFinishesItsWindowOnlyOnce() {
        var callbacks = 0
        val confirmation = BootCaptureConfirmation(100L) { callbacks++ }
        confirmation.arm(3L, 4L)
        confirmation.deliver(current = true)
        confirmation.deliver(current = true)
        assertEquals(1, callbacks)
        assertFalse(confirmation.active)
    }

    @Test fun explicitOffTestIsOneShotAndNeverConsumesWhileRecording() {
        val arm = RestartTestArm(100L)
        assertFalse(arm.consume(101L, stopped = false))
        assertTrue(arm.available(101L))
        assertTrue(arm.consume(102L, stopped = true))
        assertFalse(arm.consume(103L, stopped = true))
    }

    @Test fun noOpStopOrMainReturnRevokesOffTestBeforeConsumption() {
        val arm = RestartTestArm(100L)
        // Every accepted control invalidates even when durable OFF did not change.
        arm.cancel()
        assertFalse(arm.consume(101L, stopped = true))
    }

    @Test fun offTestExpiresAndRejectsClockRegression() {
        val arm = RestartTestArm(100L)
        assertFalse(arm.available(99L))
        assertTrue(arm.available(120_100L))
        assertFalse(arm.consume(120_101L, stopped = true))
    }

    @Test fun consumedTestStartUsesNewCaptureProofInsteadOfConsumedArm() {
        val arm = RestartTestArm(100L)
        assertTrue(arm.consume(101L, stopped = true))
        arm.cancel() // The canonical Start also runs accepted-control invalidation.
        var finished = false
        val proof = BootCaptureConfirmation(200L) { finished = true }
        proof.arm(2L, 3L) // Registered after Start, not against its previous generation.
        assertTrue(proof.accepts(2L, 3L, 201L, 64))
        proof.deliver(current = true)
        assertTrue(finished)
    }

    @Test fun newerStopStillRevokesConsumedTestCaptureProof() {
        val arm = RestartTestArm(100L)
        assertTrue(arm.consume(101L, stopped = true))
        var finished = false
        val proof = BootCaptureConfirmation(200L) { finished = true }
        proof.arm(2L, 3L)
        arm.cancel()
        assertFalse(proof.accepts(2L, 4L, 201L, 64))
        proof.deliver(current = false)
        assertFalse(finished)
    }

    @Test fun lifecycleCancellationDuringDurableReadCannotResurrectAnArm() {
        val tests = RestartTestRegistry()
        val epoch = tests.invalidate()
        tests.invalidate() // Main returned while the durable read was outstanding.
        assertFalse(tests.publish(epoch, RestartTestArm(100L)) { true })
        assertEquals(null, tests.current())
    }

    @Test fun cancellationAtPublicationRevokesTheExactToken() {
        val tests = RestartTestRegistry()
        val epoch = tests.invalidate()
        val arm = RestartTestArm(100L)
        var checks = 0
        assertFalse(tests.publish(epoch, arm) {
            if (++checks == 2) tests.invalidate()
            checks == 1
        })
        assertFalse(arm.available(101L))
        assertEquals(null, tests.current())
    }

    @Test fun delayedOldArmOrCanceledCoroutineCannotRetireNewerArm() {
        val tests = RestartTestRegistry()
        val oldEpoch = tests.invalidate()
        val old = RestartTestArm(100L)
        val newEpoch = tests.invalidate()
        val current = RestartTestArm(101L)
        assertTrue(tests.publish(newEpoch, current) { true })
        assertFalse(tests.publish(oldEpoch, old) { true })
        tests.retire(old)
        assertTrue(tests.current() === current)
        assertTrue(current.available(102L))
    }

    @Test fun stoppingOneMainDoesNotPretendAnotherVisibleMainIsBackground() {
        val owners = StartedMainOwners()
        val first = Any()
        val second = Any()
        owners.start(first)
        owners.start(second)
        assertFalse(owners.stop(first))
        assertTrue(owners.anyStarted)
        assertTrue(owners.stop(second))
        assertFalse(owners.anyStarted)
        assertFalse(owners.stop(second))
    }

    @Test fun acceptedControlAfterOwnStartRevokesItsSuccessAndFailureOwnership() {
        val tests = RestartTestRegistry()
        val arm = RestartTestArm(100L)
        val epoch = tests.invalidate()
        assertTrue(tests.publish(epoch, arm) { true })
        assertTrue(arm.consume(101L, stopped = true))
        tests.invalidate() // Canonical Start succeeds, then snapshots its new control epoch.
        val ownStartEpoch = tests.revision()
        assertEquals(ownStartEpoch, tests.revision())
        tests.invalidate() // Even an accepted no-op Stop/selection owns a newer boundary.
        assertFalse(ownStartEpoch == tests.revision())
    }

    @Test fun cancellationRemovalWinsEvenBeforeTokenCancellationFinishes() {
        val removed = CountDownLatch(1)
        val finishCancel = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val registry = RestartTestRegistry { token ->
            removed.countDown()
            check(finishCancel.await(2, TimeUnit.SECONDS))
            token.cancel()
        }
        val token = RestartTestArm(100L)
        assertTrue(registry.publish(registry.revision(), token) { true })
        val cancel = Thread {
            try { registry.invalidate() } catch (error: Throwable) { failure.set(error) }
        }
        cancel.start()
        try {
            assertTrue(removed.await(2, TimeUnit.SECONDS))
            assertTrue(token.available(101L)) // Cancellation is deliberately paused after removal.
            assertFalse(registry.claim(token, 101L, stopped = true))
        } finally {
            finishCancel.countDown()
            cancel.join(2_000L)
        }
        assertFalse(cancel.isAlive)
        failure.get()?.let { throw it }
    }

    @Test fun claimWinningAcceptsCommandBeforeLaterNavigationCancellation() {
        val registry = RestartTestRegistry()
        val token = RestartTestArm(100L)
        assertTrue(registry.publish(registry.revision(), token) { true })
        val admitted = registry.claim(token, 101L, stopped = true)
        registry.invalidate()
        registry.retire(token)
        // Accepted commands own completion; navigation cannot retroactively unaccept this Start.
        assertTrue(admitted)
        assertFalse(registry.claim(token, 102L, stopped = true))
    }

    @Test fun exactOwnAcceptedEpochCannotAdoptLaterMainCancellation() {
        val accepted = RestartTestResult.Started(RestartTestAdmission(4L, 8L))
        assertEquals(accepted, currentRestartTestResult(accepted, 8L, 4L, visible = true, healthy = true))
        assertEquals(RestartTestResult.Superseded,
            currentRestartTestResult(accepted, 9L, 4L, visible = true, healthy = true))
    }

    @Test fun rejectedStartStillReportsAfterClaimWithoutRevivingArm() {
        val registry = RestartTestRegistry()
        val token = RestartTestArm(100L)
        assertTrue(registry.publish(registry.revision(), token) { true })
        val epoch = registry.revision()
        assertTrue(registry.claim(token, 101L, stopped = true))
        val rejected = RestartTestResult.Rejected(epoch, "The selected buffer is unavailable")
        assertEquals(rejected,
            currentRestartTestResult(rejected, epoch, 0L, visible = true, healthy = false))
        assertEquals(null, registry.current())
        registry.invalidate()
        assertEquals(RestartTestResult.Superseded,
            currentRestartTestResult(rejected, registry.revision(), 0L, visible = true, healthy = false))
    }

    @Test fun startedHiddenMainDoesNotSuppressTheBootHelper() {
        val owners = StartedMainOwners()
        val owner = Any()
        var visible = false
        owners.start(owner) { visible }
        assertTrue(owners.anyStarted)
        assertFalse(owners.refreshVisibility())
        visible = true
        assertTrue(owners.refreshVisibility())
        owners.stop(owner)
        assertFalse(owners.refreshVisibility())
    }

    @Test fun normalMainNeedsAllVisibleWindowConditionsAndNoCoveringKeyguard() {
        assertTrue(mainWindowCanResumeCapture(true, true, true, true, true, false))
        assertFalse(mainWindowCanResumeCapture(false, true, true, true, true, false))
        assertFalse(mainWindowCanResumeCapture(true, false, true, true, true, false))
        assertFalse(mainWindowCanResumeCapture(true, true, false, true, true, false))
        assertFalse(mainWindowCanResumeCapture(true, true, true, false, true, false))
        assertFalse(mainWindowCanResumeCapture(true, true, true, true, false, false))
        assertFalse(mainWindowCanResumeCapture(true, true, true, true, true, true))
    }

    @Test fun mainBecomingVisibleRetiresAlreadyQueuedOrdinaryBootLaunch() {
        val boot = BootResumeLaunch("boot", 100L, { true }, null)
        val pending = AtomicReference<BootResumeLaunch?>(boot)
        retirePendingBootLaunch(pending, mainVisible = false)
        assertTrue(pending.get() === boot)
        retirePendingBootLaunch(pending, mainVisible = true)
        assertEquals(null, pending.get())
        // Losing focus to the helper cannot resurrect the retired request.
        retirePendingBootLaunch(pending, mainVisible = false)
        assertEquals(null, pending.get())
    }

    @Test fun ordinaryVisibilitySuppressionDoesNotReplaceExplicitTestCancellation() {
        val test = BootResumeLaunch("test", 100L, { true }, RestartTestArm(100L))
        val pending = AtomicReference<BootResumeLaunch?>(test)
        retirePendingBootLaunch(pending, mainVisible = true)
        assertTrue(pending.get() === test)
    }
}
