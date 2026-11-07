package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrozenPcmRangeParityTest {
    @Test
    fun blockedAdmissionActionDoesNotBlockCaptureAndItsLeaseSurvivesConcurrentClear() = withRoot { root ->
        val rate = 12_000
        val half = floats(0f, .75f)
        val frozenExpected = floats(0f, .375f, .75f, .75f)
        val continuousExpected = floats(0f, .375f, .75f, .375f, 0f, .375f, .75f, .75f)
        PersistentAudioChunkStore(root).use { store ->
            store.configure(RetentionMode.SIZE, 4096, rate, 1, PcmSampleFormat.PCM_FLOAT)
            assertEquals(half.size, store.append(half, 0, half.size))
            val workers = Executors.newFixedThreadPool(2)
            val actionEntered = CountDownLatch(1)
            val releaseAction = CountDownLatch(1)
            try {
                requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { selected ->
                    val admission = workers.submit<ByteArray> {
                        selected.withSealedRange { frozen ->
                            actionEntered.countDown()
                            assertTrue("Admission action was never released", releaseAction.await(10, TimeUnit.SECONDS))
                            read(frozen, rate * 2)
                        }
                    }
                    try {
                        assertTrue(actionEntered.await(5, TimeUnit.SECONDS))
                        val captured = workers.submit<ByteArray> {
                            assertEquals(half.size, store.append(half, 0, half.size))
                            val combined = requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use {
                                read(it, rate * 2)
                            }
                            store.clear()
                            combined
                        }
                        // Completing append/read/Clear while admission remains blocked proves
                        // its disk-work callback no longer owns the capture-store monitor.
                        assertArrayEquals(continuousExpected, captured.get(5, TimeUnit.SECONDS))
                        assertFalse(admission.isDone)
                        assertEquals(0.0, store.durationSeconds(), 0.0)
                        releaseAction.countDown()
                        assertArrayEquals(frozenExpected, admission.get(5, TimeUnit.SECONDS))
                        assertArrayEquals(frozenExpected, read(selected, rate * 2))
                    } finally {
                        releaseAction.countDown()
                    }
                }
            } finally {
                releaseAction.countDown()
                workers.shutdownNow()
                assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun frozenSameRateRangesPreserveOriginalBytesAndNewestFrameLimits() = withRoot { root ->
        for (format in PcmSampleFormat.entries) for (channels in 1..2) {
            val frameBytes = channels * format.bytesPerSample
            val source = ByteArray(1027 * frameBytes) { (it * 37 + 11).toByte() }
            PersistentAudioChunkStore(File(root, "$format-$channels")).use { store ->
                store.configure(RetentionMode.SIZE, 128L * frameBytes * 16, 44_100, channels, format)
                assertEquals(source.size, store.append(source, 0, source.size))
                requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                    val frozen = lease.withSealedRange { it }
                    assertTrue(frozen.segments.size > 1)
                    assertEquals(format == PcmSampleFormat.PCM_16, frozen.hasExactPcm16Format(44_100, channels))
                    assertFalse(frozen.hasExactPcm16Format(48_000, channels))
                    assertArrayEquals(source, read(frozen, 44_100, channels, format))
                    for (limit in listOf(0L, 1L, 73L, 1027L, 2048L)) {
                        val frames = minOf(limit, 1027L).toInt()
                        val expected = source.copyOfRange(source.size - frames * frameBytes, source.size)
                        assertArrayEquals(expected, read(frozen, 44_100, channels, format, limit))
                    }
                }
            }
        }
    }

    @Test
    fun frozenInteriorResamplingKeepsBothContextSidesAndMatchesIndependentContiguousSource() = withRoot { root ->
        val sourceRate = 48_000
        val targetRate = 16_000
        val source = floats(*FloatArray(1024) { frame ->
            (0.35 * sin(2 * PI * frame * 0.017) + if (frame % 64 == 0) 0.2 else 0.0).toFloat()
        })
        val raw = File(root, "contiguous.pcm").apply { writeBytes(source) }
        val firstFrame = 256
        val selectedFrames = 512
        val targetFrames = (selectedFrames.toDouble() * targetRate / sourceRate).roundToLong()
        val expected = ByteArrayOutputStream()
        PcmNormalizer.normalizeSegment(raw, 0L, source.size.toLong(),
            CRC32().apply { update(source) }.value.toInt(), firstFrame * 4L, selectedFrames.toLong(),
            sourceRate, 1, PcmSampleFormat.PCM_FLOAT, targetFrames, 1, PcmSampleFormat.PCM_FLOAT,
            28) { bytes, offset, count -> expected.write(bytes, offset, count); count }

        PersistentAudioChunkStore(File(root, "store")).use { store ->
            // 64-frame chunks put both selection boundaries at real chunk boundaries.
            store.configure(RetentionMode.SIZE, 4096, sourceRate, 1, PcmSampleFormat.PCM_FLOAT)
            assertEquals(source.size, store.append(source, 0, source.size))
            requireNotNull(store.acquireRange(firstFrame.toDouble() / sourceRate,
                (firstFrame + selectedFrames).toDouble() / sourceRate)).use { lease ->
                val before = read(lease, targetRate)
                val frozen = lease.withSealedRange { it }
                assertTrue(frozen.leadingContext.isNotEmpty())
                assertTrue(frozen.trailingContext.isNotEmpty())
                val actual = read(frozen, targetRate)
                assertArrayEquals(before, actual)
                assertFloatSamplesClose(expected.toByteArray(), actual)
                for (limit in listOf(0L, 1L, 17L, targetFrames, targetFrames + 20)) {
                    val bounded = read(frozen, targetRate, maximumFrames = limit)
                    assertArrayEquals(actual.copyOfRange(actual.size - bounded.size, actual.size), bounded)
                    assertEquals(minOf(limit, targetFrames) * 4L, bounded.size.toLong())
                }
            }
        }
    }

    @Test
    fun acquiredActivePrefixDoesNotGrowWhenCaptureAppendsBeforeFreeze() = withRoot { root ->
        val rate = 12_000
        val original = floats(0f, .75f, 0f, .75f)
        val appended = floats(-.5f, .5f)
        val expected = floats(0f, .375f, .75f, .375f)
        PersistentAudioChunkStore(root).use { store ->
            store.configure(RetentionMode.SIZE, 4096, rate, 1, PcmSampleFormat.PCM_FLOAT)
            assertEquals(original.size, store.append(original, 0, original.size))
            requireNotNull(store.acquireRange(0.0, 2.0 / rate)).use { prefix ->
                assertArrayEquals(expected, read(prefix, rate * 2))
                assertEquals(appended.size, store.append(appended, 0, appended.size))
                val frozen = prefix.withSealedRange { it }
                assertEquals(2.0 / rate, frozen.durationSeconds, 0.0)
                assertArrayEquals(expected, read(frozen, rate * 2))
                assertArrayEquals(original.copyOfRange(0, 8), read(frozen, rate))
                assertArrayEquals(expected.copyOfRange(12, 16), read(frozen, rate * 2, maximumFrames = 1))
                // Further recording owns a fresh chunk and cannot alter the frozen acquisition.
                assertEquals(appended.size, store.append(appended, 0, appended.size))
                store.sealActiveChunk()
                assertArrayEquals(expected, read(frozen, rate * 2))
                assertArrayEquals(expected, read(prefix, rate * 2))
            }
        }
    }

    @Test
    fun freezingActiveCapturePreservesContinuityForTheNextChunkWithoutExtendingOldSnapshot() = withRoot { root ->
        val rate = 12_000
        val half = floats(0f, .75f)
        val frozenExpected = floats(0f, .375f, .75f, .75f)
        val continuousExpected = floats(0f, .375f, .75f, .375f, 0f, .375f, .75f, .75f)
        PersistentAudioChunkStore(root).use { store ->
            store.configure(RetentionMode.SIZE, 4096, rate, 1, PcmSampleFormat.PCM_FLOAT)
            assertEquals(half.size, store.append(half, 0, half.size))
            requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { first ->
                requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { second ->
                    val frozen = first.withSealedRange { it }
                    assertArrayEquals(frozenExpected, read(frozen, rate * 2))
                    // Both saves acquired the active tail before either durable admission.
                    val overlapping = second.withSealedRange { it }
                    assertArrayEquals(frozenExpected, read(overlapping, rate * 2))
                    assertEquals(half.size, store.append(half, 0, half.size))
                    requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { whole ->
                        assertArrayEquals(continuousExpected, read(whole, rate * 2))
                        val wholeFrozen = whole.withSealedRange { it }
                        assertEquals(2, wholeFrozen.segments.size)
                        assertArrayEquals(continuousExpected, read(wholeFrozen, rate * 2))
                    }
                    assertArrayEquals(frozenExpected, read(frozen, rate * 2))
                    assertArrayEquals(frozenExpected, read(overlapping, rate * 2))
                    assertArrayEquals(frozenExpected, read(first, rate * 2))
                }
            }
        }
    }

    private fun read(
        frozen: FrozenPcmRange,
        rate: Int,
        channels: Int = 1,
        format: PcmSampleFormat = PcmSampleFormat.PCM_FLOAT,
        maximumFrames: Long = Long.MAX_VALUE,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val batchBytes = channels * format.bytesPerSample * 7
        val result = frozen.readNormalized(rate, channels, format, maximumFrames, batchBytes) { bytes, offset, count ->
            assertTrue(count in 1..batchBytes)
            assertEquals(0, count % (channels * format.bytesPerSample))
            output.write(bytes, offset, count)
            count
        }
        assertEquals(output.size().toLong(), result.sampleBytes)
        return output.toByteArray()
    }

    private fun read(lease: PersistentAudioChunkStore.RangeLease, rate: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val result = lease.readNormalized(rate, 1, PcmSampleFormat.PCM_FLOAT, Long.MAX_VALUE, 28) { bytes, offset, count ->
            output.write(bytes, offset, count)
            count
        }
        assertEquals(output.size().toLong(), result.sampleBytes)
        return output.toByteArray()
    }

    private fun floats(vararg values: Float): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putFloat(it) }
        }.array()

    private fun assertFloatSamplesClose(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        val left = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN)
        val right = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN)
        while (left.hasRemaining()) {
            assertEquals("resampled frame ${left.position() / 4}", left.float.toDouble(), right.float.toDouble(), 0.000002)
        }
    }

    private fun withRoot(block: (File) -> Unit) {
        val parent = File("build/tmp/frozen-pcm-range-parity").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "case-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
