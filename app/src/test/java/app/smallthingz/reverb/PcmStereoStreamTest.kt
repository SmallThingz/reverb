package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PcmStereoStreamTest {
    @Test fun pairedRingMatchesScalarPayloadAcrossHalosPartitionsSeeksAndFormats() {
        val root = Files.createTempDirectory("stereo-stream-scalar-").toFile()
        try {
            val frames = 1027
            for (sourceFormat in PcmSampleFormat.entries) {
                val frameBytes = 2 * sourceFormat.bytesPerSample
                val bytes = ByteArray(frames * frameBytes)
                repeat(frames) { frame -> repeat(2) { channel ->
                    PcmNormalizer.encodeSample(bytes, frame * frameBytes + channel * sourceFormat.bytesPerSample,
                        ((frame * (37 + channel * 4) % 257) - 128) / 128f, sourceFormat)
                } }
                if (sourceFormat == PcmSampleFormat.PCM_FLOAT) {
                    java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(8 * 4, 0x7fc01234).putInt(9 * 4, 0x7f800000)
                }
                run {
                    val file = java.io.File(root, sourceFormat.name).apply { writeBytes(bytes) }
                    val checksum = java.util.zip.CRC32().apply { update(bytes) }.value.toInt()
                    val record = PersistentAudioChunkStore.ChunkRecord(1u, file,
                        PersistentAudioChunkStore.ChunkState.FINALIZED, 0L, bytes.size.toLong(),
                        frames.toLong(), 96000, 2, sourceFormat, checksum, 1L, 0L)
                    run {
                        val whole = PersistentAudioChunkStore.Segment(record, 0L, frames.toLong(),
                            0L, bytes.size.toLong(), checksum, 0.0, frames / 96000.0)
                        for ((start, count) in listOf(0 to frames, 101 to 800)) {
                            val parts = mutableListOf<PersistentAudioChunkStore.Segment>()
                            var offset = 0
                            while (offset < count) {
                                val size = minOf(37, count - offset)
                                parts += whole.copy(startFrame = (start + offset).toLong(), frameCount = size.toLong())
                                offset += size
                            }
                            for (targetFrames in listOf(201L, 512L, 1201L)) for (targetChannels in 1..2) for (targetFormat in PcmSampleFormat.entries) {
                                val scalar = ByteArrayOutputStream()
                                PcmNormalizer.normalizeSegment(whole.record.file, whole.payloadOffsetBytes,
                                    whole.payloadBytesAtAcquire, whole.payloadChecksumAtAcquire,
                                    start.toLong() * frameBytes, count.toLong(), 96000, 2, sourceFormat,
                                    targetFrames, targetChannels, targetFormat) { data, at, length ->
                                    scalar.write(data, at, length); length
                                }
                                val expected = scalar.toByteArray()
                                val outputFrameBytes = targetChannels * targetFormat.bytesPerSample
                                for (batchFrames in listOf(1, 17, 1024)) for (skip in listOf(0L, 73L)) {
                                    val actual = ByteArrayOutputStream()
                                    val size = PcmStreamNormalizer.normalize(parts, targetFrames, skip,
                                        targetChannels, targetFormat, batchFrames * outputFrameBytes,
                                        { data, at, length -> actual.write(data, at, length); length })
                                    assertEquals((targetFrames - skip) * outputFrameBytes, size)
                                    assertArrayEquals("$sourceFormat->$targetFormat channels=$targetChannels start=$start count=$count target=$targetFrames batch=$batchFrames skip=$skip",
                                        expected.copyOfRange(skip.toInt() * outputFrameBytes, expected.size), actual.toByteArray())
                                }
                            }
                        }
                    }
                }
            }
        } finally { root.deleteRecursively() }
    }
}
