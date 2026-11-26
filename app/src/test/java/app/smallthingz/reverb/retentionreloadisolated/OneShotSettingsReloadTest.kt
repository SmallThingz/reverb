package app.smallthingz.reverb.retentionreloadisolated

import android.Manifest
import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import app.smallthingz.reverb.AppThemeMode
import app.smallthingz.reverb.AudioSourceMode
import app.smallthingz.reverb.BootResumeNotification
import app.smallthingz.reverb.CaptureAudioInput
import app.smallthingz.reverb.CaptureIoCoordinator
import app.smallthingz.reverb.ChannelMode
import app.smallthingz.reverb.DurableSaveNotifications
import app.smallthingz.reverb.ExportCodec
import app.smallthingz.reverb.InputRouteMode
import app.smallthingz.reverb.JournalHostIoShadow
import app.smallthingz.reverb.NativeSettingsPersistence
import app.smallthingz.reverb.NativeSettingsStoredConfiguration
import app.smallthingz.reverb.PcmSampleFormat
import app.smallthingz.reverb.PersistentAudioChunkStore
import app.smallthingz.reverb.PrefKey
import app.smallthingz.reverb.ProbeAudioRecordBuilderShadow
import app.smallthingz.reverb.ProbeAudioRecordShadow
import app.smallthingz.reverb.RecordingIncidentStore
import app.smallthingz.reverb.RecordingQuickTileStateCache
import app.smallthingz.reverb.ReverbService
import app.smallthingz.reverb.ReverbService.BufferSlot.ONE_SHOT
import app.smallthingz.reverb.ServiceStateOsShadow
import app.smallthingz.reverb.SettingsDraftController
import app.smallthingz.reverb.SettingsRuntimeLifetime
import app.smallthingz.reverb.SettingsSnapshot
import app.smallthingz.reverb.SilenceGateConfig
import app.smallthingz.reverb.defaultRetentionConfiguration
import app.smallthingz.reverb.encodeSilenceGate
import app.smallthingz.reverb.getConfiguredThemeMode
import app.smallthingz.reverb.getRecorderPreferences
import app.smallthingz.reverb.putBoolean
import app.smallthingz.reverb.putInt
import app.smallthingz.reverb.putString
import app.smallthingz.reverb.restoreRetentionConfigurationToPreferences
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
@Config(
    sdk = [35], application = Application::class,
    shadows = [JournalHostIoShadow::class, ProbeAudioRecordShadow::class, ProbeAudioRecordBuilderShadow::class],
    // Give the PID, recorder singletons, and borrowed shadows a dedicated sandbox.
    instrumentedPackages = ["app.smallthingz.reverb.retentionreloadisolated"],
)
@LooperMode(LooperMode.Mode.PAUSED)
class OneShotSettingsReloadTest {
    @Test fun themeSavePublishesOnceAndReloadResumesTheSameOneShotInput() {
        val loader = javaClass.classLoader
        listOf(ReverbService::class.java, NativeSettingsPersistence::class.java, BootResumeNotification::class.java,
            RecordingIncidentStore::class.java, DurableSaveNotifications::class.java,
            RecordingQuickTileStateCache::class.java, JournalHostIoShadow::class.java,
            ProbeAudioRecordShadow::class.java, ProbeAudioRecordBuilderShadow::class.java,
            ServiceStateOsShadow::class.java).forEach { assertSame(loader, it.classLoader) }

        Fixture(RuntimeEnvironment.getApplication()).use { fixture ->
            fixture.create()
            val service = fixture.service
            val persistence = NativeSettingsPersistence(fixture.app) { service }
            val stored = runBlocking { withTimeout(WAIT_MILLIS) { persistence.readStoredConfiguration() } }
            val previous = stored.toSnapshot()
            assertEquals(AppThemeMode.SYSTEM, previous.themeMode)
            val submission = SettingsDraftController.Submission(
                attempt = SettingsDraftController.PersistenceAttempt(),
                activation = Any(),
                snapshot = previous.copy(themeMode = AppThemeMode.DARK),
                editRevision = 1L,
                outputRevision = 0L,
                originalSilenceRaw = stored.silence.raw,
            )

            assertTrue(service.enableListening(ONE_SHOT).accepted)
            val start = checkNotNull(service.captureStart)
            assertEquals(ComponentName(service, SyntheticService::class.java), start.component)
            assertEquals(Service.START_STICKY, service.onStartCommand(start, 0, 1))
            fixture.input.heldReadEntered.awaitOrFail("One-shot did not enter the held read")
            val captureIo = serviceField(service, "captureIo") as CaptureIoCoordinator
            val original = checkNotNull(captureIo.currentInput())
            assertSame(fixture.input, original.record)
            val oneShot = serviceField(service, "oneShotAudioChunkStore") as PersistentAudioChunkStore
            fixture.awaitWriter("First packet did not reach One-shot") { original.processedFrames == PACKET_BYTES / 2L }
            assertEquals(PACKET_BYTES.toLong(), oneShot.countFilledBytes())
            assertTrue(ProbeAudioRecordShadow.created.get() > 0)
            val lifetime = serviceField(service, "settingsRuntimeLifetime") as SettingsRuntimeLifetime
            assertFalse(lifetime.isActive())
            val beforeSave = JournalHostIoShadow.directorySyncAttempts

            val result = runBlocking {
                withTimeout(WAIT_MILLIS) { persistence.persist(previous, submission, stored.malformedSilence) }
            }
            assertEquals(previous.format, result)
            assertEquals(AppThemeMode.DARK, getConfiguredThemeMode(fixture.app))
            val keepalive = checkNotNull(service.settingsStart)
            assertEquals(start.component, keepalive.component)
            assertEquals(ReverbService.ACTION_SETTINGS_RUNTIME_KEEPALIVE, keepalive.action)
            assertEquals(Service.START_STICKY, service.onStartCommand(keepalive, 0, 2))
            fixture.fenceWriter()
            assertTrue("Settings reload did not remain counted behind the read", lifetime.isActive())
            val parked = original.storage.ring.status()
            assertTrue(parked.reading && parked.fence)
            assertEquals(1L, fixture.input.resumedReadEntered.count)
            assertEquals("Settings journal publication", beforeSave + 1, JournalHostIoShadow.directorySyncAttempts)

            // The first packet has already opened the One-shot chunk. The held packet can
            // append to it without creating a directory entry while this fault is armed.
            JournalHostIoShadow.failDirectorySyncs = 1
            fixture.input.returnHeldRead.countDown()
            fixture.awaitWriter("Settings runtime reload did not complete") { !lifetime.isActive() }
            assertEquals("Reload consumed the injected directory failure", 1, JournalHostIoShadow.failDirectorySyncs)
            fixture.input.resumedReadEntered.awaitOrFail("Original One-shot input did not resume after Settings reload")
            fixture.fenceWriter()
            assertSame(original, captureIo.currentInput())
            assertEquals(1, service.inputCreations)
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, fixture.input.recordingState)
            assertSame(ONE_SHOT, serviceField(service, "activeBufferSlot"))
            assertFalse(service.isCaptureConfigurationUnavailable())
            assertEquals(2L * PACKET_BYTES, oneShot.countFilledBytes())
            assertEquals("Settings save and live reload together", beforeSave + 1, JournalHostIoShadow.directorySyncAttempts)
            assertEquals("Reload consumed the injected directory failure", 1, JournalHostIoShadow.failDirectorySyncs)
        }
    }

    class SyntheticService : ReverbService() {
        internal lateinit var input: ReadSequence
        internal var inputCreations = 0
            private set
        internal var captureStart: Intent? = null
            private set
        internal var settingsStart: Intent? = null
            private set

        override fun checkSelfPermission(permission: String): Int =
            if (permission == Manifest.permission.RECORD_AUDIO) PackageManager.PERMISSION_GRANTED
            else super.checkSelfPermission(permission)

        override fun startForegroundService(intent: Intent): ComponentName {
            check(captureStart == null && intent.action == null)
            captureStart = Intent(intent)
            return checkNotNull(intent.component)
        }

        override fun startService(intent: Intent): ComponentName {
            check(settingsStart == null && intent.action == ACTION_SETTINGS_RUNTIME_KEEPALIVE)
            settingsStart = Intent(intent)
            return checkNotNull(intent.component)
        }

        override fun createAudioRecord(): CaptureAudioInput {
            check(++inputCreations == 1) { "Settings opened a replacement capture input" }
            return input
        }
    }

    internal class ReadSequence : CaptureAudioInput {
        val heldReadEntered = CountDownLatch(1)
        val returnHeldRead = CountDownLatch(1)
        val resumedReadEntered = CountDownLatch(1)
        private val finishRead = CountDownLatch(1)
        private val packet = ByteArray(PACKET_BYTES) { (it * 13 + 1).toByte() }
        private var reads = 0
        @Volatile var reader: Thread? = null
            private set
        @Volatile private var recording = false
        var released = false
            private set

        override val state: Int get() = AudioRecord.STATE_INITIALIZED
        override val recordingState: Int get() = if (recording) AudioRecord.RECORDSTATE_RECORDING
            else AudioRecord.RECORDSTATE_STOPPED

        override fun startRecording() { recording = true }

        override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int {
            reader = Thread.currentThread()
            when (++reads) {
                2 -> {
                    heldReadEntered.countDown()
                    check(returnHeldRead.await(30, TimeUnit.SECONDS)) { "Fixture never returned the held read" }
                }
                3 -> {
                    resumedReadEntered.countDown()
                    check(finishRead.await(30, TimeUnit.SECONDS)) { "Fixture never released the resumed read" }
                    return 0
                }
            }
            if (finishRead.count == 0L) return 0
            check(reads <= 2 && packet.size <= sizeInBytes)
            buffer.put(packet)
            return packet.size
        }

        override fun stop() { recording = false }
        override fun release() {
            check(!released)
            released = true
        }

        fun releaseReads() {
            finishRead.countDown()
            returnHeldRead.countDown()
        }
    }

    private class Fixture(val app: Application) : AutoCloseable {
        private val noBackup = app.noBackupFilesDir
        private val root = checkNotNull(app.filesDir.parentFile).toPath().toAbsolutePath().normalize()
        private val preferences = getRecorderPreferences(app)
        private val originalPid = Process.myPid()
        private val controller = Robolectric.buildService(SyntheticService::class.java)
        val input = ReadSequence()
        val service = controller.get().apply { input = this@Fixture.input }
        private var ownsFixture = false
        private var createCalled = false

        fun create() {
            check(noBackup.toPath().toAbsolutePath().normalize().parent == root)
            check(noBackup.listFiles()?.isEmpty() == true && preferences.all.isEmpty())
            check(ServiceStateOsShadow.fixtureRoot == null && originalPid == 0)
            JournalHostIoShadow.reset()
            check(ProbeAudioRecordShadow.liveCount() == 0 && ProbeAudioRecordShadow.created.get() == 0 &&
                ProbeAudioRecordShadow.released.get() == 0)
            ownsFixture = true
            ServiceStateOsShadow.fixtureRoot = root
            ShadowProcess.setPid(47_192)
            check(restoreRetentionConfigurationToPreferences(preferences, defaultRetentionConfiguration()))
            check(preferences.edit()
                .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
                .putInt(PrefKey.SAMPLE_RATE, 8_000)
                .putInt(PrefKey.CHANNEL_MODE, ChannelMode.MONO.storageCode.toInt())
                .putInt(PrefKey.AUDIO_SOURCE, AudioSourceMode.VOICE_RECOGNITION.storageCode.toInt())
                .putInt(PrefKey.INPUT_ROUTE, InputRouteMode.AUTO.storageCode.toInt())
                .putInt(PrefKey.PCM_SAMPLE_FORMAT, PcmSampleFormat.PCM_16.storageCode.toInt())
                .putString(PrefKey.SILENCE_GATE, encodeSilenceGate(SilenceGateConfig()))
                .commit())
            createCalled = true
            controller.create()
            awaitWriter("Service storage initialization did not settle") {
                serviceField(service, "captureInitializationPending") == false &&
                    !service.isCaptureConfigurationUnavailable()
            }
            val saveAdmission = serviceField(service, "durableSaveAdmissionExecutor") as ExecutorService
            saveAdmission.submit {}.get(WAIT_MILLIS, TimeUnit.MILLISECONDS)
            ShadowLooper.idleMainLooper()
        }

        fun fenceWriter() {
            val handler = serviceField(service, "audioHandler") as Handler
            val task = FutureTask { Unit }
            check(handler.post(task)) { "Service writer rejected the fixture fence" }
            task.get(WAIT_MILLIS, TimeUnit.MILLISECONDS)
        }

        fun awaitWriter(message: String, done: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS)
            do {
                fenceWriter()
                if (done()) return
            } while (System.nanoTime() < deadline)
            error(message)
        }

        override fun close() {
            if (!ownsFixture) return
            var failure: Throwable? = null
            fun cleanup(block: () -> Unit): Boolean =
                try {
                    block()
                    true
                } catch (error: Throwable) {
                    val primary = failure
                    if (primary == null) failure = error else if (primary !== error) primary.addSuppressed(error)
                    false
                }

            try {
                // Synchronize with a reload that may still be unwinding after an assertion.
                if (createCalled && input.reader != null) cleanup { fenceWriter() }
                JournalHostIoShadow.failDirectorySyncs = 0
                if (createCalled && service.captureStart != null) cleanup {
                    check(service.disableListening().accepted) { "Fixture Stop was rejected" }
                }
            } finally {
                input.releaseReads()
            }

            var drained = true
            fun drain(block: () -> Unit) {
                if (!cleanup(block)) drained = false
            }
            if (createCalled) {
                val writer = serviceField(service, "audioThread") as HandlerThread?
                val reader = (serviceField(service, "captureIo") as CaptureIoCoordinator?)?.currentInput()?.thread
                drain { controller.destroy() }
                drain { joinThread(writer) }
                drain { joinThread(reader ?: input.reader) }
                cleanup { if (input.reader != null) check(input.released) { "Synthetic input was not released" } }
                val executors = listOf("durableSaveAdmissionExecutor", "bufferClearExecutor",
                    "durabilitySyncExecutor", "exportWorkExecutor")
                for (name in executors) {
                    drain {
                        (serviceField(service, name) as ExecutorService?)?.let {
                            check(it.isShutdown && it.awaitTermination(WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                                "$name did not stop"
                            }
                        }
                    }
                }
                drain { ShadowLooper.idleMainLooper() }
                for ((owner, name) in listOf(RecordingQuickTileStateCache to "persistedReadExecutor",
                    BootResumeNotification to "worker")) {
                    drain { shutdownWorker(field(owner.javaClass, name).get(owner) as ExecutorService, name) }
                }
                drain { ShadowLooper.idleMainLooper() }
                drain {
                    val watchdog = field(ReverbService::class.java, "stopReadWatchdog\$delegate").get(null) as Lazy<*>
                    if (watchdog.isInitialized()) {
                        shutdownWorker(watchdog.value as ExecutorService, "Stop watchdog")
                    }
                }
                drain {
                    runBlocking {
                        withTimeout(WAIT_MILLIS) {
                            children(DurableSaveNotifications, "scope").forEach { it.cancelAndJoin() }
                            children(RecordingIncidentStore, "historyMutationScope").forEach { it.join() }
                        }
                    }
                }
            }

            // Only relinquish the host fixture after its potential file owners have stopped.
            if (drained) {
                cleanup { JournalHostIoShadow.finishFixture()?.let { throw it } }
                cleanup { check(ProbeAudioRecordShadow.liveCount() == 0) }
                cleanup { check(ProbeAudioRecordShadow.created.get() == ProbeAudioRecordShadow.released.get()) }
                ProbeAudioRecordShadow.created.set(0)
                ProbeAudioRecordShadow.released.set(0)
                ServiceStateOsShadow.fixtureRoot = null
                ShadowProcess.setPid(originalPid)
                cleanup { check(preferences.edit().clear().commit()) }
                cleanup { check(noBackup.listFiles()?.all { it.deleteRecursively() } == true) }
            }
            failure?.let { throw it }
        }
    }

    private companion object {
        const val WAIT_MILLIS = 8_000L
        const val PACKET_BYTES = 512

        fun CountDownLatch.awaitOrFail(message: String) {
            assertTrue(message, await(WAIT_MILLIS, TimeUnit.MILLISECONDS))
        }

        fun joinThread(thread: Thread?) {
            thread ?: return
            thread.join(WAIT_MILLIS)
            check(!thread.isAlive) { "${thread.name} did not stop" }
        }

        fun shutdownWorker(worker: ExecutorService, name: String) {
            worker.shutdown()
            check(worker.awaitTermination(WAIT_MILLIS, TimeUnit.MILLISECONDS)) { "$name did not stop" }
        }

        fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }

        fun serviceField(service: ReverbService, name: String): Any? = field(ReverbService::class.java, name).get(service)

        fun children(owner: Any, scopeField: String): List<Job> {
            val scope = field(owner.javaClass, scopeField).get(owner) as CoroutineScope
            return checkNotNull(scope.coroutineContext[Job]).children.toList()
        }

        fun NativeSettingsStoredConfiguration.toSnapshot() = SettingsSnapshot(
            themeMode = themeMode,
            retentionMode = retention.mode,
            oneShotRetentionTime = retention.oneShotSeconds.toInt(),
            oneShotRetentionSizeBytes = retention.oneShotSizeBytes,
            loopingRetentionTime = retention.loopingSeconds.toInt(),
            loopingRetentionSizeBytes = retention.loopingSizeBytes,
            format = format,
            codec = ExportCodec.PCM_16,
            sampleFormat = sampleFormat,
            source = source,
            channelMode = channelMode,
            route = route,
            sampleRate = sampleRate,
            exportDirectoryUri = exportTreeUri?.toString(),
            flacCompression = flacCompression,
            quickSaveEnabled = quickSaveEnabled,
            quickSaveSeconds = quickSaveSeconds,
            silenceGate = checkNotNull(silence.raw),
            silenceHistogram = silenceHistogram,
        )
    }
}
