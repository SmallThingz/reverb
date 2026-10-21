package app.smallthingz.reverb.nativeui

import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.NativeClickPhaseNode
import app.smallthingz.reverb.NativeDragPointerChange
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativeLibraryEdgeDismiss
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.NativeVerticalPhaseBinding
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.NativeViewPointerHitLayout
import app.smallthingz.reverb.NativeWindowPointerCoordinates
import kotlinx.coroutines.CoroutineScope
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * FilesScreen's actual modifier tree on the existing window phase dispatcher. RV only places rows;
 * its stock touch/fling path must not also receive these events. No second window/input root.
 * The window calls refresh before DOWN hit testing (the root child getter also reconciles lazily).
 */
internal class NativeLibraryPointerBinding(
    private val content: ReverbLibraryScreenContent,
    private val geometry: NativeWindowPointerCoordinates,
    private val frames: MonotonicFrameClock,
    private val scope: CoroutineScope,
    private val parentInterested: (NativeDragPointerChange) -> Boolean,
) : AutoCloseable {
    private val configuration = ViewConfiguration.get(content.context)
    private val layouts = IdentityHashMap<View, Layout>()
    private var listView: ReverbLibraryRecyclerView? = null
    private var listScroll: NativeLibraryScrollOwner? = null
    private var emptyView: NativeVerticalViewport? = null
    private var emptyScroll: NativeVerticalPhaseBinding? = null
    private var dismissKey: Any? = null
    private var dismissDistance = Float.NaN
    private var closed = false
    private val edge = NativeLibraryEdgeDismiss(
        { content.bodyPointerView.width.toFloat() },
        { configuration.scaledTouchSlop.toFloat() },
        { 64f * content.pointerEnvironment.textDensity.density },
        content::dismissFromEdge,
    )
    private val edgeNode = object : NativeViewPhasePointerNode {
        override val view get() = content.bodyPointerView
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = edge.dispatch(event, pass)
        override fun cancel() = edge.restart()
    }
    // FilesScreen's transparent Scaffold still owns Surface's inert pointerInput(Unit) node.
    // It admits empty background so lower Main siblings cannot receive the same DOWN hit.
    private val scaffoldShield = object : NativeViewPhasePointerNode {
        override val view get() = content
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
        override fun cancel() = Unit
    }
    val layout: NativeViewPointerHitLayout = object : NativeViewPointerHitLayout {
        override val placed get() = !closed && content.isAttachedToWindow && content.isLaidOut && content.visibility == View.VISIBLE
        override val size get() = Size(content.width.toFloat(), content.height.toFloat())
        override val minimumTouchTarget get() = Size(48f * density, 48f * density)
        override val nodes = listOf(scaffoldShield)
        override val childrenInHitOrder: List<NativeViewPointerHitLayout>
            get() { refresh(); return children(content) }
        override fun screenToLocal(screen: Offset) = geometry.coordinates(content).local(screen)
        override fun withinLayerBounds(local: Offset) = true
    }
    private val density get() = content.pointerEnvironment.textDensity.density

    fun refresh() {
        if (closed) return
        val nextDistance = 64f * density
        if (dismissKey != content.pointerDismissKey || dismissDistance != nextDistance) {
            dismissKey = content.pointerDismissKey
            dismissDistance = nextDistance
            edge.restart() // exact pointerInput(onDismissLibrary, edgeDismissDistancePx) lifetime
        }
        val nextList = content.listViewport
        if (nextList !== listView) {
            listScroll?.close()
            listView = nextList
            listScroll = nextList?.let {
                NativeLibraryScrollOwner(it, content.pointerEnvironment, frames, scope, content.nestedScroll, parentInterested)
            }
        }
        val nextEmpty = content.emptyViewport
        if (nextEmpty !== emptyView) {
            emptyScroll?.close()
            emptyView = nextEmpty
            emptyScroll = nextEmpty?.let {
                NativeVerticalPhaseBinding(it, { configuration.scaledTouchSlop.toFloat() },
                    { configuration.scaledMaximumFlingVelocity.toFloat() }, parentInterested)
            }
        }
        val obsolete = layouts.keys.filter { !it.isAttachedToWindow }
        obsolete.forEach { layouts.remove(it)?.cancel() }
    }

    /** Native window preview-key phase calls this only for a focused descendant of the list. */
    fun pageKey(focused: View?, event: KeyEvent): Boolean {
        val list = listView ?: return false
        if (event.keyCode != KeyEvent.KEYCODE_PAGE_DOWN && event.keyCode != KeyEvent.KEYCODE_PAGE_UP) return false
        var ancestor = focused
        while (ancestor != null && ancestor !== list) ancestor = ancestor.parent as? View
        if (ancestor !== list) return false
        return listScroll?.pageKey(event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN,
            event.action == KeyEvent.ACTION_DOWN, event.isCtrlPressed) == true
    }

    private fun children(view: View): List<NativeViewPointerHitLayout> {
        if (view !is ViewGroup) return emptyList()
        val result = ArrayList<NativeViewPointerHitLayout>(view.childCount)
        for (index in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(index)
            if (child.visibility == View.VISIBLE) result += layouts.getOrPut(child) { Layout(child) }
        }
        return result
    }
    private fun interested(view: View, change: NativeDragPointerChange): Boolean {
        var ancestor: View? = view
        while (ancestor != null && ancestor !== content) {
            if (ancestor === listView) return listScroll?.interested(change) == true || parentInterested(change)
            if (ancestor === emptyView) return emptyScroll?.interested(change) == true || parentInterested(change)
            ancestor = ancestor.parent as? View
        }
        return parentInterested(change)
    }

    private inner class Layout(private val view: View) : NativeViewPointerHitLayout {
        private val click = (view as? NativeClickPhaseHost)?.let {
            NativeClickPhaseNode(view, it.nativeClickEndpoint) { change -> interested(view, change) }
        }
        private val shield = if (view is ReverbGroupLayout) object : NativeViewPhasePointerNode {
            override val view get() = this@Layout.view
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
            override fun cancel() = Unit
        } else null
        override val placed get() = view.isAttachedToWindow && view.isLaidOut && view.visibility == View.VISIBLE
        override val size get() = Size(view.width.toFloat(), view.height.toFloat())
        override val minimumTouchTarget get() = Size(48f * density, 48f * density)
        override val childrenInHitOrder get() = children(view)
        override fun screenToLocal(screen: Offset) = geometry.coordinates(view).local(screen)
        override val nodes: List<NativeViewPhasePointerNode>
            get() {
                if (view === content.bodyPointerView) return listOf(edgeNode)
                if (view === listView) return listScroll?.let { listOf(it.observerNode, it.dragNode) } ?: emptyList()
                if (view === emptyView) return emptyScroll?.let { listOf(it.observerNode, it.dragNode) } ?: emptyList()
                val player = content.pointerNodes(view)
                if (player.isNotEmpty()) return player
                if (click != null && (view !is NativeLibrarySummaryInput || view.sourceClickPresent)) return listOf(click)
                return shield?.let(::listOf) ?: emptyList()
            }
        override fun withinLayerBounds(local: Offset): Boolean = when {
            view is ReverbGroupLayout -> view.containsVisualPoint(local.x, local.y)
            view is NativePointerLayerShape -> view.containsPointerLayer(local.x, local.y)
            view === listView || view === emptyView -> {
                val extra = (30f * density).roundToInt()
                local.x >= -extra && local.x < view.width + extra && local.y >= 0f && local.y < view.height
            }
            view is LibraryExpansionViewport || view is ReverbLibraryPlayerView || view is ReverbLibraryRecordingRow ->
                local.x >= 0f && local.x < view.width && local.y >= 0f && local.y < view.height
            else -> true // No source graphicsLayer clip on ordinary Column/Row/layout wrappers.
        }
        fun cancel() { click?.cancel() }
    }
    override fun close() {
        if (closed) return
        closed = true
        listScroll?.close(); listScroll = null; listView = null
        emptyScroll?.close(); emptyScroll = null; emptyView = null
        layouts.values.forEach { it.cancel() }
        layouts.clear()
        edge.restart()
    }
}
