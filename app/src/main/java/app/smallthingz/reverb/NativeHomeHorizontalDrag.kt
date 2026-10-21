/*
 * Source-derived specialization of foundation 1.11.2 detectHorizontalDragGestures,
 * awaitPointerSlopOrCancellation, horizontalDrag and awaitEachGesture.
 * Copyright 2020-2025 The Android Open Source Project; Apache License 2.0.
 */
package app.smallthingz.reverb

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import kotlin.math.abs
import kotlin.math.sign

/**
 * The released Home uses the simple horizontal detector, not draggable's angle/pickup recognizer.
 * MAIN is child-to-parent; FINAL observes cancellation after competing child/parent consumption.
 * The host passes physical X unchanged: the released Home does not add an RTL sign inversion.
 */
internal class NativeHomeHorizontalDrag(
    private val touchSlop: () -> Float,
    private val onStart: (Offset) -> Unit,
    private val onDelta: (Float) -> Unit,
    private val onEnd: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private enum class State { DOWN, SLOP, DRAG, WAIT_UP }
    private var state = State.DOWN
    private var pointerId = Long.MIN_VALUE
    private var accumulated = 0f
    private var threshold = 0f
    private var verifyFinal: Long? = null

    fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
        if (pass == NativePointerPass.INITIAL) return
        if (state == State.WAIT_UP) {
            // awaitEachGesture waits for all pointers up in FINAL before beginning another cycle.
            if (pass == NativePointerPass.FINAL && event.changes.none { it.pressed }) restart()
            return
        }
        if (pass == NativePointerPass.FINAL) {
            val watched = verifyFinal
            verifyFinal = null
            if (watched != null && event.changes.firstOrNull { it.id == watched }?.consumed == true) {
                state = State.WAIT_UP
            }
            return
        }
        when (state) {
            State.DOWN -> if (event.changes.isNotEmpty() && event.changes.all { it.changedToDown }) {
                // awaitFirstDown(requireUnconsumed=false), including a clickable's consumed DOWN.
                // Android's firstDownRefersToPrimaryMouseButtonOnly actual is false. Do not use
                // draggable's primary-button-only admission shared by NativeDragPointerRouter.
                val down = event.changes.first()
                pointerId = down.id
                accumulated = 0f
                threshold = if (down.type == PointerType.Mouse) touchSlop() / 144f else touchSlop()
                state = State.SLOP
            }
            State.SLOP -> slop(event)
            State.DRAG -> drag(event)
            State.WAIT_UP -> Unit
        }
    }

    private fun slop(event: NativeDragPointerEvent) {
        val change = event.changes.firstOrNull { it.id == pointerId }
        if (change == null || change.consumed) {
            state = State.WAIT_UP
            return
        }
        if (change.changedToUp) {
            val next = event.changes.firstOrNull { it.pressed }
            if (next == null) state = State.WAIT_UP else pointerId = next.id
            return
        }
        accumulated += change.delta.x
        if (abs(accumulated) >= threshold) {
            val over = accumulated - sign(accumulated) * threshold
            // The detector consumes threshold crossing even if Home's onStart ignores it while
            // saving or another flip is active; do not invent an enabled recognizer shortcut.
            change.consume()
            state = State.DRAG
            onStart(change.position)
            onDelta(over)
        } else {
            verifyFinal = change.id
        }
    }

    private fun drag(event: NativeDragPointerEvent) {
        val change = event.changes.firstOrNull { it.id == pointerId }
        if (change == null) {
            state = State.WAIT_UP
            onCancel()
            return
        }
        // awaitDragOrUp switches away from a lifted pointer before checking consumption.
        if (change.changedToUp) {
            val next = event.changes.firstOrNull { it.pressed }
            if (next != null) {
                pointerId = next.id
                return
            }
            state = State.WAIT_UP
            if (change.consumed) onCancel() else onEnd()
        } else if (change.delta.x != 0f) {
            if (change.consumed) {
                state = State.WAIT_UP
                onCancel()
            } else {
                onDelta(change.delta.x)
                change.consume()
            }
        }
        // Consumed stationary/cross-axis-only frames are not returned by awaitDragOrUp.
    }

    /** Pointer-input key replacement cancels its coroutine, without inventing onDragCancel. */
    fun restart() {
        state = State.DOWN
        pointerId = Long.MIN_VALUE
        accumulated = 0f
        verifyFinal = null
    }

    /** A canceled actual pointer stream produces the source cancellation callback after slop. */
    fun cancel() {
        val dragging = state == State.DRAG
        restart()
        if (dragging) onCancel()
    }
}
