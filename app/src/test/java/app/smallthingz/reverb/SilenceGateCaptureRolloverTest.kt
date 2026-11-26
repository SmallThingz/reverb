package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SilenceGateCaptureRolloverTest {
    @Test fun existingChunkRolloverUsesOriginalHopTimeAndSliceRelativeOffset() = withRoots { root ->
        val seed = pcm(48)
        val kept = pcm(HOP * 2)
        val slice = ByteArray(7) + kept + ByteArray(3)
        val framesUntilRollover = HOP + 32
        val capacity = (seed.size + framesUntilRollover * FRAME_BYTES) * RETENTION_CHUNKS
        val legacyRoot = File(root, "legacy")
        val coalescedRoot = File(root, "coalesced")

        configuredStore(legacyRoot, capacity).use { store ->
            assertEquals(seed.size, store.appendCapturedAt(seed, 0, seed.size, START_MILLIS - 1))
            forEachHop(kept) { offset, count, time ->
                assertEquals(count, store.appendCapturedAt(kept, offset, count, time))
            }
            store.sealActiveChunk()
        }
        val requestedOffsets = mutableListOf<Int>()
        val timestamps = KeptHopTimestamps(START_MILLIS, 0L, RATE, HOP)
        configuredStore(coalescedRoot, capacity).use { store ->
            assertEquals(seed.size, store.appendCapturedAt(seed, 0, seed.size, START_MILLIS - 1))
            assertEquals(kept.size, store.appendCapturedAt(slice, 7, kept.size, START_MILLIS) { frameOffset ->
                requestedOffsets += frameOffset
                timestamps.atFrame(frameOffset)
            })
            store.sealActiveChunk()
        }

        assertEquals(listOf(framesUntilRollover), requestedOffsets)
        val expectedTimes = listOf(START_MILLIS - 1, START_MILLIS + 21)
        assertEquals(expectedTimes, chunkTimes(legacyRoot))
        assertEquals(expectedTimes, chunkTimes(coalescedRoot))
        assertArrayEquals(seed + kept, reopenedPcm(legacyRoot, capacity))
        assertArrayEquals(seed + kept, reopenedPcm(coalescedRoot, capacity))
    }

    @Test fun oneShotOverflowPreservesItsOwnRoundingUntilTheNextOldHop() = withRoots { root ->
        val kept = pcm(HOP + 96)
        val overflowFrame = HOP + 32
        val oneShotCapacity = overflowFrame * FRAME_BYTES
        val loopingCapacity = 32 * FRAME_BYTES * RETENTION_CHUNKS
        val legacyOne = File(root, "legacy-one")
        val legacyLoop = File(root, "legacy-loop")
        val coalescedOne = File(root, "coalesced-one")
        val coalescedLoop = File(root, "coalesced-loop")

        configuredStore(legacyOne, oneShotCapacity, looping = false).use { oneShot ->
            configuredStore(legacyLoop, loopingCapacity).use { loop ->
                forEachHop(kept) { offset, count, time ->
                    val written = oneShot.appendCapturedAt(kept, offset, count, time)
                    if (written < count) {
                        val overflowTime = time + (written / FRAME_BYTES) * 1000L / RATE
                        assertEquals(count - written,
                            loop.appendCapturedAt(kept, offset + written, count - written, overflowTime))
                    }
                }
                oneShot.sealActiveChunk()
                loop.sealActiveChunk()
            }
        }
        val requestedLoopOffsets = mutableListOf<Int>()
        val timestamps = KeptHopTimestamps(START_MILLIS, 0L, RATE, HOP)
        configuredStore(coalescedOne, oneShotCapacity, looping = false).use { oneShot ->
            configuredStore(coalescedLoop, loopingCapacity).use { loop ->
                val written = oneShot.appendCapturedAt(kept, 0, kept.size, START_MILLIS, timestamps::atFrame)
                assertEquals(oneShotCapacity, written)
                val overflowTime = timestamps.atFrame(written / FRAME_BYTES)
                assertEquals(kept.size - written,
                    loop.appendCapturedAt(kept, written, kept.size - written, overflowTime) { frameOffset ->
                        requestedLoopOffsets += frameOffset
                        timestamps.atOverflowFrame(written / FRAME_BYTES, frameOffset)
                    })
                oneShot.sealActiveChunk()
                loop.sealActiveChunk()
            }
        }

        assertEquals(listOf(0, 32), requestedLoopOffsets)
        // Both 32-frame offsets floor to zero independently in the old second hop.
        assertEquals(listOf(START_MILLIS + 21, START_MILLIS + 21), chunkTimes(legacyLoop))
        assertEquals(chunkTimes(legacyLoop), chunkTimes(coalescedLoop))
        assertEquals(chunkTimes(legacyOne), chunkTimes(coalescedOne))
        assertEquals(START_MILLIS + 42, timestamps.atOverflowFrame(overflowFrame, HOP - 32))
        assertArrayEquals(kept.copyOfRange(0, oneShotCapacity),
            reopenedPcm(coalescedOne, oneShotCapacity, looping = false))
        assertArrayEquals(kept.copyOfRange(oneShotCapacity, kept.size), reopenedPcm(coalescedLoop, loopingCapacity))
    }

    private fun forEachHop(bytes: ByteArray, append: (offset: Int, count: Int, timestamp: Long) -> Unit) {
        val hopBytes = HOP * FRAME_BYTES
        for (offset in bytes.indices step hopBytes) {
            val time = START_MILLIS + (offset / FRAME_BYTES) * 1000L / RATE
            append(offset, minOf(hopBytes, bytes.size - offset), time)
        }
    }

    private fun configuredStore(root: File, capacity: Int, looping: Boolean = true) =
        PersistentAudioChunkStore(root, overwriteOldest = looping).also {
            it.configure(RetentionMode.SIZE, capacity.toLong(), RATE, 1, PcmSampleFormat.PCM_16)
        }

    private fun reopenedPcm(root: File, capacity: Int, looping: Boolean = true): ByteArray =
        configuredStore(root, capacity, looping).use { store ->
            val output = ByteArrayOutputStream()
            requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { range ->
                range.readNormalized(RATE, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
                    output.write(bytes, offset, count)
                    count
                }
            }
            output.toByteArray()
        }

    private fun chunkTimes(root: File): List<Long> =
        requireNotNull(File(root, BUFFER_CHUNKS_FOLDER_NAME).listFiles())
            .mapNotNull { file -> file.name.toUIntOrNull()?.let { it to file } }
            .sortedBy { it.first }
            .map { (_, file) ->
                RandomAccessFile(file, "r").use {
                    it.seek(16)
                    java.lang.Long.reverseBytes(it.readLong())
                }
            }

    private fun pcm(frames: Int): ByteArray = ByteArray(frames * FRAME_BYTES) { (it * 29 + it / 17).toByte() }

    private fun withRoots(verify: (File) -> Unit) {
        val root = Files.createTempDirectory("kept-hop-rollover-").toFile()
        try { verify(root) } finally { root.deleteRecursively() }
    }

    private companion object {
        const val RATE = 48_000
        const val HOP = 1024
        const val FRAME_BYTES = 2
        const val RETENTION_CHUNKS = 16
        const val START_MILLIS = 10_000L
    }
}
