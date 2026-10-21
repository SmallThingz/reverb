/*
 * Pressed-pointer subset of UI 1.11.2 MotionEventAdapter.android.kt.
 * Copyright 2022-2025 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb

import android.view.InputDevice
import android.view.MotionEvent

internal fun nativePressedPointerAction(action: Int): Boolean = when (action) {
    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE,
    MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> true
    else -> false
}

internal fun nativeMouseSource(source: Int): Boolean =
    source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE ||
        source and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD

/**
 * Owns only pressed mouse-button admission alongside existing touch/stylus input. Hover, wheel and
 * fake-finger trackpad gestures remain separate lanes. The pinned adapter detects fake fingers on
 * DOWN, not on UP (whose buttonState is normally zero), so retain that decision for the stream.
 */
internal class NativePressedPointerAdmission(private val mouseButtons: Boolean) {
    private var admittedMouseStream = false

    fun reset() { admittedMouseStream = false }

    fun accepts(action: Int, source: Int, hasMouseTool: Boolean, buttons: Int, indirectClassification: Boolean): Boolean {
        if (!nativePressedPointerAction(action)) return false
        val mouse = nativeMouseSource(source) || hasMouseTool
        if (!mouse) {
            if (action == MotionEvent.ACTION_DOWN) reset()
            return true
        }
        if (!mouseButtons) return false
        if (action == MotionEvent.ACTION_DOWN) {
            admittedMouseStream = !indirectClassification && !(buttons == 0 && nativeMouseSource(source))
        }
        val admitted = admittedMouseStream
        if (action == MotionEvent.ACTION_UP) reset()
        return admitted
    }
}
