package app.smallthingz.reverb

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.WeakHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val PREVIEW_SAMPLE_RATE = 24_000
private const val SHUTTLE_PREFERRED_SAMPLE_RATE = 96_000
private const val SHUTTLE_FALLBACK_SAMPLE_RATE = 48_000
private const val PREVIEW_WRITE_BYTES = 2_400
private const val SCRUB_AUDITION_SECONDS = 0.14
private const val SCRUB_AUDITION_LEAD_SECONDS = 0.02
private const val PREVIEW_PROGRESS_INTERVAL_MILLIS = 32L
private const val PREVIEW_DRAIN_STALL_TIMEOUT_MILLIS = 2_000L
private const val SHUTTLE_GRAIN_OUTPUT_SECONDS = 0.024
private const val SHUTTLE_CROSSFADE_SECONDS = 0.008
private const val SHUTTLE_SOURCE_HOP_SECONDS = SHUTTLE_GRAIN_OUTPUT_SECONDS - SHUTTLE_CROSSFADE_SECONDS
private const val SHUTTLE_MAX_TARGET_ERROR_SECONDS = SHUTTLE_SOURCE_HOP_SECONDS * 4.0
private const val SHUTTLE_MIN_SOURCE_RATE = 0.02f
private const val SHUTTLE_MAX_SOURCE_RATE = 4_096f
private const val SHUTTLE_MIN_AUDIBLE_SPEED = 1f
private const val SHUTTLE_MAX_AUDIBLE_SPEED = 3f
private const val SHUTTLE_MAX_SOURCE_GRAIN_SECONDS =
    SHUTTLE_GRAIN_OUTPUT_SECONDS * SHUTTLE_MAX_AUDIBLE_SPEED
private const val SHUTTLE_CACHE_HALF_SPAN_SECONDS =
    SHUTTLE_MAX_TARGET_ERROR_SECONDS + SHUTTLE_MAX_SOURCE_GRAIN_SECONDS

internal const val RANGE_WAVEFORM_COARSE_BUCKETS = 96
internal const val RANGE_WAVEFORM_DETAIL_BUCKETS = 512

internal fun resolvePreviewAudioTrackBufferBytes(
    reportedMinBufferBytes: Int,
    minimumBufferBytes: Int,
): Int {
    require(minimumBufferBytes > 0) { "Preview buffer floor must be positive" }
    if (reportedMinBufferBytes <= 0) {
        throw IOException("AudioTrack minimum buffer query failed: $reportedMinBufferBytes")
    }
    return maxOf(reportedMinBufferBytes, minimumBufferBytes)
}

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

internal fun shuttleSourceRequiresReanchor(
    previousAnchorSeconds: Double?,
    targetAnchorSeconds: Double,
    signedRate: Float,
): Boolean {
    val previous = previousAnchorSeconds?.takeIf { it.isFinite() } ?: return false
    if (!targetAnchorSeconds.isFinite() || !signedRate.isFinite() || abs(signedRate) < SHUTTLE_MIN_SOURCE_RATE) {
        return false
    }
    val direction = if (signedRate >= 0f) 1.0 else -1.0
    return (targetAnchorSeconds - previous) * direction > SHUTTLE_MAX_TARGET_ERROR_SECONDS
}

internal fun nextShuttleSourceAnchorSeconds(
    previousAnchorSeconds: Double?,
    targetAnchorSeconds: Double,
    signedRate: Float,
): Double? {
    if (!targetAnchorSeconds.isFinite() || !signedRate.isFinite()) return null
    val magnitude = abs(signedRate)
    if (magnitude < SHUTTLE_MIN_SOURCE_RATE) return null
    val previous = previousAnchorSeconds?.takeIf { it.isFinite() } ?: return targetAnchorSeconds
    val direction = if (signedRate >= 0f) 1.0 else -1.0
    val distanceTowardTarget = (targetAnchorSeconds - previous) * direction
    // Below one output hop, replaying another grain would loop mostly the same waveform.
    // Wait for meaningful source movement instead; the pending tail is faded to silence.
    if (distanceTowardTarget + 1e-9 < SHUTTLE_SOURCE_HOP_SECONDS) return null
    // A smooth head must not become an audible history lesson on long timelines. If the
    // gesture target gets more than four output hops away, re-anchor at the current target;
    // the caller fades out/in across that discontinuity instead of letting latency grow unbounded.
    if (shuttleSourceRequiresReanchor(previous, targetAnchorSeconds, signedRate)) return targetAnchorSeconds
    // Otherwise converge in bounded source hops. The command rate is actual source-time per
    // wall-clock second, so it can be much larger than the audible pitch ceiling. Slow motion
    // waits for one meaningful hop and then jumps to the live target instead of replaying an
    // almost-identical grain.
    val maxAdvance = SHUTTLE_SOURCE_HOP_SECONDS * maxOf(1f, magnitude).toDouble()
    val advance = minOf(distanceTowardTarget, maxAdvance)
    return previous + direction * advance
}

internal fun sliceShuttlePcm16Mono(
    input: ByteArray,
    windowStartSeconds: Double,
    rangeStartSeconds: Double,
    rangeEndSeconds: Double,
    sampleRate: Int,
): ByteArray {
    val frameCount = input.size / 2
    if (
        frameCount <= 0 || sampleRate <= 0 || !windowStartSeconds.isFinite() ||
        !rangeStartSeconds.isFinite() || !rangeEndSeconds.isFinite() || rangeEndSeconds <= rangeStartSeconds
    ) {
        return ByteArray(0)
    }
    val startFrame = ((rangeStartSeconds - windowStartSeconds) * sampleRate.toDouble())
        .roundToInt()
        .coerceIn(0, frameCount)
    val endFrame = ((rangeEndSeconds - windowStartSeconds) * sampleRate.toDouble())
        .roundToInt()
        .coerceIn(startFrame, frameCount)
    return input.copyOfRange(startFrame * 2, endFrame * 2)
}

internal fun windowShuttlePcm16Mono(input: ByteArray, edgeFrames: Int): ByteArray {
    val frameCount = input.size / 2
    if (frameCount <= 0) return ByteArray(0)
    val edge = minOf(edgeFrames.coerceAtLeast(0), (frameCount + 1) / 2)
    if (edge <= 0) return input.copyOf(frameCount * 2)
    val output = input.copyOf(frameCount * 2)
    fun read(frame: Int): Int {
        val index = frame * 2
        return ((output[index + 1].toInt() shl 8) or (output[index].toInt() and 0xff)).toShort().toInt()
    }
    fun write(frame: Int, value: Int) {
        val index = frame * 2
        output[index] = (value and 0xff).toByte()
        output[index + 1] = ((value ushr 8) and 0xff).toByte()
    }
    val rampDenominator = (edge - 1).coerceAtLeast(1).toFloat()
    for (frame in 0 until frameCount) {
        val fromStart = (frame.toFloat() / rampDenominator).coerceIn(0f, 1f)
        val fromEnd = ((frameCount - 1 - frame).toFloat() / rampDenominator).coerceIn(0f, 1f)
        val phase = minOf(fromStart, fromEnd)
        val gain = phase * phase * (3f - 2f * phase)
        val value = (read(frame) * gain).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        write(frame, value)
    }
    return output
}

internal fun fadeOutShuttlePcm16Mono(input: ByteArray): ByteArray {
    val frameCount = input.size / 2
    if (frameCount <= 0) return ByteArray(0)
    val output = input.copyOf(frameCount * 2)
    fun read(frame: Int): Int {
        val index = frame * 2
        return ((output[index + 1].toInt() shl 8) or (output[index].toInt() and 0xff)).toShort().toInt()
    }
    fun write(frame: Int, value: Int) {
        val index = frame * 2
        output[index] = (value and 0xff).toByte()
        output[index + 1] = ((value ushr 8) and 0xff).toByte()
    }
    for (frame in 0 until frameCount) {
        val t = if (frameCount == 1) 1f else frame.toFloat() / (frameCount - 1).toFloat()
        val fade = 1f - t * t * (3f - 2f * t)
        val value = (read(frame) * fade).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        write(frame, value)
    }
    return output
}

internal fun transformShuttlePcm16Mono(input: ByteArray, signedRate: Float): ByteArray {
    val inputFrames = input.size / 2
    if (inputFrames <= 0 || !signedRate.isFinite()) return ByteArray(0)
    val speed = shuttleAudibleSpeed(signedRate)
    val reverse = signedRate < 0f
    if (!reverse && speed <= 1.0001f) return input

    val outputFrames = (inputFrames.toFloat() / speed).roundToInt()
        .coerceIn(1, inputFrames)
    val output = ByteArray(outputFrames * 2)

    fun readOriented(frame: Int): Float {
        val logicalFrame = frame.coerceIn(0, inputFrames - 1)
        val sourceFrame = if (reverse) inputFrames - 1 - logicalFrame else logicalFrame
        val index = sourceFrame * 2
        return ((input[index + 1].toInt() shl 8) or (input[index].toInt() and 0xff))
            .toShort()
            .toFloat()
    }
    fun write(frame: Int, value: Float) {
        val encoded = value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val index = frame * 2
        output[index] = (encoded and 0xff).toByte()
        output[index + 1] = ((encoded ushr 8) and 0xff).toByte()
    }
    fun cubic(position: Double): Float {
        val base = position.toInt().coerceIn(0, inputFrames - 1)
        val t = (position - base.toDouble()).toFloat().coerceIn(0f, 1f)
        val p0 = readOriented(base - 1)
        val p1 = readOriented(base)
        val p2 = readOriented(base + 1)
        val p3 = readOriented(base + 2)
        val a = -0.5f * p0 + 1.5f * p1 - 1.5f * p2 + 0.5f * p3
        val b = p0 - 2.5f * p1 + 2f * p2 - 0.5f * p3
        val c = -0.5f * p0 + 0.5f * p2
        return ((a * t + b) * t + c) * t + p1
    }

    if (outputFrames == 1) {
        write(0, readOriented(inputFrames / 2))
        return output
    }
    val sourceSpan = (inputFrames - 1).toDouble()
    val outputSpan = (outputFrames - 1).toDouble()
    for (frame in 0 until outputFrames) {
        val sourcePosition = frame.toDouble() * sourceSpan / outputSpan
        write(frame, cubic(sourcePosition))
    }
    return output
}

internal fun shuttleAudibleSpeed(signedRate: Float): Float =
    abs(signedRate.takeIf { it.isFinite() } ?: 0f)
        .coerceIn(SHUTTLE_MIN_AUDIBLE_SPEED, SHUTTLE_MAX_AUDIBLE_SPEED)

internal fun sanitizedShuttlePositionSeconds(positionSeconds: Double): Double =
    positionSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0

internal fun sanitizedShuttleSourceRate(sourceRate: Float): Float =
    sourceRate.takeIf { it.isFinite() }
        ?.coerceIn(-SHUTTLE_MAX_SOURCE_RATE, SHUTTLE_MAX_SOURCE_RATE)
        ?: 0f

internal fun shuttleSampleRateCandidates(preferredMinBufferBytes: Int): List<Int> =
    if (preferredMinBufferBytes > 0) {
        listOf(SHUTTLE_PREFERRED_SAMPLE_RATE, SHUTTLE_FALLBACK_SAMPLE_RATE)
    } else {
        listOf(SHUTTLE_FALLBACK_SAMPLE_RATE)
    }

internal inline fun <T> createShuttleWithSampleRateFallback(
    preferredMinBufferBytes: Int,
    create: (Int) -> T,
): Pair<T, Int> {
    var earlierFailure: Exception? = null
    for (sampleRate in shuttleSampleRateCandidates(preferredMinBufferBytes)) {
        try {
            return create(sampleRate) to sampleRate
        } catch (error: Exception) {
            val previous = earlierFailure
            if (previous != null) error.addSuppressed(previous)
            earlierFailure = error
        }
    }
    throw earlierFailure ?: IOException("No shuttle sample rate is available")
}

internal fun shuttleSourceGrainSeconds(signedRate: Float): Double =
    SHUTTLE_GRAIN_OUTPUT_SECONDS * shuttleAudibleSpeed(signedRate).toDouble()

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

internal data class ProgressiveWaveformSnapshot(
    val values: FloatArray,
    val builtCount: Int,
)

internal class ProgressiveWaveformAccumulator(
    bucketCount: Int,
    targetPublishCount: Int = 32,
) {
    private val values = FloatArray(bucketCount.coerceAtLeast(0))
    private val publishStride = if (values.isEmpty()) 1 else
        ((values.size + targetPublishCount.coerceAtLeast(1) - 1) / targetPublishCount.coerceAtLeast(1))
            .coerceAtLeast(1)
    private var builtCount = 0
    private var lastPublishedCount = 0

    fun record(index: Int, magnitude: Float): ProgressiveWaveformSnapshot? {
        if (index !in values.indices) return null
        values[index] = magnitude.coerceIn(0f, 1f)
        builtCount = maxOf(builtCount, index + 1)
        if (builtCount < values.size && builtCount - lastPublishedCount < publishStride) return null
        return snapshot()
    }

    fun finish(): ProgressiveWaveformSnapshot? =
        if (builtCount > lastPublishedCount) snapshot() else null

    private fun snapshot(): ProgressiveWaveformSnapshot {
        lastPublishedCount = builtCount
        return ProgressiveWaveformSnapshot(values.copyOf(), builtCount)
    }
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

internal class PlaybackHeadFrameCounter {
    private var wraps = 0L
    private var previous = 0L
    private var initialized = false

    fun update(playbackHeadPosition: Int): Long {
        val current = playbackHeadPosition.toLong() and 0xffff_ffffL
        if (initialized && current < previous) wraps += 1L shl 32
        previous = current
        initialized = true
        return wraps + current
    }
}

internal inline fun closeShuttleSourceReportingFailure(
    close: () -> Unit,
    onFailure: (Exception) -> Unit,
) {
    try {
        close()
    } catch (error: Exception) {
        runCatching { onFailure(error) }
    }
}

internal fun executeIfAccepted(executor: Executor, task: Runnable): Boolean = try {
    executor.execute(task)
    true
} catch (_: RejectedExecutionException) {
    false
}

internal fun previewPlaybackDrainStalled(
    lastAdvanceMillis: Long,
    nowMillis: Long,
    timeoutMillis: Long = PREVIEW_DRAIN_STALL_TIMEOUT_MILLIS,
): Boolean = timeoutMillis > 0L &&
    nowMillis >= lastAdvanceMillis &&
    nowMillis - lastAdvanceMillis >= timeoutMillis

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

internal inline fun releasePreviewTrackReportingFailure(
    release: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    try {
        release()
    } catch (error: Throwable) {
        runCatching { onFailure(error) }
    }
}

internal inline fun startPreviewReleaseFallbackReportingFailure(
    start: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    try {
        start()
    } catch (error: Throwable) {
        runCatching { onFailure(error) }
    }
}

internal class TimelineAudioPreviewController(
    private val onTrackReleaseFailure: (Throwable) -> Unit = {},
) : Closeable {
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

    private interface ShuttlePcmSource : Closeable {
        val durationSeconds: Double
        fun readPcm16Mono(
            startSeconds: Double,
            endSeconds: Double,
            targetSampleRate: Int,
        ): ByteArray?
    }

    private class TimelineShuttlePcmSource(
        private val snapshot: ReverbService.TimelineSnapshot,
    ) : ShuttlePcmSource {
        override val durationSeconds: Double get() = snapshot.durationSeconds

        override fun readPcm16Mono(
            startSeconds: Double,
            endSeconds: Double,
            targetSampleRate: Int,
        ): ByteArray? {
            val lease = snapshot.acquireRange(startSeconds, endSeconds) ?: return null
            val expectedBytes = ((endSeconds - startSeconds).coerceAtLeast(0.0) *
                targetSampleRate.toDouble() * 2.0)
                .roundToInt()
                .coerceAtLeast(0)
            val raw = ByteArrayOutputStream(expectedBytes)
            try {
                lease.readNormalized(
                    targetSampleRate = targetSampleRate,
                    targetChannelCount = 1,
                    targetSampleFormat = PcmSampleFormat.PCM_16,
                ) { array, offset, count ->
                    raw.write(array, offset, count)
                    count
                }
            } finally {
                snapshot.releaseChildRangeBestEffort(lease)
            }
            return raw.toByteArray().takeIf { it.isNotEmpty() }
        }

        override fun close() = Unit
    }

    private class RecordingShuttlePcmSource(
        context: Context,
        recording: RecordingEntity,
    ) : ShuttlePcmSource {
        private val reader = RecordingPcm16MonoReader.open(context, recording)
        override val durationSeconds: Double get() = reader.durationSeconds

        override fun readPcm16Mono(
            startSeconds: Double,
            endSeconds: Double,
            targetSampleRate: Int,
        ): ByteArray? =
            reader.readRange(startSeconds, endSeconds, targetSampleRate)
                .takeIf { it.isNotEmpty() }

        override fun close() = reader.close()
    }

    private data class ShuttleCommand(val positionSeconds: Double, val rate: Float)

    private data class ShuttlePcmWindow(
        val startSeconds: Double,
        val endSeconds: Double,
        val pcm: ByteArray,
    )

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
        untilSeconds: Double = snapshot.durationSeconds,
        onProgress: (Double) -> Unit,
        onFinished: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (closed) return
        cancelCurrent()
        val token = generation.incrementAndGet()
        val start = fromSeconds.coerceIn(0.0, snapshot.durationSeconds)
        val end = untilSeconds.coerceIn(start, snapshot.durationSeconds)
        if (end <= start) {
            postIfCurrent(token, onFinished)
            return
        }
        enqueueLatest {
            stream(
                token = token,
                snapshot = snapshot,
                startSeconds = start,
                endSeconds = end,
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
        startShuttleInternal(
            atSeconds = atSeconds.coerceIn(0.0, snapshot.durationSeconds),
            rate = rate,
            sourceFactory = { TimelineShuttlePcmSource(snapshot) },
            onStarted = null,
            onFailureAfterStart = null,
            onSourceCloseFailure = {},
        )
    }

    fun startRecordingShuttle(
        context: Context,
        recording: RecordingEntity,
        atSeconds: Double,
        rate: Float,
        onStarted: () -> Unit = {},
        onFailureAfterStart: () -> Unit = {},
        onSourceCloseFailure: ((Exception) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        startShuttleInternal(
            // Catalog duration can lag MediaPlayer/the opened WAV. The source thread owns the
            // authoritative upper bound, avoiding a wrong first grain then an audible jump.
            atSeconds = sanitizedShuttlePositionSeconds(atSeconds),
            rate = rate,
            sourceFactory = { RecordingShuttlePcmSource(appContext, recording) },
            onStarted = onStarted,
            onFailureAfterStart = onFailureAfterStart,
            onSourceCloseFailure = onSourceCloseFailure ?: { error ->
                Log.e("ReverbRangeAudio", "Saved recording shuttle source close failed", error)
                AppFeedbackCenter.post(
                    appContext.getString(R.string.recording_read_cleanup_failed),
                    FeedbackTone.ERROR,
                )
            },
        )
    }

    private fun startShuttleInternal(
        atSeconds: Double,
        rate: Float,
        sourceFactory: () -> ShuttlePcmSource,
        onStarted: (() -> Unit)?,
        onFailureAfterStart: (() -> Unit)?,
        onSourceCloseFailure: (Exception) -> Unit,
    ) {
        if (closed) return
        cancelCurrent()
        val token = generation.incrementAndGet()
        activeShuttleToken = token
        shuttleCommand.set(
            ShuttleCommand(
                positionSeconds = sanitizedShuttlePositionSeconds(atSeconds),
                rate = sanitizedShuttleSourceRate(rate),
            ),
        )
        enqueueLatest {
            if (!isCurrent(token)) return@enqueueLatest
            val source = try {
                sourceFactory()
            } catch (_: Exception) {
                if (activeShuttleToken == token) activeShuttleToken = 0L
                return@enqueueLatest
            }
            shuttle(
                token = token,
                source = source,
                onStarted = onStarted,
                onFailureAfterStart = onFailureAfterStart,
                onSourceCloseFailure = onSourceCloseFailure,
            )
        }
    }

    fun updateShuttle(atSeconds: Double, rate: Float) {
        val token = activeShuttleToken
        if (closed || token == 0L || generation.get() != token) return
        shuttleCommand.set(
            ShuttleCommand(
                positionSeconds = sanitizedShuttlePositionSeconds(atSeconds),
                rate = sanitizedShuttleSourceRate(rate),
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
        // A late gesture can race disposal after observing closed=false. Executor rejection is
        // therefore a normal lifecycle boundary, not an exception that should escape to UI.
        executeIfAccepted(executor, Runnable(block))
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
            val releaseQueued = !releaseExecutor.isShutdown && executeIfAccepted(
                releaseExecutor,
                Runnable { releaseTrackOnce(track) },
            )
            if (!releaseQueued) {
                // shutdown can race the isShutdown observation. Never fall back to releasing
                // AudioTrack on the UI caller: AudioFlinger teardown can block. A one-shot
                // daemon keeps this rare shutdown race off-thread; duplicate release is guarded.
                startPreviewReleaseFallbackReportingFailure(
                    start = {
                        Thread(
                            { releaseTrackOnce(track) },
                            "Reverb-range-preview-release-fallback",
                        ).apply { isDaemon = true }.start()
                    },
                    onFailure = { error ->
                        Log.e("ReverbRangeAudio", "Unable to start AudioTrack release fallback", error)
                        onTrackReleaseFailure(error)
                    },
                )
            }
        }
    }

    private fun shuttle(
        token: Long,
        source: ShuttlePcmSource,
        onStarted: (() -> Unit)?,
        onFailureAfterStart: (() -> Unit)?,
        onSourceCloseFailure: (Exception) -> Unit,
    ) {
        var track: AudioTrack? = null
        var startedCallbackSent = false
        try {
            checkCurrent(token)
            val preferredMinBufferBytes = runCatching {
                AudioTrack.getMinBufferSize(
                    SHUTTLE_PREFERRED_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            }.getOrDefault(0)
            val (shuttleTrack, shuttleSampleRate) = createShuttleWithSampleRateFallback(
                preferredMinBufferBytes = preferredMinBufferBytes,
            ) { sampleRate ->
                val grainBufferBytes = (
                    SHUTTLE_GRAIN_OUTPUT_SECONDS * sampleRate.toDouble() * 2.0
                ).roundToInt()
                createTrack(
                    volume = 0.82f,
                    minimumBufferBytes = grainBufferBytes,
                    lowLatency = true,
                    sampleRate = sampleRate,
                )
            }
            track = shuttleTrack
            synchronized(trackLock) {
                checkCurrent(token)
                activeTrack = shuttleTrack
            }
            shuttleTrack.play()
            val overlapFrames = (SHUTTLE_CROSSFADE_SECONDS * shuttleSampleRate.toDouble())
                .roundToInt()
                .coerceAtLeast(1)
            var previousTail = ByteArray(0)
            var previousAnchorSeconds: Double? = null
            var previousDirection = 0
            var sourceWindow: ShuttlePcmWindow? = null

            fun write(bytes: ByteArray) {
                if (bytes.isEmpty()) return
                var offset = 0
                while (offset < bytes.size) {
                    checkCurrent(token)
                    val written = shuttleTrack.write(
                        bytes,
                        offset,
                        bytes.size - offset,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (written <= 0) throw IOException("Audio shuttle write failed: $written")
                    offset += written
                    // Do not pause the normal Library player until shuttle audio is actually
                    // queued. Opening/normalizing a saved WAV can block; firing this callback
                    // before the first successful write creates a silent handoff gap on slow I/O.
                    if (!startedCallbackSent) {
                        startedCallbackSent = true
                        if (onStarted != null) postIfCurrent(token, onStarted)
                    }
                }
            }

            fun finishPendingTail() {
                if (previousTail.isEmpty()) return
                write(fadeOutShuttlePcm16Mono(previousTail))
                previousTail = ByteArray(0)
            }

            fun readGrain(startSeconds: Double, endSeconds: Double, anchorSeconds: Double): ByteArray? {
                val toleranceSeconds = 0.5 / shuttleSampleRate.toDouble()
                var window = sourceWindow
                if (
                    window == null ||
                    startSeconds < window.startSeconds - toleranceSeconds ||
                    endSeconds > window.endSeconds + toleranceSeconds
                ) {
                    val cacheStart = (anchorSeconds - SHUTTLE_CACHE_HALF_SPAN_SECONDS)
                        .coerceAtLeast(0.0)
                    val cacheEnd = (anchorSeconds + SHUTTLE_CACHE_HALF_SPAN_SECONDS)
                        .coerceAtMost(source.durationSeconds)
                    checkCurrent(token)
                    val pcm = source.readPcm16Mono(
                        cacheStart,
                        cacheEnd,
                        shuttleSampleRate,
                    ) ?: return null
                    checkCurrent(token)
                    val actualDuration = pcm.size.toDouble() /
                        (shuttleSampleRate.toDouble() * 2.0)
                    window = ShuttlePcmWindow(
                        startSeconds = cacheStart,
                        endSeconds = (cacheStart + actualDuration).coerceAtMost(source.durationSeconds),
                        pcm = pcm,
                    )
                    sourceWindow = window
                }
                val selected = sliceShuttlePcm16Mono(
                    input = window.pcm,
                    windowStartSeconds = window.startSeconds,
                    rangeStartSeconds = startSeconds,
                    rangeEndSeconds = endSeconds,
                    sampleRate = shuttleSampleRate,
                )
                return selected.takeIf { it.isNotEmpty() }
            }

            while (isCurrent(token)) {
                val command = shuttleCommand.get() ?: break
                val rate = command.rate
                val magnitude = abs(rate)
                if (magnitude < SHUTTLE_MIN_SOURCE_RATE) {
                    finishPendingTail()
                    previousAnchorSeconds = null
                    previousDirection = 0
                    Thread.sleep(8L)
                    continue
                }

                val direction = if (rate >= 0f) 1 else -1
                val target = command.positionSeconds.coerceIn(0.0, source.durationSeconds)
                if (previousDirection != 0 && previousDirection != direction) {
                    // Crossing direction at arbitrary waveform phase sounds like chorus when the
                    // two unrelated tails are mixed. End the old grain cleanly and restart from
                    // the new target instead.
                    finishPendingTail()
                    previousAnchorSeconds = null
                }
                previousDirection = direction
                if (shuttleSourceRequiresReanchor(previousAnchorSeconds, target, rate)) {
                    // A distant target is unrelated waveform phase. Fade the old tail before
                    // re-anchoring instead of crossfading two far-apart regions into chorus.
                    finishPendingTail()
                    previousAnchorSeconds = null
                }

                val anchor = nextShuttleSourceAnchorSeconds(previousAnchorSeconds, target, rate)
                if (anchor == null) {
                    // Source motion below one output hop is intentionally sparse. Repeating a
                    // mostly identical grain is the buzzing/comb-filter artifact this
                    // path must avoid. Finish the pending grain and wait for real source motion.
                    finishPendingTail()
                    Thread.sleep(8L)
                    continue
                }

                val sourceDuration = shuttleSourceGrainSeconds(rate)
                val start = if (rate >= 0f) anchor else (anchor - sourceDuration).coerceAtLeast(0.0)
                val end = if (rate >= 0f) {
                    (anchor + sourceDuration).coerceAtMost(source.durationSeconds)
                } else {
                    anchor
                }
                if (end <= start) {
                    finishPendingTail()
                    previousAnchorSeconds = null
                    Thread.sleep(8L)
                    continue
                }

                val grain = readGrain(start, end, anchor)
                if (grain == null) {
                    finishPendingTail()
                    previousAnchorSeconds = null
                    sourceWindow = null
                    Thread.sleep(8L)
                    continue
                }
                val accelerated = transformShuttlePcm16Mono(grain, rate)
                val acceleratedFrames = accelerated.size / 2
                if (acceleratedFrames <= 2) {
                    finishPendingTail()
                    write(windowShuttlePcm16Mono(accelerated, acceleratedFrames / 2))
                    previousAnchorSeconds = anchor
                    continue
                }
                // Keep the output overlap bounded when a boundary truncates a sped-up grain.
                // Full grains still use the canonical 8 ms overlap; tiny edge grains reserve
                // at most one quarter of their frames so they cannot disappear into the tail.
                val grainOverlapFrames = minOf(
                    overlapFrames,
                    (acceleratedFrames / 4).coerceAtLeast(1),
                )
                val blended = crossfadeShuttlePcm16Mono(
                    previousTail,
                    accelerated,
                    grainOverlapFrames,
                )
                previousTail = blended.tail
                previousAnchorSeconds = anchor
                write(blended.output)
            }
        } catch (_: PreviewCancelled) {
            Unit
        } catch (_: Exception) {
            // Cancellation/newer gestures stay silent. A real failure after AudioTrack took
            // audible ownership must hand control back to the caller so it can restore its
            // already-open player instead of leaving the gesture silent until finger-up.
            if (startedCallbackSent && onFailureAfterStart != null && isCurrent(token)) {
                postIfCurrent(token, onFailureAfterStart)
            }
        } finally {
            if (activeShuttleToken == token) activeShuttleToken = 0L
            if (track != null) {
                synchronized(trackLock) {
                    if (activeTrack === track) activeTrack = null
                }
                releaseTrackOnce(track)
            }
            closeShuttleSourceReportingFailure(
                close = source::close,
                onFailure = onSourceCloseFailure,
            )
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
            val playbackHead = PlaybackHeadFrameCounter()

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
                            val played = playbackHead.update(track.playbackHeadPosition)
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

            val targetFrames = (readResult.durationSeconds * PREVIEW_SAMPLE_RATE.toDouble())
                .roundToLong()
                .coerceAtLeast(0L)
            var lastPlayedFrames = -1L
            var lastPlaybackAdvanceAt = SystemClock.elapsedRealtime()
            while (true) {
                checkCurrent(token)
                val played = playbackHead.update(track.playbackHeadPosition)
                if (played >= targetFrames) break
                val now = SystemClock.elapsedRealtime()
                if (played > lastPlayedFrames) {
                    lastPlayedFrames = played
                    lastPlaybackAdvanceAt = now
                } else if (previewPlaybackDrainStalled(lastPlaybackAdvanceAt, now)) {
                    throw IOException("Audio preview playback stalled")
                }
                if (reportProgress) {
                    postIfCurrent(token) {
                        onProgress(
                            (startSeconds + played.toDouble() / PREVIEW_SAMPLE_RATE.toDouble())
                                .coerceAtMost(endSeconds),
                        )
                    }
                }
                Thread.sleep(16L)
            }
            checkCurrent(token)
            postIfCurrent(token, onFinished)
        } catch (_: PreviewCancelled) {
            Unit
        } catch (error: Exception) {
            if (isCurrent(token)) postIfCurrent(token) { onError(error) }
        } finally {
            snapshot.releaseChildRangeBestEffort(child)
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
        lowLatency: Boolean = false,
        sampleRate: Int = PREVIEW_SAMPLE_RATE,
    ): AudioTrack {
        val minBuffer = resolvePreviewAudioTrackBufferBytes(
            reportedMinBufferBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
            minimumBufferBytes = minimumBufferBytes,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer)
            .apply {
                if (lowLatency) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            .build()
        return configureOwnedResourceOrRelease(
            owner = track,
            release = { it.release() },
        ) { configured ->
            check(configured.state == AudioTrack.STATE_INITIALIZED) {
                "AudioTrack failed to initialize"
            }
            val result = configured.setVolume(volume.coerceIn(0f, 1f))
            if (result != AudioTrack.SUCCESS) {
                throw IOException("Unable to configure audio preview volume: $result")
            }
        }
    }

    private fun releaseTrackOnce(track: AudioTrack) {
        synchronized(releasedTracks) {
            if (releasedTracks.put(track, true) != null) return
        }
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        releasePreviewTrackReportingFailure(
            release = { track.release() },
            onFailure = { error ->
                Log.e("ReverbRangeAudio", "AudioTrack.release failed", error)
                onTrackReleaseFailure(error)
            },
        )
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

    private class PreviewCancelled : RuntimeException()
}
