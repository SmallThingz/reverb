package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.Process
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [JournalHostIoShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class DurableSaveStartupRecoveryOrderingTest {
    @Test fun delayedApplicationScanPreservesServiceFailureAndLibraryRetry() =
        withQueuedSaves { app, coordinator, saves, previousThreads ->
            val admitted = saves.single()
            // API 35 startup takes this lock before it scans Saves. Let the recovery
            // consumer publish its real journal-backed failure while startup is blocked.
            val (startup, failureReceipt) = withRetentionPersistenceLock {
                app.onCreate()
                val startup = startupThreads(previousThreads).single { it.name == "reverb-save-recovery" }
                val recovery = coordinator.recover().single { it.id == admitted.id }
                assertEquals(SavePhase.RECOVERING, pending(recovery.id).phase)
                val interruption = DurableSaveLifetimeRejectedException(
                    DurableSaveLifetimeRejection.SERVICE_STOPPING,
                )
                var receipt: DurableSavePublicationReceipt? = null
                val caught = try {
                    coordinator.materialize(recovery.id, onFailurePublished = { receipt = it }) {
                        throw interruption
                    }
                    null
                } catch (error: DurableSaveLifetimeRejectedException) {
                    error
                }
                assertSame(interruption, caught)
                assertEquals(SavePhase.FAILED, pending(recovery.id).phase)
                startup to requireNotNull(receipt)
            }

            awaitStartup(startup)
            val current = pending(admitted.id)
            val readiness = DurableSaveJobs.readiness.value
            assertTrue(readiness.loaded)
            assertEquals(SavePhase.FAILED, current.phase)
            assertTrue(retryDurableSavesForLibraryRefresh(readiness, listOf(current), userInitiated = true))
            // The Service's posted failure has yet to claim delivery at this point.
            assertTrue(DurableSaveJobs.claimFailureDelivery(failureReceipt))
            assertFalse(DurableSaveJobs.claimFailureDelivery(failureReceipt))
        }

    @Test fun startupPublishesUnownedSaveAsQueuedAndAllowsManualRetry() =
        withQueuedSaves { app, coordinator, saves, _ ->
            val admitted = saves.single()
            // The disk survives a cold start without the admission's process-local row.
            DurableSaveJobs.remove(admitted.id)
            coordinator.hydrateStartup()

            val queued = pending(admitted.id)
            val readiness = DurableSaveJobs.readiness.value
            assertTrue(readiness.loaded)
            assertEquals(SavePhase.QUEUED, queued.phase)
            assertTrue(retryDurableSavesForLibraryRefresh(readiness, listOf(queued), userInitiated = true))
            assertFalse(retryDurableSavesForLibraryRefresh(readiness, listOf(queued), userInitiated = false))

            val recovery = coordinator.recover().single { it.id == admitted.id }
            val recovering = pending(recovery.id)
            assertEquals(SavePhase.RECOVERING, recovering.phase)
            coordinator.hydrateStartup()
            assertSame(recovering, pending(recovery.id))
            val receipt = requireNotNull(recovery.publication)
            requireNotNull(DurableSaveJobs.failIfCurrent(receipt, "current Service recovery"))

            val journal = DurableSaveJournal(durableSaveJournalRoot(app))
            val error = "previous attempt failed"
            journal.write(journal.read(recovery.id).copy(error = error))
            DurableSaveJobs.remove(recovery.id)
            coordinator.hydrateStartup()
            val failed = pending(recovery.id)
            assertEquals(SavePhase.FAILED, failed.phase)
            assertEquals(error, failed.error)
        }

    @Test fun ordinaryQueuedAdmissionDoesNotRequestManualRecovery() =
        withQueuedSaves { _, coordinator, saves, _ ->
            // Establish successful readiness without converting the live admission row.
            coordinator.hydrateStartup()
            val queued = pending(saves.single().id)
            val readiness = DurableSaveJobs.readiness.value
            assertEquals(SavePhase.QUEUED, queued.phase)
            assertTrue(readiness.loaded)
            assertFalse(retryDurableSavesForLibraryRefresh(readiness, listOf(queued), userInitiated = true))
        }

    @Test fun mixedServiceRecoveryPreservesOwnedFailureAndRecoversUnownedSave() =
        withQueuedSaves(count = 2) { app, coordinator, saves, _ ->
            val (owned, unowned) = saves
            DurableSaveJobs.remove(unowned.id)
            coordinator.hydrateStartup()
            assertTrue(retryDurableSavesForLibraryRefresh(
                DurableSaveJobs.readiness.value, DurableSaveJobs.state.value, userInitiated = true,
            ))

            val service = Robolectric.buildService(RecoveryService::class.java).get()
            val base = ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
            val oldBase = base.get(service)
            base.set(service, app)
            fun field(name: String) = ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }
            val ownerType = ReverbService::class.java.declaredClasses.single { it.simpleName == "DurableSaveOwner" }
            val constructor = ownerType.declaredConstructors.single { it.parameterCount == 5 }
                .apply { isAccessible = true }
            val reportedFailures = mutableListOf<Throwable?>()
            val receiver = object : ReverbService.AudioFileReceiver {
                override fun fileReady(recording: RecordingEntity) = Unit
                override fun fileFailed(message: String, error: Throwable?) {
                    reportedFailures += error
                }
            }
            val owner = constructor.newInstance(-1L, receiver, 0L, owned.id, owned.publication)
            @Suppress("UNCHECKED_CAST")
            val owners = field("durableSaveOwners").get(service) as MutableMap<Long, Any>
            owners[-1L] = owner

            val admissionExecutor = Executors.newSingleThreadExecutor()
            val exportExecutor = object : ScheduledThreadPoolExecutor(1) {
                override fun execute(command: Runnable) {
                    // Keep the submitted materializer pending so the handoff can be inspected.
                    schedule(command, 1, TimeUnit.DAYS)
                }
            }
            field("durableSaveAdmissionExecutor").set(service, admissionExecutor)
            field("exportWorkExecutor").set(service, exportExecutor)
            try {
                val interruption = DurableSaveLifetimeRejectedException(DurableSaveLifetimeRejection.SERVICE_STOPPING)
                var receipt: DurableSavePublicationReceipt? = null
                val caught = try {
                    coordinator.materialize(owned.id, onFailurePublished = { receipt = it }) {
                        throw interruption
                    }
                    null
                } catch (error: DurableSaveLifetimeRejectedException) {
                    error
                }
                assertSame(interruption, caught)
                val failure = pending(owned.id)
                assertEquals(SavePhase.FAILED, failure.phase)

                // The materializer has published its failure, but its Service owner is still
                // registered. Run the real Service recovery scan before allowing owner cleanup.
                ReverbService::class.java.getDeclaredMethod("scheduleDurableSaveRecovery", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(service, false)
                admissionExecutor.submit {}.get(5, TimeUnit.SECONDS)
                assertSame(owner, owners[-1L])
                assertSame(failure, pending(owned.id))
                assertEquals(SavePhase.QUEUED, pending(unowned.id).phase)
                assertTrue(pending(unowned.id).startupDiscovered)

                ShadowLooper.idleMainLooper()
                assertEquals(SavePhase.RECOVERING, pending(unowned.id).phase)
                val jobId = ownerType.getDeclaredField("jobId").apply { isAccessible = true }
                assertEquals(setOf(owned.id, unowned.id), owners.values.map { jobId.get(it) }.toSet())
                assertEquals(1, exportExecutor.queue.size)

                val failurePublication = ownerType.getDeclaredField("failurePublication").apply { isAccessible = true }
                failurePublication.set(owner, requireNotNull(receipt))
                ReverbService::class.java.getDeclaredMethod(
                    "finishDurableSaveFailure", ownerType, String::class.java, Exception::class.java,
                ).apply { isAccessible = true }.invoke(service, owner, "Save paused", interruption)
                ShadowLooper.idleMainLooper()
                assertEquals(listOf(interruption), reportedFailures)
                assertEquals(listOf(unowned.id), owners.values.map { jobId.get(it) })
            } finally {
                admissionExecutor.shutdown()
                exportExecutor.shutdownNow()
                try {
                    assertTrue(admissionExecutor.awaitTermination(5, TimeUnit.SECONDS))
                    // If an assertion failed before handoff, drain it as a retired fixture.
                    field("serviceDestroying").setBoolean(service, true)
                    ShadowLooper.idleMainLooper()
                } finally {
                    owners.clear()
                    base.set(service, oldBase)
                }
            }
        }

    @Test fun retiredGenerationBetweenScanAndHandoffPreservesRetryAndFailureReceipt() =
        withQueuedSaves(count = 2) { app, coordinator, saves, _ ->
            val (failedJob, discoveredJob) = saves
            DurableSaveJobs.remove(discoveredJob.id)
            coordinator.hydrateStartup()
            val interruption = DurableSaveLifetimeRejectedException(DurableSaveLifetimeRejection.SERVICE_STOPPING)
            var receipt: DurableSavePublicationReceipt? = null
            try {
                coordinator.materialize(failedJob.id, onFailurePublished = { receipt = it }) { throw interruption }
                throw AssertionError("materializer did not stop")
            } catch (caught: DurableSaveLifetimeRejectedException) {
                assertSame(interruption, caught)
            }
            val failed = pending(failedJob.id)
            val discovered = pending(discoveredJob.id)
            assertEquals(SavePhase.FAILED, failed.phase)
            assertEquals(SavePhase.QUEUED, discovered.phase)
            assertTrue(discovered.startupDiscovered)
            val originalReceipt = requireNotNull(receipt)

            val service = Robolectric.buildService(RecoveryService::class.java).get()
            val base = ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
            val previousBase = base.get(service)
            fun field(name: String) = ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }
            val admission = Executors.newSingleThreadExecutor()
            val export = object : ScheduledThreadPoolExecutor(1) {
                override fun execute(command: Runnable) {
                    schedule(command, 1, TimeUnit.DAYS)
                }
            }
            try {
                base.set(service, app)
                field("durableSaveAdmissionExecutor").set(service, admission)
                field("exportWorkExecutor").set(service, export)
                ReverbService::class.java.getDeclaredMethod(
                    "scheduleDurableSaveRecovery", Boolean::class.javaPrimitiveType,
                ).apply { isAccessible = true }.invoke(service, false)
                // This fence follows the scan and its post. The main looper is still paused.
                admission.submit {}.get(5, TimeUnit.SECONDS)
                assertTrue(field("durableRecoveryQueued").getBoolean(service))
                val observedBeforeHandoff = pending(failedJob.id)

                // Model the generation change at Service retirement while keeping main paused.
                val generation = field("durableWorkGeneration").get(service) as java.util.concurrent.atomic.AtomicLong
                generation.incrementAndGet()
                field("serviceDestroying").setBoolean(service, true)
                ShadowLooper.idleMainLooper()

                assertFalse(field("durableRecoveryQueued").getBoolean(service))
                assertTrue((field("durableSaveOwners").get(service) as Map<*, *>).isEmpty())
                assertTrue(export.queue.isEmpty())
                assertSame(failed, observedBeforeHandoff)
                assertSame(failed, pending(failedJob.id))
                assertSame(discovered, pending(discoveredJob.id))
                val readiness = DurableSaveJobs.readiness.value
                assertTrue(readiness.loaded)
                assertTrue(retryDurableSavesForLibraryRefresh(readiness, listOf(pending(discoveredJob.id)), true))
                assertTrue(DurableSaveJobs.claimFailureDelivery(originalReceipt))
                assertFalse(DurableSaveJobs.claimFailureDelivery(originalReceipt))
            } finally {
                field("serviceDestroying").setBoolean(service, true)
                admission.shutdown()
                try {
                    assertTrue(admission.awaitTermination(5, TimeUnit.SECONDS))
                    ShadowLooper.idleMainLooper()
                } finally {
                    export.shutdownNow()
                    base.set(service, previousBase)
                }
            }
        }

    private fun withQueuedSaves(
        count: Int = 1,
        body: (ReverbApplication, DurableSaveCoordinator, List<DurableSaveHandle>, Set<Thread>) -> Unit,
    ) {
        val host = RuntimeEnvironment.getApplication()
        val app = Instrumentation.newApplication(ReverbApplication::class.java, host.baseContext) as ReverbApplication
        val root = app.noBackupFilesDir
        val previousThreads = Thread.getAllStackTraces().keys
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val previousPid = Process.myPid()
        val existingTileJobs = jobs(QuickSaveTileOptions, "worker").toSet()
        val existingSaveNotificationJobs = jobs(DurableSaveNotifications, "scope").toSet()
        val notificationStarted = DurableSaveNotifications::class.java.getDeclaredField("started")
            .apply { isAccessible = true }
        val priorNotificationStarted = notificationStarted.getBoolean(DurableSaveNotifications)
        val coordinatorField = DurableSaveCoordinator::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }
        val priorCoordinator = coordinatorField.get(null)
        check(ServiceStateOsShadow.fixtureRoot == null)
        JournalHostIoShadow.reset()
        ServiceStateOsShadow.fixtureRoot = checkNotNull(root.parentFile).toPath().toAbsolutePath().normalize()
        ShadowProcess.setPid(41_027)

        val jobIds = mutableListOf<String>()
        val expectedJournalRoot = durableSaveJournalRoot(app).toPath().toAbsolutePath().normalize()
        try {
            // Robolectric reuses the classloader across methods with distinct data roots.
            coordinatorField.set(null, null)
            PersistentAudioChunkStore(File(root, "startup-ordering-store")).use { store ->
                store.configure(RetentionMode.SIZE, 4096, 8_000, 1, PcmSampleFormat.PCM_16)
                val pcm = ByteArray(512) { (it * 29 + 7).toByte() }
                assertEquals(pcm.size, store.append(pcm, 0, pcm.size))
                val coordinator = DurableSaveCoordinator.get(app)
                assertEquals(
                    "Captured coordinator journal root for fixture no-backup root ${root.absolutePath} " +
                        "(shadow root=${ServiceStateOsShadow.fixtureRoot})",
                    expectedJournalRoot,
                    capturedJournalRoot(coordinator),
                )
                val request = DurableSaveRequest("startup-ordering", MEDIA_STORE_DIRECTORY_ID, ExportFormat.WAV,
                    ExportCodec.PCM_16, 8_000, 1, PcmSampleFormat.PCM_16, 5)
                val saves = List(count) {
                    requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use {
                        coordinator.admit(it, request).also { handle -> jobIds += handle.id }
                    }
                }
                body(app, coordinator, saves, previousThreads)
            }
        } finally {
            try {
                startupThreads(previousThreads).forEach(::awaitStartup)
                runBlocking {
                    withTimeout(5_000) {
                        jobs(ReverbNotificationChannels, "scope").joinAll()
                        jobs(QuickSaveTileOptions, "worker").filterNot { it in existingTileJobs }.joinAll()
                        jobs(DurableSaveNotifications, "scope").filterNot { it in existingSaveNotificationJobs }
                            .forEach { it.cancelAndJoin() }
                    }
                }
                notificationStarted.setBoolean(DurableSaveNotifications, priorNotificationStarted)
                jobIds.forEach { id ->
                    val journal = DurableSaveJournal(durableSaveJournalRoot(app))
                    val cancelled = journal.read(id).copy(stage = DurableSaveStage.CANCELLED)
                    journal.write(cancelled)
                    journal.retire(cancelled)
                    DurableSaveJobs.remove(id)
                }
                JournalHostIoShadow.finishFixture()?.let { throw it }
            } finally {
                coordinatorField.set(null, priorCoordinator)
                ServiceStateOsShadow.fixtureRoot = null
                ShadowProcess.setPid(previousPid)
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }
    }

    private fun capturedJournalRoot(coordinator: DurableSaveCoordinator): Path {
        val journal = DurableSaveCoordinator::class.java.getDeclaredField("journal")
            .apply { isAccessible = true }.get(coordinator)
        val root = DurableSaveJournal::class.java.getDeclaredField("root")
            .apply { isAccessible = true }.get(journal) as File
        return root.toPath().toAbsolutePath().normalize()
    }

    class RecoveryService : ReverbService() {
        override fun startForegroundService(service: Intent): ComponentName =
            ComponentName(this, ReverbService::class.java)
    }

    private fun pending(id: String): PendingSave = DurableSaveJobs.state.value.single { it.id == id }

    private fun startupThreads(previous: Set<Thread>): List<Thread> =
        Thread.getAllStackTraces().keys.filter {
            it !in previous && (it.name == "reverb-save-recovery" || it.name == "reverb-incident-recovery")
        }

    private fun awaitStartup(thread: Thread) {
        thread.join(20_000)
        assertFalse("${thread.name} did not settle", thread.isAlive)
    }

    private fun jobs(owner: Any, fieldName: String): List<Job> {
        val field = owner.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
        val scope = field.get(owner) as CoroutineScope
        return checkNotNull(scope.coroutineContext[Job]).children.toList()
    }
}
