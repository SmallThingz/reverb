package app.smallthingz.reverb

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val PREVIEW_SAMPLE_RATE = 24_000
private const val PREVIEW_WRITE_BYTES = 2_400
private const val SCRUB_AUDITION_SECONDS = 0.14
private const val SCRUB_AUDITION_LEAD_SECONDS = 0.02
private const val PREVIEW_PROGRESS_INTERVAL_MILLIS = 32L
private const val SHUTTLE_GRAIN_OUTPUT_SECONDS = 0.040
private const val SHUTTLE_CROSSFADE_SECONDS = 0.008
private const val SHUTTLE_MIN_ABS_RATE = 1f
private const val SHUTTLE_MAX_ABS_RATE = 8f

internal const val RANGE_WAVEFORM_COARSE_BUCKETS = 96
internal const val RANGE_WAVEFORM_DETAIL_BUCKETS = 512
private const val RANGE_WAVEFORM_COARSE_PROBES = 2
private const val RANGE_WAVEFORM_COARSE_FRAMES_PER_PROBE = 20
private const val RANGE_WAVEFORM_DETAIL_PROBES = 3
private const val RANGE_WAVEFORM_DETAIL_FRAMES_PER_PROBE = 16

internal fun shapeWaveformMagnitude(raw: Float): Float {
    // Fixed companding keeps quiet structure visible without needing a whole-file peak scan.
    // The transfer is duration-independent and stable while the waveform is built progressively.
    val signal = ((raw.coerceIn(0f, 1f) - 0.00008f).coerceAtLeast(0f) * 3.6f)
        .coerceIn(0f, 1f)
    return signal.pow(0.28f)
}

internal fun normalizeWaveformEnvelope(raw: FloatArray): FloatArray =
    FloatArray(raw.size) { index -> shapeWaveformMagnitude(raw[index]) }


internal fun orientShuttlePcm16Mono(input: ByteArray, signedRate: Float): ByteArray {
    val frameCount = input.size / 2
    if (frameCount <= 0 || !signedRate.isFinite()) return ByteArray(0)
    val usableBytes = frameCount * 2
    if (signedRate >= 0f) return input.copyOf(usableBytes)
    val output = ByteArray(usableBytes)
    for (outFrame in 0 until frameCount) {
        val sourceFrame = frameCount - 1 - outFrame
        output[outFrame * 2] = input[sourceFrame * 2]
        output[outFrame * 2 + 1] = input[sourceFrame * 2 + 1]
    }
    return output
}

internal data class ShuttleCrossfadeResult(
    val output: ByteArray,
    val tail: ByteArray,
)

internal fun crossfadeShuttlePcm16Mono(
    previousTail: ByteArray?,
    current: ByteArray,
    overlapFrames: Int,
): ShuttleCrossfadeResult {
    val currentFrames = current.size / 2
    if (currentFrames <= 0) return ShuttleCrossfadeResult(ByteArray(0), previousTail ?: ByteArray(0))
    val requestedOverlap = overlapFrames.coerceAtLeast(0)
    val tailFrames = minOf(requestedOverlap, currentFrames)
    val bodyFrames = currentFrames - tailFrames
    val previousFrames = (previousTail?.size ?: 0) / 2
    val mixedFrames = minOf(previousFrames, bodyFrames, requestedOverlap)
    val output = ByteArray(bodyFrames * 2)

    fun read(bytes: ByteArray, frame: Int): Int {
        val index = frame * 2
        return ((bytes[index + 1].toInt() shl 8) or (bytes[index].toInt() and 0xff)).toShort().toInt()
    }
    fun write(bytes: ByteArray, frame: Int, value: Int) {
        val index = frame * 2
        bytes[index] = (value and 0xff).toByte()
        bytes[index + 1] = ((value ushr 8) and 0xff).toByte()
    }

    var outFrame = 0
    if (mixedFrames > 0 && previousTail != null) {
        val previousStart = previousFrames - mixedFrames
        repeat(mixedFrames) { index ->
            val t = (index + 1).toFloat() / (mixedFrames + 1).toFloat()
            val blend = t * t * (3f - 2f * t)
            val previous = read(previousTail, previousStart + index)
            val next = read(current, index)
            val mixed = (previous * (1f - blend) + next * blend)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            write(output, outFrame++, mixed)
        }
    }
    val currentBodyStart = mixedFrames
    for (frame in currentBodyStart until bodyFrames) {
        var value = read(current, frame)
        if (previousFrames == 0 && requestedOverlap > 0 && frame < requestedOverlap) {
            val t = (frame + 1).toFloat() / (requestedOverlap + 1).toFloat()
            val fade = t * t * (3f - 2f * t)
            value = (value * fade).roundToInt()
        }
        write(output, outFrame++, value)
    }
    val tail = if (tailFrames > 0) {
        current.copyOfRange(bodyFrames * 2, currentFrames * 2)
    } else {
        ByteArray(0)
    }
    return ShuttleCrossfadeResult(output, tail)
}

internal enum class RangeWaveformPass(
    val bucketCount: Int,
    val probesPerBucket: Int,
    val framesPerProbe: Int,
) {
    COARSE(
        RANGE_WAVEFORM_COARSE_BUCKETS,
        RANGE_WAVEFORM_COARSE_PROBES,
        RANGE_WAVEFORM_COARSE_FRAMES_PER_PROBE,
    ),
    DETAIL(
        RANGE_WAVEFORM_DETAIL_BUCKETS,
        RANGE_WAVEFORM_DETAIL_PROBES,
        RANGE_WAVEFORM_DETAIL_FRAMES_PER_PROBE,
    ),
}

internal fun ReverbService.TimelineSnapshot.readWaveformEnvelopeProgressive(
    pass: RangeWaveformPass,
    onBucket: (bucketIndex: Int, magnitude: Float) -> Boolean,
): FloatArray {
    val duration = durationSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    if (duration <= 0.0) return FloatArray(pass.bucketCount)
    val shaped = FloatArray(pass.bucketCount)
    sampleWaveformEnvelopeProgressive(
        bucketCount = pass.bucketCount,
        probesPerBucket = pass.probesPerBucket,
        framesPerProbe = pass.framesPerProbe,
    ) { index, raw ->
        val magnitude = shapeWaveformMagnitude(raw)
        shaped[index] = magnitude
        onBucket(index, magnitude)
    }
    return shaped
}

internal class TimelineAudioPreviewController : Closeable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingDeque(),
    ) { runnable ->
        Thread(runnable, "Reverb-range-preview").apply { isDaemon = true }
    }
    // AudioTrack stop/flush/release can block in AudioFlinger. Scrub gestures used to run
    // this teardown on the caller (Compose main) thread every audition, which made the puck
    // visibly hitch. A separate worker can interrupt the preview stream without blocking UI.
    private val releaseExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Reverb-range-preview-release").apply { isDaemon = true }
    }
    private val trackLock = Any()
    private val releasedTracks = WeakHashMap<AudioTrack, Boolean>()

    private data class ShuttleCommand(val positionSeconds: Double, val rate: Float)

    private val shuttleCommand = AtomicReference<ShuttleCommand?>(null)

    @Volatile
    private var activeShuttleToken = 0L

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var closed = false

    fun play(
        snapshot: ReverbService.TimelineSnapshot,
        fromSeconds: Double,
        onProgress: (Double) -> Unit,
        onFinished: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (closed) return
        cancelCurrent()
        val token = generation.incrementAndGet()
        val start = fromSeconds.coerceIn(0.0, snapshot.durationSeconds)
        enqueueLatest {
            stream(
                token = token,
                snapshot = snapshot,
                startSeconds = start,
                endSeconds = snapshot.durationSeconds,
                volume = 1f,
                reportProgress = true,
                onProgress = onProgress,
                onFinished = onFinished,
                onError = onError,
            )
        }
    }

    fun audition(snapshot: ReverbService.TimelineSnapshot, atSeconds: Double) {
        if (closed) return
        cancelCurrent()
        val token = generation.incrementAndGet()
        val center = atSeconds.coerceIn(0.0, snapshot.durationSeconds)
        val start = (center - SCRUB_AUDITION_LEAD_SECONDS).coerceAtLeast(0.0)
        val end = (center + SCRUB_AUDITION_SECONDS).coerceAtMost(snapshot.durationSeconds)
        if (end <= start) return
        enqueueLatest {
            stream(
                token = token,
                snapshot = snapshot,
                startSeconds = start,
                endSeconds = end,
                volume = 0.72f,
                reportProgress = false,
                onProgress = {},
                onFinished = {},
                onError = {},
            )
        }
    }

    fun startShuttle(
        snapshot: ReverbService.TimelineSnapshot,
        atSeconds: Double,
        rate: Float,
    ) {
        if (closed) return
        cancelCurrent()
        val token = generation.incrementAndGet()
        activeShuttleToken = token
        shuttleCommand.set(
            ShuttleCommand(
                positionSeconds = atSeconds.coerceIn(0.0, snapshot.durationSeconds),
                rate = rate.coerceIn(-SHUTTLE_MAX_ABS_RATE, SHUTTLE_MAX_ABS_RATE),
            ),
        )
        enqueueLatest { shuttle(token, snapshot) }
    }

    fun updateShuttle(atSeconds: Double, rate: Float) {
        val token = activeShuttleToken
        if (closed || token == 0L || generation.get() != token) return
        shuttleCommand.set(
            ShuttleCommand(
                positionSeconds = atSeconds,
                rate = rate.coerceIn(-SHUTTLE_MAX_ABS_RATE, SHUTTLE_MAX_ABS_RATE),
            ),
        )
    }

    fun stopShuttle() {
        if (closed || activeShuttleToken == 0L) return
        cancelCurrent()
    }

    fun stop() {
        if (closed) return
        cancelCurrent()
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelCurrent()
        executor.shutdownNow()
        releaseExecutor.shutdown()
    }

    private fun enqueueLatest(block: () -> Unit) {
        executor.queue.clear()
        executor.execute(block)
    }

    private fun cancelCurrent() {
        generation.incrementAndGet()
        activeShuttleToken = 0L
        shuttleCommand.set(null)
        executor.queue.clear()
        val track = synchronized(trackLock) {
            activeTrack.also { activeTrack = null }
        }
        if (track != null) {
            if (!releaseExecutor.isShutdown) {
                releaseExecutor.execute { releaseTrackOnce(track) }
            } else {
                // close() calls cancelCurrent() before shutting this executor down, so this is
                // only a defensive fallback for an unexpected late cancellation.
                releaseTrackOnce(track)
            }
        }
    }

    private fun shuttle(token: Long, snapshot: ReverbService.TimelineSnapshot) {
        var track: AudioTrack? = null
        try {
            checkCurrent(token)
            track = createTrack(volume = 0.82f, minimumBufferBytes = PREVIEW_WRITE_BYTES * 2)
            synchronized(trackLock) {
                checkCurrent(token)
                activeTrack = track
            }
            track.play()
            val overlapFrames = (SHUTTLE_CROSSFADE_SECONDS * PREVIEW_SAMPLE_RATE.toDouble())
                .roundToInt()
                .coerceAtLeast(1)
            var previousTail = ByteArray(0)

            while (isCurrent(token)) {
                val command = shuttleCommand.get() ?: break
                val rate = command.rate
                val magnitude = abs(rate)
                if (magnitude < SHUTTLE_MIN_ABS_RATE) {
                    // Keep the pending tail across the tiny center dead-zone so a direction
                    // reversal can crossfade instead of restarting at an arbitrary phase.
                    Thread.sleep(8L)
                    continue
                }

                val center = command.positionSeconds.coerceIn(0.0, snapshot.durationSeconds)
                // Scrub audio is granular, not tape-stretched. The cursor already carries
                // the seek velocity, so each output grain stays at normal pitch while its
                // source position moves faster/slower with the gesture.
                val sourceDuration = SHUTTLE_GRAIN_OUTPUT_SECONDS
                val start = if (rate >= 0f) center else (center - sourceDuration).coerceAtLeast(0.0)
                val end = if (rate >= 0f) {
                    (center + sourceDuration).coerceAtMost(snapshot.durationSeconds)
                } else {
                    center
                }
                if (end <= start) {
                    previousTail = ByteArray(0)
                    Thread.sleep(8L)
                    continue
                }

                val lease = snapshot.acquireRange(start, end)
                if (lease == null) {
                    previousTail = ByteArray(0)
                    Thread.sleep(8L)
                    continue
                }
                val expectedRawBytes = ((end - start) * PREVIEW_SAMPLE_RATE.toDouble() * 2.0)
                    .roundToInt()
                    .coerceAtLeast(0)
                val raw = ByteArrayOutputStream(expectedRawBytes)
                try {
                    lease.readNormalized(
                        targetSampleRate = PREVIEW_SAMPLE_RATE,
                        targetChannelCount = 1,
                        targetSampleFormat = PcmSampleFormat.PCM_16,
                    ) { array, offset, count ->
                        checkCurrent(token)
                        raw.write(array, offset, count)
                        count
                    }
                } finally {
                    lease.close()
                }
                val oriented = orientShuttlePcm16Mono(raw.toByteArray(), rate)
                val blended = crossfadeShuttlePcm16Mono(previousTail, oriented, overlapFrames)
                previousTail = blended.tail
                var offset = 0
                while (offset < blended.output.size) {
                    checkCurrent(token)
                    val written = track.write(
                        blended.output,
                        offset,
                        blended.output.size - offset,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (written <= 0) throw IOException("Audio shuttle write failed: $written")
                    offset += written
                }
            }
        } catch (_: PreviewCancelled) {
            Unit
        } catch (_: Throwable) {
            Unit
        } finally {
            if (activeShuttleToken == token) activeShuttleToken = 0L
            if (track != null) {
                synchronized(trackLock) {
                    if (activeTrack === track) activeTrack = null
                }
                releaseTrackOnce(track)
            }
        }
    }

    private fun stream(
        token: Long,
        snapshot: ReverbService.TimelineSnapshot,
        startSeconds: Double,
        endSeconds: Double,
        volume: Float,
        reportProgress: Boolean,
        onProgress: (Double) -> Unit,
        onFinished: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        var child: PersistentAudioChunkStore.RangeLease? = null
        var track: AudioTrack? = null
        try {
            checkCurrent(token)
            child = snapshot.acquireRange(startSeconds, endSeconds)
                ?: throw IOException("No audio available for preview")
            checkCurrent(token)
            track = createTrack(volume)
            synchronized(trackLock) {
                checkCurrent(token)
                activeTrack = track
            }
            track.play()

            var lastProgressAt = 0L
            val readResult = child.readNormalized(
                targetSampleRate = PREVIEW_SAMPLE_RATE,
                targetChannelCount = 1,
                targetSampleFormat = PcmSampleFormat.PCM_16,
            ) { array, offset, count ->
                var position = offset
                val limit = offset + count
                while (position < limit) {
                    checkCurrent(token)
                    val requested = minOf(PREVIEW_WRITE_BYTES, limit - position)
                    val written = track.write(array, position, requested, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) throw IOException("Audio preview write failed: $written")
                    position += written
                    if (reportProgress) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgressAt >= PREVIEW_PROGRESS_INTERVAL_MILLIS) {
                            lastProgressAt = now
                            val played = unsignedPlaybackHead(track.playbackHeadPosition)
                            postIfCurrent(token) {
                                onProgress(
                                    (startSeconds + played.toDouble() / PREVIEW_SAMPLE_RATE.toDouble())
                                        .coerceAtMost(endSeconds),
                                )
                            }
                        }
                    }
                }
                count
            }

            if (reportProgress) {
                val targetFrames = (readResult.durationSeconds * PREVIEW_SAMPLE_RATE.toDouble())
                    .roundToLong()
                    .coerceAtLeast(0L)
                while (unsignedPlaybackHead(track.playbackHeadPosition) < targetFrames) {
                    checkCurrent(token)
                    val played = unsignedPlaybackHead(track.playbackHeadPosition)
                    postIfCurrent(token) {
                        onProgress(
                            (startSeconds + played.toDouble() / PREVIEW_SAMPLE_RATE.toDouble())
                                .coerceAtMost(endSeconds),
                        )
                    }
                    Thread.sleep(16L)
                }
            }
            checkCurrent(token)
            postIfCurrent(token, onFinished)
        } catch (_: PreviewCancelled) {
            Unit
        } catch (error: Throwable) {
            if (isCurrent(token)) postIfCurrent(token) { onError(error) }
        } finally {
            runCatching { child?.close() }
            if (track != null) {
                synchronized(trackLock) {
                    if (activeTrack === track) activeTrack = null
                }
                releaseTrackOnce(track)
            }
        }
    }

    private fun createTrack(
        volume: Float,
        minimumBufferBytes: Int = PREVIEW_WRITE_BYTES * 4,
    ): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            PREVIEW_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(minimumBufferBytes)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(PREVIEW_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer)
            .build()
            .also { it.setVolume(volume.coerceIn(0f, 1f)) }
    }

    private fun releaseTrackOnce(track: AudioTrack) {
        synchronized(releasedTracks) {
            if (releasedTracks.put(track, true) != null) return
        }
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private fun checkCurrent(token: Long) {
        if (!isCurrent(token) || Thread.currentThread().isInterrupted) throw PreviewCancelled()
    }

    private fun isCurrent(token: Long): Boolean = !closed && generation.get() == token

    private fun postIfCurrent(token: Long, block: () -> Unit) {
        mainHandler.post {
            if (isCurrent(token)) block()
        }
    }

    private fun unsignedPlaybackHead(value: Int): Long = value.toLong() and 0xffff_ffffL

    private class PreviewCancelled : RuntimeException()
}
