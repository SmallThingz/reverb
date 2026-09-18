package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferClearProgressTest {
    @Test
    fun progressPrefersBytesAndClampsRemaining() {
        val running = BufferClearStatus(
            operationId = 1L,
            bufferSlot = ReverbService.BufferSlot.LOOPING,
            phase = BufferClearPhase.RUNNING,
            totalBytes = 1_000L,
            remainingBytes = 250L,
            totalChunks = 10,
            remainingChunks = 3,
        )
        assertEquals(0.75f, requireNotNull(bufferClearProgressFraction(running)), 0.0001f)
        assertEquals(
            0f,
            requireNotNull(bufferClearProgressFraction(running.copy(remainingBytes = 2_000L))),
            0f,
        )
    }

    @Test
    fun progressFallsBackToChunksAndHandlesStartup() {
        val chunkProgress = BufferClearStatus(
            operationId = 2L,
            bufferSlot = ReverbService.BufferSlot.ONE_SHOT,
            phase = BufferClearPhase.RUNNING,
            totalChunks = 4,
            remainingChunks = 1,
        )
        assertEquals(0.75f, requireNotNull(bufferClearProgressFraction(chunkProgress)), 0.0001f)
        assertNull(
            bufferClearProgressFraction(
                chunkProgress.copy(
                    phase = BufferClearPhase.STARTING,
                    totalChunks = 0,
                    remainingChunks = 0,
                ),
            ),
        )
        assertEquals(
            1f,
            requireNotNull(bufferClearProgressFraction(chunkProgress.copy(phase = BufferClearPhase.COMPLETED))),
            0f,
        )
    }

    @Test
    fun clearContinuationRequiresSameCaptureGenerationAndInactiveTarget() {
        assertTrue(
            bufferClearCanContinue(
                cancelRequested = false,
                serviceDestroying = false,
                requestedBuffer = ReverbService.BufferSlot.LOOPING,
                activeBuffer = ReverbService.BufferSlot.ONE_SHOT,
                listeningIntentEnabled = true,
                acceptedGeneration = 8L,
                currentGeneration = 8L,
            ),
        )
        assertFalse(
            bufferClearCanContinue(
                cancelRequested = true,
                serviceDestroying = false,
                requestedBuffer = ReverbService.BufferSlot.LOOPING,
                activeBuffer = ReverbService.BufferSlot.ONE_SHOT,
                listeningIntentEnabled = true,
                acceptedGeneration = 8L,
                currentGeneration = 8L,
            ),
        )
        assertFalse(
            bufferClearCanContinue(
                cancelRequested = false,
                serviceDestroying = false,
                requestedBuffer = ReverbService.BufferSlot.LOOPING,
                activeBuffer = ReverbService.BufferSlot.LOOPING,
                listeningIntentEnabled = true,
                acceptedGeneration = 8L,
                currentGeneration = 8L,
            ),
        )
        assertFalse(
            bufferClearCanContinue(
                cancelRequested = false,
                serviceDestroying = false,
                requestedBuffer = ReverbService.BufferSlot.LOOPING,
                activeBuffer = ReverbService.BufferSlot.ONE_SHOT,
                listeningIntentEnabled = true,
                acceptedGeneration = 8L,
                currentGeneration = 9L,
            ),
        )
    }
    @Test
    fun platformCancellation_isFailureWhileUserCancellationIsNeutral() {
        assertEquals(BufferClearPhase.CANCELLED, bufferClearCancellationTerminal(reportFailure = false))
        assertEquals(BufferClearPhase.FAILED, bufferClearCancellationTerminal(reportFailure = true))
    }

    @Test
    fun teardownFailsOnlyAnOtherwiseLiveClear() {
        assertTrue(
            bufferClearTeardownBeginsFailure(
                cancelRequested = false,
                failureAlreadyRequested = false,
            ),
        )
        assertFalse(
            bufferClearTeardownBeginsFailure(
                cancelRequested = true,
                failureAlreadyRequested = false,
            ),
        )
        assertFalse(
            bufferClearTeardownBeginsFailure(
                cancelRequested = true,
                failureAlreadyRequested = true,
            ),
        )
    }

}
