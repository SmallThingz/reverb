package app.smallthingz.reverb

internal fun recordingReadIdentityIsStable(recording: RecordingEntity): Boolean =
    recording.fileIdentity.isNotBlank()
