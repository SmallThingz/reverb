package app.smallthingz.reverb

import android.content.Context
import android.util.Log
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.nativeui.LibraryTweenValue
import app.smallthingz.reverb.nativeui.NativeInlineWaveformMotion
import app.smallthingz.reverb.nativeui.ProgressiveWaveformData
import app.smallthingz.reverb.nativeui.ProgressiveWaveformFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal data class NativeRangeExportPresentation(
    val sourceGeometry: RangeMorphSourceGeometry?,
    val waveformTargetBoundsInRoot: Rect?,
    val transitionStarted: Boolean,
    val interactionReady: Boolean,
    val backProgress: Float,
    val visualizerVisible: Boolean,
    val snapshotPresent: Boolean,
    val maximumDurationSeconds: Double,
    val chromeAlpha: Float,
    val waveformData: ProgressiveWaveformData,
    val waveformFrame: ProgressiveWaveformFrame,
)

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
            AppFeedbackCenter.post(appContext.getString(R.string.audio_preview_release_failed), FeedbackTone.ERROR)
        },
        onPreviewFailure = { error ->
            Log.e("ReverbRangeAudio", "Range preview playback failed", error)
            RecordingIncidentStore.recordUnexpectedErrorInBackground(appContext, "Range preview playback", error)
            AppFeedbackCenter.post(appContext.getString(R.string.audio_preview_failed), FeedbackTone.ERROR)
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
    private var transitionStarted = false
    private var interactionReady = false
    private val opening = LibraryTweenValue(0f, Float.VectorConverter,
        tween<Float>(760, easing = FastOutSlowInEasing), { AnimationVector1D(0f) })
    private val openingProgress = MutableStateFlow(0f)
    private var openingJob: Job? = null
    private data class WaveformKey(
        val coarse: FloatArray, val coarseCount: Int,
        val detail: FloatArray, val detailCount: Int, val pass: RangeWaveformPass,
    )
    private var waveformKey: WaveformKey? = null
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
        invalidation?.request()
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
        invalidation?.request()
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
        waveformJob = if (snapshot == null) null else scope.launch {
            state.attachSnapshot(snapshot)
            state.resetWaveformConstruction()
            // Source starts disk sampling only after its760ms opening reaches .96.
            openingProgress.first { it >= .96f }
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
                    AppFeedbackCenter.post(appContext.getString(R.string.waveform_load_failed), FeedbackTone.ERROR)
                },
                onFinished = state::finishWaveformConstruction,
            )
        }
    }

    private fun startOpeningWhenMeasured() {
        if (transitionStarted || sourceGeometry == null || targetBounds == null) return
        transitionStarted = true
        opening.target(1f, durationScale)
        lateinit var next: Job
        next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (!closed && opening.running) {
                    frames.withFrameNanos { time ->
                        if (!closed) {
                            opening.frame(time, durationScale)
                            openingProgress.value = opening.value
                            if (opening.value >= .98f) interactionReady = true
                            if (waveformData != null) renderCurrent()
                        }
                    }
                }
            } finally { if (openingJob === next) openingJob = null }
        }
        openingJob = next
        next.start()
    }

    private fun refreshAndRender() {
        if (closed) return
        val key = WaveformKey(state.coarseWaveform, state.coarseWaveformBuiltCount,
            state.detailWaveform, state.detailWaveformBuiltCount, state.waveformPass)
        if (waveformKey != key || waveformLoading != state.waveformLoading) {
            if (waveformKey != key) {
                waveformKey = key
                waveformData = ProgressiveWaveformData(key.coarse, key.coarseCount,
                    key.detail, key.detailCount, key.pass)
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
        val visual = opening.value * predictiveBackOpenProgress(backProgress)
        val morph = ((visual - RANGE_BLOB_MORPH_HANDOFF_PROGRESS) /
            (1f - RANGE_BLOB_MORPH_HANDOFF_PROGRESS)).coerceIn(0f, 1f)
        onRender(state, NativeRangeExportPresentation(
            sourceGeometry, targetBounds, transitionStarted, interactionReady, backProgress,
            visualizerVisible, currentSnapshot != null, maximumDurationSeconds,
            ((visual - .46f) / .42f).coerceIn(0f, 1f), data,
            waveformFrame.copy(morphProgress = morph,
                startFraction = state.startSeconds / state.durationSeconds,
                endFraction = state.endSeconds / state.durationSeconds, morphFromBlob = true),
        ))
    }

    override fun close() {
        if (closed) return
        closed = true
        lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        invalidation?.close(); invalidation = null
        waveformMotion.close()
        scope.cancel()
        state.setPlaybackAllowed(false)
        state.close()
    }
}
