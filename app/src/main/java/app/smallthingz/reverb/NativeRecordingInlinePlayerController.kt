package app.smallthingz.reverb

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.nativeui.LibraryPlayerActions
import app.smallthingz.reverb.nativeui.LibraryPlayerUiState
import app.smallthingz.reverb.nativeui.ProgressiveWaveformData
import app.smallthingz.reverb.nativeui.SpringFineSeekBinding
import java.util.concurrent.atomic.AtomicReference
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class NativeInlinePlayerCallbacks(
    val onTrimRequestConsumed: () -> Unit,
    val onTrimSaved: (RecordingEntity) -> Unit,
    val onTrimStateUncertain: () -> Unit,
    val onWaveformCached: (RecordingEntity) -> Unit,
    val onCollapse: () -> Unit,
    val onPlaybackFailed: (Throwable?) -> Unit,
    val onBusyChange: (Boolean) -> Unit,
)
internal data class NativeInlinePlayerInput(
    val recording: RecordingEntity,
    val trimRequested: Boolean,
    val screenActive: Boolean,
    val playbackOwner: InlinePlaybackRowOwner?,
    val isCurrentPlaybackOwner: (InlinePlaybackRowOwner) -> Boolean,
    val callbacks: NativeInlinePlayerCallbacks,
)
internal data class NativeInlinePlayerPresentation(
    val state: LibraryPlayerUiState,
    val actions: LibraryPlayerActions,
    val fineSeek: SpringFineSeekBinding,
    val waveform: ProgressiveWaveformData,
    val waveformLoading: Boolean,
    val waveformMorphStarted: Boolean,
)

/** Called only after the row's fixed recording ID has been checked. */
internal fun sameNativeInlinePlayerRevision(previous: RecordingEntity, next: RecordingEntity): Boolean =
    previous.fileIdentity == next.fileIdentity && previous.sizeBytes == next.sizeBytes &&
        previous.durationMillis == next.durationMillis && previous.lastSeenAtMillis == next.lastSeenAtMillis

/**
 * One released recording.id lifetime. Revision owns playback/read jobs; this outer owner retains
 * accepted trim writes and their receiver across revision changes. No screen route is switched.
 * Publication is coalesced on Main, never STARTED-gated and never a Composition/rendering tree.
 */
internal class NativeRecordingInlinePlayerController(
    context: Context,
    private val hostView: View,
    private val lifecycleOwner: LifecycleOwner,
    parentScope: CoroutineScope,
    initial: NativeInlinePlayerInput,
    private val publish: (NativeInlinePlayerPresentation) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val rowScope = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))
    private val main = Handler(Looper.getMainLooper())
    private val positionObservation = Any()
    private var input = initial
    private val recordingId = initial.recording.id
    private var closed = false
    private var deliveryPosted = false
    private var trimSaving by observed(false)
    private val activeTrimReceiver = AtomicReference<InlineTrimResultReceiver?>(null)
    private var busyEffectValue: Boolean? = null
    private var busyEffect: Job? = null
    private var backActive = false
    private var backProgress = 0f
    private var backDirection = 1f
    private var session = Revision(initial.recording)
    private val delivery = Runnable {
        deliveryPosted = false
        if (!closed) deliver()
    }

    init {
        session.start()
        // Source collectAsState on currentStateFlow is collected for the whole row lifetime.
        rowScope.launch { lifecycleOwner.lifecycle.currentStateFlow.collect { invalidate() } }
        invalidate()
    }

    private fun <T> observed(initial: T): ReadWriteProperty<Any?, T> = object : ReadWriteProperty<Any?, T> {
        private var value = initial
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            if (this.value == value) return
            this.value = value
            invalidate()
        }
    }

    private fun invalidate() {
        if (closed || deliveryPosted) return
        deliveryPosted = true
        main.post(delivery)
    }

    fun bind(next: NativeInlinePlayerInput) {
        check(!closed) { "An inline player row has been disposed" }
        require(next.recording.id == recordingId) { "A player row cannot adopt another recording" }
        val previous = input
        val revisionChanged = !sameNativeInlinePlayerRevision(session.recording, next.recording)
        if (revisionChanged) session.close()
        input = next
        if (!next.screenActive) RecordingPlayback.observePosition(positionObservation, false)
        // A queued trim terminal may run before the coalesced presentation is delivered.
        activeTrimReceiver.get()?.setUiVisible(input.screenActive && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        if (revisionChanged) {
            session = Revision(next.recording)
            session.start()
        } else {
            if (previous.playbackOwner !== next.playbackOwner) {
                session.playbackBookkeeping.retireExpansion(session::pauseForLifecycle)
                session.bindExpansion()
            }
            if (previous.screenActive != next.screenActive) {
                session.playbackBookkeeping.revokeResume()
                if (!next.screenActive) session.pauseForLifecycle()
            }
        }
        if (previous.playbackOwner !== next.playbackOwner) { backActive = false; backProgress = 0f }
        invalidate()
    }

    /** Same expansion-keyed predictive-back owner as the source, with motion supplied by its host. */
    fun backFrame(gestureActive: Boolean, progress: Float, direction: Float) {
        backActive = gestureActive; backProgress = progress; backDirection = direction
        invalidate()
    }
    fun finishBack(ownerAtRegistration: InlinePlaybackRowOwner?) {
        finishInlineBackForCurrentRow(owner = ownerAtRegistration,
            isCurrentOwner = input.isCurrentPlaybackOwner, trimSaving = trimSaving,
            trimMode = session.trimMode,
            leaveTrim = session::leaveTrimMode,
            collapse = input.callbacks.onCollapse)
    }

    fun exitTrimForOrdinaryPlayback() {
        if (!closed) session.preemptPreview()
    }

    private fun deliver() {
        val current = session
        RecordingPlayback.observePosition(positionObservation,
            input.screenActive && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        activeTrimReceiver.get()?.setUiVisible(input.screenActive && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        if (busyEffectValue != trimSaving) {
            busyEffect?.cancel()
            busyEffectValue = trimSaving
            val saving = trimSaving
            val callback = input.callbacks.onBusyChange
            busyEffect = rowScope.launch { callback(saving) }
        }
        current.reconcileEffects()
        if (!closed && session === current) publish(current.presentation())
    }

    override fun close() {
        if (closed) return
        closed = true
        RecordingPlayback.observePosition(positionObservation, false)
        activeTrimReceiver.getAndSet(null)?.detachUi()
        input.callbacks.onBusyChange(false)
        session.close()
        busyEffect?.cancel()
        rowScope.cancel()
        main.removeCallbacks(delivery)
        deliveryPosted = false
    }

    private inner class Revision(val recording: RecordingEntity) : AutoCloseable {
        val key = revisionKey(recording)
        val playbackBookkeeping = InlinePlayerBookkeeping()
        private val revisionScope = CoroutineScope(rowScope.coroutineContext + Job(rowScope.coroutineContext[Job]))
        private var disposed = false
        private var prepared by observed(false)
        private var isPlaying by observed(false)
        private var trimPlaybackStarting by observed(false)
        private var trimStartGeneration = 0L
        private var currentPosition by observed(0)
        private val trimClockIdentity = Any()
        private var trimClock by observed<PlaybackPositionAnchor?>(null)
        private var duration by observed(recording.durationMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
        private var isScrubbing by observed(false)
        private var ordinaryScrubAuthority: RecordingScrubAuthority? = null
        var trimMode by observed(false)
        private var trimStartMillis by observed(0)
        private var trimEndMillis by observed(duration)
        var trimError by observed<String?>(null)
        private var fineSeekTarget by observed(InlineFineSeekTarget.PLAYHEAD)
        private var coarseWaveform by observed(FloatArray(RANGE_WAVEFORM_COARSE_BUCKETS))
        private var coarseBuiltCount by observed(0)
        private var detailWaveform by observed(FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS))
        private var detailBuiltCount by observed(0)
        private var waveformPass by observed(RangeWaveformPass.COARSE)
        private var waveformLoading by observed(true)
        private var waveformMorphStarted by observed(false)
        private var lastWaveform: ProgressiveWaveformData? = null
        private var lastCoarse: FloatArray? = null
        private var lastDetail: FloatArray? = null
        private var lastCoarseCount = -1
        private var lastDetailCount = -1
        private var lastPass: RangeWaveformPass? = null
        // The revision key is fixed for this lifetime; position publications compare only live dependencies.
        private var trimEffectInitialized = false
        private var trimEffectRequested = false
        private var trimEffectPrepared = false
        private var trimEffectDuration = 0
        private var trimEffect: Job? = null
        private var autoEffectInitialized = false
        private var autoEffectScreenActive = false
        private var autoEffectPrepared = false
        private var autoEffectOwner: InlinePlaybackRowOwner? = null
        private var autoEffect: Job? = null
        private val fineSeekPreviewController = TimelineAudioPreviewController(
            onTrackReleaseFailure = { error ->
                Log.e("ReverbInlinePlayer", "Saved recording preview AudioTrack.release failed", error)
                RecordingIncidentStore.recordUnexpectedErrorInBackground(appContext, "Saved recording preview AudioTrack cleanup", error)
                AppFeedbackCenter.post(actionableErrorMessage(appContext, appContext.getString(R.string.audio_preview_release_failed), error), FeedbackTone.ERROR)
            },
        )
        private val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    activeTrimReceiver.get()?.setUiVisible(input.screenActive)
                    submitInlinePlaybackAutoStart(playbackBookkeeping, input.playbackOwner, prepared, true,
                        trimSaving || input.trimRequested || trimMode || !input.screenActive || !ownsPlaybackRow()) {
                        startOrdinaryPlaybackIfNeeded()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> { activeTrimReceiver.get()?.setUiVisible(false); pauseForLifecycle() }
                else -> Unit
            }
        }
        fun reportPlaybackCleanupFailure(error: Throwable) {
            Log.e("ReverbInlinePlayer", "Saved recording playback cleanup failed", error)
            RecordingIncidentStore.recordUnexpectedErrorInBackground(
                appContext,
                "Saved recording playback cleanup",
                error,
            )
            AppFeedbackCenter.post(
                actionableErrorMessage(appContext, appContext.getString(R.string.recording_read_cleanup_failed), error),
                FeedbackTone.ERROR,
            )
        }

        fun releasePlayer() {
            finishOrdinaryScrub()
            cancelPendingTrimStart()
            if (playbackBookkeeping.released) return
            playbackBookkeeping.released = true
            playbackBookkeeping.revokeResume()
            fineSeekPreviewController.stopShuttle()
            playbackBookkeeping.fineSeekShuttleActive = false
            isScrubbing = false
            playbackBookkeeping.seeks.cancel()
            fineSeekPreviewController.stop()
            prepared = false
            isPlaying = false
            if (trimMode) trimClock = stoppedTrimClock()
        }

        private fun pauseStartedMediaPlayer(forGesture: Boolean = false) {
            if (!trimMode) {
                val authority = ordinaryScrubAuthority
                if (forGesture && authority != null && ordinaryScrubStillCurrent()) {
                    RecordingPlayback.suspendForGesture(recording, authority)
                } else if (!forGesture && (authority == null || ordinaryScrubStillCurrent())) RecordingPlayback.pause(recording)
            } else if (prepared) fineSeekPreviewController.stop()
        }

        fun pauseForLifecycle() {
            // Normal playback belongs to the service; only the gesture/editor belongs to this row.
            if (trimMode || isScrubbing || playbackBookkeeping.fineSeekShuttleActive) pauseInlinePlayback()
            finishOrdinaryScrub()
        }

        private fun startOrdinaryPlaybackIfNeeded() {
            if (!trimMode && !RecordingPlayback.state.value.matches(recording)) {
                RecordingPlayback.play(appContext, recording)
            }
        }

        fun pauseInlinePlayback() {
            if (trimMode && !isScrubbing) {
                // Freeze presentation without claiming a native stopped-head position. The next
                // trim play/gesture replaces this cursor from its selected boundary before playback.
                currentPosition = trimClock?.positionAt(SystemClock.elapsedRealtime()) ?: currentPosition
            }
            cancelPendingTrimStart()
            playbackBookkeeping.revokeResume()
            fineSeekPreviewController.stopShuttle()
            playbackBookkeeping.fineSeekShuttleActive = false
            isScrubbing = false
            runCatching { pauseStartedMediaPlayer() }
            finishOrdinaryScrub()
            isPlaying = false
            if (trimMode) trimClock = stoppedTrimClock()
        }

        fun ownsPlaybackRow(): Boolean = input.playbackOwner?.let(input.isCurrentPlaybackOwner) == true

        private fun cancelPendingTrimStart() {
            trimStartGeneration++
            if (trimMode && prepared) fineSeekPreviewController.stop()
            trimPlaybackStarting = false
        }

        private fun stoppedTrimClock() = PlaybackPositionAnchor(currentPosition, SystemClock.elapsedRealtime(), false,
            trimEndMillis, trimStartGeneration, trimClockIdentity, trimStartMillis)

        fun startCurrentRowPlayback(owner: InlinePlaybackRowOwner? = input.playbackOwner): Boolean {
            cancelPendingTrimStart()
            if (disposed || !trimMode || trimSaving || playbackBookkeeping.released || !prepared ||
                owner == null || !input.isCurrentPlaybackOwner(owner) || !input.screenActive ||
                !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
            val generation = trimStartGeneration
            val start = currentPosition.coerceIn(trimStartMillis, trimEndMillis)
                .takeIf { it < trimEndMillis } ?: trimPlaybackStartMillis()
            val end = trimEndMillis
            currentPosition = start
            trimClock = stoppedTrimClock()
            trimPlaybackStarting = true
            RecordingPlayback.pauseCurrent()
            val accepted = fineSeekPreviewController.playRecording(
                appContext, recording, start, end,
                onProgress = { sample ->
                    if (!disposed && generation == trimStartGeneration && !isScrubbing) {
                        currentPosition = (sample.positionSeconds * 1_000.0).toInt().coerceIn(start, end)
                        trimClock = PlaybackPositionAnchor(currentPosition, sample.sampledAtElapsedRealtimeMillis, true,
                            (sample.queuedUntilSeconds * 1_000.0).toInt().coerceIn(currentPosition, end),
                            generation, trimClockIdentity, start)
                    }
                },
                onStarted = {
                    if (!disposed && generation == trimStartGeneration) {
                        trimPlaybackStarting = false
                        isPlaying = true
                    }
                },
                onFinished = {
                    if (!disposed && generation == trimStartGeneration) {
                        trimPlaybackStarting = false
                        isPlaying = false
                        currentPosition = end
                        trimClock = stoppedTrimClock()
                    }
                },
                onError = { error ->
                    if (!disposed && generation == trimStartGeneration) {
                        releasePlayer()
                        input.callbacks.onPlaybackFailed(error)
                    }
                },
            )
            if (!accepted) trimPlaybackStarting = false
            return accepted
        }

        fun seekTo(positionMillis: Int, resume: Boolean = false) {
            cancelPendingTrimStart()
            playbackBookkeeping.fineSeekPosition.reset()
            val bounded = positionMillis.coerceIn(0, duration.coerceAtLeast(1))
            currentPosition = bounded
            if (trimMode) trimClock = stoppedTrimClock()
            if (!trimMode) {
                val mayResume = resume && input.screenActive && ownsPlaybackRow() && !trimSaving &&
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                if (RecordingPlayback.state.value.matches(recording)) {
                    RecordingPlayback.seek(recording, bounded, mayResume)
                } else if (mayResume) {
                    RecordingPlayback.play(appContext, recording, bounded)
                }
                return
            }
            if (!prepared || playbackBookkeeping.released) return
            isPlaying = false
            if (resume) startCurrentRowPlayback()
        }

        fun trimPlaybackStartMillis(): Int = inlineTrimPlaybackStartMillis(
            trimStartMillis, trimEndMillis, fineSeekTarget,
        )

        fun toggleFineSeekPlayback() {
            if (!trimMode) {
                if (trimSaving || !input.screenActive || !ownsPlaybackRow() ||
                    !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
                val playback = RecordingPlayback.state.value
                if (playback.matches(recording) &&
                    recordingPlaybackPhase(playback.playing, playback.wantsPlayback).offersPause) RecordingPlayback.pause(recording)
                else RecordingPlayback.play(appContext, recording,
                    if (currentPosition >= duration) 0 else null)
                return
            }
            if ((trimPlaybackStarting || isPlaying) && fineSeekPreviewController.playbackActive) {
                pauseInlinePlayback(); return
            }
            if (!prepared || playbackBookkeeping.released || trimSaving || !input.screenActive || !ownsPlaybackRow() ||
                !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
            seekTo(trimPlaybackStartMillis(), resume = true)
        }

        fun currentInlineFineSeekValues(): InlineFineSeekValues = InlineFineSeekValues(
            cursorMillis = currentPosition,
            startMillis = trimStartMillis,
            endMillis = trimEndMillis,
        )

        fun fineSeekTargetMillis(values: InlineFineSeekValues = currentInlineFineSeekValues()): Int =
            when (if (trimMode) fineSeekTarget else InlineFineSeekTarget.PLAYHEAD) {
                InlineFineSeekTarget.PLAYHEAD -> values.cursorMillis
                InlineFineSeekTarget.TRIM_START -> values.startMillis
                InlineFineSeekTarget.TRIM_END -> values.endMillis
            }

        fun trimBoundaryInputReady(): Boolean = inlineTrimBoundaryActionAllowed(
            prepared = prepared,
            trimMode = trimMode,
            saving = trimSaving,
            released = playbackBookkeeping.released,
            visible = input.screenActive && ownsPlaybackRow() && hostView.isAttachedToWindow &&
                hostView.isShown && hostView.hasWindowFocus(),
            resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            gestureActive = isScrubbing || playbackBookkeeping.fineSeekShuttleActive || backActive,
        )

        fun editTrimBoundary(target: InlineFineSeekTarget, requestedMillis: Double): Boolean =
            runInlineTrimBoundaryAction(
                values = currentInlineFineSeekValues(),
                durationMillis = duration,
                target = target,
                requestedMillis = requestedMillis,
                ready = trimBoundaryInputReady(),
                pause = ::pauseInlinePlayback,
                apply = { adjusted ->
                    fineSeekTarget = target
                    trimStartMillis = adjusted.startMillis
                    trimEndMillis = adjusted.endMillis
                    trimError = null
                    // Discrete edits revoke scrub/seek resume and use the same serialized seek owner.
                    seekTo(fineSeekTargetMillis(adjusted), resume = false)
                },
            )

        fun beginInlineFineSeek(shuttleRate: Float) {
            if (!prepared || playbackBookkeeping.released || trimSaving || playbackBookkeeping.fineSeekShuttleActive ||
                !input.screenActive || !ownsPlaybackRow() || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) return
            if (!trimMode && !beginOrdinaryScrub()) return
            if (!trimMode) {
                currentPosition = RecordingPlayback.state.value.playbackAnchor
                    ?.positionAt(SystemClock.elapsedRealtime()) ?: currentPosition
            }
            playbackBookkeeping.fineSeekPosition.reset()
            val resumeAfterScrub = isPlaying || trimPlaybackStarting || playbackBookkeeping.seeks.resume
            cancelPendingTrimStart()
            playbackBookkeeping.resumeAfterScrub = resumeAfterScrub
            playbackBookkeeping.revokeSeekResume()
            val scrubOwner = input.playbackOwner
            // MediaPlayer cannot reverse and repeated seekTo() calls produce silence/stutter.
            // Keep it sounding until the first AudioTrack grain is ready, then hand off. This avoids
            // a silent source-open gap while keeping logical playback state unchanged for release.
            isScrubbing = true
            playbackBookkeeping.fineSeekShuttleActive = true
            fineSeekPreviewController.startRecordingShuttle(
                context = appContext,
                recording = recording,
                atSeconds = fineSeekTargetMillis().toDouble() / 1_000.0,
                rate = shuttleRate,
                fromSeconds = if (trimMode) trimStartMillis / 1_000.0 else 0.0,
                untilSeconds = if (trimMode) trimEndMillis / 1_000.0 else Double.POSITIVE_INFINITY,
                ordinaryAuthority = if (trimMode) null else checkNotNull(ordinaryScrubAuthority),
                onStarted = {
                    if (!trimMode && !ordinaryScrubStillCurrent()) {
                        fineSeekPreviewController.stopShuttle()
                    } else if (!trimMode && playbackBookkeeping.fineSeekShuttleActive && playbackBookkeeping.resumeAfterScrub) {
                        runCatching { pauseStartedMediaPlayer(forGesture = true) }
                    }
                },
                onFailureAfterStart = {
                    if (inlineShuttleFailureShouldResume(
                            shuttleActive = playbackBookkeeping.fineSeekShuttleActive &&
                                input.screenActive && scrubOwner != null &&
                                input.isCurrentPlaybackOwner(scrubOwner) && !playbackBookkeeping.seeks.pending,
                            resumeAfterScrub = playbackBookkeeping.resumeAfterScrub,
                            lifecycleResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                        )
                    ) {
                        if (!trimMode && ordinaryScrubStillCurrent()) {
                            RecordingPlayback.seek(recording, currentPosition, true)
                        } else if (trimMode) {
                            runCatching { startCurrentRowPlayback(scrubOwner) }
                                .onFailure { error ->
                                    releasePlayer()
                                    input.callbacks.onPlaybackFailed(error)
                                }
                        }
                    }
                },
            )
        }

        fun applyInlineFineSeek(deltaSeconds: Float) {
            if (!isScrubbing || !playbackBookkeeping.fineSeekShuttleActive || !ordinaryScrubStillCurrent()) return
            if (!prepared || !deltaSeconds.isFinite() || duration <= 0) return
            val target = if (trimMode) fineSeekTarget else InlineFineSeekTarget.PLAYHEAD
            val adjusted = adjustInlineFineSeekPosition(
                position = playbackBookkeeping.fineSeekPosition,
                values = currentInlineFineSeekValues(),
                durationMillis = duration,
                target = target,
                deltaSeconds = deltaSeconds,
            )
            currentPosition = adjusted.cursorMillis
            trimStartMillis = adjusted.startMillis
            trimEndMillis = adjusted.endMillis
            if (trimMode && target != InlineFineSeekTarget.PLAYHEAD) {
                currentPosition = fineSeekTargetMillis(adjusted)
                trimError = null
            }
        }

        fun updateInlineFineSeekShuttle(shuttleRate: Float, pendingDeltaSeconds: Float) {
            if (!isScrubbing || !playbackBookkeeping.fineSeekShuttleActive || !ordinaryScrubStillCurrent() || duration <= 0) return
            val target = if (trimMode) fineSeekTarget else InlineFineSeekTarget.PLAYHEAD
            fineSeekPreviewController.updateShuttle(
                atSeconds = projectInlineFineSeekPosition(
                    position = playbackBookkeeping.fineSeekPosition,
                    values = currentInlineFineSeekValues(),
                    durationMillis = duration,
                    target = target,
                    pendingDeltaSeconds = pendingDeltaSeconds,
                ),
                rate = shuttleRate,
                fromSeconds = if (trimMode) trimStartMillis / 1_000.0 else 0.0,
                untilSeconds = if (trimMode) trimEndMillis / 1_000.0 else Double.POSITIVE_INFINITY,
            )
        }

        fun finishInlineSeek() {
            playbackBookkeeping.fineSeekPosition.reset()
            isScrubbing = false
            val shouldResume = playbackBookkeeping.resumeAfterScrub
            playbackBookkeeping.resumeAfterScrub = false
            if (!ordinaryScrubStillCurrent()) {
                finishOrdinaryScrub()
                adoptOrdinaryState()
                return
            }
            finishOrdinaryScrub()
            if (trimMode) currentPosition = fineSeekTargetMillis()
            seekTo(
                if (trimMode && shouldResume) trimPlaybackStartMillis() else currentPosition,
                resume = shouldResume,
            )
        }

        fun endInlineFineSeek() {
            if (!isScrubbing && !playbackBookkeeping.fineSeekShuttleActive) return
            fineSeekPreviewController.stopShuttle()
            playbackBookkeeping.fineSeekShuttleActive = false
            finishInlineSeek()
        }

        fun enterTrimMode(): Boolean {
            if (trimSaving || !canEnterInlineTrim(prepared, duration)) return false
            input.playbackOwner?.consumeInitialAutoStart()
            playbackBookkeeping.initialAutoStartPending = false
            RecordingPlayback.pauseCurrent()
            if (isPlaying) {
                runCatching { pauseStartedMediaPlayer() }
                isPlaying = false
            }
            trimStartMillis = 0
            trimEndMillis = duration
            fineSeekTarget = InlineFineSeekTarget.TRIM_START
            currentPosition = 0
            trimError = null
            trimMode = true
            prepared = false
            startTrimPlayer()
            return true
        }

        fun leaveTrimMode() {
            if (!trimMode) return
            pauseInlinePlayback()
            releasePlayer()
            trimMode = false
            trimError = null
            playbackBookkeeping.released = false
            adoptOrdinaryState()
        }

        fun preemptPreview() {
            // Retire gesture authority before a new transport command can publish playing state.
            // Accepted trim work and its terminal receiver remain owned by the outer controller.
            playbackBookkeeping.revokeResume()
            fineSeekPreviewController.stopShuttle()
            playbackBookkeeping.fineSeekShuttleActive = false
            isScrubbing = false
            finishOrdinaryScrub()
            if (trimMode) leaveTrimMode() else adoptOrdinaryState()
        }

        private fun adoptOrdinaryState() {
            if (trimMode || disposed) return
            val playback = RecordingPlayback.state.value
            val matching = playback.matches(recording)
            prepared = !matching || playback.prepared
            isPlaying = matching && recordingPlaybackPhase(playback.playing, playback.wantsPlayback).offersPause
            if (matching && !isScrubbing && !playbackBookkeeping.fineSeekShuttleActive) {
                currentPosition = playback.positionMillis
                duration = playback.durationMillis.coerceAtLeast(1)
            }
        }


        fun start() {
            playbackBookkeeping.released = false
            playbackBookkeeping.initialAutoStartPending = true
            adoptOrdinaryState()
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
            bindExpansion()
            revisionScope.launch {
                RecordingPlayback.state.collect { playback ->
                    if (trimMode && (playback.playing || playback.wantsPlayback)) {
                        if (trimSaving) pauseInlinePlayback() else leaveTrimMode()
                    }
                    if (!trimMode && ordinaryScrubAuthority != null && !ordinaryScrubStillCurrent()) {
                        playbackBookkeeping.revokeResume()
                        fineSeekPreviewController.stopShuttle()
                        playbackBookkeeping.fineSeekShuttleActive = false
                        isScrubbing = false
                        finishOrdinaryScrub()
                    }
                    adoptOrdinaryState()
                }
            }
            buildWaveform()
        }

        private fun startTrimPlayer() {
            playbackBookkeeping.released = false
            fineSeekPreviewController.prepareRecording(
                appContext, recording,
                onPrepared = { sourceDuration ->
                    if (!disposed && trimMode && !playbackBookkeeping.released) {
                        val previousDuration = duration
                        duration = sourceDuration
                        if (trimEndMillis >= previousDuration - 1) trimEndMillis = duration
                        currentPosition = currentPosition.coerceIn(0, duration)
                        prepared = true
                    }
                },
                onError = { error ->
                    if (!disposed && trimMode && !playbackBookkeeping.released) {
                        releasePlayer()
                        input.callbacks.onPlaybackFailed(error)
                    }
                },
                onSourceCloseFailure = ::reportPlaybackCleanupFailure,
            )
        }

        fun bindExpansion() {
            if (!input.screenActive || !ownsPlaybackRow()) {
                playbackBookkeeping.retireExpansion(::pauseForLifecycle)
            } else {
                playbackBookkeeping.initialAutoStartPending = !trimMode && !input.trimRequested && !trimSaving
                if (!playbackBookkeeping.initialAutoStartPending) input.playbackOwner?.consumeInitialAutoStart()
            }
        }

        private fun buildWaveform() = revisionScope.launch {
            waveformMorphStarted = true
            val expectedRevision = recordingWaveformRevision(recording)
            val cachedDetail = if (
                expectedRevision.isNotBlank() && recording.waveformRevision == expectedRevision
            ) {
                decodeRecordingWaveform(recording.waveformData)
            } else {
                null
            }
            if (cachedDetail != null) {
                detailWaveform = cachedDetail
                detailBuiltCount = cachedDetail.size
                coarseWaveform = coarseWaveformFromDetail(cachedDetail)
                coarseBuiltCount = coarseWaveform.size
                waveformPass = RangeWaveformPass.DETAIL
                waveformLoading = false
                return@launch
            }

            coarseWaveform = FloatArray(RANGE_WAVEFORM_COARSE_BUCKETS)
            detailWaveform = FloatArray(RANGE_WAVEFORM_DETAIL_BUCKETS)
            coarseBuiltCount = 0
            detailBuiltCount = 0
            waveformPass = RangeWaveformPass.COARSE
            waveformLoading = true

            try {
                buildProgressiveWaveform(
                    readPass = { pass, onBucket ->
                        readRecordingWaveformEnvelopeProgressive(
                            appContext, recording, pass,
                            RecordingReadCleanup(::reportPlaybackCleanupFailure), onBucket,
                        )
                    },
                    onPassStarted = { waveformPass = it },
                ) { pass, update ->
                    when (pass) {
                        RangeWaveformPass.COARSE -> if (update.values.size == coarseWaveform.size) {
                            coarseWaveform = update.values
                            coarseBuiltCount = update.builtCount.coerceIn(0, coarseWaveform.size)
                        }
                        RangeWaveformPass.DETAIL -> if (update.values.size == detailWaveform.size) {
                            detailWaveform = update.values
                            detailBuiltCount = update.builtCount.coerceIn(0, detailWaveform.size)
                        }
                    }
                }
                val encoded = encodeRecordingWaveform(detailWaveform)
                if (encoded.isNotBlank() && RecordingRepository.cacheWaveform(
                        context = appContext,
                        recording = recording,
                        waveformData = encoded,
                        waveformRevision = expectedRevision,
                    )
                ) {
                    input.callbacks.onWaveformCached(
                        recording.copy(
                            waveformData = encoded,
                            waveformRevision = expectedRevision,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Playback remains useful if a provider does not expose a seekable WAV descriptor.
            } finally {
                waveformLoading = false
            }
        }

        fun reconcileEffects() {
            val trimRequested = input.trimRequested
            val trimPrepared = prepared
            val trimDuration = duration
            if (!trimEffectInitialized || trimEffectRequested != trimRequested ||
                trimEffectPrepared != trimPrepared || trimEffectDuration != trimDuration
            ) {
                trimEffectInitialized = true
                trimEffectRequested = trimRequested
                trimEffectPrepared = trimPrepared
                trimEffectDuration = trimDuration
                trimEffect?.cancel()
                val requested = input.trimRequested
                val consumed = input.callbacks.onTrimRequestConsumed
                trimEffect = revisionScope.launch { if (requested && enterTrimMode()) consumed() }
            }
            val screenActive = input.screenActive
            val autoPrepared = prepared
            val playbackOwner = input.playbackOwner
            if (!autoEffectInitialized || autoEffectScreenActive != screenActive ||
                autoEffectPrepared != autoPrepared || autoEffectOwner != playbackOwner
            ) {
                autoEffectInitialized = true
                autoEffectScreenActive = screenActive
                autoEffectPrepared = autoPrepared
                autoEffectOwner = playbackOwner
                autoEffect?.cancel()
                val effectInput = input
                val effectPrepared = prepared
                autoEffect = revisionScope.launch {
                    submitInlinePlaybackAutoStart(playbackBookkeeping, effectInput.playbackOwner,
                        effectPrepared, lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                        !effectInput.screenActive || !ownsPlaybackRow() || effectInput.trimRequested || trimMode || trimSaving) {
                        startOrdinaryPlaybackIfNeeded()
                    }
                }
            }
        }

        private var waveformTarget = InlineFineSeekTarget.PLAYHEAD
        private fun beginOrdinaryScrub(): Boolean {
            val authority = RecordingPlayback.acquireScrub(recording) ?: return false
            ordinaryScrubAuthority = authority
            return true
        }
        private fun finishOrdinaryScrub() {
            ordinaryScrubAuthority?.let(RecordingPlayback::finishScrub)
            ordinaryScrubAuthority = null
        }
        private fun ordinaryScrubStillCurrent(): Boolean = trimMode || run {
            val authority = ordinaryScrubAuthority
            val playback = RecordingPlayback.state.value
            authority != null && authority.active &&
                inlineOrdinaryGestureIsCurrent(authority.key.generation, playback.generation, playback.matches(recording)) &&
                playback.prepared && authority.key.transportIntentRevision == playback.transportIntentRevision
        }

        private fun beginWaveform(x: Float, width: Float) {
            if (!trimMode && !beginOrdinaryScrub()) return
            playbackBookkeeping.fineSeekPosition.reset()
            val resumeAfterScrub = isPlaying || trimPlaybackStarting || playbackBookkeeping.seeks.resume
            cancelPendingTrimStart()
            playbackBookkeeping.resumeAfterScrub = resumeAfterScrub
            playbackBookkeeping.revokeSeekResume()
            if (isPlaying) { runCatching { pauseStartedMediaPlayer(forGesture = true) }; isPlaying = false }
            isScrubbing = true
            val initialMillis = (duration.toFloat() * (x / width).coerceIn(0f, 1f)).toInt()
            val threshold = if (trimMode) (duration.toFloat() * (24f * hostView.resources.displayMetrics.density) / width)
                .roundToInt().coerceAtLeast(1) else 0
            fineSeekTarget = if (trimMode) inlineTrimGestureTarget(initialMillis, trimStartMillis, trimEndMillis, threshold, fineSeekTarget)
                else InlineFineSeekTarget.PLAYHEAD
            waveformTarget = fineSeekTarget
            moveWaveform(x, width)
        }
        private fun moveWaveform(x: Float, width: Float) {
            if (!isScrubbing || !ordinaryScrubStillCurrent()) return
            val fraction = (x / width).coerceIn(0f, 1f)
            val millis = (duration.toFloat() * fraction).toInt().coerceIn(0, duration)
            if (!trimMode || waveformTarget == InlineFineSeekTarget.PLAYHEAD) { currentPosition = millis; return }
            val adjusted = adjustInlineTrimTarget(currentInlineFineSeekValues(), duration, waveformTarget, millis)
            trimStartMillis = adjusted.startMillis
            trimEndMillis = adjusted.endMillis
            currentPosition = when (waveformTarget) {
                InlineFineSeekTarget.TRIM_START -> adjusted.startMillis
                InlineFineSeekTarget.TRIM_END -> adjusted.endMillis
                InlineFineSeekTarget.PLAYHEAD -> currentPosition
            }
            trimError = null
        }
        private fun finishWaveform(normal: Boolean) {
            if (!isScrubbing) return
            if (!normal) playbackBookkeeping.revokeResume()
            finishInlineSeek()
        }

        private fun saveTrim() {
            val acceptedInput = input
            if (!trimSaving && trimEndMillis > trimStartMillis) {
                pauseInlinePlayback()
                trimSaving = true
                trimError = null
                acceptedInput.callbacks.onBusyChange(true)
                // Keep only accepted operation values outside the detachable UI callback gate.
                val trimContext = appContext
                val targetRecording = recording
                val startMillis = trimStartMillis
                val endMillis = trimEndMillis
                val receiverSlot = activeTrimReceiver
                val receiver = InlineTrimResultReceiver(
                    onSaved = { trimmed ->
                        trimSaving = false
                        acceptedInput.callbacks.onBusyChange(false)
                        leaveTrimMode()
                        if (
                            input.screenActive &&
                            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        ) {
                            acceptedInput.callbacks.onTrimSaved(trimmed)
                        }
                    },
                    onFailed = { error ->
                        trimSaving = false
                        acceptedInput.callbacks.onBusyChange(false)
                        trimError = recordingTrimFailureMessage(appContext, error)
                        if (
                            error is RecordingCatalogIdentityChangedException &&
                            input.screenActive &&
                            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        ) {
                            acceptedInput.callbacks.onTrimStateUncertain()
                        }
                    },
                    uiVisible = input.screenActive &&
                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                    onDetachedSuccess = { trimmed ->
                        NotifyFileReceiver(trimContext).fileReady(trimmed)
                    },
                    onDetachedFailure = { error ->
                        NotifyFileReceiver(trimContext).fileFailed(
                            recordingTrimFailureMessage(trimContext, error),
                            error,
                        )
                    },
                    onTerminal = { completed ->
                        receiverSlot.compareAndSet(completed, null)
                    },
                )
                receiverSlot.set(receiver)
                rowScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    runCommittedInlineTrim(
                        save = {
                            saveTrimmedRecordingCopy(
                                context = trimContext,
                                recording = targetRecording,
                                startMillis = startMillis,
                                endMillis = endMillis,
                            )
                        },
                        onTerminal = receiver::terminal,
                    )
                }
            }
        }

        // These callbacks belong to this exact revision and read its live fields when invoked.
        // Progress/back publications change visual values, not the action owner or closures.
        private val presentationActions = LibraryPlayerActions(
            ::beginWaveform, ::moveWaveform, ::finishWaveform,
            { target -> if (trimBoundaryInputReady()) fineSeekTarget = target }, ::editTrimBoundary,
            { if (!trimSaving) leaveTrimMode() }, ::saveTrim,
        )
        private val fineSeekTogglePlayback: () -> Unit = ::toggleFineSeekPlayback
        private val fineSeekInteractionStart: () -> Unit = {
            if (!trimMode) fineSeekTarget = InlineFineSeekTarget.PLAYHEAD
        }
        private val fineSeekBeginAdjust: (Float) -> Unit = ::beginInlineFineSeek
        private val fineSeekAdjust: (Float, Float) -> Unit = { delta, _ -> applyInlineFineSeek(delta) }
        private val fineSeekUpdateShuttle: (Float, Float) -> Unit = ::updateInlineFineSeekShuttle
        private val fineSeekEndAdjust: () -> Unit = ::endInlineFineSeek
        private val fineSeekCancelAdjust: () -> Unit = ::pauseForLifecycle
        private var presentationFineSeekTarget = InlineFineSeekTarget.PLAYHEAD
        private var presentationFineSeekKey = "$key:$presentationFineSeekTarget"

        private fun currentFineSeekInteractionKey(): String {
            val target = if (trimMode) fineSeekTarget else InlineFineSeekTarget.PLAYHEAD
            if (presentationFineSeekTarget != target) {
                presentationFineSeekTarget = target
                presentationFineSeekKey = "$key:$target"
            }
            return presentationFineSeekKey
        }

        fun presentation(): NativeInlinePlayerPresentation {
            val displayActive = input.screenActive && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            val enabled = displayActive &&
                prepared && trimMode && !trimSaving && !playbackBookkeeping.released && !isScrubbing &&
                !playbackBookkeeping.fineSeekShuttleActive && !backActive
            val state = LibraryPlayerUiState(key, prepared, isPlaying, duration, currentPosition, trimMode, trimSaving,
                trimStartMillis, trimEndMillis, fineSeekTarget, trimError, enabled, backActive, backProgress, backDirection,
                preparing = trimPlaybackStarting || !trimMode && RecordingPlayback.state.value.let { playback ->
                    playback.matches(recording) && recordingPlaybackPhase(playback.playing, playback.wantsPlayback) ==
                        RecordingPlaybackPhase.PREPARING
                },
                playbackAnchor = if (isScrubbing || playbackBookkeeping.fineSeekShuttleActive) null
                    else if (trimMode) trimClock
                    else RecordingPlayback.state.value.takeIf { it.matches(recording) }?.playbackAnchor,
                playbackDisplayActive = displayActive)
            val fineSeek = SpringFineSeekBinding((prepared || !trimMode) && !trimSaving, isPlaying || trimPlaybackStarting, duration.toFloat() / 1000f,
                currentFineSeekInteractionKey(), fineSeekTogglePlayback, fineSeekInteractionStart,
                fineSeekBeginAdjust, fineSeekAdjust, fineSeekUpdateShuttle, fineSeekEndAdjust, fineSeekCancelAdjust)
            if (lastWaveform == null || lastCoarse !== coarseWaveform || lastDetail !== detailWaveform ||
                lastCoarseCount != coarseBuiltCount || lastDetailCount != detailBuiltCount || lastPass != waveformPass) {
                lastWaveform = ProgressiveWaveformData(coarseWaveform, coarseBuiltCount, detailWaveform, detailBuiltCount, waveformPass)
                lastCoarse = coarseWaveform; lastDetail = detailWaveform
                lastCoarseCount = coarseBuiltCount; lastDetailCount = detailBuiltCount; lastPass = waveformPass
            }
            return NativeInlinePlayerPresentation(state, presentationActions, fineSeek, checkNotNull(lastWaveform), waveformLoading, waveformMorphStarted)
        }

        override fun close() {
            if (disposed) return
            // Same disposal ordering as the original registered effects.
            pauseForLifecycle()
            fineSeekPreviewController.close()
            disposed = true
            releasePlayer()
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            playbackBookkeeping.retireExpansion(::pauseForLifecycle)
            playbackBookkeeping.revokeResume()
            revisionScope.cancel()
        }
    }

    private fun revisionKey(recording: RecordingEntity) =
        "${recording.id}|${recording.fileIdentity}|${recording.sizeBytes}|${recording.durationMillis}|${recording.lastSeenAtMillis}"
}

internal fun inlineOrdinaryGestureIsCurrent(
    gestureGeneration: Long?,
    playbackGeneration: Long,
    matchingRecording: Boolean,
): Boolean = gestureGeneration != null && gestureGeneration == playbackGeneration && matchingRecording
