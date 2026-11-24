package app.smallthingz.reverb

import android.content.Context
import android.util.Log
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.geometry.Rect
import smallthingz.ui.reverb.ReverbRangeOpeningMotion
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.nativeui.NativeInlineWaveformMotion
import smallthingz.ui.components.waveform.ProgressiveWaveformData
import smallthingz.ui.components.waveform.ProgressiveWaveformFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal typealias NativeRangeExportPresentation = smallthingz.ui.reverb.NativeRangeExportPresentation

/**
 * RangeExportHomeContent's retained effect/model owner. A new owner is created for a different
 * selected buffer, just as the released remember/effect keys retire that editor. The shared
 * RangeExportEditorState retains preview/scrub/wheel generations and accepted audio ownership.
 *
 * Caller owns TimelineSnapshot lifetime. This class never closes, exports or persists it.
 * onRender binds ordinary Views only; no service, permissions, record settings or Composition.
 */
internal class NativeRangeExportOwner(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    val selectedBuffer: ReverbService.BufferSlot,
    initialDurationSeconds: Float,
    hostScope: CoroutineScope,
    initialVisualizerVisible: Boolean,
    private var onRender: (RangeExportEditorState, NativeRangeExportPresentation) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(hostScope.coroutineContext +
        SupervisorJob(hostScope.coroutineContext[Job]))
    private val frames = requireNotNull(scope.coroutineContext[MonotonicFrameClock]) {
        "Range rendering must inherit the owning window's frame clock"
    }
    private val durationScale get() = scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    private var closed = false
    private var invalidation: NativeRangeUiInvalidation? = null
    val state = RangeExportEditorState(
        initialDurationSeconds = initialDurationSeconds,
        rememberedRangeExport = getRememberedRangeExport(context, selectedBuffer),
        onPreviewReleaseFailure = { error ->
            RecordingIncidentStore.recordUnexpectedErrorInBackground(appContext,
                "Range preview resource cleanup", error)
            AppFeedbackCenter.post(actionableErrorMessage(appContext, appContext.getString(R.string.audio_preview_release_failed), error), FeedbackTone.ERROR)
        },
        onPreviewFailure = { error ->
            Log.e("ReverbRangeAudio", "Range preview playback failed", error)
            RecordingIncidentStore.recordUnexpectedErrorInBackground(appContext, "Range preview playback", error)
            AppFeedbackCenter.post(actionableErrorMessage(appContext, appContext.getString(R.string.audio_preview_failed), error), FeedbackTone.ERROR)
        },
        // Nonthrowing queue notification only. Never render halfway through a model mutation.
        notifyNativeUiChanged = { invalidation?.request() },
    )
    private var sourceGeometry: RangeMorphSourceGeometry? = null
    private var targetBounds: Rect? = null
    private var visualizerVisible = initialVisualizerVisible
    private var backProgress = 0f
    private var maximumDurationSeconds = 0.0
    private var currentSnapshot: ReverbService.TimelineSnapshot? = null
    private var waveformJob: Job? = null
    private val opening = ReverbRangeOpeningMotion(frames, scope, { durationScale }) {
        if (!closed && waveformData != null) renderCurrent()
    }
    private var waveformCoarse: FloatArray? = null
    private var waveformDetail: FloatArray? = null
    private var waveformLoading: Boolean? = null
    private var waveformData: ProgressiveWaveformData? = null
    private var waveformFrame = ProgressiveWaveformFrame(0f, 0f, 0f, 0f, 0f, 0f, true)
    private var updatingWaveform = false
    private val waveformMotion = NativeInlineWaveformMotion(frames, scope, { durationScale }) { frame ->
        waveformFrame = frame
        if (!updatingWaveform && waveformData != null && !closed) renderCurrent()
    }
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> state.setPlaybackAllowed(visualizerVisible)
            Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> state.setPlaybackAllowed(false)
            else -> Unit
        }
    }

    init {
        invalidation = NativeRangeUiInvalidation(scope, ::refreshAndRender)
        state.setPlaybackAllowed(visualizerVisible &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            RecordingPlayback.state.collect { playback ->
                // Notification/media controls can resume saved playback without navigation.
                if (!closed && playback.wantsPlayback) state.pausePreview()
            }
        }
    }

    /** Current callbacks and display inputs; input admission continues to read the live model. */
    fun bind(
        snapshot: ReverbService.TimelineSnapshot?,
        sourceGeometry: RangeMorphSourceGeometry?,
        maxExportDurationSeconds: Double,
        backProgress: Float,
        visualizerVisible: Boolean,
        onRender: (RangeExportEditorState, NativeRangeExportPresentation) -> Unit,
    ) {
        check(!closed)
        this.onRender = onRender
        this.sourceGeometry = sourceGeometry
        maximumDurationSeconds = maxExportDurationSeconds
        val beginningBack = this.backProgress <= 0f && backProgress > 0f
        this.backProgress = backProgress
        if (beginningBack) state.pausePreview()
        if (this.visualizerVisible != visualizerVisible) {
            this.visualizerVisible = visualizerVisible
            state.setPlaybackAllowed(visualizerVisible &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        if (currentSnapshot !== snapshot) replaceSnapshot(snapshot)
        startOpeningWhenMeasured()
        // Seed the entering controls in this bind, before the View can draw its first frame.
        if (waveformData == null) refreshAndRender() else invalidation?.request()
    }

    fun waveformMeasured(boundsInRoot: Rect) {
        if (closed || targetBounds == boundsInRoot) return
        targetBounds = boundsInRoot
        startOpeningWhenMeasured()
        invalidation?.request()
    }

    private fun replaceSnapshot(snapshot: ReverbService.TimelineSnapshot?) {
        currentSnapshot = snapshot
        waveformJob?.cancel()
        waveformJob = null
        if (snapshot == null) {
            state.detachSnapshotPresentation()
            return
        }
        state.attachSnapshot(snapshot)
        state.resetWaveformConstruction()
        waveformJob = scope.launch {
            // Source starts disk sampling only after its760ms opening reaches .96.
            opening.awaitWaveformReadiness()
            constructRangeWaveform(
                build = {
                    buildProgressiveWaveform(
                        readPass = { pass, onBucket -> snapshot.readWaveformEnvelopeProgressive(pass, onBucket) },
                        onPassStarted = { if (it == RangeWaveformPass.DETAIL) state.beginDetailedWaveformPass() },
                        onSnapshot = state::publishWaveformSnapshot,
                    )
                },
                onFailure = { error ->
                    RecordingIncidentStore.recordUnexpectedErrorInBackground(appContext,
                        "Range waveform construction", error)
                    AppFeedbackCenter.post(actionableErrorMessage(appContext, appContext.getString(R.string.waveform_load_failed), error), FeedbackTone.ERROR)
                },
                onFinished = state::finishWaveformConstruction,
            )
        }
    }

    private fun startOpeningWhenMeasured() {
        if (sourceGeometry != null && targetBounds != null) opening.start()
    }

    private fun refreshAndRender() {
        if (closed) return
        val coarse = state.coarseWaveform
        val coarseCount = state.coarseWaveformBuiltCount
        val detail = state.detailWaveform
        val detailCount = state.detailWaveformBuiltCount
        val detailAvailable = state.waveformPass == RangeWaveformPass.DETAIL
        val previousWaveform = waveformData
        val waveformChanged = previousWaveform == null ||
            waveformCoarse !== coarse || previousWaveform.coarse.builtCount != coarseCount ||
            waveformDetail !== detail || previousWaveform.detail.builtCount != detailCount ||
            previousWaveform.detailAvailable != detailAvailable
        if (waveformChanged || waveformLoading != state.waveformLoading) {
            if (waveformChanged) {
                waveformData = ProgressiveWaveformData(coarse, coarseCount, detail, detailCount, detailAvailable)
                waveformCoarse = coarse
                waveformDetail = detail
            }
            waveformLoading = state.waveformLoading
            updatingWaveform = true
            try {
                // Shared430/330ms reveal and absolute-phase/loading settling behavior. Its Library
                // morph stays at0; the independent range opening supplies the exact handoff below.
                waveformMotion.bind(checkNotNull(waveformData), state.waveformLoading, morphStarted = false)
            } finally { updatingWaveform = false }
        }
        renderCurrent()
    }

    private fun renderCurrent() {
        if (closed) return
        val data = waveformData ?: return
        onRender(state, opening.presentation(
            sourceGeometry, targetBounds, backProgress, visualizerVisible, currentSnapshot != null,
            maximumDurationSeconds, data, waveformFrame,
            startFraction = state.startSeconds / state.durationSeconds,
            endFraction = state.endSeconds / state.durationSeconds,
        ))
    }

    override fun close() {
        if (closed) return
        closed = true
        lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        invalidation?.close(); invalidation = null
        opening.close()
        waveformMotion.close()
        scope.cancel()
        state.setPlaybackAllowed(false)
        state.close()
    }
}
