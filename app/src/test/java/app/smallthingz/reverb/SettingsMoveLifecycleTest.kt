package app.smallthingz.reverb

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMoveLifecycleTest {
    @Test
    fun committedMove_survivesOwnerCancellationAndDeliversTerminalResult() = runBlocking {
        val persistenceStarted = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val moveStarted = CompletableDeferred<Unit>()
        val releaseMove = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Result<Int>>()

        val owner = launch {
            runCommittedSettingsMove(
                persistSettings = {
                    persistenceStarted.complete(Unit)
                    releasePersistence.await()
                    true
                },
                move = {
                    moveStarted.complete(Unit)
                    releaseMove.await()
                    7
                },
                onTerminal = { result -> terminal.complete(result) },
            )
        }

        persistenceStarted.await()
        owner.cancel()
        releasePersistence.complete(Unit)
        moveStarted.await()
        releaseMove.complete(Unit)
        owner.join()

        assertEquals(7, terminal.await().getOrThrow())
    }

    @Test
    fun committedMove_doesNotStartWhenSettingsCommitIsRejected() = runBlocking {
        var moved = false
        var terminalDelivered = false
        val started = runCommittedSettingsMove(
            persistSettings = { false },
            move = { moved = true },
            onTerminal = { terminalDelivered = true },
        )

        assertFalse(started)
        assertFalse(moved)
        assertFalse(terminalDelivered)
    }

    @Test
    fun committedMove_routesOperationFailureToTerminalResult() = runBlocking {
        var terminalFailure: Throwable? = null
        val started = runCommittedSettingsMove<Unit>(
            persistSettings = { true },
            move = { throw IOException("move unavailable") },
            onTerminal = { result -> terminalFailure = result.exceptionOrNull() },
        )

        assertTrue(started)
        assertTrue(terminalFailure is IOException)
    }
}
