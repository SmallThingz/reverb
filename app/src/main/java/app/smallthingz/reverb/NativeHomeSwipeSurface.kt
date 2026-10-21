package app.smallthingz.reverb

import android.content.Context
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * Replaces the normal Home's existing weighted Box. The parent still owns its source dimensions and
 * explicitly positions the unchanged native blob; there is no additional visual wrapper or pager.
 */
internal fun nativeHomeSwipeSurface(
    context: Context,
    blob: View,
    endpoint: NativeClickPhaseEndpoint,
    detector: NativeHomeHorizontalDrag,
    minimumTarget: () -> Float,
): NativePagerTouchRoot {
    lateinit var root: NativePagerTouchRoot
    val coordinates = NativeWindowPointerCoordinates { root }
    val dragNode = object : NativeViewPhasePointerNode {
        override val view: View get() = root
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = detector.dispatch(event, pass)
        override fun cancel() = detector.cancel()
    }
    val clickNode = NativeClickPhaseNode(blob, endpoint) { false }
    // This graphics layer does not clip. The flip's exact current matrix controls local hit geometry.
    val blobLayout = object : NativeViewPointerHitLayout {
        override val placed get() = blob.isAttachedToWindow && blob.visibility == View.VISIBLE
        override val size get() = Size(blob.width.toFloat(), blob.height.toFloat())
        override val minimumTouchTarget get() = Size(minimumTarget(), minimumTarget())
        override val nodes = listOf(clickNode)
        override val childrenInHitOrder = emptyList<NativeViewPointerHitLayout>()
        override fun screenToLocal(screen: Offset) = coordinates.coordinates(blob).local(screen)
        override fun withinLayerBounds(local: Offset) = true
    }
    val hitLayout = object : NativeViewPointerHitLayout {
        override val placed get() = root.isAttachedToWindow && root.visibility == View.VISIBLE
        override val size get() = Size(root.width.toFloat(), root.height.toFloat())
        override val minimumTouchTarget get() = Size(minimumTarget(), minimumTarget())
        override val nodes = listOf(dragNode)
        override val childrenInHitOrder = listOf(blobLayout)
        override fun screenToLocal(screen: Offset) = coordinates.coordinates(root).local(screen)
        override fun withinLayerBounds(local: Offset) = true
    }
    root = object : NativePagerTouchRoot(context, coordinates::rootToScreen, coordinates::coordinates,
        hitLayout, coordinates::begin) {
        init {
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
        }
        // MainCaptureContent's concrete geometry places the blob and optional indicator.
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
    }
    return root
}
