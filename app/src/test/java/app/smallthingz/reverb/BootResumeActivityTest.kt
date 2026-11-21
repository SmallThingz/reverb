package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BootResumeActivityTest {
    @Test fun optOutBeforeQueuedBindDispatchPreventsServiceCreation() {
        val policy = AutoStartPolicy()
        policy.load(0L, true)
        val launch = requireNotNull(policy.admission())
        val admission = BootResumeActivityAdmission(createdAtMillis = 100L)
        assertTrue(admission.admit(visible = true, nowMillis = 110L))
        policy.request(false)
        assertFalse(admission.acceptServiceBind(true, policy.currentAdmission(launch), 120L))
        assertFalse(admission.bindAccepted)
    }

    @Test fun acceptedBindKeepsPcmConfirmationAfterNoticeDismissalOrLaterPolicyOptOut() {
        val policy = AutoStartPolicy()
        policy.load(0L, true)
        val launch = requireNotNull(policy.admission())
        var noticeAllowsLaunch = true
        val admission = BootResumeActivityAdmission(createdAtMillis = 100L)
        assertTrue(admission.admit(visible = true, nowMillis = 110L))
        assertTrue(admission.acceptServiceBind(true, noticeAllowsLaunch && policy.currentAdmission(launch), 120L))
        noticeAllowsLaunch = false // First PCM may dismiss the reminder before service connection delivery.
        policy.request(false) // Opt-out affects future starts, not this accepted binding.
        assertFalse(noticeAllowsLaunch && policy.currentAdmission(launch))
        assertTrue(admission.current(130L))
        assertTrue(admission.confirmCapture(140L) {})
        assertTrue(admission.captureConfirmed)
    }

    @Test fun confirmationRequiresAcceptedBindingAndRemainsSingleUseBeforeDeadline() {
        val admission = BootResumeActivityAdmission(createdAtMillis = 100L)
        var finishes = 0
        val finish = { finishes++; Unit }
        assertFalse(admission.confirmCapture(110L, finish))
        assertTrue(admission.admit(visible = true, nowMillis = 15_090L))
        assertFalse(admission.confirmCapture(15_091L, finish))
        assertTrue(admission.acceptServiceBind(true, true, 15_092L))
        assertTrue(admission.confirmCapture(15_099L, finish))
        assertEquals(1, finishes)
        assertFalse(admission.confirmCapture(15_099L, finish))
        assertFalse(admission.confirmCapture(15_100L, finish))
        assertEquals(1, finishes)
    }

    @Test fun departureOrTeardownRetiresLateConfirmation() {
        val admission = BootResumeActivityAdmission(createdAtMillis = 100L)
        assertTrue(admission.admit(visible = true, nowMillis = 110L))
        assertTrue(admission.acceptServiceBind(true, true, 120L))
        admission.abandon()
        assertFalse(admission.confirmCapture(130L) { error("Destroyed helper cannot finish on stale confirmation") })
        val next = BootResumeActivityAdmission(createdAtMillis = 200L)
        assertFalse(next.captureConfirmed)
    }

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
        assertFalse(admission.withinDeadline(15_100L))
        assertFalse(admission.observeForeground(visible = true, nowMillis = 15_100L))
        assertFalse(admission.admit(visible = true, nowMillis = 15_101L))
        assertEquals(0L, admission.remainingMillis(20_000L))
        assertFalse(admission.current(120L)) // A stale earlier callback cannot resurrect it.
    }

    @Test fun stopRevokesWaitingLaunchBeforeAnyVisibleAdmission() {
        val admission = BootResumeActivityAdmission(createdAtMillis = 100L)
        var allowed = true
        val launch = BootResumeLaunch("boot", 100L, { allowed })
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
        val launch = BootResumeLaunch("boot", 100L, { true }, owners.visibilityRevision)
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

    @Test fun bindDenialRetiresThatAttemptAndFreshPermissionRetryBindsOnce() {
        val denied = BootResumeActivityAdmission(createdAtMillis = 100L)
        assertTrue(denied.admit(visible = true, nowMillis = 110L))
        assertFalse(denied.acceptServiceBind(visible = true, allowed = false, nowMillis = 120L))
        // A later permission grant cannot revive callbacks from the denied attempt.
        assertFalse(denied.acceptServiceBind(visible = true, allowed = true, nowMillis = 121L))

        val retry = BootResumeActivityAdmission(createdAtMillis = 200L)
        assertTrue(retry.admit(visible = true, nowMillis = 210L))
        assertTrue(retry.acceptServiceBind(visible = true, allowed = true, nowMillis = 220L))
        assertFalse(retry.acceptServiceBind(visible = true, allowed = true, nowMillis = 221L))
        assertTrue(retry.bindAccepted)
        assertTrue(retry.current(221L))
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
        assertFalse(confirmation.accepts(3L, 4L, 101L, 64))
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

    @Test fun policyQueryDoesNotMultiplyWhileInFlightOrConfirmAClosedHelper() {
        val queryEntered = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val writer = ArrayBlockingQueue<() -> Unit>(8)
        val samples = AtomicInteger()
        var confirmed = 0
        val original = BootCaptureConfirmation(0L) {}.apply { arm(3L, 4L) }
        val replacement = BootCaptureConfirmation(0L) {}.apply { arm(3L, 4L) }

        fun packet(helper: BootCaptureConfirmation, sample: () -> Boolean?) {
            helper.checkMicrophonePolicy(
                inputEpoch = 5L,
                readStartedAtNanos = System.nanoTime(),
                postToWriter = writer::offer,
                sample = {
                    samples.incrementAndGet()
                    sample()
                },
                confirmed = { confirmed++ },
            )
        }
        fun drainPolicyQueries() {
            val drained = CountDownLatch(1)
            executeCaptureInputPolicyQuery(drained::countDown)
            assertTrue(drained.await(5, TimeUnit.SECONDS))
        }
        fun nextWriterResult(): () -> Unit = checkNotNull(writer.poll(5, TimeUnit.SECONDS))

        try {
            packet(original) {
                queryEntered.countDown()
                if (releaseQuery.await(5, TimeUnit.SECONDS)) false else null
            }
            assertTrue(queryEntered.await(5, TimeUnit.SECONDS))
            // Exceed the retry delay while the original query is still outstanding.
            Thread.sleep(300L)
            repeat(10) { packet(original) { false } }
            releaseQuery.countDown()
            drainPolicyQueries()
            assertEquals(1, samples.get())
            nextWriterResult()()
            assertTrue(writer.isEmpty())

            packet(original) { false }
            val lateResult = nextWriterResult()
            assertEquals(2, samples.get())
            original.close()
            packet(replacement) { false }
            lateResult()
            nextWriterResult()()
            assertEquals(3, samples.get())
            assertEquals(0, confirmed)
            assertTrue(replacement.active)
        } finally {
            original.close()
            replacement.close()
            releaseQuery.countDown()
        }
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
        val owner = BootResumeLaunchOwner()
        val boot = BootResumeLaunch("boot", 100L, { true }, mainVisibilityRevision = 3L)
        assertTrue(owner.reserve(boot))
        assertFalse(boot.current(4L))
        assertEquals(null, owner.claim(boot.id, 110L, 4L))
        owner.expirePending(boot)
        assertEquals(null, owner.claim(boot.id, 120L, 4L))
    }

    @Test fun pendingLaunchTimeoutDoesNotReleaseAClaimedWindow() {
        val owner = BootResumeLaunchOwner()
        val first = BootResumeLaunch("first", 100L, { true })
        val next = BootResumeLaunch("next", 2_200L, { true })
        assertTrue(owner.reserve(first))
        assertFalse(owner.reserve(next))
        assertTrue(owner.claim(first.id, 120L, 0L) === first)
        assertEquals(null, owner.claim(first.id, 140L, 0L))
        owner.expirePending(first)
        assertFalse(owner.reserve(next))
        owner.release(first)
        assertTrue(owner.reserve(next))
        owner.release(first)
        assertTrue(owner.claim(next.id, 2_210L, 0L) === next)
    }

    @Test fun delayedActivityAndStaleExpiryCannotClaimOrRetireTheRetryToken() {
        val owner = BootResumeLaunchOwner()
        val delayed = BootResumeLaunch("delayed", 100L, { true })
        assertTrue(owner.reserve(delayed))
        assertEquals(null, owner.claim(delayed.id, 2_100L, 0L))
        owner.expirePending(delayed)
        val next = BootResumeLaunch("next", 2_200L, { true }, timeoutMillis = 600L)
        assertTrue(owner.reserve(next))
        assertEquals(null, owner.claim(delayed.id, 2_201L, 0L))
        owner.expirePending(delayed)
        assertTrue(owner.ownsPending(next))
        assertEquals(null, owner.claim(next.id, 2_800L, 0L))
    }

}
