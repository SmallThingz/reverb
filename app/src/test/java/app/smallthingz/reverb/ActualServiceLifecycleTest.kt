package app.smallthingz.reverb

import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowService
import org.robolectric.shadow.api.Shadow

/** Actual stopped-Service lifecycle, terminal snapshot delivery, and owned PCM recovery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [JournalHostIoShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ActualServiceLifecycleTest {
    class NoMicService : ReverbService() {
        val inputAttempts = AtomicInteger()
        override fun createAudioRecord(): CaptureAudioInput? {
            inputAttempts.incrementAndGet()
            throw AssertionError("Unexpected microphone admission")
        }
    }

    @Test fun stoppedInvalidTileStartAndDestroyRejectQueuedSnapshotButPreservePcm() {
        val app = RuntimeEnvironment.getApplication()
        val noBackup = app.noBackupFilesDir
        val appRoot = requireNotNull(app.filesDir.parentFile).canonicalFile
        check(noBackup.canonicalFile.parentFile == appRoot)
        check(noBackup.listFiles().orEmpty().isEmpty()) { "No-backup root already owned" }
        val prefs = getRecorderPreferences(app)
        check(prefs.all.isEmpty()) { "Recorder preferences already owned" }
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
        val retained = mutableListOf<ReverbService.TimelineSnapshot>()
        val pending = mutableListOf<ReverbService.TimelineSnapshot?>()
        fun failCleanup(error: Throwable) {
            if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
        }
        fun failDrain(error: Throwable) { ownerDrainFailed = true; failCleanup(error) }
        try {
            ServiceStateOsShadow.fixtureRoot = appRoot.toPath()
            check(prefs.edit().putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
                .putInt(PrefKey.CAPTURE_BUFFER_SLOT, ReverbService.BufferSlot.LOOPING.storageCode.toInt())
                .putInt(PrefKey.SAMPLE_RATE, 8_000)
                .putInt(PrefKey.CHANNEL_MODE, ChannelMode.MONO.storageCode.toInt())
                .putInt(PrefKey.PCM_SAMPLE_FORMAT, PcmSampleFormat.PCM_16.storageCode.toInt()).commit())
            check(restoreRetentionConfigurationToPreferences(prefs, defaultRetentionConfiguration()))
            PersistentAudioChunkStore(loopRoot).use { seed ->
                seed.configure(RetentionMode.SIZE, 4096, 8_000, 1, PcmSampleFormat.PCM_16)
                assertEquals(pcm.size, seed.append(pcm, 0, pcm.size))
            }
            controller = Robolectric.buildService(NoMicService::class.java)
            service = controller!!.get()
            controller!!.create()
            val actual = controller!!.get()
            ownedCoordinator = coordinatorField.get(null)
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
            assertEquals(0, (ownedCoordinator as DurableSaveCoordinator).pendingCount())
            assertTrue(DurableSaveJobs.state.value.isEmpty())
            assertNull(field(actual, "audioRecord"))
            assertEquals(0, actual.inputAttempts.get())
            assertEquals(false, getRecorderPreferences(actual).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true))
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
        } catch (error: Throwable) {
            if (destructionCalled && !destructionCompleted) failDrain(error) else primary = error
        }
        finally {
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
                assertNull(field(service!!, "audioRecord"))
                assertFalse(getRecorderPreferences(service!!).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true))
            } catch (error: Throwable) { failCleanup(error) }
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
            if (primary == null && !ownerDrainFailed) try {
                val allowed = setOf(BUFFER_CACHE_FOLDER_NAME, ONE_SHOT_BUFFER_CACHE_FOLDER_NAME,
                    LEGACY_BUFFER_CACHE_FOLDER_NAME, "durable-saves", "retention-config.v1")
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
