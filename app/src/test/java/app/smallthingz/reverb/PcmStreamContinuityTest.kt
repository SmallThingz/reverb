package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class PcmStreamContinuityTest {
    private inline fun withRoot(block: (File) -> Unit) {
        val parent = File("build/tmp/pcm-stream-continuity").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "store-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private fun pcm(frames: Int, channels: Int, format: PcmSampleFormat, signal: (Int) -> Double): ByteArray =
        ByteBuffer.allocate(frames * channels * format.bytesPerSample).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { frame -> repeat(channels) { channel ->
                val value = signal(frame) * if (channel == 0) 1 else -1
                when (format) {
                    PcmSampleFormat.PCM_8 -> put((128 + (value * 128).roundToInt()).coerceIn(0, 255).toByte())
                    PcmSampleFormat.PCM_16 -> putShort((value * 32768).roundToInt().coerceIn(-32768, 32767).toShort())
                    PcmSampleFormat.PCM_FLOAT -> putFloat(value.toFloat())
                }
            } }
        }.array()

    private fun configure(store: PersistentAudioChunkStore, chunkFrames: Int, rate: Int,
                          channels: Int = 1, format: PcmSampleFormat = PcmSampleFormat.PCM_FLOAT) {
        store.configure(RetentionMode.SIZE, chunkFrames.toLong() * channels * format.bytesPerSample * 16,
            rate, channels, format)
    }

    private fun read(store: PersistentAudioChunkStore, rate: Int, channels: Int = 1,
                     format: PcmSampleFormat = PcmSampleFormat.PCM_FLOAT, budget: Long = Long.MAX_VALUE): ByteArray =
        requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease -> read(lease, rate, channels, format, budget) }

    private fun read(lease: PersistentAudioChunkStore.RangeLease, rate: Int, channels: Int,
                     format: PcmSampleFormat, budget: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val result = lease.readNormalized(rate, channels, format, budget) { bytes, offset, count ->
            output.write(bytes, offset, count); count
        }
        assertEquals(output.size().toLong(), result.sampleBytes)
        assertEquals(minOf((lease.durationSeconds * rate).roundToLong(), budget) * channels * format.bytesPerSample,
            result.sampleBytes)
        return output.toByteArray()
    }

    private fun assertClose(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        val first = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN)
        val second = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN)
        while (first.hasRemaining()) {
            val offset = first.position() / 4
            assertEquals("sample $offset, including rollover/filter endpoints", first.float.toDouble(), second.float.toDouble(), 0.000002)
        }
    }

    @Test fun splitAndUnsplitHaveTheSamePhaseAndHaloForToneImpulseAndDc() = withRoot { root ->
        val frames = 4099 // Neither integer nor fractional conversions divide the 257-frame chunks.
        val signals = listOf<(Int) -> Double>(
            { frame -> 0.2 * sin(2 * PI * 0.49 * frame) + 0.15 * sin(2 * PI * 0.025 * frame) },
            { frame -> if (frame % 257 == 0) 0.75 else 0.0 },
            { _ -> 0.25 },
        )
        for ((sourceRate, targetRate) in listOf(48000 to 16000, 44100 to 16000, 96000 to 7350, 48000 to 44100, 7350 to 24000)) {
            for (channels in 1..2) for (format in PcmSampleFormat.entries) for ((kind, signal) in signals.withIndex()) {
                val name = "$sourceRate-$targetRate-$channels-$format-$kind"
                val bytes = pcm(frames, channels, format, signal)
                PersistentAudioChunkStore(File(root, "split-$name")).use { split ->
                    PersistentAudioChunkStore(File(root, "whole-$name")).use { whole ->
                        configure(split, 257, sourceRate, channels, format)
                        configure(whole, frames + 1, sourceRate, channels, format)
                        // Multiple append calls include an exact rollover at a call boundary.
                        val firstBytes = 257 * channels * format.bytesPerSample
                        assertEquals(firstBytes, split.append(bytes, 0, firstBytes))
                        assertEquals(bytes.size - firstBytes, split.append(bytes, firstBytes, bytes.size - firstBytes))
                        assertEquals(bytes.size, whole.append(bytes, 0, bytes.size))
                        assertTrue(requireNotNull(split.peekSnapshot()).chunkCount > 1)
                        assertEquals(1, requireNotNull(whole.peekSnapshot()).chunkCount)
                        val full = read(split, targetRate, channels)
                        assertClose(read(whole, targetRate, channels), full)
                        val bounded = read(split, targetRate, channels, budget = 73)
                        assertArrayEquals(full.copyOfRange(full.size - bounded.size, full.size), bounded)
                        assertEquals(0, read(split, targetRate, channels, budget = 0).size)
                        if (channels == 2) {
                            val mono = ByteBuffer.wrap(read(split, targetRate)).order(ByteOrder.LITTLE_ENDIAN)
                            while (mono.hasRemaining()) assertEquals(0.0, mono.float.toDouble(), 0.000002)
                        }
                    }
                }
            }
        }
    }

    @Test fun sameRateSplitStreamRetainsEveryOriginalByteIncludingFloatBits() = withRoot { root ->
        for (format in PcmSampleFormat.entries) for (channels in 1..2) {
            val bytes = ByteArray(4099 * channels * format.bytesPerSample) { (it * 37 + 11).toByte() }
            PersistentAudioChunkStore(File(root, "$format-$channels")).use { store ->
                configure(store, 257, 44100, channels, format)
                assertEquals(bytes.size, store.append(bytes, 0, bytes.size))
                assertArrayEquals(bytes, read(store, 44100, channels, format))
                assertArrayEquals(bytes.takeLast(73 * channels * format.bytesPerSample).toByteArray(),
                    read(store, 44100, channels, format, 73))
            }
        }
    }

    @Test fun aFilterWindowMaySpanManyTinyOwnedChunks() = withRoot { root ->
        val bytes = pcm(15, 2, PcmSampleFormat.PCM_16) { if (it == 7) 0.75 else 0.25 }
        PersistentAudioChunkStore(File(root, "tiny")).use { tiny ->
            PersistentAudioChunkStore(File(root, "whole")).use { whole ->
                configure(tiny, 1, 48000, 2, PcmSampleFormat.PCM_16)
                configure(whole, 16, 48000, 2, PcmSampleFormat.PCM_16)
                assertEquals(bytes.size, tiny.append(bytes, 0, bytes.size))
                assertEquals(bytes.size, whole.append(bytes, 0, bytes.size))
                assertClose(read(whole, 16000, 2), read(tiny, 16000, 2))
            }
        }
    }

    @Test fun anInterruptedLeaseReadRetainsCancellationAndDeliversNoSamples() = withRoot { root ->
        PersistentAudioChunkStore(root).use { store ->
            configure(store, 257, 48000)
            val bytes = pcm(1028, 1, PcmSampleFormat.PCM_FLOAT) { 0.25 }
            assertEquals(bytes.size, store.append(bytes, 0, bytes.size))
            requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                var delivered = 0
                try {
                    Thread.currentThread().interrupt()
                    assertThrows(InterruptedIOException::class.java) {
                        lease.readNormalized(16000, 1, PcmSampleFormat.PCM_FLOAT) { _, _, count -> delivered += count; count }
                    }
                    assertTrue(Thread.currentThread().isInterrupted)
                    assertEquals(0, delivered)
                } finally { Thread.interrupted() }
            }
        }
    }

    @Test fun aPinnedRunKeepsItsOwnHaloAfterClearAndNewCapture() = withRoot { root ->
        PersistentAudioChunkStore(root).use { store ->
            configure(store, 257, 44100)
            val bytes = pcm(1028, 1, PcmSampleFormat.PCM_FLOAT) { 0.25 * sin(2 * PI * 0.49 * it) }
            assertEquals(bytes.size, store.append(bytes, 0, bytes.size))
            requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                val expected = read(lease, 16000, 1, PcmSampleFormat.PCM_FLOAT, Long.MAX_VALUE)
                store.clear()
                val fresh = pcm(1028, 1, PcmSampleFormat.PCM_FLOAT) { -0.5 }
                assertEquals(fresh.size, store.append(fresh, 0, fresh.size))
                assertArrayEquals(expected, read(lease, 16000, 1, PcmSampleFormat.PCM_FLOAT, Long.MAX_VALUE))
            }
        }
    }

    @Test fun failedRolloverCreationCannotCarryContinuityIntoTheRetry() = withRoot { root ->
        var failNextChunkSync = false
        PersistentAudioChunkStore(root, directorySync = { directory ->
            if (failNextChunkSync && directory.name == BUFFER_CHUNKS_FOLDER_NAME) {
                failNextChunkSync = false
                throw IOException("Injected chunk creation sync failure")
            }
        }).use { store ->
            configure(store, 257, 48000)
            val positive = pcm(257, 1, PcmSampleFormat.PCM_FLOAT) { 0.5 }
            val negative = pcm(257, 1, PcmSampleFormat.PCM_FLOAT) { -0.5 }
            assertEquals(positive.size, store.append(positive, 0, positive.size))
            failNextChunkSync = true
            assertThrows(IOException::class.java) { store.append(negative, 0, negative.size) }
            assertEquals(negative.size, store.append(negative, 0, negative.size))
            assertEquals(0, headerWord(File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "1"), 36))
            val samples = ByteBuffer.wrap(read(store, 16000)).order(ByteOrder.LITTLE_ENDIAN)
            repeat(samples.remaining() / 4) { frame ->
                assertEquals(if (frame < 86) 0.5 else -0.5, samples.float.toDouble(), 0.000002)
            }
        }
    }

    @Test fun sealAndFormatChangesResetTheFilterDespiteAdjacentIdsAndTimestamps() = withRoot { root ->
        for (changeFormat in listOf(false, true)) {
            PersistentAudioChunkStore(File(root, "gap-$changeFormat")).use { store ->
                configure(store, 257, 48000)
                val positive = pcm(257, 1, PcmSampleFormat.PCM_FLOAT) { 0.5 }
                assertEquals(positive.size, store.appendCapturedAt(positive, 0, positive.size, 1000))
                // The full chunk already finalized: seal must clear the pending rollover too.
                if (changeFormat) configure(store, 257, 44100) else store.sealActiveChunk()
                val negative = pcm(257, 1, PcmSampleFormat.PCM_FLOAT) { -0.5 }
                assertEquals(negative.size, store.appendCapturedAt(negative, 0, negative.size, 1000))
                val samples = ByteBuffer.wrap(read(store, 16000)).order(ByteOrder.LITTLE_ENDIAN)
                val firstCount = (257.0 * 16000 / 48000).roundToLong().toInt()
                repeat(samples.remaining() / 4) { frame ->
                    assertEquals(if (frame < firstCount) 0.5 else -0.5, samples.float.toDouble(), 0.000002)
                }
            }
        }
    }

    @Test fun persistedRolloversSurviveRecoveryButTheNextCaptureStartsANewRun() = withRoot { root ->
        val source = pcm(1028, 1, PcmSampleFormat.PCM_FLOAT) { 0.2 * sin(2 * PI * 0.49 * it) }
        val history = File(root, "history")
        lateinit var expected: ByteArray
        PersistentAudioChunkStore(history).use { store ->
            configure(store, 257, 44100)
            assertEquals(source.size, store.append(source, 0, source.size))
            expected = read(store, 16000)
        }
        // Recovery must derive continuity from verified chunk headers even without indexes.
        File(history, BUFFER_INDEX_A_FILE_NAME).delete()
        File(history, BUFFER_INDEX_B_FILE_NAME).delete()
        PersistentAudioChunkStore(history).use { store ->
            configure(store, 257, 44100)
            assertArrayEquals(expected, read(store, 16000))
            val next = pcm(257, 1, PcmSampleFormat.PCM_FLOAT) { 0.5 }
            assertEquals(next.size, store.append(next, 0, next.size))
            val output = ByteBuffer.wrap(read(store, 16000)).order(ByteOrder.LITTLE_ENDIAN)
            output.position(expected.size)
            while (output.hasRemaining()) assertEquals(0.5, output.float.toDouble(), 0.000002)
            assertEquals(0, headerWord(File(File(history, BUFFER_CHUNKS_FOLDER_NAME), "4"), 36))
        }
    }

    private fun headerWord(file: File, offset: Int): Int = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)

    private fun setMarker(file: File, marker: Int, repairCrc: Boolean = true) {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(36, marker)
        if (repairCrc) buffer.putInt(40, CRC32().apply { update(bytes, 0, 40) }.value.toInt())
        file.writeBytes(bytes)
    }

    @Test fun legacyZeroAndUnknownMarkersRemainBoundariesAndTheMarkerIsCoveredByHeaderCrc() = withRoot { root ->
        for (marker in listOf(0, 2)) {
            val history = File(root, "legacy-$marker")
            PersistentAudioChunkStore(history).use { store ->
                configure(store, 257, 48000)
                val bytes = pcm(514, 1, PcmSampleFormat.PCM_FLOAT) { if (it < 257) 0.5 else -0.5 }
                assertEquals(bytes.size, store.append(bytes, 0, bytes.size))
            }
            val second = File(File(history, BUFFER_CHUNKS_FOLDER_NAME), "1")
            assertEquals(1, headerWord(second, 36))
            setMarker(second, marker)
            PersistentAudioChunkStore(history).use { store ->
                configure(store, 257, 48000)
                val samples = ByteBuffer.wrap(read(store, 16000)).order(ByteOrder.LITTLE_ENDIAN)
                repeat(samples.remaining() / 4) { frame ->
                    assertEquals(if (frame < 86) 0.5 else -0.5, samples.float.toDouble(), 0.000002)
                }
            }
            setMarker(second, 1, repairCrc = false)
            PersistentAudioChunkStore(history).use { store ->
                configure(store, 257, 48000)
                assertEquals("unverified continuation metadata must not be accepted", 257 * 4L, store.countFilledBytes())
            }
        }
    }

    @Test fun aCorruptNeighborCannotContributeHaloSamplesBeforeItsWholePayloadIsVerified() = withRoot { root ->
        PersistentAudioChunkStore(root).use { store ->
            configure(store, 257, 48000)
            val bytes = pcm(514, 1, PcmSampleFormat.PCM_FLOAT) { sin(2 * PI * 0.49 * it) * 0.5 }
            assertEquals(bytes.size, store.append(bytes, 0, bytes.size))
            requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use { lease ->
                val second = File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "1")
                val corrupt = second.readBytes()
                corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte()
                second.writeBytes(corrupt)
                var delivered = 0
                assertThrows(IOException::class.java) {
                    lease.readNormalized(16000, 1, PcmSampleFormat.PCM_FLOAT) { _, _, count -> delivered += count; count }
                }
                assertEquals(0, delivered) // The complete range fits one output batch.
            }
        }
    }

    @Test fun prefixRetentionKeepsSurvivingRolloverContinuityAndSuffixRetentionStopsContinuation() = withRoot { root ->
        val source = pcm(4099, 1, PcmSampleFormat.PCM_FLOAT) { 0.2 * sin(2 * PI * 0.49 * it) }
        PersistentAudioChunkStore(File(root, "loop")).use { store ->
            configure(store, 257, 44100)
            assertEquals(source.size, store.append(source, 0, source.size))
            store.sealActiveChunk()
            store.configure(RetentionMode.SIZE, 3000 * 4L, 44100, 1, PcmSampleFormat.PCM_FLOAT)
            assertEquals(3000 * 4L, store.countFilledBytes())
            PersistentAudioChunkStore(File(root, "whole")).use { whole ->
                configure(whole, 3001, 44100)
                val tail = source.copyOfRange(source.size - 3000 * 4, source.size)
                assertEquals(tail.size, whole.append(tail, 0, tail.size))
                assertClose(read(whole, 16000), read(store, 16000))
            }
        }
        PersistentAudioChunkStore(File(root, "one"), overwriteOldest = false).use { store ->
            configure(store, 257, 48000)
            assertEquals(source.size, store.append(source, 0, source.size))
            store.configure(RetentionMode.SIZE, 1000 * 4L, 48000, 1, PcmSampleFormat.PCM_FLOAT)
            assertEquals(1000 * 4L, store.countFilledBytes())
            configure(store, 257, 48000)
            val next = pcm(257, 1, PcmSampleFormat.PCM_FLOAT) { 0.5 }
            assertEquals(next.size, store.append(next, 0, next.size))
            val output = ByteBuffer.wrap(read(store, 16000)).order(ByteOrder.LITTLE_ENDIAN)
            output.position((1000.0 / 3).roundToInt() * 4)
            while (output.hasRemaining()) assertEquals(0.5, output.float.toDouble(), 0.000002)
        }
    }

    @Test fun deferredOldestPrefixTrimBetweenFullAppendsKeepsTheNewestContinuation() = withRoot { root ->
        val source = pcm(18 * 257, 1, PcmSampleFormat.PCM_FLOAT) {
            0.25 * sin(2 * PI * 0.49 * it) + if (it == 17 * 257) 0.5 else 0.0
        }
        val history = File(root, "loop")
        PersistentAudioChunkStore(history).use { store ->
            store.configure(RetentionMode.SIZE, 4113 * 4L, 44100, 1, PcmSampleFormat.PCM_FLOAT,
                deferRetentionCleanup = true)
            val firstBytes = 17 * 257 * 4
            assertEquals(firstBytes, store.append(source, 0, firstBytes))
            assertEquals(17 * 257 * 4L, store.countFilledBytes())
            assertTrue(store.performRetentionMaintenanceStep().progressed)
            assertEquals(4113 * 4L, store.countFilledBytes())
            assertEquals(128 + 4L, File(File(history, BUFFER_CHUNKS_FOLDER_NAME), "0").length())
            assertEquals(source.size - firstBytes, store.append(source, firstBytes, source.size - firstBytes))
            assertEquals(1, headerWord(File(File(history, BUFFER_CHUNKS_FOLDER_NAME), "17"), 36))

            val retained = source.copyOfRange(256 * 4, source.size)
            PersistentAudioChunkStore(File(root, "whole")).use { whole ->
                configure(whole, retained.size / 4 + 1, 44100)
                assertEquals(retained.size, whole.append(retained, 0, retained.size))
                val output = read(store, 16000)
                assertClose(read(whole, 16000), output)
                val bounded = read(store, 16000, budget = 73)
                assertArrayEquals(output.copyOfRange(output.size - bounded.size, output.size), bounded)
            }
        }
    }

    @Test fun failedPrefixReplacementSyncDoesNotRestorePendingCaptureContinuity() = withRoot { root ->
        var replacementVisible = false
        var failReplacementSync = false
        PersistentAudioChunkStore(root,
            atomicChunkReplace = { source, target ->
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                replacementVisible = true
            },
            directorySync = { directory ->
                if (failReplacementSync && replacementVisible && directory.name == BUFFER_CHUNKS_FOLDER_NAME) {
                    failReplacementSync = false
                    throw IOException("Injected prefix replacement sync failure")
                }
            },
        ).use { store ->
            store.configure(RetentionMode.SIZE, 4113 * 4L, 44100, 1, PcmSampleFormat.PCM_FLOAT,
                deferRetentionCleanup = true)
            val source = pcm(17 * 257, 1, PcmSampleFormat.PCM_FLOAT) { 0.25 }
            assertEquals(source.size, store.append(source, 0, source.size))
            failReplacementSync = true
            assertThrows(IOException::class.java) { store.performRetentionMaintenanceStep() }
            assertTrue(replacementVisible)
            val next = pcm(257, 1, PcmSampleFormat.PCM_FLOAT) { -0.25 }
            assertEquals(next.size, store.append(next, 0, next.size))
            assertEquals(0, headerWord(File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "17"), 36))
        }
    }

    @Test fun suffixRetirementFailureLeavesTheCutoffTailIntact() = withRoot { root ->
        var failRetirementSync = false
        var replacementAttempted = false
        PersistentAudioChunkStore(root, overwriteOldest = false,
            atomicChunkReplace = { _, _ ->
                replacementAttempted = true
                throw IOException("The cutoff must not be replaced before suffix retirement")
            },
            directorySync = { directory ->
                if (failRetirementSync && directory.name == "retired") {
                    failRetirementSync = false
                    throw IOException("Injected suffix retirement sync failure")
                }
            },
        ).use { store ->
            configure(store, 257, 48000)
            val source = pcm(4 * 257, 1, PcmSampleFormat.PCM_FLOAT) { 0.25 }
            assertEquals(source.size, store.append(source, 0, source.size))
            val cutoff = File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "1")
            val originalCutoff = cutoff.readBytes()
            failRetirementSync = true
            assertThrows(IOException::class.java) {
                store.configure(RetentionMode.SIZE, 400 * 4L, 48000, 1, PcmSampleFormat.PCM_FLOAT)
            }
            assertFalse(replacementAttempted)
            assertArrayEquals(originalCutoff, cutoff.readBytes())
        }
    }

    @Test fun failedCutoffReplacementSyncCannotRecoverASuccessorAcrossTheDiscardedTail() = withRoot { root ->
        val history = File(root, "history")
        val crashImage = File(root, "crash-image")
        var replacementVisible = false
        var failReplacementSync = false
        var snapshotCopied = false
        val source = pcm(4 * 257, 1, PcmSampleFormat.PCM_FLOAT) { 0.25 * sin(2 * PI * 0.49 * it) }
        lateinit var expected: ByteArray
        PersistentAudioChunkStore(File(root, "whole")).use { whole ->
            configure(whole, 401, 48000)
            assertEquals(400 * 4, whole.append(source, 0, 400 * 4))
            expected = read(whole, 16000)
        }
        PersistentAudioChunkStore(history, overwriteOldest = false,
            atomicChunkReplace = { temporary, target ->
                assertEquals("1", target.name)
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                replacementVisible = true
            },
            directorySync = { directory ->
                if (failReplacementSync && replacementVisible && directory.name == BUFFER_CHUNKS_FOLDER_NAME) {
                    failReplacementSync = false
                    // Capture the exact failure boundary before close/retry can change it.
                    assertTrue(history.copyRecursively(crashImage))
                    snapshotCopied = true
                    throw IOException("Injected cutoff replacement sync failure")
                }
            },
        ).use { store ->
            configure(store, 257, 48000)
            assertEquals(source.size, store.append(source, 0, source.size))
            failReplacementSync = true
            assertThrows(IOException::class.java) {
                store.configure(RetentionMode.SIZE, 400 * 4L, 48000, 1, PcmSampleFormat.PCM_FLOAT)
            }
            assertTrue(snapshotCopied)
            assertEquals(400 * 4L, store.countFilledBytes())
            assertClose(expected, read(store, 16000))
        }
        PersistentAudioChunkStore(crashImage, overwriteOldest = false).use { recovered ->
            // A larger recovery limit cannot hide the bug by immediately re-trimming a surviving suffix.
            configure(recovered, 257, 48000)
            assertEquals(400 * 4L, recovered.countFilledBytes())
            assertClose(expected, read(recovered, 16000))
        }
    }

    @Test fun suffixRetirementFailureAndRetryMustProvePendingMarkersBeforeShorteningTheCutoff() = withRoot { root ->
        var failRetirementSync = false
        var replacementAttempts = 0
        val injected = IOException("Injected unresolved successor retirement barrier")
        val source = pcm(3 * 257, 1, PcmSampleFormat.PCM_FLOAT) { 0.25 }
        PersistentAudioChunkStore(root, overwriteOldest = false,
            atomicChunkReplace = { temporary, target ->
                replacementAttempts++
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            },
            directorySync = { directory ->
                if (failRetirementSync && directory.name == "retired" && File(directory, "2").isFile) throw injected
            },
        ).use { store ->
            configure(store, 257, 48000)
            assertEquals(source.size, store.append(source, 0, source.size))
            val cutoff = File(File(root, BUFFER_CHUNKS_FOLDER_NAME), "1")
            val originalCutoff = cutoff.readBytes()
            failRetirementSync = true
            assertThrows(IOException::class.java) {
                store.configure(RetentionMode.SIZE, 400 * 4L, 48000, 1, PcmSampleFormat.PCM_FLOAT)
            }
            assertEquals(2 * 257 * 4L, store.countFilledBytes())
            // The successor has left the live list, but its visible marker is still unproven.
            assertTrue(File(root, "retired/2").isFile)
            val retryFailure = assertThrows(IOException::class.java) {
                store.configure(RetentionMode.SIZE, 400 * 4L, 48000, 1, PcmSampleFormat.PCM_FLOAT)
            }
            assertSame(injected, retryFailure)
            assertEquals(0, replacementAttempts)
            assertArrayEquals(originalCutoff, cutoff.readBytes())
            failRetirementSync = false
            store.configure(RetentionMode.SIZE, 400 * 4L, 48000, 1, PcmSampleFormat.PCM_FLOAT)
            assertEquals(1, replacementAttempts)
            assertEquals(400 * 4L, store.countFilledBytes())
        }
        PersistentAudioChunkStore(root, overwriteOldest = false).use { recovered ->
            configure(recovered, 257, 48000)
            assertEquals(400 * 4L, recovered.countFilledBytes())
            val output = ByteBuffer.wrap(read(recovered, 16000)).order(ByteOrder.LITTLE_ENDIAN)
            while (output.hasRemaining()) assertEquals(0.25, output.float.toDouble(), 0.000002)
        }
    }
}
