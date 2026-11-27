package app.smallthingz.reverb

import android.content.Context
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/** Failed save/name operations are incidents even when capture is stopped, including I/O failures. */
internal fun unexpectedRecordingOperationFailure(error: Throwable): Boolean {
    val visited = IdentityHashMap<Throwable, Boolean>()
    var current: Throwable? = error
    repeat(32) {
        val failure = current ?: return true
        if (visited.put(failure, true) != null) return true
        if (failure is CancellationException || failure is DurableSaveLifetimeRejectedException ||
            (failure is RecordingRenameDefiniteFailure && failure.expectedConsentRejection != null) ||
            (failure is FileCopyIncidentException && failure.incidentOwnedByRecovery)) return false
        current = failure.cause
    }
    return true
}

internal fun reportUnexpectedRecordingOperationFailure(context: Context, operation: String, error: Throwable) {
    if (unexpectedRecordingOperationFailure(error)) {
        // App errors never depend on microphone ownership or the durable capture marker.
        RecordingIncidentStore.recordUnexpectedErrorInBackground(context, operation, error)
    }
}
