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

// The waveform owns this pointer stream. Revoke cancellation before the final seek can resume.
internal inline fun finishInlineWaveformGesture(
    release: PointerInputChange?,
    playback: InlinePlayerBookkeeping,
    finishSeek: () -> Unit,
) {
    rangePointerReleaseWasNormal(release, playback::revokeResume)
    finishSeek()
}

