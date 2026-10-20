package app.smallthingz.reverb

import android.content.Context

internal data class OnboardingAudioSelection(
    val outputCompressionEnabled: Boolean,
    val skipSilenceEnabled: Boolean,
)

internal data class OnboardingAudioCommit(
    val outputFormat: ExportFormat,
    val silenceConfig: SilenceGateConfig,
)

internal data class OnboardingSilenceConfiguration(
    val config: SilenceGateConfig,
    val valid: Boolean,
)

/** Invalid optional curves stay visible as an error; only explicit Finish may repair them. */
internal fun decodeOnboardingSilence(rawPreferences: Map<String, *>): OnboardingSilenceConfiguration {
    if (!rawPreferences.containsKey(PrefKey.SILENCE_GATE.name)) {
        return OnboardingSilenceConfiguration(SilenceGateConfig(), valid = true)
    }
    val encoded = rawPreferences[PrefKey.SILENCE_GATE.name] as? String
    val config = if (encoded == null) null else try {
        decodeSilenceGate(encoded)
    } catch (_: IllegalArgumentException) {
        null
    }
    return OnboardingSilenceConfiguration(config ?: SilenceGateConfig(), valid = config != null)
}

/** Untouched choices follow fresh durable state; editing a switch owns only that choice. */
internal fun resolveOnboardingAudioSelection(
    original: OnboardingAudioSelection,
    selected: OnboardingAudioSelection,
    currentFormat: ExportFormat,
    currentSilence: SilenceGateConfig,
    sampleFormat: PcmSampleFormat,
): OnboardingAudioCommit? {
    val format = if (selected.outputCompressionEnabled == original.outputCompressionEnabled) {
        currentFormat
    } else if (selected.outputCompressionEnabled) {
        ExportFormat.FLAC
    } else {
        ExportFormat.WAV
    }
    if (format == ExportFormat.FLAC && sampleFormat != PcmSampleFormat.PCM_16) return null
    val silenceEnabled = if (selected.skipSilenceEnabled == original.skipSilenceEnabled) {
        currentSilence.enabled
    } else {
        selected.skipSilenceEnabled
    }
    return OnboardingAudioCommit(format, currentSilence.copy(enabled = silenceEnabled))
}

internal data class OnboardingConfiguration(
    val buffers: ConfiguredBufferAvailability,
    val audio: OnboardingAudioSelection,
    val compressionAvailable: Boolean,
    val silenceValid: Boolean,
)

internal fun getOnboardingConfiguration(context: Context): OnboardingConfiguration =
    withRetentionPersistenceLock {
        val silence = SilenceGatePreferences.readForOnboarding(context)
        OnboardingConfiguration(
            buffers = getConfiguredBufferAvailability(context),
            audio = OnboardingAudioSelection(
                outputCompressionEnabled = getConfiguredOutputFormat(context) == ExportFormat.FLAC,
                skipSilenceEnabled = silence.config.enabled,
            ),
            compressionAvailable = getConfiguredPcmSampleFormat(context) == PcmSampleFormat.PCM_16,
            silenceValid = silence.valid,
        )
    }
