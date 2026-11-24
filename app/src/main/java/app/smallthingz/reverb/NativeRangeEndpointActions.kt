package app.smallthingz.reverb

import android.view.View
import androidx.lifecycle.Lifecycle
import smallthingz.ui.reverb.ReverbRangeEndpointInput
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope

internal typealias NativeRangeEndpointView = smallthingz.ui.reverb.NativeRangeEndpointView
internal typealias NativeRangeEndpointActions = smallthingz.ui.reverb.ReverbRangeEndpointActions

internal fun NativeRangeEndpointActions(
    view: NativeRangeEndpointView, sourceWindow: View, state: RangeExportEditorState, target: RangeEditTarget,
    lifecycle: Lifecycle, interactionEnabled: () -> Boolean, window: PlainTextAccessibilityWindowScope,
    sourceNode: () -> NativeSourceAccessibilityNode,
): NativeRangeEndpointActions = NativeRangeEndpointActions(view, sourceWindow,
    rangeEndpointInput(state, lifecycle, target), interactionEnabled, window, sourceNode)

internal fun rangeEndpointInput(state: RangeExportEditorState, lifecycle: Lifecycle, target: RangeEditTarget) =
    ReverbRangeEndpointInput(
        label = if (target == RangeEditTarget.START) R.string.range_start_accessibility else R.string.range_end_accessibility,
        earlier = R.string.range_endpoint_earlier,
        later = R.string.range_endpoint_later,
        limits = { rangeEndpointLimits(state.durationSeconds, target) },
        position = { state.targetSeconds(target) },
        selected = { state.lastTarget == target },
        actionAvailable = { state.durationActionAvailable },
        resumed = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
        createGesture = { ready -> state.inputGesture(interactionAllowed = ready, onCancel = {}) },
        edit = { requested, gesture -> state.editEndpointFromAction(target, requested, gesture) },
    )
