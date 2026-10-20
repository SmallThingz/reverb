package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class SilenceCalibrationHistogramTest {
    private val input = SilenceCalibrationInput(
        sampleRate = 48_000,
        sampleFormat = PcmSampleFormat.PCM_16,
        channelMode = ChannelMode.MONO,
        source = AudioSourceMode.MIC,
        route = InputRouteMode.AUTO,
    )
    private val baseline = SettingsSnapshot(
        retentionMode = RetentionMode.TIME,
        oneShotRetentionTime = 300,
        loopingRetentionTime = 600,
        sampleRate = input.sampleRate,
        sampleFormat = input.sampleFormat,
        channelMode = input.channelMode,
        source = input.source,
        route = input.route,
        silenceGate = encodeSilenceGate(SilenceGateConfig(enabled = true)),
    )

    private fun histogram(offset: Float = 0f) = SilenceCalibrationHistogram(
        input, 16_000, List(SILENCE_GRAPH_BINS) { -100f + it % 30 + offset },
    )

    private fun encoded(offset: Float = 0f) = encodeSilenceCalibrationHistogram(histogram(offset))

    private fun hydrate(
        restoration: SettingsDraftRestoration,
        durable: SettingsSnapshot,
    ): SettingsDraftHydration = requireNotNull(restoration.hydrate(0L, 0L) {
        SettingsUiDraft.normalized(durable)
    })

    private fun saveDraft(draft: SettingsSnapshot, durable: SettingsSnapshot = baseline, persisting: Boolean = false) =
        SettingsDraftRestoration().save(true, true, persisting, durable) { SettingsUiDraft.normalized(draft) }

    @Test
    fun genuineMeasuredBinsAndOperationalRateRoundTripWithoutResampling() {
        val measured = histogram().copy(peaks = histogram().peaks.toMutableList().apply {
            this[0] = -120f
            this[lastIndex] = 0f
            this[17] = -37.12345f
        })
        val restored = requireNotNull(decodeSilenceCalibrationHistogram(encodeSilenceCalibrationHistogram(measured)))
        assertEquals(measured, restored)
        assertEquals(16_000, restored.measuredSampleRate)
        assertEquals(48_000, restored.input.sampleRate)
        assertEquals(measured.peaks, restored.peaksFor(input))
    }

    @Test
    fun absentMalformedAndOversizedOptionalMetadataIsIgnored() {
        val valid = encoded()
        fun field(index: Int, replacement: String) = valid.split(';').toMutableList()
            .apply { this[index] = replacement }.joinToString(";")
        fun level(replacement: String) = field(7, histogram().peaks.mapIndexed { index, value ->
            if (index == 17) replacement else value.toString()
        }.joinToString(","))
        val invalid = listOf(
            null, "", "legacy histogram", "x".repeat(2_049), valid + ";extra",
            field(0, "99"), field(1, "0"), field(2, "999"), field(3, "999"),
            field(4, "999"), field(5, "999"), field(6, "0"),
            field(7, histogram().peaks.dropLast(1).joinToString(",")),
            level("NaN"), level("Infinity"), level("-120.01"), level("0.01"),
        )
        for (raw in invalid) assertNull("Optional metadata must be discardable: ${raw?.take(40)}", decodeSilenceCalibrationHistogram(raw))
        assertEquals(histogram(), decodeSilenceCalibrationHistogram(valid))
    }

    @Test
    fun eachChangedCaptureInputHidesTheOldMeasurementWithoutDiscardingIt() {
        val measured = requireNotNull(decodeSilenceCalibrationHistogram(encoded()))
        val mismatches = listOf(
            input.copy(sampleRate = 44_100),
            input.copy(sampleFormat = PcmSampleFormat.PCM_FLOAT),
            input.copy(channelMode = ChannelMode.STEREO),
            input.copy(source = AudioSourceMode.CAMCORDER),
            input.copy(route = InputRouteMode.BUILTIN_MIC),
            input.copy(source = null),
        )
        for (changed in mismatches) assertNull(measured.peaksFor(changed))
        assertEquals(histogram().peaks, measured.peaksFor(input))
    }

    @Test
    fun cleanSettingsReopenHydratesTheSavedHistogramRepeatedly() {
        val durable = baseline.copy(silenceHistogram = encoded())
        repeat(3) {
            val reopened = hydrate(SettingsDraftRestoration(), durable)
            assertFalse(reopened.restored)
            assertFalse(reopened.hasUnsavedChanges)
            val measured = requireNotNull(decodeSilenceCalibrationHistogram(reopened.displayed.snapshot.silenceHistogram))
            assertEquals(histogram().peaks, measured.peaksFor(reopened.displayed.snapshot.silenceCalibrationInput()))
            assertTrue(saveDraft(reopened.displayed.snapshot, durable).isEmpty())
        }
    }

    @Test
    fun manualCurveEditsRetainTheMeasuredHistogramThroughDraftRecreation() {
        val durable = baseline.copy(silenceHistogram = encoded())
        val manualCurve = SilenceGateConfig(enabled = true, points = listOf(
            SilencePoint(20, -60), SilencePoint(2_000, -43), SilencePoint(20_000, -35),
        ))
        val edited = durable.copy(silenceGate = encodeSilenceGate(manualCurve))
        val reopened = hydrate(SettingsDraftRestoration.restore(saveDraft(edited, durable)), durable)
        assertEquals(manualCurve, decodeSilenceGate(reopened.displayed.snapshot.silenceGate))
        assertEquals(histogram().peaks, requireNotNull(decodeSilenceCalibrationHistogram(
            reopened.displayed.snapshot.silenceHistogram,
        )).peaksFor(reopened.displayed.snapshot.silenceCalibrationInput()))
        assertTrue(reopened.hasUnsavedChanges)
    }

    @Test
    fun newMeasurementWithTheSameCurveIsDirtyAndSurvivesRecreation() {
        val durable = baseline.copy(silenceHistogram = encoded())
        val measuredAgain = durable.copy(silenceHistogram = encoded(1f))
        assertEquals(durable.silenceGate, measuredAgain.silenceGate)
        assertTrue(settingsSnapshotHasUnsavedChanges(durable, measuredAgain, false))
        var restored = SettingsDraftRestoration.restore(saveDraft(measuredAgain, durable))
        repeat(2) {
            restored = SettingsDraftRestoration.restore(restored.save(true, false, false, SettingsSnapshot()) {
                error("Unhydrated placeholders must not overwrite the new measurement")
            })
        }
        val reopened = hydrate(restored, durable)
        assertEquals(measuredAgain, reopened.displayed.snapshot)
        assertTrue(reopened.hasUnsavedChanges)
    }

    @Test
    fun successfulSaveReopensCleanWhileFailedSaveKeepsTheHistogramDraft() {
        val durable = baseline.copy(silenceHistogram = encoded())
        val submitted = durable.copy(silenceHistogram = encoded(2f))
        val state = saveDraft(submitted, durable, persisting = true)
        val accepted = hydrate(SettingsDraftRestoration.restore(state), submitted)
        assertEquals(submitted, accepted.displayed.snapshot)
        assertFalse(accepted.hasUnsavedChanges)
        assertTrue(saveDraft(accepted.displayed.snapshot, accepted.baseline).isEmpty())

        val failed = hydrate(SettingsDraftRestoration.restore(state), durable)
        assertEquals(durable, failed.baseline)
        assertEquals(submitted, failed.displayed.snapshot)
        assertTrue(failed.hasUnsavedChanges)
    }

    @Test
    fun explicitDiscardAndReopenRestoreThePreviouslySavedHistogram() {
        val durable = baseline.copy(silenceHistogram = encoded())
        val unsaved = durable.copy(silenceHistogram = encoded(3f))
        val restored = SettingsDraftRestoration.restore(saveDraft(unsaved, durable))
        restored.discard()
        val reopened = hydrate(restored, durable)
        assertFalse(reopened.restored)
        assertFalse(reopened.hasUnsavedChanges)
        assertEquals(durable.silenceHistogram, reopened.displayed.snapshot.silenceHistogram)
    }

    @Test
    fun legacyDraftWithoutHistogramRetainsItsOtherUnsavedValues() {
        val edited = baseline.copy(quickSaveSeconds = 47, oneShotRetentionTime = 900)
        val legacy = SettingsUiDraft.normalized(edited).save().take(24).toMutableList().apply { this[0] = 1 }
        val restored = requireNotNull(SettingsUiDraft.restore(legacy))
        assertEquals(edited, restored.snapshot)
        assertNull(restored.snapshot.silenceHistogram)
        assertTrue(restored.hasUnsavedChanges(baseline))
    }

    @Test
    fun invalidHistogramDoesNotDropTheRestOfARestoredDraft() {
        val edited = baseline.copy(quickSaveSeconds = 47, oneShotRetentionTime = 900)
        for (invalid in listOf<Any>("bad metadata", "x".repeat(2_049), 123)) {
            val saved = SettingsUiDraft.normalized(edited).save().toMutableList().apply { this[24] = invalid }
            val restored = requireNotNull(SettingsUiDraft.restore(saved))
            assertEquals(edited, restored.snapshot)
            assertNull(restored.snapshot.silenceHistogram)
            assertTrue(restored.hasUnsavedChanges(baseline))
        }
    }

    @Test
    fun rollbackOwnsTheExactOldHistogramEvenWhenOptionalPresentationIsInvalid() {
        val key = PrefKey.SILENCE_CALIBRATION_HISTOGRAM
        for (invalidateFull in listOf(false, true)) {
            assertEquals(1, settingsPreferenceRollbackKeys(invalidateFull).count { it == key })
            val missing = settingsPreferenceRollbackSnapshot(emptyMap<String, Any>(), invalidateFull).getValue(key)
            assertFalse(missing.present)
            assertNull(missing.value)
            for (raw in listOf<Any>(encoded(), "damaged optional metadata", 17)) {
                val old = settingsPreferenceRollbackSnapshot(mapOf(key.name to raw), invalidateFull).getValue(key)
                assertTrue(old.present)
                assertEquals(raw, old.value)
            }
        }
    }
}
