/*
 * Source-derived specialization of foundation 1.11.2 DragGestureNode and TouchSlopDetector.
 * Copyright 2022-2025 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb

import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.geometry.isSpecified
import app.smallthingz.nativeui.input.pointer.PointerType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sign

/**
 * Actual per-pass recognizer shared by horizontal pager and vertical page-scroll owners.
 * Pinned conflict-fix flag=true. No View.onInterceptTouchEvent approximation.
 *
 * Dispatch INITIAL ancestor->descendant, MAIN descendant->ancestor, FINAL ancestor->descendant.
 * Row click controllers must see the shared consumption in their matching passes.
 */
internal class NativeDragPointerRouter(
    private val axis: NativeDragAxis,
    private val touchSlop: () -> Float,
    private val maximumVelocity: () -> Float,
    private val startImmediately: () -> Boolean,
    private val sink: NativeDragSink,
    private val parentInterested: (NativeDragPointerChange) -> Boolean = { false },
) {
    private enum class State { DOWN, SLOP, PICKUP, DRAGGING }
    private var state = State.DOWN
    private var awaitSlop: Boolean? = null
    private var consumedInitial = false
    private var initialDown: NativeDragPointerChange? = null
    private var pointerId = Long.MAX_VALUE
    private var verifyFinal = false
    private val slop = NativeDragSlop(axis)
    private var velocity: NativeDragVelocity? = null
    private var nodeOffset = Offset.Zero
    private var previousNodeOrigin = Offset.Unspecified
    private var hasStarted = false
    var enabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            reset()
        }

    fun interested(change: NativeDragPointerChange): Boolean {
        if (change.changedToDown) return enabled
        if (change.changedToUp) return false
        val delta = if (change.consumed) Offset.Zero else change.delta
        return slop.postSlop(delta, touchSlop(), commit = false) != Offset.Unspecified &&
            slop.atAngleOfInterest(delta)
    }

    fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
        if (!enabled) return
        when (state) {
            State.DOWN -> down(event, pass)
            State.SLOP -> awaitSlop(event, pass)
            State.PICKUP -> pickup(event, pass)
            State.DRAGGING -> dragging(event, pass)
        }
    }

    /** ACTION_CANCEL/detach/disabled path; distinct from a normal velocity-bearing UP. */
    fun reset() {
        moveToDown()
        if (hasStarted) sink.cancel()
        velocity = null
        hasStarted = false
    }

    private fun down(event: NativeDragPointerEvent, pass: NativePointerPass) {
        if (!event.isDown()) return
        val change = event.changes.first()
        val wait = awaitSlop ?: !startImmediately()
        awaitSlop = wait
        if (pass == NativePointerPass.INITIAL && !wait) {
            change.consume()
            consumedInitial = true
        }
        if (pass == NativePointerPass.MAIN) {
            if (wait) moveToSlop(change, change.id)
            else if (consumedInitial) {
                start(change, change, Offset.Zero, event)
                delta(change, Offset.Zero, event)
                state = State.DRAGGING
                pointerId = change.id
            }
        }
    }

    private fun awaitSlop(event: NativeDragPointerEvent, pass: NativePointerPass) {
        if (pass == NativePointerPass.INITIAL) return
        val change = event.changes.firstOrNull { it.id == pointerId } ?: run {
            val replacement = event.changes.firstOrNull { it.pressed }
            if (replacement == null) { moveToDown(); return }
            pointerId = replacement.id
            replacement
        }
        if (pass == NativePointerPass.MAIN) {
            if (!change.consumed) {
                if (change.changedToUp) {
                    val replacement = event.changes.firstOrNull { it.pressed }
                    if (replacement == null) moveToDown() else pointerId = replacement.id
                } else {
                    val threshold = if (change.type == PointerType.Mouse) touchSlop() / 144f else touchSlop()
                    val over = slop.postSlop(change.delta, threshold)
                    if (over.isSpecified) {
                        if (!interested(change) && parentInterested(change)) verifyFinal = true
                        else {
                            change.consume()
                            start(requireNotNull(initialDown), change, over, event)
                            delta(change, over, event)
                            state = State.DRAGGING
                            pointerId = change.id
                        }
                    } else verifyFinal = true
                }
            } else moveToPickup()
        }
        if (pass == NativePointerPass.FINAL && verifyFinal) {
            if (change.consumed) moveToPickup() else verifyFinal = false
        }
    }

    private fun pickup(event: NativeDragPointerEvent, pass: NativePointerPass) {
        if (pass != NativePointerPass.FINAL) return
        if (event.changes.isEmpty() || event.changes.none { it.pressed }) moveToDown()
        else if (event.changes.none { it.consumed }) {
            val down = requireNotNull(initialDown)
            moveToSlop(down, pointerId, event.changes.first().position - down.position)
        }
    }

    private fun dragging(event: NativeDragPointerEvent, pass: NativePointerPass) {
        if (pass != NativePointerPass.MAIN) return
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return
        if (change.changedToUp) {
            val replacement = event.changes.firstOrNull { it.pressed }
            if (replacement != null) pointerId = replacement.id
            else {
                if (!change.consumed) {
                    val tracker = requireNotNull(velocity)
                    tracker.add(change)
                    val result = tracker.calculate(maximumVelocity())
                    tracker.reset()
                    sink.stop(result)
                } else sink.cancel()
                moveToDown()
            }
        } else if (change.consumed) {
            // Source leaves its state intact; a later dispatch/pickup owner decides the next cycle.
            sink.cancel()
        } else if (change.delta.getDistance() != 0f) {
            delta(change, change.delta, event)
            change.consume()
        }
    }

    private fun start(
        down: NativeDragPointerChange,
        trigger: NativeDragPointerChange,
        over: Offset,
        event: NativeDragPointerEvent,
    ) {
        val tracker = velocity ?: NativeDragVelocity().also { velocity = it }
        tracker.add(down)
        nodeOffset = Offset.Zero
        previousNodeOrigin = event.nodeOriginOnScreen
        hasStarted = true
        sink.start(trigger.position - over)
    }

    private fun delta(change: NativeDragPointerChange, amount: Offset, event: NativeDragPointerEvent) {
        if (previousNodeOrigin != Offset.Unspecified && previousNodeOrigin != event.nodeOriginOnScreen) {
            nodeOffset += event.nodeOriginOnScreen - previousNodeOrigin
        }
        previousNodeOrigin = event.nodeOriginOnScreen
        requireNotNull(velocity).add(change, nodeOffset)
        sink.delta(amount)
    }

    private fun moveToDown() {
        state = State.DOWN
        awaitSlop = null
        consumedInitial = false
    }

    private fun moveToSlop(down: NativeDragPointerChange, id: Long, accumulated: Offset = Offset.Zero) {
        state = State.SLOP
        initialDown = down
        pointerId = id
        slop.reset(accumulated)
        verifyFinal = false
    }

    private fun moveToPickup() {
        state = State.PICKUP
        slop.reset()
    }
}

internal class NativeDragSlop(private val axis: NativeDragAxis) {
    private var total = Offset.Zero

    fun postSlop(delta: Offset, threshold: Float, commit: Boolean = true): Offset {
        val final = total + delta
        if (commit) total = final
        val main = if (axis == NativeDragAxis.HORIZONTAL) final.x else final.y
        if (!(abs(main) >= threshold)) return Offset.Unspecified
        // Preserve pinned TouchSlopDetector's total-vs-projected behavior for commit=false.
        return if (axis == NativeDragAxis.HORIZONTAL) Offset(total.x - sign(total.x) * threshold, total.y)
        else Offset(total.x, total.y - sign(total.y) * threshold)
    }

    fun atAngleOfInterest(delta: Offset): Boolean {
        val projected = total + delta
        val angle = atan2(y = abs(projected.y), x = abs(projected.x)) * 180 / PI
        return if (axis == NativeDragAxis.HORIZONTAL) angle < 30 else angle > 30
    }

    fun reset(value: Offset = Offset.Zero) { total = value }
}
