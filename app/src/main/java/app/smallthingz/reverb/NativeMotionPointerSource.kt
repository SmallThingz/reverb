/*
 * Touch/stylus subset derived from UI 1.11.2 MotionEventAdapter and PointerInputChangeEventProducer.
 * Copyright 2022-2025 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb

import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType

/**
 * Owned at the native window input root, BEFORE child dispatch. Accepts root-space MotionEvents,
 * not events already transformed/recycled by a child View. Mouse/hover/wheel/indirect input belongs
 * to its separate source-matched lane and is deliberately not represented as touchscreen input.
 */
internal class NativeMotionPointerSource(private val rootToScreen: (Offset) -> Offset) {
    private data class Previous(val id: Long, val screenPosition: Offset, val pressed: Boolean)
    private val previous = mutableMapOf<Int, Previous>()
    private var nextId = 0L

    fun cancel() { previous.clear() }

    fun read(event: MotionEvent): NativeRootPointerFrame? {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { cancel(); return null }
        if (event.actionMasked !in TOUCH_ACTIONS) return null
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) return null
        if ((0 until event.pointerCount).any { event.getToolType(it) == MotionEvent.TOOL_TYPE_MOUSE }) return null
        if (event.actionMasked == MotionEvent.ACTION_DOWN) previous.clear()
        val changes = ArrayList<NativeRootPointerChange>(event.pointerCount)
        for (index in 0 until event.pointerCount) {
            val androidId = event.getPointerId(index)
            val old = previous[androidId]
            val id = old?.id ?: nextId++
            val original = Offset(event.getX(index), event.getY(index))
            val screen = if (index == 0) Offset(event.rawX, event.rawY)
                else if (Build.VERSION.SDK_INT >= 29) Offset(event.getRawX(index), event.getRawY(index))
                else rootToScreen(original)
            val up = event.actionMasked == MotionEvent.ACTION_UP ||
                (event.actionMasked == MotionEvent.ACTION_POINTER_UP && index == event.actionIndex)
            val pressed = !up
            val type = when (event.getToolType(index)) {
                MotionEvent.TOOL_TYPE_FINGER -> PointerType.Touch
                MotionEvent.TOOL_TYPE_STYLUS -> PointerType.Stylus
                MotionEvent.TOOL_TYPE_ERASER -> PointerType.Eraser
                else -> PointerType.Unknown
            }
            val history = ArrayList<NativePointerHistory>(event.historySize)
            for (sample in 0 until event.historySize) {
                val point = Offset(event.getHistoricalX(index, sample), event.getHistoricalY(index, sample))
                if (point.x.isFinite() && point.y.isFinite()) {
                    history += NativePointerHistory(event.getHistoricalEventTime(sample), point)
                }
            }
            changes += NativeRootPointerChange(
                id, androidId, event.eventTime, screen, old?.screenPosition ?: screen, pressed, old?.pressed ?: false,
                type, original, history,
            )
            if (pressed) previous[androidId] = Previous(id, screen, true) else previous.remove(androidId)
        }
        return NativeRootPointerFrame(changes)
    }

    private companion object {
        val TOUCH_ACTIONS = setOf(
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP,
        )
    }
}

internal class NativeRootPointerChange(
    val id: Long,
    val androidPointerId: Int,
    val timeMillis: Long,
    val screenPosition: Offset,
    val previousScreenPosition: Offset,
    val pressed: Boolean,
    val previouslyPressed: Boolean,
    val type: PointerType,
    val originalPosition: Offset,
    val history: List<NativePointerHistory>,
    val consumption: NativePointerConsumption = NativePointerConsumption(),
)

internal class NativeRootPointerFrame(val changes: List<NativeRootPointerChange>) {
    /**
     * Use the actual float matrix transform, including all ancestor transforms. Integer
     * getLocationOnScreen/getGlobalVisibleRect approximations are not an exact substitute.
     */
    fun project(
        screenToLocal: (Offset) -> Offset,
        nodeOriginOnScreen: Offset,
        pointerIds: Set<Long>? = null,
    ): NativeDragPointerEvent =
        NativeDragPointerEvent(changes.filter { pointerIds == null || it.id in pointerIds }.map { change ->
            NativeDragPointerChange(
                change.id, change.timeMillis, screenToLocal(change.screenPosition),
                screenToLocal(change.previousScreenPosition), change.pressed, change.previouslyPressed,
                change.type, change.originalPosition, change.history, change.consumption,
            )
        }, nodeOriginOnScreen)
}
