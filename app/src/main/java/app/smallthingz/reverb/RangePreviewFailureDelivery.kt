package app.smallthingz.reverb

/** Retain the actual setup-release failure even when configuration never returns the owner. */
internal inline fun <Owner> configureRangePreviewTrackOrRelease(
    owner: Owner,
    release: (Owner) -> Unit,
    onReleaseFailure: (Throwable) -> Unit,
    configure: (Owner) -> Unit,
): Owner = configureOwnedResourceOrRelease(
    owner = owner,
    release = { acquired ->
        try {
            release(acquired)
        } catch (error: Throwable) {
            runCatching { onReleaseFailure(error) }
            throw error
        }
    },
    configure = configure,
)

/**
 * Combine playback and cleanup failures already available at this boundary. A stale playback
 * error is silent, but cancellation never hides a genuine cleanup failure. Asynchronous terminal
 * track cleanup reports independently and does not delay playback feedback.
 */
internal fun completeRangePreviewFailure(
    playbackFailure: Throwable?,
    releaseFailure: Throwable?,
    isCurrent: () -> Boolean,
    post: (() -> Unit) -> Unit,
    onPlaybackFailure: ((Throwable) -> Unit)?,
    onReleaseFailure: (Throwable) -> Unit,
) {
    fun reportReleaseFailure() {
        if (releaseFailure != null) runCatching { onReleaseFailure(releaseFailure) }
    }

    if (playbackFailure == null || onPlaybackFailure == null) {
        reportReleaseFailure()
        return
    }
    if (releaseFailure != null && releaseFailure !== playbackFailure &&
        playbackFailure.suppressed.none { it === releaseFailure }
    ) {
        playbackFailure.addSuppressed(releaseFailure)
    }
    if (!isCurrent()) {
        reportReleaseFailure()
        return
    }
    post {
        if (isCurrent()) onPlaybackFailure(playbackFailure)
        else reportReleaseFailure()
    }
}
