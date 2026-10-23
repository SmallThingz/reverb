/*
 * Adapted from AndroidX UI/foundation 1.11.2. Copyright 2022-2025 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb

import smallthingz.ui.geometry.Offset
import smallthingz.ui.input.pointer.PointerType
import smallthingz.ui.input.pointer.util.VelocityTracker
import smallthingz.ui.unit.Velocity

internal enum class NativePointerPass { INITIAL, MAIN, FINAL }
internal enum class NativeDragAxis { HORIZONTAL, VERTICAL }
internal class NativePointerConsumption(var consumed: Boolean = false)
/** One InternalPointerEvent flag shared by every node-local projection, reset for each root event. */
internal class NativePointerDispatchState(var suppressMovementConsumption: Boolean = false)
internal data class NativePointerHistory(val timeMillis: Long, val originalPosition: Offset)

/**
 * One node-local projection of a shared event. Ancestor/descendant projections MUST share the same
 * consumption object, but have their own current/previous local positions. Original/history
 * coordinates stay in the original Android root MotionEvent space for the pinned velocity feed.
 */
internal class NativeDragPointerChange(
    val id: Long,
    val timeMillis: Long,
    val position: Offset,
    val previousPosition: Offset,
    val pressed: Boolean,
    val previouslyPressed: Boolean,
    val type: PointerType,
    val originalPosition: Offset,
    val history: List<NativePointerHistory> = emptyList(),
    val consumption: NativePointerConsumption = NativePointerConsumption(),
) {
    val changedToDown get() = pressed && !previouslyPressed
    val changedToUp get() = !pressed && previouslyPressed
    val delta get() = position - previousPosition
    val consumed get() = consumption.consumed
    fun consume() { consumption.consumed = true }
}

internal data class NativeDragPointerEvent(
    val changes: List<NativeDragPointerChange>,
    val nodeOriginOnScreen: Offset,
    val primaryMouseButtonPressed: Boolean = false,
    val dispatchState: NativePointerDispatchState = NativePointerDispatchState(),
) {
    fun isDown(): Boolean = changes.isNotEmpty() &&
        !(changes.all { it.type == PointerType.Mouse } && !primaryMouseButtonPressed) &&
        changes.all { it.changedToDown }
}

internal interface NativeDragSink {
    fun start(position: Offset)
    fun delta(physicalDelta: Offset)
    fun stop(physicalVelocity: Velocity)
    fun cancel()
}

/**
 * Pinned Android DefaultVelocityTracker's default VelocityTrackerAddPointsFix=true feed,
 * using the SAME public low-level LSQ2 tracker. No android.view.VelocityTracker substitution.
 * The released app does not override the flag; legacy false mode is outside this slice.
 */
internal class NativeDragVelocity {
    private val tracker = VelocityTracker()
    private var lastMoveTime = 0L

    fun add(change: NativeDragPointerChange, nodeOffset: Offset = Offset.Zero) {
        if (change.changedToDown) reset()
        if (!change.changedToUp) {
            for (point in change.history) tracker.addPosition(point.timeMillis, point.originalPosition + nodeOffset)
            tracker.addPosition(change.timeMillis, change.originalPosition + nodeOffset)
        }
        if (change.changedToUp && change.timeMillis - lastMoveTime > 40L) reset()
        lastMoveTime = change.timeMillis
    }

    fun calculate(maximumVelocity: Float): Velocity {
        val value = tracker.calculateVelocity(Velocity(maximumVelocity, maximumVelocity))
        return Velocity(if (value.x.isNaN()) 0f else value.x, if (value.y.isNaN()) 0f else value.y)
    }

    fun reset() {
        tracker.resetTracking()
        lastMoveTime = 0L
    }
}
