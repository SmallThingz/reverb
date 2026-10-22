package app.smallthingz.reverb

import android.content.Context

internal data class OnboardingAudioSelection(
    val outputCompressionEnabled: Boolean,
    val skipSilenceEnabled: Boolean,
    val flacCompression: Int? = null,
    val silenceConfig: SilenceGateConfig? = null,
    val silenceHistogram: String? = null,
)

internal data class OnboardingAudioCommit(
    val outputFormat: ExportFormat,
    val silenceConfig: SilenceGateConfig,
    val flacCompression: Int = DEFAULT_FLAC_COMPRESSION,
    val silenceHistogram: String? = null,
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

/** New choices default on; a stored false is still an explicit existing choice. */
internal fun initialOnboardingAudioSelection(
    original: OnboardingAudioSelection,
    outputPreferencePresent: Boolean,
    silencePreferencePresent: Boolean,
    compressionAvailable: Boolean,
): OnboardingAudioSelection = original.copy(
    outputCompressionEnabled = original.outputCompressionEnabled ||
        (!outputPreferencePresent && compressionAvailable),
    skipSilenceEnabled = original.skipSilenceEnabled || !silencePreferencePresent,
)

/** Untouched fields follow fresh durable state; Finish owns only the edited fields. */
internal fun resolveOnboardingAudioSelection(
    original: OnboardingAudioSelection,
    selected: OnboardingAudioSelection,
    currentFormat: ExportFormat,
    currentSilence: SilenceGateConfig,
    sampleFormat: PcmSampleFormat,
    currentCompression: Int = DEFAULT_FLAC_COMPRESSION,
    currentHistogram: String? = null,
    currentInput: SilenceCalibrationInput? = null,
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
    val compression = selected.flacCompression?.takeIf { it != original.flacCompression }
        ?: currentCompression
    if (!validFlacCompression(compression)) return null

    val selectedCurve = selected.silenceConfig
    val curveChanged = selectedCurve != null && selectedCurve.points != original.silenceConfig?.points
    if (curveChanged && original.silenceConfig != null &&
        currentSilence.points != original.silenceConfig.points && currentSilence.points != selectedCurve?.points
    ) return null
    val curve = if (curveChanged) checkNotNull(selectedCurve) else currentSilence
    val histogramChanged = selected.silenceHistogram != original.silenceHistogram
    val histogram = if (histogramChanged) selected.silenceHistogram else currentHistogram
    val measured = decodeSilenceCalibrationHistogram(histogram)
    if (histogramChanged && histogram != null && measured == null) return null
    if (histogramChanged && measured != null && currentInput != null && measured.input != currentInput) return null
    // Validate the effective merged selection, not a stale untouched draft measurement.
    // Legacy callers without a curve retain their original two-switch merge contract.
    if (silenceEnabled && selectedCurve != null) {
        if (measured == null || (currentInput != null && measured.input != currentInput)) return null
    }
    if (curveChanged && currentInput != null &&
        decodeSilenceCalibrationHistogram(selected.silenceHistogram)?.input != currentInput
    ) return null
    return OnboardingAudioCommit(format, curve.copy(enabled = silenceEnabled), compression, histogram)
}

internal data class OnboardingConfiguration(
    val buffers: ConfiguredBufferAvailability,
    val audio: OnboardingAudioSelection,
    val compressionAvailable: Boolean,
    val silenceValid: Boolean,
    val initialAudio: OnboardingAudioSelection = audio,
    val input: SilenceCalibrationInput? = null,
)

internal fun getOnboardingConfiguration(context: Context): OnboardingConfiguration =
    withRetentionPersistenceLock {
        val silence = SilenceGatePreferences.readForOnboarding(context)
        val preferences = getRecorderPreferences(context)
        val input = readSilenceCalibrationInput(context)
        val audio = OnboardingAudioSelection(
            outputCompressionEnabled = getConfiguredOutputFormat(context) == ExportFormat.FLAC,
            skipSilenceEnabled = silence.config.enabled,
            flacCompression = getConfiguredFlacCompression(context),
            silenceConfig = silence.config,
            silenceHistogram = preferences.safeString(PrefKey.SILENCE_CALIBRATION_HISTOGRAM),
        )
        val compressionAvailable = input.sampleFormat == PcmSampleFormat.PCM_16
        OnboardingConfiguration(
            buffers = getConfiguredBufferAvailability(context),
            audio = audio,
            compressionAvailable = compressionAvailable,
            silenceValid = silence.valid,
            initialAudio = initialOnboardingAudioSelection(audio,
                preferences.contains(PrefKey.OUTPUT_FORMAT), preferences.contains(PrefKey.SILENCE_GATE),
                compressionAvailable),
            input = input,
        )
    }

/** The footer never converts an unfinished or failed sample into a successful Finish. */
internal enum class OnboardingAudioPrimaryAction { CALIBRATE, WAIT, FINISH }
internal fun onboardingAudioPrimaryAction(calibrationRequired: Boolean, calibrating: Boolean): OnboardingAudioPrimaryAction =
    when {
        calibrating -> OnboardingAudioPrimaryAction.WAIT
        calibrationRequired -> OnboardingAudioPrimaryAction.CALIBRATE
        else -> OnboardingAudioPrimaryAction.FINISH
    }
