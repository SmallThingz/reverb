package app.smallthingz.reverb

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SettingsDraftRestorationTest {
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

    private fun save(
        draft: SettingsUiDraft,
        durable: SettingsSnapshot = baseline,
        persisting: Boolean = false,
    ): List<Any> = SettingsDraftRestoration().save(true, true, persisting, durable) { draft }

    private fun hydrate(
        restored: SettingsDraftRestoration,
        durable: SettingsSnapshot = baseline,
    ): SettingsDraftHydration = requireNotNull(restored.hydrate(0L, 0L) {
        SettingsUiDraft.normalized(durable)
    })

    @Test
    fun rotationPreservesVisibleRetentionEditAgainstFreshBaseline() {
        val edited = SettingsUiDraft.normalized(baseline.copy(oneShotRetentionTime = 600))
            .copy(oneShotTimeText = "10")
        val result = hydrate(SettingsDraftRestoration.restore(save(edited)))
        assertEquals(baseline, result.baseline)
        assertEquals(edited, result.displayed)
        assertTrue(result.restored)
        assertTrue(result.hasUnsavedChanges)
    }

    @Test
    fun exactInvalidInactiveTextsAndBytePrecisionSurviveRepeatedRecreationBeforeHydration() {
        val edited = SettingsUiDraft.normalized(
            baseline.copy(oneShotRetentionSizeBytes = 9_007_199_254_740_993L),
        ).copy(
            oneShotTimeText = "0:",
            loopingTimeText = "",
            oneShotSizeText = "-",
            loopingSizeText = "12,345678901",
        )
        var restored = SettingsDraftRestoration.restore(save(edited))
        repeat(3) {
            val savedAgain = restored.save(true, false, false, SettingsSnapshot()) {
                error("Unhydrated placeholder must never replace the pending draft")
            }
            restored = SettingsDraftRestoration.restore(savedAgain)
        }
        val result = hydrate(restored)
        assertEquals(edited, result.displayed)
        assertEquals(9_007_199_254_740_993L, result.displayed.snapshot.oneShotRetentionSizeBytes)
        assertTrue(result.hasUnsavedChanges)
    }

    @Test
    fun textOnlyInactiveEditIsSavedWithoutInventingActiveModeDirtiness() {
        val edited = SettingsUiDraft.normalized(baseline).copy(oneShotSizeText = "-")
        val result = hydrate(SettingsDraftRestoration.restore(save(edited)))
        assertEquals("-", result.displayed.oneShotSizeText)
        assertFalse(result.hasUnsavedChanges)
        val switched = result.displayed.copy(snapshot = result.displayed.snapshot.copy(retentionMode = RetentionMode.SIZE))
        assertTrue(switched.hasUnsavedChanges(baseline.copy(retentionMode = RetentionMode.SIZE)))
    }

    @Test
    fun successfulAcceptedSaveUsesNewDurableBaselineWithoutRecreatingSaveOwnership() {
        val submitted = SettingsUiDraft.normalized(baseline.copy(themeMode = AppThemeMode.LIGHT))
        val result = hydrate(SettingsDraftRestoration.restore(save(submitted, persisting = true)), submitted.snapshot)
        assertEquals(submitted.snapshot, result.baseline)
        assertEquals(submitted, result.displayed)
        assertFalse(result.hasUnsavedChanges)
        assertNull(result.themePreview)
        assertTrue(save(result.displayed, result.baseline).isEmpty())
    }

    @Test
    fun failedAcceptedSaveRetainsVisibleDraftAgainstRolledBackBaseline() {
        val submitted = SettingsUiDraft.normalized(baseline.copy(themeMode = AppThemeMode.LIGHT))
        val result = hydrate(SettingsDraftRestoration.restore(save(submitted, persisting = true)))
        assertEquals(baseline, result.baseline)
        assertEquals(submitted, result.displayed)
        assertTrue(result.hasUnsavedChanges)
        assertEquals(AppThemeMode.LIGHT, result.themePreview)
    }

    @Test
    fun newerReversionToOldBaselineSurvivesAcceptedSaveAndKeepsThemePreviewOwnership() {
        val submitted = baseline.copy(themeMode = AppThemeMode.LIGHT)
        // The user changed Light back to Dark while the Light transaction was pending.
        val latestVisible = SettingsUiDraft.normalized(baseline)
        val saved = save(latestVisible, persisting = true)
        assertFalse(saved.isEmpty())
        val result = hydrate(SettingsDraftRestoration.restore(saved), submitted)
        assertEquals(AppThemeMode.DARK, result.displayed.snapshot.themeMode)
        assertEquals(AppThemeMode.LIGHT, result.baseline.themeMode)
        assertTrue(result.hasUnsavedChanges)

        val commits = CommittedThemeState()
        commits.publishCommitted(submitted.themeMode)
        val replacement = ThemePresentationOwner(AppThemeMode.DARK)
        replacement.observeCommitted(commits.state.value)
        replacement.preview(result.themePreview, commits.state.value)
        assertEquals(AppThemeMode.DARK, replacement.mode)
        // Closing/Undo releases to the actual committed Light, never the predecessor baseline.
        replacement.preview(null, commits.state.value)
        assertEquals(AppThemeMode.LIGHT, replacement.mode)
    }

    @Test
    fun newerMultiFieldDraftIsComparedWithCompletedSubmission() {
        val submitted = baseline.copy(themeMode = AppThemeMode.LIGHT, sampleRate = 44_100)
        val newest = SettingsUiDraft.normalized(
            submitted.copy(sampleRate = 32_000, oneShotRetentionTime = 900, quickSaveSeconds = 47),
        ).copy(oneShotTimeText = "15,0", loopingSizeText = "unfinished")
        val result = hydrate(SettingsDraftRestoration.restore(save(newest, persisting = true)), submitted)
        assertEquals(newest, result.displayed)
        assertEquals(submitted, result.baseline)
        assertTrue(result.hasUnsavedChanges)
        assertNull(result.themePreview)
    }

    @Test
    fun supersededHydrationCannotMutateUiOrConsumeTheRestoredDraft() {
        val edited = SettingsUiDraft.normalized(baseline.copy(oneShotRetentionTime = 900))
        val restored = SettingsDraftRestoration.restore(save(edited))
        assertNull(restored.hydrate(2L, 3L) { error("Superseded hydration must not apply UI values") })
        val savedAgain = restored.save(true, false, false, SettingsSnapshot()) {
            error("Placeholder must remain inert")
        }
        assertEquals(edited, hydrate(SettingsDraftRestoration.restore(savedAgain)).displayed)
        assertEquals(edited, requireNotNull(restored.hydrate(3L, 3L) {
            SettingsUiDraft.normalized(baseline)
        }).displayed)
        // Restoration is one-shot; a later legitimate clean hydration has current authority.
        val newerBaseline = baseline.copy(oneShotRetentionTime = 1_200)
        val later = hydrate(restored, newerBaseline)
        assertFalse(later.restored)
        assertEquals(newerBaseline, later.displayed.snapshot)
    }

    @Test
    fun baselineFailureLeavesPendingDraftAvailableForRetryAndAnotherRecreation() {
        val edited = SettingsUiDraft.normalized(baseline.copy(themeMode = AppThemeMode.SYSTEM))
        val restored = SettingsDraftRestoration.restore(save(edited))
        try {
            restored.hydrate(0L, 0L) { throw IOException("baseline unavailable") }
            fail("Expected baseline failure")
        } catch (_: IOException) {
            // The existing UI retry owner handles this failure.
        }
        val savedAgain = restored.save(true, false, false, SettingsSnapshot()) {
            error("Failed hydration must not sample placeholder inputs")
        }
        assertEquals(edited, hydrate(SettingsDraftRestoration.restore(savedAgain)).displayed)
    }

    @Test
    fun cleanPanelAndExplicitCloseOrUndoDoNotResurrectOldDrafts() {
        val clean = SettingsUiDraft.normalized(baseline)
        assertTrue(save(clean).isEmpty())
        val newestBaseline = baseline.copy(sampleRate = 44_100)
        val fresh = hydrate(SettingsDraftRestoration.restore(save(clean)), newestBaseline)
        assertEquals(newestBaseline, fresh.displayed.snapshot)

        val edited = clean.copy(oneShotTimeText = "")
        val restored = SettingsDraftRestoration.restore(save(edited))
        assertTrue(restored.save(false, false, false, baseline) { error("Closed panel must not sample") }.isEmpty())
        restored.discard()
        assertFalse(hydrate(restored, newestBaseline).restored)
        assertTrue(save(SettingsUiDraft.normalized(fresh.baseline), fresh.baseline).isEmpty())
    }

    @Test
    fun pendingPickerResultWaitsForHydrationAlongsideOtherRestoredEdits() {
        val edited = SettingsUiDraft.normalized(baseline.copy(oneShotRetentionTime = 900, themeMode = AppThemeMode.LIGHT))
            .copy(oneShotTimeText = "15", loopingSizeText = "-")
        val picker = SettingsDirectoryPickerState(panelActive = true)
        assertTrue(picker.beginRequest())
        val restoredPicker = SettingsDirectoryPickerState.restore(picker.save())
        val restoredDraft = SettingsDraftRestoration.restore(save(edited))
        val tree = "content://example.documents/tree/primary%3AReverb"
        restoredPicker.receiveResult(tree)
        assertNull(restoredPicker.takePendingResult(hydrated = false))
        val result = hydrate(restoredDraft)
        assertEquals(edited, result.displayed)
        assertEquals(tree, restoredPicker.takePendingResult(hydrated = true))
        assertNull(restoredPicker.takePendingResult(hydrated = true))
        assertTrue(result.hasUnsavedChanges)
    }

    @Test
    fun saveSamplesLatestValuesAndRoundTripsEverySettingAsPrimitives() {
        val state = SettingsDraftRestoration()
        var visible = SettingsUiDraft.normalized(baseline)
        val current = { visible }
        visible = SettingsUiDraft.normalized(
            baseline.copy(
                themeMode = AppThemeMode.SYSTEM,
                retentionMode = RetentionMode.SIZE,
                oneShotRetentionTime = 1,
                oneShotRetentionSizeBytes = 9_007_199_254_740_993L,
                loopingRetentionTime = 2,
                loopingRetentionSizeBytes = 9_007_199_254_740_995L,
                format = ExportFormat.FLAC,
                codec = ExportCodec.PCM_16,
                sampleFormat = PcmSampleFormat.PCM_FLOAT,
                source = AudioSourceMode.CAMCORDER,
                channelMode = ChannelMode.STEREO,
                route = InputRouteMode.BUILTIN_MIC,
                sampleRate = 96_000,
                exportDirectoryUri = "content://example.documents/tree/primary%3AMusic",
                wakeLockEnabled = true,
                flacCompression = 7,
                quickSaveEnabled = false,
                quickSaveSeconds = 123,
                silenceGate = "exact unsaved silence encoding",
            ),
        ).copy(oneShotTimeText = "1:", oneShotSizeText = "1,000", loopingTimeText = "", loopingSizeText = "9.")
        val saved = state.save(true, true, false, baseline, current)
        assertTrue(saved.all { it is String || it is Int || it is Long || it is Boolean })
        assertEquals(visible, SettingsUiDraft.restore(saved))
    }

    @Test
    fun unsupportedOrMalformedSavedUiStateFallsBackToFreshHydration() {
        val valid = SettingsUiDraft.normalized(baseline).save()
        val cases = listOf(
            emptyList(),
            valid.dropLast(1),
            valid.toMutableList().apply { this[0] = 99 },
            valid.toMutableList().apply { this[1] = 999 },
        )
        for (saved in cases) {
            val result = hydrate(SettingsDraftRestoration.restore(saved))
            assertFalse(result.restored)
            assertEquals(baseline, result.displayed.snapshot)
        }
        val nullable = SettingsUiDraft.normalized(SettingsSnapshot())
        assertEquals(nullable, SettingsUiDraft.restore(nullable.save()))
    }
}
