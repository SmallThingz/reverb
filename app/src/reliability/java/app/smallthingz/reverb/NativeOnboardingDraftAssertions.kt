package app.smallthingz.reverb

import android.os.Bundle
import android.os.Parcel

/** QA-only: actual Android Bundle/Parcel restoration, no Context, prefs, files or audio access. */
internal object NativeOnboardingDraftAssertions {
    fun runChecks(): List<String> {
        val passed = ArrayList<String>(5)
        val input = SilenceCalibrationInput(16_000, PcmSampleFormat.PCM_16,
            ChannelMode.MONO, AudioSourceMode.MIC, InputRouteMode.AUTO)
        val curve = SilenceGateConfig(points = listOf(
            SilencePoint(20, -75), SilencePoint(200, -64),
            SilencePoint(2_000, -61), SilencePoint(20_000, -75)))
        val histogram = encodeSilenceCalibrationHistogram(SilenceCalibrationHistogram(
            input, input.sampleRate, List(SILENCE_GRAPH_BINS) { -66f }))
        val audio = OnboardingAudioSelection(false, false, 5, curve, null)
        val configuration = OnboardingConfiguration(ConfiguredBufferAvailability(true, true),
            audio, compressionAvailable = true, silenceValid = true, input = input)

        val untouched = NativeOnboardingDraft.restore(configuration, null)
        check(untouched.selectedAudio == audio)
        check(untouched.originalAudio == audio)
        passed += "initial_audio_fields_preserved"

        val editedCurve = curve.copy(enabled = true, points = curve.points.map { it.copy(db = -60) })
        val draft = NativeOnboardingDraft.restore(configuration, null).apply {
            oneShotEnabled = false
            outputCompression = true
            skipSilence = true
            compressionLevel = 2
            silenceConfig = editedCurve
            silenceHistogram = histogram
        }
        val freshAudio = audio.copy(flacCompression = 8,
            silenceConfig = curve.copy(points = curve.points.map { it.copy(db = -70) }))
        val restored = NativeOnboardingDraft.restore(configuration.copy(audio = freshAudio), parcel(draft.save()))
        check(!restored.oneShotEnabled && restored.loopingEnabled)
        check(restored.originalAudio == audio)
        check(restored.selectedAudio == draft.selectedAudio)
        check(resolveOnboardingAudioSelection(restored.originalAudio, restored.selectedAudio,
            ExportFormat.WAV, requireNotNull(freshAudio.silenceConfig), PcmSampleFormat.PCM_16,
            8, null, input) == null) // Preserve concurrent-curve conflict detection after recreation.
        passed += "full_editor_and_baseline_parcel_roundtrip"

        val accepted = requireNotNull(resolveOnboardingAudioSelection(restored.originalAudio,
            restored.selectedAudio, ExportFormat.WAV, curve, PcmSampleFormat.PCM_16, 5, null, input))
        check(accepted.outputFormat == ExportFormat.FLAC && accepted.flacCompression == 2)
        check(accepted.silenceConfig == editedCurve && accepted.silenceHistogram == histogram)
        passed += "restored_edits_commit_together"

        val partial = NativeOnboardingDraft.restore(configuration, null).apply { compressionLevel = 1 }
        partial.refreshUntouchedAudio(freshAudio.copy(outputCompressionEnabled = true,
            skipSilenceEnabled = true, silenceHistogram = histogram))
        check(partial.compressionLevel == 1 && partial.originalAudio.flacCompression == 5)
        check(partial.outputCompression && partial.skipSilence)
        check(partial.silenceConfig == freshAudio.silenceConfig && partial.silenceHistogram == histogram)
        check(partial.originalAudio.silenceConfig == freshAudio.silenceConfig)
        val partialRestored = NativeOnboardingDraft.restore(configuration, parcel(partial.save()))
        check(partialRestored.selectedAudio == partial.selectedAudio)
        check(partialRestored.originalAudio == partial.originalAudio)
        passed += "untouched_fields_refresh_without_losing_level_edit"

        val legacy = Bundle().apply {
            putBoolean("original_compression", false)
            putBoolean("original_silence", false)
            putBoolean("compression", true)
            putBoolean("silence", true)
            putInt("level", 1)
            putString("curve", encodeSilenceGate(editedCurve))
            putString("histogram", histogram)
        }
        val migrated = NativeOnboardingDraft.restore(configuration.copy(audio = freshAudio), parcel(legacy))
        check(migrated.outputCompression && migrated.skipSilence)
        check(migrated.compressionLevel == freshAudio.flacCompression)
        check(migrated.silenceConfig == freshAudio.silenceConfig)
        check(migrated.silenceHistogram == freshAudio.silenceHistogram)
        check(migrated.originalAudio.flacCompression == freshAudio.flacCompression)
        passed += "unversioned_draft_migrates_only_released_switches"
        return passed
    }

    private fun parcel(value: Bundle): Bundle {
        val written = Parcel.obtain()
        val read = Parcel.obtain()
        try {
            written.writeBundle(value)
            val bytes = written.marshall()
            read.unmarshall(bytes, 0, bytes.size)
            read.setDataPosition(0)
            return checkNotNull(read.readBundle(NativeOnboardingDraftAssertions::class.java.classLoader))
        } finally {
            written.recycle()
            read.recycle()
        }
    }
}
