package app.smallthingz.reverb

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** The last nonzero direction also owns audio already queued when motion passes through zero. */
internal data class ShuttlePlaybackAdmission(
    val startSeconds: Double,
    val endSeconds: Double,
    val direction: Int,
) {
    fun updated(startSeconds: Double, endSeconds: Double, rate: Float) = ShuttlePlaybackAdmission(
        startSeconds, endSeconds, when {
            rate > 0f -> 1
            rate < 0f -> -1
            else -> direction
        },
    )

    fun requiresRetirementOf(previous: ShuttlePlaybackAdmission): Boolean =
        startSeconds > previous.startSeconds || endSeconds < previous.endSeconds ||
            (previous.direction != 0 && direction != 0 && direction != previous.direction)
}

/** Worker completion is transport authority even while its UI completion callback is delayed. */
internal class PreviewPlaybackRuns {
    private val owner = AtomicLong(0L)
    fun start(epoch: Long) { require(epoch > 0L); owner.set(epoch) }
    fun active(epoch: Long): Boolean = epoch > 0L && owner.get() == epoch
    fun finish(epoch: Long) { owner.compareAndSet(epoch, 0L) }
    fun cancel() { owner.set(0L) }
}

/** An immutable native observation; callers never receive or retain the AudioTrack itself. */
internal data class PreviewTrackObservation(
    val epoch: Long,
    val identity: Any,
    val playbackHeadFrames: Long,
    val underrunCount: Int,
    val playState: Int,
    val sampleRate: Int,
    val channelCount: Int,
    val bufferSizeInFrames: Int,
    val bufferCapacityInFrames: Int,
    val audioSessionId: Int,
    val acceptedWriteFrames: Long,
    val startThresholdInFrames: Int?,
)

internal enum class PreviewShuttleStage {
    SOURCE_FACTORY, CREATE_TRACK, QUIET_WAIT, READ_NORMALIZE, RENDER_GRAIN, FIRST_WRITE, CANCEL_REQUEST,
}

/** Opt-in timing metadata only; an old worker keeps its original epoch after cancellation. */
internal data class PreviewShuttleTiming(
    val epoch: Long,
    val stage: PreviewShuttleStage,
    val startedAtElapsedRealtimeNanos: Long,
    val elapsedNanos: Long,
    val threadCpuNanos: Long,
    val completed: Boolean,
    val currentAtEnd: Boolean,
    val outputBytes: Int?,
)

internal data class PreviewShuttleTimingBatch(val records: List<PreviewShuttleTiming>, val dropped: Int)

/** Legacy streaming readiness uses effective buffer size, which must fit actual queued PCM. */
internal inline fun playPrimedPreviewTrack(
    apiLevel: Int,
    firstWriteFrames: Int,
    setBufferSize: (Int) -> Int,
    play: () -> Unit,
) {
    require(firstWriteFrames > 0)
    if (apiLevel < 31) {
        val effectiveFrames = setBufferSize(maxOf(16, firstWriteFrames))
        if (effectiveFrames <= 0) {
            throw IOException("Audio preview buffer configuration failed (native result $effectiveFrames)")
        }
        if (effectiveFrames > firstWriteFrames) {
            throw IOException("Audio preview needs $effectiveFrames startup frames but only $firstWriteFrames are queued")
        }
    }
    play()
}

/** The primed track stays silent until the current UI-owned transport acknowledges handoff. */
internal fun awaitPreviewPlaybackHandoff(
    checkCurrent: () -> Unit,
    post: (() -> Unit) -> Boolean,
    action: () -> Unit,
) {
    val acknowledged = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>(null)
    check(post {
        try { checkCurrent(); action() } catch (error: Throwable) { failure.set(error) }
        finally { acknowledged.countDown() }
    }) { "Audio handoff dispatch was rejected" }
    while (!acknowledged.await(16L, TimeUnit.MILLISECONDS)) checkCurrent()
    checkCurrent()
    failure.get()?.let { throw it }
}

internal data class PreviewPlaybackSample(
    val positionSeconds: Double,
    val sampledAtElapsedRealtimeMillis: Long,
    val queuedUntilSeconds: Double,
    val acceptedWriteFrames: Long = 0L,
)

/** An exact source-frame interval. End is exclusive. */
internal data class PcmPreviewFrameRange(val first: Long, val endExclusive: Long) {
    init { require(first >= 0L && endExclusive >= first) }
    val frameCount: Long get() = endExclusive - first
}

internal fun previewFrameRange(
    startMillis: Int,
    endMillis: Int,
    sampleRate: Int,
    sourceFrames: Long,
    rounding: RecordingFrameRounding = RecordingFrameRounding.FLOOR,
): PcmPreviewFrameRange {
    require(sampleRate > 0 && sourceFrames >= 0L)
    val first = recordingFrameAtMillis(startMillis.coerceAtLeast(0).toLong(), sampleRate, rounding).coerceAtMost(sourceFrames)
    val end = recordingFrameAtMillis(endMillis.coerceAtLeast(0).toLong(), sampleRate, rounding).coerceIn(first, sourceFrames)
    return PcmPreviewFrameRange(first, end)
}

/** Exact source edges keep fractional-millisecond tails; interior trim edges use the save policy. */
internal fun recordingShuttleFrameRange(
    startSeconds: Double,
    endSeconds: Double,
    sampleRate: Int,
    sourceFrames: Long,
    trimRounding: RecordingFrameRounding,
): PcmPreviewFrameRange {
    val selected = previewFrameRange((startSeconds * 1_000.0).roundToInt(), (endSeconds * 1_000.0).roundToInt(),
        sampleRate, sourceFrames, trimRounding)
    val sourceEnd = sourceFrames.toDouble() / sampleRate
    return PcmPreviewFrameRange(
        when {
            startSeconds >= sourceEnd -> sourceFrames
            startSeconds <= 0.0 -> 0L
            else -> selected.first
        },
        if (endSeconds >= sourceEnd) sourceFrames else selected.endExclusive,
    )
}

/** No UI callback participates in admission or the terminal audio boundary. */
internal fun streamBoundedPcmFrames(
    range: PcmPreviewFrameRange,
    frameBytes: Int,
    maximumBatchFrames: Int,
    checkCurrent: () -> Unit,
    readFrames: (Long, Int) -> ByteArray,
    write: (ByteArray, Int, Int) -> Int,
): Long {
    require(frameBytes > 0 && maximumBatchFrames in 1..Int.MAX_VALUE / frameBytes)
    var frame = range.first
    while (frame < range.endExclusive) {
        checkCurrent()
        val count = minOf(maximumBatchFrames.toLong(), range.endExclusive - frame).toInt()
        val bytes = readFrames(frame, count)
        val expectedBytes = count * frameBytes
        if (bytes.size != expectedBytes) throw IOException("Incomplete bounded PCM preview batch")
        var offset = 0
        while (offset < expectedBytes) {
            checkCurrent()
            val written = write(bytes, offset, expectedBytes - offset)
            if (written <= 0 || written > expectedBytes - offset || written % frameBytes != 0) {
                throw IOException("Invalid bounded PCM preview write: $written")
            }
            offset += written
        }
        frame += count
    }
    checkCurrent()
    return range.frameCount
}
