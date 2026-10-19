package app.smallthingz.reverb

import android.content.Context

/** Only the input that determines the measured room profile; no UI or microphone ownership. */
internal data class SilenceCalibrationInput(
    val sampleRate: Int,
    val sampleFormat: PcmSampleFormat,
    val channelMode: ChannelMode?,
    val source: AudioSourceMode?,
    val route: InputRouteMode?,
) {
    fun matches(configured: ReverbService.RecorderConfigurationSnapshot): Boolean =
        sampleRate == configured.sampleRate && sampleFormat == configured.sampleFormat &&
            channelMode == configured.channelMode && source == configured.sourceMode && route == configured.routeMode
}

internal fun SettingsSnapshot.silenceCalibrationInput() = SilenceCalibrationInput(
    sampleRate, sampleFormat, channelMode, source, route,
)

internal fun readSilenceCalibrationInput(context: Context) = SilenceCalibrationInput(
    getConfiguredSampleRate(context), getConfiguredPcmSampleFormat(context),
    getConfiguredChannelMode(context), getConfiguredAudioSourceMode(context), getConfiguredInputRouteMode(context),
)

/** A tile is usable immediately, so an unapplied Settings preview cannot authorize its duration. */
internal fun settingsTileCapacityDraftChanged(
    baseline: SettingsSnapshot,
    draft: SettingsSnapshot,
    oneShotTime: String,
    loopingTime: String,
    oneShotSize: String,
    loopingSize: String,
): Boolean {
    if (draft.format != baseline.format || draft.codec != baseline.codec ||
        draft.sampleRate != baseline.sampleRate || draft.sampleFormat != baseline.sampleFormat ||
        draft.channelMode != baseline.channelMode || draft.retentionMode != baseline.retentionMode) return true
    return when (draft.retentionMode) {
        RetentionMode.TIME -> parseRetentionTimeSeconds(oneShotTime) == null ||
            parseRetentionTimeSeconds(loopingTime) == null ||
            draft.oneShotRetentionTime != baseline.oneShotRetentionTime ||
            draft.loopingRetentionTime != baseline.loopingRetentionTime
        RetentionMode.SIZE -> parseRetentionSizeMib(oneShotSize)?.takeIf { it >= 0.0 } == null ||
            parseRetentionSizeMib(loopingSize)?.takeIf { it >= 0.0 } == null ||
            draft.oneShotRetentionSizeBytes != baseline.oneShotRetentionSizeBytes ||
            draft.loopingRetentionSizeBytes != baseline.loopingRetentionSizeBytes
    }
}
