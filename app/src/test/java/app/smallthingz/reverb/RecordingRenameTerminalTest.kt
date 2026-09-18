package app.smallthingz.reverb

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRenameTerminalTest {
    private fun recording() = RecordingEntity(
        id = "/recordings/original.wav",
        displayName = "original.wav",
        mimeType = "audio/wav",
        startedAtMillis = 1L,
        durationMillis = 2L,
        sizeBytes = 3L,
        codecSummary = "PCM",
        storageType = RecordingStorageType.FILE,
        directoryId = "/recordings",
        fileIdentity = "stat:test",
    )

    @Test
    fun committedRename_survivesOwnerCancellationAndDeliversTerminalSuccess() = runBlocking {
        val renameStarted = CompletableDeferred<Unit>()
        val releaseRename = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Result<RecordingEntity?>>()
        val renamed = recording().copy(id = "/recordings/renamed.wav", displayName = "renamed.wav")

        val owner = launch {
            runCommittedRecordingRename(
                rename = {
                    renameStarted.complete(Unit)
                    releaseRename.await()
                    renamed
                },
                onTerminal = { result -> terminal.complete(result) },
            )
        }

        renameStarted.await()
        owner.cancel()
        releaseRename.complete(Unit)
        owner.join()

        assertSame(renamed, terminal.await().getOrThrow())
    }

    @Test
    fun committedRename_survivesOwnerCancellationAndDeliversTerminalFailure() = runBlocking {
        val renameStarted = CompletableDeferred<Unit>()
        val releaseRename = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Result<RecordingEntity?>>()
        val expected = IOException("rename uncertain")

        val owner = launch {
            runCommittedRecordingRename(
                rename = {
                    renameStarted.complete(Unit)
                    releaseRename.await()
                    throw expected
                },
                onTerminal = { result -> terminal.complete(result) },
            )
        }

        renameStarted.await()
        owner.cancel()
        releaseRename.complete(Unit)
        owner.join()

        assertSame(expected, terminal.await().exceptionOrNull())
    }

    @Test
    fun renameUiGate_distinguishesVisibleHiddenAndDetachedTerminals() {
        var visible = true
        var renamed = false
        var rejected = false
        var uncertain = false
        var hidden = false
        val gate = RenameUiCallbackGate(
            onRenamed = { renamed = true },
            onRejected = { rejected = true },
            onUncertain = { uncertain = true },
            onHiddenTerminal = { hidden = true },
            isVisible = { visible },
        )

        assertTrue(gate.renamed(recording()))
        assertTrue(renamed)

        visible = false
        assertFalse(gate.rejected())
        assertFalse(rejected)
        assertTrue(hidden)

        hidden = false
        gate.detach()
        assertFalse(gate.uncertain())
        assertFalse(uncertain)
        assertFalse(hidden)
    }
    @Test
    fun detachedRenameReceiver_surfacesSuccessAndDeliversTerminalOnlyOnce() {
        val renamed = recording().copy(displayName = "renamed.wav")
        var success: RecordingEntity? = null
        var visibleDelivery = false
        var terminalCount = 0
        lateinit var receiver: RenameResultReceiver
        receiver = RenameResultReceiver(
            onRenamed = { visibleDelivery = true },
            onRejected = { error("unexpected rejection") },
            onUncertain = { error("unexpected uncertainty") },
            onHiddenTerminal = {},
            isUiVisible = { false },
            onDetachedSuccess = { success = it },
            onDetachedFailure = { error("unexpected failure") },
            onTerminal = { terminalCount++ },
        )

        receiver.terminal(Result.success(renamed))
        receiver.terminal(Result.success(renamed))

        assertFalse(visibleDelivery)
        assertSame(renamed, success)
        assertTrue(terminalCount == 1)
    }

    @Test
    fun detachedRenameReceiver_surfacesRejectedAndUncertainFailures() {
        var failures = 0
        fun receiver() = RenameResultReceiver(
            onRenamed = { error("unexpected rename") },
            onRejected = {},
            onUncertain = {},
            onHiddenTerminal = {},
            isUiVisible = { false },
            onDetachedSuccess = { error("unexpected success") },
            onDetachedFailure = { failures++ },
            onTerminal = {},
        )

        receiver().terminal(Result.success(null))
        receiver().terminal(Result.failure(IOException("uncertain")))

        assertTrue(failures == 2)
    }

    @Test
    fun renameRecoveryFailures_areSuppressedOnCatalogCommitFailure() {
        val primary = IOException("catalog commit failed")
        val rollbackFailure = IOException("physical rollback failed")
        val cleanupFailure = IOException("stale row cleanup failed")

        val rolledBack: String? = attemptRenameRecoveryPreservingPrimaryFailure(
            primaryFailure = primary,
            fallback = null,
        ) {
            throw rollbackFailure
        }
        attemptRenameRecoveryPreservingPrimaryFailure(
            primaryFailure = primary,
            fallback = Unit,
        ) {
            throw cleanupFailure
        }

        assertEquals(null, rolledBack)
        assertEquals(listOf(rollbackFailure, cleanupFailure), primary.suppressed.toList())
    }

    @Test
    fun throwingVisibleRenameCallback_runsFallbackThenPreservesPrimaryFailure() {
        val renamed = recording().copy(displayName = "renamed.wav")
        val primary = IllegalStateException("ui callback failed")
        val events = mutableListOf<String>()
        lateinit var receiver: RenameResultReceiver
        receiver = RenameResultReceiver(
            onRenamed = {
                events += "visible"
                throw primary
            },
            onRejected = { error("unexpected rejection") },
            onUncertain = { error("unexpected uncertainty") },
            onHiddenTerminal = {},
            isUiVisible = { true },
            onDetachedSuccess = { events += "fallback" },
            onDetachedFailure = { error("unexpected failure") },
            onTerminal = { events += "finish" },
        )

        var observed: Throwable? = null
        try {
            receiver.terminal(Result.success(renamed))
        } catch (error: Throwable) {
            observed = error
        }

        assertSame(primary, observed)
        assertEquals(listOf("visible", "fallback", "finish"), events)
    }

    @Test
    fun throwingRenameFallback_isSuppressedOnVisibleCallbackFailure() {
        val renamed = recording().copy(displayName = "renamed.wav")
        val primary = IllegalStateException("ui callback failed")
        val fallback = IllegalArgumentException("fallback failed")
        lateinit var receiver: RenameResultReceiver
        receiver = RenameResultReceiver(
            onRenamed = { throw primary },
            onRejected = { error("unexpected rejection") },
            onUncertain = { error("unexpected uncertainty") },
            onHiddenTerminal = {},
            isUiVisible = { true },
            onDetachedSuccess = { throw fallback },
            onDetachedFailure = { error("unexpected failure") },
            onTerminal = {},
        )

        var observed: Throwable? = null
        try {
            receiver.terminal(Result.success(renamed))
        } catch (error: Throwable) {
            observed = error
        }

        assertSame(primary, observed)
        assertEquals(listOf(fallback), primary.suppressed.toList())
    }

}
