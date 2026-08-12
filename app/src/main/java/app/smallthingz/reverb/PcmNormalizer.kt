package app.smallthingz.reverb

import java.io.RandomAccessFile
import java.util.zip.CRC32
import kotlin.math.floor
import kotlin.math.roundToInt

/** Export-only PCM conversion. Recording stores exactly what AudioRecord produced. */
internal object PcmNormalizer {
    private const val OUTPUT_BUFFER_BYTES = 64 * 1024
    private const val CRC_SCRATCH_BYTES = 64 * 1024

    fun normalizeSegment(
        file: java.io.File,
        payloadDataOffset: Long,
        payloadBytes: Long,
        expectedPayloadChecksum: Int,
        payloadByteOffset: Long,
        sourceFrameCount: Long,
        sourceSampleRate: Int,
        sourceChannelCount: Int,
        sourceSampleFormat: PcmSampleFormat,
        targetFrameCount: Long,
        targetChannelCount: Int,
        targetSampleFormat: PcmSampleFormat,
        consumer: PersistentAudioChunkStore.Consumer,
    ): Long {
        if (sourceFrameCount <= 0L || targetFrameCount <= 0L) return 0L
        require(sourceFrameCount <= Int.MAX_VALUE.toLong()) { "Chunk segment too large" }
        require(sourceChannelCount in 1..2 && targetChannelCount in 1..2)
        require(sourceSampleRate > 0)

        val sourceFrameBytes = sourceChannelCount * sourceSampleFormat.bytesPerSample
        val sourceByteCountLong = sourceFrameCount * sourceFrameBytes.toLong()
        require(sourceByteCountLong <= Int.MAX_VALUE.toLong()) { "Chunk segment byte range too large" }
        require(payloadDataOffset >= 0L && payloadBytes > 0L)
        require(payloadByteOffset >= 0L && payloadByteOffset <= payloadBytes - sourceByteCountLong) {
            "Chunk segment outside payload"
        }

        if (
            targetFrameCount == sourceFrameCount &&
            targetChannelCount == sourceChannelCount &&
            targetSampleFormat == sourceSampleFormat
        ) {
            return copyVerifiedSegment(
                file = file,
                payloadDataOffset = payloadDataOffset,
                payloadBytes = payloadBytes,
                expectedPayloadChecksum = expectedPayloadChecksum,
                segmentByteOffset = payloadByteOffset,
                segmentByteCount = sourceByteCountLong,
                consumer = consumer,
            )
        }

        val sourceBytes = ByteArray(sourceByteCountLong.toInt())
        val crc = CRC32()
        val scratch = ByteArray(CRC_SCRATCH_BYTES)
        RandomAccessFile(file, "r").use { input ->
            input.seek(payloadDataOffset)
            var remaining = payloadBytes
            var payloadOffset = 0L
            while (remaining > 0L) {
                val count = minOf(scratch.size.toLong(), remaining).toInt()
                input.readFully(scratch, 0, count)
                crc.update(scratch, 0, count)

                val blockStart = payloadOffset
                val blockEnd = blockStart + count.toLong()
                val segmentEnd = payloadByteOffset + sourceByteCountLong
                val copyStart = maxOf(blockStart, payloadByteOffset)
                val copyEnd = minOf(blockEnd, segmentEnd)
                if (copyEnd > copyStart) {
                    val sourceOffset = (copyStart - blockStart).toInt()
                    val destinationOffset = (copyStart - payloadByteOffset).toInt()
                    val copyCount = (copyEnd - copyStart).toInt()
                    scratch.copyInto(
                        destination = sourceBytes,
                        destinationOffset = destinationOffset,
                        startIndex = sourceOffset,
                        endIndex = sourceOffset + copyCount,
                    )
                }
                payloadOffset = blockEnd
                remaining -= count.toLong()
            }
            if (crc.value.toInt() != expectedPayloadChecksum) {
                throw java.io.IOException("PCM chunk checksum mismatch: ${file.name}")
            }
        }

        val sourceFrames = sourceFrameCount.toInt()
        val samples = FloatArray(sourceFrames * sourceChannelCount)
        decode(
            source = sourceBytes,
            sourceOffset = 0,
            format = sourceSampleFormat,
            output = samples,
        )

        val targetFrameBytes = targetChannelCount * targetSampleFormat.bytesPerSample
        val framesPerOutputBuffer = maxOf(1, OUTPUT_BUFFER_BYTES / targetFrameBytes)
        val output = ByteArray(framesPerOutputBuffer * targetFrameBytes)
        var producedFrames = 0L
        var producedBytes = 0L

        while (producedFrames < targetFrameCount) {
            val batchFrames = minOf(framesPerOutputBuffer.toLong(), targetFrameCount - producedFrames).toInt()
            var outputOffset = 0
            repeat(batchFrames) { batchIndex ->
                val targetIndex = producedFrames + batchIndex.toLong()
                val sourcePosition = targetIndex.toDouble() * sourceFrameCount.toDouble() / targetFrameCount.toDouble()
                val baseFrame = floor(sourcePosition).toInt().coerceIn(0, sourceFrames - 1)
                val nextFrame = minOf(baseFrame + 1, sourceFrames - 1)
                val fraction = (sourcePosition - baseFrame.toDouble()).toFloat()

                val left = interpolatedChannel(samples, sourceChannelCount, baseFrame, nextFrame, fraction, 0)
                val right = if (sourceChannelCount == 2) {
                    interpolatedChannel(samples, sourceChannelCount, baseFrame, nextFrame, fraction, 1)
                } else {
                    left
                }

                if (targetChannelCount == 1) {
                    val mono = if (sourceChannelCount == 2) (left + right) * 0.5f else left
                    outputOffset = encodeSample(output, outputOffset, mono, targetSampleFormat)
                } else {
                    outputOffset = encodeSample(output, outputOffset, left, targetSampleFormat)
                    outputOffset = encodeSample(output, outputOffset, right, targetSampleFormat)
                }
            }

            if (consumer.consume(output, 0, outputOffset) != outputOffset) {
                throw java.io.IOException("PCM consumer rejected normalized output")
            }
            producedFrames += batchFrames.toLong()
            producedBytes += outputOffset.toLong()
        }
        return producedBytes
    }

    private fun copyVerifiedSegment(
        file: java.io.File,
        payloadDataOffset: Long,
        payloadBytes: Long,
        expectedPayloadChecksum: Int,
        segmentByteOffset: Long,
        segmentByteCount: Long,
        consumer: PersistentAudioChunkStore.Consumer,
    ): Long {
        require(segmentByteCount <= Int.MAX_VALUE.toLong()) { "PCM segment too large" }
        val crc = CRC32()
        val scratch = ByteArray(CRC_SCRATCH_BYTES)
        val segmentBytes = ByteArray(segmentByteCount.toInt())
        val segmentEnd = segmentByteOffset + segmentByteCount
        RandomAccessFile(file, "r").use { input ->
            input.seek(payloadDataOffset)
            var remaining = payloadBytes
            var payloadOffset = 0L
            while (remaining > 0L) {
                val count = minOf(scratch.size.toLong(), remaining).toInt()
                input.readFully(scratch, 0, count)
                crc.update(scratch, 0, count)

                val blockStart = payloadOffset
                val blockEnd = blockStart + count.toLong()
                val copyStart = maxOf(blockStart, segmentByteOffset)
                val copyEnd = minOf(blockEnd, segmentEnd)
                if (copyEnd > copyStart) {
                    val sourceOffset = (copyStart - blockStart).toInt()
                    val destinationOffset = (copyStart - segmentByteOffset).toInt()
                    val copyCount = (copyEnd - copyStart).toInt()
                    scratch.copyInto(
                        destination = segmentBytes,
                        destinationOffset = destinationOffset,
                        startIndex = sourceOffset,
                        endIndex = sourceOffset + copyCount,
                    )
                }
                payloadOffset = blockEnd
                remaining -= count.toLong()
            }
        }
        if (crc.value.toInt() != expectedPayloadChecksum) {
            throw java.io.IOException("PCM chunk checksum mismatch: ${file.name}")
        }

        var offset = 0
        while (offset < segmentBytes.size) {
            val count = minOf(OUTPUT_BUFFER_BYTES, segmentBytes.size - offset)
            if (consumer.consume(segmentBytes, offset, count) != count) {
                throw java.io.IOException("PCM consumer rejected output")
            }
            offset += count
        }
        return segmentByteCount
    }

    private fun decode(
        source: ByteArray,
        sourceOffset: Int,
        format: PcmSampleFormat,
        output: FloatArray,
    ) {
        when (format) {
            PcmSampleFormat.PCM_8 -> {
                for (index in output.indices) {
                    output[index] = ((source[sourceOffset + index].toInt() and 0xff) - 128) / 128f
                }
            }

            PcmSampleFormat.PCM_16 -> {
                var offset = sourceOffset
                for (index in output.indices) {
                    val bits = (source[offset].toInt() and 0xff) or (source[offset + 1].toInt() shl 8)
                    output[index] = bits.toShort() / 32768f
                    offset += 2
                }
            }

            PcmSampleFormat.PCM_FLOAT -> {
                var offset = sourceOffset
                for (index in output.indices) {
                    val bits =
                        (source[offset].toInt() and 0xff) or
                            ((source[offset + 1].toInt() and 0xff) shl 8) or
                            ((source[offset + 2].toInt() and 0xff) shl 16) or
                            (source[offset + 3].toInt() shl 24)
                    val decoded = Float.fromBits(bits)
                    output[index] = if (decoded.isFinite()) decoded.coerceIn(-1f, 1f) else 0f
                    offset += 4
                }
            }
        }
    }

    private fun interpolatedChannel(
        samples: FloatArray,
        channels: Int,
        baseFrame: Int,
        nextFrame: Int,
        fraction: Float,
        channel: Int,
    ): Float {
        val first = samples[baseFrame * channels + channel]
        val second = samples[nextFrame * channels + channel]
        return first + (second - first) * fraction
    }

    private fun encodeSample(
        output: ByteArray,
        offset: Int,
        value: Float,
        format: PcmSampleFormat,
    ): Int {
        val sample = value.coerceIn(-1f, 1f)
        return when (format) {
            PcmSampleFormat.PCM_8 -> {
                output[offset] = (sample * 128f + 128f).roundToInt().coerceIn(0, 255).toByte()
                offset + 1
            }

            PcmSampleFormat.PCM_16 -> {
                val encoded = if (sample <= -1f) -32768 else (sample * 32767f).roundToInt().coerceIn(-32768, 32767)
                output[offset] = encoded.toByte()
                output[offset + 1] = (encoded shr 8).toByte()
                offset + 2
            }

            PcmSampleFormat.PCM_FLOAT -> {
                val bits = sample.toBits()
                output[offset] = bits.toByte()
                output[offset + 1] = (bits ushr 8).toByte()
                output[offset + 2] = (bits ushr 16).toByte()
                output[offset + 3] = (bits ushr 24).toByte()
                offset + 4
            }
        }
    }
}
