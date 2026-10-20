package app.smallthingz.reverb

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalStoreCleanupTest {
    @Test
    fun successfulShutdownRunsEveryStageWithoutCreatingFeedback() {
        val calls = mutableListOf<String>()
        runTerminalStoreCleanup(
            seal = { calls += "seal" },
            releaseInput = { calls += "release" },
            closeLoopingStore = { calls += "looping" },
            closeOneShotStore = { calls += "one-shot" },
            onFailure = { throw AssertionError("Normal teardown is not a failure", it) },
        )
        assertEquals(listOf("seal", "release", "looping", "one-shot"), calls)
    }

    @Test
    fun failedSealKeepsPrimaryAndClosesBothStoresBeforeOneReport() {
        val sealFailure = IOException("Seal failed")
        val loopingFailure = IOException("Looping checkpoint failed")
        val oneShotFailure = IOException("One-shot close failed")
        val calls = mutableListOf<String>()
        val reports = mutableListOf<Throwable>()
        runTerminalStoreCleanup(
            seal = { calls += "seal"; throw sealFailure },
            releaseInput = { calls += "release" },
            closeLoopingStore = { calls += "looping"; throw loopingFailure },
            closeOneShotStore = { calls += "one-shot"; throw oneShotFailure },
            onFailure = { calls += "report"; reports += it },
        )
        assertEquals(listOf("seal", "release", "looping", "one-shot", "report"), calls)
        assertEquals(1, reports.size)
        assertSame(sealFailure, reports.single())
        assertEquals(listOf(loopingFailure, oneShotFailure), sealFailure.suppressed.toList())
    }

    @Test
    fun unexpectedInputReleaseFailureStillAttemptsBothStoreCloses() {
        val releaseFailure = IllegalStateException("Input release failed")
        val calls = mutableListOf<String>()
        var reported: Throwable? = null
        runTerminalStoreCleanup(
            releaseInput = { throw releaseFailure },
            closeLoopingStore = { calls += "looping" },
            closeOneShotStore = { calls += "one-shot" },
            onFailure = { reported = it },
        )
        assertEquals(listOf("looping", "one-shot"), calls)
        assertSame(releaseFailure, reported)
    }

    @Test
    fun repeatedTerminalCauseCannotSuppressItselfOrDuplicateTheReport() {
        val failure = IOException("Store already has a terminal failure")
        val reports = mutableListOf<Throwable>()
        runTerminalStoreCleanup(
            seal = { throw failure },
            closeLoopingStore = { throw failure },
            closeOneShotStore = { throw failure },
            onFailure = { reports += it },
        )
        assertEquals(listOf(failure), reports)
        assertTrue(failure.suppressed.isEmpty())
    }

    @Test
    fun timeoutSealReportsThroughProcessChannelWithoutDuplicatingLiveUiPoll() {
        val errors = RecorderErrorState()
        val process = mutableListOf<String>()
        val failure = IOException("Timeout seal failed")
        var released = false
        errors.report("Unrelated state-read error")
        runTerminalStoreCleanup(
            seal = { throw failure },
            releaseInput = { released = true },
            onFailure = { error ->
                errors.reportTerminalCaptureFailure(requireNotNull(error.message), process::add)
            },
        )
        assertTrue(released)
        assertEquals(listOf("Timeout seal failed"), process)
        assertEquals("Unrelated state-read error", errors.consumePending())
        assertNull(errors.consumePending())
    }

    @Test
    fun queuedShutdownCloseFailureSurvivesOldServiceAndLateFeedbackHost() {
        val oldServiceErrors = RecorderErrorState()
        val audio = ArrayDeque<Runnable>()
        val process = mutableListOf<String>()
        val closeFailure = IOException("Final checkpoint failed")
        var oneShotClosed = false
        // The queued terminal task can finish after onDestroy returns and the UI has unbound.
        audio.add(Runnable {
            runTerminalStoreCleanup(
                closeLoopingStore = { throw closeFailure },
                closeOneShotStore = { oneShotClosed = true },
                onFailure = { error ->
                    oldServiceErrors.reportTerminalCaptureFailure(requireNotNull(error.message), process::add)
                },
            )
        })
        val replacementServiceErrors = RecorderErrorState()
        audio.removeFirst().run()
        assertTrue(oneShotClosed)
        assertEquals(listOf("Final checkpoint failed"), process)
        assertNull(oldServiceErrors.consumePending())
        assertNull(replacementServiceErrors.consumePending())
        // A later host consumes the process-owned report, independently of either Service slot.
        assertEquals("Final checkpoint failed", process.removeAt(0))
        assertTrue(process.isEmpty())
    }

    @Test
    fun actualStoreCheckpointFailureReachesTerminalFeedbackAndDoesNotSkipOtherStore() {
        val parent = File("build/tmp/terminal-store-cleanup-tests").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "store-").toFile()
        val store = PersistentAudioChunkStore(root)
        val errors = RecorderErrorState()
        val process = mutableListOf<String>()
        var otherClosed = false
        try {
            store.configure(RetentionMode.SIZE, 65_536L, 8_000, 1)
            val pcm = ByteArray(4_096) { (it * 13).toByte() }
            assertEquals(pcm.size, store.append(pcm, 0, pcm.size))
            store.sealActiveChunk()
            // The real close checkpoint cannot publish either index revision.
            for (name in listOf(BUFFER_INDEX_A_FILE_NAME, BUFFER_INDEX_B_FILE_NAME)) {
                val staging = File(root, "$name.tmp")
                staging.deleteRecursively()
                assertTrue(staging.mkdir())
            }
            runTerminalStoreCleanup(
                closeLoopingStore = store::close,
                closeOneShotStore = { otherClosed = true },
                onFailure = { error ->
                    assertTrue(error is IOException)
                    errors.reportTerminalCaptureFailure("Final buffer checkpoint failed", process::add)
                },
            )
            assertTrue(otherClosed)
            assertEquals(listOf("Final buffer checkpoint failed"), process)
            assertNull(errors.consumePending())
        } finally {
            runCatching { store.close() }
            root.deleteRecursively()
        }
    }
}
