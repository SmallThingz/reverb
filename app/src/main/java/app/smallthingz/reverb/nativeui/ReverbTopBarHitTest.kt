package app.smallthingz.reverb.nativeui

import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.geometry.Size
import app.smallthingz.reverb.NativePointerHitScore
import app.smallthingz.reverb.nativeMinimumTouchDistance
import kotlin.math.min

/** Actual clipped46dp visual bounds, not the optional48dp wrapper's rectangular hit box. */
internal data class ReverbTopBarHitBounds(
    val left: Float, val top: Float, val width: Float, val height: Float, val radius: Float,
)

/** Source sibling priority: any admitted direct hit beats near hits; equal near hits retain z order. */
internal fun reverbTopBarHitIndex(
    highestZFirst: List<ReverbTopBarHitBounds>, x: Float, y: Float, touch: Boolean, minimumTarget: Float,
): Int? {
    if (!x.isFinite() || !y.isFinite()) return null
    var best: NativePointerHitScore? = null
    var bestIndex: Int? = null
    for ((index, box) in highestZFirst.withIndex()) {
        val localX = x - box.left; val localY = y - box.top
        val radius = min(box.radius, min(box.width, box.height) / 2f).coerceAtLeast(0f)
        val inBounds = localX >= 0f && localY >= 0f && localX < box.width && localY < box.height
        val dx = localX - localX.coerceIn(radius, box.width - radius)
        val dy = localY - localY.coerceIn(radius, box.height - radius)
        val withinShape = inBounds && dx * dx + dy * dy <= radius * radius
        if (withinShape) return index
        if (!touch) continue
        val distance = nativeMinimumTouchDistance(Offset(localX, localY), Size(box.width, box.height),
            Size(minimumTarget, minimumTarget))
        if (!distance.isFinite()) continue
        val score = NativePointerHitScore(distance, false)
        if (best == null || score < best) { best = score; bestIndex = index }
    }
    return bestIndex
}
