package app.smallthingz.reverb

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RecordingPlaybackState(
    val recording: RecordingEntity? = null,
    val prepared: Boolean = false,
    val playing: Boolean = false,
    val positionMillis: Int = 0,
    val durationMillis: Int = 0,
    val generation: Long = 0,
    val wantsPlayback: Boolean = false,
    val transportIntentRevision: Long = 0,
) {
    fun matches(candidate: RecordingEntity): Boolean = recording?.let {
        samePlaybackRecording(it, candidate)
    } == true
}

internal fun samePlaybackRecording(first: RecordingEntity, second: RecordingEntity): Boolean =
    first.id == second.id && first.storageType == second.storageType &&
        first.fileIdentity.isNotBlank() && first.fileIdentity == second.fileIdentity &&
        first.sizeBytes == second.sizeBytes

/** No UI owners or callbacks cross the started-service handoff. Only the latest start is retained. */
@androidx.annotation.MainThread
internal object RecordingPlayback {
    private val mutableState = MutableStateFlow(RecordingPlaybackState())
    val state: StateFlow<RecordingPlaybackState> = mutableState.asStateFlow()
    private var nextRequest = 0L
    private var nextOwner = 0L
    private var pending: Start? = null
    private var service: RecordingPlaybackService? = null
    private val positionObservers = java.util.IdentityHashMap<Any, Unit>()
    internal data class Start(val token: Long, val recording: RecordingEntity, val positionMillis: Int?)

    fun play(context: Context, recording: RecordingEntity, positionMillis: Int? = null): Boolean {
        val app = context.applicationContext
        val retained = state.value.takeIf { it.matches(recording) }
        val request = Start(++nextRequest, recording,
            positionMillis ?: retained?.positionMillis?.takeIf { service?.ownsRecording(recording) != true })
        pending = request
        return try {
            val started = ContextCompat.startForegroundService(app,
                Intent(app, RecordingPlaybackService::class.java)
                    .putExtra(RecordingPlaybackService.EXTRA_REQUEST, request.token))
            checkNotNull(started) { "Playback service start was rejected" }
            true
        } catch (error: Exception) {
            if (pending === request) pending = null
            reportPlaybackFailure(app, error)
            false
        }
    }

    internal fun takeStart(token: Long): Start? = pending?.takeIf { it.token == token }?.also { pending = null }
    internal fun hasPendingStart(): Boolean = pending != null
    internal fun nextGeneration(): Long = ++nextOwner
    internal fun positionObserved(): Boolean = positionObservers.isNotEmpty()
    fun observePosition(token: Any, visible: Boolean) {
        if (visible) positionObservers[token] = Unit else positionObservers.remove(token)
        service?.positionObservationChanged()
    }
    internal fun attach(owner: RecordingPlaybackService) { service = owner }
    internal fun detach(owner: RecordingPlaybackService) { if (service === owner) service = null }
    internal fun publish(owner: RecordingPlaybackService, value: RecordingPlaybackState) {
        if (service === owner) mutableState.value = value
    }
    fun pause(recording: RecordingEntity) {
        if (pending?.recording?.let { samePlaybackRecording(it, recording) } == true) pending = null
        service?.pauseMatching(recording)
    }
    fun pauseCurrent() { pending = null; service?.pauseCurrent() }
    fun suspendForGesture(recording: RecordingEntity, generation: Long, transportIntentRevision: Long) {
        service?.suspendForGestureMatching(recording, generation, transportIntentRevision)
    }
    fun seek(recording: RecordingEntity, positionMillis: Int, resume: Boolean) {
        service?.seekMatching(recording, positionMillis, resume)
    }
    fun stop(recording: RecordingEntity) {
        if (pending?.recording?.let { samePlaybackRecording(it, recording) } == true) pending = null
        service?.stopMatching(recording)
        if (service?.ownsRecording(recording) != true && mutableState.value.matches(recording)) {
            mutableState.value = RecordingPlaybackState(generation = mutableState.value.generation)
        }
    }
    fun renamed(original: RecordingEntity, renamed: RecordingEntity) {
        pending = pending?.let { retargetPlaybackStartAfterRename(it, original, renamed) }
        service?.renameMatching(original, renamed)
        if (mutableState.value.matches(original)) {
            mutableState.value = mutableState.value.copy(recording = renamed)
        }
    }
}

internal fun retargetPlaybackStartAfterRename(
    start: RecordingPlayback.Start,
    original: RecordingEntity,
    renamed: RecordingEntity,
): RecordingPlayback.Start = if (samePlaybackRecording(start.recording, original)) {
    start.copy(recording = renamed)
} else start

internal fun reportPlaybackFailure(context: Context, error: Throwable) {
    android.util.Log.w("RecordingPlayback", "Saved recording playback failed", error)
    AppFeedbackCenter.post(actionableErrorMessage(context,
        context.getString(R.string.recording_playback_failed), error), FeedbackTone.ERROR)
}
