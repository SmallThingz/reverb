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
}
