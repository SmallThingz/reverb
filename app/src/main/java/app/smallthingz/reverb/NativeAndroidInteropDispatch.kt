/*
 * Adapted from AndroidX UI 1.11.2 PointerInteropFilter.android.kt.
 * Copyright 2020-2025 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb

/** PointerInteropFilter's pass/consumption state, separate from the actual Android MotionEvent. */
internal class NativeAndroidInteropDispatch(
    private val dispatchToView: (cancel: Boolean) -> Boolean,
    private val dispatchEmptyCancel: () -> Unit,
    private val suppressMovementConsumption: (Boolean) -> Unit = {},
) {
    private enum class State { UNKNOWN, DISPATCHING, NOT_DISPATCHING }
    private var state = State.UNKNOWN
    private var lastInitialEvent: NativeDragPointerEvent? = null
    var disallowIntercept = false

    fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass, actionDown: Boolean) {
        val changes = event.changes
        val move = changes.all { !it.changedToDown && !it.changedToUp }
        val initial = disallowIntercept || changes.any { it.changedToDown || it.changedToUp } ||
            (move && changes.all { !it.consumed })
        if (state != State.NOT_DISPATCHING) {
            if (pass == NativePointerPass.INITIAL && initial) {
                lastInitialEvent = event
                dispatchToView(event, !move || disallowIntercept, actionDown)
            }
            if (pass == NativePointerPass.MAIN && move && event === lastInitialEvent && disallowIntercept) {
                changes.forEach { it.consume() }
            }
            if (pass == NativePointerPass.FINAL && !initial && event !== lastInitialEvent) {
                dispatchToView(event, true, actionDown)
            }
        }
        if (pass == NativePointerPass.FINAL) {
            if (changes.all { it.changedToUp }) reset()
            if (event === lastInitialEvent && move) {
                if (changes.any { it.consumed } && !disallowIntercept) stopDispatching()
                else changes.forEach { it.consume() }
            }
        }
    }

    private fun dispatchToView(event: NativeDragPointerEvent, consume: Boolean, actionDown: Boolean) {
        if (event.changes.any { it.consumed }) {
            stopDispatching()
        } else {
            val accepted = dispatchToView(false)
            if (actionDown) state = if (accepted) State.DISPATCHING else State.NOT_DISPATCHING
            if (state == State.DISPATCHING) {
                if (consume) event.changes.forEach { it.consume() }
                suppressMovementConsumption(!disallowIntercept)
            }
        }
    }

    private fun stopDispatching() {
        if (state == State.DISPATCHING) dispatchToView(true)
        state = State.NOT_DISPATCHING
    }

    fun cancel() {
        try {
            if (state == State.DISPATCHING) dispatchEmptyCancel()
        } finally {
            // CANCEL ends rejected/intercepted streams too; no later UP will reset them.
            reset()
        }
    }

    private fun reset() {
        state = State.UNKNOWN
        disallowIntercept = false
        lastInitialEvent = null
    }
}
