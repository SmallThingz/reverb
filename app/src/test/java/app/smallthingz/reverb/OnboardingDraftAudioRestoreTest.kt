package app.smallthingz.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OnboardingDraftAudioRestoreTest {
    private val input = SilenceCalibrationInput(16_000, PcmSampleFormat.PCM_16,
        ChannelMode.MONO, AudioSourceMode.MIC, InputRouteMode.AUTO)
    private val durableCurve = SilenceGateConfig(points = listOf(
        SilencePoint(20, -72), SilencePoint(200, -64),
        SilencePoint(2_000, -61), SilencePoint(20_000, -74)))
    private val durableHistogram = encodeSilenceCalibrationHistogram(SilenceCalibrationHistogram(
        input, input.sampleRate, List(SILENCE_GRAPH_BINS) { -62f }))

    @Test fun unsupportedLegacyEditsCannotReplaceDurableProfileOrTrapFinish() {
        val staleInput = input.copy(sampleRate = 48_000)
        val staleHistogram = encodeSilenceCalibrationHistogram(SilenceCalibrationHistogram(
            staleInput, staleInput.sampleRate, List(SILENCE_GRAPH_BINS) { -80f }))
        for (enabled in listOf(false, true)) {
            val durable = OnboardingAudioSelection(false, !enabled, 8,
                durableCurve.copy(enabled = !enabled), durableHistogram)
            for (legacyHistogram in listOf(null, "invalid", staleHistogram)) {
                val legacy = OnboardingAudioSelection(true, enabled, 1,
                    SilenceGateConfig(enabled = enabled), legacyHistogram)
                val restored = restoreOnboardingAudioSelection(durable, legacy)
                assertEquals(legacy.outputCompressionEnabled, restored.outputCompressionEnabled)
                assertEquals(enabled, restored.skipSilenceEnabled)
                assertEquals(durable.flacCompression, restored.flacCompression)
                assertEquals(durable.silenceConfig, restored.silenceConfig)
                assertEquals(durableHistogram, restored.silenceHistogram)
                val result = requireNotNull(resolveOnboardingAudioSelection(durable, restored,
                    ExportFormat.WAV, requireNotNull(durable.silenceConfig), PcmSampleFormat.PCM_16,
                    8, durableHistogram, input))
                assertEquals(ExportFormat.FLAC, result.outputFormat)
                assertEquals(durableCurve.copy(enabled = enabled), result.silenceConfig)
                assertEquals(8, result.flacCompression)
                assertEquals(durableHistogram, result.silenceHistogram)
            }
        }
    }

    @Test fun originalVersusSelectedSwitchOwnershipSurvivesMigration() {
        for (originalEnabled in listOf(false, true)) for (edited in listOf(false, true)) {
            val freshEnabled = !originalEnabled
            val durable = OnboardingAudioSelection(freshEnabled, freshEnabled, 8,
                durableCurve.copy(enabled = freshEnabled), durableHistogram)
            val savedOriginal = OnboardingAudioSelection(originalEnabled, originalEnabled,
                1, SilenceGateConfig(), null)
            val original = restoreOnboardingAudioSelection(durable, savedOriginal)
            val selectedEnabled = if (edited) !originalEnabled else originalEnabled
            val selected = original.copy(outputCompressionEnabled = selectedEnabled,
                skipSilenceEnabled = selectedEnabled)
            // Fresh persistence can change again after hydration; untouched choices follow it.
            val latestEnabled = originalEnabled
            val result = requireNotNull(resolveOnboardingAudioSelection(original, selected,
                if (latestEnabled) ExportFormat.FLAC else ExportFormat.WAV,
                durableCurve.copy(enabled = latestEnabled), PcmSampleFormat.PCM_16,
                7, durableHistogram, input))
            val expected = if (edited) selectedEnabled else latestEnabled
            assertEquals(if (expected) ExportFormat.FLAC else ExportFormat.WAV, result.outputFormat)
            assertEquals(expected, result.silenceConfig.enabled)
            assertEquals(7, result.flacCompression)
            assertEquals(durableHistogram, result.silenceHistogram)
        }
    }

    @Test fun configuredDefaultsAreNotPromotedToEnabledOrCalibrationRequired() {
        for (compression in listOf(false, true)) for (skip in listOf(false, true)) {
            val configured = OnboardingAudioSelection(compression, skip, 5,
                SilenceGateConfig(enabled = skip), null)
            val restored = restoreOnboardingAudioSelection(configured, configured)
            assertEquals(configured, restored)
            assertNotNull(resolveOnboardingAudioSelection(restored, restored,
                if (compression) ExportFormat.FLAC else ExportFormat.WAV,
                requireNotNull(configured.silenceConfig), PcmSampleFormat.PCM_16, 5, null, input))
        }
    }
}
