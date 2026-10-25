package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingAudioPreferencesTest {
    private val customCurve = SilenceGateConfig(
        enabled = false,
        points = listOf(
            SilencePoint(20, -81),
            SilencePoint(320, -54),
            SilencePoint(2_000, -63),
            SilencePoint(20_000, -74),
        ),
    )

    @Test
    fun explicitEnableChoosesFlacAndPreservesTheCurrentCalibration() {
        val result = requireNotNull(resolveOnboardingAudioSelection(
            original = OnboardingAudioSelection(false, false),
            selected = OnboardingAudioSelection(true, true),
            currentFormat = ExportFormat.WAV,
            currentSilence = customCurve,
            sampleFormat = PcmSampleFormat.PCM_16,
        ))

        assertEquals(ExportFormat.FLAC, result.outputFormat)
        assertEquals(customCurve.copy(enabled = true), result.silenceConfig)
        assertEquals(customCurve.points, decodeSilenceGate(encodeSilenceGate(result.silenceConfig)).points)
    }

    @Test
    fun explicitDisableChoosesWavWithoutResettingTheCurrentCalibration() {
        val result = requireNotNull(resolveOnboardingAudioSelection(
            original = OnboardingAudioSelection(true, true),
            selected = OnboardingAudioSelection(false, false),
            currentFormat = ExportFormat.FLAC,
            currentSilence = customCurve.copy(enabled = true),
            sampleFormat = PcmSampleFormat.PCM_FLOAT,
        ))

        assertEquals(ExportFormat.WAV, result.outputFormat)
        assertEquals(customCurve, result.silenceConfig)
    }

    @Test
    fun unchangedChoicesFollowFreshDurableValuesInEitherDirection() {
        for (originalEnabled in listOf(false, true)) {
            val original = OnboardingAudioSelection(originalEnabled, originalEnabled)
            val currentFormat = if (originalEnabled) ExportFormat.WAV else ExportFormat.FLAC
            val currentCurve = customCurve.copy(enabled = !originalEnabled)

            val result = requireNotNull(resolveOnboardingAudioSelection(
                original = original,
                selected = original,
                currentFormat = currentFormat,
                currentSilence = currentCurve,
                sampleFormat = PcmSampleFormat.PCM_16,
            ))

            assertEquals(currentFormat, result.outputFormat)
            assertEquals(currentCurve, result.silenceConfig)
        }
    }

    @Test
    fun changingOnlyOneChoiceDoesNotOverwriteTheOtherCurrentChoice() {
        val outputOnly = requireNotNull(resolveOnboardingAudioSelection(
            original = OnboardingAudioSelection(false, false),
            selected = OnboardingAudioSelection(true, false),
            currentFormat = ExportFormat.WAV,
            currentSilence = customCurve.copy(enabled = true),
            sampleFormat = PcmSampleFormat.PCM_16,
        ))
        assertEquals(ExportFormat.FLAC, outputOnly.outputFormat)
        assertEquals(customCurve.copy(enabled = true), outputOnly.silenceConfig)

        val silenceOnly = requireNotNull(resolveOnboardingAudioSelection(
            original = OnboardingAudioSelection(false, false),
            selected = OnboardingAudioSelection(false, true),
            currentFormat = ExportFormat.FLAC,
            currentSilence = customCurve,
            sampleFormat = PcmSampleFormat.PCM_16,
        ))
        assertEquals(ExportFormat.FLAC, silenceOnly.outputFormat)
        assertEquals(customCurve.copy(enabled = true), silenceOnly.silenceConfig)
    }

    @Test
    fun bothChosenAndConcurrentlyEnabledFlacRejectIncompatibleCaptureFormats() {
        for (format in listOf(PcmSampleFormat.PCM_8, PcmSampleFormat.PCM_FLOAT)) {
            assertNull(resolveOnboardingAudioSelection(
                original = OnboardingAudioSelection(false, false),
                selected = OnboardingAudioSelection(true, false),
                currentFormat = ExportFormat.WAV,
                currentSilence = customCurve,
                sampleFormat = format,
            ))
            assertNull(resolveOnboardingAudioSelection(
                original = OnboardingAudioSelection(false, false),
                selected = OnboardingAudioSelection(false, false),
                currentFormat = ExportFormat.FLAC,
                currentSilence = customCurve,
                sampleFormat = format,
            ))
        }
    }

    @Test
    fun wavAndSilenceSkippingRemainAvailableForEveryCaptureFormat() {
        for (format in PcmSampleFormat.entries) {
            val result = resolveOnboardingAudioSelection(
                original = OnboardingAudioSelection(false, false),
                selected = OnboardingAudioSelection(false, true),
                currentFormat = ExportFormat.WAV,
                currentSilence = customCurve,
                sampleFormat = format,
            )

            assertNotNull("WAV must not inherit the FLAC restriction for $format", result)
            assertEquals(ExportFormat.WAV, result!!.outputFormat)
            assertEquals(customCurve.copy(enabled = true), result.silenceConfig)
        }
    }

    @Test
    fun hydrationPreservesValidCalibrationAndDefaultsOnlyAbsentSilence() {
        val absent = decodeOnboardingSilence(emptyMap<String, Any?>())
        assertTrue(absent.valid)
        assertEquals(SilenceGateConfig(), absent.config)

        for (enabled in listOf(false, true)) {
            val config = customCurve.copy(enabled = enabled)
            val loaded = decodeOnboardingSilence(mapOf(
                PrefKey.SILENCE_GATE.name to encodeSilenceGate(config),
            ))
            assertTrue(loaded.valid)
            assertEquals(config, loaded.config)
        }
    }

    @Test
    fun malformedSilenceHydratesAsDisabledWithoutRewritingTheRawPreference() {
        for (bad in listOf(null, 17, true, "", "bad curve", "1;1;20:-51", "x".repeat(513))) {
            val raw = mapOf(PrefKey.SILENCE_GATE.name to bad)

            val loaded = decodeOnboardingSilence(raw)

            assertFalse("Malformed raw value must remain visibly invalid: $bad", loaded.valid)
            assertEquals(SilenceGateConfig(), loaded.config)
            assertTrue(raw.containsKey(PrefKey.SILENCE_GATE.name))
            assertEquals(bad, raw[PrefKey.SILENCE_GATE.name])
        }
    }
    private val input = SilenceCalibrationInput(16_000, PcmSampleFormat.PCM_16,
        ChannelMode.MONO, AudioSourceMode.MIC, InputRouteMode.AUTO)
    private fun histogram(forInput: SilenceCalibrationInput = input, db: Float = -65f): String =
        encodeSilenceCalibrationHistogram(SilenceCalibrationHistogram(
            forInput, forInput.sampleRate, List(SILENCE_GRAPH_BINS) { db }))

    @Test
    fun untouchedLevelAndMeasurementFollowFreshDurableValues() {
        val old = OnboardingAudioSelection(true, true, 5, customCurve, histogram())
        val nextInput = input.copy(sampleRate = 48_000)
        val freshHistogram = histogram(nextInput)
        val freshCurve = customCurve.copy(enabled = true, points = customCurve.points.map { it.copy(db = -70) })
        val result = requireNotNull(resolveOnboardingAudioSelection(old, old, ExportFormat.FLAC,
            freshCurve, PcmSampleFormat.PCM_16, 8, freshHistogram, nextInput))
        assertEquals(8, result.flacCompression)
        assertEquals(freshHistogram, result.silenceHistogram)
        assertEquals(freshCurve, result.silenceConfig)
    }

    @Test
    fun editedLevelAndCalibrationPersistTogether() {
        val old = OnboardingAudioSelection(false, false, 5, customCurve, null)
        val measured = customCurve.copy(enabled = true, points = customCurve.points.map { it.copy(db = -60) })
        val selected = OnboardingAudioSelection(true, true, 2, measured, histogram())
        val result = requireNotNull(resolveOnboardingAudioSelection(old, selected, ExportFormat.WAV,
            customCurve, PcmSampleFormat.PCM_16, 7, null, input))
        assertEquals(ExportFormat.FLAC, result.outputFormat)
        assertEquals(2, result.flacCompression)
        assertEquals(measured, result.silenceConfig)
        assertEquals(selected.silenceHistogram, result.silenceHistogram)
    }

    @Test
    fun editedCalibrationCannotBorrowAConcurrentInputsMeasurement() {
        val enabledCurve = customCurve.copy(enabled = true)
        val old = OnboardingAudioSelection(true, true, 5, enabledCurve, histogram())
        val changedCurve = enabledCurve.copy(points = enabledCurve.points.map { it.copy(db = -60) })
        val nextInput = input.copy(sampleRate = 48_000)
        assertNull(resolveOnboardingAudioSelection(old, old.copy(silenceConfig = changedCurve),
            ExportFormat.FLAC, enabledCurve, PcmSampleFormat.PCM_16, 5, histogram(nextInput), nextInput))
        assertNull(resolveOnboardingAudioSelection(old, old.copy(silenceHistogram = histogram(db = -62f)),
            ExportFormat.FLAC, enabledCurve, PcmSampleFormat.PCM_16, 5, histogram(nextInput), nextInput))
    }

    @Test
    fun manuallyEditedCurveCanFinishWithSilenceOffWithoutCalibration() {
        val edited = customCurve.copy(points = customCurve.points.map { it.copy(db = -60) })
        for (optionalHistogram in listOf(null, histogram())) {
            val original = OnboardingAudioSelection(false, false, 5, customCurve, optionalHistogram)
            val selected = original.copy(outputCompressionEnabled = true, flacCompression = 2,
                silenceConfig = edited)
            val result = requireNotNull(resolveOnboardingAudioSelection(original, selected,
                ExportFormat.WAV, customCurve, PcmSampleFormat.PCM_16, 5, optionalHistogram, input))
            assertEquals(ExportFormat.FLAC, result.outputFormat)
            assertEquals(2, result.flacCompression)
            assertEquals(edited, result.silenceConfig)
            assertFalse(result.silenceConfig.enabled)
            assertEquals(optionalHistogram, result.silenceHistogram)
        }
    }

    @Test
    fun manuallyEditedCurveStillRequiresMeasurementWhenSilenceIsEnabled() {
        val original = OnboardingAudioSelection(false, false, 5, customCurve, null)
        val edited = customCurve.copy(enabled = true, points = customCurve.points.map { it.copy(db = -60) })
        val selected = original.copy(skipSilenceEnabled = true, silenceConfig = edited)
        assertNull(resolveOnboardingAudioSelection(original, selected, ExportFormat.WAV,
            customCurve, PcmSampleFormat.PCM_16, 5, null, input))
        // An untouched switch follows a concurrent enable, so the effective state also gates Finish.
        assertNull(resolveOnboardingAudioSelection(original, selected.copy(skipSilenceEnabled = false),
            ExportFormat.WAV, customCurve.copy(enabled = true), PcmSampleFormat.PCM_16, 5, null, input))
    }

    @Test
    fun disabledCurveDoesNotAcceptCorruptOrWrongInputMeasurements() {
        val original = OnboardingAudioSelection(false, false, 5, customCurve, null)
        val edited = customCurve.copy(points = customCurve.points.map { it.copy(db = -60) })
        for (invalidHistogram in listOf("invalid", histogram(input.copy(sampleRate = 48_000)))) {
            val selected = original.copy(silenceConfig = edited, silenceHistogram = invalidHistogram)
            assertNull(resolveOnboardingAudioSelection(original, selected, ExportFormat.WAV,
                customCurve, PcmSampleFormat.PCM_16, 5, null, input))
        }
    }

    @Test
    fun enablingOrKeepingSilenceDoesNotRequireANewMeasurement() {
        val original = OnboardingAudioSelection(false, false, 5, customCurve, null)
        for (format in PcmSampleFormat.entries) {
            // Actual native drafts carry a curve even when the user only changes the switch.
            val explicit = requireNotNull(resolveOnboardingAudioSelection(original,
                original.copy(skipSilenceEnabled = true), ExportFormat.WAV,
                customCurve, format, 7, null, input.copy(sampleFormat = format)))
            assertTrue(explicit.silenceConfig.enabled)
            assertEquals(customCurve.points, explicit.silenceConfig.points)
            assertEquals(7, explicit.flacCompression)
            assertNull(explicit.silenceHistogram)
            val concurrent = requireNotNull(resolveOnboardingAudioSelection(original, original,
                ExportFormat.WAV, customCurve.copy(enabled = true), format, 7, null,
                input.copy(sampleFormat = format)))
            assertEquals(explicit, concurrent)
        }
    }

    @Test
    fun untouchedProfileAndMeasurementRemainDurableWhenTogglingSilence() {
        val original = OnboardingAudioSelection(false, false, 5, customCurve, null)
        val freshCurve = customCurve.copy(points = customCurve.points.map { it.copy(db = -70) })
        for (stored in listOf(null, "invalid", histogram(input.copy(sampleRate = 48_000)))) {
            val result = requireNotNull(resolveOnboardingAudioSelection(original,
                original.copy(skipSilenceEnabled = true), ExportFormat.WAV, freshCurve,
                PcmSampleFormat.PCM_16, 8, stored, input))
            assertEquals(freshCurve.copy(enabled = true), result.silenceConfig)
            assertEquals(stored, result.silenceHistogram)
            assertEquals(8, result.flacCompression)
        }
    }

    @Test
    fun concurrentCurveEditIsNotOverwrittenByAnOlderOnboardingDraft() {
        val old = OnboardingAudioSelection(true, true, 5, customCurve, histogram())
        val selected = old.copy(silenceConfig = customCurve.copy(points = customCurve.points.map { it.copy(db = -60) }))
        val concurrent = customCurve.copy(points = customCurve.points.map { it.copy(db = -75) })
        assertNull(resolveOnboardingAudioSelection(old, selected, ExportFormat.FLAC, concurrent,
            PcmSampleFormat.PCM_16, 5, histogram(), input))
    }

}
