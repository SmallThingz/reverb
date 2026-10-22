package app.smallthingz.reverb

import android.os.Bundle

/** Onboarding edits stay in a restorable draft until the existing atomic Finish transaction. */
internal class NativeOnboardingDraft private constructor(
    var oneShotEnabled: Boolean,
    var loopingEnabled: Boolean,
    originalAudio: OnboardingAudioSelection,
    var outputCompression: Boolean,
    skipSilence: Boolean,
    var compressionLevel: Int,
    var silenceConfig: SilenceGateConfig,
    silenceHistogram: String?,
) {
    var originalAudio: OnboardingAudioSelection = originalAudio
        private set
    var skipSilence: Boolean = skipSilence
        set(value) {
            field = value
            if (silenceConfig.enabled != value) silenceConfig = silenceConfig.copy(enabled = value)
        }
    init {
        if (silenceConfig.enabled != skipSilence) silenceConfig = silenceConfig.copy(enabled = skipSilence)
    }
    var silenceHistogram: String? = silenceHistogram
        set(value) {
            if (field == value) return
            field = value
            histogram = decodeSilenceCalibrationHistogram(value)
        }
    var histogram: SilenceCalibrationHistogram? = decodeSilenceCalibrationHistogram(silenceHistogram)
        private set
    val currentSilence: SilenceGateConfig
        get() = if (silenceConfig.enabled == skipSilence) silenceConfig else silenceConfig.copy(enabled = skipSilence)
    val selectedAudio: OnboardingAudioSelection
        get() = OnboardingAudioSelection(outputCompression, skipSilence, compressionLevel,
            currentSilence, silenceHistogram)

    fun calibrationRequired(input: SilenceCalibrationInput?): Boolean =
        skipSilence && (input == null || histogram?.input != input)

    /** Fresh durable changes replace only fields this draft has not edited. */
    fun refreshUntouchedAudio(fresh: OnboardingAudioSelection) {
        val original = originalAudio
        val outputUntouched = outputCompression == original.outputCompressionEnabled
        val skipUntouched = skipSilence == original.skipSilenceEnabled
        val levelUntouched = compressionLevel == original.flacCompression
        val profileUntouched = silenceConfig.points == original.silenceConfig?.points &&
            silenceHistogram == original.silenceHistogram
        if (outputUntouched) outputCompression = fresh.outputCompressionEnabled
        if (skipUntouched) skipSilence = fresh.skipSilenceEnabled
        if (levelUntouched) fresh.flacCompression?.let { compressionLevel = it }
        if (profileUntouched) {
            silenceConfig = (fresh.silenceConfig ?: SilenceGateConfig()).copy(enabled = skipSilence)
            silenceHistogram = fresh.silenceHistogram
        }
        originalAudio = original.copy(
            outputCompressionEnabled = if (outputUntouched) fresh.outputCompressionEnabled else original.outputCompressionEnabled,
            skipSilenceEnabled = if (skipUntouched) fresh.skipSilenceEnabled else original.skipSilenceEnabled,
            flacCompression = if (levelUntouched) fresh.flacCompression else original.flacCompression,
            silenceConfig = if (profileUntouched) fresh.silenceConfig else original.silenceConfig,
            silenceHistogram = if (profileUntouched) fresh.silenceHistogram else original.silenceHistogram,
        )
    }

    /** A new accepted measurement owns the fresh profile sampled when that explicit attempt began. */
    fun calibrated(config: SilenceGateConfig, measured: SilenceCalibrationHistogram, baseline: OnboardingAudioSelection) {
        originalAudio = originalAudio.copy(silenceConfig = baseline.silenceConfig,
            silenceHistogram = baseline.silenceHistogram)
        silenceConfig = config
        skipSilence = config.enabled
        silenceHistogram = encodeSilenceCalibrationHistogram(measured)
    }

    fun save(): Bundle = Bundle().apply {
        putBoolean(ONE_SHOT, oneShotEnabled)
        putBoolean(LOOPING, loopingEnabled)
        putBoolean(ORIGINAL_COMPRESSION, originalAudio.outputCompressionEnabled)
        putBoolean(ORIGINAL_SILENCE, originalAudio.skipSilenceEnabled)
        originalAudio.flacCompression?.let { putInt(ORIGINAL_LEVEL, it) }
        originalAudio.silenceConfig?.let { putString(ORIGINAL_CURVE, encodeSilenceGate(it)) }
        putString(ORIGINAL_HISTOGRAM, originalAudio.silenceHistogram)
        putBoolean(COMPRESSION, outputCompression)
        putBoolean(SILENCE, skipSilence)
        putInt(LEVEL, compressionLevel)
        putString(CURVE, encodeSilenceGate(currentSilence))
        putString(HISTOGRAM, silenceHistogram)
    }

    companion object {
        private const val ONE_SHOT = "one_shot"
        private const val LOOPING = "looping"
        private const val ORIGINAL_COMPRESSION = "original_compression"
        private const val ORIGINAL_SILENCE = "original_silence"
        private const val ORIGINAL_LEVEL = "original_compression_level"
        private const val ORIGINAL_CURVE = "original_silence_curve"
        private const val ORIGINAL_HISTOGRAM = "original_silence_histogram"
        private const val COMPRESSION = "compression"
        private const val SILENCE = "silence"
        private const val LEVEL = "compression_level"
        private const val CURVE = "silence_curve"
        private const val HISTOGRAM = "silence_histogram"

        fun restore(configuration: OnboardingConfiguration, saved: Bundle?): NativeOnboardingDraft {
            fun value(key: String, initial: Boolean): Boolean =
                if (saved?.containsKey(key) == true) saved.getBoolean(key) else initial
            fun level(key: String, initial: Int?): Int =
                (if (saved?.containsKey(key) == true) saved.getInt(key) else initial)
                    ?.takeIf(::validFlacCompression) ?: DEFAULT_FLAC_COMPRESSION
            fun text(key: String, initial: String?): String? =
                if (saved?.containsKey(key) == true) saved.getString(key) else initial
            fun curve(key: String, initial: SilenceGateConfig?): SilenceGateConfig {
                val encoded = saved?.getString(key) ?: return initial ?: SilenceGateConfig()
                return try { decodeSilenceGate(encoded) } catch (_: IllegalArgumentException) {
                    initial ?: SilenceGateConfig()
                }
            }
            return NativeOnboardingDraft(
                oneShotEnabled = value(ONE_SHOT,
                    configuration.buffers.oneShotEnabled || !configuration.buffers.loopingEnabled),
                loopingEnabled = value(LOOPING, configuration.buffers.loopingEnabled),
                originalAudio = OnboardingAudioSelection(
                    value(ORIGINAL_COMPRESSION, configuration.audio.outputCompressionEnabled),
                    value(ORIGINAL_SILENCE, configuration.audio.skipSilenceEnabled),
                    level(ORIGINAL_LEVEL, configuration.audio.flacCompression),
                    curve(ORIGINAL_CURVE, configuration.audio.silenceConfig),
                    text(ORIGINAL_HISTOGRAM, configuration.audio.silenceHistogram),
                ),
                outputCompression = value(COMPRESSION, configuration.initialAudio.outputCompressionEnabled),
                skipSilence = value(SILENCE, configuration.initialAudio.skipSilenceEnabled),
                compressionLevel = level(LEVEL, configuration.audio.flacCompression),
                silenceConfig = curve(CURVE, configuration.audio.silenceConfig),
                silenceHistogram = text(HISTOGRAM, configuration.audio.silenceHistogram),
            )
        }
    }
}
