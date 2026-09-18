package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentAudioChunkStoreDurabilityTest {
    @Test
    fun retirementTombstoneSampleFormatWire_readsLegacyNamesAndV2ByteCodes() {
        assertEquals(PcmSampleFormat.PCM_16, retirementSampleFormatFromWire("v1", "PCM_16"))
        assertEquals(PcmSampleFormat.PCM_16, retirementSampleFormatFromWire("v2", "2"))
        assertEquals(null, retirementSampleFormatFromWire("v2", "PCM_16"))
        assertEquals(null, retirementSampleFormatFromWire("v3", "2"))
    }
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
    fun nonRegularChunkIndex_failsClosedWithoutRewritingAudio() = withStoreRoot { root ->
        val expected = pcmBytes(24_000)
        PersistentAudioChunkStore(root).use { store ->
            configure(store, 512 * 1024L)
            assertEquals(expected.size, store.append(expected, 0, expected.size))
            store.sealActiveChunk()
        }
        val chunk = File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "0")
        val before = chunk.readBytes()
        val firstIndex = File(root, BUFFER_INDEX_A_FILE_NAME)
        val secondIndex = File(root, BUFFER_INDEX_B_FILE_NAME)
        firstIndex.deleteRecursively()
        secondIndex.deleteRecursively()
        assertTrue(firstIndex.mkdir())

        val reopened = PersistentAudioChunkStore(root)
        try {
            assertThrows(IOException::class.java) { configure(reopened, 512 * 1024L) }
        } finally {
            runCatching { reopened.close() }
        }
        assertArrayEquals(before, chunk.readBytes())
    }

    @Test
    fun brokenIndexes_recoverFromChunkFilesWithoutLosingAudio() = withStoreRoot { root ->
        val expected = pcmBytes(90_000)
        PersistentAudioChunkStore(root).use { store ->
            configure(store, 512 * 1024L)
            assertEquals(expected.size, store.append(expected, 0, expected.size))
            store.sealActiveChunk()
        }
        File(root, BUFFER_INDEX_A_FILE_NAME).writeBytes(byteArrayOf(1, 2, 3))
        File(root, BUFFER_INDEX_B_FILE_NAME).writeBytes(byteArrayOf(4, 5, 6))

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
    fun processDeath_neverRecertifiesCorruptedCheckpointedActivePrefix() = withStoreRoot { root ->
        val checkpointed = pcmBytes(8_192)
        val tail = pcmBytes(4_096).mapIndexed { index, byte -> (byte.toInt() xor (index and 0x1f)).toByte() }.toByteArray()
        val crashed = PersistentAudioChunkStore(root)
        configure(crashed, 512 * 1024L)
        assertEquals(checkpointed.size, crashed.append(checkpointed, 0, checkpointed.size))
        crashed.checkpoint()
        assertEquals(tail.size, crashed.append(tail, 0, tail.size))
        simulateProcessDeath(crashed)

        val chunk = File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "0")
        val corrupted = chunk.readBytes().also { bytes ->
            val payloadOffset = 128
            bytes[payloadOffset + 137] = (bytes[payloadOffset + 137].toInt() xor 0x40).toByte()
        }
        chunk.writeBytes(corrupted)

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 512 * 1024L)
            assertFalse(reopened.hasData())
        }
        val preserved = File(root, "preserved").listFiles().orEmpty()
            .singleOrNull { ".corrupt" in it.name }
        assertNotNull(preserved)
        assertArrayEquals(corrupted, requireNotNull(preserved).readBytes())
    }

    @Test
    fun processDeath_recoversUncheckpointedTailOnlyWhenCheckpointedPrefixStillMatches() = withStoreRoot { root ->
        val checkpointed = pcmBytes(8_192)
        val tail = ByteArray(4_096) { index -> ((index * 43 + 19) and 0xff).toByte() }
        val expected = checkpointed + tail
        val crashed = PersistentAudioChunkStore(root)
        configure(crashed, 512 * 1024L)
        assertEquals(checkpointed.size, crashed.append(checkpointed, 0, checkpointed.size))
        crashed.checkpoint()
        assertEquals(tail.size, crashed.append(tail, 0, tail.size))
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

        val chunk = File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "0")
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
        val chunks = File(root, BUFFER_CHUNKS_FOLDER_NAME).apply { mkdirs() }
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
    fun disablingOneShotRetention_preservesHistoryAcrossRestartUntilExplicitShrink() = withStoreRoot { root ->
        val expected = pcmBytes(8_192)
        PersistentAudioChunkStore(root, overwriteOldest = false).use { store ->
            configure(store, 8_192L)
            assertEquals(expected.size, store.append(expected, 0, expected.size))
            store.sealActiveChunk()
            configure(store, 0L)
            assertTrue(store.isFull()) // disabled is not writable
            assertArrayEquals(expected, readAll(store))
        }

        PersistentAudioChunkStore(root, overwriteOldest = false).use { reopened ->
            configure(reopened, 0L)
            assertTrue(reopened.hasData())
            assertArrayEquals(expected, readAll(reopened))
            configure(reopened, 4_096L)
            assertArrayEquals(expected.copyOf(4_096), readAll(reopened))
        }
    }

    @Test
    fun disablingLoopingRetention_preservesHistoryUntilExplicitClear() = withStoreRoot { root ->
        val expected = pcmBytes(8_192)
        PersistentAudioChunkStore(root, overwriteOldest = true).use { store ->
            configure(store, 8_192L)
            assertEquals(expected.size, store.append(expected, 0, expected.size))
            store.sealActiveChunk()
            configure(store, 0L)
            assertEquals(0, store.append(pcmBytes(1_024), 0, 1_024))
            assertTrue(store.hasData())
            assertArrayEquals(expected, readAll(store))
        }

        PersistentAudioChunkStore(root, overwriteOldest = true).use { reopened ->
            configure(reopened, 0L)
            assertTrue(reopened.hasData())
            assertArrayEquals(expected, readAll(reopened))
            reopened.clear()
            assertFalse(reopened.hasData())
            assertArrayEquals(ByteArray(0), readAllOrEmpty(reopened))
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
    fun rangeLeaseClose_releasesEveryChunkAfterRetentionCleanupFailure() = withStoreRoot { root ->
        val first = pcmBytes(8_192)
        val second = pcmBytes(8_192).map { byte -> (byte.toInt() xor 0x55).toByte() }.toByteArray()
        var failChunkDirectorySync = false
        val store = PersistentAudioChunkStore(
            rootDirectory = root,
            overwriteOldest = false,
            directorySync = { directory ->
                if (failChunkDirectorySync && directory.name == BUFFER_CHUNKS_FOLDER_NAME) {
                    throw IOException("Injected lease-close retention sync failure")
                }
            },
        )
        configure(store, 131_072L)
        assertEquals(first.size, store.append(first, 0, first.size))
        store.sealActiveChunk()
        assertEquals(second.size, store.append(second, 0, second.size))
        store.sealActiveChunk()
        val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))

        configure(store, 4_096L)
        failChunkDirectorySync = true
        assertThrows(IOException::class.java) { lease.close() }
        failChunkDirectorySync = false

        // Retrying retention after the close failure must not find any record still pinned by
        // the already-closed lease. Otherwise the retired second chunk survives until restart.
        configure(store, 4_096L)
        store.close()
        assertFalse(File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "1").exists())
    }

    @Test
    fun deferredLoopingRetention_shrinksOneChunkPerMaintenanceStepAndKeepsNewestAudio() =
        withStoreRoot { root ->
            val first = pcmBytes(16_000)
            val second = first.map { byte -> (byte.toInt() xor 0x33).toByte() }.toByteArray()
            val third = first.map { byte -> (byte.toInt() xor 0x66).toByte() }.toByteArray()
            PersistentAudioChunkStore(root, overwriteOldest = true).use { store ->
                configure(store, 512 * 1024L)
                for (chunk in listOf(first, second, third)) {
                    assertEquals(chunk.size, store.append(chunk, 0, chunk.size))
                    store.sealActiveChunk()
                }
                assertEquals(3, requireNotNull(store.peekSnapshot()).chunkCount)

                store.configure(
                    requestedRetentionMode = RetentionMode.SIZE,
                    requestedRetentionValue = third.size.toLong(),
                    requestedSampleRate = 8_000,
                    requestedChannelCount = 1,
                    sampleFormat = PcmSampleFormat.PCM_16,
                    deferRetentionCleanup = true,
                )
                assertEquals(3, requireNotNull(store.peekSnapshot()).chunkCount)
                assertTrue(store.retentionMaintenanceNeeded())

                val firstStep = store.performRetentionMaintenanceStep()
                assertTrue(firstStep.progressed)
                assertTrue(firstStep.needsMore)
                assertFalse(firstStep.blocked)
                assertEquals(2, requireNotNull(store.peekSnapshot()).chunkCount)

                val secondStep = store.performRetentionMaintenanceStep()
                assertTrue(secondStep.progressed)
                assertFalse(secondStep.needsMore)
                assertEquals(1, requireNotNull(store.peekSnapshot()).chunkCount)
                assertFalse(store.retentionMaintenanceNeeded())
                assertArrayEquals(third, readAll(store))
            }
        }

    @Test
    fun deferredOneShotRetention_shrinksOneChunkPerMaintenanceStepAndKeepsOldestAudio() =
        withStoreRoot { root ->
            val first = pcmBytes(16_000)
            val second = first.map { byte -> (byte.toInt() xor 0x33).toByte() }.toByteArray()
            val third = first.map { byte -> (byte.toInt() xor 0x66).toByte() }.toByteArray()
            PersistentAudioChunkStore(root, overwriteOldest = false).use { store ->
                configure(store, 512 * 1024L)
                for (chunk in listOf(first, second, third)) {
                    assertEquals(chunk.size, store.append(chunk, 0, chunk.size))
                    store.sealActiveChunk()
                }
                assertEquals(3, requireNotNull(store.peekSnapshot()).chunkCount)

                store.configure(
                    requestedRetentionMode = RetentionMode.SIZE,
                    requestedRetentionValue = first.size.toLong(),
                    requestedSampleRate = 8_000,
                    requestedChannelCount = 1,
                    sampleFormat = PcmSampleFormat.PCM_16,
                    deferRetentionCleanup = true,
                )
                assertEquals(3, requireNotNull(store.peekSnapshot()).chunkCount)
                assertTrue(store.retentionMaintenanceNeeded())

                val firstStep = store.performRetentionMaintenanceStep()
                assertTrue(firstStep.progressed)
                assertTrue(firstStep.needsMore)
                assertFalse(firstStep.blocked)
                assertEquals(2, requireNotNull(store.peekSnapshot()).chunkCount)

                val secondStep = store.performRetentionMaintenanceStep()
                assertTrue(secondStep.progressed)
                assertFalse(secondStep.needsMore)
                assertEquals(1, requireNotNull(store.peekSnapshot()).chunkCount)
                assertFalse(store.retentionMaintenanceNeeded())
                assertArrayEquals(first, readAll(store))
            }
        }

    @Test
    fun deferredRetention_partialBoundaryKeepsNewestLoopingAndOldestOneShot() =
        withStoreRoot { root ->
            val first = pcmBytes(16_000)
            val second = first.map { byte -> (byte.toInt() xor 0x5a).toByte() }.toByteArray()
            val retentionBytes = 24_000L

            PersistentAudioChunkStore(File(root, "looping"), overwriteOldest = true).use { store ->
                configure(store, 512 * 1024L)
                for (chunk in listOf(first, second)) {
                    assertEquals(chunk.size, store.append(chunk, 0, chunk.size))
                    store.sealActiveChunk()
                }
                store.configure(
                    requestedRetentionMode = RetentionMode.SIZE,
                    requestedRetentionValue = retentionBytes,
                    requestedSampleRate = 8_000,
                    requestedChannelCount = 1,
                    sampleFormat = PcmSampleFormat.PCM_16,
                    deferRetentionCleanup = true,
                )

                val step = store.performRetentionMaintenanceStep()
                assertTrue(step.progressed)
                assertFalse(step.needsMore)
                assertArrayEquals(first.copyOfRange(8_000, first.size) + second, readAll(store))
            }

            PersistentAudioChunkStore(File(root, "one-shot"), overwriteOldest = false).use { store ->
                configure(store, 512 * 1024L)
                for (chunk in listOf(first, second)) {
                    assertEquals(chunk.size, store.append(chunk, 0, chunk.size))
                    store.sealActiveChunk()
                }
                store.configure(
                    requestedRetentionMode = RetentionMode.SIZE,
                    requestedRetentionValue = retentionBytes,
                    requestedSampleRate = 8_000,
                    requestedChannelCount = 1,
                    sampleFormat = PcmSampleFormat.PCM_16,
                    deferRetentionCleanup = true,
                )

                val step = store.performRetentionMaintenanceStep()
                assertTrue(step.progressed)
                assertFalse(step.needsMore)
                assertArrayEquals(first + second.copyOfRange(0, 8_000), readAll(store))
            }
        }

    @Test
    fun deferredTimeRetention_preservesCorrectPartialBoundaryForBothBuffers() =
        withStoreRoot { root ->
            // 8 kHz mono PCM16: 12,000 bytes = 0.75 seconds. Two chunks total 1.5 s,
            // so a 1-second limit requires an exact 0.5-second boundary trim.
            val first = pcmBytes(12_000)
            val second = first.map { byte -> (byte.toInt() xor 0x47).toByte() }.toByteArray()

            PersistentAudioChunkStore(File(root, "looping-time"), overwriteOldest = true).use { store ->
                configure(store, 512 * 1024L)
                for (chunk in listOf(first, second)) {
                    assertEquals(chunk.size, store.append(chunk, 0, chunk.size))
                    store.sealActiveChunk()
                }
                store.configure(
                    requestedRetentionMode = RetentionMode.TIME,
                    requestedRetentionValue = 1L,
                    requestedSampleRate = 8_000,
                    requestedChannelCount = 1,
                    sampleFormat = PcmSampleFormat.PCM_16,
                    deferRetentionCleanup = true,
                )

                val step = store.performRetentionMaintenanceStep()
                assertTrue(step.progressed)
                assertFalse(step.needsMore)
                assertArrayEquals(first.copyOfRange(8_000, first.size) + second, readAll(store))
            }

            PersistentAudioChunkStore(File(root, "one-shot-time"), overwriteOldest = false).use { store ->
                configure(store, 512 * 1024L)
                for (chunk in listOf(first, second)) {
                    assertEquals(chunk.size, store.append(chunk, 0, chunk.size))
                    store.sealActiveChunk()
                }
                store.configure(
                    requestedRetentionMode = RetentionMode.TIME,
                    requestedRetentionValue = 1L,
                    requestedSampleRate = 8_000,
                    requestedChannelCount = 1,
                    sampleFormat = PcmSampleFormat.PCM_16,
                    deferRetentionCleanup = true,
                )

                val step = store.performRetentionMaintenanceStep()
                assertTrue(step.progressed)
                assertFalse(step.needsMore)
                assertArrayEquals(first + second.copyOfRange(0, 4_000), readAll(store))
            }
        }

    @Test
    fun deferredRetention_leaseReleaseOnlyUnblocksMaintenanceWithoutDoingCleanupInline() =
        withStoreRoot { root ->
            val first = pcmBytes(16_000)
            val second = first.map { byte -> (byte.toInt() xor 0x2d).toByte() }.toByteArray()
            PersistentAudioChunkStore(root, overwriteOldest = true).use { store ->
                configure(store, 512 * 1024L)
                for (chunk in listOf(first, second)) {
                    assertEquals(chunk.size, store.append(chunk, 0, chunk.size))
                    store.sealActiveChunk()
                }
                val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
                store.configure(
                    requestedRetentionMode = RetentionMode.SIZE,
                    requestedRetentionValue = 24_000L,
                    requestedSampleRate = 8_000,
                    requestedChannelCount = 1,
                    sampleFormat = PcmSampleFormat.PCM_16,
                    deferRetentionCleanup = true,
                )

                val blocked = store.performRetentionMaintenanceStep()
                assertFalse(blocked.progressed)
                assertTrue(blocked.needsMore)
                assertTrue(blocked.blocked)
                assertEquals(2, requireNotNull(store.peekSnapshot()).chunkCount)

                lease.close()
                assertEquals(2, requireNotNull(store.peekSnapshot()).chunkCount)
                assertTrue(store.retentionMaintenanceNeeded())

                val resumed = store.performRetentionMaintenanceStep()
                assertTrue(resumed.progressed)
                assertFalse(resumed.needsMore)
                assertArrayEquals(first.copyOfRange(8_000, first.size) + second, readAll(store))
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
                File(root, BUFFER_INDEX_A_FILE_NAME).writeBytes(byteArrayOf(0x55, iteration.toByte()))
                File(root, BUFFER_INDEX_B_FILE_NAME).writeBytes(byteArrayOf(0x33, iteration.toByte()))
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
    fun incrementalClear_partialProgressSurvivesRestartWithoutExtraCheckpoint() = withStoreRoot { root ->
        val expected = pcmBytes(64_000)
        val crashed = PersistentAudioChunkStore(root)
        configure(crashed, 128 * 1024L)
        assertEquals(expected.size, crashed.append(expected, 0, expected.size))
        crashed.sealActiveChunk()
        val before = requireNotNull(crashed.peekSnapshot())
        assertTrue(before.chunkCount > 1)

        val step = crashed.clearOneChunk()
        assertFalse(step.complete)
        assertEquals(before.chunkCount - 1, step.remainingChunkCount)
        assertTrue(step.removedPayloadBytes > 0L)
        assertTrue(step.remainingPayloadBytes < before.filledBytes)

        val remainingAfterCancel = readAll(crashed)
        assertTrue(expected.endsWithBytes(remainingAfterCancel))

        // Reopen without closing/checkpointing the first store. Recovery must reconcile the
        // stale pre-clear index with the durable retirement/deletion of the first chunk.
        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 128 * 1024L)
            assertArrayEquals(remainingAfterCancel, readAll(reopened))
        }
    }

    @Test
    fun clearFailsClosedWhenRetirementJournalCannotBePersisted() = withStoreRoot { root ->
        val expected = pcmBytes(8_192)
        val store = PersistentAudioChunkStore(root, overwriteOldest = true)
        configure(store, 64 * 1024L)
        assertEquals(expected.size, store.append(expected, 0, expected.size))
        store.sealActiveChunk()

        val retiredPath = File(root, "retired")
        assertTrue(retiredPath.createNewFile())
        assertThrows(IOException::class.java) { store.clear() }
        assertTrue(store.hasData())
        assertArrayEquals(expected, readAll(store))
        store.close()
    }

    @Test
    fun retirementMarkerSyncFailure_neverDeletesChunkBeforeMarkerIsDurable() = withStoreRoot { root ->
        val expected = pcmBytes(8_192)
        var failRetiredDirectorySync = false
        val store = PersistentAudioChunkStore(
            rootDirectory = root,
            overwriteOldest = true,
            directorySync = { directory ->
                if (failRetiredDirectorySync && directory.name == "retired") {
                    throw IOException("Injected retirement-marker directory sync failure")
                }
            },
        )
        configure(store, 64 * 1024L)
        assertEquals(expected.size, store.append(expected, 0, expected.size))
        store.sealActiveChunk()

        val chunk = File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "0")
        val marker = File(File(root, "retired"), "0")
        failRetiredDirectorySync = true
        assertThrows(IOException::class.java) { store.clear() }

        // The first 4 KiB chunk has crossed the visible atomic retirement boundary, so
        // runtime follows that retirement even though marker fsync failed. The chunk bytes
        // themselves remain untouched until the marker directory becomes durable.
        assertEquals(4_096L, store.countFilledBytes())
        assertTrue(marker.isFile)
        assertTrue(chunk.isFile)
        store.checkpoint()
        assertTrue(chunk.isFile)

        failRetiredDirectorySync = false
        store.checkpoint()
        assertFalse(chunk.exists())
        assertFalse(marker.exists())
        assertEquals(4_096L, store.countFilledBytes())

        store.clear()
        assertFalse(store.hasData())
        store.close()

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 64 * 1024L)
            assertFalse(reopened.hasData())
        }
    }

    @Test
    fun clear_surfacesActiveAccessCloseFailureAfterRetiringAudio() = withStoreRoot { root ->
        val expected = pcmBytes(4_096)
        val store = PersistentAudioChunkStore(root)
        configure(store, 128 * 1024L)
        assertEquals(expected.size, store.append(expected, 0, expected.size))
        replaceActiveAccessWithFailingClose(store)

        val error = assertThrows(IOException::class.java) { store.clear() }
        assertTrue(
            error.message?.contains("Injected active access close failure") == true ||
                error.suppressed.any { it.message?.contains("Injected active access close failure") == true },
        )
        assertFalse(store.hasData())
        store.close()

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 128 * 1024L)
            assertFalse(reopened.hasData())
        }
    }

    @Test
    fun sealActiveChunk_surfacesActiveAccessCloseFailureAfterFinalizingAudio() = withStoreRoot { root ->
        val expected = pcmBytes(4_096)
        val store = PersistentAudioChunkStore(root)
        configure(store, 128 * 1024L)
        assertEquals(expected.size, store.append(expected, 0, expected.size))
        replaceActiveAccessWithFailingClose(store)

        val error = assertThrows(IOException::class.java) { store.sealActiveChunk() }
        assertTrue(
            error.message?.contains("Injected active access close failure") == true ||
                error.suppressed.any { it.message?.contains("Injected active access close failure") == true },
        )
        assertEquals(expected.size.toLong(), store.countFilledBytes())
        assertArrayEquals(expected, readAll(store))
        store.close()

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 128 * 1024L)
            assertArrayEquals(expected, readAll(reopened))
        }
    }

    @Test
    fun activeChunkCreationFailure_reportsUncertainCleanupAndRecoversEmpty() = withStoreRoot { root ->
        var failChunkDirectorySync = false
        val store = PersistentAudioChunkStore(
            rootDirectory = root,
            directorySync = { directory ->
                if (failChunkDirectorySync && directory.name == BUFFER_CHUNKS_FOLDER_NAME) {
                    throw IOException("Injected active chunk creation directory sync failure")
                }
            },
        )
        configure(store, 128 * 1024L)

        val attempted = pcmBytes(4_096)
        failChunkDirectorySync = true
        val error = assertThrows(IOException::class.java) {
            store.append(attempted, 0, attempted.size)
        }
        assertTrue(error.message?.contains("Injected active chunk creation directory sync failure") == true)
        assertTrue(
            error.suppressed.any { suppressed ->
                suppressed.message?.contains("Unable to durably clean failed chunk creation") == true
            },
        )
        assertFalse(store.hasData())

        failChunkDirectorySync = false
        store.close()
        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 128 * 1024L)
            assertFalse(reopened.hasData())
        }
    }

    @Test
    fun append_partialWriteThenFailure_salvagesCompleteFramesAndDropsTornTail() = withStoreRoot { root ->
        val prefix = pcmBytes(4_096)
        val attempted = pcmBytes(8)
        val store = PersistentAudioChunkStore(root)
        configure(store, 128 * 1024L)
        assertEquals(prefix.size, store.append(prefix, 0, prefix.size))
        replaceActiveAccessWithPartialWriteFailure(store, bytesBeforeFailure = 7)

        val error = assertThrows(IOException::class.java) {
            store.append(attempted, 0, attempted.size)
        }
        assertTrue(error.message?.contains("Injected partial append failure") == true)

        val expected = prefix + attempted.copyOf(6)
        assertEquals(expected.size.toLong(), store.countFilledBytes())
        store.sealActiveChunk()
        assertArrayEquals(expected, readAll(store))
        val preserved = File(root, "preserved").listFiles().orEmpty()
            .singleOrNull { ".partial-append" in it.name }
        assertNotNull(preserved)
        store.close()

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 128 * 1024L)
            assertArrayEquals(expected, readAll(reopened))
        }
    }

    @Test
    fun append_partialWriteWithUnpreservableTornTail_blocksFinalizationAndRecoversAfterRestart() =
        withStoreRoot { root ->
            val prefix = pcmBytes(4_096)
            val attempted = pcmBytes(8)
            var failPreservedDirectorySync = false
            val store = PersistentAudioChunkStore(
                rootDirectory = root,
                directorySync = { directory ->
                    if (failPreservedDirectorySync && directory.name == "preserved") {
                        throw IOException("Injected preserved-directory sync failure")
                    }
                },
            )
            configure(store, 128 * 1024L)
            assertEquals(prefix.size, store.append(prefix, 0, prefix.size))
            replaceActiveAccessWithPartialWriteFailure(store, bytesBeforeFailure = 7)

            failPreservedDirectorySync = true
            val error = assertThrows(IOException::class.java) {
                store.append(attempted, 0, attempted.size)
            }
            assertTrue(error.message?.contains("Injected partial append failure") == true)
            assertTrue(
                error.suppressed.any { suppressed ->
                    suppressed.message?.contains("Unable to preserve torn partial append") == true
                },
            )
            assertEquals((prefix.size + 6).toLong(), store.countFilledBytes())
            assertThrows(IOException::class.java) { store.sealActiveChunk() }

            simulateAbruptProcessDeathWithoutSync(store)
            failPreservedDirectorySync = false

            val expected = prefix + attempted.copyOf(6)
            PersistentAudioChunkStore(root).use { reopened ->
                configure(reopened, 128 * 1024L)
                assertArrayEquals(expected, readAll(reopened))
            }
            val preserved = File(root, "preserved").listFiles().orEmpty()
                .singleOrNull { ".partial-frame" in it.name }
            assertNotNull(preserved)
        }

    @Test
    fun close_surfacesActiveAccessCloseFailureWhileLeavingAudioRecoverable() = withStoreRoot { root ->
        val expected = pcmBytes(4_096)
        val store = PersistentAudioChunkStore(root)
        configure(store, 128 * 1024L)
        assertEquals(expected.size, store.append(expected, 0, expected.size))
        replaceActiveAccessWithFailingClose(store)

        val error = assertThrows(IOException::class.java) { store.close() }
        assertTrue(
            error.message?.contains("Injected active access close failure") == true ||
                error.suppressed.any { it.message?.contains("Injected active access close failure") == true },
        )

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 128 * 1024L)
            assertArrayEquals(expected, readAll(reopened))
        }
    }

    @Test
    fun close_surfacesCheckpointFailureWhileLeavingAudioRecoverable() = withStoreRoot { root ->
        val expected = pcmBytes(8_192)
        val store = PersistentAudioChunkStore(root)
        configure(store, 64 * 1024L)
        assertEquals(expected.size, store.append(expected, 0, expected.size))

        val indexATemp = File(root, BUFFER_INDEX_A_FILE_NAME + ".tmp")
        val indexBTemp = File(root, BUFFER_INDEX_B_FILE_NAME + ".tmp")
        indexATemp.deleteRecursively()
        indexBTemp.deleteRecursively()
        assertTrue(indexATemp.mkdir())
        assertTrue(indexBTemp.mkdir())

        assertThrows(IOException::class.java) { store.close() }
        indexATemp.deleteRecursively()
        indexBTemp.deleteRecursively()

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 64 * 1024L)
            assertArrayEquals(expected, readAll(reopened))
        }
    }

    @Test
    fun truncationFsyncFailure_keepsRuntimeGeometryBoundToPublishedReplacement() = withStoreRoot { root ->
        val original = pcmBytes(8_192)
        var failDirectorySync = false
        val store = PersistentAudioChunkStore(
            rootDirectory = root,
            overwriteOldest = false,
            directorySync = { directory ->
                if (failDirectorySync && directory.name == BUFFER_CHUNKS_FOLDER_NAME) {
                    throw IOException("Injected post-replacement directory sync failure")
                }
            },
        )
        configure(store, 128 * 1024L)
        assertEquals(original.size, store.append(original, 0, original.size))
        store.sealActiveChunk()

        failDirectorySync = true
        assertThrows(IOException::class.java) { configure(store, 4_096L) }
        failDirectorySync = false

        val expected = original.copyOf(4_096)
        assertEquals(expected.size.toLong(), store.countFilledBytes())
        assertArrayEquals(expected, readAll(store))
        store.close()

        PersistentAudioChunkStore(root, overwriteOldest = false).use { reopened ->
            configure(reopened, 4_096L)
            assertArrayEquals(expected, readAll(reopened))
        }
    }
    @Test
    fun oneShotShrink_recoversAtomicReplacementThatThrowsAfterCommit() = withStoreRoot { root ->
        val expected = pcmBytes(8_192)
        val store = PersistentAudioChunkStore(
            rootDirectory = root,
            overwriteOldest = false,
            atomicChunkReplace = { source, target ->
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                throw IOException("Injected post-commit atomic move failure")
            },
        )
        configure(store, 8_192L)
        assertEquals(expected.size, store.append(expected, 0, expected.size))
        store.sealActiveChunk()

        configure(store, 4_100L)
        assertEquals(4_100L, store.countFilledBytes())
        assertArrayEquals(expected.copyOf(4_100), readAll(store))
        store.close()

        PersistentAudioChunkStore(root, overwriteOldest = false).use { reopened ->
            configure(reopened, 4_100L)
            assertArrayEquals(expected.copyOf(4_100), readAll(reopened))
        }
    }

    @Test
    fun oneShotShrink_failedAtomicReplacementLeavesOriginalAuthoritative() = withStoreRoot { root ->
        val expected = pcmBytes(8_192)
        val store = PersistentAudioChunkStore(
            rootDirectory = root,
            overwriteOldest = false,
            atomicChunkReplace = { _, _ -> throw IOException("Injected pre-commit atomic move failure") },
        )
        configure(store, 8_192L)
        assertEquals(expected.size, store.append(expected, 0, expected.size))
        store.sealActiveChunk()

        assertThrows(IOException::class.java) { configure(store, 4_100L) }
        assertEquals(expected.size.toLong(), store.countFilledBytes())
        assertArrayEquals(expected, readAll(store))
        store.close()
    }

    @Test
    fun oneShotShrink_uncertainReplacementPoisonsStoreAndPreservesTemp() = withStoreRoot { root ->
        val expected = pcmBytes(8_192)
        val store = PersistentAudioChunkStore(
            rootDirectory = root,
            overwriteOldest = false,
            atomicChunkReplace = { _, target ->
                target.writeBytes(byteArrayOf(1, 2, 3, 4))
                throw IOException("Injected ambiguous atomic move failure")
            },
        )
        configure(store, 8_192L)
        assertEquals(expected.size, store.append(expected, 0, expected.size))
        store.sealActiveChunk()

        val failure = assertThrows(IOException::class.java) { configure(store, 4_100L) }
        assertTrue(failure.message?.contains("replacement result is uncertain") == true)
        assertThrows(IOException::class.java) { store.hasData() }
        assertTrue(
            File(root, BUFFER_CHUNKS_FOLDER_NAME).listFiles().orEmpty()
                .any { it.isFile && it.name.endsWith(".truncate.tmp") },
        )
        assertThrows(IOException::class.java) { store.close() }
    }

    @Test
    fun atomicChunkReplacementClassification_acceptsOnlyExactKnownSide() {
        val original = CopyDigest(4L, byteArrayOf(1, 2, 3, 4))
        val replacement = CopyDigest(2L, byteArrayOf(5, 6, 7, 8))
        assertEquals(
            AtomicChunkReplacementState.ORIGINAL,
            classifyAtomicChunkReplacementAfterFailure(original, replacement, original),
        )
        assertEquals(
            AtomicChunkReplacementState.REPLACEMENT,
            classifyAtomicChunkReplacementAfterFailure(original, replacement, replacement),
        )
        assertEquals(
            AtomicChunkReplacementState.UNCERTAIN,
            classifyAtomicChunkReplacementAfterFailure(
                original, replacement, CopyDigest(4L, byteArrayOf(9, 9, 9, 9)),
            ),
        )
        assertEquals(
            AtomicChunkReplacementState.UNCERTAIN,
            classifyAtomicChunkReplacementAfterFailure(original, replacement, null),
        )
    }

    @Test
    fun retirementTombstone_neverFallsBackToNonAtomicReplacement() = withStoreRoot { root ->
        val retired = File(root, "retired").apply { mkdirs() }
        val target = File(retired, "7").apply { writeText("old") }
        val temp = File(retired, "7.tmp").apply { writeText("new") }

        assertThrows(IOException::class.java) {
            publishRetirementTombstoneAtomically(temp, target) { source, destination ->
                throw AtomicMoveNotSupportedException(source.path, destination.path, "injected")
            }
        }
        assertEquals("old", target.readText())
        assertEquals("new", temp.readText())
    }


    @Test
    fun retiredLeaseChunk_neverResurrectsWhenBothIndexesAreLost() = withStoreRoot { root ->
        val expected = pcmBytes(32_000)
        val crashed = PersistentAudioChunkStore(root)
        configure(crashed, 128 * 1024L)
        assertEquals(expected.size, crashed.append(expected, 0, expected.size))
        crashed.sealActiveChunk()
        val lease = requireNotNull(crashed.acquireRange(0.0, crashed.durationSeconds()))

        crashed.clear()
        assertFalse(crashed.hasData())
        val retirementMarker = File(root, "retired/0").readText()
        assertTrue(retirementMarker.startsWith("v2|0|"))
        assertTrue(retirementMarker.endsWith("|${PcmSampleFormat.PCM_16.storageCode.toInt()}"))
        assertArrayEquals(expected, readLease(lease))
        val chunk = File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "0")
        assertTrue(chunk.isFile)
        crashed.close()

        File(root, BUFFER_INDEX_A_FILE_NAME).writeBytes(byteArrayOf(0x11, 0x22))
        File(root, BUFFER_INDEX_B_FILE_NAME).writeBytes(byteArrayOf(0x33, 0x44))

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 128 * 1024L)
            assertFalse(reopened.hasData())
        }
        assertFalse(chunk.exists())
        assertTrue(File(root, "retired").listFiles().orEmpty().isEmpty())
        // Intentionally do not close the abandoned lease: this models process death, where
        // in-memory references disappear without executing RangeLease.close().
    }

    @Test
    fun malformedRetirementMarker_neverResurrectsChunkAfterIndexLoss() = withStoreRoot { root ->
        val expected = pcmBytes(4_096)
        PersistentAudioChunkStore(root).use { store ->
            configure(store, 128 * 1024L)
            assertEquals(expected.size, store.append(expected, 0, expected.size))
            store.sealActiveChunk()
        }
        val retired = File(root, "retired").apply { mkdirs() }
        File(retired, "0").writeText("v1|0|corrupt")
        File(root, BUFFER_INDEX_A_FILE_NAME).writeBytes(byteArrayOf(0x11, 0x22))
        File(root, BUFFER_INDEX_B_FILE_NAME).writeBytes(byteArrayOf(0x33, 0x44))

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 128 * 1024L)
            assertFalse(reopened.hasData())
        }
        assertFalse(File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "0").exists())
        assertTrue(File(root, "preserved").listFiles().orEmpty().any { ".retired-ambiguous" in it.name })
    }

    @Test
    fun malformedRetirementMarker_neverOverwritesExistingRecoveryCopy() = withStoreRoot { root ->
        val expected = pcmBytes(4_096)
        PersistentAudioChunkStore(root).use { store ->
            configure(store, 128 * 1024L)
            assertEquals(expected.size, store.append(expected, 0, expected.size))
            store.sealActiveChunk()
        }
        val retired = File(root, "retired").apply { mkdirs() }
        File(retired, "0").writeText("v1|0|corrupt")
        File(root, BUFFER_INDEX_A_FILE_NAME).writeBytes(byteArrayOf(0x11, 0x22))
        File(root, BUFFER_INDEX_B_FILE_NAME).writeBytes(byteArrayOf(0x33, 0x44))
        val preserved = File(root, "preserved").apply { mkdirs() }
        val sentinelBytes = byteArrayOf(9, 8, 7, 6)
        val sentinel = File(preserved, "0.retired-ambiguous").apply { writeBytes(sentinelBytes) }

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 128 * 1024L)
            assertFalse(reopened.hasData())
        }

        assertArrayEquals(sentinelBytes, sentinel.readBytes())
        assertTrue(File(preserved, "0.retired-ambiguous.1").isFile)
    }

    @Test
    fun storagePathState_neverTreatsUnavailableAsMissing() = withStoreRoot { root ->
        val present = File(root, "present").apply { writeText("data") }
        val missing = File(root, "missing")
        val nonDirectory = File(root, "not-a-directory").apply { writeText("data") }
        val unavailable = File(nonDirectory, "child")

        assertEquals(StoragePathState.PRESENT, storagePathState(present))
        assertEquals(StoragePathState.MISSING, storagePathState(missing))
        assertEquals(StoragePathState.UNAVAILABLE, storagePathState(unavailable))
        assertTrue(storagePathMayContainData(StoragePathState.PRESENT))
        assertFalse(storagePathMayContainData(StoragePathState.MISSING))
        assertTrue(storagePathMayContainData(StoragePathState.UNAVAILABLE))
    }

    @Test
    fun atomicFileBackingState_neverTreatsAmbiguousBackingAsAbsent() {
        val base = File("build/tmp/atomic-state-test/state.bin")
        fun state(vararg values: Pair<String, StoragePathState>): StoragePathState {
            val bySuffix = values.toMap()
            return atomicFileBackingState(base) { candidate ->
                val suffix = candidate.path.removePrefix(base.path)
                bySuffix[suffix] ?: StoragePathState.MISSING
            }
        }

        assertEquals(StoragePathState.MISSING, state())
        assertEquals(StoragePathState.PRESENT, state(".bak" to StoragePathState.PRESENT))
        assertEquals(StoragePathState.PRESENT, state(".new" to StoragePathState.PRESENT))
        assertEquals(StoragePathState.UNAVAILABLE, state("" to StoragePathState.UNAVAILABLE))
        assertEquals(
            StoragePathState.PRESENT,
            state("" to StoragePathState.UNAVAILABLE, ".bak" to StoragePathState.PRESENT),
        )
    }

    @Test
    fun staleRetirementMarker_isClearedBeforeChunkIdReuse() = withStoreRoot { root ->
        val expected = pcmBytes(4_096)
        val store = PersistentAudioChunkStore(root)
        configure(store, 128 * 1024L)
        val nextId = PersistentAudioChunkStore::class.java.getDeclaredField("nextChunkId")
        nextId.isAccessible = true
        nextId.setInt(store, 7)
        val retired = File(root, "retired").apply { mkdirs() }
        val stale = File(retired, "7").apply { writeText("v1|7|1|8000|1|PCM_16") }

        assertEquals(expected.size, store.append(expected, 0, expected.size))
        store.sealActiveChunk()
        assertFalse(stale.exists())
        store.close()

        PersistentAudioChunkStore(root).use { reopened ->
            configure(reopened, 128 * 1024L)
            assertArrayEquals(expected, readAll(reopened))
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
    fun loopingExplicitShrink_retainsExactNewestBytesAfterBoundaryLeaseReleases() = withStoreRoot { root ->
        val expected = pcmBytes(30_000)
        val store = PersistentAudioChunkStore(root, overwriteOldest = true)
        configure(store, 131_072L)
        assertEquals(expected.size, store.append(expected, 0, expected.size))
        store.sealActiveChunk()
        val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))

        configure(store, 10_000L)
        assertTrue(store.countFilledBytes() >= 10_000L)
        assertArrayEquals(expected, readLease(lease))
        lease.close()

        assertEquals(10_000L, store.countFilledBytes())
        assertArrayEquals(expected.copyOfRange(expected.size - 10_000, expected.size), readAll(store))
        store.close()

        PersistentAudioChunkStore(root, overwriteOldest = true).use { reopened ->
            configure(reopened, 10_000L)
            assertEquals(10_000L, reopened.countFilledBytes())
            assertArrayEquals(expected.copyOfRange(expected.size - 10_000, expected.size), readAll(reopened))
        }
    }

    @Test
    fun loopingExplicitTimeShrink_retainsExactNewestFrameWindowAcrossRestart() = withStoreRoot { root ->
        val expected = pcmBytes(40_000) // 2.5 s at 8 kHz mono PCM16.
        PersistentAudioChunkStore(root, overwriteOldest = true).use { store ->
            configureTime(store, 10L)
            assertEquals(expected.size, store.append(expected, 0, expected.size))
            store.sealActiveChunk()
            configureTime(store, 1L)
            assertEquals(16_000L, store.countFilledBytes())
            assertEquals(1.0, store.durationSeconds(), 0.000_001)
            assertArrayEquals(expected.copyOfRange(expected.size - 16_000, expected.size), readAll(store))
        }

        PersistentAudioChunkStore(root, overwriteOldest = true).use { reopened ->
            configureTime(reopened, 1L)
            assertEquals(16_000L, reopened.countFilledBytes())
            assertArrayEquals(expected.copyOfRange(expected.size - 16_000, expected.size), readAll(reopened))
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
                var expectedAfterResize: ByteArray? = null
                when (random.nextInt(6)) {
                    0, 1, 2, 3 -> {
                        val count = (1 + random.nextInt(2_048)) * 2
                        val bytes = ByteArray(count) { random.nextInt(256).toByte() }
                        assertEquals("looping append at $iteration", count, store.append(bytes, 0, count))
                        completeHistory.write(bytes)
                    }
                    4 -> {
                        val retainedBeforeResize = readAllOrEmpty(store)
                        capacity = ((256 + random.nextInt(8_000)) * 2).toLong()
                        val expectedSize = minOf(capacity, retainedBeforeResize.size.toLong()).toInt()
                        expectedAfterResize = retainedBeforeResize.copyOfRange(
                            retainedBeforeResize.size - expectedSize,
                            retainedBeforeResize.size,
                        )
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
                expectedAfterResize?.let { expected ->
                    assertArrayEquals("explicit resize over-deleted at $iteration", expected, observed)
                }
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun loopingRetentionShrink_neverRetiresCorruptedFinalizedChunk() = withStoreRoot { root ->
        val first = pcmBytes(8_192)
        val second = ByteArray(8_192) { index -> ((index * 37 + 11) and 0xff).toByte() }
        PersistentAudioChunkStore(root, overwriteOldest = true).use { store ->
            configure(store, 64 * 1024L)
            assertEquals(first.size, store.append(first, 0, first.size))
            store.sealActiveChunk()
            assertEquals(second.size, store.append(second, 0, second.size))
            store.sealActiveChunk()
        }
        val firstChunk = File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "0")
        RandomAccessFile(firstChunk, "rw").use { file ->
            file.seek(128L + 137L)
            val original = file.read()
            file.seek(128L + 137L)
            file.write(original xor 0x40)
            file.fd.sync()
        }
        val corruptedBytes = firstChunk.readBytes()

        PersistentAudioChunkStore(root, overwriteOldest = true).use { reopened ->
            assertThrows(IOException::class.java) { configure(reopened, second.size.toLong()) }
        }
        assertTrue(firstChunk.isFile)
        assertArrayEquals(corruptedBytes, firstChunk.readBytes())
    }

    @Test
    fun oneShotRetentionShrink_neverRetiresCorruptedFinalizedTailChunk() = withStoreRoot { root ->
        val first = pcmBytes(8_192)
        val second = ByteArray(8_192) { index -> ((index * 41 + 23) and 0xff).toByte() }
        PersistentAudioChunkStore(root, overwriteOldest = false).use { store ->
            configure(store, 64 * 1024L)
            assertEquals(first.size, store.append(first, 0, first.size))
            store.sealActiveChunk()
            assertEquals(second.size, store.append(second, 0, second.size))
            store.sealActiveChunk()
        }
        val chunkFiles = File(root, BUFFER_CHUNKS_FOLDER_NAME).listFiles().orEmpty()
            .filter { it.name.toUIntOrNull() != null }
            .sortedBy { it.name.toUInt() }
        val secondChunk = requireNotNull(chunkFiles.lastOrNull())
        RandomAccessFile(secondChunk, "rw").use { file ->
            file.seek(128L + 311L)
            val original = file.read()
            file.seek(128L + 311L)
            file.write(original xor 0x10)
            file.fd.sync()
        }
        val corruptedBytes = secondChunk.readBytes()

        PersistentAudioChunkStore(root, overwriteOldest = false).use { reopened ->
            val beforeShrink = requireNotNull(reopened.peekSnapshot())
            assertTrue(beforeShrink.chunkCount >= 2)
            assertEquals((first.size + second.size).toLong(), beforeShrink.filledBytes)
            assertThrows(IOException::class.java) { readAll(reopened) }
            assertThrows(IOException::class.java) { configure(reopened, first.size.toLong()) }
        }
        assertTrue(secondChunk.isFile)
        assertArrayEquals(corruptedBytes, secondChunk.readBytes())
    }

    @Test
    fun loopingRetentionBoundaryTrim_neverRecertifiesCorruptedFinalizedChunk() = withStoreRoot { root ->
        val expected = pcmBytes(16_000)
        PersistentAudioChunkStore(root, overwriteOldest = true).use { store ->
            configure(store, 64 * 1024L)
            assertEquals(expected.size, store.append(expected, 0, expected.size))
            store.sealActiveChunk()
        }
        val chunk = File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "0")
        RandomAccessFile(chunk, "rw").use { file ->
            file.seek(128L + 4_000L)
            val original = file.read()
            file.seek(128L + 4_000L)
            file.write(original xor 0x20)
            file.fd.sync()
        }
        val corruptedBytes = chunk.readBytes()

        PersistentAudioChunkStore(root, overwriteOldest = true).use { reopened ->
            // Initial 64 KiB retention subdivides this history into 4 KiB chunks. Keeping
            // 14 KiB therefore requires a 2 KiB partial trim of the corrupted oldest chunk.
            assertThrows(IOException::class.java) { configure(reopened, 14_000L) }
        }
        assertTrue(chunk.isFile)
        assertArrayEquals(corruptedBytes, chunk.readBytes())
    }

    @Test
    fun explicitClear_stillDeletesCorruptedFinalizedChunk() = withStoreRoot { root ->
        val expected = pcmBytes(8_192)
        PersistentAudioChunkStore(root, overwriteOldest = true).use { store ->
            configure(store, 64 * 1024L)
            assertEquals(expected.size, store.append(expected, 0, expected.size))
            store.sealActiveChunk()
        }
        val chunk = File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "0")
        RandomAccessFile(chunk, "rw").use { file ->
            file.seek(file.length() - 1L)
            val original = file.read()
            file.seek(file.length() - 1L)
            file.write(original xor 0x01)
            file.fd.sync()
        }

        PersistentAudioChunkStore(root, overwriteOldest = true).use { reopened ->
            configure(reopened, 64 * 1024L)
            reopened.clear()
            assertFalse(reopened.hasData())
        }
        assertFalse(chunk.exists())
    }

    @Test
    fun checksumCorruption_isDetectedWithoutDeletingTheOnlyChunk() = withStoreRoot { root ->
        val expected = pcmBytes(16_000)
        PersistentAudioChunkStore(root).use { store ->
            configure(store, 128 * 1024L)
            store.append(expected, 0, expected.size)
            store.sealActiveChunk()
        }
        val chunk = File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "0")
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
    fun waveformSampling_surfacesTerminalReaderCloseFailureWithoutRetry() = withStoreRoot { root ->
        val expectedBytes = pcmBytes(24_000)
        PersistentAudioChunkStore(root).use { store ->
            configure(store, 128 * 1024L)
            assertEquals(expectedBytes.size, store.append(expectedBytes, 0, expectedBytes.size))
            store.sealActiveChunk()
            val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
            val closeFailure = IOException("Injected waveform reader close failure")
            var closeAttempts = 0
            try {
                val thrown = assertThrows(IOException::class.java) {
                    lease.sampleWaveformEnvelopeProgressive(
                        bucketCount = 16,
                        accessCloser = { access ->
                            closeAttempts++
                            access.close()
                            throw closeFailure
                        },
                    ) { _, _ -> true }
                }
                assertSame(closeFailure, thrown)
                assertEquals(1, closeAttempts)
            } finally {
                lease.close()
            }
            assertArrayEquals(expectedBytes, readAll(store))
        }
    }

    @Test
    fun waveformSampling_preservesReadFailureAndSuppressesTerminalCloseFailure() = withStoreRoot { root ->
        val expectedBytes = pcmBytes(24_000)
        PersistentAudioChunkStore(root).use { store ->
            configure(store, 128 * 1024L)
            assertEquals(expectedBytes.size, store.append(expectedBytes, 0, expectedBytes.size))
            store.sealActiveChunk()
            val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
            val readFailure = IOException("Injected waveform read failure")
            val closeFailure = IOException("Injected waveform reader close failure")
            var closeAttempts = 0
            try {
                val thrown = assertThrows(IOException::class.java) {
                    lease.sampleWaveformEnvelopeProgressive(
                        bucketCount = 16,
                        accessFactory = { file ->
                            object : RandomAccessFile(file, "r") {
                                override fun seek(pos: Long) {
                                    throw readFailure
                                }
                            }
                        },
                        accessCloser = { access ->
                            closeAttempts++
                            access.close()
                            throw closeFailure
                        },
                    ) { _, _ -> true }
                }
                assertSame(readFailure, thrown)
                assertEquals(listOf(closeFailure), readFailure.suppressed.toList())
                assertEquals(1, closeAttempts)
            } finally {
                lease.close()
            }
            assertArrayEquals(expectedBytes, readAll(store))
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

    private fun configureTime(store: PersistentAudioChunkStore, retentionSeconds: Long) {
        store.configure(
            requestedRetentionMode = RetentionMode.TIME,
            requestedRetentionValue = retentionSeconds,
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

    private fun replaceActiveAccessWithPartialWriteFailure(
        store: PersistentAudioChunkStore,
        bytesBeforeFailure: Int,
    ) {
        require(bytesBeforeFailure > 0)
        val recordField = PersistentAudioChunkStore::class.java.getDeclaredField("activeRecord")
        recordField.isAccessible = true
        val record = requireNotNull(recordField.get(store) as? PersistentAudioChunkStore.ChunkRecord)

        val accessField = PersistentAudioChunkStore::class.java.getDeclaredField("activeAccess")
        accessField.isAccessible = true
        val previous = requireNotNull(accessField.get(store) as? RandomAccessFile)
        previous.close()
        val failing = object : RandomAccessFile(record.file, "rw") {
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                super.write(bytes, offset, minOf(bytesBeforeFailure, count))
                throw IOException("Injected partial append failure")
            }
        }
        failing.seek(record.payloadOffsetBytes + record.payloadBytes)
        accessField.set(store, failing)
    }

    private fun replaceActiveAccessWithFailingClose(store: PersistentAudioChunkStore) {
        val recordField = PersistentAudioChunkStore::class.java.getDeclaredField("activeRecord")
        recordField.isAccessible = true
        val record = requireNotNull(recordField.get(store) as? PersistentAudioChunkStore.ChunkRecord)

        val accessField = PersistentAudioChunkStore::class.java.getDeclaredField("activeAccess")
        accessField.isAccessible = true
        val previous = requireNotNull(accessField.get(store) as? RandomAccessFile)
        previous.close()
        accessField.set(
            store,
            object : RandomAccessFile(record.file, "rw") {
                override fun close() {
                    super.close()
                    throw IOException("Injected active access close failure")
                }
            },
        )
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
