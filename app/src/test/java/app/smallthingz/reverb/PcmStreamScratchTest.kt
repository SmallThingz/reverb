package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmStreamScratchTest {
    @Test fun unchangedStreamGrowsOnceAndReusesCapacityForASmallerPartialChunk() = withDirectory { root ->
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(11, 12, 13, 14, 15, 16, 17)
        val third = byteArrayOf(21, 22, 23, 24, 25, 26, 27)
        val last = byteArrayOf(31, 32, 33, 34, 35)
        val range = range(
            chunk(root, 0, first),
            chunk(root, 1, second),
            chunk(root, 2, third),
            chunk(root, 3, last, selectedFrames = 2),
        )
        val output = ByteArrayOutputStream()
        val buffers = mutableListOf<ByteArray>()
        val result = range.readNormalized(8000, 1, PcmSampleFormat.PCM_8, Long.MAX_VALUE, 8) { bytes, offset, count ->
            buffers += bytes
            output.write(bytes, offset, count)
            count
        }

        val expected = first + second + third + last.copyOf(2)
        assertEquals(expected.size.toLong(), result.sampleBytes)
        assertArrayEquals(expected, output.toByteArray())
        assertEquals(4, buffers.size)
        assertNotSame(buffers[0], buffers[1])
        assertSame(buffers[1], buffers[2])
        assertSame(buffers[2], buffers[3])
    }

    @Test fun sealedCaptureSpansReuseTheSameSourceBufferAndPreserveExactBytes() = withDirectory { root ->
        val first = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val second = byteArrayOf(11, 12, 13)
        PersistentAudioChunkStore(root).use { store ->
            store.configure(RetentionMode.SIZE, 1_048_576, 8000, 1, PcmSampleFormat.PCM_8)
            assertEquals(first.size, store.append(first, 0, first.size))
            store.sealActiveChunk()
            assertEquals(second.size, store.append(second, 0, second.size))
            store.sealActiveChunk()
            requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                lease.withSealedRange { range ->
                    assertEquals(2, range.segments.size)
                    assertFalse(range.segments[1].continuesPreviousChunkAtAcquire)
                    val output = ByteArrayOutputStream()
                    val buffers = mutableListOf<ByteArray>()
                    val result = range.readNormalized(8000, 1, PcmSampleFormat.PCM_8, Long.MAX_VALUE, 8) { bytes, offset, count ->
                        buffers += bytes
                        output.write(bytes, offset, count)
                        count
                    }
                    val expected = first + second
                    assertEquals(expected.size.toLong(), result.sampleBytes)
                    assertArrayEquals(expected, output.toByteArray())
                    assertEquals(2, buffers.size)
                    assertSame(buffers[0], buffers[1])
                }
            }
        }
    }

    @Test fun resamplingKeepsPhaseAcrossVariableChunkSizesAndAPartialTail() = withDirectory { root ->
        val source = byteArrayOf(100, 102, 106, 110, 116, 122, 114, 100, 90)
        val range = range(
            chunk(root, 0, source.copyOfRange(0, 3)),
            chunk(root, 1, source.copyOfRange(3, 7)),
            chunk(root, 2, source.copyOfRange(7, 9) + byteArrayOf(66), selectedFrames = 2),
        )
        val expected = ByteArray(source.size * 2) { index ->
            val frame = index / 2
            val first = source[frame].toInt()
            val next = source[minOf(frame + 1, source.lastIndex)].toInt()
            (if (index % 2 == 0) first else (first + next) / 2).toByte()
        }
        val output = ByteArrayOutputStream()
        val result = range.readNormalized(
            16000, 1, PcmSampleFormat.PCM_8, Long.MAX_VALUE, 5, allowOutsideRangeContext = false,
        ) { bytes, offset, count ->
            output.write(bytes, offset, count)
            count
        }

        assertEquals(expected.size.toLong(), result.sampleBytes)
        assertArrayEquals(expected, output.toByteArray())
    }

    @Test fun resamplingRestartsInterpolationAtEachCaptureSpan() = withDirectory { root ->
        val first = chunk(root, 0, byteArrayOf(100, 102, 104, 106))
        val second = chunk(root, 1, byteArrayOf(120, 110, 106)).copy(continuesPreviousChunkAtAcquire = false)
        val output = ByteArrayOutputStream()
        val result = range(first, second).readNormalized(16000, 1, PcmSampleFormat.PCM_8, Long.MAX_VALUE) { bytes, offset, count ->
            output.write(bytes, offset, count)
            count
        }
        val expected = byteArrayOf(100, 101, 102, 103, 104, 105, 106, 106, 120, 115, 110, 108, 106, 106)
        assertEquals(expected.size.toLong(), result.sampleBytes)
        assertArrayEquals(expected, output.toByteArray())
    }

    @Test fun corruptionBeyondALateChunkSliceAbortsBeforeThatChunkCanContribute() = withDirectory { root ->
        val first = chunk(root, 0, ByteArray(8) { 100 })
        val second = chunk(root, 1, ByteArray(8) { 100 })
        val late = chunk(root, 2, ByteArray(65_537) { 120 }, selectedFrames = 2)
        val range = range(first, second, late)
        RandomAccessFile(late.record.file, "rw").use { file ->
            file.seek(file.length() - 1L)
            file.write(121)
        }
        for ((rate, expectedBytes) in listOf(8000 to 16, 16000 to 30)) {
            val output = ByteArrayOutputStream()
            val failure = assertThrows(IOException::class.java) {
                range.readNormalized(rate, 1, PcmSampleFormat.PCM_8, Long.MAX_VALUE, 2) { bytes, offset, count ->
                    output.write(bytes, offset, count)
                    count
                }
            }
            assertEquals("PCM chunk checksum mismatch: 2.pcm", failure.message)
            assertArrayEquals(ByteArray(expectedBytes) { 100 }, output.toByteArray())
        }
    }

    @Test fun concurrentReadsOfMultipleSpansUseDifferentBuffersWhileTheFirstCallbackIsActive() = withDirectory { root ->
        val first = byteArrayOf(1, 2, 3, 4)
        val second = byteArrayOf(11, 12, 13, 14)
        val range = range(
            chunk(root, 0, first),
            chunk(root, 1, second).copy(continuesPreviousChunkAtAcquire = false),
        )
        val callbackStarted = CountDownLatch(1)
        val secondReadFinished = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            lateinit var firstBuffer: ByteArray
            val firstRead = executor.submit<ByteArray> {
                val output = ByteArrayOutputStream()
                range.readNormalized(8000, 1, PcmSampleFormat.PCM_8, Long.MAX_VALUE) { bytes, offset, count ->
                    if (output.size() == 0) {
                        firstBuffer = bytes
                        callbackStarted.countDown()
                        assertTrue(secondReadFinished.await(10, SECONDS))
                    }
                    output.write(bytes, offset, count)
                    count
                }
                output.toByteArray()
            }
            assertTrue(callbackStarted.await(10, SECONDS))
            val secondRead = range.readNormalized(8000, 1, PcmSampleFormat.PCM_8, second.size.toLong()) { bytes, offset, count ->
                assertNotSame(firstBuffer, bytes)
                assertArrayEquals(second, bytes.copyOfRange(offset, offset + count))
                count
            }
            assertEquals(second.size.toLong(), secondRead.sampleBytes)
            secondReadFinished.countDown()
            assertArrayEquals(first + second, firstRead.get(10, SECONDS))
        } finally {
            secondReadFinished.countDown()
            executor.shutdownNow()
        }
    }

    private fun withDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("pcm-stream-scratch-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun chunk(root: File, index: Int, bytes: ByteArray, selectedFrames: Int = bytes.size): PersistentAudioChunkStore.Segment {
        val file = File(root, "$index.pcm").apply { writeBytes(bytes) }
        val checksum = CRC32().apply { update(bytes) }.value.toInt()
        val record = PersistentAudioChunkStore.ChunkRecord(
            index.toUInt(), file, PersistentAudioChunkStore.ChunkState.FINALIZED, 0L, bytes.size.toLong(),
            bytes.size.toLong(), 8000, 1, PcmSampleFormat.PCM_8, checksum, 1L, 0L,
            continuesPreviousChunk = index != 0,
        )
        return PersistentAudioChunkStore.Segment(
            record, 0L, selectedFrames.toLong(), 0L, bytes.size.toLong(), checksum, 0.0, selectedFrames / 8000.0,
        )
    }

    private fun range(vararg segments: PersistentAudioChunkStore.Segment): FrozenPcmRange =
        FrozenPcmRange(segments.asList(), emptyList(), emptyList(), 0L, 0L, segments.sumOf { it.frameCount } / 8000.0)
}
