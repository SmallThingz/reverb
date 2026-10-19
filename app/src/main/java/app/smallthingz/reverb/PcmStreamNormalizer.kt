package app.smallthingz.reverb

import java.io.IOException
import kotlin.math.floor

/** A lease-proven continuous run shares one phase, independent of storage chunk boundaries. */
internal object PcmStreamNormalizer {
    private const val OUTPUT_BYTES = 64 * 1024

    fun normalize(
        segments: List<PersistentAudioChunkStore.Segment>,
        targetFrameCount: Long,
        firstTargetFrame: Long,
        targetChannelCount: Int,
        targetSampleFormat: PcmSampleFormat,
        consumer: PersistentAudioChunkStore.Consumer,
    ): Long {
        checkPcmConversionNotInterrupted()
        require(segments.isNotEmpty() && firstTargetFrame in 0..targetFrameCount)
        if (firstTargetFrame == targetFrameCount) return 0L
        val first = segments.first()
        val last = segments.last()
        val channels = first.record.channelCount
        val format = first.record.sampleFormat
        val frameBytes = first.record.frameBytes
        val sourceFrames = segments.foldIndexed(0L) { index, count, segment ->
            if ((index and 255) == 0) checkPcmConversionNotInterrupted()
            Math.addExact(count, segment.frameCount)
        }
        require(sourceFrames > 0L)

        // Preserve exact input bytes, including float bit patterns, for matching geometry.
        if (sourceFrames == targetFrameCount && channels == targetChannelCount && format == targetSampleFormat) {
            var skip = firstTargetFrame
            var produced = 0L
            for (segment in segments) {
                checkPcmConversionNotInterrupted()
                if (skip >= segment.frameCount) { skip -= segment.frameCount; continue }
                val count = segment.frameCount - skip
                produced += PcmNormalizer.normalizeSegment(
                    segment.record.file, segment.payloadOffsetBytes, segment.payloadBytesAtAcquire,
                    segment.payloadChecksumAtAcquire, (segment.startFrame + skip) * frameBytes,
                    count, segment.record.sampleRate, channels, format, count,
                    targetChannelCount, targetSampleFormat, consumer,
                )
                skip = 0L
            }
            return produced
        }

        val step = sourceFrames.toDouble() / targetFrameCount
        val radius = pcmResamplingRadius(step)
        val before = minOf(radius.toLong(), first.startFrame)
        val after = minOf(radius.toLong(), last.payloadBytesAtAcquire / frameBytes - last.startFrame - last.frameCount)
        val bufferedFrames = Math.addExact(Math.addExact(sourceFrames, before), after)
        val capacity = radius * 2 + 3
        val window = FloatArray(capacity * channels)
        val cursor = VerifiedFrames(segments, before, after)
        var base = 0L
        val converter = PcmRateConverter(capacity, step) { frame, channel ->
            val logical = (base + frame).coerceIn(0L, bufferedFrames - 1)
            window[(logical % capacity).toInt() * channels + channel]
        }
        val outputFrameBytes = targetChannelCount * targetSampleFormat.bytesPerSample
        val framesPerBatch = OUTPUT_BYTES / outputFrameBytes
        val output = ByteArray(framesPerBatch * outputFrameBytes)
        var targetIndex = firstTargetFrame
        var nextFrame = -1L
        var produced = 0L
        while (targetIndex < targetFrameCount) {
            checkPcmConversionNotInterrupted()
            val batch = minOf(framesPerBatch.toLong(), targetFrameCount - targetIndex).toInt()
            var offset = 0
            repeat(batch) { index ->
                val position = before + (targetIndex + index).toDouble() * sourceFrames / targetFrameCount
                val center = floor(position).toLong()
                val low = (center - radius).coerceIn(0L, bufferedFrames - 1)
                val high = (center + maxOf(radius, 1)).coerceIn(0L, bufferedFrames - 1)
                if (nextFrame < low) {
                    // Newest-output limits skip entire unneeded payloads, retaining the global phase.
                    cursor.seek(low)
                    nextFrame = low
                }
                while (nextFrame <= high) {
                    cursor.next(window, (nextFrame % capacity).toInt() * channels)
                    nextFrame++
                }
                base = center - radius
                val localPosition = radius + (position - center)
                val left = converter.sample(localPosition, 0)
                val right = if (channels == 2) converter.sample(localPosition, 1) else left
                if (targetChannelCount == 1) {
                    offset = PcmNormalizer.encodeSample(output, offset, if (channels == 2) (left + right) * 0.5f else left, targetSampleFormat)
                } else {
                    offset = PcmNormalizer.encodeSample(output, offset, left, targetSampleFormat)
                    offset = PcmNormalizer.encodeSample(output, offset, right, targetSampleFormat)
                }
            }
            if (consumer.consume(output, 0, offset) != offset) throw IOException("PCM consumer rejected normalized output")
            targetIndex += batch
            produced += offset
        }
        return produced
    }

    /** Holds one whole-CRC-verified payload slice plus the caller's bounded decoded ring. */
    private class VerifiedFrames(
        private val segments: List<PersistentAudioChunkStore.Segment>,
        private val before: Long,
        private val after: Long,
    ) {
        private val channels = segments.first().record.channelCount
        private val format = segments.first().record.sampleFormat
        private val frameBytes = channels * format.bytesPerSample
        private var index = 0
        private var start = 0L
        private var frameInSegment = 0L
        private var bytes = ByteArray(0)

        private fun frames(): Long = segments[index].frameCount +
            (if (index == 0) before else 0L) + (if (index == segments.lastIndex) after else 0L)

        fun seek(frame: Long) {
            check(frame >= start + frameInSegment)
            while (frame >= start + frames()) {
                if ((index and 255) == 0) checkPcmConversionNotInterrupted()
                start += frames()
                index++
                bytes = ByteArray(0)
            }
            frameInSegment = frame - start
        }

        fun next(output: FloatArray, offset: Int) {
            if (frameInSegment == frames()) {
                start += frames()
                index++
                frameInSegment = 0L
                bytes = ByteArray(0)
            }
            if (bytes.isEmpty()) {
                val segment = segments[index]
                val firstFrame = segment.startFrame - if (index == 0) before else 0L
                bytes = PcmNormalizer.readVerifiedSegment(
                    segment.record.file, segment.payloadOffsetBytes, segment.payloadBytesAtAcquire,
                    segment.payloadChecksumAtAcquire, firstFrame * frameBytes, frames() * frameBytes,
                )
            }
            val byteOffset = Math.toIntExact(frameInSegment * frameBytes)
            for (channel in 0 until channels) {
                output[offset + channel] = PcmNormalizer.decodeSample(bytes, byteOffset + channel * format.bytesPerSample, format)
            }
            frameInSegment++
        }
    }
}
