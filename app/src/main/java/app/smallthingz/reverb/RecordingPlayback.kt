package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
    val playbackAnchor: PlaybackPositionAnchor? = null,
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
    private val scrubAuthorities = RecordingScrubAuthorities()
    private val positionObservers = PlaybackPositionObservers { service?.positionObservationChanged() }
    internal data class Start(val token: Long, val recording: RecordingEntity, val positionMillis: Int?)

    fun play(context: Context, recording: RecordingEntity, positionMillis: Int? = null): Boolean {
        val app = context.applicationContext
        val retained = state.value.takeIf { it.matches(recording) }
        val request = Start(++nextRequest, recording,
            positionMillis ?: retained?.positionMillis?.takeIf { service?.ownsRecording(recording) != true })
        pending = request
        // Incoming intent revokes a primed/playing scrub before service delivery can be delayed.
        scrubAuthorities.revoke()
        return try {
            val started: ComponentName? = app.startForegroundService(Intent(app, RecordingPlaybackService::class.java)
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
    internal fun cancelPendingStartFor(owner: RecordingPlaybackService) {
        val request = pending ?: return
        if (service === owner && owner.ownsRecording(request.recording)) pending = null
    }
    internal fun nextGeneration(): Long = ++nextOwner
    internal fun positionObserved(): Boolean = positionObservers.observed
    fun observePosition(token: Any, visible: Boolean) {
        positionObservers.observe(token, visible)
    }
    internal fun attach(owner: RecordingPlaybackService) {
        if (service !== owner) scrubAuthorities.revoke()
        service = owner
    }
    internal fun detach(owner: RecordingPlaybackService) {
        if (service === owner) { scrubAuthorities.retireServiceOwner(); service = null }
    }
    internal fun revokeScrub(owner: RecordingPlaybackService) {
        if (service === owner) scrubAuthorities.revoke()
    }
    internal fun retireServiceScrub(owner: RecordingPlaybackService) {
        if (service === owner) scrubAuthorities.retireServiceOwner()
    }
    internal fun publish(owner: RecordingPlaybackService, value: RecordingPlaybackState) {
        if (service !== owner) return
        // StateFlow delivery can queue behind the worker's Main acknowledgement. Revoke now.
        scrubAuthorities.published(value.takeIf { it.prepared && it.recording != null }
            ?.let { RecordingScrubKey(it.generation, it.transportIntentRevision) })
        mutableState.value = value
    }
    private fun currentScrubKey(recording: RecordingEntity): RecordingScrubKey? {
        val value = mutableState.value
        return recordingScrubAdmission(value, value.matches(recording),
            service?.ownsRecording(recording) == true, pending != null)
    }
    fun canScrub(recording: RecordingEntity): Boolean = currentScrubKey(recording) != null
    fun acquireScrub(recording: RecordingEntity): RecordingScrubAuthority? {
        val key = currentScrubKey(recording) ?: return null
        val value = mutableState.value
        return scrubAuthorities.acquire(key,
            resumePlayback = !key.retainedIdle && (value.playing || value.wantsPlayback))
    }
    fun isScrubCurrent(recording: RecordingEntity, authority: RecordingScrubAuthority): Boolean =
        scrubAuthorities.owns(authority) && currentScrubKey(recording) == authority.key
    fun finishScrub(authority: RecordingScrubAuthority) = scrubAuthorities.finish(authority)
    fun pause(recording: RecordingEntity) {
        if (pending?.recording?.let { samePlaybackRecording(it, recording) } == true) pending = null
        if (!pauseRetained(recording)) service?.pauseMatching(recording)
    }
    fun pauseCurrent() {
        pending = null
        scrubAuthorities.revoke()
        val recording = mutableState.value.recording
        if (recording == null || !pauseRetained(recording)) service?.pauseCurrent()
    }
    private fun pauseRetained(recording: RecordingEntity): Boolean {
        if (currentScrubKey(recording)?.retainedIdle != true) return false
        updateRetainedPosition(mutableState.value.positionMillis)
        return true
    }
    private fun updateRetainedPosition(positionMillis: Int) {
        scrubAuthorities.revoke()
        val value = mutableState.value
        mutableState.value = value.copy(
            positionMillis = positionMillis.coerceIn(0, value.durationMillis),
            transportIntentRevision = value.transportIntentRevision + 1L,
            playbackAnchor = null,
        )
    }
    fun suspendForGesture(recording: RecordingEntity, authority: RecordingScrubAuthority) {
        if (scrubAuthorities.owns(authority)) service?.suspendForGestureMatching(recording, authority)
    }
    fun seek(recording: RecordingEntity, positionMillis: Int, resume: Boolean) {
        if (!resume && currentScrubKey(recording)?.retainedIdle == true) {
            updateRetainedPosition(positionMillis)
        } else service?.seekMatching(recording, positionMillis, resume)
    }
    fun stop(recording: RecordingEntity) {
        if (pending?.recording?.let { samePlaybackRecording(it, recording) } == true) pending = null
        service?.stopMatching(recording)
        if (service?.ownsRecording(recording) != true && mutableState.value.matches(recording)) {
            scrubAuthorities.revoke()
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

/** A retained cursor can use the independent preview reader without starting MediaPlayer. */
internal fun recordingScrubAdmission(
    state: RecordingPlaybackState,
    matchingRecording: Boolean,
    serviceOwnsRecording: Boolean,
    pendingStart: Boolean,
): RecordingScrubKey? {
    if (!matchingRecording || pendingStart || state.durationMillis <= 0) return null
    if (state.prepared) {
        return if (serviceOwnsRecording) RecordingScrubKey(state.generation, state.transportIntentRevision) else null
    }
    if (serviceOwnsRecording || state.playing || state.wantsPlayback) return null
    return RecordingScrubKey(state.generation, state.transportIntentRevision, retainedIdle = true)
}
