package app.smallthingz.reverb.nativeui

import android.graphics.Path
import kotlin.math.min
import kotlin.math.roundToInt

/** CaptureScreen.MainCaptureContent and BufferBlobPage: all edge rounding stays independent. */
internal data class ReverbHomeGeometry(
    val side: Int,
    val bodyWidth: Int,
    val blobAreaHeight: Int,
    val blobSize: Int,
    val clusterWidth: Int,
    val clusterHeight: Int,
    val bottomSpacer: Int,
)

internal fun reverbHomeGeometry(width: Int, height: Int, density: Float): ReverbHomeGeometry {
    fun dp(value: Float) = (value * density).roundToInt()
    val side = dp(18f)
    val bodyWidth = (width - side * 2).coerceAtLeast(0)
    // Column measures non-weight children first, in declaration order. A constrained size modifier
    // can shrink the cluster and then the spacer; it never manufactures additional room.
    val clusterHeight = min(dp(112f), height.coerceAtLeast(0))
    val bottom = min(dp(18f), (height - clusterHeight).coerceAtLeast(0))
    val blobArea = (height - clusterHeight - bottom).coerceAtLeast(0)
    // BoxWithConstraints reports px/density; Dp arithmetic then roundToPx mirrors the source.
    val blobDp = min(min(bodyWidth / density * .90f, blobArea / density * .94f), 372f)
    return ReverbHomeGeometry(side, bodyWidth, blobArea, dp(blobDp),
        min(dp(256f), bodyWidth), clusterHeight, bottom)
}

/** Literal captureControlUnionShape path, evaluated in the actual constrained surface size. */
internal fun reverbCaptureControlUnionPath(width: Float, height: Float, destination: Path) {
    val centerX = width * .5f
    val topHeight = height * (54f / 112f)
    val bottomTop = height * (42f / 112f)
    val topWidth = width * (242f / 256f)
    val bottomWidth = width
    val topLeft = centerX - topWidth * .5f
    val topRight = centerX + topWidth * .5f
    val bottomLeft = centerX - bottomWidth * .5f
    val bottomRight = centerX + bottomWidth * .5f
    val topRadius = topHeight * .40f
    val bottomRadius = (height - bottomTop) * .40f
    val squircleControl = .44f
    val transitionStart = topHeight * .52f
    val transitionEnd = bottomTop + bottomRadius * .62f
    val transitionSpan = (transitionEnd - transitionStart).coerceAtLeast(1f)
    val handle = transitionSpan * .46f
    destination.rewind()
    destination.moveTo(topLeft + topRadius, 0f)
    destination.lineTo(topRight - topRadius, 0f)
    destination.cubicTo(topRight - topRadius * squircleControl, 0f,
        topRight, topRadius * squircleControl, topRight, topRadius)
    destination.lineTo(topRight, transitionStart)
    destination.cubicTo(topRight, transitionStart + handle,
        bottomRight, transitionEnd - handle, bottomRight, transitionEnd)
    destination.lineTo(bottomRight, height - bottomRadius)
    destination.cubicTo(bottomRight, height - bottomRadius * squircleControl,
        bottomRight - bottomRadius * squircleControl, height, bottomRight - bottomRadius, height)
    destination.lineTo(bottomLeft + bottomRadius, height)
    destination.cubicTo(bottomLeft + bottomRadius * squircleControl, height,
        bottomLeft, height - bottomRadius * squircleControl, bottomLeft, height - bottomRadius)
    destination.lineTo(bottomLeft, transitionEnd)
    destination.cubicTo(bottomLeft, transitionEnd - handle, topLeft, transitionStart + handle,
        topLeft, transitionStart)
    destination.lineTo(topLeft, topRadius)
    destination.cubicTo(topLeft, topRadius * squircleControl, topLeft + topRadius * squircleControl,
        0f, topLeft + topRadius, 0f)
    destination.close()
}
