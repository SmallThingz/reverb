package app.smallthingz.reverb.nativeui

import smallthingz.ui.animation.smoothProgressSpring

/** Presentation only: operation identity and the reported fraction always remain authoritative. */
internal class ClearProgressMotion {
    private val fill = smoothProgressSpring()
    private var operationId: Long? = null
    var progress = 0f
        private set
    val value: Float get() = fill.value.coerceIn(0f, progress)
    val running: Boolean get() = fill.isRunning

    fun bind(operation: Long, fraction: Float, terminal: Boolean, scale: Float) {
        val next = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        val reset = operationId != operation || next < progress
        operationId = operation
        progress = next
        if (reset || terminal || scale <= 0f) fill.snapTo(next)
        else fill.retarget(next, scale)
    }

    fun frame(time: Long, scale: Float) = fill.frame(time, scale)
    fun snap() = fill.snapTo(progress)
}
