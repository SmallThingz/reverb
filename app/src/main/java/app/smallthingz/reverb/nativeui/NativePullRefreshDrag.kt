/*
 * Derived from AndroidX Material3 1.4.0 PullToRefresh.kt, Copyright 2023 The Android
 * Open Source Project, Apache-2.0. See pull-refresh/SOURCE.md and incidents/APACHE-2.0.txt.
 */
package app.smallthingz.reverb.nativeui

import kotlin.math.abs
import kotlin.math.pow

/** Physical Y drag accumulator. No View, dispatcher, refresh work or additional input policy. */
internal class NativePullRefreshDrag(val thresholdPx: Int) {
    var distancePulled = 0f
        private set
    var verticalOffset = 0f
        private set
    val adjustedDistance get() = distancePulled * .5f
    val shouldRefresh get() = adjustedDistance > thresholdPx

    fun attach(refreshing: Boolean) {
        // Source onAttach resets the visible offset, but retains this node's distance accumulator.
        verticalOffset = if (refreshing) thresholdPx.toFloat() else 0f
    }

    fun consume(availableY: Float, refreshing: Boolean): Float {
        if (refreshing) return 0f
        val next = (distancePulled + availableY).coerceAtLeast(0f)
        val consumed = next - distancePulled
        distancePulled = next
        verticalOffset = nativePullRefreshOffset(adjustedDistance, thresholdPx)
        return consumed
    }

    fun consumedReleaseVelocity(availableY: Float): Float = when {
        distancePulled == 0f -> 0f
        availableY < 0f -> 0f
        else -> availableY
    }

    fun hidden() { distancePulled = 0f; verticalOffset = 0f }
    fun atThreshold() { distancePulled = thresholdPx.toFloat(); verticalOffset = thresholdPx.toFloat() }
}

internal fun nativePullRefreshOffset(adjustedDistance: Float, thresholdPx: Int): Float {
    if (adjustedDistance <= thresholdPx) return adjustedDistance
    val overshootPercent = abs(adjustedDistance / thresholdPx) - 1f
    val linearTension = overshootPercent.coerceIn(0f, 2f)
    val tensionPercent = linearTension - linearTension.pow(2) / 4
    return thresholdPx + thresholdPx * tensionPercent
}
