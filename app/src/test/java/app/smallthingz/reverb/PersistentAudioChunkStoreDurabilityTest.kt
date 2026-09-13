package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Random
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
    fun loopingEviction_syncsPartialReplacementBeforeRetiringOldAudio() = withStoreRoot { root ->
        val capacity = 8_192L
        val initial = pcmBytes(capacity.toInt())
        val replacement = pcmBytes(256)
        PersistentAudioChunkStore(root).use { store ->
            configure(store, capacity)
            assertEquals(initial.size, store.append(initial, 0, initial.size))
            assertEquals(replacement.size, store.append(replacement, 0, replacement.size))

            val durableField = PersistentAudioChunkStore::class.java.getDeclaredField("activeDurablePayloadBytes")
            durableField.isAccessible = true
            assertEquals(replacement.size.toLong(), durableField.getLong(store))
            assertTrue(store.countFilledBytes() <= capacity)
            assertTrue((initial + replacement).endsWithBytes(readAll(store)))
        }
    }

    @Test
    fun periodicPayloadSync_isIdempotentAndMakesNewActiveBytesDurable() = withStoreRoot { root ->
        val first = pcmBytes(16_000)
        val second = pcmBytes(8_000)
        val crashed = PersistentAudioChunkStore(root)
        configure(crashed, 512 * 1024L)
        assertEquals(first.size, crashed.append(first, 0, first.size))
        assertEquals(first.size.toLong(), crashed.syncActivePayloadToDisk())
        assertEquals(0L, crashed.syncActivePayloadToDisk())

        assertEquals(second.size, crashed.append(second, 0, second.size))
        assertEquals((first.size + second.size).toLong(), crashed.syncActivePayloadToDisk())
        simulateAbruptProcessDeathWithoutSync(crashed)

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 512 * 1024L)
            assertArrayEquals(first + second, readAll(reopened))
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
    fun clearBuffer_doesNotInvalidateAnExportLeaseAlreadyInFlight() = withStoreRoot { root ->
        val expected = pcmBytes(32_000)
        val store = PersistentAudioChunkStore(root)
        configure(store, 128 * 1024L)
        assertEquals(expected.size, store.append(expected, 0, expected.size))
        val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))

        store.clear()
        assertFalse(store.hasData())
        assertArrayEquals(expected, readLease(lease))
        lease.close()
        store.close()

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 128 * 1024L)
            assertFalse(reopened.hasData())
        }
    }

    @Test
    fun retiredChunk_waitsForEveryConcurrentReadLeaseBeforeDeletion() = withStoreRoot { root ->
        val expected = pcmBytes(32_000)
        val store = PersistentAudioChunkStore(root)
        configure(store, 128 * 1024L)
        assertEquals(expected.size, store.append(expected, 0, expected.size))
        store.sealActiveChunk()
        val duration = store.durationSeconds()
        val first = requireNotNull(store.acquireRange(0.0, duration))
        val second = requireNotNull(store.acquireRange(0.0, duration))

        store.clear()
        assertFalse(store.hasData())
        first.close()
        assertArrayEquals(expected, readLease(second))
        second.close()
        store.close()

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 128 * 1024L)
            assertFalse(reopened.hasData())
        }
    }

    @Test
    fun randomizedOneShotRetention_restartsAndResizesMatchByteModel() = withStoreRoot { root ->
        val random = Random(0x5eedL)
        var capacity = 16_384L
        var expected = byteArrayOf()
        var store = PersistentAudioChunkStore(root, overwriteOldest = false)
        configure(store, capacity)
        try {
            repeat(160) { iteration ->
                when (random.nextInt(5)) {
                    0, 1, 2 -> {
                        val count = (1 + random.nextInt(2_048)) * 2
                        val bytes = ByteArray(count) { random.nextInt(256).toByte() }
                        val writable = (capacity - expected.size.toLong()).coerceAtLeast(0L)
                            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        val expectedWritten = minOf(count, writable) - minOf(count, writable) % 2
                        val actual = store.append(bytes, 0, bytes.size)
                        assertEquals("append at iteration $iteration", expectedWritten, actual)
                        if (actual > 0) expected += bytes.copyOf(actual)
                    }
                    3 -> {
                        capacity = (random.nextInt(8_193) * 2).toLong()
                        configure(store, capacity)
                        if (expected.size.toLong() > capacity) expected = expected.copyOf(capacity.toInt())
                    }
                    else -> {
                        store.close()
                        store = PersistentAudioChunkStore(root, overwriteOldest = false)
                        configure(store, capacity)
                    }
                }
                assertArrayEquals("timeline mismatch at iteration $iteration", expected, readAllOrEmpty(store))
            }
        } finally {
            store.close()
        }

        PersistentAudioChunkStore(root, overwriteOldest = false).use { reopened ->
            configure(reopened, capacity)
            assertArrayEquals(expected, readAllOrEmpty(reopened))
        }
    }

    @Test
    fun randomizedLoopingRetention_isAlwaysAnExactRecentSuffixAcrossRestarts() = withStoreRoot { root ->
        val random = Random(0x10_0f_1aL)
        var capacity = 8_192L
        val completeHistory = ByteArrayOutputStream()
        var store = PersistentAudioChunkStore(root, overwriteOldest = true)
        configure(store, capacity)
        try {
            repeat(220) { iteration ->
                when (random.nextInt(6)) {
                    0, 1, 2, 3 -> {
                        val count = (1 + random.nextInt(2_048)) * 2
                        val bytes = ByteArray(count) { random.nextInt(256).toByte() }
                        assertEquals("looping append at $iteration", count, store.append(bytes, 0, count))
                        completeHistory.write(bytes)
                    }
                    4 -> {
                        capacity = ((256 + random.nextInt(8_000)) * 2).toLong()
                        configure(store, capacity)
                    }
                    else -> {
                        store.close()
                        store = PersistentAudioChunkStore(root, overwriteOldest = true)
                        configure(store, capacity)
                    }
                }

                val observed = readAllOrEmpty(store)
                assertTrue("capacity exceeded at $iteration", observed.size.toLong() <= capacity)
                val all = completeHistory.toByteArray()
                assertTrue("retained data is not a suffix at $iteration", all.endsWithBytes(observed))
            }
        } finally {
            store.close()
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

    @Test
    fun waveformSampling_isBoundedReadOnlyAndDetectsSignal() = withStoreRoot { root ->
        val expected = pcmBytes(96_000)
        PersistentAudioChunkStore(root).use { store ->
            configure(store, 128 * 1024L)
            assertEquals(expected.size, store.append(expected, 0, expected.size))
            store.sealActiveChunk()
            val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
            try {
                val published = ArrayList<Int>()
                val envelope = lease.sampleWaveformEnvelopeProgressive(64) { index, _ ->
                    published += index
                    true
                }
                assertEquals((0 until 64).toList(), published)
                assertEquals(64, envelope.size)
                assertTrue(envelope.all { it in 0f..1f })
                assertTrue(envelope.any { it > 0.05f })
                assertTrue(envelope.distinct().size > 1)
            } finally {
                lease.close()
            }
            assertArrayEquals(expected, readAll(store))
        }
    }

    @Test
    fun waveformSampling_canStopAtPublishedFrontier() = withStoreRoot { root ->
        val expected = pcmBytes(96_000)
        PersistentAudioChunkStore(root).use { store ->
            configure(store, 128 * 1024L)
            assertEquals(expected.size, store.append(expected, 0, expected.size))
            store.sealActiveChunk()
            val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
            try {
                val published = ArrayList<Int>()
                lease.sampleWaveformEnvelopeProgressive(64) { index, _ ->
                    published += index
                    index < 11
                }
                assertEquals((0..11).toList(), published)
            } finally {
                lease.close()
            }
            assertArrayEquals(expected, readAll(store))
        }
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

    private fun ByteArray.endsWithBytes(suffix: ByteArray): Boolean {
        if (suffix.size > size) return false
        val start = size - suffix.size
        for (index in suffix.indices) {
            if (this[start + index] != suffix[index]) return false
        }
        return true
    }

    private fun simulateAbruptProcessDeathWithoutSync(store: PersistentAudioChunkStore) {
        val field = PersistentAudioChunkStore::class.java.getDeclaredField("activeAccess")
        field.isAccessible = true
        val access = requireNotNull(field.get(store) as? RandomAccessFile)
        access.close()
        field.set(store, null)
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
