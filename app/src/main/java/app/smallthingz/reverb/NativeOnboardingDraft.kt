package app.smallthingz.reverb

import android.os.Bundle

/**
 * Exactly the six rememberSaveable values in released MainActivity.OnboardingScreen:559–564.
 * Initial one-shot repair applies only on initialization; restored values are not silently rewritten.
 * The original audio values are retained separately so untouched choices follow fresh durable state.
 */
internal class NativeOnboardingDraft private constructor(
    var oneShotEnabled: Boolean,
    var loopingEnabled: Boolean,
    val originalAudio: OnboardingAudioSelection,
    var outputCompression: Boolean,
    var skipSilence: Boolean,
) {
    val selectedAudio: OnboardingAudioSelection
        get() = OnboardingAudioSelection(outputCompression, skipSilence)

    fun save(): Bundle = Bundle().apply {
        putBoolean(ONE_SHOT, oneShotEnabled)
        putBoolean(LOOPING, loopingEnabled)
        putBoolean(ORIGINAL_COMPRESSION, originalAudio.outputCompressionEnabled)
        putBoolean(ORIGINAL_SILENCE, originalAudio.skipSilenceEnabled)
        putBoolean(COMPRESSION, outputCompression)
        putBoolean(SILENCE, skipSilence)
    }

    companion object {
        private const val ONE_SHOT = "one_shot"
        private const val LOOPING = "looping"
        private const val ORIGINAL_COMPRESSION = "original_compression"
        private const val ORIGINAL_SILENCE = "original_silence"
        private const val COMPRESSION = "compression"
        private const val SILENCE = "silence"

        fun restore(configuration: OnboardingConfiguration, saved: Bundle?): NativeOnboardingDraft {
            fun value(key: String, initial: Boolean): Boolean =
                if (saved?.containsKey(key) == true) saved.getBoolean(key) else initial
            return NativeOnboardingDraft(
                oneShotEnabled = value(ONE_SHOT,
                    configuration.buffers.oneShotEnabled || !configuration.buffers.loopingEnabled),
                loopingEnabled = value(LOOPING, configuration.buffers.loopingEnabled),
                originalAudio = OnboardingAudioSelection(
                    value(ORIGINAL_COMPRESSION, configuration.audio.outputCompressionEnabled),
                    value(ORIGINAL_SILENCE, configuration.audio.skipSilenceEnabled),
                ),
                outputCompression = value(COMPRESSION, configuration.audio.outputCompressionEnabled),
                skipSilence = value(SILENCE, configuration.audio.skipSilenceEnabled),
            )
        }
    }
}
