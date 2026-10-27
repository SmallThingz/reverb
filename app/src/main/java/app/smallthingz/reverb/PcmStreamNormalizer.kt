package app.smallthingz.reverb

import java.io.IOException
import kotlin.math.floor

/** A lease-proven continuous run shares one phase, independent of storage chunk boundaries. */
internal object PcmStreamNormalizer {
    private val EMPTY_BYTES = ByteArray(0)

    fun normalize(
        segments: List<PersistentAudioChunkStore.Segment>,
        targetFrameCount: Long,
        firstTargetFrame: Long,
        targetChannelCount: Int,
        targetSampleFormat: PcmSampleFormat,
        maximumOutputBatchBytes: Int,
        consumer: PersistentAudioChunkStore.Consumer,
        leadingContext: List<PersistentAudioChunkStore.Segment> = emptyList(),
        trailingContext: List<PersistentAudioChunkStore.Segment> = emptyList(),
    ): Long {
        checkPcmConversionNotInterrupted()
        require(segments.isNotEmpty() && firstTargetFrame in 0..targetFrameCount)
        require(targetChannelCount in 1..2)
        val outputFrameBytes = targetChannelCount * targetSampleFormat.bytesPerSample
        require(maximumOutputBatchBytes in outputFrameBytes..PcmNormalizer.DEFAULT_OUTPUT_BATCH_BYTES)
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
                    targetChannelCount, targetSampleFormat, maximumOutputBatchBytes, consumer,
                )
                skip = 0L
            }
            return produced
        }

        val step = sourceFrames.toDouble() / targetFrameCount
        val radius = pcmResamplingRadius(step)
        val ownBefore = minOf(radius.toLong(), first.startFrame)
        // Linear upsampling needs the next frame even though its filter radius is zero.
        val requiredAfter = if (step < 1.0) 1L else radius.toLong()
        val ownAfter = minOf(requiredAfter, last.payloadBytesAtAcquire / frameBytes - last.startFrame - last.frameCount)
        val availableBefore = leadingContext.sumOf { it.frameCount }
        val extraBefore = minOf(radius - ownBefore, availableBefore)
        val extraAfter = minOf(requiredAfter - ownAfter, trailingContext.sumOf { it.frameCount })
        val before = ownBefore + extraBefore
        val after = ownAfter + extraAfter
        val bufferedFrames = Math.addExact(Math.addExact(sourceFrames, before), after)
        val readSegments = if (before == 0L && after == 0L) segments else ContextSegments(
            segments,
            sliceContext(leadingContext, availableBefore - extraBefore, extraBefore),
            sliceContext(trailingContext, 0L, extraAfter),
            ownBefore, ownAfter,
        )
        val capacity = radius * 2 + 3
        val window = FloatArray(capacity * channels)
        val cursor = VerifiedFrames(readSegments)
        var firstWindowFrame = 0
        var lastWindowFrame = 0
        var windowStart = 0
        val converter = PcmRateConverter(capacity, step) { frame, channel ->
            val relative = frame.coerceIn(firstWindowFrame, lastWindowFrame) - firstWindowFrame
            val slot = windowStart + relative
            window[(if (slot >= capacity) slot - capacity else slot) * channels + channel]
        }
        val framesPerBatch = maximumOutputBatchBytes / outputFrameBytes
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
                val base = center - radius
                // The verified window spans less than one ring. Resolve its absolute
                // position once, avoiding Long remainder for every filter tap/channel.
                firstWindowFrame = (low - base).toInt()
                lastWindowFrame = firstWindowFrame + minOf(
                    (capacity - 1 - firstWindowFrame).toLong(), bufferedFrames - 1 - low,
                ).toInt()
                windowStart = (low % capacity).toInt()
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

    private fun sliceContext(segments: List<PersistentAudioChunkStore.Segment>,
                             skipFrames: Long, takeFrames: Long): List<PersistentAudioChunkStore.Segment> {
        if (takeFrames == 0L) return emptyList()
        val result = ArrayList<PersistentAudioChunkStore.Segment>(minOf(segments.size.toLong(), takeFrames).toInt())
        var skip = skipFrames
        var remaining = takeFrames
        for ((index, segment) in segments.withIndex()) {
            if ((index and 255) == 0) checkPcmConversionNotInterrupted()
            if (skip >= segment.frameCount) { skip -= segment.frameCount; continue }
            val count = minOf(remaining, segment.frameCount - skip)
            result += if (skip == 0L && count == segment.frameCount) segment else
                segment.copy(startFrame = segment.startFrame + skip, frameCount = count)
            skip = 0L
            remaining -= count
            if (remaining == 0L) break
        }
        check(remaining == 0L)
        return result
    }

    /** Shares the selected middle; only the two edge descriptors and bounded halo are new. */
    private class ContextSegments(
        private val selected: List<PersistentAudioChunkStore.Segment>,
        private val leading: List<PersistentAudioChunkStore.Segment>,
        private val trailing: List<PersistentAudioChunkStore.Segment>,
        before: Long, after: Long,
    ) : AbstractList<PersistentAudioChunkStore.Segment>() {
        private val first = selected.first().let { segment ->
            val extra = before + if (selected.size == 1) after else 0L
            if (extra == 0L) segment else segment.copy(startFrame = segment.startFrame - before,
                frameCount = segment.frameCount + extra)
        }
        private val last = if (selected.size == 1) first else selected.last().let { segment ->
            if (after == 0L) segment else segment.copy(frameCount = segment.frameCount + after)
        }
        override val size = Math.addExact(Math.addExact(leading.size, selected.size), trailing.size)
        override fun get(index: Int): PersistentAudioChunkStore.Segment {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException(index.toString())
            val middle = index - leading.size
            return when {
                middle < 0 -> leading[index]
                middle == 0 -> first
                middle == selected.lastIndex -> last
                middle < selected.size -> selected[middle]
                else -> trailing[middle - selected.size]
            }
        }
    }

    /** Holds one whole-CRC-verified payload slice plus the caller's bounded decoded ring. */
    private class VerifiedFrames(
        private val segments: List<PersistentAudioChunkStore.Segment>,
    ) {
        private val channels = segments.first().record.channelCount
        private val format = segments.first().record.sampleFormat
        private val frameBytes = channels * format.bytesPerSample
        private var index = 0
        private var segment = segments[0]
        private var frames = segment.frameCount
        private var start = 0L
        private var frameInSegment = 0L
        private var bytes = EMPTY_BYTES

        private fun advance() {
            start += frames
            index++
            segment = segments[index]
            frames = segment.frameCount
            frameInSegment = 0L
            bytes = EMPTY_BYTES
        }

        fun seek(frame: Long) {
            check(frame >= start + frameInSegment)
            while (frame >= start + frames) {
                if ((index and 255) == 0) checkPcmConversionNotInterrupted()
                advance()
            }
            frameInSegment = frame - start
        }

        fun next(output: FloatArray, offset: Int) {
            if (frameInSegment == frames) advance()
            if (bytes.isEmpty()) {
                val firstFrame = segment.startFrame
                bytes = PcmNormalizer.readVerifiedSegment(
                    segment.record.file, segment.payloadOffsetBytes, segment.payloadBytesAtAcquire,
                    segment.payloadChecksumAtAcquire, firstFrame * frameBytes, frames * frameBytes,
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
