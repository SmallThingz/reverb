package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import app.smallthingz.reverb.RangeExportEditorState
import app.smallthingz.reverb.ReverbService
import kotlinx.coroutines.CoroutineScope

internal class ReverbRangeExportControls(
    context: Context,
    environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    scope: CoroutineScope,
) : smallthingz.ui.reverb.ReverbRangeExportControls(context, environment, inputRoot, scope, reverbRangeLabels) {
    fun bind(environment: ReverbViewEnvironment, state: RangeExportEditorState, loading: Boolean,
             canExport: Boolean, selectedBuffer: ReverbService.BufferSlot, onCancel: () -> Unit,
             onExport: () -> Unit, canClose: Boolean = true) {
        bind(environment, loading, !loading && state.snapshotReady && canExport,
            state.selectionDurationEditing, selectedBuffer == ReverbService.BufferSlot.ONE_SHOT,
            onCancel, onExport, canClose)
    }
}
