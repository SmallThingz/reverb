package app.smallthingz.reverb

import androidx.compose.ui.input.pointer.PointerInputChange

/** Compose delivers platform CANCEL as a consumed UP, without cancelling the gesture coroutine. */
internal fun rangePointerReleaseWasNormal(
    change: PointerInputChange?,
    onCancel: () -> Unit,
): Boolean {
    if (change != null && !change.pressed && !change.isConsumed) return true
    onCancel()
    return false
}

/** The shared parent owns Range taps, but Library taps belong to its child clickable. */
internal fun cancelFineSeekOwnedAudio(
    ownsPointerTap: Boolean,
    ownsDrag: Boolean,
    stillCurrent: Boolean,
    onCancelAudio: () -> Unit,
) {
    if (stillCurrent && (ownsPointerTap || ownsDrag)) onCancelAudio()
}
