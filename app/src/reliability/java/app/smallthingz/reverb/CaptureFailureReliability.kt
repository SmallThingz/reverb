package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Injects a disconnected input; never starts microphone capture. Only in the isolated QA APK. */
internal fun verifyFailedCaptureRestartIsNotSilentlyListening(context: Context) {
    check(context.packageName.endsWith(".reliability"))
    val preferences = getRecorderPreferences(context)
    check(preferences.edit().putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false).commit())
    val bound = CountDownLatch(1)
    var service: ReverbService? = null
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as ReverbService.BackgroundRecorderBinder).service
            bound.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    check(context.bindService(Intent(context, ReverbService::class.java), connection, Context.BIND_AUTO_CREATE))
    try {
        check(bound.await(15, TimeUnit.SECONDS)) { "Recorder service did not bind" }
        val recorder = requireNotNull(service)
        val handler = recorder.field("audioHandler") as Handler
        val test = FutureTask {
            val store = recorder.field("loopingAudioChunkStore") as PersistentAudioChunkStore
            store.configure(RetentionMode.SIZE, 1_048_576L, 16_000, 1, PcmSampleFormat.PCM_16)
            val prior = ByteArray(32_000) { ((it * 13 + 7) and 0xff).toByte() }
            check(store.append(prior, 0, prior.size) == prior.size)
            store.checkpoint()
            val before = store.readTestPcm()
            val disconnected = DisconnectedTestAudioRecord()
            val generation = recorder.field("listeningCommandGeneration") as AtomicLong
            val epoch = generation.incrementAndGet()
            recorder.setField("durableCaptureIntentAuthorityValid", true)
            recorder.setField("durableListeningIntentEnabled", true)
            recorder.setField("state", ReverbService.STATE_LISTENING)
            recorder.setField("audioRecord", disconnected)
            recorder.setField("audioRecordGeneration", epoch)
            // Force the replacement factory to return null before opening any microphone.
            recorder.setField("sampleRate", 0)
            try {
                (recorder.field("audioReader") as Runnable).run()
                check(disconnected.readCalls == 1) { "Disconnected-read branch was not exercised" }
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
    } finally {
        context.unbindService(connection)
    }
}

private class DisconnectedTestAudioRecord : AudioRecord(
    MediaRecorder.AudioSource.DEFAULT,
    16_000,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    32_000,
) {
    var readCalls = 0
        private set

    override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int {
        readCalls++
        return ERROR_DEAD_OBJECT
    }

    override fun getRecordingState(): Int = RECORDSTATE_RECORDING

    override fun startRecording() {
        error("Reliability fixture must not start microphone capture")
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
