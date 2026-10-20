package app.smallthingz.reverb

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SettingsCommitBarrierTest {
    private val baseline = SettingsSnapshot(
        themeMode = AppThemeMode.DARK,
        retentionMode = RetentionMode.TIME,
        oneShotRetentionTime = 300,
        oneShotRetentionSizeBytes = 1_000_001L,
        loopingRetentionTime = 600,
        loopingRetentionSizeBytes = 2_000_003L,
        format = ExportFormat.WAV,
        codec = ExportCodec.PCM_16,
        source = AudioSourceMode.MIC,
        channelMode = ChannelMode.MONO,
        route = InputRouteMode.AUTO,
        sampleRate = 48_000,
    )
    private val submitted = baseline.copy(themeMode = AppThemeMode.LIGHT, oneShotRetentionTime = 900)

    private fun restored(draft: SettingsUiDraft) = SettingsDraftRestoration.restore(
        SettingsDraftRestoration().save(true, true, true, baseline) { draft },
    )

    private suspend fun hydrate(
        barrier: SettingsCommitBarrier,
        restoration: SettingsDraftRestoration,
        read: suspend () -> SettingsSnapshot,
    ): SettingsDraftHydration {
        val durable = barrier.readSettled(read)
        return requireNotNull(restoration.hydrate(0L, 0L) { SettingsUiDraft.normalized(durable) })
    }

    private suspend fun replacementBeforeTerminal(
        draft: SettingsUiDraft,
        commit: Boolean = true,
        throwFailure: Boolean = false,
    ): SettingsDraftHydration = coroutineScope {
        val barrier = SettingsCommitBarrier()
        var durable = baseline
        val releaseSave = CompletableDeferred<Unit>()
        // This starts acceptance synchronously but does not yet enter the persistence lock.
        val save = async(start = CoroutineStart.UNDISPATCHED) {
            runDurableUiBooleanAttempt {
                barrier.runAcceptedSave {
                    releaseSave.await()
                    withRetentionPersistenceLock {
                        if (throwFailure) throw IOException("Settings transaction failed")
                        if (commit) durable = submitted
                        commit
                    }
                }
            }
        }
        var reads = 0
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            hydrate(barrier, restored(draft)) {
                withRetentionPersistenceLock {
                    reads++
                    durable
                }
            }
        }
        try {
            assertEquals("Replacement really read before predecessor acquired the lock", 1, reads)
            assertFalse("The old read cannot become an editable baseline", replacement.isCompleted)
        } finally {
            releaseSave.complete(Unit)
        }
        assertEquals(commit && !throwFailure, withTimeout(5_000L) { save.await() })
        val result = withTimeout(5_000L) { replacement.await() }
        assertEquals("Settlement requires a new durable baseline", 2, reads)
        result
    }

    @Test
    fun sameSubmittedDraftBecomesCleanAfterReplacementReadWinsPersistenceLock() = runBlocking {
        val draft = SettingsUiDraft.normalized(submitted)
        val result = replacementBeforeTerminal(draft)
        assertEquals(submitted, result.baseline)
        assertEquals(draft, result.displayed)
        assertFalse(result.hasUnsavedChanges)
        assertNull(result.themePreview)
    }

    @Test
    fun newerDraftRetainsExactTextsAndUsesCompletedSubmissionForUndo() = runBlocking {
        val newest = SettingsUiDraft.normalized(
            submitted.copy(themeMode = AppThemeMode.SYSTEM, sampleRate = 32_000,
                oneShotRetentionTime = 1_200, loopingRetentionSizeBytes = 9_007_199_254_740_993L),
        ).copy(oneShotTimeText = "20,00", loopingTimeText = "10:00",
            oneShotSizeText = "-", loopingSizeText = "unfinished")
        val result = replacementBeforeTerminal(newest)
        assertEquals(submitted, result.baseline)
        assertEquals(newest, result.displayed)
        assertTrue(result.hasUnsavedChanges)
        assertEquals(AppThemeMode.SYSTEM, result.themePreview)
        // Production Undo consumes this baseline, not the predecessor's pre-save values.
        assertEquals(900, result.baseline.oneShotRetentionTime)
        assertEquals(AppThemeMode.LIGHT, result.baseline.themeMode)
    }

    @Test
    fun newerReversionToPreSaveValuesStaysDirtyAgainstCompletedSubmission() = runBlocking {
        val newest = SettingsUiDraft.normalized(baseline).copy(loopingSizeText = "2,000")
        val result = replacementBeforeTerminal(newest)
        assertEquals(submitted, result.baseline)
        assertEquals(newest, result.displayed)
        assertTrue(result.hasUnsavedChanges)
        assertEquals(AppThemeMode.DARK, result.themePreview)
    }

    @Test
    fun failedSavePreservesOldBaselineAndSubmittedDraft() = runBlocking {
        val draft = SettingsUiDraft.normalized(submitted)
        val result = replacementBeforeTerminal(draft, commit = false)
        assertEquals(baseline, result.baseline)
        assertEquals(draft, result.displayed)
        assertTrue(result.hasUnsavedChanges)
    }

    @Test
    fun thrownTransactionFailureReleasesReplacementWithoutBlessingDraft() = runBlocking {
        val draft = SettingsUiDraft.normalized(submitted)
        val result = replacementBeforeTerminal(draft, throwFailure = true)
        assertEquals(baseline, result.baseline)
        assertEquals(draft, result.displayed)
        assertTrue(result.hasUnsavedChanges)
    }

    @Test
    fun saveAcceptedAndFinishedDuringEarlierReadInvalidatesThatRead() = runBlocking {
        val barrier = SettingsCommitBarrier()
        var durable = baseline
        var reads = 0
        val deliverRead = CompletableDeferred<Unit>()
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            hydrate(barrier, restored(SettingsUiDraft.normalized(submitted))) {
                val sampled = withRetentionPersistenceLock { durable }
                if (++reads == 1) deliverRead.await()
                sampled
            }
        }
        try {
            barrier.runAcceptedSave { withRetentionPersistenceLock { durable = submitted } }
        } finally {
            deliverRead.complete(Unit)
        }
        val result = withTimeout(5_000L) { replacement.await() }
        assertEquals(2, reads)
        assertEquals(submitted, result.baseline)
        assertFalse(result.hasUnsavedChanges)
    }

    @Test
    fun cancellingPredecessorCannotRetireAcceptanceBeforeRuntimeConvergence() = runBlocking {
        val barrier = SettingsCommitBarrier()
        val finishRuntimeApply = CompletableDeferred<Unit>()
        var durable = baseline
        var runtimeApplied = false
        val save = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.runAcceptedSave {
                withRetentionPersistenceLock { durable = submitted }
                finishRuntimeApply.await()
                runtimeApplied = true
            }
        }
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            hydrate(barrier, restored(SettingsUiDraft.normalized(submitted))) {
                withRetentionPersistenceLock { durable }
            }
        }
        try {
            save.cancel()
            assertFalse(save.isCompleted)
            assertFalse(replacement.isCompleted)
        } finally {
            finishRuntimeApply.complete(Unit)
        }
        withTimeout(5_000L) { save.join() }
        val result = withTimeout(5_000L) { replacement.await() }
        assertTrue(runtimeApplied)
        assertEquals(submitted, result.baseline)
        assertFalse(result.hasUnsavedChanges)
    }

    @Test
    fun repeatedReplacementCancelsOnlyHydrationAndKeepsPendingExactDraft() = runBlocking {
        val barrier = SettingsCommitBarrier()
        val releaseSave = CompletableDeferred<Unit>()
        var durable = baseline
        val newest = SettingsUiDraft.normalized(submitted.copy(sampleRate = 32_000))
            .copy(oneShotTimeText = "15,00", loopingSizeText = "-")
        var restoration = restored(newest)
        val save = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.runAcceptedSave {
                releaseSave.await()
                withRetentionPersistenceLock { durable = submitted }
            }
        }
        try {
            repeat(3) {
                val retired = async(start = CoroutineStart.UNDISPATCHED) {
                    hydrate(barrier, restoration) { withRetentionPersistenceLock { durable } }
                }
                assertFalse(retired.isCompleted)
                retired.cancelAndJoin()
                restoration = SettingsDraftRestoration.restore(
                    restoration.save(true, false, false, SettingsSnapshot()) {
                        error("Unhydrated placeholders cannot replace the pending draft")
                    },
                )
            }
        } finally {
            releaseSave.complete(Unit)
        }
        withTimeout(5_000L) { save.join() }
        val result = hydrate(barrier, restoration) { withRetentionPersistenceLock { durable } }
        assertEquals(submitted, result.baseline)
        assertEquals(newest, result.displayed)
        assertTrue(result.hasUnsavedChanges)
    }

    @Test
    fun overlappingAcceptedSavesKeepHydrationPendingUntilLastTerminal() = runBlocking {
        val barrier = SettingsCommitBarrier()
        val finishFirst = CompletableDeferred<Unit>()
        val finishSecond = CompletableDeferred<Unit>()
        var durable = baseline
        val latest = submitted.copy(sampleRate = 32_000)
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.runAcceptedSave {
                finishFirst.await()
                withRetentionPersistenceLock { durable = submitted }
            }
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.runAcceptedSave {
                finishSecond.await()
                withRetentionPersistenceLock { durable = latest }
            }
        }
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            hydrate(barrier, restored(SettingsUiDraft.normalized(latest))) {
                withRetentionPersistenceLock { durable }
            }
        }
        try {
            finishFirst.complete(Unit)
            withTimeout(5_000L) { first.join() }
            assertFalse(replacement.isCompleted)
        } finally {
            finishFirst.complete(Unit)
            finishSecond.complete(Unit)
        }
        withTimeout(5_000L) { second.join() }
        val result = withTimeout(5_000L) { replacement.await() }
        assertEquals(latest, result.baseline)
        assertFalse(result.hasUnsavedChanges)
    }

    @Test
    fun settledReadFailureRemainsObservableAndDoesNotConsumePendingDraft() = runBlocking {
        val barrier = SettingsCommitBarrier()
        val draft = SettingsUiDraft.normalized(submitted)
        val restoration = restored(draft)
        val failure = IOException("Durable read unavailable")
        try {
            hydrate(barrier, restoration) { throw failure }
            fail("Expected the current durable-read failure")
        } catch (error: IOException) {
            assertSame(failure, error)
        }
        var reads = 0
        val result = hydrate(barrier, restoration) { reads++; baseline }
        assertEquals(1, reads)
        assertEquals(baseline, result.baseline)
        assertEquals(draft, result.displayed)
    }

    @Test
    fun obsoleteReadFailureIsRetriedAfterCrossingAcceptedSave() = runBlocking {
        val barrier = SettingsCommitBarrier()
        var durable = baseline
        var reads = 0
        val deliverFailure = CompletableDeferred<Unit>()
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            hydrate(barrier, restored(SettingsUiDraft.normalized(submitted))) {
                if (++reads == 1) {
                    deliverFailure.await()
                    throw IOException("Superseded read failed")
                }
                withRetentionPersistenceLock { durable }
            }
        }
        try {
            barrier.runAcceptedSave { withRetentionPersistenceLock { durable = submitted } }
        } finally {
            deliverFailure.complete(Unit)
        }
        val result = withTimeout(5_000L) { replacement.await() }
        assertEquals(2, reads)
        assertEquals(submitted, result.baseline)
        assertFalse(result.hasUnsavedChanges)
    }

    @Test
    fun editRevisionStillRejectsSettledReadWithoutConsumingRestoration() = runBlocking {
        val barrier = SettingsCommitBarrier()
        val draft = SettingsUiDraft.normalized(submitted)
        val restoration = restored(draft)
        val durable = barrier.readSettled { baseline }
        assertNull(restoration.hydrate(0L, 1L) {
            error("Superseded read cannot mutate UI")
        })
        val result = requireNotNull(restoration.hydrate(1L, 1L) {
            SettingsUiDraft.normalized(durable)
        })
        assertEquals(draft, result.displayed)
        assertEquals(baseline, result.baseline)
    }
}
