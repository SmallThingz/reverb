package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class NormalizedExportFrameBudgetTest {
    @Test(timeout = 10_000)
    fun roundedLiveAndSnapshotRangesKeepNewestFramesWithinActualWriterBudget() {
        for (snapshot in listOf(false, true)) for (mixedRate in listOf(false, true)) {
            val parent = File("build/tmp/export-frame-budget").apply { mkdirs() }
            val root = Files.createTempDirectory(parent.toPath(), "range-").toFile()
            try {
                PersistentAudioChunkStore(File(root, "history")).use { store ->
                    val targetRate = if (mixedRate) 16_000 else 96_000
                    val targetFormat = PcmSampleFormat.PCM_FLOAT
                    fun append(rate: Int, channels: Int, format: PcmSampleFormat, startValue: Float) {
                        store.configure(RetentionMode.SIZE, 1_048_576, rate, channels, format)
                        val bytes = ByteBuffer.allocate(40 * channels * format.bytesPerSample)
                            .order(ByteOrder.nativeOrder()).apply {
                                repeat(40) { frame ->
                                    repeat(channels) { channel ->
                                        val value = (startValue + frame * 0.002f) * if (channel == 0) 1 else -1
                                        when (format) {
                                            PcmSampleFormat.PCM_8 -> put((128 + value * 127).toInt().toByte())
                                            PcmSampleFormat.PCM_16 -> putShort((value * 32767).toInt().toShort())
                                            PcmSampleFormat.PCM_FLOAT -> putFloat(value)
                                        }
                                    }
                                }
                            }.array()
                        assertEquals(bytes.size, store.append(bytes, 0, bytes.size))
                        store.sealActiveChunk()
                    }
                    if (mixedRate) append(8_000, 1, PcmSampleFormat.PCM_16, 0.1f)
                    append(targetRate, 2, targetFormat, 0.3f)
                    val budget = if (mixedRate) 50L else 10L
                    val end = (if (mixedRate) 40.0 / 8_000 else 0.0) + 20.75 / targetRate
                    val start = end - budget.toDouble() / targetRate
                    val parentLease = if (snapshot) requireNotNull(store.acquireRange(0.0, store.durationSeconds())) else null
                    try {
                        val lease = requireNotNull(if (snapshot) parentLease!!.acquireSubRange(start, end)
                            else store.acquireRange(start, end))
                        lease.use {
                            val raw = ByteArrayOutputStream()
                            lease.readNormalized(targetRate, 2, targetFormat) { bytes, offset, count ->
                                raw.write(bytes, offset, count)
                                count
                            }
                            val uncapped = raw.toByteArray()
                            val frameBytes = 8
                            assertTrue("Fixture must reproduce outward expansion", uncapped.size > budget * frameBytes)
                            val expected = uncapped.takeLast((budget * frameBytes).toInt()).toByteArray()
                            val file = File(root, "bounded.wav")
                            val access = RandomAccessFile(file, "rw")
                            var closes = 0
                            val output = object : WavSeekableOutput {
                                override fun position(position: Long) { access.seek(position) }
                                override fun write(buffer: ByteBuffer): Int = access.channel.write(buffer)
                                override fun truncate(size: Long) { access.setLength(size) }
                                override fun force(metadata: Boolean) { access.channel.force(metadata) }
                                override fun close() { closes++; access.close() }
                            }
                            val target = RecordingOutputTarget(
                                id = file.path, displayName = file.name, mimeType = "audio/wav",
                                storageType = RecordingStorageType.FILE, directoryId = root.path,
                                startedAtMillis = 1L, file = file, staging = true,
                            )
                            val writer = WavAudioFileWriter(target, targetRate, 2, targetFormat, output,
                                maximumFrameCount = budget)
                            val result = lease.readNormalized(targetRate, 2, targetFormat, budget) { bytes, offset, count ->
                                writer.write(bytes, offset, count)
                                count
                            }
                            assertEquals(budget * frameBytes, result.sampleBytes)
                            assertEquals(budget.toDouble() / targetRate, result.durationSeconds, 0.0)
                            assertThrows(IOException::class.java) { writer.write(ByteArray(frameBytes), 0, frameBytes) }
                            writer.close()
                            writer.close()
                            assertEquals(1, closes)
                            val actual = file.readBytes()
                            val header = writer.expectedHeaderBytes
                            assertArrayEquals(header, actual.copyOfRange(0, header.size))
                            assertArrayEquals(expected, actual.copyOfRange(header.size, actual.size))
                            // Independent endpoint check: the newest selected source frame is 20.
                            val finalFrame = ByteBuffer.wrap(expected, expected.size - frameBytes, frameBytes)
                                .order(ByteOrder.nativeOrder())
                            assertEquals(0.34f, finalFrame.float, 0.00001f)
                            assertEquals(-0.34f, finalFrame.float, 0.00001f)
                        }
                    } finally { parentLease?.close() }
                }
            } finally { root.deleteRecursively() }
        }
    }

    @Test
    fun exactFloatStereoRiffBudgetDoesNotRoundThroughSeconds() {
        assertEquals(536_870_905L, exportFrameLimit(ExportFormat.WAV, ExportCodec.PCM_16,
            96_000, 2, PcmSampleFormat.PCM_FLOAT))
        assertEquals(0xF_FFFF_FFFFL, exportFrameLimit(ExportFormat.FLAC, ExportCodec.PCM_16,
            96_000, 2, PcmSampleFormat.PCM_16))
    }
}
