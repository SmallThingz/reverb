package app.smallthingz.reverb

import android.content.Context
import android.util.Log

/** Report the actual release failure before an optional setup/probe caller can discard it. */
internal inline fun releaseAudioInputWithReporting(
    release: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    try {
        release()
    } catch (error: Throwable) {
        closePreservingPrimaryFailure(error) { onFailure(error) }
        throw error
    }
}

internal inline fun <Owner> probeAudioInputConfiguration(
    acquire: () -> Owner,
    release: (Owner) -> Unit,
    onReleaseFailure: (Throwable) -> Unit,
    probe: (Owner) -> Boolean,
): Boolean = try {
    withOwnedResource(
        owner = acquire(),
        release = { owner ->
            releaseAudioInputWithReporting(
                release = { release(owner) },
                onFailure = onReleaseFailure,
            )
        },
        block = probe,
    )
} catch (_: Exception) {
    false
}

internal fun reportAudioInputReleaseFailure(context: Context, source: String, error: Throwable) {
    val appContext = context.applicationContext
    Log.e("ReverbAudioInput", "$source failed", error)
    RecordingIncidentStore.recordUnexpectedErrorInBackground(appContext, source, error)
    AppFeedbackCenter.post(
        appContext.getString(R.string.audio_input_release_failed),
        FeedbackTone.ERROR,
    )
}
