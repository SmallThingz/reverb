package app.smallthingz.reverb

import app.smallthingz.reverb.PersistentAudioChunkStore.Segment
import app.smallthingz.reverb.PersistentAudioChunkStore.ReadResult
import app.smallthingz.reverb.PersistentAudioChunkStore.Consumer

/** Immutable geometry, independent of a live chunk store or process-local lease. */
internal data class FrozenPcmRange(
    val segments: List<Segment>,
    val leadingContext: List<Segment>,
    val trailingContext: List<Segment>,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val durationSeconds: Double,
) {
    fun hasExactPcm16Format(sampleRate: Int, channelCount: Int): Boolean = segments.all {
        it.record.sampleRate == sampleRate && it.record.channelCount == channelCount &&
            it.record.sampleFormat == PcmSampleFormat.PCM_16
    }

    fun readNormalized(
        targetSampleRate: Int,
        targetChannelCount: Int,
        targetSampleFormat: PcmSampleFormat,
        maximumOutputFrames: Long,
        maximumOutputBatchBytes: Int = PcmNormalizer.DEFAULT_OUTPUT_BATCH_BYTES,
        consumer: Consumer,
    ): ReadResult {
        require(targetSampleRate > 0 && targetChannelCount in 1..2)
        require(maximumOutputFrames >= 0L)
        checkPcmConversionNotInterrupted()
        val outputFrameBytes = targetChannelCount * targetSampleFormat.bytesPerSample
        require(maximumOutputBatchBytes in outputFrameBytes..PcmNormalizer.DEFAULT_OUTPUT_BATCH_BYTES)
        if (maximumOutputFrames == 0L) return ReadResult(0L, 0.0)
        var framesToDrop = 0L
        if (maximumOutputFrames != Long.MAX_VALUE) {
            val planning = TargetFrameAccumulator(targetSampleRate)
            var plannedFrames = 0L
            forEachContinuousRun { first, _, frames ->
                plannedFrames = planning.feed(frames, segments[first].record.sampleRate)
            }
            framesToDrop = (plannedFrames - maximumOutputFrames).coerceAtLeast(0L)
        }
        var totalOutputBytes = 0L
        var cumulativeDuration = 0.0
        var cumulativeTargetFrames = 0L
        val emission = TargetFrameAccumulator(targetSampleRate)
        forEachContinuousRun { first, end, frames ->
            val rate = segments[first].record.sampleRate
            cumulativeDuration += frames.toDouble() / rate
            val boundary = emission.feed(frames, rate)
            val targetFrames = boundary - cumulativeTargetFrames
            cumulativeTargetFrames = boundary
            if (targetFrames > 0 && framesToDrop < targetFrames) {
                totalOutputBytes += PcmStreamNormalizer.normalize(
                    segments.subList(first, end), targetFrames, framesToDrop,
                    targetChannelCount, targetSampleFormat, maximumOutputBatchBytes, consumer,
                    if (first == 0) leadingContext else emptyList(),
                    if (end == segments.size) trailingContext else emptyList(),
                )
                framesToDrop = 0L
            } else {
                framesToDrop = (framesToDrop - targetFrames).coerceAtLeast(0L)
            }
        }
        val outputDuration = if (maximumOutputFrames == Long.MAX_VALUE) cumulativeDuration
            else totalOutputBytes.toDouble() / outputFrameBytes / targetSampleRate
        return ReadResult(totalOutputBytes, outputDuration)
    }

    private inline fun forEachContinuousRun(visit: (Int, Int, Long) -> Unit) {
        var first = 0
        while (first < segments.size) {
            checkPcmConversionNotInterrupted()
            var end = first + 1
            var frames = segments[first].frameCount
            while (end < segments.size && segmentsAreContinuous(segments[end - 1], segments[end])) {
                if ((end and 255) == 0) checkPcmConversionNotInterrupted()
                frames = Math.addExact(frames, segments[end].frameCount)
                end++
            }
            visit(first, end, frames)
            first = end
        }
    }

    private fun segmentsAreContinuous(previous: Segment, next: Segment): Boolean =
        recordsAreContinuous(previous, next) && next.startFrame == 0L &&
            previous.startFrame + previous.frameCount == previous.payloadBytesAtAcquire / previous.record.frameBytes
    private fun recordsAreContinuous(previous: Segment, next: Segment): Boolean =
        next.continuesPreviousChunkAtAcquire && next.record.id == previous.record.id + 1u &&
            previous.record.sampleRate == next.record.sampleRate &&
            previous.record.channelCount == next.record.channelCount &&
            previous.record.sampleFormat == next.record.sampleFormat
}
