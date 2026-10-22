package app.smallthingz.reverb

import android.content.Context
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import app.smallthingz.nativeui.geometry.Offset

/** Captured authoritative float matrices from the window's existing geometry owner. */
internal class NativePointerCoordinates(
    private val screenToLocal: Matrix,
    val rootToLocal: Matrix,
    val originOnScreen: Offset,
) {
    private val point = FloatArray(2)
    fun local(screen: Offset): Offset {
        point[0] = screen.x
        point[1] = screen.y
        screenToLocal.mapPoints(point)
        return Offset(point[0], point[1])
    }
}

internal interface NativeViewPhasePointerNode : NativePhasePointerNode {
    val view: View
    override val attached: Boolean get() = view.isAttachedToWindow
    /** Motion is supplied only for this event, split to this retained node's pointer IDs. */
    fun bindMotion(event: MotionEvent?, coordinates: NativePointerCoordinates?, androidPointerMask: Int) = Unit
}

/** Existing concrete View/coordinator geometry, retained by the owning screen. */
internal interface NativeViewPointerHitLayout : NativePointerHitLayout {
    override val nodes: List<NativeViewPhasePointerNode>
    override val childrenInHitOrder: List<NativeViewPointerHitLayout>
}

/**
 * Root replacing the existing onboarding viewport FrameLayout, not another visual wrapper.
 * It owns actual touch dispatch; participating rows/vertical hosts must not also use platform
 * ScrollView interception. Accessibility/hover are left to the separate window owners.
 *
 * hitLayout supplies actual component shape/min-target/placed child geometry in source order.
 * NativePointerHitTest owns hit selection; no platform rectangle/elevation fallback is used.
 * Each newly pressed pointer is hit-tested once. Movement/UP retain that exact node identity path.
 */
internal open class NativePagerTouchRoot(
    context: Context,
    rootToScreen: (Offset) -> Offset,
    private val coordinates: (View) -> NativePointerCoordinates,
    private val hitLayout: NativeViewPointerHitLayout,
    private val prepareCoordinates: (MotionEvent) -> Unit,
    mouseButtons: Boolean = false,
) : FrameLayout(context) {
    private val source = NativeMotionPointerSource(rootToScreen, mouseButtons)
    private val paths = NativePointerPhaseTree()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val owned = paths.hasPaths
            cancelPointerInput()
            return if (owned) true else super.dispatchTouchEvent(event)
        }
        if (!nativePressedPointerAction(event.actionMasked)) return super.dispatchTouchEvent(event)
        prepareCoordinates(event)
        val frame = source.read(event) ?: return super.dispatchTouchEvent(event)
        paths.pruneDetached()
        for (change in frame.changes) {
            if (change.pressed && !change.previouslyPressed) {
                paths.add(change.id, nativePointerHitPath(hitLayout, change.screenPosition, change.type))
            }
        }
        val bound = mutableListOf<NativeViewPhasePointerNode>()
        return try {
            val dispatched = paths.dispatch { owner, ids ->
                val node = owner as NativeViewPhasePointerNode
                val transform = coordinates(node.view)
                var mask = 0
                for (change in frame.changes) {
                    if (change.id in ids) mask = mask or (1 shl change.androidPointerId)
                }
                node.bindMotion(event, transform, mask)
                bound += node
                frame.project(transform::local, transform.originOnScreen, ids)
            }
            paths.release(frame.changes.filterNot { it.pressed }.mapTo(mutableSetOf()) { it.id })
            // Pinned PointerInputEventProcessor computes movement separately from dispatch/handled.
            // AndroidView interop can suppress this request on the SAME per-event state.
            if (frame.anyMovementConsumed()) parent?.requestDisallowInterceptTouchEvent(true)
            dispatched
        } finally {
            bound.forEach { it.bindMotion(null, null, 0) }
        }
    }

    fun cancelPointerInput() {
        paths.cancel()
        source.cancel()
    }

    override fun onDetachedFromWindow() {
        cancelPointerInput()
        super.onDetachedFromWindow()
    }

}
