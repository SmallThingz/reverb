package app.smallthingz.reverb

import app.smallthingz.reverb.nativeui.ReverbRangeExportContent
import smallthingz.ui.input.phase.NativeWindowPointerCoordinates

internal typealias NativeRangePointerBinding = smallthingz.ui.reverb.NativeRangePointerBinding

internal fun NativeRangePointerBinding(
    content: ReverbRangeExportContent,
    coordinates: NativeWindowPointerCoordinates,
    density: () -> Float,
    touchSlop: () -> Float,
): NativeRangePointerBinding = NativeRangePointerBinding(
    content, content.state.rangeTimelineActions(), coordinates, density, touchSlop,
)
