package app.smallthingz.reverb

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.smallthingz.reverb.nativeui.NativePointerLayerShape
import app.smallthingz.reverb.nativeui.ReverbGroupLayout
import app.smallthingz.reverb.nativeui.ReverbRangeExportContent
import java.util.IdentityHashMap

/**
 * Range's source pointer modifiers in Main's ONE window dispatch. The waveform transform affects
 * drawing only: scrub coordinates stay on the untransformed waveformInput container. Endpoints
 * retain their own moving local coordinates and source44dp layout/48dp minimum finger target.
 */
internal class NativeRangePointerBinding(
    private val content: ReverbRangeExportContent,
    private val coordinates: NativeWindowPointerCoordinates,
    private val density: () -> Float,
    private val touchSlop: () -> Float,
) : AutoCloseable {
    private val layouts = IdentityHashMap<View, Layout>()
    private var closed = false
    private val timeline = NativeRangeTimelineInput(content.state, { content.interactionEnabled },
        content.timeline::timelineWidthPixels, content.timeline::secondsForWaveformX,
        content.timeline::markerCenterPixels, touchSlop)
    private val waveformNode = GestureNode(content.timeline.waveformInput, timeline.waveform, false)
    private val startNode = GestureNode(content.timeline.endpointStart, timeline.start, true)
    private val endNode = GestureNode(content.timeline.endpointEnd, timeline.end, true)

    val layout: NativeViewPointerHitLayout get() = layoutFor(content)

    init {
        content.onLeafStructureChanged = ::retireRemovedLeaves
        content.onGeometryChanged = ::refresh
        content.onInputBindingChanged = ::refresh
    }

    /** Called at the rendered binding/layout boundary, like source pointerInput key replacement. */
    fun refresh() {
        if (closed) return
        timeline.refreshKeys()
        retireRemovedLeaves()
    }
    private fun retireRemovedLeaves() {
        fun belongs(view: View): Boolean {
            var next: View? = view
            while (next != null) {
                if (next === content) return true
                next = next.parent as? View
            }
            return false
        }
        layouts.keys.filterNot(::belongs).forEach { layouts.remove(it)?.close() }
    }
    private fun layoutFor(view: View): Layout = layouts.getOrPut(view) { Layout(view) }

    private inner class GestureNode(
        override val view: View,
        private val gesture: NativeRangeTimelineInput.Gesture,
        private val endpoint: Boolean,
    ) : NativeViewPhasePointerNode {
        override val attached get() = !closed && view.isAttachedToWindow && (!endpoint || content.interactionEnabled)
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = gesture.dispatch(event, pass)
        override fun cancel() = gesture.cancel()
    }

    private fun children(view: View): List<NativeViewPointerHitLayout> {
        if (view !is ViewGroup || view === content.durationWheel) return emptyList()
        val sourceOrder = content.timeline.pointerChildrenInHitOrder(view)
        if (sourceOrder != null) return sourceOrder.map(::layoutFor)
        return (view.childCount - 1 downTo 0).map { view.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE }.map(::layoutFor)
    }

    private inner class Layout(private val view: View) : NativeViewPointerHitLayout, AutoCloseable {
        private val click = (view as? NativeClickPhaseHost)?.let {
            NativeClickPhaseNode(view, it.nativeClickEndpoint) { false }
        }
        private val wheel = if (view === content.durationWheel)
            NativeAndroidInteropNode(view, content.nativeInteropRequests) else null
        private val surfaceShield = if (view is ReverbGroupLayout) object : NativeViewPhasePointerNode {
            override val view get() = this@Layout.view
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
            override fun cancel() = Unit
        } else null
        override val placed get() = !closed && view.isAttachedToWindow && view.isLaidOut &&
            view.visibility == View.VISIBLE
        override val size get() = Size(view.width.toFloat(), view.height.toFloat())
        override val minimumTouchTarget get() = Size(48f * density(), 48f * density())
        override val childrenInHitOrder get() = children(view)
        override fun screenToLocal(screen: Offset) = coordinates.coordinates(view).local(screen)
        override fun withinLayerBounds(local: Offset): Boolean = when (view) {
            is ReverbGroupLayout -> view.containsVisualPoint(local.x, local.y)
            is NativePointerLayerShape -> view.containsPointerLayer(local.x, local.y)
            else -> true
        }
        override val nodes: List<NativeViewPhasePointerNode> get() = when (view) {
            content.timeline.waveformInput -> listOf(waveformNode) // Present even when disabled.
            content.timeline.endpointStart -> if (content.interactionEnabled) listOf(startNode) else emptyList()
            content.timeline.endpointEnd -> if (content.interactionEnabled) listOf(endNode) else emptyList()
            content.fine -> if (content.fine.fineSeekInputEnabled) listOf(content.fine.fineSeekNode) else emptyList()
            content.fine.puckClickView -> listOf(content.fine.puckClickNode) // Disabled clickable retained.
            else -> wheel?.let(::listOf) ?: click?.let(::listOf) ?: surfaceShield?.let(::listOf) ?: emptyList()
        }
        override fun close() { try { click?.cancel() } finally { wheel?.close() } }
    }

    /** Main cancels its retained pointer paths before retiring this binding. */
    override fun close() {
        if (closed) return
        closed = true
        content.onLeafStructureChanged = {}; content.onGeometryChanged = {}; content.onInputBindingChanged = {}
        var failure: Throwable? = null
        fun release(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        release(timeline::close)
        layouts.values.forEach { release(it::close) }
        layouts.clear()
        failure?.let { throw it }
    }
}
