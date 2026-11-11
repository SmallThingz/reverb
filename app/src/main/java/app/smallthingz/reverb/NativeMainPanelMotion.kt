package app.smallthingz.reverb

import androidx.activity.OnBackPressedDispatcher
import smallthingz.ui.animation.core.Animatable
import smallthingz.ui.animation.core.snap
import smallthingz.ui.animation.core.tween
import smallthingz.ui.runtime.MonotonicFrameClock
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.nativeui.NativeLibraryPredictiveBack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One presented pose shared by ordinary navigation and the existing predictive Back owner.
 * Ownership changes rebase the retained Animatable; retired jobs cannot publish stale frames.
 * The actual window supplies the gated frame clock and duration scale for both owners.
 */
internal class NativeMainPanelMotion(
    initiallyVisible: Boolean,
    parentScope: CoroutineScope,
    dispatcher: OnBackPressedDispatcher,
    lifecycleOwner: LifecycleOwner,
    private val changed: () -> Unit,
    animateBackCancellation: Boolean = false,
) : AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))
    private val frames = requireNotNull(scope.coroutineContext[MonotonicFrameClock])
    private val animated = Animatable(if (initiallyVisible) 1f else 0f)
    private var presentedProgress = animated.value
    private var targetProgress = animated.value
    private var targetSnaps = false
    private var animation: Job? = null
    private var animationGeneration = 0L
    private var animationTarget = animated.value
    private var animationSnaps = false
    private var gestureActive = false
    private var closed = false
    private val back = NativeLibraryPredictiveBack(dispatcher, lifecycleOwner, scope, frames) { active, progress, _ ->
        val wasActive = gestureActive
        if (active && !wasActive) retireAnimation()
        gestureActive = active
        if (active) presentedProgress = 1f - progress.coerceIn(0f, 1f)
        // Cleanup can reset Back's internal progress. Keep the last visible pose until its next owner publishes.
        if (!active) animatePanel()
        if (!closed) changed()
    }.apply {
        animateCancellation = animateBackCancellation
        takeCurrentBackProgress = {
            retireAnimation()
            1f - presentedProgress
        }
    }
    val settledProgress: Float get() = presentedProgress
    val backPreviewActive: Boolean get() = gestureActive || back.commitCompleted
    val panelProgress: Float get() = presentedProgress

    private fun retireAnimation() {
        ++animationGeneration
        val retired = animation
        animation = null
        retired?.cancel()
    }

    private fun animatePanel() {
        if (closed || gestureActive || back.ownsProgress) return
        if (animation?.isActive == true && animationTarget == targetProgress && animationSnaps == targetSnaps) return
        retireAnimation()
        if (presentedProgress == targetProgress) return
        val generation = animationGeneration
        val start = presentedProgress
        val target = targetProgress
        val snaps = targetSnaps
        animationTarget = target
        animationSnaps = snaps
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                animated.snapTo(start)
                if (closed || gestureActive || generation != animationGeneration) return@launch
                animated.animateTo(target, if (snaps) snap() else tween(220)) {
                    if (!closed && !gestureActive && generation == animationGeneration) {
                        presentedProgress = value
                        changed()
                    }
                }
            } finally {
                if (generation == animationGeneration) animation = null
            }
        }
        animation = next
        next.start()
    }

    /** Called after the actual retained navigation fields change, like the released SideEffect. */
    fun bind(visible: Boolean, dragging: Boolean, dragProgress: Float, backEnabled: Boolean, onBack: () -> Unit) {
        if (closed) return
        targetProgress = if (dragging) dragProgress else if (visible) 1f else 0f
        targetSnaps = dragging || (back.commitCompleted && !visible)
        back.bind(backEnabled, onBack)
        animatePanel()
    }

    fun beginForwardMotion() = back.beginForwardMotion()

    override fun close() {
        if (closed) return
        closed = true
        retireAnimation()
        back.close()
        scope.cancel()
    }
}
