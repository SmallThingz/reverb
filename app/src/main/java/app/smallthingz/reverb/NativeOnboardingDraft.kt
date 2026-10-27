package app.smallthingz.reverb

import android.os.Bundle

/** Onboarding edits stay in a restorable draft until the existing atomic Finish transaction. */
internal class NativeOnboardingDraft private constructor(
    var oneShotEnabled: Boolean,
    var loopingEnabled: Boolean,
    originalAudio: OnboardingAudioSelection,
    var outputCompression: Boolean,
    var skipSilence: Boolean,
    var compressionLevel: Int,
    var silenceConfig: SilenceGateConfig,
    var silenceHistogram: String?,
) {
    var originalAudio: OnboardingAudioSelection = originalAudio
        private set
    val selectedAudio: OnboardingAudioSelection
        get() = OnboardingAudioSelection(outputCompression, skipSilence, compressionLevel,
            silenceConfig.copy(enabled = skipSilence), silenceHistogram)

    /** Fresh durable changes replace only fields this draft has not edited. */
    fun refreshUntouchedAudio(fresh: OnboardingAudioSelection) {
        val original = originalAudio
        val outputUntouched = outputCompression == original.outputCompressionEnabled
        val skipUntouched = skipSilence == original.skipSilenceEnabled
        val levelUntouched = compressionLevel == original.flacCompression
        val curveUntouched = silenceConfig.points == original.silenceConfig?.points
        val histogramUntouched = silenceHistogram == original.silenceHistogram
        if (outputUntouched) outputCompression = fresh.outputCompressionEnabled
        if (skipUntouched) skipSilence = fresh.skipSilenceEnabled
        if (levelUntouched) compressionLevel = fresh.flacCompression ?: DEFAULT_FLAC_COMPRESSION
        if (curveUntouched) silenceConfig = fresh.silenceConfig ?: SilenceGateConfig()
        if (histogramUntouched) silenceHistogram = fresh.silenceHistogram
        originalAudio = fresh.copy(
            outputCompressionEnabled = if (outputUntouched) fresh.outputCompressionEnabled else original.outputCompressionEnabled,
            skipSilenceEnabled = if (skipUntouched) fresh.skipSilenceEnabled else original.skipSilenceEnabled,
            flacCompression = if (levelUntouched) fresh.flacCompression else original.flacCompression,
            silenceConfig = if (curveUntouched) fresh.silenceConfig else original.silenceConfig,
            silenceHistogram = if (histogramUntouched) fresh.silenceHistogram else original.silenceHistogram,
        )
    }

    fun save(): Bundle = Bundle().apply {
        putBoolean(ONE_SHOT, oneShotEnabled)
        putBoolean(LOOPING, loopingEnabled)
        putBoolean(ORIGINAL_COMPRESSION, originalAudio.outputCompressionEnabled)
        putBoolean(ORIGINAL_SILENCE, originalAudio.skipSilenceEnabled)
        putBoolean(COMPRESSION, outputCompression)
        putBoolean(SILENCE, skipSilence)
        putInt(AUDIO_EDITOR_VERSION, 1)
        putInt(ORIGINAL_LEVEL, originalAudio.flacCompression ?: DEFAULT_FLAC_COMPRESSION)
        putString(ORIGINAL_CURVE, encodeSilenceGate(originalAudio.silenceConfig ?: SilenceGateConfig()))
        putString(ORIGINAL_HISTOGRAM, originalAudio.silenceHistogram)
        putInt(LEVEL, compressionLevel)
        putString(CURVE, encodeSilenceGate(silenceConfig))
        putString(HISTOGRAM, silenceHistogram)
    }

    companion object {
        private const val ONE_SHOT = "one_shot"
        private const val LOOPING = "looping"
        private const val ORIGINAL_COMPRESSION = "original_compression"
        private const val ORIGINAL_SILENCE = "original_silence"
        private const val COMPRESSION = "compression"
        private const val SILENCE = "silence"
        private const val AUDIO_EDITOR_VERSION = "audio_editor_version"
        private const val ORIGINAL_LEVEL = "original_level"
        private const val ORIGINAL_CURVE = "original_curve"
        private const val ORIGINAL_HISTOGRAM = "original_histogram"
        private const val LEVEL = "level"
        private const val CURVE = "curve"
        private const val HISTOGRAM = "histogram"

        fun restore(configuration: OnboardingConfiguration, saved: Bundle?): NativeOnboardingDraft {
            fun value(key: String, initial: Boolean): Boolean =
                if (saved?.containsKey(key) == true) saved.getBoolean(key) else initial
            val audio = configuration.audio
            val hasEditors = saved?.getInt(AUDIO_EDITOR_VERSION) == 1
            fun level(key: String): Int = if (hasEditors && saved?.containsKey(key) == true)
                saved.getInt(key).takeIf(::validFlacCompression) ?: (audio.flacCompression ?: DEFAULT_FLAC_COMPRESSION)
                else audio.flacCompression ?: DEFAULT_FLAC_COMPRESSION
            fun curve(key: String): SilenceGateConfig = (if (hasEditors) {
                try { saved?.getString(key)?.let(::decodeSilenceGate) } catch (_: IllegalArgumentException) { null }
            } else null) ?: audio.silenceConfig ?: SilenceGateConfig()
            fun histogram(key: String): String? = if (hasEditors && saved?.containsKey(key) == true)
                saved.getString(key)?.takeIf { decodeSilenceCalibrationHistogram(it) != null }
                else audio.silenceHistogram
            return NativeOnboardingDraft(
                oneShotEnabled = value(ONE_SHOT,
                    configuration.buffers.oneShotEnabled || !configuration.buffers.loopingEnabled),
                loopingEnabled = value(LOOPING, configuration.buffers.loopingEnabled),
                originalAudio = restoreOnboardingAudioSelection(configuration.audio, OnboardingAudioSelection(
                    value(ORIGINAL_COMPRESSION, configuration.audio.outputCompressionEnabled),
                    value(ORIGINAL_SILENCE, configuration.audio.skipSilenceEnabled),
                )).copy(flacCompression = level(ORIGINAL_LEVEL), silenceConfig = curve(ORIGINAL_CURVE),
                    silenceHistogram = histogram(ORIGINAL_HISTOGRAM)),
                outputCompression = value(COMPRESSION, configuration.audio.outputCompressionEnabled),
                skipSilence = value(SILENCE, configuration.audio.skipSilenceEnabled),
                compressionLevel = level(LEVEL),
                silenceConfig = curve(CURVE),
                silenceHistogram = histogram(HISTOGRAM),
            )
        }
    }
}
