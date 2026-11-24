package app.smallthingz.reverb

import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.reverb.ReverbRangeTimelineActions

internal typealias NativeRangeTimelineInput = smallthingz.ui.reverb.NativeRangeTimelineInput

internal fun NativeRangeTimelineInput(
    state: RangeExportEditorState,
    enabled: () -> Boolean,
    timelineWidth: () -> Float,
    secondsForWaveformX: (Float) -> Float,
    endpointPointerX: (NativeDragPointerChange) -> Float,
    touchSlop: () -> Float,
): NativeRangeTimelineInput = NativeRangeTimelineInput(
    state.rangeTimelineActions(), enabled, timelineWidth, secondsForWaveformX, endpointPointerX, touchSlop,
)

internal fun RangeExportEditorState.rangeTimelineActions() = ReverbRangeTimelineActions(
    durationSeconds = { durationSeconds },
    lastTarget = { lastTarget },
    targetSeconds = ::targetSeconds,
    createGesture = { enabled -> inputGesture(enabled) { if (it) pausePreview() } },
    selectTarget = ::selectTarget,
    beginBoundaryScrub = ::beginBoundaryScrub,
    updateBoundaryScrub = ::updateBoundaryScrub,
    endBoundaryScrub = ::endBoundaryScrub,
    beginBoundaryEdit = ::beginBoundaryEdit,
)
