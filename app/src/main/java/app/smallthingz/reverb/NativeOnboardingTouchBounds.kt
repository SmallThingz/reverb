/*
 * Minimum-touch clipping adapted from AndroidX ui 1.11.2 NodeCoordinator.
 * Copyright 2020 The Android Open Source Project. Apache License 2.0.
 */
package app.smallthingz.reverb

import app.smallthingz.nativeui.geometry.Rect
import app.smallthingz.nativeui.geometry.Size

/** NodeCoordinator.touchBoundsInRoot initial padding; this is semantic geometry, not View layout. */
internal fun nativeOnboardingMinimumTouchBounds(size: Size, minimum: Size): Rect {
    val horizontal = ((minimum.width - size.width) / 2f).coerceAtLeast(0f)
    val vertical = ((minimum.height - size.height) / 2f).coerceAtLeast(0f)
    return Rect(-horizontal, -vertical, size.width + horizontal, size.height + vertical)
}

/**
 * NodeCoordinator.rectInParent(clipToMinimumTouchTargetSize=true), before mapping that layer.
 * A large child partially crossing a clipping ancestor can keep up to half the minimum target
 * beyond that ancestor. Its own size exceeding the minimum does not disable this rule.
 */
internal fun nativeOnboardingClipTouchBounds(bounds: Rect, layer: Size, minimum: Size): Rect {
    val outside = bounds.right < 0f || bounds.left > layer.width ||
        bounds.bottom < 0f || bounds.top > layer.height
    val underWidth = (minimum.width - bounds.width) / 2f
    val underHeight = (minimum.height - bounds.height) / 2f
    val left = when {
        outside -> 0f
        underWidth > 0f -> bounds.left - underWidth
        else -> bounds.left.coerceAtLeast(-minimum.width / 2f)
    }
    val top = when {
        outside -> 0f
        underHeight > 0f -> bounds.top - underHeight
        else -> bounds.top.coerceAtLeast(-minimum.height / 2f)
    }
    val right = minOf(layer.width + minimum.width, maxOf(layer.width, left + minimum.width))
    val bottom = minOf(layer.height + minimum.height, maxOf(layer.height, top + minimum.height))
    val clipped = bounds.intersect(Rect(left, top, right, bottom))
    return if (clipped.isEmpty) Rect.Zero else clipped
}
