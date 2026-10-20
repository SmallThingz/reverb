package app.smallthingz.reverb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.ArrayDeque
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeRetentionSettlementTest {
    @Test
    fun reportedOneShotBatchRoundingSettlesAndRetainsEveryBoundaryFrame() = withStore(looping = false) { store, _, _ ->
        val oldest = pcm(8_000, 17)
        val newest = pcm(48_800, 53)
        appendIn64FrameBatches(store, oldest)
        store.sealActiveChunk()
        appendIn64FrameBatches(store, newest)
        store.sealActiveChunk()
        assertEquals(2, requireNotNull(store.peekSnapshot()).chunkCount)

        val owner = MaintenanceOwner(store)
        owner.configure(RetentionMode.TIME, 1L)
        owner.drainImmediate()

        assertEquals(1, owner.passes)
        assertEquals(16_000L, store.countFilledBytes())
        assertArrayEquals(oldest, readPcm(store))
        owner.assertIdle()
    }

    @Test
    fun loopingBatchRoundingSettlesAndRetainsEveryNewestBoundaryFrame() = withStore(looping = true) { store, _, _ ->
        val oldest = pcm(48_800, 53)
        val newest = pcm(8_000, 17)
        appendIn64FrameBatches(store, oldest)
        store.sealActiveChunk()
        appendIn64FrameBatches(store, newest)
        store.sealActiveChunk()
        assertEquals(2, requireNotNull(store.peekSnapshot()).chunkCount)

        val owner = MaintenanceOwner(store)
        owner.configure(RetentionMode.TIME, 1L)
        owner.drainImmediate()

        assertEquals(1, owner.passes)
        assertEquals(16_000L, store.countFilledBytes())
        assertArrayEquals(newest, readPcm(store))
        owner.assertIdle()
    }

    @Test
    fun aRealFrameExcessStillTrimsForBothModesAndBufferDirections() {
        for (looping in listOf(false, true)) {
            for (mode in listOf(RetentionMode.SIZE, RetentionMode.TIME)) {
                withStore(looping) { store, _, _ ->
                    val original = pcm(8_001, 31)
                    appendIn64FrameBatches(store, original)
                    store.sealActiveChunk()
                    val owner = MaintenanceOwner(store)
                    owner.configure(mode, if (mode == RetentionMode.SIZE) 16_000L else 1L)
                    owner.drainImmediate()

                    assertEquals(1, owner.passes)
                    assertEquals(16_000L, store.countFilledBytes())
                    val expected = if (looping) original.copyOfRange(2, original.size) else original.copyOf(16_000)
                    assertArrayEquals(expected, readPcm(store))
                    owner.assertIdle()
                }
            }
        }
    }

    @Test
    fun realLeasedBoundaryKeepsItsRetryUntilReleaseForBothModesAndDirections() {
        for (looping in listOf(false, true)) {
            for (mode in listOf(RetentionMode.SIZE, RetentionMode.TIME)) {
                withStore(looping) { store, _, _ ->
                    val original = pcm(8_001, 31)
                    appendIn64FrameBatches(store, original)
                    store.sealActiveChunk()
                    val lease = requireNotNull(store.acquireRange(0.0, store.durationSeconds()))
                    val owner = MaintenanceOwner(store)
                    try {
                        owner.configure(mode, if (mode == RetentionMode.SIZE) 16_000L else 1L)
                        owner.drainImmediate()
                        assertEquals(1, owner.passes)
                        assertTrue(owner.state.isActive())
                        assertTrue(owner.foreground)
                        assertEquals(1, owner.delayed.size)
                        assertTrue(owner.failures.isEmpty())
                        assertEquals(original.size.toLong(), store.countFilledBytes())
                        assertArrayEquals(original, readPcm(lease))
                    } finally {
                        lease.close()
                    }
                    owner.delayed.removeFirst().run()
                    owner.drainImmediate()
                    val expected = if (looping) original.copyOfRange(2, original.size) else original.copyOf(16_000)
                    assertArrayEquals(expected, readPcm(store))
                    owner.assertIdle()
                }
            }
        }
    }

    @Test
    fun mixedRatesAndReconfigurationKeepExactFramesAndStillRemoveSubBoundaryFrameExcess() {
        for (looping in listOf(false, true)) {
            for (extraFrame in listOf(false, true)) {
                withStore(looping) { store, root, _ ->
                    val first = pcm(4_000, 11) // 0.5 s at 8 kHz.
                    appendIn64FrameBatches(store, first)
                    // A format change seals the first span without clearing its PCM or duration.
                    store.configure(RetentionMode.TIME, 120L, 16_000, 1, deferRetentionCleanup = true)
                    val second = pcm(if (extraFrame) 8_001 else 8_000, 97) // 0.5 s, optionally plus 1/16000 s.
                    appendIn64FrameBatches(store, second)
                    store.sealActiveChunk()
                    val owner = MaintenanceOwner(store)
                    owner.configure(RetentionMode.TIME, 1L, sampleRate = 16_000)
                    owner.drainImmediate()

                    assertEquals(if (extraFrame) 1 else 0, owner.passes)
                    // Looping must drop one whole oldest 8-kHz frame even though the excess
                    // is only half of that frame; one-shot instead drops the newest 16-kHz frame.
                    val expectedFirst = if (looping && extraFrame) first.copyOfRange(2, first.size) else first
                    val expectedSecond = if (!looping && extraFrame) second.copyOf(second.size - 2) else second
                    assertArrayEquals(expectedFirst, chunkPayload(File(root, "chunks/0")))
                    assertArrayEquals(expectedSecond, chunkPayload(File(root, "chunks/1")))
                    assertEquals((expectedFirst.size + expectedSecond.size).toLong(), store.countFilledBytes())
                    owner.assertIdle()
                }
            }
        }
    }

    @Test
    fun realTimeBoundaryWriteFailureStillLatchesAndExplicitConfigurationRearms() {
        for (looping in listOf(false, true)) {
            withStore(looping) { store, _, fault ->
                val original = pcm(8_001, 31)
                appendIn64FrameBatches(store, original)
                store.sealActiveChunk()
                val owner = MaintenanceOwner(store)
                fault.enabled = true
                owner.configure(RetentionMode.TIME, 1L)
                owner.drainImmediate()

                assertEquals(1, fault.attempts)
                assertEquals(1, owner.failures.size)
                assertEquals("Controlled TIME boundary replacement failure", owner.failures.single().message)
                assertFalse(owner.state.isActive())
                assertFalse(owner.foreground)
                assertTrue(owner.delayed.isEmpty())
                assertArrayEquals(original, readPcm(store))
                repeat(3) { owner.schedule(); owner.retry() }
                owner.drainImmediate()
                assertEquals(1, fault.attempts)

                fault.enabled = false
                owner.configure(RetentionMode.TIME, 1L)
                owner.drainImmediate()
                assertEquals(2, fault.attempts)
                val expected = if (looping) original.copyOfRange(2, original.size) else original.copyOf(16_000)
                assertArrayEquals(expected, readPcm(store))
                owner.assertIdle(expectedFailures = 1)
            }
        }
    }

    /** Real stores composed with production scheduler admission, failure and lifetime settlement. */
    private class MaintenanceOwner(private val store: PersistentAudioChunkStore) {
        val state = RetentionMaintenanceSchedulerState()
        private val main = ArrayDeque<Runnable>()
        private val worker = ArrayDeque<Runnable>()
        val delayed = ArrayDeque<Runnable>()
        val failures = ArrayList<Exception>()
        var foreground = false
        var passes = 0

        fun configure(mode: RetentionMode, limit: Long, sampleRate: Int = 8_000) {
            store.configure(mode, limit, sampleRate, 1, deferRetentionCleanup = true)
            state.rearmAfterConfiguration()
            schedule()
        }

        fun schedule() {
            observeRetentionMaintenancePass(state, store::retentionMaintenanceNeeded, ::fail)?.let(::admit)
        }

        fun retry() { state.claimActiveRetry()?.let(::admit) }

        private fun admit(pass: Long) {
            admitRetentionPassOnMain(state, pass, { main.add(it) }, { true }, {
                foreground = true
                true
            }, { id -> worker.add(Runnable { runPass(id) }) }, { main.add(Runnable { settle() }) })
        }

        private fun runPass(pass: Long) {
            passes++
            val step = try {
                store.performRetentionMaintenanceStep()
            } catch (error: Exception) {
                settleFailedRetentionPass(state, pass, { fail(error) }, ::retry, { settle() })
                return
            }
            if (!state.completePass(pass, step.needsMore)) return
            if (step.needsMore && state.isActive()) {
                if (step.blocked) delayed.add(Runnable { retry() }) else retry()
                return
            }
            schedule()
            if (!state.isActive()) main.add(Runnable { settle() })
        }

        private fun fail(error: Exception) {
            failures += error
            store.sealActiveChunk()
            schedule() // Failure cleanup must not rearm the failed scheduler generation.
            main.add(Runnable { settle() })
        }

        private fun settle() = settleIdleServiceLifetime(
            state, { foreground = true; true }, { false }, { foreground = false }, {},
        )

        fun drainImmediate() {
            repeat(32) {
                main.pollFirst()?.run()
                worker.pollFirst()?.run()
                if (main.isEmpty() && worker.isEmpty()) return
            }
            throw AssertionError("Retention maintenance did not settle in 32 immediate dispatch rounds")
        }

        fun assertIdle(expectedFailures: Int = 0) {
            assertEquals(expectedFailures, failures.size)
            assertFalse("Store still advertises retention work", store.retentionMaintenanceNeeded())
            assertFalse(state.isActive())
            assertFalse("Retention retained its foreground lifetime", foreground)
            assertTrue("No lease exists to release this delayed retry", delayed.isEmpty())
            assertNull(state.claimActiveRetry())
        }
    }

    private class ReplacementFault(var enabled: Boolean = false, var attempts: Int = 0)

    private fun withStore(
        looping: Boolean,
        block: (PersistentAudioChunkStore, File, ReplacementFault) -> Unit,
    ) {
        val parent = File("build/tmp/time-retention-settlement-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "store-").toFile()
        val fault = ReplacementFault()
        try {
            PersistentAudioChunkStore(root, overwriteOldest = looping, atomicChunkReplace = { source, target ->
                fault.attempts++
                if (fault.enabled) throw IOException("Controlled TIME boundary replacement failure")
                replaceAudioChunkAtomically(source, target)
            }).use { store ->
                store.configure(RetentionMode.TIME, 120L, 8_000, 1, deferRetentionCleanup = true)
                block(store, root, fault)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun pcm(frames: Int, seed: Int): ByteArray = ByteArray(frames * 2) { (it * 29 + seed).toByte() }

    private fun appendIn64FrameBatches(store: PersistentAudioChunkStore, pcm: ByteArray) {
        var offset = 0
        while (offset < pcm.size) {
            val count = minOf(128, pcm.size - offset)
            assertEquals(count, store.append(pcm, offset, count))
            offset += count
        }
    }

    private fun chunkPayload(file: File): ByteArray {
        val bytes = file.readBytes()
        val payloadOffset = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
        return bytes.copyOfRange(payloadOffset, bytes.size)
    }

    private fun readPcm(store: PersistentAudioChunkStore): ByteArray =
        requireNotNull(store.acquireRange(0.0, store.durationSeconds())).use(::readPcm)

    private fun readPcm(lease: PersistentAudioChunkStore.RangeLease): ByteArray {
        val output = ByteArrayOutputStream()
        lease.readNormalized(8_000, 1, PcmSampleFormat.PCM_16) { bytes, offset, count ->
            output.write(bytes, offset, count)
            count
        }
        return output.toByteArray()
    }
}
