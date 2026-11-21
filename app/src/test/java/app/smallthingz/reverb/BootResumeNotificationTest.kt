package app.smallthingz.reverb

import android.app.NotificationManager
import android.content.SharedPreferences
import android.provider.Settings
import java.io.Closeable
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

    @Test fun notificationDenialStillStartsOneAutomaticResumeSession() {
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
        assertEquals(1, fixture.automaticCancellations)
        fixture.drain()
    }

    @Test fun optingOutOfAutomaticResumeLeavesFallbackReminderAndItsProof() {
        val fixture = Fixture()
        fixture.boot()
        fixture.drain()
        fixture.controller.dismissAutomaticStatus()
        fixture.boot()
        fixture.drain()
        assertTrue(fixture.visible)
        assertTrue(fixture.controller.needsCaptureProof)
        assertEquals(1, fixture.resumes)
        assertEquals(1, fixture.automaticCancellations)
    }

    @Test fun explicitOptOutClearsSettledOrDiscoveredAutomaticStatusOnly() {
        val settled = Fixture()
        settled.automaticAvailable = true
        settled.boot()
        settled.drain()
        checkNotNull(settled.resumeEvents).ended(BootResumeFailure.OVERLAY_PERMISSION)
        assertEquals(BootAutomaticNotice.Failed(BootResumeFailure.OVERLAY_PERMISSION), settled.automaticNotice)
        assertTrue(settled.controller.needsCaptureProof)
        settled.controller.dismissAutomaticStatus()
        settled.drain()
        assertEquals(null, settled.automaticNotice)
        assertFalse(settled.controller.needsCaptureProof)
        assertEquals(1, settled.posts) // Failed replaced the manual reminder, and opt-out did not repost it.

        val discovered = Fixture()
        discovered.visible = true
        discovered.automaticNotice = BootAutomaticNotice.Failed(BootResumeFailure.TIMEOUT)
        discovered.controller.discoverExistingNotice()
        discovered.drain()
        discovered.controller.dismissAutomaticStatus()
        discovered.drain()
        assertEquals(null, discovered.automaticNotice)
        assertTrue(discovered.visible)
        assertTrue(discovered.controller.needsCaptureProof)
    }

    @Test fun explicitStatusClearBeatsQueuedBootAndStaleRegistryPresence() {
        val fixture = Fixture()
        fixture.automaticNotice = BootAutomaticNotice.Failed(BootResumeFailure.TIMEOUT)
        fixture.automaticRegistrySnapshot = true
        fixture.controller.dismissAutomaticStatus()
        fixture.controller.discoverExistingNotice()
        fixture.boot()
        fixture.drain()
        assertEquals(1, fixture.registryReads)
        assertEquals(0, fixture.resumes)
        assertEquals(null, fixture.automaticNotice)
        assertTrue(fixture.visible)
        assertTrue(fixture.controller.needsCaptureProof)
        assertEquals(listOf("auto-cancel", "post"), fixture.events)
    }

    @Test fun initialMissingPermissionShowsReasonedFailureWithoutTrying() {
        val fixture = Fixture()
        fixture.automaticAvailable = true
        fixture.boot()
        fixture.drain()
        checkNotNull(fixture.resumeEvents).ended(BootResumeFailure.MICROPHONE_PERMISSION)
        assertEquals(BootAutomaticNotice.Failed(BootResumeFailure.MICROPHONE_PERMISSION), fixture.automaticNotice)
        assertEquals(listOf("post", "cancel", "auto-post"), fixture.events)
        assertTrue(fixture.controller.needsCaptureProof)
    }

    @Test fun fatalCaptureFailureNeedsAnAcceptedBindingAndRevokesRetryBeforePublication() {
        val fixture = Fixture()
        fixture.automaticAvailable = true
        fixture.boot()
        fixture.drain()
        val resume = checkNotNull(fixture.resumeEvents)
        val terminal = BootAutomaticNotice.Failed(BootResumeFailure.NO_WRITABLE_BUFFER, intentCleared = true)
        resume.attempting(60_000L)
        assertFalse(fixture.controller.captureFailed(terminal))
        resume.captureBindingAccepted()
        fixture.boot() // A duplicate can run before the queued terminal publication.
        fixture.raw = mapOf(PrefKey.AUDIO_MEMORY_ENABLED.name to false)
        assertTrue(fixture.controller.captureFailed(terminal))
        assertFalse(fixture.resumeAllowed!!.invoke())
        resume.ended(BootResumeFailure.TIMEOUT)
        fixture.drain()
        assertEquals(terminal, fixture.automaticNotice)
        assertEquals(1, fixture.automaticCancellations)
        assertTrue(fixture.controller.needsCaptureProof)
        fixture.boot()
        fixture.drain()
        assertEquals(terminal, fixture.automaticNotice)
    }

    @Test fun fatalCaptureWithClearedIntentDoesNotRestoreObsoleteReminderWhenAutoChannelIsBlocked() {
        val fixture = Fixture()
        fixture.boot()
        fixture.drain()
        checkNotNull(fixture.resumeEvents).captureBindingAccepted()
        fixture.raw = mapOf(PrefKey.AUDIO_MEMORY_ENABLED.name to false)
        assertTrue(fixture.controller.captureFailed(BootAutomaticNotice.Failed(BootResumeFailure.AUDIO_INPUT, intentCleared = true)))
        fixture.drain()
        assertFalse(fixture.visible)
        assertEquals(null, fixture.automaticNotice)
        assertFalse(fixture.controller.needsCaptureProof)
        assertEquals(listOf("post", "cancel"), fixture.events)
    }

    @Test fun acceptedStopOrNewCaptureSupersedesAQueuedRecorderFailure() {
        for (explicitStop in listOf(true, false)) {
            val fixture = Fixture()
            fixture.automaticAvailable = true
            fixture.boot()
            fixture.drain()
            val resume = checkNotNull(fixture.resumeEvents)
            resume.attempting(60_000L)
            resume.captureBindingAccepted()
            val terminal = BootAutomaticNotice.Failed(BootResumeFailure.AUDIO_INPUT, "Microphone failed")
            assertTrue(fixture.controller.captureFailed(terminal))
            if (explicitStop) fixture.controller.dismiss() else fixture.controller.dismissAutomaticStatus()
            fixture.drain()
            assertEquals(null, fixture.automaticNotice)
            assertFalse(fixture.controller.captureFailed(terminal))
            assertEquals(1, fixture.events.count { it == "auto-post" })
        }
    }

    @Test fun activeAutoStatusReplacesReminderThenRetiresTryingBeforePublishingFailed() {
        val fixture = Fixture()
        fixture.automaticAvailable = true
        fixture.boot()
        fixture.drain()
        val resume = checkNotNull(fixture.resumeEvents)
        resume.attempting(60_000L)
        assertEquals(listOf("post", "cancel", "auto-post"), fixture.events)
        assertEquals(BootAutomaticNotice.Trying(60_000L), fixture.automaticNotice)
        assertFalse(fixture.visible)
        assertTrue(fixture.controller.needsCaptureProof)

        resume.ended(BootResumeFailure.TIMEOUT)
        assertEquals(BootAutomaticNotice.Failed(BootResumeFailure.TIMEOUT), fixture.automaticNotice)
        assertEquals(listOf("post", "cancel", "auto-post", "auto-cancel", "auto-post"), fixture.events)
        resume.attempting(120_000L)
        resume.ended(BootResumeFailure.OVERLAY_PERMISSION)
        fixture.boot()
        fixture.drain()
        assertEquals(BootAutomaticNotice.Failed(BootResumeFailure.TIMEOUT), fixture.automaticNotice)
        assertEquals(2, fixture.events.count { it == "auto-post" })
        assertTrue(fixture.controller.needsCaptureProof)
    }

    @Test fun uncertainTryingRetirementBlocksTheFailedNoticeAndManualFallback() {
        val fixture = Fixture()
        fixture.automaticAvailable = true
        fixture.boot()
        fixture.drain()
        val resume = checkNotNull(fixture.resumeEvents)
        resume.attempting(60_000L)
        val initial = IllegalStateException("Initial notification cancellation failed")
        val cleanup = IllegalStateException("Notification cancellation retry failed")
        var attempts = 0
        fixture.duringAutoCancel = { throw if (++attempts == 1) initial else cleanup }
        resume.ended(BootResumeFailure.OVERLAY_PERMISSION)
        assertEquals(2, attempts)
        assertEquals(BootAutomaticNotice.Trying(60_000L), fixture.automaticNotice)
        assertEquals(1, fixture.events.count { it == "auto-post" })
        assertFalse(fixture.visible)
        assertEquals(initial, fixture.errors.single())
        assertEquals(listOf(cleanup), initial.suppressed.toList())

        fixture.duringAutoCancel = {}
        fixture.controller.dismissAutomaticStatus()
        fixture.drain()
        assertEquals(null, fixture.automaticNotice)
        assertFalse(fixture.controller.needsCaptureProof)
    }

    @Test fun dualSlotCancellationAttemptsBothAndPreservesTheFirstFailure() {
        val tryingError = IllegalStateException("Cannot cancel Trying")
        val failedError = IllegalArgumentException("Cannot cancel Failed")
        for (ids in listOf(setOf(47), setOf(48), setOf(47, 48))) {
            val attempted = mutableListOf<Int>()
            val error = runCatching {
                cancelBootAutomaticNotifications { id ->
                    attempted.add(id)
                    if (id in ids) throw if (id == 47) tryingError else failedError
                }
            }.exceptionOrNull()
            assertEquals(listOf(47, 48), attempted)
            val expected = if (47 in ids) tryingError else failedError
            assertTrue(error === expected)
            assertEquals(if (ids.size == 2) listOf(failedError) else emptyList(), error!!.suppressed.toList())
        }
    }

    @Test fun autoStatusFailureOrBlockKeepsOrRestoresOnlyTheManualFallback() {
        for (available in listOf(false, true)) {
            val fixture = Fixture()
            fixture.automaticAvailable = available
            fixture.automaticPublishSucceeds = false
            fixture.boot()
            fixture.drain()
            checkNotNull(fixture.resumeEvents).attempting(60_000L)
            assertTrue(fixture.visible)
            assertEquals(null, fixture.automaticNotice)
            assertEquals(if (available) 2 else 1, fixture.posts)
            assertTrue(fixture.controller.needsCaptureProof)
        }
    }

    @Test fun autoChannelBlockedDuringAttemptRemovesTryingAndRestoresManualProof() {
        val fixture = Fixture()
        fixture.automaticAvailable = true
        fixture.boot()
        fixture.drain()
        val resume = checkNotNull(fixture.resumeEvents)
        resume.attempting(60_000L)
        fixture.automaticAvailable = false
        resume.ended(BootResumeFailure.MICROPHONE_PERMISSION)
        assertEquals(null, fixture.automaticNotice)
        assertTrue(fixture.visible)
        assertTrue(fixture.controller.needsCaptureProof)
        assertEquals(listOf("post", "cancel", "auto-post", "auto-cancel", "post"), fixture.events)
    }

    @Test fun autoOnlyPublicationNeedsProofEvenWhenManualChannelIsBlocked() {
        val fixture = Fixture()
        fixture.available = false
        fixture.automaticAvailable = true
        fixture.boot()
        fixture.drain()
        checkNotNull(fixture.resumeEvents).attempting(60_000L)
        assertEquals(0, fixture.posts)
        assertTrue(fixture.controller.needsCaptureProof)
        fixture.controller.dismiss()
        fixture.drain()
        assertEquals(null, fixture.automaticNotice)
        assertFalse(fixture.controller.needsCaptureProof)
    }

    @Test fun intentionalCancellationInvalidatesAnAlreadyQueuedFailure() {
        for (cancelByHelper in listOf(false, true)) {
            val fixture = Fixture()
            fixture.automaticAvailable = true
            fixture.boot()
            fixture.drain()
            val resume = checkNotNull(fixture.resumeEvents)
            resume.attempting(60_000L)
            fixture.queue.addLast { resume.ended(BootResumeFailure.TIMEOUT) }
            if (cancelByHelper) resume.cancel() else fixture.controller.cancelAutomaticResume()
            fixture.drain()
            assertEquals(null, fixture.automaticNotice)
            assertFalse(fixture.visible)
            assertEquals(1, fixture.events.count { it == "auto-post" })
            assertFalse(fixture.controller.needsCaptureProof)
        }
    }

    @Test fun pcmProofDuringFailurePublicationOwnsFinalRemoval() {
        val fixture = Fixture()
        fixture.automaticAvailable = true
        fixture.boot()
        fixture.drain()
        val resume = checkNotNull(fixture.resumeEvents)
        resume.attempting(60_000L)
        fixture.duringAutoPost = { fixture.controller.dismiss() }
        resume.ended(BootResumeFailure.TIMEOUT)
        assertFalse(fixture.controller.needsCaptureProof)
        fixture.drain()
        assertEquals(null, fixture.automaticNotice)
        assertFalse(fixture.visible)
        fixture.boot()
        fixture.drain()
        assertEquals(2, fixture.events.count { it == "auto-post" })
    }

    @Test fun existingAutomaticNoticeIsDiscoveredForProofWithoutResuming() {
        val fixture = Fixture()
        fixture.automaticNotice = BootAutomaticNotice.Failed(BootResumeFailure.TIMEOUT)
        fixture.controller.discoverExistingNotice()
        fixture.drain()
        assertTrue(fixture.controller.needsCaptureProof)
        assertEquals(0, fixture.resumes)
        assertTrue(fixture.events.isEmpty())
        fixture.controller.dismiss()
        fixture.drain()
        assertEquals(null, fixture.automaticNotice)
    }

    @Test fun bootObservesExistingAutomaticNoticeWhileServiceDiscoverySubmissionIsDelayed() {
        val fixture = Fixture()
        fixture.automaticNotice = BootAutomaticNotice.Failed(BootResumeFailure.TIMEOUT)
        fixture.controller.discoverExistingNotice()
        val delayedDiscovery = fixture.queue.removeFirst()
        fixture.boot()
        fixture.drain()
        delayedDiscovery()
        assertEquals(1, fixture.registryReads)
        assertEquals(0, fixture.posts)
        assertFalse(fixture.visible)
        assertEquals(BootAutomaticNotice.Failed(BootResumeFailure.TIMEOUT), fixture.automaticNotice)
        assertTrue(fixture.controller.needsCaptureProof)
    }

    @Test fun unknownRegistryKeepsExistingNoticesAndDoesNotBlockHelperSession() {
        val fixture = Fixture()
        val previous = BootAutomaticNotice.Failed(BootResumeFailure.OVERLAY_PERMISSION)
        fixture.automaticNotice = previous
        fixture.automaticAvailable = true
        fixture.registryFailures = 4
        fixture.boot()
        fixture.drain()
        assertTrue(fixture.resumeAllowed!!.invoke())
        val resume = checkNotNull(fixture.resumeEvents)
        resume.attempting(60_000L)
        assertEquals(previous, fixture.automaticNotice)
        assertEquals(0, fixture.posts)
        assertEquals(0, fixture.events.count { it == "auto-post" })
        assertFalse(fixture.controller.needsCaptureProof)

        resume.ended(BootResumeFailure.TIMEOUT)
        assertEquals(BootAutomaticNotice.Failed(BootResumeFailure.TIMEOUT), fixture.automaticNotice)
        assertEquals(0, fixture.posts)
        assertEquals(1, fixture.events.count { it == "auto-post" })
        assertTrue(fixture.controller.needsCaptureProof)
    }

    @Test fun stopDuringNotificationPublicationPreventsActivityLaunch() {
        val fixture = Fixture()
        fixture.duringPost = { fixture.controller.dismiss() }
        fixture.boot()
        fixture.drain()
        assertEquals(0, fixture.resumes)
    }

    @Test fun failedSessionSetupPreservesNoticeAndRepeatedBootDoesNotReplaceIt() {
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
        var automaticCancellations = 0
        var resumeAllowed: (() -> Boolean)? = null
        var resumeEvents: BootResumeEvents? = null
        var automaticAvailable = false
        var automaticPublishSucceeds = true
        var automaticNotice: BootAutomaticNotice? = null
        var automaticRegistrySnapshot: Boolean? = null
        var duringAutoPost: () -> Unit = {}
        var duringAutoCancel: () -> Unit = {}
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
            discover = {
                registryReads++
                check(registryReads > registryFailures) { "Active notification registry temporarily unavailable" }
                BootResumeNotices(visible, automaticRegistrySnapshot ?: (automaticNotice != null))
            },
            automaticDeliveryAvailable = { automaticAvailable },
            postAutomatic = { notice ->
                check(!visible) { "Automatic status and the manual reminder cannot both be displayed" }
                check(automaticNotice == null) { "The old automatic notice must retire before its replacement is posted" }
                events.add("auto-post")
                duringAutoPost()
                if (automaticPublishSucceeds) automaticNotice = notice
                automaticPublishSucceeds
            },
            cancelAutomatic = {
                duringAutoCancel()
                if (automaticNotice != null) events.add("auto-cancel")
                automaticNotice = null
            },
            report = { errors.add(it) },
            resume = { allowed, events ->
                resumes++
                resumeAllowed = allowed
                resumeEvents = events
                duringResume()
                Closeable { automaticCancellations++ }
            },
        )
        fun boot() = controller.onBoot { finished++ }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
    }
}
