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
    private var predictive = false
    private var closed = false
    /** Main's retained panels use the same released completion marker as inline Library owners. */
    val commitCompleted: Boolean get() = committed

    /** A new forward drag must not inherit a previous completed predictive dismissal. */
    fun beginForwardMotion() {
        if (closed) return
        committed = false
        publish()
    }

    private val callback = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            retireGesture()
            if (isEnabled) { predictive = true; launchGesture() }
        }
        override fun handleOnBackProgressed(backEvent: BackEventCompat) { activeChannel?.trySend(backEvent) }
        override fun handleOnBackCancelled() = cancelGesture()
        override fun handleOnBackPressed() {
            if (activeChannel != null && !predictive) retireGesture()
            predictive = false
            // Initial publication can synchronously replace this gesture. Close only its channel.
            val channel = activeChannel ?: run { retireGesture(); launchGesture() }
            channel.close()
        }
    }
    init { dispatcher.addCallback(lifecycleOwner, callback) }

    fun bind(enabled: Boolean, onBack: () -> Unit) {
        if (closed) return
        this.onBack = onBack
        if (!enabled && callback.isEnabled && activeJob?.isActive == false) cancelGesture()
        callback.isEnabled = enabled
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

    private fun launchGesture(): Channel<BackEventCompat> {
        val channel = Channel<BackEventCompat>(Channel.BUFFERED, BufferOverflow.SUSPEND)
        activeChannel = channel
        // Main.immediate may run the body before launch returns. Install ownership first.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext()[Job]
            var completed = false
            try {
                if (!callback.isEnabled) return@launch
                committed = false
                progress.snapTo(0f)
                gestureActive = true
                publish()
                currentCoroutineContext().ensureActive()
                channel.consumeAsFlow().collect { event ->
                    edge = event.swipeEdge
                    progress.snapTo(event.progress.coerceIn(0f, 1f))
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
                // Replacement/close revokes ownership before cancellation can resume this body.
                if (activeJob === owner) {
                    activeJob = null
                    activeChannel = null
                    predictive = false
                    if (!completed) committed = false
                    gestureActive = false
                    edge = BackEventCompat.EDGE_NONE
                    publish()
                }
            }
        }
        activeJob = job
        job.start()
        return channel
    }
    private fun cancelGesture() {
        val channel = activeChannel
        activeChannel = null
        predictive = false
        // Cancel the event stream, retaining the job that owns the 160ms return animation.
        channel?.cancel(CancellationException("onBack cancelled"))
    }
    private fun retireGesture() {
        val job = activeJob
        val channel = activeChannel
        activeJob = null
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
