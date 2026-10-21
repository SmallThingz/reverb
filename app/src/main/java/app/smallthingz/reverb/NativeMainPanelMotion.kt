package app.smallthingz.reverb

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.nativeui.NativeLibraryPredictiveBack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * MainScreen's two animateFloatAsState channels plus its already-shared predictive Back owner.
 * The pinned animation-core 1.11.2 AnimateAsState.kt 411–443 conflation/additional poll and
 * child animateTo ownership are retained. No Composition, independent timer or platform animator.
 * The actual window supplies the gated frame clock and duration scale.
 */
internal class NativeMainPanelMotion(
    initiallyVisible: Boolean,
    parentScope: CoroutineScope,
    dispatcher: OnBackPressedDispatcher,
    lifecycleOwner: LifecycleOwner,
    private val changed: () -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))
    private val frames = requireNotNull(scope.coroutineContext[MonotonicFrameClock])
    private val animated = Animatable(if (initiallyVisible) 1f else 0f)
    private val targets = Channel<Float>(Channel.CONFLATED)
    private var spec: AnimationSpec<Float> = tween(220)
    private var visible = initiallyVisible
    private var gestureActive = false
    private var gestureProgress = 0f
    private var closed = false
    private val back = NativeLibraryPredictiveBack(dispatcher, lifecycleOwner, scope, frames) { active, progress, _ ->
        gestureActive = active
        gestureProgress = progress
        if (!closed) changed()
    }
    val settledProgress: Float get() = animated.value
    val panelProgress: Float get() = predictiveBackPanelOpenProgress(
        animated.value, gestureActive, gestureProgress, back.commitCompleted, visible)

    init {
        scope.launch {
            for (target in targets) {
                val newTarget = targets.tryReceive().getOrNull() ?: target
                launch {
                    if (newTarget != animated.targetValue) {
                        animated.animateTo(newTarget, spec) { if (!closed) changed() }
                    }
                }
            }
        }
    }

    /** Called after the actual retained navigation fields change, like the released SideEffect. */
    fun bind(visible: Boolean, dragging: Boolean, dragProgress: Float, backEnabled: Boolean, onBack: () -> Unit) {
        if (closed) return
        this.visible = visible
        back.bind(backEnabled, onBack)
        spec = if (dragging || (back.commitCompleted && !visible)) snap() else tween(220)
        targets.trySend(if (dragging) dragProgress else if (visible) 1f else 0f)
    }

    fun beginForwardMotion() = back.beginForwardMotion()

    override fun close() {
        if (closed) return
        closed = true
        back.close()
        targets.close()
        scope.cancel()
    }
}
