package app.smallthingz.reverb

import android.os.Bundle

/** Onboarding edits stay in a restorable draft until the existing atomic Finish transaction. */
internal class NativeOnboardingDraft private constructor(
    var oneShotEnabled: Boolean,
    var loopingEnabled: Boolean,
    originalAudio: OnboardingAudioSelection,
    var outputCompression: Boolean,
    var skipSilence: Boolean,
) {
    var originalAudio: OnboardingAudioSelection = originalAudio
        private set
    val selectedAudio: OnboardingAudioSelection
        get() = originalAudio.copy(outputCompressionEnabled = outputCompression, skipSilenceEnabled = skipSilence)

    /** Fresh durable changes replace only fields this draft has not edited. */
    fun refreshUntouchedAudio(fresh: OnboardingAudioSelection) {
        val original = originalAudio
        val outputUntouched = outputCompression == original.outputCompressionEnabled
        val skipUntouched = skipSilence == original.skipSilenceEnabled
        if (outputUntouched) outputCompression = fresh.outputCompressionEnabled
        if (skipUntouched) skipSilence = fresh.skipSilenceEnabled
        originalAudio = fresh.copy(
            outputCompressionEnabled = if (outputUntouched) fresh.outputCompressionEnabled else original.outputCompressionEnabled,
            skipSilenceEnabled = if (skipUntouched) fresh.skipSilenceEnabled else original.skipSilenceEnabled,
        )
    }

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
                originalAudio = restoreOnboardingAudioSelection(configuration.audio, OnboardingAudioSelection(
                    value(ORIGINAL_COMPRESSION, configuration.audio.outputCompressionEnabled),
                    value(ORIGINAL_SILENCE, configuration.audio.skipSilenceEnabled),
                )),
                outputCompression = value(COMPRESSION, configuration.audio.outputCompressionEnabled),
                skipSilence = value(SILENCE, configuration.audio.skipSilenceEnabled),
            )
        }
    }
}
