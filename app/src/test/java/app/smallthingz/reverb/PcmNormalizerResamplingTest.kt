package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class PcmNormalizerResamplingTest {
    private fun pcm(format: PcmSampleFormat, channels: Int, frames: Int = 4800): ByteArray =
        ByteBuffer.allocate(frames * channels * format.bytesPerSample).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { frame ->
                repeat(channels) { channel ->
                    val signal = (0.25 + 0.25 * sin(2 * PI * 1000 * frame / 48000) +
                        0.25 * sin(2 * PI * 10000 * frame / 48000))
                    val sign = if (channel == 0) 1 else -1
                    when (format) {
                        PcmSampleFormat.PCM_8 -> put((128 + sign * (signal * 128).roundToInt()).toByte())
                        PcmSampleFormat.PCM_16 -> putShort((sign * (signal * 32768).roundToInt()).toShort())
                        PcmSampleFormat.PCM_FLOAT -> putFloat((sign * signal).toFloat())
                    }
                }
            }
        }.array()

    private fun decode(bytes: ByteArray, format: PcmSampleFormat, channels: Int, channel: Int): FloatArray {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / channels / format.bytesPerSample) { frame ->
            input.position((frame * channels + channel) * format.bytesPerSample)
            when (format) {
                PcmSampleFormat.PCM_8 -> ((input.get().toInt() and 255) - 128) / 128f
                PcmSampleFormat.PCM_16 -> input.short / 32768f
                PcmSampleFormat.PCM_FLOAT -> input.float
            }
        }
    }

    private fun amplitude(values: FloatArray, hz: Int): Double {
        var real = 0.0; var imaginary = 0.0
        for (frame in 128 until values.size - 128) {
            real += values[frame] * cos(2 * PI * hz * frame / 16000)
            imaginary += values[frame] * sin(2 * PI * hz * frame / 16000)
        }
        return 2 * sqrt(real * real + imaginary * imaginary) / (values.size - 256)
    }

    private inline fun withPcm(bytes: ByteArray, block: (File, Int) -> Unit) {
        val parent = File("build/tmp/pcm-resampling").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "pcm-").toFile()
        try {
            val file = File(root, "source.pcm").apply { writeBytes(bytes) }
            block(file, CRC32().apply { update(bytes) }.value.toInt())
        } finally { root.deleteRecursively() }
    }

    private fun normalize(file: File, checksum: Int, sourceFormat: PcmSampleFormat, sourceChannels: Int,
                          targetFormat: PcmSampleFormat, targetChannels: Int = sourceChannels,
                          firstFrame: Int = 0, frames: Int = 4800, targetFrames: Int = frames / 3): ByteArray {
        val output = ByteArrayOutputStream()
        val count = PcmNormalizer.normalizeSegment(file, 0, file.length(), checksum,
            firstFrame.toLong() * sourceChannels * sourceFormat.bytesPerSample, frames.toLong(), 48000,
            sourceChannels, sourceFormat, targetFrames.toLong(), targetChannels, targetFormat) { bytes, offset, size ->
            output.write(bytes, offset, size); size
        }
        assertEquals(targetFrames.toLong() * targetChannels * targetFormat.bytesPerSample, count)
        return output.toByteArray()
    }

    @Test fun exportConversionFiltersAllPcmFormatsAndBothChannelsWithoutChangingFrameCounts() {
        for (sourceFormat in PcmSampleFormat.entries) for (channels in 1..2) {
            withPcm(pcm(sourceFormat, channels)) { file, checksum ->
                for (targetFormat in PcmSampleFormat.entries) {
                    val output = normalize(file, checksum, sourceFormat, channels, targetFormat)
                    assertEquals(1600 * channels * targetFormat.bytesPerSample, output.size)
                    for (channel in 0 until channels) {
                        val values = decode(output, targetFormat, channels, channel)
                        val mean = values.sliceArray(128 until values.size - 128).average()
                        assertEquals(if (channel == 0) 0.25 else -0.25, mean, 0.01)
                        assertEquals(0.25, amplitude(values, 1000), 0.012)
                        assertTrue("Aliased 10 kHz became 6 kHz: $sourceFormat/$targetFormat/$channels",
                            amplitude(values, 6000) < 0.01)
                    }
                }
                if (channels == 2) {
                    val mono = normalize(file, checksum, sourceFormat, channels, PcmSampleFormat.PCM_FLOAT, 1)
                    assertTrue(decode(mono, PcmSampleFormat.PCM_FLOAT, 1, 0).all { kotlin.math.abs(it) < 0.0001f })
                }
            }
        }
    }

    @Test fun sameRateSameGeometryStillCopiesEveryOriginalByte() {
        for (format in PcmSampleFormat.entries) for (channels in 1..2) {
            val bytes = ByteArray(73 * channels * format.bytesPerSample) { (it * 31).toByte() }
            withPcm(bytes) { file, checksum ->
                assertArrayEquals(bytes, normalize(file, checksum, format, channels, format, frames = 73, targetFrames = 73))
            }
        }
    }

    @Test fun subrangeHaloMatchesWholePayloadConversionAndCannotBypassChecksum() {
        withPcm(pcm(PcmSampleFormat.PCM_FLOAT, 1)) { file, checksum ->
            val whole = normalize(file, checksum, PcmSampleFormat.PCM_FLOAT, 1, PcmSampleFormat.PCM_FLOAT)
            val part = normalize(file, checksum, PcmSampleFormat.PCM_FLOAT, 1, PcmSampleFormat.PCM_FLOAT,
                firstFrame = 1500, frames = 1500)
            assertArrayEquals(whole.copyOfRange(500 * 4, 1000 * 4), part)
            val corrupt = file.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() }
            file.writeBytes(corrupt)
            var delivered = 0
            assertThrows(IOException::class.java) {
                PcmNormalizer.normalizeSegment(file, 0, file.length(), checksum, 1500L * 4, 1500, 48000, 1,
                    PcmSampleFormat.PCM_FLOAT, 500, 1, PcmSampleFormat.PCM_FLOAT) { _, _, count -> delivered += count; count }
            }
            assertEquals(0, delivered)
        }
    }

    @Test fun normalizedRangeBudgetStillKeepsTheNewestFilteredFrames() {
        val parent = File("build/tmp/pcm-resampling").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "store-").toFile()
        try {
            PersistentAudioChunkStore(File(root, "history")).use { store ->
                store.configure(RetentionMode.SIZE, 1_048_576, 48000, 2, PcmSampleFormat.PCM_16)
                val source = pcm(PcmSampleFormat.PCM_16, 2)
                assertEquals(source.size, store.append(source, 0, source.size))
                store.sealActiveChunk()
                requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { range ->
                    fun read(budget: Long): ByteArray {
                        val output = ByteArrayOutputStream()
                        val result = range.readNormalized(16000, 2, PcmSampleFormat.PCM_16, budget) { bytes, offset, count ->
                            output.write(bytes, offset, count); count
                        }
                        assertEquals(output.size().toLong(), result.sampleBytes)
                        return output.toByteArray()
                    }
                    val all = read(Long.MAX_VALUE)
                    val bounded = read(1000)
                    assertEquals(4000, bounded.size)
                    assertArrayEquals(all.copyOfRange(all.size - bounded.size, all.size), bounded)
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun sourceVerificationAndFilteringObserveCancellationBeforeDelivery() {
        withPcm(pcm(PcmSampleFormat.PCM_16, 1)) { file, checksum ->
            try {
                Thread.currentThread().interrupt()
                assertThrows(InterruptedIOException::class.java) {
                    normalize(file, checksum, PcmSampleFormat.PCM_16, 1, PcmSampleFormat.PCM_16)
                }
                assertTrue(Thread.currentThread().isInterrupted)
            } finally { Thread.interrupted() }
        }
    }

    @Test fun savedWavAuditionUsesFilteredHaloAndSignedStereoDownmix() {
        for (format in PcmSampleFormat.entries) for (channels in 1..2) {
            val data = pcm(format, channels, frames = 9600)
            withPcm(buildWavHeaderBytes(48000, channels, format, data.size.toLong()) + data) { file, _ ->
                FileInputStream(file).channel.use { channel ->
                    val layout = readWavPcmLayout(channel)
                    val output = readWavPcm16MonoRange(channel, layout, 0.05, 0.15, 16000)
                    assertTrue(output.size / 2 in 1600..1601)
                    val values = decode(output, PcmSampleFormat.PCM_16, 1, 0).copyOf(1600)
                    if (channels == 1) {
                        assertEquals(0.25, amplitude(values, 1000), 0.012)
                        assertTrue(amplitude(values, 6000) < 0.01)
                    } else assertTrue(values.all { kotlin.math.abs(it) < 0.0001f })
                }
            }
        }
    }

    @Test fun savedWavExtremeDecimationFailsBeforeReadingOrAllocatingTheRange() {
        withPcm(ByteArray(0)) { file, _ ->
            FileInputStream(file).channel.use { channel ->
                val layout = WavPcmLayout(Int.MAX_VALUE, 1, PcmSampleFormat.PCM_16, 0, 2)
                val error = assertThrows(IOException::class.java) {
                    readWavPcm16MonoRange(channel, layout, 0.0, layout.durationSeconds, 1)
                }
                assertEquals("PCM conversion ratio is too large", error.message)
            }
        }
    }
}
