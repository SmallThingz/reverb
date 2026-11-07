package app.smallthingz.reverb

/** One-shot configuration retains its pause because resizing can change automatic handoff/fullness. */
internal fun captureSettingsMayResumeLoopingInput(
    retention: RetentionConfiguration,
    loopingEnabled: Boolean,
    oneShotEnabled: Boolean,
    frameBytes: Int,
    inputConfigurationChanged: Boolean,
    silenceGateChanged: Boolean,
    recordingLooping: Boolean,
    oneShotRetentionUnchanged: Boolean,
): Boolean {
    if (inputConfigurationChanged || silenceGateChanged || !recordingLooping || !oneShotRetentionUnchanged) return false
    val loopingValue = when (retention.mode) {
        RetentionMode.SIZE -> retention.loopingSizeBytes
        RetentionMode.TIME -> retention.loopingSeconds
    }
    val oneShotValue = when (retention.mode) {
        RetentionMode.SIZE -> retention.oneShotSizeBytes
        RetentionMode.TIME -> retention.oneShotSeconds
    }
    return loopingEnabled && normalizeRetentionValue(retention.mode, loopingValue, frameBytes) > 0L &&
        (normalizeRetentionValue(retention.mode, oneShotValue, frameBytes) > 0L) == oneShotEnabled
}
