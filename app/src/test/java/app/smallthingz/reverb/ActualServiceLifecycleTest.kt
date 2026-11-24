package app.smallthingz.reverb

import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import app.smallthingz.reverb.actualserviceisolated.ActualServiceSandboxMarker
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowService
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadow.api.Shadow

/** Actual stopped-Service lifecycle, terminal snapshot delivery, and owned PCM recovery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [JournalHostIoShadow::class,
    ProbeAudioRecordShadow::class, ProbeAudioRecordBuilderShadow::class],
    // Android shadow targets normalize into the shared android.* instrumentation package.
    // Use a real test-only package to isolate this fixture's borrowed process identity.
    instrumentedPackages = ["app.smallthingz.reverb.actualserviceisolated"])
@LooperMode(LooperMode.Mode.PAUSED)
class ActualServiceLifecycleTest {
    @Before fun verifySandboxOwnership() {
        val loader = ActualServiceSandboxMarker::class.java.classLoader
        assertSame(loader, javaClass.classLoader)
        assertSame(loader, ReverbService::class.java.classLoader)
        assertSame(loader, RecordingIncidentStore::class.java.classLoader)
    }

    class NoMicService : ReverbService() {
        val inputAttempts = AtomicInteger()
        @Volatile internal var syntheticInput: CaptureAudioInput? = null
        internal var afterCreate: (() -> Unit)? = null

        override fun onCreate() {
            super.onCreate()
            val callback = afterCreate
            afterCreate = null
            callback?.invoke()
        }

        override fun createAudioRecord(): CaptureAudioInput? {
            inputAttempts.incrementAndGet()
            return syntheticInput ?: throw AssertionError("Unexpected microphone admission")
        }
    }

    @Test fun stoppedInvalidTileStartAndDestroyRejectQueuedSnapshotButPreservePcm() = lifecycle(false)
    @Test fun publicCaptureStopKeepsCompletedPcmAndRejectsHeldRead() = lifecycle(true)
    @Test fun retiredFalseWakePreferenceDoesNotCreateCaptureLock() = lifecycle(true, legacyWake = false)
    @Test fun retiredTrueWakePreferenceDoesNotCreateCaptureLock() = lifecycle(true, legacyWake = true)
    @Test fun retiredMalformedWakePreferenceDoesNotCreateCaptureLock() = lifecycle(true, legacyWake = "invalid")
    @Test fun nullCaptureServiceStartFailsClosedAfterIntentCommit() = lifecycle(false, false)
    @Test fun thrownCaptureServiceStartFailsClosedAfterIntentCommit() = lifecycle(false, true)
    @Test fun quickSaveReportsColdRecoveryRejectionAndRestoresOverlappingClear() =
        lifecycle(capture = false, rejectNotificationQuickSave = true)

    private fun acceptedStartComponent() = android.content.ComponentName("app.smallthingz.reverb", "fixture.Owner")

    @Test fun serviceStartReturnsTheExactComponentOwner() {
        val owner = acceptedStartComponent()
        assertSame(owner, requireServiceStarted { owner })
    }

    @Test
    fun durableLifetimePreservesOriginalStartAndPromotionFailures() {
        for (failDuringStart in listOf(true, false)) {
            val expected = SecurityException("Android denied foreground work")
            val events = mutableListOf<String>()
            val result = attemptDurableSaveLifetimeProtection(null,
                start = {
                    events += "start"
                    if (failDuringStart) throw expected
                    acceptedStartComponent()
                },
                promote = {
                    events += "promote"
                    throw expected
                })
            assertSame(expected, result.failure)
            assertEquals(if (failDuringStart) listOf("start") else listOf("start", "promote"), events)
        }
    }

    @Test
    fun durableLifetimeRequiresComponentBeforePromotionAndSucceedsInOrder() {
        var promotions = 0
        val missing = attemptDurableSaveLifetimeProtection(null, { null }, { promotions++ })
        assertTrue(missing.failure is IllegalStateException)
        assertTrue(missing.failure!!.message.orEmpty().contains("no component"))
        assertEquals(0, promotions)
        val events = mutableListOf<String>()
        val accepted = attemptDurableSaveLifetimeProtection(null,
            { events += "start"; acceptedStartComponent() }, { events += "promote" })
        assertEquals(null, accepted.failure)
        assertEquals(listOf("start", "promote"), events)
    }

    private fun lifecycle(capture: Boolean, rejectedStartThrows: Boolean? = null,
        rejectNotificationQuickSave: Boolean = false, legacyWake: Any? = null) {
        val app = RuntimeEnvironment.getApplication()
        val noBackup = app.noBackupFilesDir
        val appRoot = requireNotNull(app.filesDir.parentFile).canonicalFile
        check(noBackup.canonicalFile.parentFile == appRoot)
        check(noBackup.listFiles().orEmpty().isEmpty()) { "No-backup root already owned" }
        val prefs = getRecorderPreferences(app)
        check(prefs.all.isEmpty()) { "Recorder preferences already owned" }
        check(ShadowPowerManager.getLatestWakeLock() == null) { "Wake lock observation already owned" }
        // Positive control proves the platform observer sees creation, even without acquire().
        val observedLock = requireNotNull(app.getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fixture:creation-observer")
        assertSame(observedLock, ShadowPowerManager.getLatestWakeLock())
        assertFalse(observedLock.isHeld)
        ShadowPowerManager.clearWakeLocks()
        if (rejectedStartThrows != null || rejectNotificationQuickSave)
            check(AppFeedbackCenter.events.value.isEmpty()) { "Feedback already owned" }
        check(ServiceStateOsShadow.fixtureRoot == null)
        JournalHostIoShadow.reset()
        val snapshotCleanupScope = Class.forName("app.smallthingz.reverb.TimelineSnapshotWorkKt")
            .getDeclaredField("timelineSnapshotCleanupScope").apply { isAccessible = true }.get(null) as CoroutineScope
        val snapshotChildrenBefore = children(snapshotCleanupScope)
        check(snapshotChildrenBefore.isEmpty()) { "Another timeline cleanup owns the process scope" }
        val incidentScope = RecordingIncidentStore::class.java.getDeclaredField("historyMutationScope")
            .apply { isAccessible = true }.get(RecordingIncidentStore) as CoroutineScope
        val incidentChildrenBefore = children(incidentScope)
        check(incidentChildrenBefore.isEmpty()) { "Incident work already owned" }
        val incidentRevision = RecordingIncidentStore.historyRevision.value
        check(incidentRetriesEmpty()) { "Incident retry already owned" }
        val incidentEpochField = RecordingIncidentStore::class.java.getDeclaredField("captureSessionPublicationEpoch")
            .apply { isAccessible = true }
        val priorIncidentEpoch = incidentEpochField.get(RecordingIncidentStore)
        val notificationType = DurableSaveNotifications::class.java
        val notificationStarted = notificationType.getDeclaredField("started").apply { isAccessible = true }
        check(!notificationStarted.getBoolean(DurableSaveNotifications)) { "Notification collector already owned" }
        val notificationScope = notificationType.getDeclaredField("scope").apply { isAccessible = true }
            .get(DurableSaveNotifications) as CoroutineScope
        val notificationChildrenBefore = children(notificationScope)
        check(notificationChildrenBefore.isEmpty()) { "Notification scope already owned" }
        val notificationManager = requireNotNull(app.getSystemService(NotificationManager::class.java))
        val priorNotifications = notificationManager.activeNotifications.map { it.tag to it.id }.toSet()
        check(priorNotifications.isEmpty()) { "Notification owner already active" }
        val priorChannels = notificationManager.notificationChannels.map { it.id }.toSet()
        val ownedChannelIds = ReverbNotificationChannel.entries.map { it.id }.toSet()
        check(priorChannels.intersect(ownedChannelIds).isEmpty()) { "Reverb notification channels already owned" }
        val coordinatorField = DurableSaveCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
        check(coordinatorField.get(null) == null) { "Coordinator singleton already owned" }
        check(DurableSaveJobs.state.value.isEmpty()) { "Save jobs already owned" }
        @Suppress("UNCHECKED_CAST")
        val saveReadiness = DurableSaveJobs::class.java.getDeclaredField("mutableReadiness")
            .apply { isAccessible = true }.get(DurableSaveJobs) as MutableStateFlow<DurableSaveJobs.Readiness>
        val priorReadiness = saveReadiness.value
        drainTileWorker()
        val tileState = snapshotTileState()
        check(tileState.getValue("runtimeOwner") == null) { "Quick tile runtime already owned" }
        @Suppress("UNCHECKED_CAST")
        val inputCache = Class.forName("app.smallthingz.reverb.RecorderPreferences")
            .getDeclaredField("inputConfigCache").apply { isAccessible = true }.get(null) as MutableMap<Any, Boolean>
        check(inputCache.isEmpty()) { "Audio capability cache already owned" }
        val keyType = Class.forName("app.smallthingz.reverb.InputConfigKey")
        val ownedProbeKey = keyType.getDeclaredConstructor(Int::class.javaPrimitiveType, AudioSourceMode::class.java,
            InputRouteMode::class.java, ChannelMode::class.java, PcmSampleFormat::class.java).apply {
            isAccessible = true
        }.newInstance(8_000, AudioSourceMode.VOICE_RECOGNITION, InputRouteMode.AUTO, ChannelMode.MONO, PcmSampleFormat.PCM_16)
        check(ProbeAudioRecordShadow.created.get() == 0 && ProbeAudioRecordShadow.released.get() == 0 &&
            ProbeAudioRecordShadow.liveCount() == 0)
        val priorPid = android.os.Process.myPid()
        val processIdentity = RecordingIncidentStore::class.java.getDeclaredField("currentAppProcess\$delegate")
            .apply { isAccessible = true }.get(RecordingIncidentStore) as Lazy<*>
        if (capture) {
            check(priorPid == 0) { "Process shadow PID already owned" }
            check(!processIdentity.isInitialized()) { "Incident process identity already owned" }
            check(android.os.Process.getStartElapsedRealtime() >= 0L)
            check(android.os.SystemClock.elapsedRealtime() >= 0L)
            check(System.currentTimeMillis() > 0L)
            val bootCount = readOptionalRecordingBootCount {
                android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
            }
            check(bootCount >= -1)
        }
        var ownsPid = false
        val pcm = ByteArray(512) { (it * 13 + 1).toByte() }
        val loopRoot = File(noBackup, BUFFER_CACHE_FOLDER_NAME)
        var controller: org.robolectric.android.controller.ServiceController<NoMicService>? = null
        var service: ReverbService? = null
        var thread: HandlerThread? = null
        var destructionCalled = false
        var destructionCompleted = false
        var boundaryReached = false
        var primary: Throwable? = null
        var ownerDrainFailed = false
        var ownedCoordinator: Any? = null
        var packetInput: PacketInput? = null
        var captureEpoch: Any? = null
        var ownedFeedback: FeedbackEvent? = null
        val rejectionStates = mutableListOf<Pair<Boolean, Long>>()
        val retained = mutableListOf<ReverbService.TimelineSnapshot>()
        val pending = mutableListOf<ReverbService.TimelineSnapshot?>()
        fun failCleanup(error: Throwable) {
            if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
        }
        fun failDrain(error: Throwable) { ownerDrainFailed = true; failCleanup(error) }
        try {
            if (legacyWake != null) {
                val editor = prefs.edit()
                if (legacyWake is Boolean) editor.putBoolean("WAKE_LOCK_ENABLED", legacyWake)
                else editor.putString("WAKE_LOCK_ENABLED", legacyWake as String)
                check(editor.commit())
            }
            if (capture) {
                org.robolectric.shadows.ShadowProcess.setPid(47_191)
                ownsPid = true
                check(android.os.Process.myPid() == 47_191)
            }
            if (rejectNotificationQuickSave)
                check(prefs.edit().putBoolean(PrefKey.QUICK_SAVE_ENABLED, false).commit())
            ServiceStateOsShadow.fixtureRoot = appRoot.toPath()
            check(prefs.edit().putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
                .putInt(PrefKey.CAPTURE_BUFFER_SLOT, ReverbService.BufferSlot.LOOPING.storageCode.toInt())
                .putInt(PrefKey.SAMPLE_RATE, 8_000)
                .putInt(PrefKey.CHANNEL_MODE, ChannelMode.MONO.storageCode.toInt())
                .putInt(PrefKey.AUDIO_SOURCE, AudioSourceMode.VOICE_RECOGNITION.storageCode.toInt())
                .putInt(PrefKey.INPUT_ROUTE, InputRouteMode.AUTO.storageCode.toInt())
                .putInt(PrefKey.PCM_SAMPLE_FORMAT, PcmSampleFormat.PCM_16.storageCode.toInt()).commit())
            check(restoreRetentionConfigurationToPreferences(prefs, defaultRetentionConfiguration()))
            PersistentAudioChunkStore(loopRoot).use { seed ->
                seed.configure(RetentionMode.SIZE, 4096, 8_000, 1, PcmSampleFormat.PCM_16)
                if (!capture) assertEquals(pcm.size, seed.append(pcm, 0, pcm.size))
            }
            controller = Robolectric.buildService(NoMicService::class.java)
            val actual = controller.get()
            service = actual
            if (rejectNotificationQuickSave) {
                // Issue the action before Robolectric drains the main-queue recovery completion.
                actual.afterCreate = {
                    assertEquals(true, field(actual, "durableRecoveryQueued"))
                    val intent = Intent(actual, NoMicService::class.java).setAction(ReverbService.ACTION_QUICK_SAVE)
                    assertEquals(Service.START_NOT_STICKY, actual.onStartCommand(intent, 0, 1))
                    assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                        field(actual, "foregroundServiceTypes"))
                    assertNull(ShadowPowerManager.getLatestWakeLock())
                }
            }
            try {
                controller.create()
            } finally {
                actual.afterCreate = null
                ownedCoordinator = coordinatorField.get(null)
            }
            checkNotNull(ownedCoordinator)
            thread = field(actual, "audioThread") as HandlerThread
            val handler = field(actual, "audioHandler") as Handler
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            do {
                fence(handler)
                check(System.nanoTime() < deadline) { "Service initialization did not settle" }
            } while (field(actual, "captureInitializationPending") != false || field(actual, "captureStoresReady") != true)
            (field(actual, "durableSaveAdmissionExecutor") as ExecutorService).submit {}.get(8, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            if (rejectNotificationQuickSave) {
                val event = AppFeedbackCenter.events.value.single()
                ownedFeedback = event
                assertEquals(actual.getString(R.string.notification_save_disabled), event.message)
                assertEquals(FeedbackTone.ERROR, event.tone)
                assertEquals(0, field(actual, "foregroundServiceTypes"))

                AppFeedbackCenter.acknowledge(event.id)
                val clearWorker = field(actual, "bufferClearExecutor") as ExecutorService
                val releaseClearWorker = CountDownLatch(1)
                clearWorker.submit { check(releaseClearWorker.await(8, TimeUnit.SECONDS)) }
                val shadow = Shadow.extract<ShadowService>(actual)
                var clearId: Long? = null
                try {
                    clearId = requireNotNull(actual.startClearBuffer())
                    val clearOwner = requireNotNull(field(actual, "activeBufferClearOperation"))
                    val clearNotification = requireNotNull(shadow.lastForegroundNotification)
                    assertEquals(actual.getString(R.string.clearing_buffer),
                        clearNotification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())

                    val intent = Intent(actual, NoMicService::class.java).setAction(ReverbService.ACTION_QUICK_SAVE)
                    assertEquals(Service.START_NOT_STICKY, actual.onStartCommand(intent, 0, 2))
                    ShadowLooper.idleMainLooper()

                    ownedFeedback = AppFeedbackCenter.events.value.single()
                    assertEquals(event.message, ownedFeedback.message)
                    assertEquals(FeedbackTone.ERROR, ownedFeedback.tone)
                    assertSame(clearOwner, field(actual, "activeBufferClearOperation"))
                    assertSame(clearNotification, shadow.lastForegroundNotification)

                    AppFeedbackCenter.acknowledge(ownedFeedback.id)
                    // A valid tile request acquires Saving even though the notification action is disabled.
                    // The empty One-shot buffer then settles the admitted Save without writing an output.
                    intent.putExtra(ReverbService.EXTRA_QUICK_TILE_BUFFER_SLOT,
                        ReverbService.BufferSlot.ONE_SHOT.storageCode.toInt())
                        .putExtra(ReverbService.EXTRA_QUICK_SAVE_SECONDS, 1)
                    assertEquals(Service.START_NOT_STICKY, actual.onStartCommand(intent, 0, 3))
                    assertEquals(actual.getString(R.string.saving),
                        shadow.lastForegroundNotification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
                    assertNull(ShadowPowerManager.getLatestWakeLock())

                    fence(handler)
                    (field(actual, "durableSaveAdmissionExecutor") as ExecutorService).submit {}.get(8, TimeUnit.SECONDS)
                    ShadowLooper.idleMainLooper()

                    ownedFeedback = AppFeedbackCenter.events.value.single()
                    assertEquals(actual.getString(R.string.nothing_to_export), ownedFeedback.message)
                    assertEquals(FeedbackTone.ERROR, ownedFeedback.tone)
                    assertSame(clearOwner, field(actual, "activeBufferClearOperation"))
                    assertEquals(actual.getString(R.string.clearing_buffer),
                        shadow.lastForegroundNotification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
                } finally {
                    clearId?.let { actual.cancelBufferClear(it) }
                    releaseClearWorker.countDown()
                    clearWorker.submit {}.get(8, TimeUnit.SECONDS)
                    fence(handler)
                    ShadowLooper.idleMainLooper()
                }
                assertNull(field(actual, "activeBufferClearOperation"))
                assertEquals(0, field(actual, "foregroundServiceTypes"))
            }
            assertEquals(0, (ownedCoordinator as DurableSaveCoordinator).pendingCount())
            assertTrue(DurableSaveJobs.state.value.isEmpty())
            assertNull(field(actual, "audioRecord"))
            assertEquals(0, actual.inputAttempts.get())
            assertNull(ShadowPowerManager.getLatestWakeLock())
            assertEquals(false, getRecorderPreferences(actual).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true))
            if (capture) {
                check(!SilenceGatePreferences.read(actual).config.enabled)
                val first = ByteArray(512) { (it * 31 + 9).toByte() }
                val rejected = ByteArray(512) { (it * 7 + 71).toByte() }
                val packet = PacketInput(first, rejected)
                packetInput = packet
                actual.syntheticInput = packet
                exercisePublicCapture(actual, handler, retained, packet, first)
                captureEpoch = incidentEpochField.get(RecordingIncidentStore)
                check(captureEpoch !== priorIncidentEpoch) { "Capture session was never armed" }
                assertEquals(1, actual.inputAttempts.get())
                destructionCalled = true
                controller!!.destroy()
                destructionCompleted = true
                ShadowLooper.idleMainLooper()
                thread!!.join(8_000)
                check(!thread!!.isAlive)
                ShadowLooper.idleMainLooper()
                joinNewChildren(snapshotCleanupScope, snapshotChildrenBefore)
                assertEquals(0, leaseCount(actual))
                assertEquals(2, packet.reads.get())
                assertEquals(1, packet.releases.get())
                assertFalse(RecordingIncidentStore.beginKnownCaptureStop(actual).armed)
                PersistentAudioChunkStore(loopRoot).use { reopened ->
                    reopened.configure(RetentionMode.SIZE, 4096, 8_000, 1, PcmSampleFormat.PCM_16)
                    requireNotNull(reopened.acquireRange(0.0, reopened.durationSeconds())).use {
                        assertArrayEquals(first, readStoreTestPcm16Mono8Khz(it))
                    }
                }
            } else if (rejectedStartThrows != null) {
                exerciseRejectedStart(actual, handler, retained, pcm, rejectedStartThrows, rejectionStates) { ownedFeedback = it }
                // Only after the committed-intent and paused-runtime assertions, release this fixture's intent.
                assertTrue(actual.disableListening().accepted)
                fence(handler)
                assertFalse(getRecorderPreferences(actual).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true))
                destructionCalled = true
                controller!!.destroy()
                destructionCompleted = true
                ShadowLooper.idleMainLooper()
                thread!!.join(8_000)
                check(!thread!!.isAlive)
                ShadowLooper.idleMainLooper()
                joinNewChildren(snapshotCleanupScope, snapshotChildrenBefore)
                assertEquals(0, leaseCount(actual))
                assertEquals(0, actual.inputAttempts.get())
                PersistentAudioChunkStore(loopRoot).use { reopened ->
                    reopened.configure(RetentionMode.SIZE, 4096, 8_000, 1, PcmSampleFormat.PCM_16)
                    requireNotNull(reopened.acquireRange(0.0, reopened.durationSeconds())).use {
                        assertArrayEquals(pcm, readStoreTestPcm16Mono8Khz(it))
                    }
                }
            } else {
                val healthy = mutableListOf<ReverbService.TimelineSnapshot?>()
                actual.acquireTimelineSnapshot(ReverbService.BufferSlot.LOOPING) { snapshot ->
                    snapshot?.let(retained::add); healthy += snapshot
                }
                awaitLeaseCount(actual, handler, 1)
                ShadowLooper.idleMainLooper()
                val live = requireNotNull(healthy.single())
                requireNotNull(live.acquireRange(0.0, live.durationSeconds)).use {
                    assertArrayEquals(pcm, readStoreTestPcm16Mono8Khz(it))
                }
                live.close(); retained.remove(live)
                val shadow = Shadow.extract<ShadowService>(actual)
                val intent = Intent(actual, ReverbService::class.java).setAction(ReverbService.ACTION_QUICK_TILE_COMMAND)
                    .putExtra(ReverbService.EXTRA_QUICK_TILE_BUFFER_SLOT, -123)
                assertEquals(Service.START_NOT_STICKY, actual.onStartCommand(intent, 0, 11))
                fence(handler)
                assertNull(field(actual, "audioRecord"))
                assertEquals(11, shadow.stopSelfResultId)
                actual.acquireTimelineSnapshot(ReverbService.BufferSlot.LOOPING) { snapshot ->
                    snapshot?.let(retained::add); pending += snapshot
                }
                awaitLeaseCount(actual, handler, 1)
                assertTrue(pending.isEmpty())
                assertEquals(1, leaseCount(actual))
                boundaryReached = true
                destructionCalled = true
                controller!!.destroy()
                destructionCompleted = true
                ShadowLooper.idleMainLooper()
                assertEquals(listOf(null), pending)
                thread!!.join(8_000)
                check(!thread!!.isAlive) { "Service audio thread did not terminate" }
                ShadowLooper.idleMainLooper()
                // Wait for the actual asynchronously released snapshot lease before reopening.
                joinNewChildren(snapshotCleanupScope, snapshotChildrenBefore)
                assertEquals(0, leaseCount(actual))
                assertNull(field(actual, "audioRecord"))
                assertEquals(0, actual.inputAttempts.get())
                assertFalse(getRecorderPreferences(actual).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true))
                PersistentAudioChunkStore(loopRoot).use { reopened ->
                    reopened.configure(RetentionMode.SIZE, 4096, 8_000, 1, PcmSampleFormat.PCM_16)
                    requireNotNull(reopened.acquireRange(0.0, reopened.durationSeconds())).use {
                        assertArrayEquals(pcm, readStoreTestPcm16Mono8Khz(it))
                    }
                }
            }
        } catch (error: Throwable) {
            if (destructionCalled && !destructionCompleted) failDrain(error) else primary = error
        }
        finally {
            packetInput?.allowSecond?.countDown()
            if ((capture || rejectedStartThrows != null) && service != null) try {
                if (getRecorderPreferences(service!!).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)) {
                    check(service!!.disableListening().accepted)
                }
            } catch (error: Throwable) { failDrain(error) }
            if (service != null && thread == null) try {
                thread = field(service!!, "audioThread") as HandlerThread?
            } catch (error: Throwable) { failDrain(error) }
            if (service != null && ownedCoordinator == null) try {
                ownedCoordinator = coordinatorField.get(null)
            } catch (error: Throwable) { failDrain(error) }
            retained.toList().forEach { snapshot -> try {
                snapshot.close(); retained.remove(snapshot)
            } catch (error: Throwable) { failDrain(error) } }
            if (!destructionCalled && controller != null) try {
                destructionCalled = true; controller!!.destroy(); destructionCompleted = true; ShadowLooper.idleMainLooper()
            } catch (error: Throwable) { failDrain(error) }
            if (thread != null) try { thread!!.join(8_000); check(!thread!!.isAlive) }
            catch (error: Throwable) { failDrain(error) }
            if (!ownerDrainFailed) try { ShadowLooper.idleMainLooper() } catch (error: Throwable) { failDrain(error) }
            try { joinNewChildren(snapshotCleanupScope, snapshotChildrenBefore) } catch (error: Throwable) { failDrain(error) }
            try {
                joinNewChildren(incidentScope, incidentChildrenBefore)
                check(incidentRetriesEmpty()) { "Fixture left incident retries" }
                check(RecordingIncidentStore.historyRevision.value == incidentRevision) { "Unexpected incident history mutation" }
            } catch (error: Throwable) { failDrain(error) }
            try { assertNull(ShadowPowerManager.getLatestWakeLock()) }
            catch (error: Throwable) { failCleanup(error) }
            service?.let { actual ->
                listOf("durableSaveAdmissionExecutor", "exportWorkExecutor", "durabilitySyncExecutor", "bufferClearExecutor")
                    .forEach { name -> try {
                        val owner = field(actual, name) as ExecutorService?
                        if (owner != null) check(owner.isShutdown && owner.awaitTermination(8, TimeUnit.SECONDS)) {
                            "$name did not terminate"
                        }
                    } catch (error: Throwable) { failDrain(error) } }
            }
            if (!ownerDrainFailed) try {
                ShadowLooper.idleMainLooper()
                joinNewChildren(snapshotCleanupScope, snapshotChildrenBefore)
                joinNewChildren(incidentScope, incidentChildrenBefore)
                check(incidentRetriesEmpty())
                check(RecordingIncidentStore.historyRevision.value == incidentRevision)
            } catch (error: Throwable) { failDrain(error) }
            try { drainTileWorker() } catch (error: Throwable) { failDrain(error) }
            if (!ownerDrainFailed) try {
                ShadowLooper.idleMainLooper()
                drainTileWorker()
                ShadowLooper.idleMainLooper()
            } catch (error: Throwable) { failDrain(error) }
            var collectorJoined = false
            try {
                val created = children(notificationScope) - notificationChildrenBefore
                check(created.size <= 1)
                created.forEach { child -> runBlocking { withTimeout(8_000) { child.cancelAndJoin() } } }
                collectorJoined = true
            } catch (error: Throwable) { failDrain(error) }
            if (!ownerDrainFailed && collectorJoined && boundaryReached) try {
                ShadowLooper.idleMainLooper()
                assertEquals(listOf(null), pending)
                assertEquals(0, (service as NoMicService).inputAttempts.get())
                assertEquals(0, ProbeAudioRecordShadow.created.get())
                assertEquals(0, ProbeAudioRecordShadow.liveCount())
                assertNull(field(service!!, "audioRecord"))
                assertFalse(getRecorderPreferences(service!!).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true))
            } catch (error: Throwable) { failCleanup(error) }
            if (!ownerDrainFailed && collectorJoined && capture && packetInput != null) try {
                ShadowLooper.idleMainLooper()
                assertEquals(2, packetInput!!.reads.get())
                assertEquals(1, packetInput!!.stops.get())
                assertEquals(1, packetInput!!.releases.get())
                assertFalse(getRecorderPreferences(service!!).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true))
                check(captureEpoch != null && incidentEpochField.get(RecordingIncidentStore) === captureEpoch)
                assertFalse(RecordingIncidentStore.beginKnownCaptureStop(service!!).armed)
                assertTrue(listOf("recording-incidents.bin", "recording-incidents.bin.bak", "recording-incidents.bin.new")
                    .all { !File(noBackup, it).exists() })
                assertTrue(ProbeAudioRecordShadow.created.get() > 0)
                assertEquals(ProbeAudioRecordShadow.created.get(), ProbeAudioRecordShadow.released.get())
                assertEquals(0, ProbeAudioRecordShadow.liveCount())
            } catch (error: Throwable) { failCleanup(error) }
            if (!ownerDrainFailed && collectorJoined && rejectedStartThrows != null && ownedFeedback != null) try {
                ShadowLooper.idleMainLooper()
                assertEquals(listOf(ownedFeedback), AppFeedbackCenter.events.value)
                assertEquals(listOf(false to pcm.size.toLong()), rejectionStates)
                assertEquals(0, (service as NoMicService).inputAttempts.get())
                assertNull(field(service!!, "audioRecord"))
                assertEquals(0, ProbeAudioRecordShadow.created.get())
                assertEquals(0, ProbeAudioRecordShadow.liveCount())
                assertFalse(getRecorderPreferences(service!!).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true))
                assertTrue(listOf("recording-incidents.bin", "recording-incidents.bin.bak", "recording-incidents.bin.new")
                    .all { !File(noBackup, it).exists() })
                AppFeedbackCenter.acknowledge(requireNotNull(ownedFeedback).id)
                assertTrue(AppFeedbackCenter.events.value.isEmpty())
            } catch (error: Throwable) { failCleanup(error) }
            if (rejectNotificationQuickSave) {
                ownedFeedback?.let { AppFeedbackCenter.acknowledge(it.id) }
            }
            if (!ownerDrainFailed) try {
                check(inputCache.keys.all { it == ownedProbeKey }) { "Unexpected audio capability owner" }
                inputCache[ownedProbeKey]?.let { check(it && inputCache.remove(ownedProbeKey) == true) }
                check(ProbeAudioRecordShadow.liveCount() == 0) { "Capability probe still owned" }
                ProbeAudioRecordShadow.created.set(0)
                ProbeAudioRecordShadow.released.set(0)
            } catch (error: Throwable) { failDrain(error) }
            if (!ownerDrainFailed) {
                retained.toList().forEach { snapshot -> try {
                    snapshot.close(); retained.remove(snapshot)
                } catch (error: Throwable) { failDrain(error) } }
            }
            if (!ownerDrainFailed) service?.let { actual -> try { restoreTileState(tileState, field(actual, "quickTileStateOwner")) }
            catch (error: Throwable) { failDrain(error) } }
            if (!ownerDrainFailed && collectorJoined) try {
                notificationStarted.setBoolean(DurableSaveNotifications, false)
                saveReadiness.value = priorReadiness
                check(notificationManager.activeNotifications.map { it.tag to it.id }.toSet() == priorNotifications)
                notificationManager.notificationChannels.map { it.id }
                    .filter { it in ownedChannelIds && it !in priorChannels }
                    .forEach(notificationManager::deleteNotificationChannel)
            } catch (error: Throwable) { failDrain(error) }
            if (!ownerDrainFailed && ownedCoordinator != null) try {
                check(coordinatorField.get(null) === ownedCoordinator)
                coordinatorField.set(null, null)
            } catch (error: Throwable) { failDrain(error) }
            try { check(DurableSaveJobs.state.value.isEmpty()) { "Fixture left save job ownership" } }
            catch (error: Throwable) { failCleanup(error) }
            if (!ownerDrainFailed) try { JournalHostIoShadow.finishFixture()?.let(::failDrain) } catch (error: Throwable) { failDrain(error) }
            if (!ownerDrainFailed && ownsPid) try {
                check(!processIdentity.isInitialized()) { "Fixture initialized process identity; retaining PID owner" }
                check(android.os.Process.myPid() == 47_191) { "Process shadow PID owner changed" }
                org.robolectric.shadows.ShadowProcess.setPid(priorPid)
                check(android.os.Process.myPid() == priorPid)
                ownsPid = false
            } catch (error: Throwable) { failDrain(error) }
            if (primary == null && !ownerDrainFailed) try {
                val allowed = setOf(BUFFER_CACHE_FOLDER_NAME, ONE_SHOT_BUFFER_CACHE_FOLDER_NAME,
                    LEGACY_BUFFER_CACHE_FOLDER_NAME, "durable-saves", "retention-config.v1",
                    "recording-session.bin", "app-process.bin", "recording-incident-pending.bin", "recording-incidents.bin")
                val children = noBackup.listFiles().orEmpty()
                check(children.all { it.name in allowed && !java.nio.file.Files.isSymbolicLink(it.toPath()) }) {
                    "Unexpected no-backup owner; preserving fixture"
                }
                check(children.all { it.deleteRecursively() })
                check(prefs.edit().clear().commit())
            } catch (error: Throwable) { failCleanup(error) }
            if (!ownerDrainFailed) ServiceStateOsShadow.fixtureRoot = null
        }
        primary?.let { throw it }
    }

    private fun exerciseRejectedStart(actual: NoMicService, handler: Handler,
        retained: MutableList<ReverbService.TimelineSnapshot>, pcm: ByteArray, thrown: Boolean,
        states: MutableList<Pair<Boolean, Long>>, ownFeedback: (FeedbackEvent) -> Unit) {
        val wrapperField = android.content.ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
        val oldBase = wrapperField.get(actual) as android.content.Context
        val starts = mutableListOf<Intent>()
        val denied = SecurityException("fixture foreground denial")
        wrapperField.set(actual, object : android.content.ContextWrapper(oldBase) {
            override fun startForegroundService(intent: Intent): android.content.ComponentName? {
                starts += Intent(intent)
                if (thrown) throw denied
                return null
            }
        })
        var primary: Throwable? = null
        try {
            val result = actual.enableListening(ReverbService.BufferSlot.LOOPING)
            val event = AppFeedbackCenter.events.value.single()
            ownFeedback(event)
            assertTrue(result.accepted)
            assertTrue(getRecorderPreferences(actual).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false))
            assertEquals(1, starts.size)
            assertEquals(android.content.ComponentName(actual, NoMicService::class.java), starts.single().component)
            assertEquals(FeedbackTone.ERROR, event.tone)
            assertTrue(event.message.contains(actual.getString(R.string.audio_input_init_failed)))
            assertTrue(event.message.contains(if (thrown) "fixture foreground denial" else "Service start returned no component"))
            assertNull(Shadow.extract<ShadowService>(actual).lastForegroundNotification)
            assertNull(ShadowPowerManager.getLatestWakeLock())
            assertEquals(0, actual.inputAttempts.get())
            assertEquals(0, ProbeAudioRecordShadow.created.get())
            assertNull(field(actual, "audioRecord"))
            fence(handler)
            assertTrue(listOf("recording-incidents.bin", "recording-incidents.bin.bak", "recording-incidents.bin.new")
                .all { !File(actual.applicationContext.noBackupFilesDir, it).exists() })
            actual.getState(object : ReverbService.StateCallback {
                override fun state(commandGeneration: Long, listeningEnabled: Boolean,
                    activeBufferSlot: ReverbService.BufferSlot?, oneShotSeconds: Float, oneShotBytes: Long,
                    loopingSeconds: Float, loopingBytes: Long, oneShotIsEnabled: Boolean, oneShotIsFull: Boolean,
                    loopingIsEnabled: Boolean, retentionMode: RetentionMode?, exporting: Boolean) {
                    states += listeningEnabled to loopingBytes
                }
            })
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (states.isEmpty()) {
                fence(handler); ShadowLooper.idleMainLooper()
                check(System.nanoTime() < deadline) { "State delivery did not finish" }
            }
            assertEquals(listOf(false to pcm.size.toLong()), states)
            assertFalse(RecordingQuickTileStateCache.readNonBlocking().listening)
            val snapshots = mutableListOf<ReverbService.TimelineSnapshot?>()
            actual.acquireTimelineSnapshot(ReverbService.BufferSlot.LOOPING) { snapshot ->
                snapshot?.let(retained::add); snapshots += snapshot
            }
            awaitLeaseCount(actual, handler, 1)
            ShadowLooper.idleMainLooper()
            val snapshot = requireNotNull(snapshots.single())
            requireNotNull(snapshot.acquireRange(0.0, snapshot.durationSeconds)).use {
                assertArrayEquals(pcm, readStoreTestPcm16Mono8Khz(it))
            }
            snapshot.close(); retained.remove(snapshot)
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            try { wrapperField.set(actual, oldBase) } catch (error: Throwable) {
                if (primary == null) throw error
                if (primary !== error) primary!!.addSuppressed(error)
            }
        }
    }

    private class PacketInput(private val first: ByteArray, private val rejected: ByteArray) : CaptureAudioInput {
        val secondEntered = CountDownLatch(1)
        val allowSecond = CountDownLatch(1)
        val released = CountDownLatch(1)
        val reads = AtomicInteger()
        val stops = AtomicInteger()
        val releases = AtomicInteger()
        @Volatile private var recording = false
        override val state: Int get() = android.media.AudioRecord.STATE_INITIALIZED
        override val recordingState: Int get() = if (recording) android.media.AudioRecord.RECORDSTATE_RECORDING
            else android.media.AudioRecord.RECORDSTATE_STOPPED
        override fun startRecording() { check(!recording); recording = true }
        override fun read(buffer: java.nio.ByteBuffer, sizeInBytes: Int, readMode: Int): Int {
            val number = reads.incrementAndGet()
            val bytes = when (number) {
                1 -> first
                2 -> { secondEntered.countDown(); check(allowSecond.await(8, TimeUnit.SECONDS)); rejected }
                else -> throw AssertionError("Input read after Stop")
            }
            check(bytes.size <= sizeInBytes)
            buffer.put(bytes)
            return bytes.size
        }
        override fun stop() { check(stops.incrementAndGet() == 1); recording = false; allowSecond.countDown() }
        override fun release() { check(releases.incrementAndGet() == 1); released.countDown() }
    }

    private fun exercisePublicCapture(actual: NoMicService, handler: Handler,
        retained: MutableList<ReverbService.TimelineSnapshot>,
        packet: PacketInput, first: ByteArray) {
        val wrapperField = android.content.ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
        val oldBase = wrapperField.get(actual) as android.content.Context
        val starts = mutableListOf<Intent>()
        wrapperField.set(actual, object : android.content.ContextWrapper(oldBase) {
            override fun checkSelfPermission(permission: String): Int =
                if (permission == android.Manifest.permission.RECORD_AUDIO) android.content.pm.PackageManager.PERMISSION_GRANTED
                else super.checkSelfPermission(permission)
            override fun startForegroundService(intent: Intent): android.content.ComponentName {
                starts += Intent(intent)
                return android.content.ComponentName(actual, NoMicService::class.java)
            }
        })
        var failure: Throwable? = null
        try {
            assertTrue(actual.enableListening(ReverbService.BufferSlot.LOOPING).accepted)
            assertNull(ShadowPowerManager.getLatestWakeLock())
            assertTrue(getRecorderPreferences(actual).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false))
            assertEquals(1, starts.size)
            assertEquals(android.content.ComponentName(actual, NoMicService::class.java), starts.single().component)
            assertEquals(Service.START_STICKY, actual.onStartCommand(starts.single(), 0, 37))
            assertNotNull(Shadow.extract<ShadowService>(actual).lastForegroundNotification)
            assertTrue(packet.secondEntered.await(8, TimeUnit.SECONDS))
            assertNull(ShadowPowerManager.getLatestWakeLock())
            assertEquals(8_000, field(actual, "sampleRate"))
            assertEquals(2, packet.reads.get())
            val armed = RecordingIncidentStore.beginKnownCaptureStop(actual)
            assertTrue(armed.armed)
            assertEquals(47_191, requireNotNull(armed.marker).pid)
            assertEquals(android.os.Process.getStartElapsedRealtime(), armed.marker!!.processStartElapsedRealtimeMillis)
            assertTrue(actual.disableListening().accepted)
            assertFalse(getRecorderPreferences(actual).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true))
            packet.allowSecond.countDown()
            assertTrue(packet.released.await(8, TimeUnit.SECONDS))
            assertEquals(1, packet.stops.get())
            assertEquals(1, packet.releases.get())
            fence(handler)
            assertFalse(RecordingIncidentStore.beginKnownCaptureStop(actual).armed)
            assertNull(ShadowPowerManager.getLatestWakeLock())
            val incidentRoot = actual.applicationContext.noBackupFilesDir
            assertTrue(listOf("recording-incidents.bin", "recording-incidents.bin.bak", "recording-incidents.bin.new")
                .all { !File(incidentRoot, it).exists() })
            val snapshots = mutableListOf<ReverbService.TimelineSnapshot?>()
            actual.acquireTimelineSnapshot(ReverbService.BufferSlot.LOOPING) { snapshot ->
                snapshot?.let(retained::add); snapshots += snapshot
            }
            awaitLeaseCount(actual, handler, 1)
            ShadowLooper.idleMainLooper()
            val snapshot = requireNotNull(snapshots.single())
            requireNotNull(snapshot.acquireRange(0.0, snapshot.durationSeconds)).use {
                assertArrayEquals(first, readStoreTestPcm16Mono8Khz(it))
            }
            snapshot.close(); retained.remove(snapshot)
            assertEquals(2, packet.reads.get())
            assertEquals(1, starts.size)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            packet.allowSecond.countDown()
            try { wrapperField.set(actual, oldBase) } catch (error: Throwable) {
                val firstFailure = failure
                if (firstFailure == null) throw error
                if (firstFailure !== error) firstFailure.addSuppressed(error)
            }
        }
    }

    private fun field(owner: Any, name: String): Any? = ReverbService::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }.get(owner)

    private fun leaseCount(service: ReverbService): Int {
        val store = field(service, "loopingAudioChunkStore") as PersistentAudioChunkStore
        return synchronized(store) {
            PersistentAudioChunkStore::class.java.getDeclaredField("activeRangeLeases")
                .apply { isAccessible = true }.getInt(store)
        }
    }

    private fun fence(handler: Handler) {
        val task = FutureTask { Unit }
        check(handler.post(task))
        task.get(8, TimeUnit.SECONDS)
    }

    private fun awaitLeaseCount(service: ReverbService, handler: Handler, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        do {
            fence(handler)
            check(System.nanoTime() < deadline) { "Service lease did not reach $expected" }
        } while (leaseCount(service) != expected)
    }

    private fun drainTileWorker() {
        val worker = RecordingQuickTileStateCache::class.java.getDeclaredField("persistedReadExecutor")
            .apply { isAccessible = true }.get(RecordingQuickTileStateCache) as ExecutorService
        worker.submit {}.get(8, TimeUnit.SECONDS)
    }

    private val tileFields = listOf("cachedSnapshot", "stateGeneration", "hydrationGeneration",
        "runtimeAuthoritative", "runtimeOwner", "runtimeOwnerStopped", "oneShotFullRuntimeGeneration",
        "latestRuntimeOneShotFull", "completedClearGeneration", "oneShotClearGeneration")

    private fun tileField(name: String) = RecordingQuickTileStateCache::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun <T> withTileLocks(block: () -> T): T {
        val fullLock = tileField("oneShotFullPreferenceLock").get(RecordingQuickTileStateCache)
        val stateLock = tileField("stateLock").get(RecordingQuickTileStateCache)
        return synchronized(fullLock) { synchronized(stateLock) { block() } }
    }

    private fun snapshotTileState(): Map<String, Any?> = withTileLocks {
        val pending = tileField("pendingClears").get(RecordingQuickTileStateCache) as Map<*, *>
        check(pending.isEmpty()) { "Quick tile clear already owned" }
        tileFields.associateWith { tileField(it).get(RecordingQuickTileStateCache) }
    }

    private fun restoreTileState(saved: Map<String, Any?>, owner: Any?) = withTileLocks {
        check(tileField("runtimeOwner").get(RecordingQuickTileStateCache) === owner)
        val pending = tileField("pendingClears").get(RecordingQuickTileStateCache) as Map<*, *>
        check(pending.isEmpty())
        saved.forEach { (name, value) -> tileField(name).set(RecordingQuickTileStateCache, value) }
    }

    private fun children(scope: CoroutineScope): Set<Job> =
        requireNotNull(scope.coroutineContext[Job]).children.toSet()

    private fun incidentRetriesEmpty(): Boolean = listOf("pendingServiceStopIncidentRetries", "pendingCaptureInterruptionRetries")
        .all { name ->
            val retries = RecordingIncidentStore::class.java.getDeclaredField(name).apply { isAccessible = true }
                .get(RecordingIncidentStore)
            val pending = retries.javaClass.getDeclaredField("pending").apply { isAccessible = true }.get(retries) as List<*>
            pending.isEmpty()
        }

    private fun joinNewChildren(scope: CoroutineScope, prior: Set<Job>) {
        val created = children(scope) - prior
        created.forEach { child -> runBlocking { withTimeout(8_000) { child.join() } } }
    }
}

/** Test-only platform capability probe; never starts or reads native microphone input. */
@Implements(android.media.AudioRecord::class)
class ProbeAudioRecordShadow {
    @RealObject private lateinit var record: android.media.AudioRecord
    companion object {
        val created = AtomicInteger()
        val released = AtomicInteger()
        private val live = java.util.IdentityHashMap<android.media.AudioRecord, Boolean>()
        fun register(record: android.media.AudioRecord): Unit = synchronized(live) {
            check(live.put(record, true) == null)
            created.incrementAndGet()
            Unit
        }
        fun retire(record: android.media.AudioRecord) = synchronized(live) {
            check(live.remove(record) == true) { "Probe released twice or not owned" }
            released.incrementAndGet()
        }
        fun liveCount(): Int = synchronized(live) { live.size }
        @JvmStatic @Implementation fun getMinBufferSize(rate: Int, channels: Int, format: Int): Int = 16_384
    }
    @Implementation fun getState(): Int = android.media.AudioRecord.STATE_INITIALIZED
    @Implementation fun release() { retire(record) }
    @Implementation fun startRecording() { throw AssertionError("Capability probe attempted microphone start") }
}

@Implements(android.media.AudioRecord.Builder::class)
class ProbeAudioRecordBuilderShadow {
    @Implementation fun build(): android.media.AudioRecord {
        return Shadow.newInstanceOf(android.media.AudioRecord::class.java).also(ProbeAudioRecordShadow::register)
    }
}
