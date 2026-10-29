package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioRecord
import android.os.Handler
import android.os.IBinder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Injects a disconnected input; never starts microphone capture. Only in the isolated QA APK. */
internal fun verifyFailedCaptureRestartIsNotSilentlyListening(context: Context) {
    check(context.packageName.endsWith(".reliability"))
    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
    val parent = File(context.noBackupFilesDir, "capture-restart-failure")
    check(parent.mkdirs() || parent.isDirectory)
    val root = Files.createTempDirectory(parent.toPath(), "restart-").toFile()
    val admission = createFrameFixtureAdmission(context, root)
    check(frameFixtureAdmission.compareAndSet(null, admission)) { "Another synthetic owner is unresolved" }
    val bound = CountDownLatch(1)
    var service: CaptureFrameIdentityService? = null
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as ReverbService.BackgroundRecorderBinder).service as CaptureFrameIdentityService
            bound.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    var isBound = false
    var primary: Throwable? = null
    try {
        isBound = context.bindService(Intent(context, CaptureFrameIdentityService::class.java), connection, Context.BIND_AUTO_CREATE)
        check(isBound)
        check(bound.await(15, TimeUnit.SECONDS)) { "Recorder service did not bind" }
        val recorder = requireNotNull(service)
        val handler = recorder.field("audioHandler") as Handler
        val test = FutureTask {
            check(recorder.frameField("captureStoresReady") == true && recorder.frameField("audioRecord") == null)
            check(recorder.field("captureReadBuffers") == null) {
                "Idle service allocated capture buffers before capture preparation"
            }
            ReverbService::class.java.getDeclaredMethod("prepareCaptureReadBuffersOnAudioThread").apply {
                isAccessible = true
            }.invoke(recorder)
            check(recorder.field("captureReadBuffers") != null) { "Capture buffers were not prepared" }
            val store = recorder.field("loopingAudioChunkStore") as PersistentAudioChunkStore
            check(!store.hasData()) { "Unique restart fixture unexpectedly contains audio" }
            store.configure(RetentionMode.SIZE, 1_048_576L, 16_000, 1, PcmSampleFormat.PCM_16)
            val prior = ByteArray(32_000) { ((it * 13 + 7) and 0xff).toByte() }
            check(store.append(prior, 0, prior.size) == prior.size)
            store.checkpoint()
            val before = store.readTestPcm()
            val disconnected = DisconnectedTestAudioInput()
            val generation = recorder.field("listeningCommandGeneration") as AtomicLong
            val epoch = generation.incrementAndGet()
            recorder.setField("durableCaptureIntentAuthorityValid", true)
            recorder.setField("durableListeningIntentEnabled", true)
            recorder.setField("state", ReverbService.STATE_LISTENING)
            recorder.setField("audioRecord", disconnected)
            recorder.setField("audioRecordGeneration", epoch)
            // The Service override always returns null; no native factory can run on any path.
            try {
                (recorder.field("audioReader") as Runnable).run()
                check(admission.factoryCalls.get() == 1) { "Replacement did not reach the null-only synthetic factory exactly once" }
                check(disconnected.readCalls == 1) { "Disconnected-read branch was not exercised" }
                check(disconnected.releaseCalls == 1) { "Disconnected input was not released exactly once" }
                check(recorder.field("audioRecord") == null) { "Failed replacement still owns a recorder" }
                check(before.contentEquals(store.readTestPcm())) { "Input recovery changed retained audio" }
                check(recorder.field("state") != ReverbService.STATE_LISTENING) {
                    "Failed AudioRecord restart silently left STATE_LISTENING with no recorder"
                }
                check(recorder.field("durableListeningIntentEnabled") == false) {
                    "Terminal input failure left stale enabled capture state"
                }
            } finally {
                // Restore only this isolated fixture; do not leave an armed synthetic service.
                recorder.setField("durableListeningIntentEnabled", false)
                recorder.setField("state", ReverbService.STATE_READY)
                recorder.setField("sampleRate", 16_000)
                ReverbService::class.java.getDeclaredMethod("releaseAudioRecord").apply {
                    isAccessible = true
                }.invoke(recorder)
            }
        }
        check(handler.post(test))
        test.get(20, TimeUnit.SECONDS)
    } catch (error: Throwable) {
        primary = error
        throw error
    } finally {
        var cleanupFailure: Throwable? = null
        var drained = !isBound
        if (isBound) try {
            context.unbindService(connection)
            check(admission.destroyed.await(30, TimeUnit.SECONDS)) { "Synthetic restart teardown outcome unknown" }
            val audioThread = checkNotNull(service).frameField("audioThread") as Thread
            audioThread.join(30_000)
            check(!audioThread.isAlive) { "Synthetic restart store teardown outcome unknown" }
            drained = true
            if (primary == null) check(admission.factoryCalls.get() == 1) {
                "Unexpected factory request during terminal restart cleanup"
            }
        } catch (error: Throwable) { cleanupFailure = error }
        if (drained && !frameFixtureAdmission.compareAndSet(admission, null)) {
            val error = IllegalStateException("Synthetic restart admission owner changed")
            if (cleanupFailure != null) cleanupFailure.addSuppressed(error) else cleanupFailure = error
        }
        if (cleanupFailure != null) {
            if (primary != null) primary.addSuppressed(cleanupFailure) else primary = cleanupFailure
        }
        try {
            File(root, "result.txt").writeText(if (primary == null)
                "PASS capture_restart_failure\nnullFactoryCalls=${admission.factoryCalls.get()}\naudioThreadTerminated=$drained\n"
                else "FAIL capture_restart_failure\n${primary.stackTraceToString()}")
        } catch (error: Throwable) {
            if (primary != null) primary.addSuppressed(error) else primary = error
        }
        primary?.let { throw it }
    }
}

private class DisconnectedTestAudioInput : CaptureAudioInput {
    var readCalls = 0
        private set
    var releaseCalls = 0
        private set

    override val state: Int = AudioRecord.STATE_INITIALIZED
    override val recordingState: Int = AudioRecord.RECORDSTATE_RECORDING

    override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int {
        readCalls++
        return AudioRecord.ERROR_DEAD_OBJECT
    }

    override fun startRecording() {
        error("Reliability fixture must not start microphone capture")
    }

    override fun stop() = Unit

    override fun release() {
        releaseCalls++
    }
}

private fun ReverbService.field(name: String): Any? =
    ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)

private fun ReverbService.setField(name: String, value: Any) {
    ReverbService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}

private fun PersistentAudioChunkStore.readTestPcm(): ByteArray {
    val bytes = ByteArrayOutputStream()
    requireNotNull(acquireRange(0.0, durationSeconds())).use { lease ->
        lease.readNormalized(16_000, 1, PcmSampleFormat.PCM_16) { array, offset, count ->
            bytes.write(array, offset, count)
            count
        }
    }
    return bytes.toByteArray()
}
