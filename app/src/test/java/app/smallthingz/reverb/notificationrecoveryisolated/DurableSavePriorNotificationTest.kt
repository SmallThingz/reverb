package app.smallthingz.reverb.notificationrecoveryisolated

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import app.smallthingz.reverb.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [JournalHostIoShadow::class],
    instrumentedPackages = ["app.smallthingz.reverb.notificationrecoveryisolated"])
@LooperMode(LooperMode.Mode.PAUSED)
class DurableSavePriorNotificationTest {
    @Test fun loadedServiceScanKeepsPriorPendingTagUntilMainAdopts() {
        val app = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory(app.cacheDir.toPath(), "prior-save-tag-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        val pendingId = UUID.randomUUID().toString()
        val orphanId = UUID.randomUUID().toString()
        val fenceId = UUID.randomUUID().toString()
        val priorTag = durableSaveNotificationTag(pendingId)
        val staleTag = durableSaveNotificationTag(orphanId)
        val started = DurableSaveNotifications::class.java.getDeclaredField("started").apply { isAccessible = true }
        val scope = DurableSaveNotifications::class.java.getDeclaredField("scope").apply { isAccessible = true }
            .get(DurableSaveNotifications) as CoroutineScope
        val parent = checkNotNull(scope.coroutineContext[Job])
        val priorChildren = parent.children.toSet()
        @Suppress("UNCHECKED_CAST")
        val readiness = DurableSaveJobs::class.java.getDeclaredField("mutableReadiness").apply { isAccessible = true }
            .get(DurableSaveJobs) as MutableStateFlow<DurableSaveJobs.Readiness>
        val priorReadiness = readiness.value
        check(!started.getBoolean(DurableSaveNotifications) && priorChildren.isEmpty())
        check(DurableSaveJobs.state.value.isEmpty() && !priorReadiness.loaded)
        check(ServiceStateOsShadow.fixtureRoot == null)
        JournalHostIoShadow.reset()
        ServiceStateOsShadow.fixtureRoot = root.toPath().toAbsolutePath().normalize()
        try {
            val journal = DurableSaveJournal(durableSaveJournalRoot(context))
            seedQueuedManifest(root, journal, pendingId)
            val channel = ReverbNotificationChannel.SAVE_RESULTS
            ReverbNotificationChannels.ensure(app, channel)
            val saved = Notification.Builder(app, channel.id).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Prior process").build()
            manager.notify(priorTag, DURABLE_SAVE_NOTIFICATION_ID, saved)
            manager.notify(staleTag, DURABLE_SAVE_NOTIFICATION_ID, saved)
            fun has(tag: String) = manager.activeNotifications.any { it.tag == tag && it.id == DURABLE_SAVE_NOTIFICATION_ID }
            assertTrue(has(priorTag) && has(staleTag))

            val coordinator = DurableSaveCoordinator::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }.newInstance(context)
            val scan = runBlocking(Dispatchers.IO) { coordinator.scanForRecovery(emptySet()) }
            assertEquals(listOf(pendingId), scan.rows.map { it.id })
            assertTrue(readiness.value.loaded)
            assertTrue(DurableSaveJobs.state.value.isEmpty())

            // The collector has entered a loaded sweep. Reposting the same orphan after
            // its cancellation requires a second pass, independent of active-tag order.
            awaitCondition { !has(staleTag) }
            manager.notify(staleTag, DURABLE_SAVE_NOTIFICATION_ID, saved)
            DurableSaveJobs.publish(PendingSave(fenceId, "Collector fence", 1, 1, SavePhase.QUEUED))
            awaitCondition { !has(staleTag) }
            assertTrue("Loaded sweep canceled a journaled save before adoption", has(priorTag))

            assertEquals(listOf(pendingId), DurableSaveJobs.adoptRecovery(scan, emptySet()).map { it.id })
            assertTrue(has(priorTag))
            journal.write(journal.read(pendingId).copy(stage = DurableSaveStage.CANCELLED))
            val retired = runBlocking(Dispatchers.IO) { coordinator.scanForRecovery(emptySet()) }
            assertTrue(DurableSaveJobs.adoptRecovery(retired, emptySet()).isEmpty())
            awaitCondition { !has(priorTag) }
        } finally {
            try {
                runBlocking {
                    withTimeout(8_000) {
                        parent.children.filterNot { it in priorChildren }.toList().forEach { it.cancelAndJoin() }
                    }
                }
                started.setBoolean(DurableSaveNotifications, false)
                for (id in listOf(pendingId, orphanId, fenceId)) {
                    DurableSaveJobs.remove(id)
                    manager.cancel(durableSaveNotificationTag(id), DURABLE_SAVE_NOTIFICATION_ID)
                }
                readiness.value = priorReadiness
                JournalHostIoShadow.finishFixture()?.let { throw it }
            } finally {
                ServiceStateOsShadow.fixtureRoot = null
                check(root.deleteRecursively())
            }
        }
    }

    private fun awaitCondition(condition: () -> Boolean) = runBlocking {
        withTimeout(8_000) { while (!condition()) delay(10) }
    }

    private fun seedQueuedManifest(root: File, journal: DurableSaveJournal, id: String) {
        val directory = journal.createDirectory(id)
        val request = DurableSaveRequest("prior.wav", MEDIA_STORE_DIRECTORY_ID, ExportFormat.WAV,
            ExportCodec.PCM_16, 8_000, 1, PcmSampleFormat.PCM_16, 5)
        PersistentAudioChunkStore(File(root, "source")).use { store ->
            store.configure(RetentionMode.SIZE, 4096, 8_000, 1, PcmSampleFormat.PCM_16)
            val pcm = ByteArray(512) { it.toByte() }
            assertEquals(pcm.size, store.append(pcm, 0, pcm.size))
            requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                lease.withSealedRange { range ->
                    val pins = DurablePcmPins.link(directory, range) { planned ->
                        journal.write(DurableSaveRecord(id, DurableSaveStage.PINNING, request, planned))
                    }
                    journal.write(DurableSaveRecord(id, DurableSaveStage.QUEUED, request, pins))
                }
            }
        }
    }
}
