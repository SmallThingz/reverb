package app.smallthingz.reverb

import java.io.RandomAccessFile
import java.util.zip.CRC32
import kotlin.math.floor
import kotlin.math.roundToInt

/** Export-only PCM conversion. Recording stores exactly what AudioRecord produced. */
internal object PcmNormalizer {
    private const val CHUNK_HEADER_BYTES = 64L
    private const val OUTPUT_BUFFER_BYTES = 64 * 1024

    fun normalizeSegment(
        file: java.io.File,
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

        require(payloadBytes in 1..Int.MAX_VALUE.toLong()) { "Chunk payload too large" }
        val fullPayload = ByteArray(payloadBytes.toInt())
        RandomAccessFile(file, "r").use { input ->
            input.seek(CHUNK_HEADER_BYTES)
            input.readFully(fullPayload)
        }
        val crc = CRC32().apply { update(fullPayload) }.value.toInt()
        if (crc != expectedPayloadChecksum) {
            throw java.io.IOException("PCM chunk checksum mismatch: ${file.name}")
        }

        val sourceFrameBytes = sourceChannelCount * sourceSampleFormat.bytesPerSample
        val sourceByteCountLong = sourceFrameCount * sourceFrameBytes.toLong()
        require(sourceByteCountLong <= Int.MAX_VALUE.toLong()) { "Chunk segment byte range too large" }
        require(payloadByteOffset >= 0L && payloadByteOffset + sourceByteCountLong <= payloadBytes) {
            "Chunk segment outside payload"
        }
        val segmentStart = payloadByteOffset.toInt()
        val sourceBytes = fullPayload.copyOfRange(segmentStart, segmentStart + sourceByteCountLong.toInt())

        val sourceFrames = sourceFrameCount.toInt()
        val samples = FloatArray(sourceFrames * sourceChannelCount)
        decode(sourceBytes, sourceSampleFormat, samples)

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

    private fun decode(
        source: ByteArray,
        format: PcmSampleFormat,
        output: FloatArray,
    ) {
        when (format) {
            PcmSampleFormat.PCM_8 -> {
                for (index in output.indices) {
                    output[index] = ((source[index].toInt() and 0xff) - 128) / 128f
                }
            }

            PcmSampleFormat.PCM_16 -> {
                var offset = 0
                for (index in output.indices) {
                    val bits = (source[offset].toInt() and 0xff) or (source[offset + 1].toInt() shl 8)
                    output[index] = bits.toShort() / 32768f
                    offset += 2
                }
            }

            PcmSampleFormat.PCM_FLOAT -> {
                var offset = 0
                for (index in output.indices) {
                    val bits =
                        (source[offset].toInt() and 0xff) or
                            ((source[offset + 1].toInt() and 0xff) shl 8) or
                            ((source[offset + 2].toInt() and 0xff) shl 16) or
                            (source[offset + 3].toInt() shl 24)
                    output[index] = Float.fromBits(bits).coerceIn(-1f, 1f)
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
                output[offset] = (sample * 127f + 128f).roundToInt().coerceIn(0, 255).toByte()
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
