package app.smallthingz.reverb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CommittedThemeStateTest {
    @Test
    fun replacementSamplingAttemptedThemeDuringFailedFirstSaveUsesPreWriteBaseline() {
        assertFailedFirstSaveKeepsBaseline(newerPreview = null)
    }

    @Test
    fun failedFirstSaveBaselineCannotDisplaceReplacementNewerPreview() {
        assertFailedFirstSaveKeepsBaseline(newerPreview = AppThemeMode.SYSTEM)
    }

    private fun assertFailedFirstSaveKeepsBaseline(newerPreview: AppThemeMode?) {
        val commits = CommittedThemeState()
        val durableMode = AppThemeMode.DARK
        var preferenceMode = durableMode
        var replacement: ThemePresentationOwner? = null
        assertNull(commits.state.value)

        val committed = withRetentionPersistenceLock {
            // Production captures this before persistRetentionTransaction can commit the editor.
            commits.initializeBaseline(preferenceMode)
            val baseline = commits.state.value
            val result = persistRetentionTransaction(
                writeNewRecovery = { true },
                commitNewPreferences = {
                    // Model SharedPreferences exposing its attempted map before commit returns false.
                    preferenceMode = AppThemeMode.LIGHT
                    val owner = ThemePresentationOwner(preferenceMode)
                    replacement = owner
                    if (newerPreview != null) owner.preview(newerPreview, commits.state.value)
                    owner.observeCommitted(commits.state.value)
                    assertEquals(newerPreview ?: durableMode, owner.mode)
                    false
                },
                restoreRecovery = { true },
                restorePreferences = {
                    preferenceMode = durableMode
                    true
                },
            )
            if (result) commits.publishCommitted(AppThemeMode.LIGHT)
            assertEquals(baseline, commits.state.value)
            result
        }

        assertFalse(committed)
        val hydratedMode = withRetentionPersistenceLock { preferenceMode }
        assertEquals(durableMode, hydratedMode)
        val owner = requireNotNull(replacement)
        // A clean hydration releases to the baseline; a newer draft owns its preview instead.
        if (newerPreview == null) owner.preview(null, commits.state.value)
        assertEquals(newerPreview ?: hydratedMode, owner.mode)
        assertEquals(durableMode, commits.state.value!!.mode)
        assertEquals(1L, commits.state.value!!.revision)
    }

    @Test
    fun existingSuccessfulPublicationWinsOverBaselineInitializationAndQueuedBaseline() {
        val commits = CommittedThemeState()
        commits.initializeBaseline(AppThemeMode.DARK)
        val baseline = commits.state.value!!
        commits.publishCommitted(AppThemeMode.LIGHT)
        val accepted = commits.state.value!!
        assertTrue(accepted.revision > baseline.revision)

        // Later transactions cannot replace the established process state with another read.
        commits.initializeBaseline(AppThemeMode.SYSTEM)
        assertEquals(accepted, commits.state.value)
        val owner = ThemePresentationOwner(AppThemeMode.SYSTEM)
        owner.observeCommitted(accepted)
        owner.observeCommitted(baseline)
        assertEquals(AppThemeMode.LIGHT, owner.mode)
        owner.preview(AppThemeMode.DARK, commits.state.value)
        owner.observeCommitted(baseline)
        assertEquals(AppThemeMode.DARK, owner.mode)
        owner.preview(null, commits.state.value)
        assertEquals(AppThemeMode.LIGHT, owner.mode)
    }

    @Test
    fun closedOwnerRejectsDelayedFirstBaseline() {
        val commits = CommittedThemeState()
        val retired = ThemePresentationOwner(AppThemeMode.LIGHT)
        retired.close()
        commits.initializeBaseline(AppThemeMode.DARK)
        retired.observeCommitted(commits.state.value)
        retired.preview(null, commits.state.value)
        assertEquals(AppThemeMode.LIGHT, retired.mode)

        val replacement = ThemePresentationOwner(AppThemeMode.LIGHT)
        replacement.observeCommitted(commits.state.value)
        assertEquals(AppThemeMode.DARK, replacement.mode)
    }

    @Test
    fun replacementReadsOldThemeBeforeAcceptedCommitThenObservesDurableTheme() = runBlocking {
        val commits = CommittedThemeState()
        val oldOwner = ThemePresentationOwner(AppThemeMode.DARK)
        oldOwner.preview(AppThemeMode.LIGHT, commits.state.value)
        val accepted = CompletableDeferred<Unit>()
        val finishTransaction = CompletableDeferred<Unit>()
        val save = launch(start = CoroutineStart.UNDISPATCHED) {
            commits.initializeBaseline(AppThemeMode.DARK)
            accepted.complete(Unit)
            finishTransaction.await()
            // Production publishes the submitted theme only after the whole transaction succeeds,
            // before releasing the retention lock. No Activity callback publishes.
            commits.publishCommitted(AppThemeMode.LIGHT)
        }
        accepted.await()
        oldOwner.close()

        // Replacement onCreate still samples the previous durable preference.
        val replacement = ThemePresentationOwner(AppThemeMode.DARK)
        replacement.observeCommitted(commits.state.value)
        assertEquals(AppThemeMode.DARK, replacement.mode)
        val delivered = CompletableDeferred<Unit>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            commits.state.collect {
                replacement.observeCommitted(commits.state.value)
                if (replacement.mode == AppThemeMode.LIGHT) delivered.complete(Unit)
            }
        }
        finishTransaction.complete(Unit)
        save.join()
        withTimeout(5_000L) { delivered.await() }
        assertEquals(AppThemeMode.LIGHT, replacement.mode)

        // A predecessor UI continuation can neither publish nor repaint this owner.
        oldOwner.preview(AppThemeMode.DARK, commits.state.value)
        assertEquals(AppThemeMode.LIGHT, commits.state.value!!.mode)
        assertEquals(AppThemeMode.LIGHT, replacement.mode)
        observer.cancelAndJoin()
    }

    @Test
    fun commitBetweenInitialSampleAndObserverRegistrationIsReplayed() = runBlocking {
        val commits = CommittedThemeState()
        val replacement = ThemePresentationOwner(AppThemeMode.DARK)
        replacement.observeCommitted(commits.state.value)
        commits.publishCommitted(AppThemeMode.LIGHT)

        val delivered = CompletableDeferred<Unit>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            commits.state.collect {
                replacement.observeCommitted(commits.state.value)
                delivered.complete(Unit)
            }
        }
        withTimeout(5_000L) { delivered.await() }
        assertEquals(AppThemeMode.LIGHT, replacement.mode)
        observer.cancelAndJoin()
    }

    @Test
    fun processWithoutPublishedCommitKeepsExistingPlatformThemeSample() {
        val commits = CommittedThemeState()
        for (mode in AppThemeMode.entries) {
            val owner = ThemePresentationOwner(mode)
            owner.observeCommitted(commits.state.value)
            assertEquals(mode, owner.mode)
        }
        assertNull(commits.state.value)
    }

    @Test
    fun newerCommitWinsOverQueuedEarlierPayload() {
        val commits = CommittedThemeState()
        commits.publishCommitted(AppThemeMode.LIGHT)
        val earlier = commits.state.value!!
        commits.publishCommitted(AppThemeMode.DARK)
        val newer = commits.state.value!!
        assertTrue(newer.revision > earlier.revision)

        val owner = ThemePresentationOwner(AppThemeMode.SYSTEM)
        owner.observeCommitted(newer)
        owner.observeCommitted(earlier)
        assertEquals(AppThemeMode.DARK, owner.mode)
        assertEquals(newer, commits.state.value)
    }

    @Test
    fun olderCommitCannotRepaintNewerDraftAndReleaseUsesLatestPublication() {
        val commits = CommittedThemeState()
        val owner = ThemePresentationOwner(AppThemeMode.SYSTEM)
        owner.preview(AppThemeMode.LIGHT, commits.state.value)
        // The submitted Light save is still running when the user previews Dark.
        owner.preview(AppThemeMode.DARK, commits.state.value)
        commits.publishCommitted(AppThemeMode.LIGHT)
        owner.observeCommitted(commits.state.value)
        assertEquals(AppThemeMode.DARK, owner.mode)

        // A second accepted save finishes, but flow delivery has not happened yet.
        commits.publishCommitted(AppThemeMode.SYSTEM)
        owner.preview(null, commits.state.value)
        assertEquals(AppThemeMode.SYSTEM, owner.mode)
    }

    @Test
    fun equalThemePublicationDoesNotRetirePreviewOwnership() {
        val commits = CommittedThemeState()
        val owner = ThemePresentationOwner(AppThemeMode.DARK)
        owner.preview(AppThemeMode.LIGHT, commits.state.value)
        commits.publishCommitted(AppThemeMode.LIGHT)
        owner.observeCommitted(commits.state.value)
        commits.publishCommitted(AppThemeMode.SYSTEM)
        owner.observeCommitted(commits.state.value)
        assertEquals(AppThemeMode.LIGHT, owner.mode)
        owner.preview(null, commits.state.value)
        assertEquals(AppThemeMode.SYSTEM, owner.mode)
    }

    @Test
    fun teardownRejectsQueuedCommitAndPreviewRelease() {
        val commits = CommittedThemeState()
        val retired = ThemePresentationOwner(AppThemeMode.DARK)
        retired.preview(AppThemeMode.LIGHT, commits.state.value)
        retired.close()
        commits.publishCommitted(AppThemeMode.SYSTEM)
        retired.observeCommitted(commits.state.value)
        retired.preview(null, commits.state.value)
        retired.preview(AppThemeMode.DARK, commits.state.value)
        assertEquals(AppThemeMode.LIGHT, retired.mode)

        val replacement = ThemePresentationOwner(AppThemeMode.DARK)
        replacement.observeCommitted(commits.state.value)
        assertEquals(AppThemeMode.SYSTEM, replacement.mode)
    }
}
