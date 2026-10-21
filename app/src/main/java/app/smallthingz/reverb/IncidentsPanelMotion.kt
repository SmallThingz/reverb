package app.smallthingz.reverb

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A closed incident panel must be outside the viewport before its composition is removed. */
internal fun incidentsPanelTranslationX(viewportWidth: Float, openProgress: Float): Float =
    viewportWidth.coerceAtLeast(0f) * (1f - openProgress.coerceIn(0f, 1f))

/** Keeps a partial opening stationary while predictive Back owns the rendered position. */
internal class IncidentsPanelMotionState(
    initiallyOpen: Boolean,
    private val scope: CoroutineScope,
    private val durationMillis: Int,
) {
    var progress by mutableFloatStateOf(if (initiallyOpen) 1f else 0f)
        private set
    // Composition only needs to change when the panel crosses the closed boundary.
    val hasVisibleProgress by derivedStateOf { progress > 0f }
    private val animation = Animatable(progress)
    private var settling: Job? = null
    private var target: Float? = null
    private var generation = 0L

    private fun stop(): Float {
        generation++
        settling?.cancel()
        settling = null
        target = null
        return progress
    }

    fun snapTo(value: Float) {
        stop()
        progress = value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: progress
    }

    fun beginBack(interruptedOpenProgress: Float?): Float {
        if (interruptedOpenProgress != null) snapTo(interruptedOpenProgress)
        return stop()
    }

    fun resumeAfterBack(interruptedOpenProgress: Float?, open: Boolean) {
        if (interruptedOpenProgress != null) snapTo(interruptedOpenProgress)
        settleTo(open)
    }

    fun settleTo(open: Boolean) {
        val end = if (open) 1f else 0f
        if (target == end && settling?.isActive == true) return
        val start = stop()
        if (start == end) return
        target = end
        val owner = generation
        settling = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            animation.snapTo(start)
            animation.animateTo(end, tween(durationMillis)) {
                if (generation == owner) progress = value
            }
        }
    }
}

