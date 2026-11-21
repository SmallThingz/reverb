package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeCaptureControlsStyle
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

internal fun reverbHomeGeometry(
    width: Int,
    height: Int,
    density: Float,
    captureControls: NativeCaptureControlsStyle,
): ReverbHomeGeometry {
    fun dp(value: Float) = (value * density).roundToInt()
    val side = dp(18f)
    val bodyWidth = (width - side * 2).coerceAtLeast(0)
    // Column measures non-weight children first, in declaration order. A constrained size modifier
    // can shrink the cluster and then the spacer; it never manufactures additional room.
    val clusterHeight = min(captureControls.preferredHeightPx(density), height.coerceAtLeast(0))
    val bottom = min(dp(18f), (height - clusterHeight).coerceAtLeast(0))
    val blobArea = (height - clusterHeight - bottom).coerceAtLeast(0)
    // BoxWithConstraints reports px/density; Dp arithmetic then roundToPx mirrors the source.
    val blobDp = min(min(bodyWidth / density * .90f, blobArea / density * .94f), 372f)
    return ReverbHomeGeometry(side, bodyWidth, blobArea, dp(blobDp),
        min(captureControls.preferredWidthPx(density), bodyWidth), clusterHeight, bottom)
}
