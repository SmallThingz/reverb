package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class PcmPreviewBatchTest {
    private data class Output(val bytes: ByteArray, val counts: List<Int>, val result: PersistentAudioChunkStore.ReadResult)

    private inline fun withStore(
        rate: Int,
        channels: Int,
        format: PcmSampleFormat,
        chunkFrames: Int,
        block: (PersistentAudioChunkStore, File) -> Unit,
    ) {
        val parent = File("build/tmp/pcm-preview-batch").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "store-").toFile()
        try {
            PersistentAudioChunkStore(root).use { store ->
                store.configure(RetentionMode.SIZE, chunkFrames.toLong() * channels * format.bytesPerSample * 16,
                    rate, channels, format)
                block(store, root)
            }
        } finally { root.deleteRecursively() }
    }

    private fun signal(frames: Int, channels: Int, format: PcmSampleFormat): ByteArray =
        ByteBuffer.allocate(frames * channels * format.bytesPerSample).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { frame -> repeat(channels) { channel ->
                val value = 0.4 * sin(2 * PI * (0.037 + channel * 0.011) * frame) + channel * 0.1
                when (format) {
                    PcmSampleFormat.PCM_8 -> put((128 + (value * 128).roundToInt()).coerceIn(0, 255).toByte())
                    PcmSampleFormat.PCM_16 -> putShort((value * 32768).roundToInt().coerceIn(-32768, 32767).toShort())
                    PcmSampleFormat.PCM_FLOAT -> putFloat(value.toFloat())
                }
            } }
        }.array()

    private fun read(
        lease: PersistentAudioChunkStore.RangeLease,
        rate: Int = 24_000,
        channels: Int = 1,
        format: PcmSampleFormat = PcmSampleFormat.PCM_16,
        budget: Long = Long.MAX_VALUE,
        batch: Int = PcmNormalizer.DEFAULT_OUTPUT_BATCH_BYTES,
    ): Output {
        val bytes = ByteArrayOutputStream()
        val counts = ArrayList<Int>()
        val result = lease.readNormalized(rate, channels, format, budget, batch) { array, offset, count ->
            assertTrue(count > 0 && count <= batch)
            assertEquals(0, count % (channels * format.bytesPerSample))
            bytes.write(array, offset, count)
            counts += count
            count
        }
        assertEquals(bytes.size().toLong(), result.sampleBytes)
        return Output(bytes.toByteArray(), counts, result)
    }

    @Test fun previewBatchRetainsConvertedBytesAcrossRatesFormatsChannelsAndChunkHalos() {
        for (rate in listOf(48_000, 44_100)) for (channels in 1..2) for (format in PcmSampleFormat.entries) {
            withStore(rate, channels, format, 512) { store, _ ->
                val input = signal(6145, channels, format)
                assertEquals(input.size, store.append(input, 0, input.size))
                requireNotNull(store.acquireRange(0.0031, store.durationSeconds() - 0.0053)).use { lease ->
                    for (budget in listOf(Long.MAX_VALUE, 1317L, 37L, 0L)) {
                        val expected = read(lease, budget = budget)
                        val actual = read(lease, budget = budget, batch = 2400)
                        assertArrayEquals(expected.bytes, actual.bytes)
                        assertEquals(expected.result, actual.result)
                    }
                    // The byte limit also stays frame-aligned for converted stereo float output.
                    val expected = read(lease, channels = 2, format = PcmSampleFormat.PCM_FLOAT)
                    val actual = read(lease, channels = 2, format = PcmSampleFormat.PCM_FLOAT, batch = 2401)
                    assertArrayEquals(expected.bytes, actual.bytes)
                    assertEquals(expected.result, actual.result)
                }
            }
        }
    }

    @Test fun previewEmitsBeforeAnExportSizedBatchIsReadyAndKeepsTheFinalTail() {
        withStore(48_000, 1, PcmSampleFormat.PCM_16, 8192) { store, _ ->
            val input = signal(67555, 1, PcmSampleFormat.PCM_16)
            assertEquals(input.size, store.append(input, 0, input.size))
            requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                val exported = read(lease)
                val preview = read(lease, batch = 2400)
                assertEquals(65536, exported.counts.first().toInt())
                assertEquals(2400, preview.counts.first().toInt())
                assertTrue(preview.counts.size > exported.counts.size)
                assertTrue(preview.counts.last() in 1 until 2400)
                assertArrayEquals(exported.bytes, preview.bytes)
                assertEquals(exported.result, preview.result)
            }
        }
    }

    @Test fun matchingFormatFastPathHonorsTheBoundAndPreservesEveryOriginalBit() {
        for (channels in 1..2) for (format in PcmSampleFormat.entries) {
            withStore(24_000, channels, format, 257) { store, _ ->
                val input = ByteArray(2003 * channels * format.bytesPerSample) { (it * 37 + 11).toByte() }
                assertEquals(input.size, store.append(input, 0, input.size))
                requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                    val actual = read(lease, channels = channels, format = format, batch = 241)
                    assertArrayEquals(input, actual.bytes)
                    val expectedTail = read(lease, channels = channels, format = format, budget = 73)
                    val actualTail = read(lease, channels = channels, format = format, budget = 73, batch = 241)
                    assertArrayEquals(expectedTail.bytes, actualTail.bytes)
                    assertEquals(expectedTail.result, actualTail.result)
                }
            }
        }
    }

    @Test fun separateCaptureRunsRetainTheirPhaseAndOutputWhenBatchesAreSmall() {
        withStore(44_100, 2, PcmSampleFormat.PCM_FLOAT, 512) { store, _ ->
            val first = signal(2049, 2, PcmSampleFormat.PCM_FLOAT)
            assertEquals(first.size, store.append(first, 0, first.size))
            store.sealActiveChunk()
            val second = signal(3001, 2, PcmSampleFormat.PCM_FLOAT)
            assertEquals(second.size, store.append(second, 0, second.size))
            requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                val expected = read(lease)
                val actual = read(lease, batch = 2400)
                assertArrayEquals(expected.bytes, actual.bytes)
                assertEquals(expected.result, actual.result)
            }
        }
    }

    @Test fun cancellationAfterFirstEmissionStopsBothConvertedAndMatchingPaths() {
        for (rate in listOf(24_000, 48_000)) {
            withStore(rate, 1, PcmSampleFormat.PCM_16, 4096) { store, _ ->
                val input = signal(8192, 1, PcmSampleFormat.PCM_16)
                assertEquals(input.size, store.append(input, 0, input.size))
                requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                    var calls = 0
                    try {
                        assertThrows(InterruptedIOException::class.java) {
                            lease.readNormalized(24_000, 1, PcmSampleFormat.PCM_16, Long.MAX_VALUE, 2400) { _, _, count ->
                                assertTrue(count in 1..2400)
                                calls++
                                Thread.currentThread().interrupt()
                                count
                            }
                        }
                        assertEquals(1, calls)
                        assertTrue(Thread.currentThread().isInterrupted)
                    } finally { Thread.interrupted() }
                }
            }
        }
    }

    @Test fun corruptPayloadNeverContributesSamplesEvenWhenHealthyPrefixWasEmitted() {
        for (corruptChunk in listOf(0, 1)) {
            withStore(48_000, 1, PcmSampleFormat.PCM_16, 4096) { store, root ->
                val input = signal(8192, 1, PcmSampleFormat.PCM_16)
                assertEquals(input.size, store.append(input, 0, input.size))
                requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                    val expected = read(lease).bytes
                    val source = File(File(root, BUFFER_CHUNKS_FOLDER_NAME), corruptChunk.toString())
                    val corrupted = source.readBytes()
                    corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
                    source.writeBytes(corrupted)
                    val delivered = ByteArrayOutputStream()
                    assertThrows(IOException::class.java) {
                        lease.readNormalized(24_000, 1, PcmSampleFormat.PCM_16, Long.MAX_VALUE, 2400) { bytes, offset, count ->
                            delivered.write(bytes, offset, count)
                            count
                        }
                    }
                    assertEquals(if (corruptChunk == 0) 0 else 2400, delivered.size())
                    assertArrayEquals(expected.copyOf(delivered.size()), delivered.toByteArray())
                }
            }
        }
    }

    @Test fun invalidBatchBoundsFailBeforeDeliveringAnyPcm() {
        withStore(48_000, 1, PcmSampleFormat.PCM_16, 512) { store, _ ->
            val input = signal(1024, 1, PcmSampleFormat.PCM_16)
            assertEquals(input.size, store.append(input, 0, input.size))
            requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                for (batch in listOf(Int.MIN_VALUE, -1, 0, 1, 65537, Int.MAX_VALUE)) {
                    assertThrows(IllegalArgumentException::class.java) {
                        lease.readNormalized(24_000, 1, PcmSampleFormat.PCM_16, Long.MAX_VALUE, batch) { _, _, _ ->
                            fail("Invalid batch must not reach the consumer")
                            0
                        }
                    }
                }
                assertArrayEquals(read(lease).bytes, read(lease, batch = 3).bytes)
            }
        }
    }
}
