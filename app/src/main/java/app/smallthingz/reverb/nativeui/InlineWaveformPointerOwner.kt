package app.smallthingz.reverb.nativeui

import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.geometry.Size

/** Boundary markers straddle the waveform edges; keep their outer halves touchable. */
internal fun inlineWaveformMinimumTouchTarget(width: Float, minimum: Float, trimMode: Boolean): Size =
    Size(if (trimMode) width + minimum else minimum, minimum)

/** Exact released pointerInput keys; trimSaving is read live and does not restart the detector. */
internal data class InlineWaveformPointerKey(
    val recordingRevisionKey: String,
    val durationMillis: Int,
    val prepared: Boolean,
    val trimMode: Boolean,
)

internal data class InlineWaveformPointerBinding(
    val key: InlineWaveformPointerKey,
    val saving: Boolean,
    val begin: (Float, Float) -> Unit,
    val move: (Float, Float) -> Unit,
    val finish: (normalRelease: Boolean) -> Unit,
    val ready: Boolean = key.prepared,
)

/** MAIN-pass awaitEachGesture/awaitFirstDown(requireUnconsumed=false), without platform slop. */
internal class InlineWaveformPointerOwner {
    private var binding: InlineWaveformPointerBinding? = null
    private var handlerBinding: InlineWaveformPointerBinding? = null
    private var pointer: Long? = null
    private var awaitAllUp = false

    fun bind(next: InlineWaveformPointerBinding?) {
        if (binding?.key != next?.key) {
            cancel()
            handlerBinding = null
        }
        binding = next
    }

    fun dispatch(event: NativeDragPointerEvent, width: Int) {
        val live = binding ?: return
        // SuspendingPointerInput starts lazily at its first event and retains that handler until
        // its keys restart. Mutable source delegates (saving) remain live within that handler.
        val current = handlerBinding ?: live.also { handlerBinding = it }
        val owner = pointer
        if (owner == null) {
            if (awaitAllUp) {
                if (event.changes.none { it.pressed }) awaitAllUp = false
                return
            }
            // awaitFirstDown on Android accepts every mouse button; unlike the draggable
            // awaitPrimaryFirstDown helper, it imposes no primary-button restriction.
            if (event.changes.isEmpty() || event.changes.any { !it.changedToDown }) return
            val down = event.changes.first()
            awaitAllUp = true
            if (!live.ready || live.saving || width <= 0) return
            down.consume()
            pointer = down.id
            current.begin(down.position.x, width.toFloat())
            return
        }
        val change = event.changes.firstOrNull { it.id == owner }
        if (change == null || !change.pressed) {
            pointer = null
            awaitAllUp = event.changes.any { it.pressed }
            current.finish(change != null && !change.consumed)
        } else {
            change.consume()
            current.move(change.position.x, width.toFloat())
        }
    }

    /** A detached pointer-input node creates a fresh coroutine on reattachment. */
    fun detached() { cancel(); handlerBinding = null }

    fun cancel() {
        val owned = pointer != null
        pointer = null
        awaitAllUp = false
        if (owned) handlerBinding?.finish?.invoke(false)
    }
}
