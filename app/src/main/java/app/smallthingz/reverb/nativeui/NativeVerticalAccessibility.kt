/*
 * Copyright 2024 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 * Source-derived from BringIntoViewSpec.android.kt and AndroidComposeViewAccessibilityDelegate.
 */
package app.smallthingz.reverb.nativeui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect as AndroidRect
import android.graphics.RectF
import android.view.View
import app.smallthingz.nativeui.foundation.gestures.BringIntoViewSpec
import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.geometry.Rect
import kotlin.math.abs

internal enum class NativeVerticalAccessibilityDirection { FORWARD, BACKWARD }

/** Source delegate admits an action only when its signed viewport amount can change the range. */
internal fun nativeVerticalAccessibilityDelta(
    direction: NativeVerticalAccessibilityDirection,
    viewportHeight: Float,
    position: Int,
    maximum: Int,
    enabled: Boolean,
): Float? {
    if (!enabled) return null
    val delta = if (direction == NativeVerticalAccessibilityDirection.FORWARD) viewportHeight else -viewportHeight
    return delta.takeIf { (it < 0f && position > 0) || (it > 0f && position < maximum) }
}

internal fun nativeBringIntoViewSpec(context: Context): BringIntoViewSpec =
    if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val itemSize = abs((offset + size) - offset)
                val initialTarget = .3f * containerSize - 0f * itemSize
                val space = containerSize - initialTarget
                val target = if (itemSize <= containerSize && space < itemSize) containerSize - itemSize else initialTarget
                return offset - target
            }
        }
    } else {
        object : BringIntoViewSpec {} // Uses the pinned public default minimum-distance arithmetic.
    }

/** An ordinary View ancestor participates in the same concurrent responder chain. */
internal interface NativeBringIntoViewParent {
    val bringIntoViewView: View
    suspend fun bringDescendantIntoView(child: View, bounds: () -> Rect?)
}

/**
 * Source BringIntoViewResponder.localRectOf translates a requested rect by the unclipped FULL
 * child bounding box's top-left. Preserve that rule, rather than clipping the request or rounding
 * every ancestor offset. Coordinates are re-read for every animation frame.
 */
internal fun nativeBringIntoViewLocalRect(ancestor: View, child: View, requested: Rect): Rect? {
    if (!ancestor.isAttachedToWindow || !child.isAttachedToWindow) return null
    val fullBounds = RectF(0f, 0f, child.width.toFloat(), child.height.toFloat())
    var current = child
    while (current !== ancestor) {
        val parent = current.parent as? View ?: return null
        current.matrix.mapRect(fullBounds)
        fullBounds.offset((current.left - parent.scrollX).toFloat(), (current.top - parent.scrollY).toFloat())
        current = parent
    }
    return requested.translate(Offset(fullBounds.left, fullBounds.top))
}

internal fun Rect.toNativeBringIntoViewRect(): AndroidRect =
    // AndroidComposeView's final native boundary uses toAndroidRect's Float.toInt conversion.
    AndroidRect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
