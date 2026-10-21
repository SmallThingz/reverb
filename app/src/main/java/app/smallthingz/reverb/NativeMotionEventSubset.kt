package app.smallthingz.reverb

import android.os.Build
import android.view.MotionEvent

/**
 * Public-SDK counterpart of Android 16 Input.cpp resolveActionForSplitMotionEvent.
 * Pointer order remains the source order, including noncontiguous Android IDs.
 */
internal fun nativePointerSubsetAction(action: Int, flags: Int, selectedIndices: IntArray): Int {
    require(selectedIndices.isNotEmpty())
    val masked = action and MotionEvent.ACTION_MASK
    if (masked != MotionEvent.ACTION_POINTER_DOWN && masked != MotionEvent.ACTION_POINTER_UP) return action
    val oldIndex = (action and MotionEvent.ACTION_POINTER_INDEX_MASK) ushr MotionEvent.ACTION_POINTER_INDEX_SHIFT
    val newIndex = selectedIndices.indexOf(oldIndex)
    if (newIndex < 0) return MotionEvent.ACTION_MOVE
    if (selectedIndices.size > 1) return masked or (newIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    return if (masked == MotionEvent.ACTION_POINTER_DOWN) MotionEvent.ACTION_DOWN
    else if (flags and MotionEvent.FLAG_CANCELED != 0) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
}

/**
 * Only used for touch/stylus events already admitted by NativePagerTouchRoot.
 *
 * Copies full PointerProperties/PointerCoords (all axes), history, millisecond times, precision,
 * flags, buttons/modifiers, device/source and edges. All-pointer delivery uses obtain(original),
 * preserving even metadata unavailable to public setters. A true subset cannot retain hidden
 * display/raw-transform metadata, nanosecond remainder or actionButton through public APIs.
 * actionButton is undefined for this lane's actions; no consumer reads it or raw/display metadata.
 * Classification (needed by CombinedClickable deep press on API29+) is delivered separately from
 * the ORIGINAL event, including API29–33 where public obtain cannot set it.
 */
internal fun MotionEvent.nativePointerSubset(pointerMask: Int): MotionEvent {
    var availableMask = 0
    val selected = IntArray(pointerCount)
    var count = 0
    for (index in 0 until pointerCount) {
        val bit = 1 shl getPointerId(index)
        availableMask = availableMask or bit
        if (pointerMask and bit != 0) selected[count++] = index
    }
    require(count > 0 && pointerMask and availableMask == pointerMask)
    if (count == pointerCount) return MotionEvent.obtain(this)
    val indices = selected.copyOf(count)
    val properties = Array(count) { MotionEvent.PointerProperties().also { p -> getPointerProperties(indices[it], p) } }
    val coords = Array(count) { MotionEvent.PointerCoords() }
    fun copySample(history: Int) {
        for (index in indices.indices) {
            if (history < historySize) getHistoricalPointerCoords(indices[index], history, coords[index])
            else getPointerCoords(indices[index], coords[index])
        }
    }
    copySample(0)
    val firstTime = if (historySize > 0) getHistoricalEventTime(0) else eventTime
    // addBatch accepts MOVE; restore the source-derived action only after copying every sample.
    val result = MotionEvent.obtain(
        downTime, firstTime, MotionEvent.ACTION_MOVE, count, properties, coords,
        metaState, buttonState, xPrecision, yPrecision, deviceId, edgeFlags, source, flags,
    )
    try {
        for (sample in 1..historySize) {
            copySample(sample)
            result.addBatch(if (sample < historySize) getHistoricalEventTime(sample) else eventTime, coords, metaState)
        }
        result.action = nativePointerSubsetAction(action, flags, indices)
        return result
    } catch (failure: Throwable) {
        result.recycle()
        throw failure
    }
}

internal fun MotionEvent.nativeOriginalClassification(): Int =
    if (Build.VERSION.SDK_INT >= 29) classification else 0
