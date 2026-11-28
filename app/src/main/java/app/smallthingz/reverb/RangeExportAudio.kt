package app.smallthingz.reverb

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Debug
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
import java.util.concurrent.Semaphore
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Registered ordinary authority retains only this native/control-worker handle. */
private class PreviewTrackScrubHandle(
    private val owner: PreviewTrackQuietOwner,
    private val track: AudioTrack,
    private val controlExecutor: Executor,
) : RecordingScrubQuietHandle {
    override fun retire() { owner.retire() }
    override fun requestQuiet() {
        owner.dispatchSilence {
            controlExecutor.execute { owner.silence({ track.pause() }, { track.flush() }) }
        }
    }
}

private const val PREVIEW_SAMPLE_RATE = 24_000
private const val SHUTTLE_PREFERRED_SAMPLE_RATE = 96_000
private const val SHUTTLE_FALLBACK_SAMPLE_RATE = 48_000
private const val PREVIEW_FRAMES_PER_BATCH = 1_200
private const val SCRUB_AUDITION_SECONDS = 0.14
private const val SCRUB_AUDITION_LEAD_SECONDS = 0.02
private const val PREVIEW_PROGRESS_INTERVAL_MILLIS = 32L
private const val PREVIEW_DRAIN_STALL_TIMEOUT_MILLIS = 2_000L
private const val SHUTTLE_GRAIN_OUTPUT_SECONDS = 0.024
private const val SHUTTLE_CROSSFADE_SECONDS = 0.008
private const val SHUTTLE_GRAIN_EDGE_SECONDS = 0.002
private const val SHUTTLE_SOURCE_HOP_SECONDS = SHUTTLE_GRAIN_OUTPUT_SECONDS - SHUTTLE_CROSSFADE_SECONDS
private const val SHUTTLE_MAX_TARGET_ERROR_SECONDS = SHUTTLE_SOURCE_HOP_SECONDS * 4.0
private const val SHUTTLE_UNITY_RATE_TOLERANCE = 0.0001f
private const val SHUTTLE_CROSSFADE_NANOS = 8_000_000L
private const val SHUTTLE_SOURCE_HOP_NANOS = 16_000_000L
private const val SHUTTLE_UNITY_PROJECTION_NANOS = 24_000_000L
private const val SHUTTLE_UNITY_WINDOW_SECONDS = 0.040
private const val SHUTTLE_UNITY_WINDOW_GUARD_SECONDS = 0.004
private const val SHUTTLE_UNITY_EDGE_SECONDS = 0.002
private const val SHUTTLE_TAIL_WAIT_NANOS = 20_000_000L
private const val SHUTTLE_RENDER_DEADLINE_NANOS = 16_000_000L
private const val SHUTTLE_PRESSURE_WINDOW_NANOS = 250_000_000L
private const val SHUTTLE_MAX_SOURCE_RATE = 4_096f
private const val SHUTTLE_MIN_AUDIBLE_SPEED = 1f
private const val SHUTTLE_MAX_AUDIBLE_SPEED = 3f
private const val SHUTTLE_PITCH_GAIN_PER_OCTAVE = 0.06f
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
    if (!targetAnchorSeconds.isFinite() || !signedRate.isFinite() || signedRate == 0f) {
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
    if (magnitude == 0f) return null
    val previous = previousAnchorSeconds?.takeIf { it.isFinite() } ?: return targetAnchorSeconds
    val direction = if (signedRate >= 0f) 1.0 else -1.0
    val distanceTowardTarget = (targetAnchorSeconds - previous) * direction
    // Below one output hop, replaying another grain would loop mostly the same waveform.
    // Wait for meaningful source movement instead of replaying the previous waveform.
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

/** Work in absolute output frames so adjacent blocks do not independently round a source hop. */
internal data class ShuttleUnityBlock(
    val firstFrame: Long,
    val endFrame: Long,
    val lowerFrame: Long,
    val upperFrame: Long,
    val reverse: Boolean,
    val fadeIn: Boolean,
) {
    val reachesBoundary: Boolean get() = if (reverse) firstFrame == lowerFrame else endFrame == upperFrame
}

internal fun shuttleUsesUnityStream(sourceSecondsPerWallSecond: Float): Boolean =
    abs(abs(sourceSecondsPerWallSecond) - 1f) <= SHUTTLE_UNITY_RATE_TOLERANCE

/** The two source hops can join while their difference still fits within the audible overlap. */
internal fun shuttleUsesContinuousGrains(rate: Float): Boolean {
    if (!rate.isFinite()) return false
    val pitch = shuttleAudibleSpeed(rate).toDouble()
    val hopDifference = abs(abs(rate).toDouble() - pitch) * SHUTTLE_SOURCE_HOP_SECONDS
    return hopDifference <= pitch * SHUTTLE_CROSSFADE_SECONDS
}

internal class ShuttleUnityMode {
    private var continuousGrainsActive = false
    private var unitySinceNanos: Long? = null

    fun usesUnityStream(rate: Float, observedNanos: Long,
                        continuousGrains: Boolean = shuttleUsesContinuousGrains(rate)): Boolean {
        if (!shuttleUsesUnityStream(rate)) {
            continuousGrainsActive = continuousGrains
            unitySinceNanos = null
            return false
        }
        if (!continuousGrainsActive) return true
        val firstUnity = unitySinceNanos ?: observedNanos.also { unitySinceNanos = it }
        if (observedNanos - firstUnity < SHUTTLE_SOURCE_HOP_NANOS) return false
        continuousGrainsActive = false
        unitySinceNanos = null
        return true
    }
}

private fun shuttleSourceOverlapSeconds(rate: Float): Double =
    SHUTTLE_CROSSFADE_SECONDS * shuttleAudibleSpeed(rate)

/** At slow rates, sample the gesture only once one source overlap of new material is available. */
internal fun nextContinuousShuttleGrainAnchorSeconds(previous: Double?, target: Double, rate: Float): Double? {
    if (previous == null) return target
    val magnitude = abs(rate.toDouble())
    val direction = if (rate < 0f) -1.0 else 1.0
    val sourceHop = maxOf(SHUTTLE_SOURCE_HOP_SECONDS * magnitude, shuttleSourceOverlapSeconds(rate))
    val directedAdvance = (target - previous) * direction
    val requiredAdvance = sourceHop - SHUTTLE_CROSSFADE_SECONDS * magnitude
    return if (directedAdvance + 1e-9 >= requiredAdvance) previous + direction * sourceHop else null
}

internal fun sparseShuttleWaitExceedsOverlap(previous: Double?, target: Double, rate: Float): Boolean {
    if (previous == null) return false
    val direction = if (rate < 0f) -1.0 else 1.0
    val progress = ((target - previous) * direction).coerceAtLeast(0.0)
    // The scheduler may render one overlap ahead. Settle only if even with that
    // lead, the next meaningfully different source still needs another overlap.
    return shuttleSourceOverlapSeconds(rate) - progress > 2.0 * SHUTTLE_CROSSFADE_SECONDS * abs(rate.toDouble())
}

/** Only project the last observed gesture briefly. A stalled UI cannot drive open-loop audio. */
internal fun shuttleUnityTargetSeconds(position: Double, rate: Float, commandNanos: Long, nowNanos: Long): Double {
    val elapsedNanos = (nowNanos - commandNanos).coerceIn(0L, SHUTTLE_UNITY_PROJECTION_NANOS)
    return position + elapsedNanos / 1_000_000_000.0 * rate
}

internal class ShuttleUnityCursor(private val sampleRate: Int, private val reverse: Boolean) {
    private val direction = if (reverse) -1 else 1
    private val hop = (SHUTTLE_SOURCE_HOP_SECONDS * sampleRate).roundToInt().coerceAtLeast(1)
    private val prime = (SHUTTLE_GRAIN_OUTPUT_SECONDS * sampleRate).roundToInt().coerceAtLeast(1)
    private val reanchor = (SHUTTLE_MAX_TARGET_ERROR_SECONDS * sampleRate).roundToInt().coerceAtLeast(1)
    private val taper = (SHUTTLE_UNITY_EDGE_SECONDS * sampleRate).roundToInt()
    private var nextFrame: Long? = null
    val nextSeconds: Double? get() = nextFrame?.let { it.toDouble() / sampleRate }

    fun continueAt(sourceSeconds: Double) { nextFrame = (sourceSeconds * sampleRate).roundToLong() }

    fun heldTailStartSeconds(frames: Int): Double? =
        nextFrame?.let { (it - direction * frames.toLong()).toDouble() / sampleRate }

    fun next(targetSeconds: Double, lowerSeconds: Double, upperSeconds: Double,
             extraLookaheadFrames: Int = 0): ShuttleUnityBlock? {
        val lower = firstFullShuttleFrame(lowerSeconds, sampleRate)
        val upper = lastFullShuttleFrame(upperSeconds, sampleRate)
        if (upper <= lower) return null
        val target = (targetSeconds.coerceIn(lowerSeconds, upperSeconds) * sampleRate).roundToLong().coerceIn(lower, upper)
        val previous = nextFrame?.coerceIn(lower, upper)
        val fresh = previous == null || (target - previous) * direction > reanchor
        val edge = when {
            !fresh -> checkNotNull(previous)
            // An edited endpoint can be the target before its first expansion. Prime
            // once from its admitted side; subsequent expansions append only new frames.
            !reverse && target == upper -> (upper - prime).coerceAtLeast(lower)
            reverse && target == lower -> (lower + prime).coerceAtMost(upper)
            else -> target
        }
        // Keep a small amount ahead of the observed gesture. AudioTrack provides the native
        // backpressure, while this bound covers both an unusual native capacity and a stalled UI.
        if (!fresh && (edge - target) * direction > hop + extraLookaheadFrames) return null
        val remaining = if (reverse) edge - lower else upper - edge
        if (remaining == 0L) return null
        val blockLength = if (fresh) prime else hop
        // Give even a final fraction of a block room for the whole terminal taper.
        val count = if (remaining <= blockLength + taper) remaining else blockLength.toLong()
        if (!fresh && (edge - target) * direction + count > 2L * hop + extraLookaheadFrames) return null
        val end = edge + direction * count
        nextFrame = end
        return ShuttleUnityBlock(minOf(edge, end), maxOf(edge, end), lower, upper, reverse, fadeIn = fresh)
    }
}

/** PCM held back from AudioTrack so an uncertain endpoint can either continue or end cleanly. */
internal class ShuttleUnityTail(sampleRate: Int, private val channelCount: Int) {
    private val frameBytes = pcm16FrameBytes(channelCount)
    private val retainedBytes = (SHUTTLE_UNITY_EDGE_SECONDS * sampleRate).roundToInt() * frameBytes
    private var pending = ByteArray(0)
    private var appendedAt = 0L
    var overlapsNext = false
        private set
    var needsFadeIn = false
        private set

    fun seedOverlap(bytes: ByteArray, nowNanos: Long) {
        check(pending.isEmpty())
        pending = bytes
        needsFadeIn = false
        appendedAt = nowNanos
        overlapsNext = bytes.isNotEmpty()
    }

    fun append(packet: ByteArray, nowNanos: Long): ByteArray {
        val joined = if (overlapsNext) {
            blendShuttleEntrance(packet, pending, channelCount)
            packet
        } else if (pending.isEmpty()) packet else pending + packet
        overlapsNext = false
        needsFadeIn = false
        appendedAt = nowNanos
        val bodyBytes = (joined.size - retainedBytes).coerceAtLeast(0)
        pending = joined.copyOfRange(bodyBytes, joined.size)
        return joined.copyOf(bodyBytes)
    }

    fun finishWhenWaiting(nowNanos: Long, commandAgeNanos: Long): ByteArray =
        if (commandAgeNanos >= SHUTTLE_UNITY_PROJECTION_NANOS || nowNanos - appendedAt >= SHUTTLE_TAIL_WAIT_NANOS) finish()
        else ByteArray(0)

    fun finish(): ByteArray {
        val tail = takePending()
        if (tail.isEmpty()) return tail
        needsFadeIn = true
        return fadeOutShuttlePcm16(tail, channelCount)
    }

    fun expectEntranceFade() { needsFadeIn = true }

    fun takePending(): ByteArray = pending.also {
        pending = ByteArray(0)
        overlapsNext = false
    }
}

private fun blendShuttleEntrance(current: ByteArray, previous: ByteArray, channels: Int) {
    val frames = minOf(current.size, previous.size) / pcm16FrameBytes(channels)
    for (frame in 0 until frames) {
        val phase = (frame + 1).toFloat() / (frames + 1)
        val gain = phase * phase * (3f - 2f * phase)
        repeat(channels) { channel ->
            val sample = frame * channels + channel
            current.writePcm16(sample, (previous.readPcm16(sample) * (1f - gain) + current.readPcm16(sample) * gain).roundToInt())
        }
    }
}

/** One track epoch owns its pressure evidence and its bounded recovery choices. */
internal class ShuttleRenderBudget(sampleRate: Int) {
    private val lowWaterFrames = (SHUTTLE_UNITY_WINDOW_GUARD_SECONDS * sampleRate).roundToLong()
    private val extraFrames = (SHUTTLE_CROSSFADE_SECONDS * sampleRate).roundToInt()
    private var lastGrainPressure: Long? = null
    private var lastUnityPressure: Long? = null
    private var previousUnderruns: Int? = null
    var phaseAlignmentEnabled = true
        private set
    var extraUnityLookaheadFrames = 0
        private set

    fun observeGrainRender(nowNanos: Long, commandAgeNanos: Long, queuedFrames: Long, renderNanos: Long): Boolean {
        if (!phaseAlignmentEnabled) return false
        if (!fresh(commandAgeNanos)) {
            lastGrainPressure = null
            return false
        }
        if (queuedFrames !in 0L..lowWaterFrames || renderNanos < SHUTTLE_RENDER_DEADLINE_NANOS) return false
        phaseAlignmentEnabled = !repeatPressure(lastGrainPressure, nowNanos)
        lastGrainPressure = nowNanos
        return !phaseAlignmentEnabled
    }

    fun observeUnity(nowNanos: Long, commandAgeNanos: Long, queuedFrames: Long, underrunCount: Int): Boolean {
        if (extraUnityLookaheadFrames != 0) return false
        if (!fresh(commandAgeNanos)) {
            discardUnityPressure()
            return false
        }
        val previous = previousUnderruns
        previousUnderruns = underrunCount
        if (previous == null || underrunCount <= previous || queuedFrames !in 0L..lowWaterFrames) return false
        val increase = repeatPressure(lastUnityPressure, nowNanos)
        lastUnityPressure = nowNanos
        if (increase) extraUnityLookaheadFrames = extraFrames
        return increase
    }

    fun discardUnityPressure() {
        lastUnityPressure = null
        previousUnderruns = null
    }

    private fun fresh(commandAgeNanos: Long): Boolean = commandAgeNanos in 0L..SHUTTLE_UNITY_PROJECTION_NANOS

    private fun repeatPressure(previous: Long?, now: Long): Boolean =
        previous != null && now - previous in 0L..SHUTTLE_PRESSURE_WINDOW_NANOS
}

private fun firstFullShuttleFrame(seconds: Double, sampleRate: Int): Long {
    val frame = seconds * sampleRate
    return ceil(frame - 2 * Math.ulp(frame)).toLong()
}

private fun lastFullShuttleFrame(seconds: Double, sampleRate: Int): Long {
    val frame = seconds * sampleRate
    return floor(frame + 2 * Math.ulp(frame)).toLong()
}

/** One small, bounded verified window. PCM bytes already on the track are never revisited. */
internal class ShuttleUnityPcm(private val sampleRate: Int, private val channelCount: Int) {
    private data class Window(val start: Long, val end: Long, val lower: Long, val upper: Long, val pcm: ByteArray)
    private val frameBytes = pcm16FrameBytes(channelCount)
    private val lattice = (SHUTTLE_UNITY_WINDOW_SECONDS * sampleRate).roundToInt().coerceAtLeast(1)
    private val guard = (SHUTTLE_UNITY_WINDOW_GUARD_SECONDS * sampleRate).roundToInt()
    private val edgeFrames = (SHUTTLE_UNITY_EDGE_SECONDS * sampleRate).roundToInt()
    private var cached: Window? = null

    fun render(block: ShuttleUnityBlock, taperBoundary: Boolean = true,
               read: (startSeconds: Double, endSeconds: Double) -> ByteArray?): ByteArray {
        val window = cached?.takeIf {
            block.lowerFrame <= it.lower && block.upperFrame >= it.upper &&
                block.firstFrame >= it.start + (if (it.start == block.lowerFrame) 0 else guard) &&
                block.endFrame <= it.end - (if (it.end == block.upperFrame) 0 else guard)
        } ?: run {
            // 40 ms is an integral number of frames at the app's standard capture rates
            // and both shuttle rates. Padding keeps refill boundaries out of resampler edges.
            val start = (block.firstFrame / lattice * lattice - lattice).coerceAtLeast(block.lowerFrame)
            val end = (((block.endFrame + lattice - 1) / lattice + 1) * lattice).coerceAtMost(block.upperFrame)
            val pcm = read(start.toDouble() / sampleRate, end.toDouble() / sampleRate)
                ?: throw IOException("Shuttle PCM window is unavailable")
            if (pcm.size % frameBytes != 0 || pcm.size / frameBytes < block.endFrame - start) {
                throw IOException("Shuttle PCM window did not cover the admitted frames")
            }
            Window(start, minOf(end, start + pcm.size / frameBytes), block.lowerFrame, block.upperFrame, pcm)
                .also { cached = it }
        }
        val first = ((block.firstFrame - window.start) * frameBytes).toInt()
        val last = ((block.endFrame - window.start) * frameBytes).toInt()
        val output = window.pcm.copyOfRange(first, last)
        if (block.reverse) reverseShuttlePcm16InPlace(output, frameBytes)
        val frames = output.size / frameBytes

        fun taper(fromStart: Boolean) {
            val count = minOf(edgeFrames, frames)
            for (step in 0 until count) {
                val frame = if (fromStart) step else frames - 1 - step
                val phase = step.toFloat() / count
                val gain = phase * phase * (3f - 2f * phase)
                repeat(channelCount) { channel ->
                    val sample = frame * channelCount + channel
                    output.writePcm16(sample, (output.readPcm16(sample) * gain).roundToInt())
                }
            }
        }
        if (block.fadeIn) taper(fromStart = true)
        if (block.reachesBoundary && taperBoundary) taper(fromStart = false)
        return output
    }
}

private fun reverseShuttlePcm16InPlace(pcm: ByteArray, frameBytes: Int) {
    repeat(pcm.size / frameBytes / 2) { frame ->
        val left = frame * frameBytes
        val right = pcm.size - left - frameBytes
        repeat(frameBytes) { byte ->
            val saved = pcm[left + byte]
            pcm[left + byte] = pcm[right + byte]
            pcm[right + byte] = saved
        }
    }
}

internal fun readTimelineShuttlePcm16(
    snapshot: ReverbService.TimelineSnapshot,
    startSeconds: Double,
    endSeconds: Double,
    targetSampleRate: Int,
    channelCount: Int,
    checkAdmission: () -> Unit,
): ByteArray? {
    val lease = snapshot.acquireRange(startSeconds, endSeconds) ?: return null
    val expectedBytes = ((endSeconds - startSeconds).coerceAtLeast(0.0) *
        targetSampleRate.toDouble() * pcm16FrameBytes(channelCount))
        .roundToInt()
        .coerceAtLeast(0)
    val raw = ByteArrayOutputStream(expectedBytes)
    try {
        withPcmConversionAdmission(checkAdmission) {
            lease.readNormalized(
                targetSampleRate = targetSampleRate,
                targetChannelCount = channelCount,
                targetSampleFormat = PcmSampleFormat.PCM_16,
                maximumOutputFrames = Long.MAX_VALUE,
                allowOutsideRangeContext = false,
            ) { array, offset, count ->
                raw.write(array, offset, count)
                count
            }
        }
    } finally {
        snapshot.releaseChildRangeBestEffort(lease)
    }
    return raw.toByteArray().takeIf { it.isNotEmpty() }
}

/** Normalizes admitted native frames at their actual positions on the captured timeline. */
internal fun readTimelineUnityPcm16(
    snapshot: ReverbService.TimelineSnapshot,
    startSeconds: Double,
    endSeconds: Double,
    selectedStartSeconds: Double,
    selectedEndSeconds: Double,
    targetSampleRate: Int,
    channelCount: Int,
    checkAdmission: () -> Unit,
): ByteArray? = withPcmConversionAdmission(checkAdmission) {
    val firstOutput = (startSeconds * targetSampleRate).roundToLong()
    val endOutput = (endSeconds * targetSampleRate).roundToLong()
    if (endOutput <= firstOutput) return@withPcmConversionAdmission null
    val frameBytes = pcm16FrameBytes(channelCount)
    val output = ByteArrayOutputStream(Math.multiplyExact(Math.toIntExact(endOutput - firstOutput), frameBytes))
    val scratch = PcmNormalizer.SegmentScratch()
    snapshot.readShuttleSegments(startSeconds - SHUTTLE_UNITY_EDGE_SECONDS,
        endSeconds + SHUTTLE_UNITY_EDGE_SECONDS) { segments ->
        var index = 0
        var emittedUntil = firstOutput
        while (index < segments.size && emittedUntil < endOutput) {
            checkPcmConversionNotInterrupted()
            val first = index++
            while (index < segments.size && shuttleSegmentsAreContinuous(segments[index - 1], segments[index])) index++
            val firstSegment = segments[first]
            val rate = firstSegment.record.sampleRate
            val runEnd = minOf(endOutput, firstFullShuttleFrame(segments[index - 1].timelineEndSeconds, targetSampleRate))
            val runStart = maxOf(emittedUntil, firstFullShuttleFrame(firstSegment.timelineStartSeconds, targetSampleRate))
            if (runStart >= runEnd) continue
            check(runStart == emittedUntil) { "Captured shuttle range has a frame gap" }
            val step = rate.toDouble() / targetSampleRate
            val padSeconds = (maxOf(pcmResamplingRadius(step), 1) + 1).toDouble() / rate
            val supportStart = maxOf(selectedStartSeconds, runStart.toDouble() / targetSampleRate - padSeconds)
            val supportEnd = minOf(selectedEndSeconds, runEnd.toDouble() / targetSampleRate + padSeconds)
            val support = ArrayList<PersistentAudioChunkStore.Segment>()
            var firstSourceSeconds = 0.0
            for (part in first until index) {
                val segment = segments[part]
                val localStart = maxOf(0.0, supportStart - segment.timelineStartSeconds)
                val localEnd = minOf(supportEnd, segment.timelineEndSeconds) - segment.timelineStartSeconds
                if (localEnd <= localStart) continue
                val sourceStart = lastFullShuttleFrame(localStart, rate).coerceIn(0L, segment.frameCount)
                val sourceEnd = firstFullShuttleFrame(localEnd, rate).coerceIn(sourceStart, segment.frameCount)
                if (sourceEnd == sourceStart) continue
                if (support.isEmpty()) firstSourceSeconds = segment.timelineStartSeconds + sourceStart.toDouble() / rate
                support += segment.copy(startFrame = segment.startFrame + sourceStart, frameCount = sourceEnd - sourceStart)
            }
            check(support.isNotEmpty()) { "Captured shuttle range has no admitted PCM support" }
            var firstPosition = (runStart.toDouble() / targetSampleRate - firstSourceSeconds) * rate
            // Same-rate windows preserve whole PCM samples through floating-point division.
            if (step == 1.0 && abs(firstPosition - firstPosition.roundToLong()) < 1e-5) {
                firstPosition = firstPosition.roundToLong().toDouble()
            }
            val runFrames = runEnd - runStart
            val maximumBatchFrames = PcmNormalizer.DEFAULT_OUTPUT_BATCH_BYTES / frameBytes
            val batchBytes = minOf(runFrames, maximumBatchFrames.toLong()).toInt() * frameBytes
            PcmStreamNormalizer.normalize(
                support, runFrames, 0L, channelCount, PcmSampleFormat.PCM_16,
                batchBytes, { bytes, offset, count ->
                    output.write(bytes, offset, count)
                    count
                }, allowOutsideRangeContext = false, segmentScratch = scratch,
                outputGrid = PcmStreamNormalizer.OutputGrid(step, firstPosition),
            )
            emittedUntil = runEnd
        }
        check(emittedUntil == endOutput) { "Captured shuttle source does not cover the admitted output" }
        output.toByteArray()
    }
}

private fun shuttleSegmentsAreContinuous(
    previous: PersistentAudioChunkStore.Segment, next: PersistentAudioChunkStore.Segment,
): Boolean = next.continuesPreviousChunkAtAcquire && next.record.id == previous.record.id + 1u &&
    previous.record.sampleRate == next.record.sampleRate && previous.record.channelCount == next.record.channelCount &&
    previous.record.sampleFormat == next.record.sampleFormat && next.startFrame == 0L &&
    previous.startFrame + previous.frameCount == previous.payloadBytesAtAcquire / previous.record.frameBytes

private fun pcm16FrameBytes(channelCount: Int): Int {
    require(channelCount in 1..2)
    return channelCount * PcmSampleFormat.PCM_16.bytesPerSample
}

private fun playbackChannelMask(channelCount: Int): Int = when (channelCount) {
    1 -> AudioFormat.CHANNEL_OUT_MONO
    2 -> AudioFormat.CHANNEL_OUT_STEREO
    else -> throw IllegalArgumentException("Unsupported playback channel count: $channelCount")
}

internal fun sliceShuttlePcm16(
    input: ByteArray,
    windowStartSeconds: Double,
    rangeStartSeconds: Double,
    rangeEndSeconds: Double,
    sampleRate: Int,
    channelCount: Int = 1,
    previousTail: ByteArray? = null,
    signedRate: Float = 1f,
    selectedStartSeconds: Double = rangeStartSeconds,
    selectedEndSeconds: Double = rangeEndSeconds,
    onSourceSlice: ((Int, Int) -> Unit)? = null,
    maximumContinuousShiftFrames: Int? = null,
): ByteArray {
    val frameBytes = pcm16FrameBytes(channelCount)
    val frameCount = input.size / frameBytes
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
    val alignedStart = if (previousTail != null && endFrame > startFrame &&
        selectedStartSeconds.isFinite() && selectedEndSeconds.isFinite()
    ) {
        alignedShuttleStartFrame(
            window = input,
            channelCount = channelCount,
            nominalStart = startFrame,
            grainFrames = endFrame - startFrame,
            previousTail = previousTail,
            signedRate = signedRate,
            sampleRate = sampleRate,
            allowAlignment = rangeStartSeconds > selectedStartSeconds && rangeEndSeconds < selectedEndSeconds,
            selectedFirstFrame = ceil((selectedStartSeconds - windowStartSeconds) * sampleRate).toInt()
                .coerceIn(0, frameCount),
            selectedEndFrame = floor((selectedEndSeconds - windowStartSeconds) * sampleRate).toInt()
                .coerceIn(0, frameCount),
            maximumContinuousShiftFrames = maximumContinuousShiftFrames,
        )
    } else startFrame
    val alignedEnd = alignedStart + endFrame - startFrame
    onSourceSlice?.invoke(alignedStart, alignedEnd)
    return input.copyOfRange(alignedStart * frameBytes, alignedEnd * frameBytes)
}

/** The existing slice/transform pipeline, named for opt-in worker-stage diagnostics. */
internal fun renderShuttlePcm16(
    input: ByteArray,
    windowStartSeconds: Double,
    rangeStartSeconds: Double,
    rangeEndSeconds: Double,
    sampleRate: Int,
    channelCount: Int = 1,
    previousTail: ByteArray? = null,
    signedRate: Float = 1f,
    selectedStartSeconds: Double = rangeStartSeconds,
    selectedEndSeconds: Double = rangeEndSeconds,
    onSourceSlice: ((Int, Int) -> Unit)? = null,
    maximumContinuousShiftFrames: Int? = null,
): ByteArray = transformShuttlePcm16(
    sliceShuttlePcm16(input, windowStartSeconds, rangeStartSeconds, rangeEndSeconds,
        sampleRate, channelCount, previousTail, signedRate, selectedStartSeconds, selectedEndSeconds, onSourceSlice,
        maximumContinuousShiftFrames),
    signedRate, channelCount,
)

private fun ByteArray.readPcm16(sample: Int): Int {
    val index = sample * 2
    return ((this[index + 1].toInt() shl 8) or (this[index].toInt() and 0xff)).toShort().toInt()
}

private fun ByteArray.writePcm16(sample: Int, value: Int) {
    val index = sample * 2
    this[index] = (value and 0xff).toByte()
    this[index + 1] = ((value ushr 8) and 0xff).toByte()
}

internal fun windowShuttlePcm16(input: ByteArray, edgeFrames: Int, channelCount: Int = 1): ByteArray {
    val frameBytes = pcm16FrameBytes(channelCount)
    val frameCount = input.size / frameBytes
    if (frameCount <= 0) return ByteArray(0)
    val edge = minOf(edgeFrames.coerceAtLeast(0), (frameCount + 1) / 2)
    if (edge <= 0) return input.copyOf(frameCount * frameBytes)
    val output = input.copyOf(frameCount * frameBytes)
    val rampDenominator = (edge - 1).coerceAtLeast(1).toFloat()
    for (frame in 0 until frameCount) {
        val fromStart = (frame.toFloat() / rampDenominator).coerceIn(0f, 1f)
        val fromEnd = ((frameCount - 1 - frame).toFloat() / rampDenominator).coerceIn(0f, 1f)
        val phase = minOf(fromStart, fromEnd)
        val gain = phase * phase * (3f - 2f * phase)
        val firstSample = frame * channelCount
        for (channel in 0 until channelCount) {
            val sample = firstSample + channel
            val value = (output.readPcm16(sample) * gain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.writePcm16(sample, value)
        }
    }
    return output
}

internal fun fadeOutShuttlePcm16(input: ByteArray, channelCount: Int = 1, edgeFrames: Int = Int.MAX_VALUE): ByteArray {
    val frameBytes = pcm16FrameBytes(channelCount)
    val frameCount = input.size / frameBytes
    if (frameCount <= 0) return ByteArray(0)
    val output = input.copyOf(frameCount * frameBytes)
    val edge = edgeFrames.coerceIn(0, frameCount)
    val firstFadeFrame = frameCount - edge
    for (frame in firstFadeFrame until frameCount) {
        val t = if (edge == 1) 1f else (frame - firstFadeFrame).toFloat() / (edge - 1).toFloat()
        val fade = 1f - t * t * (3f - 2f * t)
        val firstSample = frame * channelCount
        for (channel in 0 until channelCount) {
            val sample = firstSample + channel
            val value = (output.readPcm16(sample) * fade).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.writePcm16(sample, value)
        }
    }
    return output
}

internal fun transformShuttlePcm16(input: ByteArray, signedRate: Float, channelCount: Int = 1): ByteArray {
    val frameBytes = pcm16FrameBytes(channelCount)
    val inputFrames = input.size / frameBytes
    if (inputFrames <= 0 || !signedRate.isFinite()) return ByteArray(0)
    val speed = shuttleAudibleSpeed(signedRate)
    val reverse = signedRate < 0f
    if (!reverse && speed <= 1.0001f) return input

    val outputFrames = (inputFrames.toFloat() / speed).roundToInt()
        .coerceIn(1, inputFrames)
    val output = ByteArray(outputFrames * frameBytes)

    fun readOriented(frame: Int, channel: Int): Float {
        val logicalFrame = frame.coerceIn(0, inputFrames - 1)
        val sourceFrame = if (reverse) inputFrames - 1 - logicalFrame else logicalFrame
        return input.readPcm16(sourceFrame * channelCount + channel).toFloat()
    }
    fun write(frame: Int, channel: Int, value: Float) = output.writePcm16(
        frame * channelCount + channel,
        value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()),
    )
    val sourceStep = if (outputFrames == 1) speed.toDouble() else
        (inputFrames - 1).toDouble() / (outputFrames - 1)
    ShuttlePcmNative.filter(input, 0, inputFrames, channelCount, reverse,
        outputFrames, outputFrames, sourceStep)?.let { samples ->
        for (frame in 0 until outputFrames) {
            if ((frame and 127) == 0) checkPcmConversionNotInterrupted()
            for (channel in 0 until channelCount) write(frame, channel, samples[frame * channelCount + channel])
        }
        checkPcmConversionNotInterrupted()
        return output
    }
    if (outputFrames == 1) {
        val converter = PcmRateConverter(inputFrames, speed.toDouble(), ::readOriented)
        for (channel in 0 until channelCount) {
            write(0, channel, converter.sample((inputFrames / 2).toDouble(), channel))
        }
        return output
    }
    val sourceSpan = (inputFrames - 1).toDouble()
    val outputSpan = (outputFrames - 1).toDouble()
    // Pitch compression, not source-head transport speed, owns the decimation ratio.
    val converter = PcmRateConverter(inputFrames, sourceSpan / outputSpan, ::readOriented)
    for (frame in 0 until outputFrames) {
        val sourcePosition = frame.toDouble() * sourceSpan / outputSpan
        for (channel in 0 until channelCount) {
            write(frame, channel, converter.sample(sourcePosition, channel))
        }
    }
    return output
}

/** The input is signed source seconds per wall second. The direction never affects pitch. */
internal fun shuttleAudibleSpeed(signedRate: Float): Float {
    val transportSpeed = if (signedRate.isFinite()) abs(signedRate) else 0f
    if (transportSpeed <= 1f) return SHUTTLE_MIN_AUDIBLE_SPEED
    return (1f + log2(transportSpeed) * SHUTTLE_PITCH_GAIN_PER_OCTAVE)
        .coerceAtMost(SHUTTLE_MAX_AUDIBLE_SPEED)
}

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

private data class ShuttleTrackRequest(
    val sampleRate: Int,
    val useLibraryOutput: Boolean = false,
    val audioSessionId: Int? = null,
)

internal fun <T> createShuttleWithSampleRateFallback(
    preferredMinBufferBytes: Int,
    onConfigurationReleaseFailure: (Throwable) -> Unit,
    create: (Int, (Throwable) -> Unit) -> T,
): Pair<T, Int> = createShuttleTrackWithFallback(preferredMinBufferBytes, onConfigurationReleaseFailure) { request, report ->
    create(request.sampleRate, report)
}

private inline fun <T> createShuttleTrackWithFallback(
    preferredMinBufferBytes: Int,
    noinline onConfigurationReleaseFailure: (Throwable) -> Unit,
    libraryPlaybackRate: Int? = null,
    libraryPlaybackAudioSessionId: Int? = null,
    create: (ShuttleTrackRequest, (Throwable) -> Unit) -> T,
): Pair<T, Int> {
    val requests = buildList {
        libraryPlaybackRate?.let { rate ->
            // Try the ordinary session on the matching output before the existing independent path.
            libraryPlaybackAudioSessionId?.let { add(ShuttleTrackRequest(rate, useLibraryOutput = true, audioSessionId = it)) }
            add(ShuttleTrackRequest(rate, useLibraryOutput = true))
        }
        shuttleSampleRateCandidates(preferredMinBufferBytes).forEach { add(ShuttleTrackRequest(it)) }
    }
    var earlierFailure: Exception? = null
    for (request in requests) {
        try {
            // Configuration rejection may fall back, but its owner's actual release failure
            // must be reported before a later success discards that primary exception.
            return create(request, onConfigurationReleaseFailure) to request.sampleRate
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

internal fun shuttleOverlapFrames(grainFrames: Int, canonicalOverlapFrames: Int): Int =
    minOf(canonicalOverlapFrames.coerceAtLeast(0), grainFrames.coerceAtLeast(0) / 3)

internal fun shuttleWaitFinishesPendingTail(signedRate: Float): Boolean =
    abs(signedRate) < SHUTTLE_MIN_AUDIBLE_SPEED

internal fun shuttleAnchorAfterGrain(
    anchorSeconds: Double,
    startSeconds: Double,
    endSeconds: Double,
    durationSeconds: Double,
    signedRate: Float,
): Double = when {
    signedRate >= 0f && endSeconds >= durationSeconds -> durationSeconds
    signedRate < 0f && startSeconds <= 0.0 -> 0.0
    else -> anchorSeconds
}

internal fun crossfadeShuttlePcm16(
    previousTail: ByteArray?,
    current: ByteArray,
    overlapFrames: Int,
    channelCount: Int = 1,
    entranceFrames: Int = overlapFrames,
): ShuttleCrossfadeResult {
    val frameBytes = pcm16FrameBytes(channelCount)
    val currentFrames = current.size / frameBytes
    if (currentFrames <= 0) return ShuttleCrossfadeResult(ByteArray(0), previousTail ?: ByteArray(0))
    val requestedOverlap = overlapFrames.coerceAtLeast(0)
    val tailFrames = minOf(requestedOverlap, currentFrames)
    val bodyFrames = currentFrames - tailFrames
    val previousFrames = (previousTail?.size ?: 0) / frameBytes
    val mixedFrames = minOf(previousFrames, bodyFrames, requestedOverlap)
    val output = ByteArray(bodyFrames * frameBytes)

    var outFrame = 0
    if (mixedFrames > 0 && previousTail != null) {
        repeat(mixedFrames) { index ->
            val t = (index + 1).toFloat() / (mixedFrames + 1).toFloat()
            val blend = t * t * (3f - 2f * t)
            for (channel in 0 until channelCount) {
                // The pending tail starts at the next source anchor. A truncated grain
                // must mix its beginning with that same beginning, not a later tail suffix.
                val previous = previousTail.readPcm16(index * channelCount + channel)
                val next = current.readPcm16(index * channelCount + channel)
                val mixed = (previous * (1f - blend) + next * blend)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.writePcm16(outFrame * channelCount + channel, mixed)
            }
            outFrame++
        }
    }
    if (previousFrames == 0 && entranceFrames > 0) {
        for (frame in 0 until minOf(entranceFrames, bodyFrames)) {
            val t = (frame + 1).toFloat() / (entranceFrames + 1).toFloat()
            val fade = t * t * (3f - 2f * t)
            for (channel in 0 until channelCount) {
                val value = current.readPcm16(frame * channelCount + channel)
                output.writePcm16(outFrame * channelCount + channel, (value * fade).roundToInt())
            }
            outFrame++
        }
    }
    current.copyInto(output, outFrame * frameBytes, outFrame * frameBytes, bodyFrames * frameBytes)
    val tail = if (tailFrames > 0) {
        current.copyOfRange(bodyFrames * frameBytes, currentFrames * frameBytes)
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



internal class TimelineAudioPreviewController(
    private val onTrackReleaseFailure: (Throwable) -> Unit = {},
    collectShuttleTimings: Boolean = false,
    private val shuttleNowNanos: () -> Long = System::nanoTime,
    private val onShuttleTargetWait: ((Float) -> Unit)? = null,
) : Closeable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private val shuttleTimings = if (collectShuttleTimings) ArrayDeque<PreviewShuttleTiming>() else null
    private var droppedShuttleTimings = 0

    /** Diagnostic reads remain available after cancellation/close; no native resource is retained. */
    fun drainShuttleTimings(): PreviewShuttleTimingBatch {
        val timings = shuttleTimings ?: return PreviewShuttleTimingBatch(emptyList(), 0)
        return synchronized(timings) {
            PreviewShuttleTimingBatch(timings.toList(), droppedShuttleTimings).also {
                timings.clear()
                droppedShuttleTimings = 0
            }
        }
    }

    private inline fun <T> timeShuttleStage(token: Long, stage: () -> PreviewShuttleStage, action: () -> T): T {
        val timings = shuttleTimings
        if (timings == null || token == 0L) return action()
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val cpuAt = Debug.threadCpuTimeNanos()
        var completed = false
        var outputBytes: Int? = null
        try {
            return action().also { result ->
                outputBytes = when (result) { is ByteArray -> result.size; is Int -> result; else -> null }
                completed = true
            }
        } finally {
            val record = PreviewShuttleTiming(token, stage(), startedAt,
                SystemClock.elapsedRealtimeNanos() - startedAt, Debug.threadCpuTimeNanos() - cpuAt,
                completed, isCurrent(token), outputBytes)
            synchronized(timings) {
                if (timings.size < 512) timings.addLast(record)
                else if (droppedShuttleTimings < Int.MAX_VALUE) droppedShuttleTimings++
            }
        }
    }
    private val playbackRuns = PreviewPlaybackRuns()
    val playbackActive: Boolean get() = !closed && playbackRuns.active(generation.get())
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
    private val controlExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Reverb-range-preview-control").apply { isDaemon = true }
    }
    private companion object {
        val trackSlots = Semaphore(3)
        val failedTrackReleases = java.util.concurrent.atomic.AtomicInteger()
    }
    private val trackLock = Any()
    private val trackQuietOwners = WeakHashMap<AudioTrack, PreviewTrackQuietOwner>()

    private fun quietOwner(track: AudioTrack): PreviewTrackQuietOwner = synchronized(trackQuietOwners) {
        trackQuietOwners.getOrPut(track) { PreviewTrackQuietOwner(previewQuietCoordinator) }
    }

    private interface ShuttlePcmSource : Closeable {
        val durationSeconds: Double
        val channelCount: Int
        val nativeSampleRate: Int? get() = null
        fun readPcm16(
            startSeconds: Double,
            endSeconds: Double,
            targetSampleRate: Int,
            selectedStartSeconds: Double,
            selectedEndSeconds: Double,
            checkAdmission: () -> Unit,
        ): ByteArray?

        fun unityBounds(start: Double, end: Double): Pair<Double, Double> = start to end

        fun readUnityPcm16(
            start: Double, end: Double, targetRate: Int, lower: Double, upper: Double, checkAdmission: () -> Unit,
        ): ByteArray? = readPcm16(start, end, targetRate, lower, upper, checkAdmission)
    }

    private class TimelineShuttlePcmSource(
        private val snapshot: ReverbService.TimelineSnapshot,
    ) : ShuttlePcmSource {
        override val durationSeconds: Double get() = snapshot.durationSeconds
        override val channelCount: Int = snapshot.channelCount

        override fun readPcm16(
            startSeconds: Double,
            endSeconds: Double,
            targetSampleRate: Int,
            selectedStartSeconds: Double,
            selectedEndSeconds: Double,
            checkAdmission: () -> Unit,
        ): ByteArray? = readTimelineShuttlePcm16(snapshot, startSeconds, endSeconds, targetSampleRate, channelCount, checkAdmission)

        override fun readUnityPcm16(
            start: Double, end: Double, targetRate: Int, lower: Double, upper: Double, checkAdmission: () -> Unit,
        ): ByteArray? = readTimelineUnityPcm16(snapshot, start, end, lower, upper, targetRate, channelCount, checkAdmission)

        override fun close() = Unit
    }

    private class RecordingShuttlePcmSource(
        private val reader: RecordingPcm16Reader,
        private val ownsReader: Boolean,
        private val trimRounding: RecordingFrameRounding,
    ) : ShuttlePcmSource {
        override val durationSeconds: Double get() = reader.durationSeconds
        override val channelCount: Int get() = reader.channelCount
        override val nativeSampleRate: Int get() = reader.layout.sampleRate

        override fun unityBounds(start: Double, end: Double): Pair<Double, Double> {
            val rate = reader.layout.sampleRate
            val selection = recordingShuttleFrameRange(start, end, rate, reader.layout.frameCount, trimRounding)
            val lower = maxOf(start, selection.first.toDouble() / rate)
            return lower to minOf(end, selection.endExclusive.toDouble() / rate).coerceAtLeast(lower)
        }

        override fun readPcm16(
            startSeconds: Double,
            endSeconds: Double,
            targetSampleRate: Int,
            selectedStartSeconds: Double,
            selectedEndSeconds: Double,
            checkAdmission: () -> Unit,
        ): ByteArray? =
            withPcmConversionAdmission(checkAdmission) {
                reader.readRange(startSeconds, endSeconds, targetSampleRate,
                    recordingShuttleFrameRange(selectedStartSeconds, selectedEndSeconds,
                        reader.layout.sampleRate, reader.layout.frameCount, trimRounding))
                    .takeIf { it.isNotEmpty() }
            }

        override fun close() { if (ownsReader) reader.close() }
    }

    private data class ShuttleCommand(
        val positionSeconds: Double,
        val rate: Float,
        val admission: ShuttlePlaybackAdmission,
        val observedNanos: Long,
    ) {
        val startSeconds: Double get() = admission.startSeconds
        val endSeconds: Double get() = admission.endSeconds
    }

    private interface PreviewPcmStream : Closeable {
        val sampleRate: Int
        val channelCount: Int
        val startSeconds: Double
        val endSeconds: Double
        fun read(write: (ByteArray, Int, Int) -> Int): Long
    }

    private var preparedRecording: RecordingEntity? = null
    private var preparedReader: RecordingPcm16Reader? = null
    private var preparedReaderCleanup: ((Throwable) -> Unit)? = null

    fun prepareRecording(
        context: Context,
        recording: RecordingEntity,
        onPrepared: (Int) -> Unit,
        onError: (Throwable) -> Unit,
        onSourceCloseFailure: (Throwable) -> Unit,
    ) {
        if (closed) return
        cancelCurrent()
        val token = generation.incrementAndGet()
        val appContext = context.applicationContext
        enqueueLatest {
            try {
                checkCurrent(token)
                if (preparedRecording?.let { samePlaybackRecording(it, recording) } != true) {
                    closePreparedReader()
                    preparedReader = RecordingPcm16Reader.open(appContext, recording, RecordingReadCleanup(onSourceCloseFailure))
                    preparedRecording = recording
                    preparedReaderCleanup = onSourceCloseFailure
                }
                val millis = (checkNotNull(preparedReader).durationSeconds * 1_000.0).toLong()
                    .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                postIfCurrent(token) { onPrepared(millis) }
            } catch (_: PreviewCancelled) {
                Unit
            } catch (error: Exception) {
                postIfCurrent(token) { onError(error) }
            }
        }
    }

    private fun closePreparedReader() {
        val reader = preparedReader
        val cleanup = preparedReaderCleanup ?: {}
        preparedReader = null
        preparedRecording = null
        preparedReaderCleanup = null
        runCleanupReportingThrowable({ reader?.close() }, cleanup)
    }

    fun playRecording(
        context: Context,
        recording: RecordingEntity,
        fromMillis: Int,
        untilMillis: Int,
        onProgress: (PreviewPlaybackSample) -> Unit,
        onStarted: () -> Unit,
        onFinished: () -> Unit,
        onError: (Throwable) -> Unit,
    ): Boolean {
        if (closed || untilMillis <= fromMillis) return false
        cancelCurrent()
        val token = generation.incrementAndGet()
        playbackRuns.start(token)
        val appContext = context.applicationContext
        return enqueueLatest {
            stream(token, sourceFactory = {
                check(preparedRecording?.let { samePlaybackRecording(it, recording) } == true)
                val reader = checkNotNull(preparedReader)
                val rounding = recordingTrimFrameRounding(getConfiguredOutputFormat(appContext), recording.displayName, reader.isFlac)
                val range = previewFrameRange(fromMillis, untilMillis, reader.layout.sampleRate, reader.layout.frameCount, rounding)
                check(range.frameCount > 0L) { "No audio available for preview" }
                object : PreviewPcmStream {
                    override val sampleRate = reader.layout.sampleRate
                    override val channelCount = reader.channelCount
                    override val startSeconds = range.first.toDouble() / sampleRate
                    override val endSeconds = range.endExclusive.toDouble() / sampleRate
                    override fun read(write: (ByteArray, Int, Int) -> Int): Long = streamBoundedPcmFrames(
                        // Source identity checks amortize over250ms; the sink still admits
                        // and writes at most50ms at a time, checking its epoch before each write.
                        range, pcm16FrameBytes(channelCount), (sampleRate / 4).coerceAtLeast(1),
                        { checkCurrent(token) }, reader::readFramesPcm16, write,
                    )
                    override fun close() = Unit
                }
            }, volume = 1f, samplePlaybackHead = true, onProgress = null,
                onStarted = onStarted, onFinished = onFinished, onError = onError,
                onClockSample = onProgress)
        }.also { accepted -> if (!accepted) playbackRuns.finish(token) }
    }

    private fun timelineStream(
        snapshot: ReverbService.TimelineSnapshot,
        start: Double,
        end: Double,
    ): PreviewPcmStream {
        val child = snapshot.acquireRange(start, end) ?: throw IOException("No audio available for preview")
        return try { object : PreviewPcmStream {
            override val sampleRate = PREVIEW_SAMPLE_RATE
            override val channelCount = child.maximumChannelCount()
            override val startSeconds = start
            override val endSeconds = end
            override fun read(write: (ByteArray, Int, Int) -> Int): Long = child.readNormalized(
                targetSampleRate = sampleRate, targetChannelCount = channelCount,
                targetSampleFormat = PcmSampleFormat.PCM_16,
                maximumOutputFrames = Long.MAX_VALUE,
                maximumOutputBatchBytes = PREVIEW_FRAMES_PER_BATCH * pcm16FrameBytes(channelCount),
                allowOutsideRangeContext = false,
                consumer = { array, offset, count -> write(array, offset, count) },
            ).sampleBytes / pcm16FrameBytes(channelCount)
            override fun close() = snapshot.releaseChildRangeBestEffort(child)
        } } catch (error: Throwable) {
            snapshot.releaseChildRangeBestEffort(child)
            throw error
        }
    }

    private data class ShuttlePcmWindow(
        val startSeconds: Double,
        val endSeconds: Double,
        val pcm: ByteArray,
    )

    private val shuttleCommand = AtomicReference<ShuttleCommand?>(null)
    private var restartShuttle: ((ShuttleCommand) -> Unit)? = null

    private val activeShuttleToken = AtomicLong(0L)

    @Volatile
    private var activeTrack: AudioTrack? = null
    private var activeTrackEpoch = 0L

    @Volatile
    private var closed = false

    /** Read on an observation worker: native reads share the terminal-release lifetime fence. */
    fun observeTrack(): PreviewTrackObservation? {
        val owned = synchronized(trackLock) { activeTrack?.let { it to activeTrackEpoch } } ?: return null
        val (track, epoch) = owned
        val owner = quietOwner(track)
        val sample = owner.read {
            PreviewTrackObservation(epoch, owner.observationIdentity,
                track.playbackHeadPosition.toLong() and 0xffff_ffffL, track.underrunCount, track.playState,
                track.sampleRate, track.channelCount, track.bufferSizeInFrames,
                track.bufferCapacityInFrames, track.audioSessionId,
                owner.acceptedWriteBytes / pcm16FrameBytes(track.channelCount),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) track.startThresholdInFrames else null)
        } ?: return null
        return sample.takeIf { isCurrent(epoch) && synchronized(trackLock) {
            activeTrack === track && activeTrackEpoch == epoch
        } }
    }

    fun play(
        snapshot: ReverbService.TimelineSnapshot,
        fromSeconds: Double,
        untilSeconds: Double = snapshot.durationSeconds,
        onProgress: ((Double) -> Unit)? = null,
        onStarted: () -> Unit,
        onFinished: () -> Unit,
        onError: (Throwable) -> Unit,
        onClockSample: ((PreviewPlaybackSample) -> Unit)? = null,
    ): Boolean {
        if (closed) return false
        cancelCurrent()
        val token = generation.incrementAndGet()
        val start = fromSeconds.coerceIn(0.0, snapshot.durationSeconds)
        val end = untilSeconds.coerceIn(start, snapshot.durationSeconds)
        if (end <= start) return false
        playbackRuns.start(token)
        return enqueueLatest {
            stream(
                token = token,
                sourceFactory = { timelineStream(snapshot, start, end) },
                volume = 1f,
                samplePlaybackHead = true,
                onProgress = onProgress,
                onStarted = onStarted,
                onFinished = onFinished,
                onError = onError,
                onClockSample = onClockSample,
            )
        }.also { accepted -> if (!accepted) playbackRuns.finish(token) }
    }

    fun audition(snapshot: ReverbService.TimelineSnapshot, atSeconds: Double,
                 fromSeconds: Double = 0.0, untilSeconds: Double = snapshot.durationSeconds) {
        if (closed) return
        cancelCurrent()
        val token = generation.incrementAndGet()
        val lower = fromSeconds.coerceIn(0.0, snapshot.durationSeconds)
        val upper = untilSeconds.coerceIn(lower, snapshot.durationSeconds)
        val center = atSeconds.coerceIn(lower, upper)
        val start = (center - SCRUB_AUDITION_LEAD_SECONDS).coerceAtLeast(lower)
        val end = (center + SCRUB_AUDITION_SECONDS).coerceAtMost(upper)
        if (end <= start) return
        enqueueLatest {
            stream(
                token = token,
                sourceFactory = { timelineStream(snapshot, start, end) },
                volume = 1f,
                samplePlaybackHead = false,
                onProgress = null,
                onStarted = {},
                onFinished = {},
                onError = null,
            )
        }
    }

    fun startShuttle(
        snapshot: ReverbService.TimelineSnapshot,
        atSeconds: Double,
        rate: Float,
        fromSeconds: Double = 0.0,
        untilSeconds: Double = snapshot.durationSeconds,
    ) {
        startShuttleInternal(
            atSeconds = atSeconds.coerceIn(0.0, snapshot.durationSeconds),
            rate = rate,
            fromSeconds = fromSeconds,
            untilSeconds = untilSeconds,
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
        onSourceCloseFailure: ((Throwable) -> Unit)? = null,
        fromSeconds: Double = 0.0,
        untilSeconds: Double = Double.POSITIVE_INFINITY,
        ordinaryAuthority: RecordingScrubAuthority? = null,
    ) {
        val appContext = context.applicationContext
        val reportSourceCloseFailure: (Throwable) -> Unit = onSourceCloseFailure ?: { error ->
            Log.e("ReverbRangeAudio", "Saved recording shuttle source close failed", error)
            RecordingIncidentStore.recordUnexpectedErrorInBackground(
                appContext,
                "Saved recording shuttle source cleanup",
                error,
            )
            AppFeedbackCenter.post(
                actionableErrorMessage(appContext, appContext.getString(R.string.recording_read_cleanup_failed), error),
                FeedbackTone.ERROR,
            )
        }
        val sourceCleanup = RecordingReadCleanup(reportSourceCloseFailure)
        startShuttleInternal(
            // Catalog duration can lag MediaPlayer/the opened WAV. The source thread owns the
            // authoritative upper bound, avoiding a wrong first grain then an audible jump.
            atSeconds = sanitizedShuttlePositionSeconds(atSeconds),
            rate = rate,
            fromSeconds = fromSeconds,
            untilSeconds = untilSeconds,
            sourceFactory = {
                // Read the current save policy on this admission, never from a cached preparation.
                val trimFormat = getConfiguredOutputFormat(appContext)
                val cached = preparedReader.takeIf { preparedRecording?.let { samePlaybackRecording(it, recording) } == true }
                val reader = cached ?: RecordingPcm16Reader.open(appContext, recording, sourceCleanup)
                RecordingShuttlePcmSource(reader, cached == null,
                    recordingTrimFrameRounding(trimFormat, recording.displayName, reader.isFlac))
            },
            onStarted = onStarted,
            onFailureAfterStart = onFailureAfterStart,
            onSourceCloseFailure = reportSourceCloseFailure,
            ordinaryAuthority = ordinaryAuthority,
        )
    }

    private fun startShuttleInternal(
        atSeconds: Double,
        rate: Float,
        fromSeconds: Double,
        untilSeconds: Double,
        sourceFactory: () -> ShuttlePcmSource,
        onStarted: (() -> Unit)?,
        onFailureAfterStart: (() -> Unit)?,
        onSourceCloseFailure: (Throwable) -> Unit,
        ordinaryAuthority: RecordingScrubAuthority? = null,
    ) {
        if (closed || ordinaryAuthority?.active == false) return
        cancelCurrent()
        val token = generation.incrementAndGet()
        activeShuttleToken.set(token)
        shuttleCommand.set(
            ShuttleCommand(
                positionSeconds = sanitizedShuttlePositionSeconds(atSeconds),
                rate = sanitizedShuttleSourceRate(rate),
                admission = ShuttlePlaybackAdmission(0.0, 0.0, 0).updated(
                    fromSeconds.coerceAtLeast(0.0), untilSeconds.coerceAtLeast(fromSeconds),
                    sanitizedShuttleSourceRate(rate),
                ),
                observedNanos = shuttleNowNanos(),
            ),
        )
        restartShuttle = { command ->
            startShuttleInternal(command.positionSeconds, command.rate, command.startSeconds, command.endSeconds,
                sourceFactory, onStarted, onFailureAfterStart, onSourceCloseFailure, ordinaryAuthority)
        }
        enqueueLatest {
            if (!isShuttleCurrent(token, ordinaryAuthority)) return@enqueueLatest
            val source = try {
                timeShuttleStage(token, { PreviewShuttleStage.SOURCE_FACTORY }) { sourceFactory() }
            } catch (error: Exception) {
                postShuttleFailureRecovery(token, ordinaryAuthority, onFailureAfterStart, error)
                activeShuttleToken.compareAndSet(token, 0L)
                return@enqueueLatest
            }
            shuttle(
                token = token,
                source = source,
                onStarted = onStarted,
                onFailureAfterStart = onFailureAfterStart,
                onSourceCloseFailure = onSourceCloseFailure,
                ordinaryAuthority = ordinaryAuthority,
            )
        }
    }

    fun updateShuttle(atSeconds: Double, rate: Float,
                      fromSeconds: Double = 0.0, untilSeconds: Double = Double.POSITIVE_INFINITY) {
        val token = activeShuttleToken.get()
        if (closed || token == 0L || generation.get() != token) return
        val previous = shuttleCommand.get()
        val boundedRate = sanitizedShuttleSourceRate(rate)
        val admission = (previous?.admission ?: ShuttlePlaybackAdmission(0.0, 0.0, 0)).updated(
            fromSeconds.coerceAtLeast(0.0), untilSeconds.coerceAtLeast(fromSeconds), boundedRate,
        )
        val next = ShuttleCommand(sanitizedShuttlePositionSeconds(atSeconds), boundedRate, admission, shuttleNowNanos())
        if (previous != null && admission.requiresRetirementOf(previous.admission)) {
            // Expanding the range preserves every queued source frame. Only exclusion or a
            // direction reversal (including through zero) retires the physical queue.
            // A blocked old write may return, but that track can never play again.
            restartShuttle?.invoke(next)
        } else shuttleCommand.set(next)
    }

    fun stopShuttle() {
        if (closed || activeShuttleToken.get() == 0L) return
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
        executeIfAccepted(executor, Runnable {
            closePreparedReader()
            executeIfAccepted(controlExecutor, Runnable { releaseExecutor.shutdown() })
            controlExecutor.shutdown()
        })
        executor.shutdown()
    }

    private fun enqueueLatest(block: () -> Unit): Boolean {
        executor.queue.clear()
        // A late gesture can race disposal after observing closed=false. Executor rejection is
        // therefore a normal lifecycle boundary, not an exception that should escape to UI.
        return executeIfAccepted(executor, Runnable(block))
    }

    private fun cancelCurrent(): Unit = timeShuttleStage(activeShuttleToken.get(), { PreviewShuttleStage.CANCEL_REQUEST }) {
        generation.incrementAndGet()
        playbackRuns.cancel()
        activeShuttleToken.set(0L)
        shuttleCommand.set(null)
        restartShuttle = null
        executor.queue.clear()
        val track = synchronized(trackLock) {
            activeTrack.also { track ->
                track?.let { quietOwner(it).retire() }
                activeTrack = null
                activeTrackEpoch = 0L
            }
        }
        if (track != null) {
            retireTrack(track)
        }
    }

    private fun retireTrack(track: AudioTrack) {
        val owner = quietOwner(track)
        owner.retire()
        if (!owner.claimCleanupDispatch()) return
        fun dispatch(executor: Executor, name: String, action: () -> Unit) {
            if (executeIfAccepted(executor, Runnable(action))) return
            runCleanupReportingThrowable(
                { Thread(action, name).apply { isDaemon = true }.start() },
                { error -> owner.cleanupRejected(); reportTrackReleaseFailure(error) },
            )
        }
        dispatch(controlExecutor, "Reverb-range-preview-control-fallback") {
            owner.silence({ track.pause() }, { track.flush() })
            dispatch(releaseExecutor, "Reverb-range-preview-release-fallback") { releaseTrackOnce(track) }
        }
    }

    private fun shuttle(
        token: Long,
        source: ShuttlePcmSource,
        onStarted: (() -> Unit)?,
        onFailureAfterStart: (() -> Unit)?,
        onSourceCloseFailure: (Throwable) -> Unit,
        ordinaryAuthority: RecordingScrubAuthority?,
    ) {
        var track: AudioTrack? = null
        var authorityHandle: PreviewTrackScrubHandle? = null
        var nativeScratchRun: Closeable? = null
        fun checkShuttleCurrent() {
            checkCurrent(token)
            if (ordinaryAuthority?.active == false) throw PreviewCancelled()
        }
        var startedCallbackSent = false
        var failure: Exception? = null
        try {
            checkShuttleCurrent()
            val channelCount = source.channelCount
            val frameBytes = pcm16FrameBytes(channelCount)
            // Match Library playback attributes and the source rate for common PCM formats.
            // A 96 kHz FAST request can select a mixer that cannot grant FAST at that rate.
            val libraryPlaybackRate = source.nativeSampleRate?.takeIf {
                ordinaryAuthority != null && (it == 44_100 || it == SHUTTLE_FALLBACK_SAMPLE_RATE)
            }
            val (shuttleTrack, shuttleSampleRate) = timeShuttleStage(token, { PreviewShuttleStage.CREATE_TRACK }) {
                val preferredMinBufferBytes = runCatching {
                    AudioTrack.getMinBufferSize(
                        SHUTTLE_PREFERRED_SAMPLE_RATE,
                        playbackChannelMask(channelCount),
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                }.getOrDefault(0)
                createShuttleTrackWithFallback(
                    preferredMinBufferBytes = preferredMinBufferBytes,
                    onConfigurationReleaseFailure = ::reportTrackReleaseFailure,
                    libraryPlaybackRate = libraryPlaybackRate,
                    libraryPlaybackAudioSessionId = ordinaryAuthority?.playbackAudioSessionId,
                ) { (sampleRate, useLibraryOutput, audioSessionId), onConfigurationReleaseFailure ->
                    val grainBufferBytes = (SHUTTLE_GRAIN_OUTPUT_SECONDS * sampleRate).roundToInt() * frameBytes
                    createTrack(
                        token = token,
                        volume = 1f,
                        channelCount = channelCount,
                        minimumBufferBytes = grainBufferBytes,
                        lowLatency = !useLibraryOutput,
                        sampleRate = sampleRate,
                        contentType = if (useLibraryOutput) AudioAttributes.CONTENT_TYPE_MUSIC
                            else AudioAttributes.CONTENT_TYPE_SPEECH,
                        onConfigurationReleaseFailure = onConfigurationReleaseFailure,
                        audioSessionId = audioSessionId,
                        checkAdmission = ::checkShuttleCurrent,
                    )
                }
            }
            track = shuttleTrack
            if (ordinaryAuthority != null) {
                val handle = PreviewTrackScrubHandle(quietOwner(shuttleTrack), shuttleTrack, controlExecutor)
                authorityHandle = handle
                if (!ordinaryAuthority.attach(handle)) throw PreviewCancelled()
            }
            checkShuttleCurrent()
            check(timeShuttleStage(token, { PreviewShuttleStage.QUIET_WAIT }) {
                runBlocking { previewQuietCoordinator.awaitQuiet() }
            }) { "Previous audio preview could not be silenced" }
            synchronized(trackLock) {
                checkShuttleCurrent()
                activeTrack = shuttleTrack
                activeTrackEpoch = token
            }
            nativeScratchRun = ShuttlePcmNative.openRun()
            val overlapFrames = (SHUTTLE_CROSSFADE_SECONDS * shuttleSampleRate.toDouble())
                .roundToInt()
                .coerceAtLeast(1)
            val grainEdgeFrames = (SHUTTLE_GRAIN_EDGE_SECONDS * shuttleSampleRate).roundToInt()
            var previousTail = ByteArray(0)
            var previousAnchorSeconds: Double? = null
            var grainTailAnchorSeconds: Double? = null
            var grainTailEndSeconds: Double? = null
            var settledGrainEdge: Double? = null
            var lastGrainWriteNanos: Long? = null
            var renderedGrainFirstSeconds = 0.0
            var renderedGrainSourceFrames = 0
            var previousDirection = 0
            var sourceWindow: ShuttlePcmWindow? = null
            var unityCursor: ShuttleUnityCursor? = null
            var unityPcm: ShuttleUnityPcm? = null
            val unityTail = ShuttleUnityTail(shuttleSampleRate, channelCount)
            val unityMode = ShuttleUnityMode()
            var lastReportedWait: ShuttleCommand? = null
            var lastWaitWasFresh = false
            val budget = ShuttleRenderBudget(shuttleSampleRate)
            val nativePlaybackHead = PlaybackHeadFrameCounter()
            var unityBlocksSinceObservation = 0
            var priorUnityBounds: Pair<Double, Double>? = null
            var admittedUnityBounds: Pair<Double, Double>? = null

            fun observeNativeQueue(action: (queuedFrames: Long, underruns: Int) -> Unit) {
                val owner = quietOwner(shuttleTrack)
                owner.read {
                    checkShuttleCurrent()
                    val head = nativePlaybackHead.update(shuttleTrack.playbackHeadPosition)
                    action((owner.acceptedWriteBytes / frameBytes - head).coerceAtLeast(0L), shuttleTrack.underrunCount)
                }
            }

            fun write(bytes: ByteArray) {
                if (bytes.isEmpty()) return
                var offset = 0
                while (offset < bytes.size) {
                    checkShuttleCurrent()
                    val written = timeShuttleStage(if (startedCallbackSent) 0L else token, { PreviewShuttleStage.FIRST_WRITE }) {
                        quietOwner(shuttleTrack).write {
                            checkShuttleCurrent()
                            shuttleTrack.write(bytes, offset, bytes.size - offset,
                                if (startedCallbackSent) AudioTrack.WRITE_BLOCKING else AudioTrack.WRITE_NON_BLOCKING)
                        }
                    } ?: throw PreviewCancelled()
                    if (written <= 0) throw IOException("Audio shuttle write failed: $written")
                    offset += written
                    // Do not pause the normal Library player until shuttle audio is actually
                    // queued. Opening/normalizing a saved WAV can block; firing this callback
                    // before the first successful write creates a silent handoff gap on slow I/O.
                    if (!startedCallbackSent) {
                        startedCallbackSent = true
                        // Prime one grain, then acknowledge the normal-player pause before
                        // this track can sound. A delayed UI callback cannot mix both owners.
                        if (onStarted != null) awaitPreviewPlaybackHandoff(
                            checkCurrent = ::checkShuttleCurrent,
                            post = { action -> mainHandler.post { action() } },
                            action = onStarted,
                        )
                        checkShuttleCurrent()
                        if (!quietOwner(shuttleTrack).play {
                                playPrimedPreviewTrack(
                                    Build.VERSION.SDK_INT, written / frameBytes, shuttleTrack::setBufferSizeInFrames,
                                ) {
                                    // Legacy buffer sizing can return after the scrub was retired.
                                    checkShuttleCurrent()
                                    shuttleTrack.play()
                                }
                            }) throw PreviewCancelled()
                    }
                }
            }

            fun finishPendingTail() {
                grainTailAnchorSeconds = null
                grainTailEndSeconds = null
                if (previousTail.isEmpty()) return
                write(fadeOutShuttlePcm16(previousTail, channelCount, grainEdgeFrames))
                previousTail = ByteArray(0)
            }

            fun renderGrain(startSeconds: Double, endSeconds: Double, anchorSeconds: Double,
                            lower: Double, upper: Double, rate: Float, continuousGrains: Boolean): ByteArray? {
                val toleranceSeconds = 0.5 / shuttleSampleRate.toDouble()
                var window = sourceWindow
                if (
                    window == null ||
                    startSeconds < window.startSeconds - toleranceSeconds ||
                    endSeconds > window.endSeconds + toleranceSeconds
                ) {
                    val requestedCacheStart = (anchorSeconds - SHUTTLE_CACHE_HALF_SPAN_SECONDS)
                        .coerceAtLeast(lower)
                    // Align the native-rate cache without losing support for this grain.
                    val cacheStart = if (source.nativeSampleRate == shuttleSampleRate) {
                        val firstFrame = maxOf(floor(requestedCacheStart * shuttleSampleRate),
                            ceil(lower * shuttleSampleRate))
                        val alignedStart = firstFrame / shuttleSampleRate
                        if (alignedStart in lower..startSeconds) alignedStart else requestedCacheStart
                    } else requestedCacheStart
                    val cacheEnd = (anchorSeconds + SHUTTLE_CACHE_HALF_SPAN_SECONDS)
                        .coerceAtMost(upper)
                    checkShuttleCurrent()
                    val pcm = timeShuttleStage(token, { PreviewShuttleStage.READ_NORMALIZE }) {
                        source.readPcm16(cacheStart, cacheEnd, shuttleSampleRate, lower, upper, ::checkShuttleCurrent)
                    } ?: return null
                    checkShuttleCurrent()
                    val actualDuration = pcm.size.toDouble() /
                        (shuttleSampleRate.toDouble() * frameBytes)
                    window = ShuttlePcmWindow(
                        startSeconds = cacheStart,
                        endSeconds = (cacheStart + actualDuration).coerceAtMost(source.durationSeconds),
                        pcm = pcm,
                    )
                    sourceWindow = window
                }
                val activeWindow = checkNotNull(window)
                val magnitude = abs(rate)
                val nominalEdge = if (rate >= 0f) startSeconds else endSeconds
                val retainedShift = grainTailAnchorSeconds?.let { tailEdge ->
                    val nominalFrame = ((nominalEdge - activeWindow.startSeconds) * shuttleSampleRate).roundToInt()
                    abs((tailEdge - activeWindow.startSeconds) * shuttleSampleRate - nominalFrame)
                } ?: 0.0
                val continuousShift = if (continuousGrains && (magnitude != 1f || retainedShift >= 0.5)) {
                    // A tighter hop at a new rate must still be able to reach the phase
                    // already in the unsubmitted tail. The search keeps its 4 ms hard cap.
                    val slip = abs(magnitude - shuttleAudibleSpeed(rate)) * SHUTTLE_SOURCE_HOP_SECONDS * shuttleSampleRate
                    ceil(maxOf(slip * 2, retainedShift)).toInt() + 2
                } else null
                val measure = budget.phaseAlignmentEnabled && (magnitude > 1f || continuousShift != null) &&
                    previousTail.isNotEmpty() && startSeconds > lower && endSeconds < upper
                val renderStarted = if (measure) shuttleNowNanos() else 0L
                val selected = timeShuttleStage(token, { PreviewShuttleStage.RENDER_GRAIN }) {
                    withPcmConversionAdmission(::checkShuttleCurrent) {
                        renderShuttlePcm16(
                            input = activeWindow.pcm,
                            windowStartSeconds = activeWindow.startSeconds,
                            rangeStartSeconds = startSeconds,
                            rangeEndSeconds = endSeconds,
                            sampleRate = shuttleSampleRate,
                            channelCount = channelCount,
                            previousTail = if (!budget.phaseAlignmentEnabled) null else previousTail,
                            signedRate = rate,
                            selectedStartSeconds = lower,
                            selectedEndSeconds = upper,
                            onSourceSlice = { first, end ->
                                val edge = if (rate >= 0f) first else end
                                renderedGrainFirstSeconds = activeWindow.startSeconds + edge.toDouble() / shuttleSampleRate
                                renderedGrainSourceFrames = end - first
                            },
                            maximumContinuousShiftFrames = continuousShift,
                        )
                    }
                }
                if (measure && startedCallbackSent) {
                    val now = shuttleNowNanos()
                    val renderNanos = now - renderStarted
                    val latest = shuttleCommand.get()
                    if (renderNanos >= SHUTTLE_RENDER_DEADLINE_NANOS && latest != null &&
                        (abs(latest.rate) > 1f || continuousShift != null && shuttleUsesContinuousGrains(latest.rate))) {
                        observeNativeQueue { frames, _ ->
                            budget.observeGrainRender(now, now - latest.observedNanos, frames, renderNanos)
                        }
                    }
                }
                return selected.takeIf { it.isNotEmpty() }
            }

            while (isShuttleCurrent(token, ordinaryAuthority)) {
                val command = shuttleCommand.get() ?: break
                val rate = command.rate
                val magnitude = abs(rate)
                val continuousGrains = shuttleUsesContinuousGrains(rate)
                val useUnityStream = unityMode.usesUnityStream(rate, command.observedNanos, continuousGrains)
                // The gesture already has a dead zone. Any reported nonzero rate can
                // audition once, then sparse playback waits for actual source movement.
                if (magnitude == 0f) {
                    finishPendingTail()
                    write(unityTail.finish())
                    previousAnchorSeconds = null
                    settledGrainEdge = null
                    lastGrainWriteNanos = null
                    unityCursor = null
                    unityPcm = null
                    budget.discardUnityPressure()
                    previousDirection = 0
                    Thread.sleep(8L)
                    continue
                }

                val direction = if (rate >= 0f) 1 else -1
                val lower = command.startSeconds.coerceIn(0.0, source.durationSeconds)
                val upper = command.endSeconds.coerceIn(lower, source.durationSeconds)
                val target = command.positionSeconds.coerceIn(lower, upper)
                if (previousDirection != 0 && previousDirection != direction) {
                    // Crossing direction at arbitrary waveform phase sounds like chorus when the
                    // two unrelated tails are mixed. End the old grain cleanly and restart from
                    // the new target instead.
                    finishPendingTail()
                    write(unityTail.finish())
                    previousAnchorSeconds = null
                    settledGrainEdge = null
                    lastGrainWriteNanos = null
                    unityCursor = null
                    unityPcm = null
                    budget.discardUnityPressure()
                }
                previousDirection = direction
                if (useUnityStream) {
                    val now = shuttleNowNanos()
                    val cursor = unityCursor ?: ShuttleUnityCursor(shuttleSampleRate, direction < 0).also {
                        val pendingOrigin = grainTailAnchorSeconds
                        if (pendingOrigin != null && previousTail.isNotEmpty()) {
                            it.continueAt(pendingOrigin)
                            unityTail.seedOverlap(previousTail, now)
                            previousTail = ByteArray(0)
                            grainTailAnchorSeconds = null
                            grainTailEndSeconds = null
                        } else {
                            finishPendingTail()
                            settledGrainEdge?.let { edge ->
                                it.continueAt(edge)
                                unityTail.expectEntranceFade()
                            }
                        }
                        settledGrainEdge = null
                        unityCursor = it
                        unityPcm = ShuttleUnityPcm(shuttleSampleRate, channelCount)
                        priorUnityBounds = null
                        sourceWindow = null
                    }
                    val commandAge = now - command.observedNanos
                    val priorBounds = priorUnityBounds
                    if (priorBounds == null || priorBounds.first != lower || priorBounds.second != upper) {
                        priorUnityBounds = lower to upper
                        admittedUnityBounds = source.unityBounds(lower, upper)
                    }
                    val projected = shuttleUnityTargetSeconds(target, rate, command.observedNanos, now)
                    val (admittedLower, admittedUpper) = checkNotNull(admittedUnityBounds)
                    val block = cursor.next(projected, admittedLower, admittedUpper, budget.extraUnityLookaheadFrames)
                    if (commandAge > SHUTTLE_UNITY_PROJECTION_NANOS || block?.fadeIn == true) {
                        budget.discardUnityPressure()
                        unityBlocksSinceObservation = 0
                    }
                    if (block == null) {
                        write(unityTail.finishWhenWaiting(now, commandAge))
                        Thread.sleep(if (commandAge > SHUTTLE_UNITY_PROJECTION_NANOS) 8L else 2L)
                        continue
                    }
                    if (block.fadeIn) write(unityTail.finish())
                    val audibleBlock = if (unityTail.needsFadeIn && !block.fadeIn) block.copy(fadeIn = true) else block
                    val packet = timeShuttleStage(token, { PreviewShuttleStage.RENDER_GRAIN }) {
                        checkNotNull(unityPcm).render(audibleBlock, taperBoundary = false) { start, end ->
                            checkShuttleCurrent()
                            timeShuttleStage(token, { PreviewShuttleStage.READ_NORMALIZE }) {
                                source.readUnityPcm16(start, end, shuttleSampleRate, lower, upper, ::checkShuttleCurrent)
                            }
                        }
                    }
                    if (startedCallbackSent && !block.reachesBoundary && budget.extraUnityLookaheadFrames == 0 &&
                        ++unityBlocksSinceObservation >= 4) {
                        unityBlocksSinceObservation = 0
                        val sampledAt = shuttleNowNanos()
                        val latest = shuttleCommand.get()
                        if (latest != null && shuttleUsesUnityStream(latest.rate)) {
                            observeNativeQueue { frames, underruns ->
                                if (budget.observeUnity(sampledAt, sampledAt - latest.observedNanos, frames, underruns)) {
                                    val requested = (SHUTTLE_GRAIN_OUTPUT_SECONDS * shuttleSampleRate).roundToInt() +
                                        budget.extraUnityLookaheadFrames
                                    if (shuttleTrack.bufferSizeInFrames < requested) {
                                        shuttleTrack.setBufferSizeInFrames(requested)
                                    }
                                }
                            }
                        }
                    }
                    write(unityTail.append(packet, shuttleNowNanos()))
                    val physicalEnd = if (direction > 0) upper >= source.durationSeconds else lower <= 0.0
                    if (block.reachesBoundary && physicalEnd) write(unityTail.finish())
                    continue
                }
                var handoffAnchor: Double? = null
                unityCursor?.let { cursor ->
                    val overlapsNext = unityTail.overlapsNext
                    val pending = unityTail.takePending()
                    if (pending.isNotEmpty()) {
                        previousTail = pending
                        handoffAnchor = if (overlapsNext) cursor.nextSeconds else
                            cursor.heldTailStartSeconds(pending.size / frameBytes)
                    } else {
                        // The unity tail was already submitted. Resume from that edge and
                        // use it as the reference if the gesture jumps to a distant target.
                        settledGrainEdge = cursor.nextSeconds
                        previousAnchorSeconds = settledGrainEdge
                    }
                    unityCursor = null
                    unityPcm = null
                    budget.discardUnityPressure()
                }
                val sparseGrains = magnitude < 1f && !continuousGrains
                val cadencedGrains = continuousGrains || sparseGrains
                val now = shuttleNowNanos()
                val fresh = now - command.observedNanos in 0L until SHUTTLE_UNITY_PROJECTION_NANOS
                val projected = if (cadencedGrains && fresh) {
                    shuttleUnityTargetSeconds(target, rate, command.observedNanos, now).coerceIn(lower, upper)
                } else target
                if (fresh && handoffAnchor == null && shuttleSourceRequiresReanchor(previousAnchorSeconds, projected, rate)) {
                    // A distant target is unrelated waveform phase. Fade the old tail before
                    // re-anchoring instead of crossfading two far-apart regions into chorus.
                    finishPendingTail()
                    previousAnchorSeconds = null
                    settledGrainEdge = null
                }

                val transitionAnchor = handoffAnchor
                val settledEdge = settledGrainEdge
                val atSettledBoundary = settledEdge != null &&
                    (if (direction > 0) settledEdge >= upper else settledEdge <= lower)
                val anchor = when {
                    // Opening a decoder or track can outlast the projection window. The
                    // active gesture can still prime once at its last observed position.
                    atSettledBoundary || (!fresh && startedCallbackSent) -> null
                    transitionAnchor != null -> transitionAnchor.coerceIn(lower, upper)
                    settledEdge != null -> {
                        val lead = if (cadencedGrains) SHUTTLE_CROSSFADE_SECONDS * magnitude else 0.0
                        settledEdge.takeIf { (it - projected) * direction <= lead }
                    }
                    cadencedGrains -> nextContinuousShuttleGrainAnchorSeconds(previousAnchorSeconds, projected, rate)
                        ?.coerceIn(lower, upper)
                    else -> nextShuttleSourceAnchorSeconds(previousAnchorSeconds, target, rate)
                }
                if (anchor == null) {
                    val sparseWait = sparseGrains &&
                        lastGrainWriteNanos?.let { now - it >= SHUTTLE_CROSSFADE_NANOS } == true &&
                        sparseShuttleWaitExceedsOverlap(previousAnchorSeconds, projected, rate)
                    if ((!fresh || sparseWait) && previousTail.isNotEmpty()) {
                        settledGrainEdge = grainTailEndSeconds?.coerceIn(lower, upper)
                        finishPendingTail()
                        // An already submitted overlap never provides input for the next grain.
                    }
                    if (onShuttleTargetWait != null && (lastReportedWait !== command || lastWaitWasFresh != fresh)) {
                        lastReportedWait = command
                        lastWaitWasFresh = fresh
                        onShuttleTargetWait.invoke(rate)
                    }
                    Thread.sleep(if (cadencedGrains && fresh) 2L else 8L)
                    continue
                }

                val sourceDuration = shuttleSourceGrainSeconds(rate)
                val preserveAnchor = handoffAnchor != null || settledEdge != null ||
                    cadencedGrains && previousAnchorSeconds != null
                val start = if (rate >= 0f) {
                    if (preserveAnchor) anchor else minOf(anchor, upper - sourceDuration).coerceAtLeast(lower)
                } else (anchor - sourceDuration).coerceAtLeast(lower)
                val end = if (rate >= 0f) (start + sourceDuration).coerceAtMost(upper)
                    else if (preserveAnchor) anchor else maxOf(anchor, lower + sourceDuration).coerceAtMost(upper)
                if (end <= start) {
                    finishPendingTail()
                    previousAnchorSeconds = null
                    Thread.sleep(8L)
                    continue
                }

                val accelerated = renderGrain(start, end, anchor, lower, upper, rate, continuousGrains)
                if (accelerated == null) {
                    finishPendingTail()
                    previousAnchorSeconds = null
                    sourceWindow = null
                    Thread.sleep(8L)
                    continue
                }
                settledGrainEdge = null
                val acceleratedFrames = accelerated.size / frameBytes
                val completedAnchor = shuttleAnchorAfterGrain(
                    anchor - lower, start - lower, end - lower, upper - lower, rate,
                ) + lower
                if (acceleratedFrames <= 2) {
                    finishPendingTail()
                    write(windowShuttlePcm16(accelerated, acceleratedFrames / 2, channelCount))
                    previousAnchorSeconds = completedAnchor
                    continue
                }
                // Keep the output overlap bounded when a boundary truncates a sped-up grain.
                // Full grains still use the canonical 8 ms overlap; tiny edge grains reserve
                // at most one third of their frames so they cannot disappear into the tail.
                val grainOverlapFrames = shuttleOverlapFrames(acceleratedFrames, overlapFrames)
                val sourceStep = (renderedGrainSourceFrames - 1).toDouble() / (acceleratedFrames - 1)
                val renderedEnd = renderedGrainFirstSeconds + direction * acceleratedFrames * sourceStep / shuttleSampleRate
                val endsAtBoundary = completedAnchor != anchor || rate >= 0f && end >= upper || rate < 0f && start <= lower
                val sparseOverlapWouldArriveLate = sparseGrains &&
                    sparseShuttleWaitExceedsOverlap(anchor, projected + rate * SHUTTLE_CROSSFADE_SECONDS, rate)
                val isolated = previousTail.isEmpty() && (!fresh || endsAtBoundary || sparseOverlapWouldArriveLate)
                if (isolated) {
                    // A sparse grain with no overlap due before its body drains should
                    // reach the sink in one packet, including its short terminal taper.
                    previousAnchorSeconds = completedAnchor
                    settledGrainEdge = renderedEnd.coerceIn(lower, upper)
                    write(windowShuttlePcm16(accelerated, minOf(grainEdgeFrames, grainOverlapFrames), channelCount))
                    continue
                }
                val blended = crossfadeShuttlePcm16(
                    previousTail,
                    accelerated,
                    grainOverlapFrames,
                    channelCount,
                    entranceFrames = minOf(grainEdgeFrames, grainOverlapFrames),
                )
                previousTail = blended.tail
                grainTailAnchorSeconds = renderedGrainFirstSeconds +
                    direction * (blended.output.size / frameBytes) * sourceStep / shuttleSampleRate
                grainTailEndSeconds = renderedEnd
                previousAnchorSeconds = completedAnchor
                write(blended.output)
                lastGrainWriteNanos = shuttleNowNanos()
                if (endsAtBoundary) {
                    // Later expansion starts at the already submitted endpoint. Repeated
                    // commands at the current boundary cannot prime the same audio again.
                    settledGrainEdge = grainTailEndSeconds?.coerceIn(lower, upper) ?: completedAnchor
                    finishPendingTail()
                }
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            try {
                nativeScratchRun?.close()
            } finally {
                // Register quiet proof before detaching: ordinary resume must never see a
                // ticketless retiring track after its authority registration disappears.
                track?.let { quietOwner(it).retire() }
                authorityHandle?.let { ordinaryAuthority?.detach(it) }
                // Register proof ownership before a fallback can request normal playback.
                failure?.let { error ->
                    postShuttleFailureRecovery(token, ordinaryAuthority, onFailureAfterStart, error, startedCallbackSent)
                }
                // A retiring worker must not clear a newer gesture published by the UI thread.
                activeShuttleToken.compareAndSet(token, 0L)
                if (track != null) {
                    synchronized(trackLock) {
                        if (activeTrack === track) { activeTrack = null; activeTrackEpoch = 0L }
                    }
                    retireTrack(track)
                }
                runCleanupReportingThrowable(
                    cleanup = source::close,
                    onFailure = onSourceCloseFailure,
                )
            }
        }
    }

    private fun postShuttleFailureRecovery(
        token: Long,
        authority: RecordingScrubAuthority?,
        onFailureAfterStart: (() -> Unit)?,
        error: Exception,
        startedCallbackSent: Boolean = false,
    ) {
        if (error is PreviewCancelled || error is CancellationException || error is InterruptedException ||
            Thread.currentThread().isInterrupted || onFailureAfterStart == null ||
            !(authority?.playbackSuspended ?: startedCallbackSent) || !isShuttleCurrent(token, authority)) return
        // The same Library gesture can still own suspension after its AudioTrack is replaced.
        postIfCurrent(token) {
            if (authority?.active != false) onFailureAfterStart()
        }
    }

    private fun stream(
        token: Long,
        sourceFactory: () -> PreviewPcmStream,
        volume: Float,
        samplePlaybackHead: Boolean,
        onProgress: ((Double) -> Unit)?,
        onStarted: () -> Unit,
        onFinished: () -> Unit,
        onError: ((Throwable) -> Unit)?,
        onClockSample: ((PreviewPlaybackSample) -> Unit)? = null,
    ) {
        var source: PreviewPcmStream? = null
        var track: AudioTrack? = null
        var playbackFailure: Throwable? = null
        var trackReleaseFailure: Throwable? = null
        var startedCallbackSent = false
        try {
            checkCurrent(token)
            val opened = sourceFactory()
            source = opened
            checkCurrent(token)
            val channelCount = opened.channelCount
            val sampleRate = opened.sampleRate
            val startSeconds = opened.startSeconds
            val endSeconds = opened.endSeconds
            val frameBytes = pcm16FrameBytes(channelCount)
            val batchBytes = (sampleRate / 20).coerceAtLeast(1) * frameBytes
            track = createTrack(
                token, volume,
                channelCount = channelCount,
                sampleRate = sampleRate,
                minimumBufferBytes = batchBytes,
                lowLatency = true,
                onConfigurationReleaseFailure = { trackReleaseFailure = it },
            )
            check(runBlocking { previewQuietCoordinator.awaitQuiet() }) { "Previous audio preview could not be silenced" }
            synchronized(trackLock) {
                checkCurrent(token)
                activeTrack = track
                activeTrackEpoch = token
            }
            val playbackHead = PlaybackHeadFrameCounter()

            var lastProgressAt = 0L
            var submittedFrames = 0L
            val latestProgress = AtomicReference<PreviewPlaybackSample?>(null)
            val progressPosted = java.util.concurrent.atomic.AtomicBoolean(false)
            fun publishPosition(played: Long) {
                val sample = PreviewPlaybackSample(
                    (startSeconds + played.toDouble() / sampleRate).coerceAtMost(endSeconds),
                    SystemClock.elapsedRealtime(),
                    (startSeconds + submittedFrames.toDouble() / sampleRate).coerceAtMost(endSeconds),
                    submittedFrames,
                )
                latestProgress.set(sample)
                if (progressPosted.compareAndSet(false, true)) postIfCurrent(token) {
                    // Reopen posting first: a concurrent terminal sample is consumed here
                    // or queues its own delivery before the worker posts onFinished.
                    progressPosted.set(false)
                    val latest = latestProgress.getAndSet(null)
                    if (latest != null) {
                        onProgress?.invoke(latest.positionSeconds)
                        onClockSample?.invoke(latest)
                    }
                }
            }
            val targetFrames = opened.read { array, offset, count ->
                var position = offset
                val limit = offset + count
                while (position < limit) {
                    checkCurrent(token)
                    val requested = minOf(batchBytes, limit - position)
                    val written = quietOwner(track).write {
                        checkCurrent(token)
                        track.write(array, position, requested,
                            if (startedCallbackSent) AudioTrack.WRITE_BLOCKING else AudioTrack.WRITE_NON_BLOCKING)
                    } ?: throw PreviewCancelled()
                    if (written <= 0) throw IOException("Audio preview write failed: $written")
                    position += written
                    submittedFrames += written / frameBytes
                    if (!startedCallbackSent) {
                        if (!quietOwner(track).play {
                                playPrimedPreviewTrack(
                                    Build.VERSION.SDK_INT, written / frameBytes, track::setBufferSizeInFrames,
                                ) {
                                    checkCurrent(token)
                                    track.play()
                                }
                            }) throw PreviewCancelled()
                        startedCallbackSent = true
                        postIfCurrent(token, onStarted)
                    }
                    if (samplePlaybackHead) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgressAt >= PREVIEW_PROGRESS_INTERVAL_MILLIS) {
                            lastProgressAt = now
                            // Long playback must unwrap the head even without a progress observer.
                            val played = playbackHead.update(quietOwner(track).read { track.playbackHeadPosition } ?: throw PreviewCancelled())
                            publishPosition(played)
                        }
                    }
                }
                count
            }

            var lastPlayedFrames = -1L
            var lastPlaybackAdvanceAt = SystemClock.elapsedRealtime()
            while (true) {
                checkCurrent(token)
                val played = playbackHead.update(quietOwner(track).read { track.playbackHeadPosition } ?: throw PreviewCancelled())
                if (played >= targetFrames) {
                    if (samplePlaybackHead) publishPosition(played)
                    break
                }
                val now = SystemClock.elapsedRealtime()
                if (played > lastPlayedFrames) {
                    lastPlayedFrames = played
                    lastPlaybackAdvanceAt = now
                } else if (previewPlaybackDrainStalled(lastPlaybackAdvanceAt, now)) {
                    throw IOException("Audio preview playback stalled")
                }
                if (samplePlaybackHead) publishPosition(played)
                Thread.sleep(16L)
            }
            checkCurrent(token)
            playbackRuns.finish(token)
            postIfCurrent(token, onFinished)
        } catch (_: PreviewCancelled) {
            Unit
        } catch (error: Exception) {
            playbackFailure = error
        } finally {
            playbackRuns.finish(token)
            try {
                source?.close()
            } catch (error: Throwable) {
                val primary = playbackFailure
                if (primary != null && primary !== error) {
                    primary.addSuppressed(error)
                    throw primary
                }
                throw error
            } finally {
                if (track != null) {
                    quietOwner(track).retire()
                    synchronized(trackLock) {
                        if (activeTrack === track) { activeTrack = null; activeTrackEpoch = 0L }
                    }
                    retireTrack(track)
                }
                // Configuration cleanup has completed and stays attached to its failure.
                // Terminal track cleanup runs independently and reports process feedback.
                completeRangePreviewFailure(
                    playbackFailure = playbackFailure,
                    releaseFailure = trackReleaseFailure,
                    isCurrent = { isCurrent(token) },
                    post = { action -> mainHandler.post { action() } },
                    onPlaybackFailure = onError,
                    onReleaseFailure = ::reportTrackReleaseFailure,
                )
            }
        }
    }

    private fun createTrack(
        token: Long,
        volume: Float,
        channelCount: Int = 1,
        minimumBufferBytes: Int = PREVIEW_FRAMES_PER_BATCH * pcm16FrameBytes(channelCount) * 4,
        lowLatency: Boolean = false,
        sampleRate: Int = PREVIEW_SAMPLE_RATE,
        contentType: Int = AudioAttributes.CONTENT_TYPE_SPEECH,
        onConfigurationReleaseFailure: (Throwable) -> Unit,
        audioSessionId: Int? = null,
        checkAdmission: () -> Unit = {},
    ): AudioTrack {
        while (!trackSlots.tryAcquire(16L, TimeUnit.MILLISECONDS)) {
            checkCurrent(token)
            checkAdmission()
            if (failedTrackReleases.get() >= 3) throw IOException("Audio preview resources could not be released")
        }
        var releaseFailed = false
        try {
            checkCurrent(token)
            checkAdmission()
            return createConfiguredTrack(
                volume, channelCount, minimumBufferBytes, lowLatency, sampleRate, contentType, audioSessionId,
            ) { error ->
                releaseFailed = true
                failedTrackReleases.incrementAndGet()
                onConfigurationReleaseFailure(error)
            }
        } catch (error: Throwable) {
            if (!releaseFailed) trackSlots.release()
            throw error
        }
    }

    private fun createConfiguredTrack(
        volume: Float,
        channelCount: Int,
        minimumBufferBytes: Int,
        lowLatency: Boolean,
        sampleRate: Int,
        contentType: Int,
        audioSessionId: Int?,
        onConfigurationReleaseFailure: (Throwable) -> Unit,
    ): AudioTrack {
        val channelMask = playbackChannelMask(channelCount)
        val minBuffer = resolvePreviewAudioTrackBufferBytes(
            reportedMinBufferBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
            minimumBufferBytes = minimumBufferBytes,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(contentType)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer)
            .apply {
                if (lowLatency) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                if (audioSessionId != null) setSessionId(audioSessionId)
            }
            .build()
        return configureRangePreviewTrackOrRelease(
            owner = track,
            release = { it.release() },
            onReleaseFailure = onConfigurationReleaseFailure,
        ) { configured ->
            check(configured.state == AudioTrack.STATE_INITIALIZED) {
                "AudioTrack failed to initialize"
            }
            // Finite selections and sparse grains may never fill the native buffer capacity.
            // API28-30 instead fit the effective buffer to their first admitted write before play.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val threshold = configured.setStartThresholdInFrames(1)
                if (threshold != 1) throw IOException("Unable to configure audio preview start threshold: $threshold")
            }
            val result = configured.setVolume(volume.coerceIn(0f, 1f))
            if (result != AudioTrack.SUCCESS) {
                throw IOException("Unable to configure audio preview volume: $result")
            }
        }
    }

    private fun releaseTrackOnce(
        track: AudioTrack,
        onFailure: (Throwable) -> Unit = ::reportTrackReleaseFailure,
    ) {
        val owner = quietOwner(track)
        val released = owner.release(
            pause = { track.pause() },
            flush = { track.flush() },
            release = { track.release() },
            onFailure = { error -> runCleanupReportingThrowable({ onFailure(error) }, {}) },
        )
        if (released) {
            if (owner.nativeReleaseSucceeded) trackSlots.release() else failedTrackReleases.incrementAndGet()
        }
    }

    private fun reportTrackReleaseFailure(error: Throwable) {
        Log.e("ReverbRangeAudio", "AudioTrack.release failed", error)
        onTrackReleaseFailure(error)
    }

    private fun checkCurrent(token: Long) {
        if (!isCurrent(token) || Thread.currentThread().isInterrupted) throw PreviewCancelled()
    }

    private fun isShuttleCurrent(token: Long, authority: RecordingScrubAuthority?): Boolean =
        isCurrent(token) && authority?.active != false

    private fun isCurrent(token: Long): Boolean = !closed && generation.get() == token

    private fun postIfCurrent(token: Long, block: () -> Unit) {
        mainHandler.post {
            if (isCurrent(token)) block()
        }
    }

    private class PreviewCancelled : RuntimeException()
}
