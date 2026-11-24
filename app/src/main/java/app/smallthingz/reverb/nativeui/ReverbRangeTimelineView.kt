package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import app.smallthingz.reverb.NativeRangeExportPresentation
import app.smallthingz.reverb.RangeExportEditorState

internal class ReverbRangeTimelineView(
    context: Context,
    environment: ReverbViewEnvironment,
    onWaveformPositioned: (View) -> Unit,
) : smallthingz.ui.reverb.ReverbRangeTimelineView(context, environment, onWaveformPositioned) {
    fun bind(state: RangeExportEditorState, frame: NativeRangeExportPresentation) =
        bind(state.reverbRangeSnapshot(false), frame)
}
