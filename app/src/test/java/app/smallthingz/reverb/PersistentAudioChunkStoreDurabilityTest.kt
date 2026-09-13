package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentAudioChunkStoreDurabilityTest {
    @Test
    fun finalizedAudio_survivesCloseAndReopenByteForByte() = withStoreRoot { root ->
        val expected = pcmBytes(24_000)
        PersistentAudioChunkStore(root).use { store ->
            configure(store, 512 * 1024L)
            assertEquals(expected.size, store.append(expected, 0, expected.size))
            store.sealActiveChunk()
        }

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 512 * 1024L)
            assertArrayEquals(expected, readAll(reopened))
        }
    }

    @Test
    fun brokenIndexes_recoverFromChunkFilesWithoutLosingAudio() = withStoreRoot { root ->
        val expected = pcmBytes(90_000)
        PersistentAudioChunkStore(root).use { store ->
            configure(store, 512 * 1024L)
            assertEquals(expected.size, store.append(expected, 0, expected.size))
            store.sealActiveChunk()
        }
        File(root, ReverbConfig.BUFFER_INDEX_A_FILE_NAME).writeBytes(byteArrayOf(1, 2, 3))
        File(root, ReverbConfig.BUFFER_INDEX_B_FILE_NAME).writeBytes(byteArrayOf(4, 5, 6))

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 512 * 1024L)
            assertArrayEquals(expected, readAll(reopened))
        }
    }

    @Test
    fun processDeath_recoversActiveChunkFromDurablePayloadEvenWithStaleHeader() = withStoreRoot { root ->
        val expected = pcmBytes(32_000)
        val crashed = PersistentAudioChunkStore(root)
        configure(crashed, 512 * 1024L)
        assertEquals(expected.size, crashed.append(expected, 0, expected.size))
        simulateProcessDeath(crashed)

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 512 * 1024L)
            assertArrayEquals(expected, readAll(reopened))
        }
    }

    @Test
    fun tornFinalFrame_isPreservedBeforeLiveRecoveryAlignsPayload() = withStoreRoot { root ->
        val expected = pcmBytes(12_000)
        val crashed = PersistentAudioChunkStore(root)
        configure(crashed, 512 * 1024L)
        assertEquals(expected.size, crashed.append(expected, 0, expected.size))
        simulateProcessDeath(crashed)

        val chunk = File(File(root, ReverbConfig.BUFFER_CHUNKS_FOLDER_NAME), "0")
        RandomAccessFile(chunk, "rw").use { file ->
            file.seek(file.length())
            file.write(0x7f)
            file.fd.sync()
        }
        val tornLength = chunk.length()

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 512 * 1024L)
            assertArrayEquals(expected, readAll(reopened))
        }
        val preserved = File(root, "preserved").listFiles().orEmpty()
            .singleOrNull { ".partial-frame" in it.name }
        assertNotNull(preserved)
        assertEquals(tornLength, requireNotNull(preserved).length())
    }

    @Test
    fun corruptAndUnrecognizedChunkArtifacts_areQuarantinedNotDeleted() = withStoreRoot { root ->
        val chunks = File(root, ReverbConfig.BUFFER_CHUNKS_FOLDER_NAME).apply { mkdirs() }
        val unrecognizedBytes = byteArrayOf(9, 8, 7, 6, 5)
        val corruptBytes = ByteArray(256) { index -> (index * 13).toByte() }
        File(chunks, "mystery.partial").writeBytes(unrecognizedBytes)
        File(chunks, "0").writeBytes(corruptBytes)

        PersistentAudioChunkStore(root).use { store -> configure(store, 64 * 1024L) }

        assertFalse(File(chunks, "mystery.partial").exists())
        assertFalse(File(chunks, "0").exists())
        val preserved = File(root, "preserved").listFiles().orEmpty()
        assertTrue(preserved.any { it.readBytes().contentEquals(unrecognizedBytes) })
        assertTrue(preserved.any { it.readBytes().contentEquals(corruptBytes) })
    }

    @Test
    fun legacyBufferDirectory_isNeverAutomaticallyDeleted() = withStoreRoot { root ->
        val legacy = File(root.parentFile, "${root.name}-legacy").apply { mkdirs() }
        val sentinel = File(legacy, "only-copy.raw").apply { writeBytes(byteArrayOf(4, 3, 2, 1)) }
        try {
            PersistentAudioChunkStore(
                rootDirectory = root,
                legacyDirectory = legacy,
                overwriteOldest = true,
            ).use { store -> configure(store, 64 * 1024L) }
            assertTrue(sentinel.isFile)
            assertArrayEquals(byteArrayOf(4, 3, 2, 1), sentinel.readBytes())
        } finally {
            legacy.deleteRecursively()
        }
    }

    @Test
    fun oneShotShrink_waitsForReadLeaseThenPreservesExactPrefixAcrossRestart() = withStoreRoot { root ->
        val expected = pcmBytes(8_192)
        val store = PersistentAudioChunkStore(root, overwriteOldest = false)
        configure(store, 8_192L)
        assertEquals(expected.size, store.append(expected, 0, expected.size))
        val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))

        configure(store, 4_096L)
        assertEquals(4_096L, store.countFilledBytes())
        assertArrayEquals(expected, readLease(lease))
        lease.close()
        assertEquals(4_096L, store.countFilledBytes())
        assertArrayEquals(expected.copyOf(4_096), readAll(store))
        store.close()

        PersistentAudioChunkStore(root, overwriteOldest = false).use { reopened ->
            configure(reopened, 4_096L)
            assertArrayEquals(expected.copyOf(4_096), readAll(reopened))
        }
    }

    @Test
    fun repeatedRestartsAndIndexLoss_preserveTheEntireObservableTimeline() = withStoreRoot { root ->
        val expected = ByteArrayOutputStream()
        repeat(24) { iteration ->
            val additionSize = 2_048 + (iteration % 7) * 514
            val addition = pcmBytes(additionSize)
            PersistentAudioChunkStore(root).use { store ->
                configure(store, 512 * 1024L)
                assertArrayEquals(expected.toByteArray(), readAllOrEmpty(store))
                assertEquals(addition.size, store.append(addition, 0, addition.size))
                store.sealActiveChunk()
            }
            expected.write(addition)

            if (iteration % 5 == 4) {
                File(root, ReverbConfig.BUFFER_INDEX_A_FILE_NAME).writeBytes(byteArrayOf(0x55, iteration.toByte()))
                File(root, ReverbConfig.BUFFER_INDEX_B_FILE_NAME).writeBytes(byteArrayOf(0x33, iteration.toByte()))
            }

            PersistentAudioChunkStore(root).use { reopened ->
                configure(reopened, 512 * 1024L)
                assertArrayEquals(expected.toByteArray(), readAll(reopened))
            }
        }
    }

    @Test
    fun chunkIdWrap_preservesChronologyAcrossRestart() = withStoreRoot { root ->
        val expected = pcmBytes(1_536)
        PersistentAudioChunkStore(root).use { store ->
            configure(store, 8_192L)
            val nextId = PersistentAudioChunkStore::class.java.getDeclaredField("nextChunkId")
            nextId.isAccessible = true
            nextId.setInt(store, -2) // UInt.MAX_VALUE - 1
            assertEquals(expected.size, store.append(expected, 0, expected.size))
            store.sealActiveChunk()
        }

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 8_192L)
            assertArrayEquals(expected, readAll(reopened))
        }
    }

    @Test
    fun checksumCorruption_isDetectedWithoutDeletingTheOnlyChunk() = withStoreRoot { root ->
        val expected = pcmBytes(16_000)
        PersistentAudioChunkStore(root).use { store ->
            configure(store, 128 * 1024L)
            store.append(expected, 0, expected.size)
            store.sealActiveChunk()
        }
        val chunk = File(File(root, ReverbConfig.BUFFER_CHUNKS_FOLDER_NAME), "0")
        RandomAccessFile(chunk, "rw").use { file ->
            file.seek(file.length() - 1L)
            val original = file.read()
            file.seek(file.length() - 1L)
            file.write(original xor 0x01)
            file.fd.sync()
        }
        val corruptedBytes = chunk.readBytes()

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 128 * 1024L)
            assertThrows(IOException::class.java) { readAll(reopened) }
        }
        assertTrue(chunk.isFile)
        assertArrayEquals(corruptedBytes, chunk.readBytes())
    }

    private fun configure(store: PersistentAudioChunkStore, retentionBytes: Long) {
        store.configure(
            requestedRetentionMode = RetentionMode.SIZE,
            requestedRetentionValue = retentionBytes,
            requestedSampleRate = 8_000,
            requestedChannelCount = 1,
            sampleFormat = PcmSampleFormat.PCM_16,
        )
    }

    private fun readAllOrEmpty(store: PersistentAudioChunkStore): ByteArray {
        if (!store.hasData()) return byteArrayOf()
        return readAll(store)
    }

    private fun readAll(store: PersistentAudioChunkStore): ByteArray {
        val duration = store.durationSeconds()
        val lease = requireNotNull(store.acquireRange(0.0, duration))
        return lease.use(::readLease)
    }

    private fun readLease(lease: PersistentAudioChunkStore.RangeLease): ByteArray {
        val output = ByteArrayOutputStream()
        val result = lease.readNormalized(
            targetSampleRate = 8_000,
            targetChannelCount = 1,
            targetSampleFormat = PcmSampleFormat.PCM_16,
        ) { bytes, offset, count ->
            output.write(bytes, offset, count)
            count
        }
        assertEquals(output.size().toLong(), result.sampleBytes)
        return output.toByteArray()
    }

    private fun simulateProcessDeath(store: PersistentAudioChunkStore) {
        val field = PersistentAudioChunkStore::class.java.getDeclaredField("activeAccess")
        field.isAccessible = true
        val access = requireNotNull(field.get(store) as? RandomAccessFile)
        access.fd.sync()
        access.close()
        field.set(store, null)
    }

    private fun pcmBytes(size: Int): ByteArray {
        require(size % 2 == 0)
        return ByteArray(size) { index -> ((index * 29 + index / 17 + 7) and 0xff).toByte() }
    }

    private inline fun withStoreRoot(block: (File) -> Unit) {
        val parent = File("build/tmp/durability-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "store-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
