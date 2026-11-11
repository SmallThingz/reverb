/*
 * Native event adapter for Reverb PredictiveBackMotion.kt and Activity Compose 1.13.0's
 * ComposePredictiveBackHandler. Apache-2.0 AndroidX channel/lifecycle behavior is retained.
 */
package app.smallthingz.reverb.nativeui

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import smallthingz.ui.animation.core.Animatable
import smallthingz.ui.animation.core.FastOutSlowInEasing
import smallthingz.ui.animation.core.tween
import smallthingz.ui.runtime.MonotonicFrameClock
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.predictiveBackCommitDurationMillis
import app.smallthingz.reverb.predictiveBackHorizontalDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One stable Library selection/one keyed inline-expansion predictive registration. The screen
 * fallback is registered first; row creation adds its later owner. Key replacement closes only the
 * old registration, and its commit closure retains the original InlinePlaybackRowOwner identity.
 */
internal class NativeLibraryPredictiveBack(
    dispatcher: OnBackPressedDispatcher,
    lifecycleOwner: LifecycleOwner,
    parentScope: CoroutineScope,
    frames: MonotonicFrameClock,
    private val frame: (active: Boolean, progress: Float, direction: Float) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + frames + Job(parentScope.coroutineContext[Job]))
    private val progress = Animatable(0f)
    private var edge = BackEventCompat.EDGE_NONE
    private var gestureActive = false
    private var committed = false
    private var enabled = false
    private var onBack: () -> Unit = {}
    private var enabledEffect: Job? = null
    private var activeChannel: Channel<BackEventCompat>? = null
    private var activeJob: Job? = null
    private var activeJobStarted = false
    private var predictive = false
    private var closed = false
    /** Library's shared Back/Incidents preview opts in; selection and inline owners retain their contract. */
    var animateCancellation: Boolean = false
    /** Panel-only handoff: revoke its ordinary frame publisher and return the currently displayed Back fraction. */
    var takeCurrentBackProgress: (() -> Float)? = null
    private var cancelling = false
    /** Main's retained panels use the same released completion marker as inline Library owners. */
    val commitCompleted: Boolean get() = committed
    val ownsProgress: Boolean get() = activeJob != null

    /** A new forward drag must not inherit a previous completed predictive dismissal. */
    fun beginForwardMotion() {
        if (closed) return
        committed = false
        publish()
    }

    private val callback = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            val restartingCancellation = animateCancellation && cancelling
            val panelProgress = if (isEnabled) takeCurrentBackProgress?.invoke()?.coerceIn(0f, 1f) else null
            val heldProgress = panelProgress ?: if (restartingCancellation) progress.value else 0f
            val anchored = restartingCancellation || panelProgress != null
            val rawAnchor = if (anchored) backEvent.progress.coerceIn(0f, 1f) else null
            retireGesture()
            if (isEnabled) {
                predictive = true
                if (anchored) edge = backEvent.swipeEdge
                launchGesture(heldProgress, rawAnchor)
            }
        }
        override fun handleOnBackProgressed(backEvent: BackEventCompat) { activeChannel?.trySend(backEvent) }
        override fun handleOnBackCancelled() {
            if (cancelling) return
            if (animateCancellation && gestureActive && progress.value > 0f && isEnabled)
                settleCancelledGesture()
            else cancelGesture()
        }
        override fun handleOnBackPressed() {
            val panelProgress = if (isEnabled && (activeChannel == null || !predictive))
                takeCurrentBackProgress?.invoke()?.coerceIn(0f, 1f) else null
            val resumedProgress = panelProgress ?: if (cancelling) progress.value else 0f
            if (activeChannel != null && !predictive) retireGesture()
            predictive = false
            // Initial publication can synchronously replace this gesture. Close only its channel.
            val channel = activeChannel ?: run { retireGesture(); launchGesture(resumedProgress) }
            channel.close()
        }
    }
    init { dispatcher.addCallback(lifecycleOwner, callback) }

    fun bind(enabled: Boolean, onBack: () -> Unit) {
        if (closed) return
        this.onBack = onBack
        val retirePanelGesture = !enabled && callback.isEnabled && takeCurrentBackProgress != null && !committed
        if (!enabled && callback.isEnabled && activeJob?.isActive == false) cancelGesture()
        callback.isEnabled = enabled
        if (retirePanelGesture) cancelGesture()
        if (this.enabled == enabled) return
        this.enabled = enabled
        enabledEffect?.cancel()
        enabledEffect = scope.launch {
            if (enabled) {
                committed = false
                if (!gestureActive) progress.snapTo(0f)
            } else if (!committed) {
                gestureActive = false
                edge = BackEventCompat.EDGE_NONE
                progress.snapTo(0f)
            }
            publish()
        }
    }
    private fun publish() {
        if (!closed) frame(gestureActive, progress.value, predictiveBackHorizontalDirection(edge))
    }

    private fun launchGesture(initialProgress: Float = 0f, rawAnchor: Float? = null): Channel<BackEventCompat> {
        val channel = Channel<BackEventCompat>(Channel.BUFFERED, BufferOverflow.SUSPEND)
        activeChannel = channel
        // Main.immediate may run the body before launch returns. Install ownership first.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext()[Job]
            if (activeJob === owner) activeJobStarted = true
            var completed = false
            try {
                if (!callback.isEnabled) return@launch
                committed = false
                progress.snapTo(initialProgress)
                gestureActive = true
                publish()
                currentCoroutineContext().ensureActive()
                channel.consumeAsFlow().collect { event ->
                    edge = event.swipeEdge
                    val rawProgress = event.progress.coerceIn(0f, 1f)
                    progress.snapTo(if (rawAnchor == null) rawProgress
                        else restartedGestureProgress(initialProgress, rawAnchor, rawProgress))
                    publish()
                    currentCoroutineContext().ensureActive()
                }
                val duration = predictiveBackCommitDurationMillis(progress.value)
                if (duration == 0) { progress.snapTo(1f); publish() }
                else progress.animateTo(1f, tween(duration, easing = FastOutSlowInEasing)) { publish() }
                currentCoroutineContext().ensureActive()
                completed = true
                committed = true
                onBack()
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                progress.animateTo(0f, tween(160, easing = FastOutSlowInEasing)) { publish() }
            } finally {
                finishGesture(owner, completed)
            }
        }
        activeJobStarted = false
        activeJob = job
        job.start()
        return channel
    }
    private fun settleCancelledGesture() {
        val retiredJob = activeJob
        val retiredChannel = activeChannel
        // Revoke before cancellation: the old finally block cannot clear the replacement owner.
        activeJob = null
        activeChannel = null
        activeJobStarted = false
        predictive = false
        cancelling = true
        retiredJob?.cancel()
        retiredChannel?.cancel(CancellationException("onBack cancelled"))
        val settle = scope.launch(start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext()[Job]
            if (activeJob === owner) activeJobStarted = true
            try {
                progress.animateTo(0f, tween(160, easing = FastOutSlowInEasing)) { publish() }
            } finally {
                finishGesture(owner, completed = false)
            }
        }
        activeJob = settle
        settle.start()
    }
    private fun restartedGestureProgress(held: Float, anchor: Float, raw: Float): Float {
        // The new stream starts at its own raw anchor, not at the interrupted visual fraction.
        return when {
            raw == anchor -> held
            raw == 1f -> 1f
            raw < anchor -> held * raw / anchor
            else -> held + (1f - held) * (raw - anchor) / (1f - anchor)
        }
    }
    private fun cancelGesture() {
        val job = activeJob
        val channel = activeChannel
        val started = activeJobStarted
        activeChannel = null
        predictive = false
        // The released handler cancels the gesture job as well as its event stream. Keep its
        // ownership until terminal cleanup publishes, and capture both before that can reenter.
        job?.cancel()
        channel?.cancel(CancellationException("onBack cancelled"))
        // A dispatched job cancelled before body entry never reaches its finally block.
        if (job != null && !started) finishGesture(job, completed = false)
    }
    private fun finishGesture(owner: Job?, completed: Boolean) {
        // Replacement/close revokes ownership before an old gesture can publish cleanup.
        if (activeJob === owner) {
            cancelling = false
            activeJob = null
            activeJobStarted = false
            activeChannel = null
            predictive = false
            if (!completed) committed = false
            gestureActive = false
            edge = BackEventCompat.EDGE_NONE
            publish()
        }
    }
    private fun retireGesture() {
        cancelling = false
        val job = activeJob
        val channel = activeChannel
        activeJob = null
        activeJobStarted = false
        activeChannel = null
        predictive = false
        job?.cancel()
        channel?.cancel()
    }
    override fun close() {
        if (closed) return
        closed = true
        callback.remove()
        retireGesture()
        scope.cancel()
    }
}
