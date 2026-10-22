package app.smallthingz.reverb

import android.view.View
import android.view.ViewGroup
import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.geometry.Size
import app.smallthingz.reverb.nativeui.NativePointerLayerShape
import app.smallthingz.reverb.nativeui.ReverbHomeScaffoldView
import java.util.IdentityHashMap

/**
 * Finite Home subtree adapter for Main's one input root. Other screen roots are not recursively
 * adopted. Draw/layout stay with ReverbHomeScreenView; this composes its existing blob/control nodes
 * under the released Scaffold vertical detector and binds only actual top-bar click leaves.
 */
internal class NativeHomePointerBinding(
    private val home: ReverbHomeScaffoldView,
    private val geometry: NativeWindowPointerCoordinates,
    private val density: () -> Float,
    private val vertical: NativeHomeVerticalDrag,
    private val gestureEnabled: () -> Boolean,
    private val customLayout: (View) -> NativeViewPointerHitLayout? = { null },
) : AutoCloseable {
    private val layouts = IdentityHashMap<View, Layout>()
    private var wasEnabled = gestureEnabled()
    // Scaffold's Material Surface appends pointerInput(Unit) {} even when Home drag is disabled.
    // Preserve that non-consuming hit shield over retained Settings behind the Home surface.
    private val surfaceNode = object : NativeViewPhasePointerNode {
        override val view: View get() = home
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
        override fun cancel() = Unit
    }
    private val parentNode = object : NativeViewPhasePointerNode {
        override val view: View get() = home
        override val attached get() = home.isAttachedToWindow && gestureEnabled()
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = vertical.dispatch(event, pass)
        override fun cancel() = vertical.cancel()
    }

    val layout: NativeViewPointerHitLayout get() {
        refresh()
        return layoutFor(home)
    }

    fun refresh() {
        val enabled = gestureEnabled()
        if (enabled != wasEnabled) {
            // pointerInput(enabled) replaces its coroutine without inventing onDragCancel.
            vertical.restart()
            wasEnabled = enabled
        }
        fun belongs(view: View): Boolean {
            var cursor: View? = view
            while (cursor != null) {
                if (cursor === home) return true
                cursor = cursor.parent as? View
            }
            return false
        }
        layouts.keys.filterNot(::belongs).forEach { layouts.remove(it)?.close() }
    }

    private fun layoutFor(view: View): NativeViewPointerHitLayout = customLayout(view) ?:
        if (view is NativeHomePointerRoot) view.parentPointerLayout(geometry.windowPositionInScreen)
        else layouts.getOrPut(view) { Layout(view) }

    private inner class Layout(private val view: View) : NativeViewPointerHitLayout {
        private val click = (view as? NativeClickPhaseHost)?.let {
            // Plain pointerInput drag detectors do not establish a ScrollableContainer/DragGestureNode
            // press-delay ancestor. Reuse the existing source click endpoint and false interest.
            NativeClickPhaseNode(view, it.nativeClickEndpoint) { false }
        }
        override val placed get() = view.isAttachedToWindow && view.visibility == View.VISIBLE
        override val size get() = Size(view.width.toFloat(), view.height.toFloat())
        override val minimumTouchTarget get() = Size(48f * density(), 48f * density())
        override val nodes: List<NativeViewPhasePointerNode> get() = when {
            view === home -> if (gestureEnabled()) listOf(parentNode, surfaceNode) else listOf(surfaceNode)
            click != null -> listOf(click)
            else -> emptyList()
        }
        override val childrenInHitOrder: List<NativeViewPointerHitLayout> get() {
            val group = view as? ViewGroup ?: return emptyList()
            return (group.childCount - 1 downTo 0).map { layoutFor(group.getChildAt(it)) }
        }
        override fun screenToLocal(screen: Offset) = geometry.coordinates(view).local(screen)
        override fun withinLayerBounds(local: Offset): Boolean =
            (view as? NativePointerLayerShape)?.containsPointerLayer(local.x, local.y) ?: true
        fun close() { click?.cancel() }
    }

    /** Main cancels its retained pointer paths before retiring this binding. */
    override fun close() {
        vertical.restart()
        layouts.values.forEach { it.close() }
        layouts.clear()
    }
}
