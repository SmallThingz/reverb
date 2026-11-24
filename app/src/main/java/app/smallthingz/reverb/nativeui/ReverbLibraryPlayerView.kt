package app.smallthingz.reverb.nativeui

internal typealias LibraryPlayerUiState = smallthingz.ui.reverb.LibraryPlayerUiState
internal typealias LibraryPlayerActions = smallthingz.ui.reverb.LibraryPlayerActions
internal typealias PlayerTimeline = smallthingz.ui.reverb.PlayerTimeline

internal typealias ReverbLibraryPlayerView = smallthingz.ui.reverb.ReverbLibraryPlayerView<SpringFineSeekBinding>

internal val reverbPlayerLabels = smallthingz.ui.reverb.ReverbPlayerLabels(
    app.smallthingz.reverb.R.string.trim_recording,
    app.smallthingz.reverb.R.string.cancel,
    app.smallthingz.reverb.R.string.trim_saving,
    app.smallthingz.reverb.R.string.trim_save_copy,
    app.smallthingz.reverb.R.string.recording_playback_preparing,
    app.smallthingz.reverb.R.string.trim_start_accessibility,
    app.smallthingz.reverb.R.string.trim_end_accessibility,
)

internal fun ReverbLibraryPlayerView(
    context: android.content.Context,
    environment: ReverbViewEnvironment,
    fineSeekView: ReverbSpringFineSeekView,
    nativeInputRoot: android.view.View,
    frames: smallthingz.ui.runtime.MonotonicFrameClock,
    scope: kotlinx.coroutines.CoroutineScope,
    durationScale: () -> Float,
): ReverbLibraryPlayerView = smallthingz.ui.reverb.ReverbLibraryPlayerView(
    context, environment, fineSeekView, nativeInputRoot, frames, scope, reverbPlayerLabels, durationScale,
)

internal fun libraryPlayerTimeDisplayUnit(millis: Int, trimMode: Boolean): Long =
    smallthingz.ui.reverb.libraryPlayerTimeDisplayUnit(millis, trimMode)
