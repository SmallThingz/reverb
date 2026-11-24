package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import androidx.lifecycle.Lifecycle
import app.smallthingz.reverb.NativeRangeExportPresentation
import app.smallthingz.reverb.R
import app.smallthingz.reverb.RangeEditTarget
import app.smallthingz.reverb.RangeExportEditorState
import app.smallthingz.reverb.ReverbService
import app.smallthingz.reverb.rangeEndpointInput
import app.smallthingz.reverb.rangeExportSelectionWithinLimit
import app.smallthingz.reverb.runRangeDurationAction
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.reverb.ReverbRangeDurationActions
import smallthingz.ui.reverb.ReverbRangeEditorSnapshot
import smallthingz.ui.reverb.ReverbRangeExportActions
import smallthingz.ui.reverb.ReverbRangeLabels
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope

internal val reverbRangeLabels = ReverbRangeLabels(
    R.string.close, R.string.buffer_one_shot, R.string.buffer_loop, R.string.preparing_audio_selection,
    R.string.export, R.string.range_export_duration, R.string.player_play, R.string.player_pause,
)

/** Binds the capture editor and accepted work to the UI library's range viewport. */
internal class ReverbRangeExportContent(
    context: Context,
    environment: ReverbViewEnvironment,
    val state: RangeExportEditorState,
    private val lifecycle: Lifecycle,
    scope: CoroutineScope,
    sourceWindow: View,
    inputRoot: () -> View?,
    onWaveformPositioned: (View) -> Unit,
) : smallthingz.ui.reverb.ReverbRangeExportContent(
    context, environment, rangeActions(state, lifecycle), reverbRangeLabels,
    scope, sourceWindow, inputRoot, onWaveformPositioned,
) {
    private var snapshot: ReverbRangeEditorSnapshot? = null
    private var onExport: () -> Unit = {}
    private val exportCurrent = {
        val duration = state.selectionDurationExactSeconds
        if (!state.selectionDurationEditing && duration.isFinite() && duration > 0.0) onExport()
    }

    fun bind(environment: ReverbViewEnvironment, frame: NativeRangeExportPresentation,
             selectedBuffer: ReverbService.BufferSlot, onCancel: () -> Unit, onExport: () -> Unit) {
        this.onExport = onExport
        val canExport = rangeExportSelectionWithinLimit(state.selectionDurationExactSeconds, frame.maximumDurationSeconds)
        val next = state.reverbRangeSnapshot(canExport, snapshot)
        snapshot = next
        bind(environment, frame, next, selectedBuffer == ReverbService.BufferSlot.ONE_SHOT, onCancel, exportCurrent)
    }

    fun bindEndpointAccessibility(window: PlainTextAccessibilityWindowScope,
        sourceNode: (View) -> NativeSourceAccessibilityNode): AutoCloseable = bindEndpointAccessibility(
        window, rangeEndpointInput(state, lifecycle, RangeEditTarget.START),
        rangeEndpointInput(state, lifecycle, RangeEditTarget.END), sourceNode,
    )

}

internal fun RangeExportEditorState.reverbRangeSnapshot(
    canExport: Boolean,
    previous: ReverbRangeEditorSnapshot? = null,
): ReverbRangeEditorSnapshot {
    val anchor = playbackAnchor
    if (previous != null && previous.startSeconds == startSeconds && previous.endSeconds == endSeconds &&
        previous.durationSeconds == durationSeconds && previous.lastTarget == lastTarget &&
        previous.snapshotReady == snapshotReady && previous.durationActionAvailable == durationActionAvailable &&
        previous.selectionDurationEditing == selectionDurationEditing &&
        previous.isPlaying == isPlaying && previous.canExport == canExport && previous.playbackAnchor == anchor) return previous
    return ReverbRangeEditorSnapshot(startSeconds, endSeconds, durationSeconds, lastTarget, snapshotReady,
        durationActionAvailable, selectionDurationEditing, isPlaying, canExport, anchor)
}

private fun rangeActions(state: RangeExportEditorState, lifecycle: Lifecycle) = ReverbRangeExportActions(
    togglePreview = { if (state.durationActionAvailable) state.togglePreview() },
    beginFineAdjust = state::beginFineAdjust,
    fineAdjust = state::fineAdjust,
    updateFineAdjustShuttle = state::updateFineAdjustShuttle,
    endFineAdjust = state::endFineAdjust,
    cancelFineAdjust = state::pausePreview,
    createFineGesture = { ready, cancel -> state.inputGesture(interactionAllowed = ready, onCancel = cancel) },
    duration = ReverbRangeDurationActions(
        begin = state::beginSelectionDurationEdit,
        change = state::setSelectionDuration,
        end = state::endSelectionDurationEdit,
        actionReady = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && state.durationActionAvailable },
        durationSeconds = { state.selectionDurationExactSeconds },
        maximumSeconds = state::selectionDurationWheelLimitExactSeconds,
        commit = { requested, limit ->
            runRangeDurationAction(requested, state.selectionDurationWheelLimitExactSeconds(limit), ready = true,
                begin = state::beginSelectionDurationEdit,
                commit = { state.setSelectionDurationFromAction(it, state.selectionDurationWheelLimitExactSeconds(limit)) },
                end = state::endSelectionDurationEdit)
        },
    ),
)
