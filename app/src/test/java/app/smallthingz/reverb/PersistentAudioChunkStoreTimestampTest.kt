package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentAudioChunkStoreTimestampTest {
    @Test
    fun epochZeroParentRange_keepsFirstChunkTimestampAcrossLaterNonzeroChunk() = withRoot { root ->
        val first = pcmBytes()
        val second = ByteArray(first.size) { (first[it].toInt() xor 0x5a).toByte() }
        PersistentAudioChunkStore(root, overwriteOldest = false).use { store ->
            append(store, first, 0L)
            append(store, second, 10_000L)
            requireNotNull(store.acquireRange(0.0, 2.0)).use { range ->
                assertEquals(0L, range.startedAtMillis)
                assertEquals(11_000L, range.endedAtMillis)
                assertEquals(2.0, range.durationSeconds, 0.0)
                assertArrayEquals(first + second, read(range))
            }
        }
    }

    @Test
    fun epochZeroChildRange_keepsFirstSelectedTimestampAcrossLaterNonzeroChunk() = withRoot { root ->
        val first = pcmBytes()
        val second = ByteArray(first.size) { (first[it].toInt() xor 0x5a).toByte() }
        PersistentAudioChunkStore(root, overwriteOldest = false).use { store ->
            append(store, first, 0L)
            append(store, second, 10_000L)
            requireNotNull(store.acquireRange(0.0, 2.0)).use { parent ->
                requireNotNull(parent.acquireSubRange(0.0, 1.5)).use { child ->
                    assertEquals(0L, child.startedAtMillis)
                    assertEquals(10_500L, child.endedAtMillis)
                    assertEquals(1.5, child.durationSeconds, 0.0)
                    assertArrayEquals(first + second.copyOf(8_000), read(child))
                }
            }
        }
    }

    @Test
    fun legacyOffsetDerivedZero_keepsParentAndChildStartAcrossLaterChunk() = withRoot { root ->
        val first = pcmBytes()
        val second = ByteArray(first.size) { (first[it].toInt() xor 0x5a).toByte() }
        PersistentAudioChunkStore(root, overwriteOldest = false).use { store -> append(store, first, 10_000L) }
        val chunk = onlyChunk(root)
        val bytes = chunk.readBytes()
        // Preserve a valid signed legacy header: -250 ms + 2,000 frames at 8 kHz is epoch zero.
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(16, -250L)
        val checksum = CRC32().apply { update(bytes, 0, 40) }.value.toInt()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(40, checksum)
        chunk.writeBytes(bytes)
        assertTrue(File(root, BUFFER_INDEX_A_FILE_NAME).delete())
        File(root, BUFFER_INDEX_B_FILE_NAME).let { if (it.exists()) assertTrue(it.delete()) }
        val expected = first.copyOfRange(4_000, first.size) + second.copyOf(8_000)
        PersistentAudioChunkStore(root, overwriteOldest = false).use { store ->
            append(store, second, 10_000L)
            requireNotNull(store.acquireRange(0.25, 1.5)).use { range ->
                assertEquals(0L, range.startedAtMillis)
                assertEquals(10_500L, range.endedAtMillis)
                assertEquals(1.25, range.durationSeconds, 0.0)
                assertArrayEquals(expected, read(range))
            }
            requireNotNull(store.acquireRange(0.0, 2.0)).use { parent ->
                assertEquals(-250L, parent.startedAtMillis)
                requireNotNull(parent.acquireSubRange(0.25, 1.5)).use { child ->
                    assertEquals(0L, child.startedAtMillis)
                    assertEquals(10_500L, child.endedAtMillis)
                    assertEquals(1.25, child.durationSeconds, 0.0)
                    assertArrayEquals(expected, read(child))
                }
            }
        }
    }

    @Test
    fun nearMaximumParentRange_saturatesBothTimestampsWithoutChangingSelectedPcm() = withRoot { root ->
        val pcm = pcmBytes()
        PersistentAudioChunkStore(root, overwriteOldest = false).use { store ->
            append(store, pcm, Long.MAX_VALUE - 5L)
            requireNotNull(store.acquireRange(0.25, 0.75)).use { range ->
                assertEquals(Long.MAX_VALUE, range.startedAtMillis)
                assertEquals(Long.MAX_VALUE, range.endedAtMillis)
                assertEquals(0.5, range.durationSeconds, 0.0)
                assertArrayEquals(pcm.copyOfRange(4_000, 12_000), read(range))
            }
        }
    }

    @Test
    fun nearMaximumChildRange_saturatesAbsoluteOffsetsWithoutChangingSelectedPcm() = withRoot { root ->
        val pcm = pcmBytes()
        PersistentAudioChunkStore(root, overwriteOldest = false).use { store ->
            append(store, pcm, Long.MAX_VALUE - 5L)
            requireNotNull(store.acquireRange(0.0, 1.0)).use { parent ->
                assertEquals(Long.MAX_VALUE - 5L, parent.startedAtMillis)
                assertEquals(Long.MAX_VALUE, parent.endedAtMillis)
                requireNotNull(parent.acquireSubRange(0.25, 0.75)).use { child ->
                    assertEquals(Long.MAX_VALUE, child.startedAtMillis)
                    assertEquals(Long.MAX_VALUE, child.endedAtMillis)
                    assertArrayEquals(pcm.copyOfRange(4_000, 12_000), read(child))
                }
            }
        }
    }

    @Test
    fun nearMaximumRecovery_saturatesSnapshotAndPreservesWholeChunkBytes() = withRoot { root ->
        val pcm = pcmBytes()
        PersistentAudioChunkStore(root, overwriteOldest = false).use { store ->
            append(store, pcm, Long.MAX_VALUE - 5L)
        }
        val chunk = onlyChunk(root)
        val before = chunk.readBytes()
        PersistentAudioChunkStore(root, overwriteOldest = false).use { reopened ->
            configure(reopened)
            assertEquals(Long.MAX_VALUE, requireNotNull(reopened.peekSnapshot()).lastWriteAtMillis)
            assertArrayEquals(pcm, readAll(reopened))
        }
        assertArrayEquals(before, chunk.readBytes())
    }

    @Test
    fun nearMaximumLoopingTrim_saturatesPersistedHeaderAndRecoversExactTail() = withRoot { root ->
        val pcm = pcmBytes()
        PersistentAudioChunkStore(root).use { store ->
            append(store, pcm, Long.MAX_VALUE - 5L)
            configure(store, 8_000L)
            requireNotNull(store.acquireRange(0.0, 0.5)).use { range ->
                assertEquals(Long.MAX_VALUE, range.startedAtMillis)
                assertEquals(Long.MAX_VALUE, range.endedAtMillis)
                assertArrayEquals(pcm.copyOfRange(8_000, 16_000), read(range))
            }
        }
        assertEquals(Long.MAX_VALUE, headerTimestamp(onlyChunk(root)))
        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 8_000L)
            assertEquals(Long.MAX_VALUE, requireNotNull(reopened.peekSnapshot()).lastWriteAtMillis)
            assertArrayEquals(pcm.copyOfRange(8_000, 16_000), readAll(reopened))
        }
    }

    @Test
    fun nearMaximumOneShotTrim_saturatesLastWriteAndPreservesExactPrefix() = withRoot { root ->
        assertOneShotTrim(root, deferred = false)
    }

    @Test
    fun nearMaximumDeferredOneShotTrim_saturatesLastWriteAndPreservesExactPrefix() = withRoot { root ->
        assertOneShotTrim(root, deferred = true)
    }

    @Test
    fun legacyNegativeHeader_remainsReadableAndTrimmableAcrossRestart() = withRoot { root ->
        val pcm = pcmBytes()
        PersistentAudioChunkStore(root).use { store -> append(store, pcm, 10_000L) }
        val chunk = onlyChunk(root)
        val bytes = chunk.readBytes()
        // Existing chunk headers admit signed timestamps. Recompute their immutable CRC so this
        // models valid legacy storage, not corruption that recovery should quarantine.
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(16, -10_000L)
        val checksum = CRC32().apply { update(bytes, 0, 40) }.value.toInt()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(40, checksum)
        chunk.writeBytes(bytes)
        assertTrue(File(root, BUFFER_INDEX_A_FILE_NAME).delete())
        File(root, BUFFER_INDEX_B_FILE_NAME).let { if (it.exists()) assertTrue(it.delete()) }

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened)
            assertEquals(-9_000L, requireNotNull(reopened.peekSnapshot()).lastWriteAtMillis)
            requireNotNull(reopened.acquireRange(0.0, 1.0)).use { parent ->
                assertEquals(-10_000L, parent.startedAtMillis)
                assertEquals(-9_000L, parent.endedAtMillis)
                requireNotNull(parent.acquireSubRange(0.25, 0.75)).use { child ->
                    assertEquals(-9_750L, child.startedAtMillis)
                    assertEquals(-9_250L, child.endedAtMillis)
                    assertArrayEquals(pcm.copyOfRange(4_000, 12_000), read(child))
                }
            }
            configure(reopened, 8_000L)
            assertArrayEquals(pcm.copyOfRange(8_000, 16_000), readAll(reopened))
        }
        assertEquals(-9_500L, headerTimestamp(onlyChunk(root)))
        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 8_000L)
            assertEquals(-9_000L, requireNotNull(reopened.peekSnapshot()).lastWriteAtMillis)
            assertArrayEquals(pcm.copyOfRange(8_000, 16_000), readAll(reopened))
        }
    }

    @Test
    fun ordinaryTimestampsAndPcm_remainExactForParentChildAndLoopingTrim() = withRoot { root ->
        val pcm = pcmBytes()
        PersistentAudioChunkStore(root).use { store ->
            append(store, pcm, 10_000L)
            requireNotNull(store.acquireRange(0.25, 0.75)).use { parent ->
                assertEquals(10_250L, parent.startedAtMillis)
                assertEquals(10_750L, parent.endedAtMillis)
                requireNotNull(parent.acquireSubRange(0.125, 0.375)).use { child ->
                    assertEquals(10_375L, child.startedAtMillis)
                    assertEquals(10_625L, child.endedAtMillis)
                    assertArrayEquals(pcm.copyOfRange(6_000, 10_000), read(child))
                }
            }
            configure(store, 8_000L)
            assertArrayEquals(pcm.copyOfRange(8_000, 16_000), readAll(store))
        }
        assertEquals(10_500L, headerTimestamp(onlyChunk(root)))
        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 8_000L)
            assertEquals(11_000L, requireNotNull(reopened.peekSnapshot()).lastWriteAtMillis)
            assertArrayEquals(pcm.copyOfRange(8_000, 16_000), readAll(reopened))
        }
    }

    private fun assertOneShotTrim(root: File, deferred: Boolean) {
        val pcm = pcmBytes()
        PersistentAudioChunkStore(root, overwriteOldest = false).use { store ->
            append(store, pcm, Long.MAX_VALUE - 5L)
            configure(store, 8_000L, deferred)
            if (deferred) {
                assertTrue(store.retentionMaintenanceNeeded())
                val step = store.performRetentionMaintenanceStep()
                assertTrue(step.progressed)
                assertFalse(step.needsMore)
            }
            assertEquals(Long.MAX_VALUE, requireNotNull(store.peekSnapshot()).lastWriteAtMillis)
            assertArrayEquals(pcm.copyOf(8_000), readAll(store))
        }
        PersistentAudioChunkStore(root, overwriteOldest = false).use { reopened ->
            configure(reopened, 8_000L)
            assertEquals(Long.MAX_VALUE, requireNotNull(reopened.peekSnapshot()).lastWriteAtMillis)
            assertArrayEquals(pcm.copyOf(8_000), readAll(reopened))
        }
    }

    private fun append(store: PersistentAudioChunkStore, pcm: ByteArray, timestamp: Long) {
        configure(store)
        assertEquals(pcm.size, store.appendCapturedAt(pcm, 0, pcm.size, timestamp))
        store.sealActiveChunk()
    }

    private fun configure(store: PersistentAudioChunkStore, bytes: Long = 1_048_576L, deferred: Boolean = false) {
        store.configure(RetentionMode.SIZE, bytes, 8_000, 1, PcmSampleFormat.PCM_16, deferred)
    }

    private fun onlyChunk(root: File): File = requireNotNull(File(root, BUFFER_CHUNKS_FOLDER_NAME).listFiles())
        .filter { it.name.toUIntOrNull() != null }.single()

    private fun headerTimestamp(chunk: File): Long =
        ByteBuffer.wrap(chunk.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getLong(16)

    private fun pcmBytes(): ByteArray = ByteArray(16_000) { index -> ((index * 29 + index / 17 + 7) and 0xff).toByte() }

    private fun readAll(store: PersistentAudioChunkStore): ByteArray =
        requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use(::read)

    private fun read(range: PersistentAudioChunkStore.RangeLease): ByteArray {
        val output = ByteArrayOutputStream()
        val result = range.readNormalized(8_000, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
            output.write(bytes, offset, count)
            count
        }
        assertEquals(output.size().toLong(), result.sampleBytes)
        return output.toByteArray()
    }

    private fun withRoot(block: (File) -> Unit) {
        val parent = File("build/tmp/chunk-timestamp-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "store-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
