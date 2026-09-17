package app.smallthingz.reverb

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineTrimTerminalTest {
    @Test
    fun committedTrim_survivesOwnerCancellationAndDeliversTerminalSuccess() = runBlocking {
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Result<RecordingEntity>>()
        val saved = RecordingEntity(
            id = "/recordings/trim.wav",
            displayName = "trim.wav",
            mimeType = "audio/wav",
            startedAtMillis = 1L,
            durationMillis = 2L,
            sizeBytes = 3L,
            codecSummary = "PCM",
            storageType = RecordingStorageType.FILE,
            directoryId = "/recordings",
            fileIdentity = "stat:test",
        )

        val owner = launch {
            runCommittedInlineTrim(
                save = {
                    saveStarted.complete(Unit)
                    releaseSave.await()
                    saved
                },
                onTerminal = { result -> terminal.complete(result) },
            )
        }

        saveStarted.await()
        owner.cancel()
        releaseSave.complete(Unit)
        owner.join()

        assertSame(saved, terminal.await().getOrThrow())
    }

    @Test
    fun committedTrim_survivesOwnerCancellationAndDeliversTerminalFailure() = runBlocking {
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Result<RecordingEntity>>()
        val expected = IOException("trim unavailable")

        val owner = launch {
            runCommittedInlineTrim(
                save = {
                    saveStarted.complete(Unit)
                    releaseSave.await()
                    throw expected
                },
                onTerminal = { result -> terminal.complete(result) },
            )
        }

        saveStarted.await()
        owner.cancel()
        releaseSave.complete(Unit)
        owner.join()

        assertSame(expected, terminal.await().exceptionOrNull())
    }

    @Test
    fun detachedTrimUiGate_rejectsTerminalUiDelivery() {
        var failed = false
        val gate = InlineTrimUiCallbackGate(
            onSaved = { error("unexpected save") },
            onFailed = { failed = true },
            visible = true,
        )
        val error = IOException("trim failed")

        assertTrue(gate.failed(error))
        assertTrue(failed)

        gate.setVisible(false)
        failed = false
        assertFalse(gate.failed(error))
        assertTrue(failed)

        gate.detach()
        failed = false
        assertFalse(gate.failed(error))
        assertFalse(failed)
    }
}
