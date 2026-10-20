package app.smallthingz.reverb

import android.content.Context
import android.util.Log

/** Report acquired-owner cleanup before an optional probe can discard its failure. */
internal fun optionalRecordingProbeCleanup(context: Context, source: String): RecordingReadCleanup {
    val appContext = context.applicationContext
    return RecordingReadCleanup { error ->
        Log.e("ReverbRecordingProbe", "$source failed", error)
        RecordingIncidentStore.recordUnexpectedErrorInBackground(appContext, source, error)
        AppFeedbackCenter.post(
            appContext.getString(R.string.recording_read_cleanup_failed),
            FeedbackTone.ERROR,
        )
    }
}
