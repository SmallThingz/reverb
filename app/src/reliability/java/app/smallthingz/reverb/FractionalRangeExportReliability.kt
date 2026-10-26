package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real snapshot-to-WAV export, using synthetic PCM and the isolated QA Service only. */
internal fun verifyFractionalRangeExport(
    context: Context,
    tree: Uri,
    provider: ReliabilityDocumentsProvider,
) {
    check(context.packageName == "app.smallthingz.reverb.reliability")
    val prefs = getRecorderPreferences(context)
    check(prefs.edit()
        .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
        .putInt(PrefKey.OUTPUT_FORMAT, ExportFormat.WAV.storageCode.toInt())
        .putInt(PrefKey.OUTPUT_CODEC, ExportCodec.PCM_16.storageCode.toInt())
        .putInt(PrefKey.PCM_SAMPLE_FORMAT, PcmSampleFormat.PCM_16.storageCode.toInt())
        .putInt(PrefKey.SAMPLE_RATE, 16_000)
        .putInt(PrefKey.CHANNEL_MODE, ChannelMode.MONO.storageCode.toInt())
        .commit())
    check(!readDurableCaptureIntentPreferences(prefs).enabled)
    val connected = CountDownLatch(1)
    val service = AtomicReference<ReverbService>()
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service.set((binder as ReverbService.BackgroundRecorderBinder).service)
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    val root = File(context.filesDir, "fractional-range-source-${UUID.randomUUID()}")
    var bound = false
    var store: PersistentAudioChunkStore? = null
    var executor: ExecutorService? = null
    var primaryFailure: Throwable? = null
    val main = Handler(Looper.getMainLooper())
    fun awaitMainCallbacks() {
        val fence = FutureTask { Unit }
        check(main.post(fence))
        fence.get(15, TimeUnit.SECONDS)
    }
    try {
        check(setConfiguredExportTreeUri(context, tree))
        provider.reset(ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID)
        bound = context.bindService(Intent(context, ReverbService::class.java), connection, Context.BIND_AUTO_CREATE)
        check(bound)
        check(connected.await(15, TimeUnit.SECONDS)) { "QA recorder did not bind" }
        val recorder = requireNotNull(service.get())
        val audio = ReverbService::class.java.getDeclaredField("audioHandler").apply { isAccessible = true }
            .get(recorder) as Handler
        val initialized = FutureTask {
            val record = ReverbService::class.java.getDeclaredField("audioRecord").apply { isAccessible = true }
            check(record.get(recorder) == null) { "Synthetic export unexpectedly owns microphone input" }
        }
        check(audio.post(initialized))
        initialized.get(15, TimeUnit.SECONDS)
        check(!readDurableCaptureIntentPreferences(prefs).enabled)
        val config = recorder.getConfigurationSnapshot()
        check(config.format == ExportFormat.WAV && config.codec == ExportCodec.PCM_16 &&
            config.sampleFormat == PcmSampleFormat.PCM_16 && config.sampleRate == 16_000 &&
            config.channelMode == ChannelMode.MONO) { "QA export configuration did not converge" }
        val worker = ReverbService::class.java.getDeclaredField("exportWorkExecutor").apply { isAccessible = true }
            .get(recorder) as ExecutorService
        executor = worker

        val source = PersistentAudioChunkStore(root)
        store = source
        source.configure(RetentionMode.SIZE, 1_048_576L, 16_000, 1, PcmSampleFormat.PCM_16)
        // 8,001 frames: the 0.5000625-second tail is not exactly representable as Float.
        val pcm = ByteArray(16_002)
        for (frame in 0 until 8001) {
            val sample = frame - 4000
            pcm[frame * 2] = sample.toByte()
            pcm[frame * 2 + 1] = (sample shr 8).toByte()
        }
        check(source.append(pcm, 0, pcm.size) == pcm.size)
        source.sealActiveChunk()
        check(source.durationSeconds() == 8001.0 / 16_000.0)
        val before = root.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(root).path to it.readBytes() }
        val uiConfig = ExportUiConfig(ExportFormat.WAV, ExportCodec.PCM_16, PcmSampleFormat.PCM_16, 16_000, 1)
        val boundedTail = buildCustomExportRange(source.durationSeconds(), 0.375f, 1f, uiConfig)
        check(boundedTail.startSeconds == 0.375f)
        check(boundedTail.endSeconds == source.durationSeconds().toFloat())
        check(boundedTail.warningDurationSeconds == null)

        fun exportAndCheck(name: String, start: Float, end: Float, firstByte: Int, lastByte: Int, frames: Int) {
            val delivered = CountDownLatch(1)
            val terminalCount = AtomicInteger()
            val saved = AtomicReference<RecordingEntity>()
            val failure = AtomicReference<Throwable>()
            val receiver = object : ReverbService.AudioFileReceiver {
                override fun fileReady(recording: RecordingEntity) {
                    saved.set(recording); terminalCount.incrementAndGet(); delivered.countDown()
                }
                override fun fileFailed(message: String, error: Throwable?) {
                    failure.set(IllegalStateException(message, error))
                    terminalCount.incrementAndGet(); delivered.countDown()
                }
                override fun fileCancelled() {
                    failure.set(IllegalStateException("Synthetic export was unexpectedly cancelled"))
                    terminalCount.incrementAndGet(); delivered.countDown()
                }
            }
            val parent = ReverbService.TimelineSnapshot(requireNotNull(source.acquireRange(0.0, source.durationSeconds())))
            // Public submission owns the parent and its child. Raw end=1f intentionally reaches
            // the Service's exact Double clamp; the UI builder result above is not substituted.
            try {
                recorder.dumpRecordingRange(parent, start, end, receiver, name)
            } catch (error: Throwable) {
                try { parent.close() } catch (closeError: Throwable) {
                    if (closeError !== error) error.addSuppressed(closeError)
                }
                throw error
            }
            check(delivered.await(15, TimeUnit.SECONDS)) { "Fractional export did not deliver a terminal result" }
            worker.submit {}.get(15, TimeUnit.SECONDS)
            awaitMainCallbacks()
            failure.get()?.let { throw it }
            check(terminalCount.get() == 1) { "Fractional export delivered duplicate terminal results" }
            val recording = checkNotNull(saved.get()) { "Fractional export did not publish a recording" }
            val bytes = requireNotNull(openRecordingInputStream(context, recording)).use { it.readBytes() }
            val expected = pcm.copyOfRange(firstByte, lastByte)
            check(expected.size == frames * 2)
            check(bytes.size == 44 + expected.size) { "Unexpected WAV length ${bytes.size}" }
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check(bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(82, 73, 70, 70)))
            check(bytes.copyOfRange(8, 12).contentEquals(byteArrayOf(87, 65, 86, 69)))
            check(bytes.copyOfRange(12, 16).contentEquals(byteArrayOf(102, 109, 116, 32)))
            check(bytes.copyOfRange(36, 40).contentEquals(byteArrayOf(100, 97, 116, 97)))
            check(header.getInt(4) == bytes.size - 8)
            check(header.getInt(16) == 16)
            check(header.getShort(20).toInt() == 1 && header.getShort(22).toInt() == 1)
            check(header.getInt(24) == 16_000 && header.getInt(28) == 32_000)
            check(header.getShort(32).toInt() == 2 && header.getShort(34).toInt() == 16)
            check(header.getInt(40) == frames * 2) { "Wrong fractional output frame count" }
            check(bytes.copyOfRange(44, bytes.size).contentEquals(expected)) { "Selected PCM bytes changed" }
            check(!recorder.cancelCurrentExport()) { "Completed fractional export retained its token" }
        }

        exportAndCheck("fractional-middle", 0.125f, 0.375f, 4000, 12000, 4000)
        exportAndCheck("fractional-tail", 0.375f, 1f, 12000, 16002, 2001)
        val retained = ByteArrayOutputStream()
        requireNotNull(source.acquireRange(0.0, source.durationSeconds())).use { original ->
            original.readNormalized(16_000, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
                retained.write(bytes, offset, count)
                count
            }
        }
        check(retained.toByteArray().contentEquals(pcm)) { "Range exports changed source PCM" }
        val after = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes() }
        check(before.keys == after.keys && before.all { (path, bytes) -> bytes.contentEquals(after.getValue(path)) }) {
            "Range exports changed sealed source files"
        }
        check(!readDurableCaptureIntentPreferences(prefs).enabled)
        val idle = FutureTask {
            val record = ReverbService::class.java.getDeclaredField("audioRecord").apply { isAccessible = true }
            check(record.get(recorder) == null) { "Synthetic export created microphone input" }
        }
        check(audio.post(idle))
        idle.get(15, TimeUnit.SECONDS)
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        var cleanupFailure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error
                else if (cleanupFailure !== error) cleanupFailure!!.addSuppressed(error)
            }
        }
        // Never reset the provider or delete source/output artifacts on failure. A timeout must
        // stop this owned export and fence its worker before the synthetic store is closed.
        if (primaryFailure != null) cleanup { service.get()?.cancelCurrentExport() }
        var drained = executor == null
        cleanup {
            executor?.submit {}?.get(15, TimeUnit.SECONDS)
            drained = true
        }
        if (drained) {
            cleanup { awaitMainCallbacks() }
            cleanup { store?.close() }
        }
        else cleanup { error("QA export worker did not drain; synthetic source retained open") }
        if (bound) cleanup { context.unbindService(connection) }
        if (drained) cleanup { check(setConfiguredExportTreeUri(context, null)) }
        cleanupFailure?.let { error ->
            if (primaryFailure != null) {
                if (primaryFailure !== error) primaryFailure!!.addSuppressed(error)
            } else throw error
        }
    }
}
