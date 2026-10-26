package app.smallthingz.reverb.nativeui

import smallthingz.ui.geometry.Offset
import app.smallthingz.reverb.FineSeekInputSession
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.RangeDurationWheelInteraction
import app.smallthingz.reverb.RangeInputGesture
import app.smallthingz.reverb.cancelFineSeekOwnedAudio
import app.smallthingz.reverb.fineSeekPointerTapInsideBounds
import app.smallthingz.reverb.rangeFineTuneConstrainedY
import app.smallthingz.reverb.rangeFineTuneDeltaSeconds
import app.smallthingz.reverb.rangeFineTuneHorizontalTouchPull
import app.smallthingz.reverb.rangeFineTuneMovementExceedsSlop
import app.smallthingz.reverb.rangeFineTunePuckContains
import app.smallthingz.reverb.rangeFineTuneSeekPull
import app.smallthingz.reverb.rangeFineTuneShuttleRate
import app.smallthingz.reverb.rangeFineTuneVerticalDragPull
import kotlin.math.abs
import kotlin.math.cosh
import kotlin.math.roundToInt

/** Source callback set; the optional Range gesture factory retains the editor's generation owner. */
internal data class SpringFineSeekBinding(
    val enabled: Boolean,
    val isPlaying: Boolean,
    val durationSeconds: Float,
    val interactionKey: Any?,
    val onTogglePlayback: () -> Unit,
    val onInteractionStart: () -> Unit,
    val onBeginFineAdjust: (Float) -> Unit,
    val onFineAdjust: (Float, Float) -> Unit,
    val onUpdateFineAdjustShuttle: (Float, Float) -> Unit,
    val onEndFineAdjust: () -> Unit,
    val onCancelFineAdjust: () -> Unit,
    val createInputGesture: (((Boolean) -> Unit) -> RangeInputGesture)? = null,
)

/** Source uses two intentionally distinct travel clamps:1 physical px in input,1dp in drawing. */
internal data class SpringFineSeekGeometry(val width: Int, val height: Int, val density: Float) {
    val inputHorizontalTravel = (width * .5f - 10f * density - 24f * density).coerceAtLeast(1f)
    val inputVerticalTravel = (height * .5f - 10f * density - 24f * density).coerceAtLeast(1f)
    val drawHorizontalTravel = (width / density * .5f - 10f - 24f).coerceAtLeast(1f) * density
    val drawVerticalTravel = (height / density * .5f - 10f - 24f).coerceAtLeast(1f) * density
    val hitWidth = minOf(width, (64f * density).roundToInt())
    val hitHeight = minOf(height, (64f * density).roundToInt())
    fun puckLeft(horizontal: Float, rtl: Boolean): Int {
        val offset = (horizontal * drawHorizontalTravel).roundToInt()
        val centeredLeft = ((width - hitWidth) * .5f).roundToInt()
        // Box centers with absolute placement; offset places its SAME-sized child relatively.
        // RTL negates only the inner offset, not the odd-pixel centered outer position.
        return centeredLeft + if (rtl) -offset else offset
    }
    fun puckTop(horizontal: Float, rawVertical: Float): Int =
        ((height - hitHeight) * .5f).roundToInt() +
            (rangeFineTuneConstrainedY(rawVertical, horizontal) * drawVerticalTravel).roundToInt()
}

private const val FINE_SEEK_RETURN_STRENGTH = 3.4f
private val fineSeekReturnDenominator = cosh(FINE_SEEK_RETURN_STRENGTH) - 1f

internal fun springFineSeekReturnRemaining(elapsedNanos: Long): Float {
    val t = (elapsedNanos.toFloat() / 210_000_000L).coerceIn(0f, 1f)
    return (cosh(FINE_SEEK_RETURN_STRENGTH * (1f - t)) - 1f) / fineSeekReturnDenominator
}

/**
 * Source SpringFineSeekControl's awaitEachGesture + two frame effects.
 * Source pure rate/session helpers are reused unchanged. No pointer recognizer, audio owner or
 * coroutine renderer is hidden here. The shared phase tree supplies source-consumption ordering.
 *
 * Library keeps child-clickable pointer taps. Range's optional factory takes Initial-pass ownership
 * and finishes the same DOWN's generation; keyboard/accessibility still use the child clickable.
 */
internal class NativeSpringFineSeekState(
    private val invalidate: () -> Unit,
    private val requestFrames: () -> Unit,
) {
    var horizontalPull: Float = 0f
        private set
    var rawVerticalPull: Float = 0f
        private set
    val dragging: Boolean get() = dragSession != null
    val needsFrames: Boolean get() = dragging || returning
    private var binding: SpringFineSeekBinding? = null
    private var handlerBinding: SpringFineSeekBinding? = null
    private var frameBinding: SpringFineSeekBinding? = null
    private val idleDurationOwner = RangeDurationWheelInteraction()
    private var pointerOwner: FineSeekInputSession? = null
    private var dragSession: FineSeekInputSession? = null
    private var run: PointerRun? = null
    private var waitingForAllUp = false
    private var lastDragFrame = 0L
    private var returning = false
    private var returnStartFrame = 0L
    private var returnStartX = 0f
    private var returnStartY = 0f
    private var touchSlop = Float.NaN
    private var awaitingRangeTapOwner = false

    private class PointerRun(
        val session: FineSeekInputSession,
        val binding: SpringFineSeekBinding,
        val id: Long,
        val down: Offset,
        val geometry: SpringFineSeekGeometry,
        val startedOnPuck: Boolean,
        val ownsPointerTap: Boolean,
        var dragStartRawVertical: Float,
        var fineAdjustStarted: Boolean = false,
        var awaitingDownFinal: Boolean = ownsPointerTap,
    )

    fun bind(next: SpringFineSeekBinding, newTouchSlop: Float) {
        val previous = binding
        val pointerRestart = previous?.interactionKey != next.interactionKey ||
            previous?.enabled != next.enabled || touchSlop != newTouchSlop
        // rememberUpdatedState(enabled/onCancel) updates before pointerInput key retirement.
        binding = next
        if (pointerRestart) {
            cancel()
            handlerBinding = null
            awaitingRangeTapOwner = next.createInputGesture != null
        }
        touchSlop = newTouchSlop
        if (!next.enabled && (previous == null || previous.enabled != next.enabled)) {
            val owner = pointerOwner
            if (owner != null) owner.cancel() else if(next.createInputGesture==null)next.onCancelFineAdjust()
            if (pointerOwner == null && dragSession == null) {
                horizontalPull = 0f; rawVerticalPull = 0f
                returning = false
            }
        }
        invalidate()
    }

    /** Source pointerInput restarts only for its keys, not every playback-position rebind. */
    fun pointer(event: NativeDragPointerEvent, pass: NativePointerPass, geometry: SpringFineSeekGeometry) {
        try {
            pointerEvent(event, pass, geometry)
        } catch (error: Throwable) {
            // Source awaitEachGesture's finally retires its session even when a callback throws.
            val active = run
            if (active != null) finishRun(active, normal = false, stillPressed = false)
            else pointerOwner?.cancel()
            throw error
        }
    }

    private fun pointerEvent(event: NativeDragPointerEvent, pass: NativePointerPass, geometry: SpringFineSeekGeometry) {
        if (binding?.enabled != true) return
        if (waitingForAllUp) {
            if (pass==NativePointerPass.MAIN&&event.changes.none { it.pressed }) {
                waitingForAllUp = false
                awaitingRangeTapOwner = binding?.createInputGesture != null
            }
            return
        }
        val active = run
        if (active == null) {
            val rangeOwnsPointerTap=awaitingRangeTapOwner
            if(pass!=if(rangeOwnsPointerTap)NativePointerPass.INITIAL else NativePointerPass.MAIN)return
            // Android awaitFirstDown(requireUnconsumed=false) has no primary-mouse restriction.
            if (event.changes.isEmpty() || !event.changes.all { it.changedToDown }) return
            val down = event.changes.first()
            // Range owns even a rejected DOWN before the child clickable's Main pass.
            if(rangeOwnsPointerTap)down.consume()
            pointerOwner?.cancel()
            val captured = handlerBinding ?: checkNotNull(binding).also { handlerBinding = it }
            val session = FineSeekInputSession(
                createInputGesture = { onCancel -> binding?.createInputGesture?.invoke(onCancel)?:RangeInputGesture(
                    interactionAllowed = { binding?.enabled == true },
                    durationInteraction = { idleDurationOwner }, onCancel = onCancel,
                ) },
                onCancel = { cancelled, stillCurrent ->
                    val ownsPointer = pointerOwner === cancelled
                    val ownsDrag = dragSession === cancelled
                    if (ownsPointer) pointerOwner = null
                    if (ownsDrag) stopDrag()
                    cancelFineSeekOwnedAudio(rangeOwnsPointerTap&&ownsPointer, ownsDrag, stillCurrent) {
                        binding?.onCancelFineAdjust?.invoke()
                    }
                },
            )
            pointerOwner = session
            if (!session.perform {}) {
                waitingForAllUp = event.changes.any { it.pressed }
                return
            }
            val puckX = geometry.width * .5f + horizontalPull * geometry.inputHorizontalTravel
            val puckY = geometry.height * .5f +
                rangeFineTuneConstrainedY(rawVerticalPull, horizontalPull) * geometry.inputVerticalTravel
            val pointerRun = PointerRun(session, captured, down.id, down.position, geometry,
                rangeFineTunePuckContains(down.position.x, down.position.y, puckX, puckY, 32f * geometry.density),
                rangeOwnsPointerTap,
                rawVerticalPull)
            run = pointerRun
            if (!pointerRun.startedOnPuck&&!rangeOwnsPointerTap) {
                if (!session.perform { startDrag(pointerRun, down.position.x, geometry.width); down.consume() }) {
                    finishRun(pointerRun, normal = false, stillPressed = event.changes.any { it.pressed })
                }
            }
            return
        }

        // Source explicitly awaits the Range DOWN's Final pass before starting an outside drag.
        if(active.awaitingDownFinal) {
            if(pass!=NativePointerPass.FINAL)return
            active.awaitingDownFinal=false
            if(!active.startedOnPuck&&!active.session.perform {startDrag(active,active.down.x,geometry.width)}) {
                finishRun(active,normal=false,stillPressed=event.changes.any {it.pressed})
            }
            return
        }
        if(pass!=NativePointerPass.MAIN)return

        val change = event.changes.firstOrNull { it.id == active.id }
        if(change!=null&&active.ownsPointerTap) {
            val hitSize=(64f*active.geometry.density).roundToInt()
            val centerX=((active.geometry.width-hitSize)*.5f).roundToInt()+hitSize*.5f
            val centerY=((active.geometry.height-hitSize)*.5f).roundToInt()+hitSize*.5f
            active.session.observePointerTap(fineSeekPointerTapInsideBounds(
                change.position.x,change.position.y,
                centerX+(horizontalPull*active.geometry.inputHorizontalTravel).roundToInt(),
                centerY+(rangeFineTuneConstrainedY(rawVerticalPull,horizontalPull)*active.geometry.inputVerticalTravel).roundToInt(),
                hitSize*.5f),change.consumed)
        }
        if (change == null || !change.pressed) {
            // Same source release test: consumed UP is cancellation, including child's ordinary tap.
            val normal = change != null && !change.pressed && !change.consumed
            if (!normal) active.session.cancel()
            if(active.ownsPointerTap)change?.consume()
            finishRun(active, normal, event.changes.any { it.pressed })
            return
        }
        if (!active.session.perform {
            if (!active.fineAdjustStarted && rangeFineTuneMovementExceedsSlop(
                    change.position.x - active.down.x, change.position.y - active.down.y, touchSlop)) {
                startDrag(active, change.position.x, geometry.width)
            }
            if (active.fineAdjustStarted) {
                horizontalPull = rangeFineTuneHorizontalTouchPull(change.position.x,
                    geometry.width.toFloat(), active.geometry.inputHorizontalTravel)
                rawVerticalPull = rangeFineTuneVerticalDragPull(active.dragStartRawVertical,
                    change.position.y - active.down.y, active.geometry.inputVerticalTravel * 2.35f)
                change.consume()
                invalidate()
            }
        }) finishRun(active, normal = false, stillPressed = event.changes.any { it.pressed })
    }

    private fun startDrag(active: PointerRun, x: Float, currentWidth: Int) {
        if (active.fineAdjustStarted) return
        active.binding.onInteractionStart()
        active.dragStartRawVertical = rawVerticalPull
        horizontalPull = rangeFineTuneHorizontalTouchPull(x, currentWidth.toFloat(),
            active.geometry.inputHorizontalTravel)
        dragSession = active.session
        frameBinding = binding // LaunchedEffect(dragSession) captures the current composition's arguments.
        returning = false
        lastDragFrame = 0L
        active.fineAdjustStarted = true
        active.binding.onBeginFineAdjust(rangeFineTuneShuttleRate(horizontalPull,
            rangeFineTuneConstrainedY(rawVerticalPull, horizontalPull), active.binding.durationSeconds))
        invalidate()
        requestFrames()
    }

    private fun finishRun(active: PointerRun, normal: Boolean, stillPressed: Boolean) {
        try {
            when {
                !normal -> active.session.cancel()
                active.fineAdjustStarted -> active.session.finishFineAdjust(
                    onCommit = { active.binding.onFineAdjust(it, .04f) },
                    onEnd = {
                        if (dragSession === active.session) stopDrag()
                        active.binding.onEndFineAdjust()
                    },
                )
                active.ownsPointerTap&&active.startedOnPuck->active.session.finishPointerTap(active.binding.onTogglePlayback)
                else -> active.session.finish {} // Library's child clickable owns ordinary tap.
            }
        } finally {
            if (dragSession === active.session) stopDrag()
            if (pointerOwner === active.session) pointerOwner = null
            if (run === active) run = null
            waitingForAllUp = stillPressed
            if(!stillPressed)awaitingRangeTapOwner=binding?.createInputGesture!=null
        }
    }

    private fun stopDrag() {
        dragSession = null
        frameBinding = null
        lastDragFrame = 0L
        returnStartX = horizontalPull; returnStartY = rawVerticalPull
        returnStartFrame = 0L
        returning = abs(returnStartX) >= .0001f || abs(returnStartY) >= .0001f
        if (!returning) { horizontalPull = 0f; rawVerticalPull = 0f }
        invalidate()
        if (returning) requestFrames()
    }

    fun frame(frameNanos: Long) {
        val session = dragSession
        if (session != null) {
            val captured = checkNotNull(frameBinding)
            val previous = lastDragFrame
            lastDragFrame = frameNanos
            if (previous == 0L) { session.perform {}; return }
            val elapsed = (frameNanos - previous).coerceAtMost(50_000_000L)
            val liveX = horizontalPull
            val liveY = rangeFineTuneConstrainedY(rawVerticalPull, liveX)
            val rate = rangeFineTuneShuttleRate(liveX, liveY, captured.durationSeconds)
            session.frame(rangeFineTuneDeltaSeconds(rangeFineTuneSeekPull(liveX), liveY,
                captured.durationSeconds, elapsed / 1_000_000_000f), elapsed,
                onCommit = { captured.onFineAdjust(it, .04f) },
                onShuttle = { captured.onUpdateFineAdjustShuttle(rate, it) })
            return
        }
        if (!returning) return
        if (returnStartFrame == 0L) { returnStartFrame = frameNanos; return }
        val elapsed = frameNanos - returnStartFrame
        val remaining = springFineSeekReturnRemaining(elapsed)
        horizontalPull = returnStartX * remaining; rawVerticalPull = returnStartY * remaining
        if (elapsed >= 210_000_000L) {
            horizontalPull = 0f; rawVerticalPull = 0f; returning = false
        }
        invalidate()
    }

    /** ACTION_CANCEL, disabled pointerInput, identity replacement or detach retires this session. */
    fun cancel() {
        val active = run
        if (active != null) finishRun(active, normal = false, stillPressed = false)
        else pointerOwner?.cancel()
        waitingForAllUp = false
        awaitingRangeTapOwner = binding?.createInputGesture != null
    }

    fun release() {
        cancel()
        handlerBinding = null
        binding = null
        frameBinding = null
        returning = false
        horizontalPull = 0f; rawVerticalPull = 0f
        invalidate()
    }
}
