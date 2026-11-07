package app.smallthingz.reverb

internal enum class RecordingPlaybackPhase {
    PREPARING, PLAYING, PAUSED;

    val offersPause: Boolean get() = this != PAUSED
}

internal fun recordingPlaybackPhase(playing: Boolean, wantsPlayback: Boolean): RecordingPlaybackPhase =
    when {
        playing -> RecordingPlaybackPhase.PLAYING
        wantsPlayback -> RecordingPlaybackPhase.PREPARING
        else -> RecordingPlaybackPhase.PAUSED
    }
