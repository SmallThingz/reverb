/*
 * Adapted from AndroidX UI 1.11.2 PointerInteropFilter/AndroidViewHolder.android.kt.
 * Copyright 2020-2025 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/** Existing parent owns this per-AndroidView request route; no extra layout/visual wrapper. */
internal class NativeAndroidInteropRequests {
    private var listener: ((Boolean) -> Unit)? = null
    fun request(disallow: Boolean) { listener?.invoke(disallow) }
    fun bind(onRequest: (Boolean) -> Unit): AutoCloseable {
        listener = onRequest
        return AutoCloseable { if (listener === onRequest) listener = null }
    }
}

internal interface NativeAndroidInteropHost {
    val nativeInteropRequests: NativeAndroidInteropRequests
}

/**
 * Actual native wheel/curve/editor delivery in the owning window's shared Initial/Main/Final path.
 * The leaf's immediate parent forwards requestDisallowInterceptTouchEvent then calls super.
 */
internal class NativeAndroidInteropNode(
    override val view: View,
    requests: NativeAndroidInteropRequests,
    suppressMovementConsumption: (Boolean) -> Unit = {},
) : NativeViewPhasePointerNode, AutoCloseable {
    private var rootEvent: MotionEvent? = null
    private var transform: NativePointerCoordinates? = null
    private var pointerMask = 0
    private var localEvent: MotionEvent? = null
    private var currentEvent: NativeDragPointerEvent? = null
    private var closed = false
    private val state = NativeAndroidInteropDispatch(::dispatchLocal, ::dispatchEmptyCancel) { suppress ->
        requireNotNull(currentEvent).dispatchState.suppressMovementConsumption = suppress
        suppressMovementConsumption(suppress)
    }
    private val requestLease = requests.bind { state.disallowIntercept = it }

    override val attached get() = !closed && view.isAttachedToWindow
    override val shareWithSiblings get() = true
    override fun bindMotion(event: MotionEvent?, coordinates: NativePointerCoordinates?, androidPointerMask: Int) {
        localEvent?.recycle()
        localEvent = null
        rootEvent = event
        transform = coordinates
        pointerMask = androidPointerMask
    }
    override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
        val previous = currentEvent
        currentEvent = event
        try { state.dispatch(event, pass, localMotion().actionMasked == MotionEvent.ACTION_DOWN) }
        finally { currentEvent = previous }
    }
    private fun localMotion(): MotionEvent = localEvent ?: requireNotNull(rootEvent)
        .nativePointerSubset(pointerMask).also {
            it.transform(requireNotNull(transform).rootToLocal)
            localEvent = it
        }
    private fun dispatchLocal(cancel: Boolean): Boolean {
        val motion = localMotion()
        val action = motion.action
        if (cancel) motion.action = MotionEvent.ACTION_CANCEL
        return try { dispatchAndroid(motion) } finally { motion.action = action }
    }
    private fun dispatchAndroid(motion: MotionEvent): Boolean = when (motion.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE,
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_OUTSIDE,
        MotionEvent.ACTION_CANCEL -> view.dispatchTouchEvent(motion)
        else -> view.dispatchGenericMotionEvent(motion)
    }
    private fun dispatchEmptyCancel() {
        val now = SystemClock.uptimeMillis()
        val motion = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        motion.source = InputDevice.SOURCE_UNKNOWN
        try { dispatchAndroid(motion) } finally { motion.recycle() }
    }
    override fun cancel() = state.cancel()
    override fun close() {
        if (closed) return
        closed = true
        try { state.cancel() } finally {
            requestLease.close()
            bindMotion(null, null, 0)
        }
    }
}
