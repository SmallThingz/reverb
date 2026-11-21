package app.smallthingz.reverb

import android.app.NotificationManager
import android.content.SharedPreferences
import android.provider.Settings
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootResumeNotificationTest {
    @Test fun appDenialTakesPrecedenceOverBlockedChannelForReview() {
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            bootResumeNotificationSettingsAction(false, NotificationManager.IMPORTANCE_NONE))
    }

    @Test fun blockedChannelWithAppPermissionOpensChannelReview() {
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,
            bootResumeNotificationSettingsAction(true, NotificationManager.IMPORTANCE_NONE))
    }

    @Test fun absentOrEnabledChannelUsesAppSettingsFallback() {
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, bootResumeNotificationSettingsAction(true, null))
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            bootResumeNotificationSettingsAction(true, NotificationManager.IMPORTANCE_LOW))
    }

    @Test fun enabledIntentPostsOnlyOnceAcrossRepeatedBootDelivery() {
        val fixture = Fixture()
        fixture.boot()
        fixture.boot()
        assertEquals(0, fixture.posts)
        fixture.drain()
        assertEquals(1, fixture.posts)
        assertEquals(2, fixture.finished)
        assertTrue(fixture.visible)
    }

    @Test fun missingIntentDoesNotInventRecording() {
        val fixture = Fixture(emptyMap<String, Any?>())
        fixture.visible = true
        fixture.boot()
        fixture.drain()
        assertEquals(0, fixture.posts)
        assertFalse(fixture.visible)
    }

    @Test fun stopBeforeQueuedBootReadWinsWithoutChangingPreferences() {
        val fixture = Fixture()
        fixture.boot()
        fixture.raw = mapOf(PrefKey.AUDIO_MEMORY_ENABLED.name to false)
        fixture.controller.dismiss()
        fixture.drain()
        assertEquals(0, fixture.posts)
        assertFalse(fixture.visible)
        assertEquals(mapOf(PrefKey.AUDIO_MEMORY_ENABLED.name to false), fixture.raw)
    }

    @Test fun stopDuringReadInvalidatesSampledEnabledIntent() {
        val fixture = Fixture()
        fixture.afterRead = { fixture.controller.dismiss() }
        fixture.boot()
        fixture.drain()
        assertEquals(0, fixture.posts)
        assertFalse(fixture.visible)
    }

    @Test fun stopDuringPlatformPublicationCancelsAfterPublication() {
        val fixture = Fixture()
        fixture.duringPost = { fixture.controller.dismiss() }
        fixture.boot()
        fixture.drain()
        assertEquals(listOf("post", "cancel"), fixture.events)
        assertFalse(fixture.visible)
        assertEquals(1, fixture.finished)
    }

    @Test fun successfulResumeSuppressesLateRepeatedBootEvenWhileIntentRemainsEnabled() {
        val fixture = Fixture()
        fixture.boot()
        fixture.drain()
        fixture.controller.dismiss()
        fixture.boot()
        fixture.drain()
        assertEquals(1, fixture.posts)
        assertFalse(fixture.visible)
        assertEquals(true, fixture.raw[PrefKey.AUDIO_MEMORY_ENABLED.name])
    }

    @Test fun notificationDenialLeavesIntentUntouchedAndDoesNotReportAnIncident() {
        val fixture = Fixture()
        val before = fixture.raw
        fixture.available = false
        fixture.boot()
        fixture.drain()
        assertEquals(0, fixture.posts)
        assertEquals(before, fixture.raw)
        assertTrue(fixture.errors.isEmpty())
        assertEquals(1, fixture.finished)
    }

    @Test fun notificationDenialStillAllowsOneVisibleActivityAttempt() {
        val fixture = Fixture()
        fixture.available = false
        fixture.boot()
        fixture.boot()
        fixture.drain()
        assertEquals(0, fixture.posts)
        assertEquals(1, fixture.resumes)
        assertTrue(fixture.resumeAllowed!!.invoke())
        assertFalse(fixture.controller.needsCaptureProof)
    }

    @Test fun failedPlatformPublicationDoesNotManufactureReminderOwnershipOrPreventHelperLaunch() {
        val fixture = Fixture()
        fixture.publishSucceeds = false
        fixture.boot()
        fixture.drain()
        assertFalse(fixture.visible)
        assertFalse(fixture.controller.needsCaptureProof)
        assertTrue(fixture.resumeAllowed!!.invoke())
    }

    @Test fun aNewControllerDiscoversOnlyTheExistingNoticeAndStopRevokesItsProof() {
        val fixture = Fixture()
        fixture.visible = true
        fixture.controller.discoverExistingNotice()
        fixture.controller.discoverExistingNotice()
        assertFalse(fixture.controller.needsCaptureProof)
        fixture.drain()
        assertTrue(fixture.controller.needsCaptureProof)
        assertEquals(1, fixture.registryReads)
        assertEquals(0, fixture.posts)
        assertEquals(0, fixture.resumes)
        fixture.controller.dismiss()
        assertFalse(fixture.controller.needsCaptureProof)
        fixture.drain()
        assertFalse(fixture.visible)
        assertEquals(listOf("cancel"), fixture.events)
    }

    @Test fun failedRegistryDiscoveryCanRetryOnAFutureServiceStart() {
        val fixture = Fixture()
        fixture.visible = true
        fixture.registryFailures = 2
        fixture.controller.discoverExistingNotice()
        fixture.drain()
        assertFalse(fixture.controller.needsCaptureProof)
        assertEquals(2, fixture.registryReads)
        fixture.controller.discoverExistingNotice()
        fixture.drain()
        assertTrue(fixture.controller.needsCaptureProof)
        assertEquals(3, fixture.registryReads)
        assertEquals(1, fixture.errors.size)
        assertEquals(0, fixture.posts)
    }

    @Test fun notificationPostFailureDoesNotRevokeVisibleActivityAttempt() {
        val fixture = Fixture()
        fixture.duringPost = { throw SecurityException("revoked") }
        fixture.boot()
        fixture.drain()
        assertEquals(1, fixture.resumes)
        assertEquals(1, fixture.errors.size)
    }

    @Test fun lateActivityAdmissionIsRevokedByAcceptedStop() {
        val fixture = Fixture()
        fixture.boot()
        fixture.drain()
        assertTrue(fixture.resumeAllowed!!.invoke())
        fixture.controller.dismiss()
        assertFalse(fixture.resumeAllowed!!.invoke())
        fixture.drain()
    }

    @Test fun stopDuringNotificationPublicationPreventsActivityLaunch() {
        val fixture = Fixture()
        fixture.duringPost = { fixture.controller.dismiss() }
        fixture.boot()
        fixture.drain()
        assertEquals(0, fixture.resumes)
    }

    @Test fun failedActivityLaunchPreservesNoticeAndIsNotRepeated() {
        val fixture = Fixture()
        fixture.duringResume = { throw IllegalStateException("launch unavailable") }
        fixture.boot()
        fixture.boot()
        fixture.drain()
        assertEquals(1, fixture.resumes)
        assertEquals(1, fixture.posts)
        assertTrue(fixture.visible)
        assertEquals(1, fixture.errors.size)
    }

    @Test fun failedStopWithRestoredEnabledIntentKeepsResumeNoticeEligible() {
        val fixture = Fixture()
        fixture.boot()
        val accepted = commitRecorderPreferenceMutation(commit = { false }, onException = {})
        if (accepted) fixture.controller.dismiss()
        fixture.drain()
        assertFalse(accepted)
        assertEquals(1, fixture.posts)
        assertTrue(fixture.visible)
        assertEquals(true, fixture.raw[PrefKey.AUDIO_MEMORY_ENABLED.name])
    }

    @Test fun bootReadWaitsForFailedStopToRestoreItsTemporaryPreferenceMap() {
        val raw = AtomicReference<Map<String, *>>(mapOf(PrefKey.AUDIO_MEMORY_ENABLED.name to true))
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            check(method.name == "getAll")
            raw.get()
        } as SharedPreferences
        val reading = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val posted = AtomicBoolean(false)
        val failure = AtomicReference<Exception?>()
        val controller = BootResumeNoticeController(
            enqueue = { it() },
            readEnabled = {
                reading.countDown()
                readDurableCaptureIntentPreferences(prefs).enabled
            },
            deliveryAvailable = { true },
            post = {
                posted.set(true)
                true
            },
            cancel = { posted.set(false) },
            report = { failure.set(it) },
        )
        val reader = Thread { controller.onBoot { finished.countDown() } }.apply { isDaemon = true }
        try {
            synchronized(captureIntentPreferenceLock) {
                // Model Android's in-memory publication inside an unaccepted Stop transaction.
                raw.set(mapOf(PrefKey.AUDIO_MEMORY_ENABLED.name to false))
                reader.start()
                try {
                    assertTrue(reading.await(5, TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (reader.state != Thread.State.BLOCKED && finished.count != 0L && System.nanoTime() < deadline) {
                        Thread.yield()
                    }
                    assertEquals(Thread.State.BLOCKED, reader.state)
                    assertEquals(1L, finished.count)
                } finally {
                    // Failed commit restores true before releasing the recorder transaction monitor.
                    raw.set(mapOf(PrefKey.AUDIO_MEMORY_ENABLED.name to true))
                }
            }
        } finally {
            reader.join(5_000L)
        }
        assertEquals(0L, finished.count)
        assertEquals(null, failure.get())
        assertTrue(posted.get())
    }

    @Test fun malformedIntentAndDestinationFailClosedWithoutRepairingRawState() {
        val corruptStates = listOf(
            mapOf(PrefKey.AUDIO_MEMORY_ENABLED.name to "true"),
            mapOf(PrefKey.AUDIO_MEMORY_ENABLED.name to null),
            mapOf(PrefKey.AUDIO_MEMORY_ENABLED.name to true, PrefKey.CAPTURE_BUFFER_SLOT.name to 999),
            mapOf(PrefKey.AUDIO_MEMORY_ENABLED.name to true, PrefKey.CAPTURE_BUFFER_SLOT.name to null),
        )
        for (raw in corruptStates) {
            val fixture = Fixture(raw)
            fixture.visible = true
            fixture.boot()
            fixture.drain()
            assertEquals(0, fixture.posts)
            assertFalse(fixture.visible)
            assertEquals(raw, fixture.raw)
            assertEquals(1, fixture.errors.size)
            assertEquals(1, fixture.finished)
        }
    }

    @Test fun postFailureAlwaysFinishesAndRetainsEnabledIntent() {
        val fixture = Fixture()
        fixture.duringPost = { throw SecurityException("Notification permission revoked") }
        fixture.boot()
        fixture.drain()
        assertEquals(1, fixture.errors.size)
        assertEquals(1, fixture.finished)
        assertEquals(true, fixture.raw[PrefKey.AUDIO_MEMORY_ENABLED.name])
        assertFalse(fixture.visible)
    }

    @Test fun newProcessReadsLatestStoppedIntentInsteadOfPreviousNotice() {
        val old = Fixture()
        old.boot()
        old.drain()
        val fresh = Fixture(mapOf(PrefKey.AUDIO_MEMORY_ENABLED.name to false))
        fresh.visible = old.visible
        fresh.boot()
        fresh.drain()
        assertEquals(0, fresh.posts)
        assertFalse(fresh.visible)
    }

    private class Fixture(var raw: Map<String, *> = mapOf(PrefKey.AUDIO_MEMORY_ENABLED.name to true)) {
        val queue = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val errors = mutableListOf<Exception>()
        var posts = 0
        var finished = 0
        var visible = false
        var available = true
        var publishSucceeds = true
        var registryReads = 0
        var registryFailures = 0
        var resumes = 0
        var resumeAllowed: (() -> Boolean)? = null
        var afterRead: () -> Unit = {}
        var duringPost: () -> Unit = {}
        var duringResume: () -> Unit = {}
        val controller = BootResumeNoticeController(
            enqueue = { queue.addLast(it) },
            readEnabled = {
                val enabled = decodeDurableCaptureIntentPreferences(raw).enabled
                afterRead()
                enabled
            },
            deliveryAvailable = { available },
            post = {
                events.add("post")
                posts++
                duringPost()
                visible = publishSucceeds
                visible
            },
            cancel = { events.add("cancel"); visible = false },
            isNoticeActive = {
                registryReads++
                check(registryReads > registryFailures) { "Active notification registry temporarily unavailable" }
                visible
            },
            report = { errors.add(it) },
            resume = { allowed -> resumes++; resumeAllowed = allowed; duringResume() },
        )
        fun boot() = controller.onBoot { finished++ }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
    }
}
