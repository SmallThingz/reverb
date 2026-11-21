package app.smallthingz.reverb

import java.io.Closeable
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootResumeRetryTest {
    @Test fun readyBootRetriesImmediatelyWithBackoffUntilItsElapsedDeadline() {
        val fixture = Fixture()
        fixture.start()
        fixture.start()
        fixture.signalWindowChanged()
        assertEquals(listOf(0L), fixture.launchTimes())
        assertEquals(1, fixture.watches)

        fixture.advanceTo(60_000L)
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 30_000L, 45_000L), fixture.launchTimes())
        assertTrue(fixture.launches.all { it.deadline == 60_000L && !it.allowed() })
        assertEquals(1, fixture.unwatches)
        assertTrue(fixture.liveTimers().isEmpty())
        assertTrue(fixture.errors.isEmpty())
        fixture.signalWindowChanged()
        fixture.start()
        assertEquals(7, fixture.launches.size)
        assertEquals(listOf(60_000L), fixture.attemptDeadlines)
        assertEquals(listOf(BootResumeFailure.TIMEOUT), fixture.outcomes)
    }

    @Test fun firstUnlockRunsImmediatelyAndRepeatedSignalsDoNotResetDeadline() {
        val fixture = Fixture(ready = false)
        fixture.start()
        fixture.advanceTo(120_000L)
        fixture.signalWindowChanged()
        assertTrue(fixture.launches.isEmpty())
        assertTrue(fixture.liveTimers().isEmpty())

        fixture.ready = true
        fixture.signalWindowChanged()
        fixture.signalWindowChanged()
        assertEquals(listOf(120_000L), fixture.launchTimes())
        assertEquals(180_000L, fixture.launches.single().deadline)

        fixture.ready = false
        fixture.advanceTo(121_000L)
        assertEquals(listOf(120_000L), fixture.launchTimes())
        val superseded = fixture.liveTimers().single().run
        fixture.advanceTo(121_200L)
        fixture.ready = true
        fixture.signalWindowChanged()
        fixture.signalWindowChanged()
        superseded() // A timer already enqueued before the unlock callback must not run again.
        assertEquals(listOf(120_000L, 121_200L), fixture.launchTimes())
        fixture.advanceTo(181_000L)
        assertTrue(fixture.launches.all { it.deadline == 180_000L })
        assertEquals(1, fixture.unwatches)
        assertEquals(listOf(BootResumeFailure.TIMEOUT), fixture.outcomes)
    }

    @Test fun lateTimerAfterDeepSleepDoesNotReopenExpiredSession() {
        val fixture = Fixture()
        fixture.start()
        fixture.now = 61_000L
        fixture.liveTimers().single().run()
        fixture.drain()
        assertEquals(listOf(0L), fixture.launchTimes())
        assertEquals(1, fixture.unwatches)
        assertFalse(fixture.launches.single().allowed())
    }

    @Test fun lockAndUnlockBetweenRetryTicksTriggersAnImmediateAttemptWithoutExtendingDeadline() {
        val fixture = Fixture()
        fixture.start()
        fixture.advanceTo(16_000L)
        val previousTimer = fixture.liveTimers().single()
        assertEquals(30_000L, previousTimer.at)

        fixture.ready = false
        fixture.signalWindowChanged(screenTurnedOff = true)
        fixture.advanceTo(17_000L)
        fixture.ready = true
        fixture.signalWindowChanged()
        fixture.signalWindowChanged()
        previousTimer.run()
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 17_000L), fixture.launchTimes())
        assertEquals(32_000L, fixture.liveTimers().single().at)
        fixture.advanceTo(60_000L)
        assertTrue(fixture.launches.all { it.deadline == 60_000L })
        assertEquals(1, fixture.unwatches)
    }

    @Test fun stopOrUserDismissalInvalidatesQueuedLaunchBeforeWorkerCleanup() {
        val fixture = Fixture()
        fixture.start()
        val attempt = fixture.launches.single()
        val timer = fixture.liveTimers().single().run
        assertTrue(attempt.allowed())
        attempt.cancel()
        assertFalse(attempt.allowed())
        assertEquals(0, fixture.unwatches)
        timer()
        fixture.drain()
        assertEquals(1, fixture.unwatches)
        assertTrue(fixture.liveTimers().isEmpty())
        assertEquals(listOf(0L), fixture.launchTimes())
        assertEquals(listOf<BootResumeFailure?>(null), fixture.outcomes)
    }

    @Test fun provenCaptureFinishesImmediatelyAndEndsRetriesWithoutNotificationPermission() {
        val fixture = Fixture()
        val notice = BootResumeNoticeController(
            enqueue = { it() },
            readEnabled = { true },
            deliveryAvailable = { false },
            post = { error("Notification permission was denied") },
            cancel = {},
            report = { throw it },
            resume = { _, _ -> fixture.start(); fixture.retry },
        )
        notice.onBoot {}
        assertFalse(notice.needsCaptureProof)
        val launched = fixture.launches.single()
        val admission = BootResumeActivityAdmission(createdAtMillis = launched.at)
        fixture.advanceTo(100L)
        assertTrue(admission.admit(visible = true, nowMillis = fixture.now))
        assertTrue(admission.acceptServiceBind(true, launched.allowed(), fixture.now))
        fixture.advanceTo(350L)
        var finishedAt: Long? = null
        assertTrue(admission.confirmCapture(fixture.now) {
            launched.cancel()
            finishedAt = fixture.now
        })
        assertEquals(350L, finishedAt)
        assertFalse(launched.allowed())
        fixture.advanceTo(60_000L)
        assertEquals(listOf(0L), fixture.launchTimes())
        assertEquals(1, fixture.unwatches)
    }

    @Test fun optOutOrPermissionLossWhileWaitingRetiresWatcherOnNextStateEvent() {
        val fixture = Fixture(ready = false)
        fixture.start()
        fixture.eligible = false
        fixture.signalWindowChanged()
        fixture.eligible = true
        fixture.ready = true
        fixture.signalWindowChanged()
        assertTrue(fixture.launches.isEmpty())
        assertEquals(1, fixture.unwatches)
        assertTrue(fixture.liveTimers().isEmpty())
    }

    @Test fun eachRetryReadsCurrentDurableRecordingIntentAndDisablesItsOldToken() {
        val fixture = Fixture()
        fixture.start()
        assertEquals(1, fixture.intentReads)
        fixture.intentEnabled = false
        fixture.advanceTo(1_000L)
        assertEquals(2, fixture.intentReads)
        assertEquals(listOf(0L), fixture.launchTimes())
        assertFalse(fixture.launches.single().allowed())
        assertEquals(1, fixture.unwatches)
    }

    @Test fun missingPermissionEndsAnOtherwiseCurrentAttemptWithTheObservedCause() {
        val fixture = Fixture()
        fixture.start()
        fixture.permissionFailure = BootResumeFailure.OVERLAY_PERMISSION
        fixture.signalWindowChanged()
        fixture.advanceTo(60_000L)
        assertEquals(listOf(0L), fixture.launchTimes())
        assertEquals(listOf(BootResumeFailure.OVERLAY_PERMISSION), fixture.outcomes)
    }

    @Test fun initialMissingPermissionEndsBeforeWatchingOrTrying() {
        val fixture = Fixture(ready = false)
        fixture.permissionFailure = BootResumeFailure.MICROPHONE_PERMISSION
        fixture.start()
        assertEquals(0, fixture.watches)
        assertTrue(fixture.launches.isEmpty())
        assertTrue(fixture.attemptDeadlines.isEmpty())
        assertEquals(listOf(BootResumeFailure.MICROPHONE_PERMISSION), fixture.outcomes)
    }

    @Test fun stopDuringDurableReadPreventsItsSampleFromQueuingAnActivity() {
        val fixture = Fixture()
        fixture.afterIntentRead = { fixture.retry.close() }
        fixture.start()
        assertTrue(fixture.launches.isEmpty())
        assertEquals(1, fixture.unwatches)
        assertTrue(fixture.liveTimers().isEmpty())
    }

    private class Fixture(var ready: Boolean = true) {
        data class Timer(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)
        data class Launch(val at: Long, val deadline: Long, val allowed: () -> Boolean, val cancel: () -> Unit)

        var now = 0L
        var eligible = true
        var permissionFailure: BootResumeFailure? = null
        var intentEnabled = true
        var intentReads = 0
        var watches = 0
        var unwatches = 0
        var afterIntentRead: () -> Unit = {}
        private var changed: ((Boolean) -> Unit)? = null
        private val queued = ArrayDeque<() -> Unit>()
        private val timers = mutableListOf<Timer>()
        val launches = mutableListOf<Launch>()
        val errors = mutableListOf<Exception>()
        val attemptDeadlines = mutableListOf<Long>()
        val outcomes = mutableListOf<BootResumeFailure?>()
        val retry = BootResumeRetry(
            elapsedMillis = { now },
            enqueue = queued::addLast,
            schedule = { delay, run ->
                val timer = Timer(now + delay, run)
                timers.add(timer)
                Closeable { timer.cancelled = true }
            },
            watchWindow = { callback ->
                watches++
                changed = callback
                Closeable { unwatches++ }
            },
            windowReady = { ready },
            allowed = { eligible },
            durableIntentEnabled = {
                intentReads++
                intentEnabled.also { afterIntentRead() }
            },
            launch = { deadline, allowed, cancel -> launches.add(Launch(now, deadline, allowed, cancel)) },
            report = { errors.add(it) },
            permissionFailure = { permissionFailure },
            attempting = { attemptDeadlines.add(it) },
            ended = { outcomes.add(it) },
        )

        fun start() { retry.start(); drain() }
        fun signalWindowChanged(screenTurnedOff: Boolean = false) { changed?.invoke(screenTurnedOff); drain() }
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
        fun launchTimes(): List<Long> = launches.map { it.at }
        fun liveTimers(): List<Timer> = timers.filterNot { it.cancelled }

        fun advanceTo(end: Long) {
            while (true) {
                val timer = liveTimers().filter { it.at <= end }.minByOrNull { it.at } ?: break
                now = timer.at
                timer.run()
                drain()
            }
            now = end
        }
    }
}
