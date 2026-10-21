/*
 * Native event adapter for Reverb PredictiveBackMotion.kt and Activity Compose 1.13.0's
 * ComposePredictiveBackHandler. Apache-2.0 AndroidX channel/lifecycle behavior is retained.
 */
package app.smallthingz.reverb.nativeui

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.predictiveBackCommitDurationMillis
import app.smallthingz.reverb.predictiveBackHorizontalDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
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
            cancelGesture()
            if (isEnabled) { predictive = true; launchGesture() }
        }
        override fun handleOnBackProgressed(backEvent: BackEventCompat) { activeChannel?.trySend(backEvent) }
        override fun handleOnBackCancelled() = cancelGesture()
        override fun handleOnBackPressed() {
            if (activeChannel != null && !predictive) cancelGesture()
            if (activeChannel == null) { predictive = false; launchGesture() }
            activeChannel?.close()
            predictive = false
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

    private fun launchGesture() {
        val channel = Channel<BackEventCompat>(Channel.BUFFERED, BufferOverflow.SUSPEND)
        activeChannel = channel
        activeJob = scope.launch {
            if (!callback.isEnabled) return@launch
            committed = false
            progress.snapTo(0f)
            gestureActive = true
            publish()
            var completed = false
            try {
                channel.consumeAsFlow().collect { event ->
                    edge = event.swipeEdge
                    progress.snapTo(event.progress.coerceIn(0f, 1f))
                    publish()
                }
                val duration = predictiveBackCommitDurationMillis(progress.value)
                if (duration == 0) { progress.snapTo(1f); publish() }
                else progress.animateTo(1f, tween(duration, easing = FastOutSlowInEasing)) { publish() }
                completed = true
                committed = true
                onBack()
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                progress.animateTo(0f, tween(160, easing = FastOutSlowInEasing)) { publish() }
            } finally {
                if (!completed) committed = false
                gestureActive = false
                edge = BackEventCompat.EDGE_NONE
                publish()
            }
        }
    }
    private fun cancelGesture() {
        activeChannel?.cancel(CancellationException("onBack cancelled"))
        activeJob?.cancel()
        activeChannel = null
        activeJob = null
        predictive = false
    }
    override fun close() {
        if (closed) return
        closed = true
        callback.remove()
        cancelGesture()
        scope.cancel()
    }
}
