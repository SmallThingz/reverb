package app.smallthingz.reverb

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class NativeInlinePlayerCallbacks(
    val onTrimRequestConsumed: () -> Unit,
    val onTrimSaved: (RecordingEntity) -> Unit,
    val onTrimStateUncertain: () -> Unit,
    val onWaveformCached: (RecordingEntity) -> Unit,
    val onCollapse: () -> Unit,
    val onPlaybackFailed: () -> Unit,
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
        val nextKey = revisionKey(next.recording)
        if (nextKey != session.key) {
            session.close()
            input = next
            session = Revision(next.recording)
            session.start()
        } else {
            input = next
            if (previous.playbackOwner !== next.playbackOwner) {
                session.playbackBookkeeping.retireExpansion(session::pauseInlinePlayback)
                session.bindExpansion()
            }
            if (previous.screenActive != next.screenActive) {
                session.playbackBookkeeping.revokeResume()
                if (!next.screenActive) session.pauseInlinePlayback()
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
            leaveTrim = { session.trimMode = false; session.trimError = null },
            collapse = input.callbacks.onCollapse)
    }

    private fun deliver() {
        val current = session
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
        private var mediaPlayer by observed<MediaPlayer?>(null)
        private var prepared by observed(false)
        private var isPlaying by observed(false)
        private var currentPosition by observed(0)
        private var duration by observed(recording.durationMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
        private var isScrubbing by observed(false)
        private val pinnedMediaSource = AtomicReference<InlinePlayerMediaSource?>(null)
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
        private var trimEffectKey: List<Any?>? = null
        private var trimEffect: Job? = null
        private var autoEffectKey: List<Any?>? = null
        private var autoEffect: Job? = null
        private var progressEffectKey: Pair<Boolean, Boolean>? = null
        private var progressEffect: Job? = null
        private val fineSeekPreviewController = TimelineAudioPreviewController(
            onTrackReleaseFailure = { error ->
                Log.e("ReverbInlinePlayer", "Saved recording preview AudioTrack.release failed", error)
                RecordingIncidentStore.recordUnexpectedErrorInBackground(appContext, "Saved recording preview AudioTrack cleanup", error)
                AppFeedbackCenter.post(appContext.getString(R.string.audio_preview_release_failed), FeedbackTone.ERROR)
            },
        )
        private val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    activeTrimReceiver.get()?.setUiVisible(input.screenActive)
                    submitInlinePlaybackAutoStart(playbackBookkeeping, input.playbackOwner, prepared, true,
                        trimSaving || input.trimRequested || trimMode || !input.screenActive || !ownsPlaybackRow() || playbackBookkeeping.seeks.pending) {
                        seekTo(currentPosition, resume = true)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> { activeTrimReceiver.get()?.setUiVisible(false); pauseInlinePlayback() }
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
                appContext.getString(R.string.recording_read_cleanup_failed),
                FeedbackTone.ERROR,
            )
        }

        fun releasePlayer() {
            if (playbackBookkeeping.released) return
            playbackBookkeeping.released = true
            playbackBookkeeping.revokeResume()
            fineSeekPreviewController.stopShuttle()
            playbackBookkeeping.fineSeekShuttleActive = false
            isScrubbing = false
            playbackBookkeeping.seeks.cancel()
            val player = mediaPlayer
            mediaPlayer = null
            player?.runCatching { stop() }
            if (player != null) {
                runCleanupReportingThrowable(
                    cleanup = { player.release() },
                    onFailure = ::reportPlaybackCleanupFailure,
                )
            }
            runCleanupReportingThrowable(
                cleanup = { pinnedMediaSource.getAndSet(null)?.close() },
                onFailure = ::reportPlaybackCleanupFailure,
            )
            prepared = false
            isPlaying = false
        }

        fun pauseInlinePlayback() {
            playbackBookkeeping.revokeResume()
            fineSeekPreviewController.stopShuttle()
            playbackBookkeeping.fineSeekShuttleActive = false
            isScrubbing = false
            runCatching { mediaPlayer?.takeIf { prepared && !playbackBookkeeping.released }?.pause() }
            isPlaying = false
        }

        fun ownsPlaybackRow(): Boolean = input.playbackOwner?.let(input.isCurrentPlaybackOwner) == true

        fun startCurrentRowPlayback(
            player: MediaPlayer,
            owner: InlinePlaybackRowOwner? = input.playbackOwner,
        ): Boolean = startInlinePlaybackForCurrentRow(
            owner = owner,
            isCurrentOwner = input.isCurrentPlaybackOwner,
            start = { player.start() },
        )

        fun seekTo(positionMillis: Int, resume: Boolean = false) {
            playbackBookkeeping.fineSeekPosition.reset()
            val bounded = positionMillis.coerceIn(0, duration.coerceAtLeast(1))
            currentPosition = bounded
            if (!prepared || playbackBookkeeping.released) return
            val player = mediaPlayer ?: return
            runCatching {
                // Never start against the previous position; only the terminal seek callback resumes.
                if (player.isPlaying) player.pause()
                isPlaying = false
                val resumeOwner = input.playbackOwner?.takeIf {
                    resume && input.screenActive && input.isCurrentPlaybackOwner(it) && !trimSaving &&
                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                }
                playbackBookkeeping.requestSeek(bounded, resumeOwner)?.let { player.seekTo(it) }
            }.onFailure {
                releasePlayer()
                input.callbacks.onPlaybackFailed()
            }
        }

        fun trimPlaybackStartMillis(): Int = inlineTrimPlaybackStartMillis(
            trimStartMillis, trimEndMillis, fineSeekTarget,
        )

        fun toggleFineSeekPlayback() {
            val player = mediaPlayer ?: return
            if (!prepared || playbackBookkeeping.released || trimSaving || !input.screenActive || !ownsPlaybackRow() ||
                !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) return
            runCatching {
                if (playbackBookkeeping.seeks.pending && playbackBookkeeping.seeks.resume) {
                    playbackBookkeeping.revokeSeekResume()
                } else if (player.isPlaying) {
                    player.pause()
                    isPlaying = false
                } else {
                    val restartAt = when {
                        trimMode -> trimPlaybackStartMillis()
                        currentPosition >= duration -> 0
                        else -> currentPosition
                    }
                    seekTo(restartAt, resume = true)
                }
            }.onFailure {
                releasePlayer()
                input.callbacks.onPlaybackFailed()
            }
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
            prepared = prepared && mediaPlayer != null,
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
            playbackBookkeeping.fineSeekPosition.reset()
            playbackBookkeeping.resumeAfterScrub = isPlaying || playbackBookkeeping.seeks.resume
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
                onStarted = {
                    if (playbackBookkeeping.fineSeekShuttleActive && playbackBookkeeping.resumeAfterScrub) {
                        runCatching { mediaPlayer?.pause() }
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
                        val player = mediaPlayer
                        if (player != null) {
                            runCatching { startCurrentRowPlayback(player, scrubOwner) }
                                .onSuccess { started -> if (started) isPlaying = true }
                                .onFailure {
                                    releasePlayer()
                                    input.callbacks.onPlaybackFailed()
                                }
                        }
                    }
                },
            )
        }

        fun applyInlineFineSeek(deltaSeconds: Float) {
            if (!deltaSeconds.isFinite() || duration <= 0) return
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
            if (!playbackBookkeeping.fineSeekShuttleActive || duration <= 0) return
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
            )
        }

        fun finishInlineSeek() {
            playbackBookkeeping.fineSeekPosition.reset()
            isScrubbing = false
            val shouldResume = playbackBookkeeping.resumeAfterScrub
            playbackBookkeeping.resumeAfterScrub = false
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
            if (isPlaying) {
                runCatching { mediaPlayer?.pause() }
                isPlaying = false
            }
            trimStartMillis = 0
            trimEndMillis = duration
            fineSeekTarget = InlineFineSeekTarget.TRIM_START
            currentPosition = 0
            seekTo(0)
            trimError = null
            trimMode = true
            return true
        }


        fun start() {
            playbackBookkeeping.released = false
            playbackBookkeeping.initialAutoStartPending = true
            val player = configureOwnedResourceOrRelease(
                owner = MediaPlayer(),
                release = { it.release() },
            ) { configuredPlayer ->
                configuredPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build(),
                )
                configuredPlayer.setOnPreparedListener { preparedPlayer ->
                    if (!inlinePlaybackCallbackIsCurrent(
                            released = playbackBookkeeping.released,
                            disposed = disposed,
                            samePlayer = mediaPlayer === preparedPlayer,
                        )
                    ) {
                        return@setOnPreparedListener
                    }
                    prepared = true
                    val previousDuration = duration
                    duration = preparedPlayer.duration.coerceAtLeast(1)
                    if (!trimMode || trimEndMillis >= previousDuration - 1) trimEndMillis = duration
                    currentPosition = currentPosition.coerceIn(0, duration)
                    // The current expansion's effect owns auto-start. Preparation may complete
                    // after the row was collapsed or replaced while its exit is still composed.
                }
                configuredPlayer.setOnSeekCompleteListener { activePlayer ->
                    if (inlinePlaybackCallbackIsCurrent(
                            released = playbackBookkeeping.released,
                            disposed = disposed,
                            samePlayer = mediaPlayer === activePlayer,
                        ) && playbackBookkeeping.seeks.pending
                    ) {
                        runCatching {
                            val next = playbackBookkeeping.seeks.completed()
                            if (next != null) {
                                activePlayer.seekTo(next)
                            } else {
                                val resume = playbackBookkeeping.seeks.resume
                                val resumeOwner = playbackBookkeeping.seekResumeOwner
                                playbackBookkeeping.revokeSeekResume()
                                if (!isScrubbing) {
                                    currentPosition = activePlayer.currentPosition.coerceIn(0, duration)
                                    if (resume && input.screenActive && ownsPlaybackRow() && !trimSaving &&
                                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                                    ) {
                                        if (startCurrentRowPlayback(activePlayer, resumeOwner)) isPlaying = true
                                    }
                                }
                            }
                        }.onFailure {
                            releasePlayer()
                            input.callbacks.onPlaybackFailed()
                        }
                    }
                }
                configuredPlayer.setOnCompletionListener { completedPlayer ->
                    if (inlinePlaybackCallbackIsCurrent(
                            released = playbackBookkeeping.released,
                            disposed = disposed,
                            samePlayer = mediaPlayer === completedPlayer,
                        )
                    ) {
                        isPlaying = false
                        currentPosition = duration
                    }
                }
                configuredPlayer.setOnErrorListener { errorPlayer, _, _ ->
                    val samePlayer = mediaPlayer === errorPlayer
                    val ownsPlaybackResources = !playbackBookkeeping.released && samePlayer
                    val shouldReportFailure = inlinePlaybackCallbackIsCurrent(
                        released = playbackBookkeeping.released,
                        disposed = disposed,
                        samePlayer = samePlayer,
                    )
                    handleInlinePlaybackError(
                        ownsPlaybackResources = ownsPlaybackResources,
                        shouldReportFailure = shouldReportFailure,
                        releaseResources = ::releasePlayer,
                        reportFailure = input.callbacks.onPlaybackFailed,
                    )
                    true
                }
            }
            mediaPlayer = player
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
            bindExpansion()
            if (!input.screenActive) pauseInlinePlayback()
            prepare(player)
            buildWaveform()
        }

        fun bindExpansion() {
            if (!input.screenActive || !ownsPlaybackRow()) {
                playbackBookkeeping.retireExpansion(::pauseInlinePlayback)
            } else {
                playbackBookkeeping.initialAutoStartPending = !trimMode && !input.trimRequested && !trimSaving
                if (!playbackBookkeeping.initialAutoStartPending) input.playbackOwner?.consumeInitialAutoStart()
            }
        }

        private fun prepare(player: MediaPlayer) = revisionScope.launch {
            if (playbackBookkeeping.released) return@launch
            // withContext has prompt cancellation when returning to Main. Keep ownership in an
            // atomic slot before the IO block returns so a collapse at that exact boundary cannot
            // leak the opened descriptor even when the result itself is discarded.
            val openedRef = AtomicReference<InlinePlayerMediaSource?>(null)
            try {
                val opened = withContext(Dispatchers.IO) {
                    openInlinePlayerMediaSource(
                        appContext, recording, RecordingReadCleanup(::reportPlaybackCleanupFailure),
                    ).also(openedRef::set)
                }
                if (playbackBookkeeping.released || mediaPlayer !== player) return@launch
                openedRef.compareAndSet(opened, null)
                runCleanupReportingThrowable(
                    cleanup = { pinnedMediaSource.getAndSet(opened)?.close() },
                    onFailure = ::reportPlaybackCleanupFailure,
                )
                player.setDataSource(opened.descriptor)
                player.prepareAsync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!playbackBookkeeping.released && mediaPlayer === player) {
                    releasePlayer()
                    input.callbacks.onPlaybackFailed()
                }
            } finally {
                runCleanupReportingThrowable(
                    cleanup = { openedRef.getAndSet(null)?.close() },
                    onFailure = ::reportPlaybackCleanupFailure,
                )
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
            val trimKey = listOf(input.trimRequested, key, prepared, duration)
            if (trimEffectKey != trimKey) {
                trimEffectKey = trimKey
                trimEffect?.cancel()
                val requested = input.trimRequested
                val consumed = input.callbacks.onTrimRequestConsumed
                trimEffect = revisionScope.launch { if (requested && enterTrimMode()) consumed() }
            }
            val autoKey = listOf(input.screenActive, prepared, input.playbackOwner)
            if (autoEffectKey != autoKey) {
                autoEffectKey = autoKey
                autoEffect?.cancel()
                val effectInput = input
                val effectPrepared = prepared
                autoEffect = revisionScope.launch {
                    submitInlinePlaybackAutoStart(playbackBookkeeping, effectInput.playbackOwner,
                        effectPrepared, lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                        !effectInput.screenActive || !ownsPlaybackRow() || effectInput.trimRequested || trimMode || trimSaving) {
                        seekTo(currentPosition, resume = true)
                    }
                }
            }
            val progressKey = isPlaying to isScrubbing
            if (progressEffectKey != progressKey) {
                progressEffectKey = progressKey
                progressEffect?.cancel()
                progressEffect = if (isPlaying && !isScrubbing) revisionScope.launch {
                    while (true) {
                        delay(48L)
                        if (playbackBookkeeping.released) break
                        if (playbackBookkeeping.seeks.pending) continue
                        val position = runCatching { mediaPlayer?.currentPosition ?: currentPosition }.getOrDefault(currentPosition)
                        if (trimMode && position >= trimEndMillis) {
                            runCatching { mediaPlayer?.pause() }
                            isPlaying = false
                            currentPosition = trimEndMillis
                            seekTo(trimEndMillis)
                            break
                        }
                        currentPosition = position.coerceIn(0, duration.coerceAtLeast(1))
                    }
                } else null
            }
        }

        private var waveformTarget = InlineFineSeekTarget.PLAYHEAD
        private fun beginWaveform(x: Float, width: Float) {
            playbackBookkeeping.fineSeekPosition.reset()
            playbackBookkeeping.resumeAfterScrub = isPlaying || playbackBookkeeping.seeks.resume
            playbackBookkeeping.revokeSeekResume()
            if (isPlaying) { runCatching { mediaPlayer?.pause() }; isPlaying = false }
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
                lateinit var receiver: InlineTrimResultReceiver
                receiver = InlineTrimResultReceiver(
                    onSaved = { trimmed ->
                        trimSaving = false
                        acceptedInput.callbacks.onBusyChange(false)
                        trimMode = false
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
                        NotifyFileReceiver(appContext).fileReady(trimmed)
                    },
                    onDetachedFailure = { error ->
                        NotifyFileReceiver(appContext).fileFailed(
                            recordingTrimFailureMessage(appContext, error),
                            error,
                        )
                    },
                    onTerminal = { completed ->
                        activeTrimReceiver.compareAndSet(completed, null)
                    },
                )
                activeTrimReceiver.set(receiver)
                rowScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    runCommittedInlineTrim(
                        save = {
                            saveTrimmedRecordingCopy(
                                context = appContext,
                                recording = recording,
                                startMillis = trimStartMillis,
                                endMillis = trimEndMillis,
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
            { if (!trimSaving) { trimMode = false; trimError = null } }, ::saveTrim,
        )
        private val fineSeekTogglePlayback: () -> Unit = ::toggleFineSeekPlayback
        private val fineSeekInteractionStart: () -> Unit = {
            if (!trimMode) fineSeekTarget = InlineFineSeekTarget.PLAYHEAD
        }
        private val fineSeekBeginAdjust: (Float) -> Unit = ::beginInlineFineSeek
        private val fineSeekAdjust: (Float, Float) -> Unit = { delta, _ -> applyInlineFineSeek(delta) }
        private val fineSeekUpdateShuttle: (Float, Float) -> Unit = ::updateInlineFineSeekShuttle
        private val fineSeekEndAdjust: () -> Unit = ::endInlineFineSeek
        private val fineSeekCancelAdjust: () -> Unit = ::pauseInlinePlayback
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
            val enabled = input.screenActive && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                prepared && trimMode && !trimSaving && !playbackBookkeeping.released && !isScrubbing &&
                !playbackBookkeeping.fineSeekShuttleActive && !backActive
            val state = LibraryPlayerUiState(key, prepared, isPlaying, duration, currentPosition, trimMode, trimSaving,
                trimStartMillis, trimEndMillis, fineSeekTarget, trimError, enabled, backActive, backProgress, backDirection)
            val fineSeek = SpringFineSeekBinding(prepared && !trimSaving, isPlaying, duration.toFloat() / 1000f,
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
            fineSeekPreviewController.close()
            disposed = true
            releasePlayer()
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            playbackBookkeeping.retireExpansion(::pauseInlinePlayback)
            playbackBookkeeping.revokeResume()
            revisionScope.cancel()
        }
    }

    private fun revisionKey(recording: RecordingEntity) =
        "${recording.id}|${recording.fileIdentity}|${recording.sizeBytes}|${recording.durationMillis}|${recording.lastSeenAtMillis}"
}
