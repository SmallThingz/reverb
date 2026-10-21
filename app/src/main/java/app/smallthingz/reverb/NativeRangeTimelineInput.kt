package app.smallthingz.reverb

import kotlin.math.abs
import kotlinx.coroutines.CancellationException

/**
 * Released RangeExportTimeline / RangeTimelineBar awaitEachGesture bodies, supplied Main-pass
 * projected events by the owning window. No Android recognizer or audio/state copy is introduced.
 *
 * The waveform pointerInput exists even when interaction is disabled; its gesture checks live
 * ownership. Endpoint pointerInput exists only while enabled. The host preserves that distinction.
 */
internal class NativeRangeTimelineInput(
    private val state: RangeExportEditorState,
    private val enabled: () -> Boolean,
    private val timelineWidth: () -> Float,
    private val secondsForWaveformX: (Float) -> Float,
    private val markerCenter: (RangeEditTarget) -> Float,
    private val touchSlop: () -> Float,
) : AutoCloseable {
    val waveform = WaveformGesture()
    val start = EndpointGesture(RangeEditTarget.START)
    val end = EndpointGesture(RangeEditTarget.END)
    private var durationKey = state.durationSeconds
    private var widthKey = timelineWidth()
    private var enabledKey = enabled()
    private var slopKey = touchSlop()

    /** Pointer-key changes retire exactly the corresponding source coroutine, not other owners. */
    fun refreshKeys() {
        val duration = state.durationSeconds
        val width = timelineWidth()
        val nowEnabled = enabled()
        val slop = touchSlop()
        if (duration != durationKey || width != widthKey || nowEnabled != enabledKey) waveform.cancel()
        if (duration != durationKey || width != widthKey || nowEnabled != enabledKey || slop != slopKey) {
            start.cancel(); end.cancel()
        }
        durationKey = duration; widthKey = width; enabledKey = nowEnabled; slopKey = slop
    }

    private fun newGesture() = state.inputGesture(
        interactionAllowed = enabled,
        onCancel = { stillCurrent -> if (stillCurrent) state.pausePreview() },
    )

    internal abstract inner class Gesture {
        protected var owned: RangeInputGesture? = null
        protected var pointerId = Long.MIN_VALUE
        protected var insideFinally = false
        private var awaitingAllUp = false

        fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
            if (pass != NativePointerPass.MAIN) return
            if (awaitingAllUp) {
                if (event.changes.none { it.pressed }) awaitingAllUp = false
                return
            }
            val active = owned
            if (active == null) {
                // Android awaitFirstDown's expect/actual admits secondary mouse DOWN too.
                if (event.changes.isEmpty() || !event.changes.all { it.changedToDown }) return
                if (requiresUnconsumedDown && event.changes.any { it.consumed }) return
                val down = event.changes.first()
                pointerId = down.id
                owned = newGesture()
                try {
                    if (!begin(down)) finish(insideFinally, event)
                } catch (error: Throwable) {
                    failed(error)
                    throw error
                }
                return
            }
            val change = event.changes.firstOrNull { it.id == pointerId }
            try {
                if (change == null || !change.pressed) {
                    if (change == null || change.consumed) active.cancel()
                    finish(true, event)
                } else if (!move(change)) finish(true, event)
            } catch (error: Throwable) {
                failed(error)
                throw error
            }
        }

        protected abstract val requiresUnconsumedDown: Boolean
        protected abstract fun begin(down: NativeDragPointerChange): Boolean
        protected abstract fun move(change: NativeDragPointerChange): Boolean
        protected abstract fun normalFinish()

        private fun finish(runFinally: Boolean, event: NativeDragPointerEvent) {
            val previous = owned
            owned = null; pointerId = Long.MIN_VALUE; insideFinally = false
            // Source first rejected perform happens before try/finally; later returns still finish.
            if (runFinally) previous?.finish(::normalFinish)
            awaitingAllUp = event.changes.any { it.pressed }
        }

        private fun failed(error: Throwable) {
            val previous = owned
            val runFinally = insideFinally
            owned = null; pointerId = Long.MIN_VALUE; insideFinally = false
            if (runFinally) {
                if (error is CancellationException) previous?.cancel()
                previous?.finish(::normalFinish)
            }
        }

        fun cancel() {
            val previous = owned
            val runFinally = insideFinally
            owned = null; pointerId = Long.MIN_VALUE; awaitingAllUp = false; insideFinally = false
            if (runFinally) {
                previous?.cancel()
                previous?.finish(::normalFinish)
            }
        }
    }

    internal inner class WaveformGesture : Gesture() {
        override val requiresUnconsumedDown = true
        private var target = RangeEditTarget.START
        override fun begin(down: NativeDragPointerChange): Boolean {
            val gesture = checkNotNull(owned)
            target = state.lastTarget
            val downSeconds = secondsForWaveformX(down.position.x)
            if (!gesture.perform { state.beginBoundaryScrub(target) }) return false
            insideFinally = true
            return gesture.perform { state.updateBoundaryScrub(target, downSeconds) }
        }
        override fun move(change: NativeDragPointerChange) = checkNotNull(owned).perform {
            change.consume()
            state.updateBoundaryScrub(target, secondsForWaveformX(change.position.x))
        }
        override fun normalFinish() = state.endBoundaryScrub()
    }

    internal inner class EndpointGesture(private val target: RangeEditTarget) : Gesture() {
        override val requiresUnconsumedDown = false
        private var origin = 0f
        private var centerAtDown = 0f
        private var downLocalX = 0f
        private var dragging = false
        override fun begin(down: NativeDragPointerChange): Boolean {
            if (!checkNotNull(owned).perform {
                    down.consume()
                    state.selectTarget(target)
                }) return false
            origin = state.targetSeconds(target)
            centerAtDown = markerCenter(target)
            downLocalX = down.position.x
            dragging = false
            insideFinally = true
            return true
        }
        override fun move(change: NativeDragPointerChange) = checkNotNull(owned).perform {
            val delta = rangeTimelineDragDeltaPx(centerAtDown, markerCenter(target),
                downLocalX, change.position.x)
            if (!dragging && abs(delta) > touchSlop()) {
                dragging = true
                state.beginBoundaryScrub(target)
            }
            change.consume()
            if (dragging) state.updateBoundaryScrub(target,
                origin + delta / timelineWidth() * state.durationSeconds)
        }
        override fun normalFinish() {
            if (dragging) state.endBoundaryScrub() else state.beginBoundaryEdit(target)
        }
    }

    override fun close() { waveform.cancel(); start.cancel(); end.cancel() }
}
