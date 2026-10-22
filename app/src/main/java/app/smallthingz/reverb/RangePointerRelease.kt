package app.smallthingz.reverb

/** The shared parent owns Range taps, but Library taps belong to its child clickable. */
internal fun cancelFineSeekOwnedAudio(
    ownsPointerTap: Boolean,
    ownsDrag: Boolean,
    stillCurrent: Boolean,
    onCancelAudio: () -> Unit,
) {
    if (stillCurrent && (ownsPointerTap || ownsDrag)) onCancelAudio()
}
