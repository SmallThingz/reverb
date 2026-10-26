package app.smallthingz.reverb

/** Unedited output follows the latest durable preference; an explicit edit owns its chosen format. */
internal fun commitSettingsForSelectedOutput(
    originalFormat: ExportFormat,
    selectedFormat: ExportFormat,
    readCurrentFormat: () -> ExportFormat,
    codec: ExportCodec,
    sampleRate: Int,
    channelMode: ChannelMode,
    sampleFormat: PcmSampleFormat,
    commit: (ExportFormat) -> Boolean,
): Boolean {
    val output = if (selectedFormat == originalFormat) readCurrentFormat() else selectedFormat
    if (output == ExportFormat.FLAC && sampleFormat != PcmSampleFormat.PCM_16) return false
    if (!isCodecSupported(output, codec, sampleRate, channelMode)) return false
    return commit(output)
}

/** A later output edit or Undo owns its value even when it returns to the submitted selection. */
internal fun reconcileSettingsOutputAfterCommit(
    currentFormat: ExportFormat,
    committedFormat: ExportFormat,
    submittedOutputRevision: Long,
    currentOutputRevision: Long,
): ExportFormat = if (submittedOutputRevision == currentOutputRevision) committedFormat else currentFormat
