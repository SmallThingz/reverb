package app.smallthingz.reverb

import smallthingz.ui.focus.FocusDirection
import smallthingz.ui.geometry.Rect

internal fun <T> nativeBestDirectionalFocusCandidate(
    candidates: List<T>, focused: Rect, direction: FocusDirection, bounds: (T) -> Rect?,
): T? = smallthingz.ui.common.nativeBestDirectionalFocusCandidate(candidates, focused, direction, bounds)

internal fun nativeIsBetterFocusCandidate(
    proposedCandidate: Rect, currentCandidate: Rect, focusedRect: Rect, direction: FocusDirection,
): Boolean = smallthingz.ui.common.nativeIsBetterFocusCandidate(proposedCandidate, currentCandidate, focusedRect, direction)
