package app.smallthingz.reverb

import android.media.AudioRecord
import android.media.AudioTimestamp
import java.nio.ByteBuffer

/** One owned microphone resource; production construction/configuration stays in the Service. */
internal interface CaptureAudioInput {
    val state: Int
    val recordingState: Int
    val nativeBufferFrames: Int? get() = null
    fun startRecording()
    fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int
    /** Optional capture-clock proof, queried only by calibration, never inferred from delivery time. */
    fun lastReadStartedAtNanos(readEndedAtNanos: Long): Long? = null
    /** Framework policy, not PCM amplitude. Queried only by calibration. */
    fun isClientSilenced(): Boolean = false
    fun observeClientSilencing(onState: (Boolean?, Long) -> Unit, onFailure: (Exception) -> Unit): CapturePolicySubscription? = null
    fun stop()
    fun release()
}

internal class AndroidCaptureAudioInput(private val record: AudioRecord, private val frameBytes: Int) : CaptureAudioInput {
    private val timestamp = AudioTimestamp()
    private var framesRead = 0L
    private var lastReadFirstFrame = 0L
    private var timingValid = true
    init { require(frameBytes > 0) }
    override val state: Int get() = record.state
    override val recordingState: Int get() = record.recordingState
    override val nativeBufferFrames: Int get() = record.bufferSizeInFrames
    override fun startRecording() {
        record.startRecording()
        framesRead = 0L
        lastReadFirstFrame = 0L
        timingValid = true
    }
    override fun read(buffer: ByteBuffer, sizeInBytes: Int, readMode: Int): Int {
        val read = record.read(buffer, sizeInBytes, readMode)
        if (read > 0) {
            lastReadFirstFrame = framesRead
            if (read % frameBytes != 0) timingValid = false
            framesRead += read / frameBytes
        }
        return read
    }
    override fun lastReadStartedAtNanos(readEndedAtNanos: Long): Long? {
        if (!timingValid) return null
        return try {
            if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) null
            else captureReadStartNanos(lastReadFirstFrame, timestamp.framePosition, timestamp.nanoTime,
                record.sampleRate)?.takeIf { it <= readEndedAtNanos }
        } catch (_: Exception) { null }
    }
    override fun observeClientSilencing(
        onState: (Boolean?, Long) -> Unit,
        onFailure: (Exception) -> Unit,
    ): CapturePolicySubscription? = if (android.os.Build.VERSION.SDK_INT >= 29)
        AndroidCapturePolicySubscription(record, onState, onFailure) else null
    override fun stop() = record.stop()
    override fun isClientSilenced(): Boolean = android.os.Build.VERSION.SDK_INT >= 29 &&
        record.activeRecordingConfiguration?.isClientSilenced == true
    override fun release() = record.release()
}

/** Native overruns may make delivered-frame position lag capture position; never move it forward by guessing. */
internal fun captureReadStartNanos(
    firstReadFrame: Long, timestampFrame: Long, timestampNanos: Long, sampleRate: Int,
): Long? {
    if (firstReadFrame < 0 || timestampFrame < 0 || timestampNanos == 0L || sampleRate <= 0) return null
    return try {
        val frames = Math.subtractExact(firstReadFrame, timestampFrame)
        val seconds = Math.floorDiv(frames, sampleRate.toLong())
        val remainder = Math.floorMod(frames, sampleRate.toLong())
        Math.addExact(timestampNanos, Math.addExact(Math.multiplyExact(seconds, 1_000_000_000L),
            remainder * 1_000_000_000L / sampleRate))
    } catch (_: ArithmeticException) { null }
}

/** Callback payload witnesses are admitted before returning to the framework callback. */
@androidx.annotation.RequiresApi(29)
private class AndroidCapturePolicySubscription(
    private val record: AudioRecord,
    onState: (Boolean?, Long) -> Unit,
    onFailure: (Exception) -> Unit,
) : CapturePolicySubscription {
    private val retired = java.util.concurrent.atomic.AtomicBoolean()
    private val witnesses = CapturePolicyWitnesses(
        execute = { worker.execute(it) },
        snapshot = { record.activeRecordingConfiguration?.isClientSilenced to System.currentTimeMillis() },
        deliver = { onState(true, it) }, failed = onFailure,
    )
    private val callback = object : android.media.AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>) {
            try {
                val owned = configs.singleOrNull { it.clientAudioSessionId == record.audioSessionId }
                witnesses.observe(owned?.isClientSilenced, System.currentTimeMillis())
            } catch (error: Exception) { witnesses.report(error) }
        }
    }
    init { record.registerAudioRecordingCallback(java.util.concurrent.Executor { it.run() }, callback) }
    override fun activate() = witnesses.activate()
    override fun close() {
        if (!retired.compareAndSet(false, true)) return
        witnesses.close()
        record.unregisterAudioRecordingCallback(callback)
    }
    companion object {
        private val worker = java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
            java.util.concurrent.ThreadFactory { task -> Thread(task, "Reverb-input-policy").apply { isDaemon = true } },
        )
    }
}
