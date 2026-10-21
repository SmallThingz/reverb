package app.smallthingz.reverb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SettingsDraftControllerTest {
    private val baseline = SettingsSnapshot(
        themeMode = AppThemeMode.DARK,
        retentionMode = RetentionMode.TIME,
        oneShotRetentionTime = 300,
        oneShotRetentionSizeBytes = 9_007_199_254_740_993L,
        loopingRetentionTime = 600,
        loopingRetentionSizeBytes = 2_000_003L,
        format = ExportFormat.WAV,
        codec = ExportCodec.PCM_16,
        sampleFormat = PcmSampleFormat.PCM_16,
        source = AudioSourceMode.MIC,
        channelMode = ChannelMode.MONO,
        route = InputRouteMode.AUTO,
        sampleRate = 48_000,
    )

    private class Host {
        var panelActive = true
        val themes = mutableListOf<AppThemeMode?>()
        val childReadiness = mutableListOf<Pair<Boolean, Boolean>>()
        val renders = mutableListOf<SettingsDraftPresentation>()
        fun controller(
            saved: List<Any> = emptyList(),
            barrier: SettingsCommitBarrier = SettingsCommitBarrier(),
        ) = SettingsDraftController(
            initiallyActive = true,
            savedDraft = saved,
            barrier = barrier,
            isPanelActive = { panelActive },
            onChanged = { renders += it },
            onThemePreview = { themes += it },
            onChildEditorReadiness = { active, ready -> childReadiness += active to ready },
        )
    }

    private suspend fun SettingsDraftController.read(
        value: SettingsSnapshot = baseline,
    ) = hydrate(
        readDurable = { SettingsDraftBaseline(SettingsUiDraft.normalized(value), "raw-durable-silence") },
        restoreFields = { it },
    )

    @Test
    fun restoredRawInputsAndChildEditorWaitForAcceptedCommitSettlement() = runBlocking {
        val barrier = SettingsCommitBarrier()
        val submitted = baseline.copy(themeMode = AppThemeMode.LIGHT, oneShotRetentionTime = 900)
        val newer = SettingsUiDraft.normalized(submitted.copy(themeMode = AppThemeMode.SYSTEM))
            .copy(oneShotTimeText = "15,00", oneShotSizeText = "-", loopingSizeText = "unfinished")
        val saved = SettingsDraftRestoration().save(true, true, true, baseline) { newer }
        var durable = baseline
        val releaseCommit = CompletableDeferred<Unit>()
        val commit = async(start = CoroutineStart.UNDISPATCHED) {
            barrier.runAcceptedSave {
                releaseCommit.await()
                durable = submitted
            }
        }
        val host = Host()
        val controller = host.controller(saved, barrier)
        var reads = 0
        val hydration = async(start = CoroutineStart.UNDISPATCHED) {
            controller.hydrate(
                readDurable = {
                    reads++
                    SettingsDraftBaseline(SettingsUiDraft.normalized(durable), "raw-durable-silence")
                },
                restoreFields = { it },
            )
        }
        assertEquals(0, reads)
        assertFalse(controller.state.hydrated)
        assertFalse(host.childReadiness.last().second)
        releaseCommit.complete(Unit)
        commit.await()
        assertEquals(SettingsDraftHydrationResult.APPLIED, hydration.await())
        assertEquals(submitted, controller.state.baseline)
        assertEquals(newer, controller.state.displayed)
        assertEquals(9_007_199_254_740_993L, controller.state.displayed.snapshot.oneShotRetentionSizeBytes)
        assertEquals(AppThemeMode.SYSTEM, host.themes.last())
        assertTrue(controller.state.hasUnsavedChanges)
        assertEquals(true to true, host.childReadiness.last())
    }

    @Test
    fun modeSwitchKeepsBothRawDraftsAndExactBackingBytes() = runBlocking {
        val controller = Host().controller()
        controller.read()
        val timeDraft = controller.state.displayed.copy(
            oneShotTimeText = "5,000", oneShotSizeText = "-", loopingSizeText = "1.23456789",
        )
        controller.edited(timeDraft)
        assertFalse(controller.state.hasUnsavedChanges)
        controller.edited(timeDraft.copy(snapshot = timeDraft.snapshot.copy(retentionMode = RetentionMode.SIZE)))
        assertEquals("-", controller.state.displayed.oneShotSizeText)
        assertEquals("5,000", controller.state.displayed.oneShotTimeText)
        assertEquals(9_007_199_254_740_993L, controller.state.displayed.snapshot.oneShotRetentionSizeBytes)
        assertTrue(controller.state.hasUnsavedChanges)
        assertEquals(timeDraft.oneShotSizeText, SettingsUiDraft.restore(controller.saveDraft())!!.oneShotSizeText)
    }

    @Test
    fun successfulOlderCommitPreservesNewerOutputChoiceAndThemeDraft() = runBlocking {
        val host = Host()
        val controller = host.controller()
        controller.read()
        val submitted = SettingsUiDraft.normalized(baseline.copy(themeMode = AppThemeMode.LIGHT))
        controller.edited(submitted)
        controller.previewTheme(AppThemeMode.LIGHT)
        val attempt = requireNotNull(controller.beginPersistence())
        val submission = controller.submission(attempt, submitted)
        val later = submitted.copy(
            snapshot = submitted.snapshot.copy(themeMode = AppThemeMode.SYSTEM),
            oneShotTimeText = "15:",
        )
        controller.edited(later, explicitOutputChoice = true)
        controller.previewTheme(AppThemeMode.SYSTEM)
        assertFalse(controller.committed(submission, ExportFormat.FLAC))
        controller.finishPersistence(attempt)
        assertEquals(ExportFormat.FLAC, controller.state.baseline.format)
        assertEquals(ExportFormat.WAV, controller.state.displayed.snapshot.format)
        assertEquals("15:", controller.state.displayed.oneShotTimeText)
        assertEquals(AppThemeMode.SYSTEM, host.themes.last())
        assertTrue(controller.state.interactionReady)
        assertTrue(controller.undo())
        assertEquals(ExportFormat.FLAC, controller.state.displayed.snapshot.format)
        assertEquals(AppThemeMode.LIGHT, host.themes.last())
    }

    @Test
    fun unchangedSuccessfulCommitNormalizesOnlyAfterTheDurableTail() = runBlocking {
        val host = Host()
        val controller = host.controller()
        controller.read()
        val raw = controller.state.displayed.copy(oneShotTimeText = "5,000", loopingSizeText = "-")
        controller.edited(raw)
        val attempt = requireNotNull(controller.beginPersistence())
        val submitted = controller.submission(attempt, raw)
        assertEquals(raw, controller.state.displayed)
        assertTrue(controller.committed(submitted, ExportFormat.FLAC))
        assertEquals(SettingsUiDraft.normalized(baseline.copy(format = ExportFormat.FLAC)),
            controller.state.displayed)
        assertNull(host.themes.last())
        controller.finishPersistence(attempt)
        assertFalse(controller.state.interactionReady)
        assertTrue(controller.needsHydration)
    }

    @Test
    fun hiddenFailureDefersUndoUntilPersistenceEnds() = runBlocking {
        val host = Host()
        val controller = host.controller()
        controller.read()
        val edited = SettingsUiDraft.normalized(baseline.copy(themeMode = AppThemeMode.LIGHT))
        controller.edited(edited)
        val attempt = requireNotNull(controller.beginPersistence())
        host.panelActive = false
        controller.setActive(false)
        assertEquals(edited, controller.state.displayed)
        controller.finishPersistence(attempt)
        assertEquals(SettingsUiDraft.normalized(baseline), controller.state.displayed)
        assertFalse(controller.state.hasUnsavedChanges)
        assertNull(host.themes.last())
        assertFalse(controller.needsHydration)
    }

    @Test
    fun reopeningPendingSaveRetainsNewerDraftButReleasesOnlyNewActivation() = runBlocking {
        val host = Host()
        val controller = host.controller()
        controller.read()
        val oldOwner = controller.state.activation
        controller.edited(SettingsUiDraft.normalized(baseline.copy(themeMode = AppThemeMode.LIGHT)))
        val attempt = requireNotNull(controller.beginPersistence())
        controller.submission(attempt, controller.state.displayed)
        host.panelActive = false
        controller.setActive(false)
        host.panelActive = true
        controller.setActive(true)
        assertFalse(controller.state.interactionReady)
        assertFalse(controller.acceptsInput(oldOwner, true))
        controller.finishPersistence(attempt)
        assertTrue(controller.state.interactionReady)
        assertTrue(controller.acceptsInput(controller.state.activation, true))
        assertEquals(AppThemeMode.LIGHT, controller.state.displayed.snapshot.themeMode)
    }

    @Test
    fun retiredHydrationCannotHydrateALaterActivation() = runBlocking {
        val host = Host()
        val controller = host.controller()
        val releaseRead = CompletableDeferred<Unit>()
        val hydration = async(start = CoroutineStart.UNDISPATCHED) {
            controller.hydrate(
                readDurable = {
                    releaseRead.await()
                    SettingsDraftBaseline(SettingsUiDraft.normalized(baseline), null)
                },
                restoreFields = { it },
            )
        }
        host.panelActive = false
        controller.setActive(false)
        host.panelActive = true
        controller.setActive(true)
        releaseRead.complete(Unit)
        assertEquals(SettingsDraftHydrationResult.RETIRED, hydration.await())
        assertFalse(controller.state.hydrated)
        assertFalse(controller.state.interactionReady)
        assertEquals(SettingsDraftHydrationResult.APPLIED, controller.read())
    }

    @Test
    fun directoryResultWaitsForFreshBaselineAndRetiresWithActualPanel() = runBlocking {
        val host = Host()
        val controller = host.controller()
        val uri = "content://example.documents/tree/primary%3AReverb"
        assertTrue(controller.directoryPicker.beginRequest())
        controller.receiveDirectoryResult(uri)
        assertNull(controller.state.displayed.snapshot.exportDirectoryUri)
        controller.read()
        assertEquals(uri, controller.state.displayed.snapshot.exportDirectoryUri)
        assertNull(controller.state.baseline.exportDirectoryUri)
        assertTrue(controller.state.hasUnsavedChanges)
        assertTrue(controller.directoryPicker.beginRequest())
        host.panelActive = false // Actual panel changes before the retained renderer observes it.
        controller.receiveDirectoryResult("content://example.documents/tree/primary%3AOther")
        assertEquals(uri, controller.state.displayed.snapshot.exportDirectoryUri)
        assertTrue(controller.saveDraft().isEmpty())
    }
}
