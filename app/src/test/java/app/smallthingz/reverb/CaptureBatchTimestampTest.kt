package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Actual chunk/lease geometry with fixed wall anchors; not a hardware capture-clock test. */
class CaptureBatchTimestampTest {
    @Test fun oneDeliveredBatchRetainsEveryFrameAndEveryChunkOffset() = withRoot { root ->
        val source = pcm(8_000, 0)
        PersistentAudioChunkStore(root, directorySync = {}).use { store ->
            configure(store)
            append(store, source, deliveredAtMillis = 11_000L)
            requireNotNull(store.acquireRange(0.0, 1.0)).use { lease ->
                assertEquals(10_000L, lease.startedAtMillis)
                assertEquals(11_000L, lease.endedAtMillis)
                assertArrayEquals(source, read(lease))
                // Eight real chunks of 1,000 frames each. These intervals are binary-exact seconds.
                repeat(8) { index ->
                    requireNotNull(lease.acquireSubRange(index / 8.0, (index + 1) / 8.0)).use { child ->
                        assertEquals(10_000L + index * 125L, child.startedAtMillis)
                        assertEquals(10_000L + (index + 1) * 125L, child.endedAtMillis)
                        assertArrayEquals(source.copyOfRange(index * 2_000, (index + 1) * 2_000), read(child))
                    }
                }
            }
        }
    }

    @Test fun adjacentBatchesKeepTheirSourceOffsetsAcrossRollover() = withRoot { root ->
        val first = pcm(8_000, 0)
        val second = pcm(8_000, 8_000)
        PersistentAudioChunkStore(root, directorySync = {}).use { store ->
            configure(store)
            append(store, first, 11_000L)
            append(store, second, 12_000L)
            requireNotNull(store.acquireRange(0.0, 2.0)).use { lease ->
                assertEquals(10_000L, lease.startedAtMillis)
                assertEquals(12_000L, lease.endedAtMillis)
                assertArrayEquals(first + second, read(lease))
                requireNotNull(lease.acquireSubRange(0.875, 1.125)).use { crossing ->
                    assertEquals(10_875L, crossing.startedAtMillis)
                    assertEquals(11_125L, crossing.endedAtMillis)
                    assertArrayEquals((first + second).copyOfRange(14_000, 18_000), read(crossing))
                }
            }
        }
    }

    @Test fun sealAndRestartPreserveDistinctWallTimesEvenAfterClockRollback() = withRoot { root ->
        val first = pcm(8_000, 0)
        val later = pcm(8_000, 8_000)
        PersistentAudioChunkStore(root, directorySync = {}).use { store ->
            configure(store)
            append(store, first, 11_000L)
            store.sealActiveChunk()
        }
        PersistentAudioChunkStore(root, directorySync = {}).use { reopened ->
            configure(reopened)
            // A separate epoch after a wall-clock correction is not forced onto the old clock.
            append(reopened, later, 6_000L)
            requireNotNull(reopened.acquireRange(0.0, 2.0)).use { lease ->
                assertArrayEquals(first + later, read(lease))
                requireNotNull(lease.acquireSubRange(1.0, 2.0)).use { child ->
                    assertEquals(5_000L, child.startedAtMillis)
                    assertEquals(6_000L, child.endedAtMillis)
                    assertArrayEquals(later, read(child))
                }
            }
        }
    }

    @Test fun oneShotOverflowUsesItsOffsetWithinTheOriginalBatch() = withRoot { root ->
        val source = pcm(24_000, 0)
        PersistentAudioChunkStore(File(root, "one-shot"), overwriteOldest = false, directorySync = {}).use { oneShot ->
            PersistentAudioChunkStore(File(root, "looping"), directorySync = {}).use { looping ->
                configure(oneShot)
                configure(looping)
                val start = captureBatchStartedAtMillis(13_000L, source.size, 2, 8_000)
                val firstBytes = oneShot.appendCapturedAt(source, 0, source.size, start)
                assertEquals(32_000, firstBytes)
                val overflowStart = recordingTimestampWithOffset(start, firstBytes / 2 * 1000L / 8_000)
                assertEquals(source.size - firstBytes, looping.appendCapturedAt(
                    source, firstBytes, source.size - firstBytes, overflowStart))
                requireNotNull(oneShot.acquireRange(0.0, 2.0)).use { first ->
                    assertEquals(10_000L, first.startedAtMillis)
                    assertEquals(12_000L, first.endedAtMillis)
                    assertArrayEquals(source.copyOfRange(0, firstBytes), read(first))
                }
                requireNotNull(looping.acquireRange(0.0, 1.0)).use { tail ->
                    assertEquals(12_000L, tail.startedAtMillis)
                    assertEquals(13_000L, tail.endedAtMillis)
                    assertArrayEquals(source.copyOfRange(firstBytes, source.size), read(tail))
                }
            }
        }
    }

    @Test fun batchPolicyBoundsEpochAndUsesFramesRatherThanSampleBytes() {
        assertEquals(9_000L, captureBatchStartedAtMillis(10_000L, 384_000, 8, 48_000))
        assertEquals(0L, captureBatchStartedAtMillis(50L, 16_000, 2, 8_000))
        assertEquals(0L, captureBatchStartedAtMillis(Long.MIN_VALUE, 16_000, 2, 8_000))
        assertEquals(Long.MAX_VALUE - 1_000L,
            captureBatchStartedAtMillis(Long.MAX_VALUE, 16_000, 2, 8_000))
    }

    private fun configure(store: PersistentAudioChunkStore) =
        store.configure(RetentionMode.SIZE, 32_000L, 8_000, 1, PcmSampleFormat.PCM_16)

    private fun append(store: PersistentAudioChunkStore, pcm: ByteArray, deliveredAtMillis: Long) {
        val start = captureBatchStartedAtMillis(deliveredAtMillis, pcm.size, 2, 8_000)
        assertEquals(pcm.size, store.appendCapturedAt(pcm, 0, pcm.size, start))
    }

    private fun pcm(frames: Int, firstFrame: Int): ByteArray =
        ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { putShort((firstFrame + it).toShort()) }
        }.array()

    private fun read(lease: PersistentAudioChunkStore.RangeLease): ByteArray {
        val output = ByteArrayOutputStream()
        val result = lease.readNormalized(8_000, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
            output.write(bytes, offset, count)
            count
        }
        assertEquals(output.size().toLong(), result.sampleBytes)
        return output.toByteArray()
    }

    private inline fun withRoot(block: (File) -> Unit) {
        val parent = File(".tmp/capture-batch-time").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "store-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
