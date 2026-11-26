package app.smallthingz.reverb.nativeui

import smallthingz.ui.reverb.ReverbLibraryLayout
import smallthingz.ui.reverb.ReverbSingleLineInputView
import smallthingz.ui.reverb.ReverbRecordingSpotlight
import smallthingz.ui.input.NativeAndroidInteropHost
import smallthingz.ui.input.phase.NativeAndroidInteropNode

import smallthingz.ui.input.phase.NativePointerLayerShape
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.scroll.NativeVerticalViewport
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import smallthingz.ui.input.phase.NativeClickPhaseHost
import smallthingz.ui.input.phase.NativeClickPhaseNode
import smallthingz.ui.input.phase.NativeDragPointerChange
import smallthingz.ui.input.phase.NativeDragPointerEvent
import app.smallthingz.reverb.NativeLibraryEdgeDismiss
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.scroll.NativeVerticalPhaseBinding
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.input.phase.NativeWindowPointerCoordinates
import smallthingz.ui.reverb.ReverbLibraryPlayerView as LibraryPlayerView
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
    private var listNodes = emptyList<NativeViewPhasePointerNode>()
    private var indicatorHit: NativeScrollIndicatorHitLayout? = null
    private var bodyHasPointer = false
    private var emptyView: NativeVerticalViewport? = null
    private var emptyScroll: NativeVerticalPhaseBinding? = null
    private var emptyNodes = emptyList<NativeViewPhasePointerNode>()
    private var dismissKey: Any? = null
    private var dismissDistance = Float.NaN
    private var inputRevision = content.inlinePointerRevision
    private var closed = false
    private val edge = NativeLibraryEdgeDismiss(
        { content.bodyPointerView.width.toFloat() },
        { configuration.scaledTouchSlop.toFloat() },
        { 64f * content.pointerEnvironment.textDensity.density },
        onEdgeDown = { listView?.sourceStopScroll?.invoke() },
        dismiss = content::dismissFromEdge,
    )
    private val edgeNode = object : NativeViewPhasePointerNode {
        override val view get() = content.bodyPointerView
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
            // This ancestor sees every ordinary body pointer, including drags waiting for slop.
            if (pass == NativePointerPass.INITIAL) bodyHasPointer = event.changes.any { it.pressed }
            edge.dispatch(event, pass)
        }
        override fun cancel() {
            bodyHasPointer = false
            edge.restart()
        }
    }
    private val edgeNodes = listOf(edgeNode)
    // FilesScreen's transparent Scaffold still owns Surface's inert pointerInput(Unit) node.
    // It admits empty background so lower Main siblings cannot receive the same DOWN hit.
    private fun scaffoldNodes(): List<NativeViewPhasePointerNode> {
        val revision = content.inlinePointerRevision
        return listOf(object : NativeViewPhasePointerNode {
            override val view get() = content
            override val attached get() = !closed && content.pointerInputActive &&
                revision == content.inlinePointerRevision && content.isAttachedToWindow
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
            override fun cancel() = Unit
        })
    }
    private var scaffoldNodes = scaffoldNodes()
    val layout: NativeViewPointerHitLayout = object : NativeViewPointerHitLayout {
        override val placed get() = !closed && content.pointerInputActive && content.isAttachedToWindow && content.isLaidOut && content.visibility == View.VISIBLE
        override val size get() = Size(content.width.toFloat(), content.height.toFloat())
        override val minimumTouchTarget get() = Size(ReverbLibraryLayout.minimumTouchTargetPx(density), ReverbLibraryLayout.minimumTouchTargetPx(density))
        override val nodes get(): List<NativeViewPhasePointerNode> { refresh(); return scaffoldNodes }
        override val childrenInHitOrder: List<NativeViewPointerHitLayout>
            get() {
                refresh()
                // A thumb press bypasses Body's edge recognizer and the row/refresh path.
                return children(content, first = indicatorHit)
            }
        override fun screenToLocal(screen: Offset) = geometry.coordinates(content).local(screen)
        override fun withinLayerBounds(local: Offset) = true
    }
    private val density get() = content.pointerEnvironment.textDensity.density

    fun refresh() {
        if (closed) return
        val inputChanged = inputRevision != content.inlinePointerRevision
        if (inputChanged) {
            inputRevision = content.inlinePointerRevision
            // One retired prefix removes the entire old Library path (including edge, click
            // and scrim). The root prunes it before adding any newly pressed pointer.
            scaffoldNodes = scaffoldNodes()
            layouts.values.forEach { it.cancelInput() }
            edgeNode.cancel()
        }
        val nextDistance = ReverbLibraryLayout.edgeDismissDistancePx(density)
        if (dismissKey != content.pointerDismissKey || dismissDistance != nextDistance) {
            dismissKey = content.pointerDismissKey
            dismissDistance = nextDistance
            edge.restart() // exact pointerInput(onDismissLibrary, edgeDismissDistancePx) lifetime
        }
        val nextList = content.listViewport
        val listChanged = nextList !== listView
        if (listChanged) {
            listScroll?.close()
            listView = nextList
            listScroll = nextList?.let {
                NativeLibraryScrollOwner(it, content.pointerEnvironment, frames, scope, content.nestedScroll, parentInterested)
            }
            rebuildListNodes()
        }
        if (listChanged || inputChanged) rebuildIndicatorHit(nextList)
        // The Info cutout uses View geometry; Android 12+ stretch deforms its card in a RenderNode.
        listView?.sourceOverscroll?.setEffectsEnabled(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !content.inlineInfoActive)
        val nextEmpty = content.emptyViewport
        if (nextEmpty !== emptyView) {
            emptyScroll?.close()
            emptyView = nextEmpty
            emptyScroll = nextEmpty?.let {
                NativeVerticalPhaseBinding(it, { configuration.scaledTouchSlop.toFloat() },
                    { configuration.scaledMaximumFlingVelocity.toFloat() }, parentInterested)
            }
            emptyNodes = emptyScroll?.let { java.util.Collections.unmodifiableList(listOf(it.observerNode, scrollNode(it.dragNode))) }
                ?: emptyList()
        }
        val obsolete = layouts.keys.filter { !it.isAttachedToWindow }
        obsolete.forEach { layouts.remove(it)?.close() }
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

    private fun children(view: View, first: NativeViewPointerHitLayout? = null): List<NativeViewPointerHitLayout> {
        if (view !is ViewGroup) return emptyList()
        val result = ArrayList<NativeViewPointerHitLayout>(view.childCount + if (first == null) 0 else 1)
        if (first != null) result.add(first)
        for (index in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(index)
            if (child.visibility == View.VISIBLE) result += layouts.getOrPut(child) { Layout(child) }
        }
        return result
    }
    private fun interested(view: View, change: NativeDragPointerChange): Boolean {
        if (edge.reservesScroll) return false
        var ancestor: View? = view
        while (ancestor != null && ancestor !== content) {
            if (ancestor === listView) return listScroll?.interested(change) == true || parentInterested(change)
            if (ancestor === emptyView) return emptyScroll?.interested(change) == true || parentInterested(change)
            ancestor = ancestor.parent as? View
        }
        return parentInterested(change)
    }

    private fun scrollNode(node: NativeViewPhasePointerNode) = object : NativeViewPhasePointerNode {
        override val view get() = node.view
        private val dispatchScroll = node::dispatch
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) =
            edge.dispatchScroll(event, pass, dispatchScroll)
        override fun cancel() = node.cancel()
    }

    private fun rebuildListNodes() {
        listNodes = listScroll?.let { listOf(it.observerNode, scrollNode(it.dragNode)) }
            ?: emptyList()
    }

    private fun rebuildIndicatorHit(viewport: ReverbLibraryRecyclerView?) {
        indicatorHit?.retire()
        indicatorHit = viewport?.let {
            NativeScrollIndicatorHitLayout(it, geometry, inHitRegion = { !bodyHasPointer }) {
                !closed && content.pointerInputActive && !content.inlineModalActive && content.listViewport === it
            }
        }
    }

    private inner class Layout(private val view: View) : NativeViewPointerHitLayout {
        private val click = (view as? NativeClickPhaseHost)?.let {
            NativeClickPhaseNode(view, it.nativeClickEndpoint) { change -> interested(view, change) }
        }
        private val shield = if (view is NativeGroupLayout) object : NativeViewPhasePointerNode {
            override val view get() = this@Layout.view
            override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
            override fun cancel() = Unit
        } else null
        private val interop = when {
            view is ReverbRecordingSpotlight -> NativeAndroidInteropNode(view, view.nativeInteropRequests)
            view is ReverbSingleLineInputView && view.parent is NativeAndroidInteropHost ->
                NativeAndroidInteropNode(view, (view.parent as NativeAndroidInteropHost).nativeInteropRequests)
            else -> null
        }
        private val interopNodes = interop?.let { node -> listOf(object : NativeViewPhasePointerNode by node {
            // The backdrop must never deliver the same DOWN to the row/toolbar behind it.
            override val shareWithSiblings get() = view !is ReverbRecordingSpotlight
        }) } ?: emptyList()
        private var scrimScrollSource = emptyList<NativeViewPhasePointerNode>()
        private var scrimScrollNodes = emptyList<NativeViewPhasePointerNode>()
        private val clickNodes = click?.let(::listOf) ?: emptyList()
        private val shieldNodes = shield?.let(::listOf) ?: emptyList()
        // New contacts during a thumb drag stop at Scaffold; they cannot join Body after lift.
        override val placed get() = view.isAttachedToWindow && view.isLaidOut && view.visibility == View.VISIBLE &&
            (view !== content.bodyPointerView || indicatorHit?.ownsPointer != true)
        // The source scroll observer and rows keep the original inset hit bounds.
        private val listInsetLeft get() = if (view === listView) view.paddingLeft else 0
        private val listInsetRight get() = if (view === listView) view.paddingRight else 0
        override val size get() = Size((view.width - listInsetLeft - listInsetRight).coerceAtLeast(0).toFloat(),
            view.height.toFloat())
        override val minimumTouchTarget get() = (view as? PlayerTimeline)?.minimumTouchTarget
            ?: Size(ReverbLibraryLayout.minimumTouchTargetPx(density), ReverbLibraryLayout.minimumTouchTargetPx(density))
        override val childrenInHitOrder get() = children(view)
        override fun screenToLocal(screen: Offset): Offset {
            val local = geometry.coordinates(view).local(screen)
            return Offset(local.x - listInsetLeft, local.y)
        }
        override val nodes: List<NativeViewPhasePointerNode>
            get() {
                if (view === content.bodyPointerView) return if (content.inlineModalActive) emptyList() else edgeNodes
                if (view is ReverbRecordingSpotlight && content.inlineInfoActive) {
                    // Same owner/coordinate-bound nodes as the card's ancestor path. Keep the
                    // exclusive interop leaf so dimmed rows never receive DOWN; drag consumption
                    // cancels its Android press through the existing Initial/Main/Final protocol.
                    if (scrimScrollSource !== listNodes) {
                        scrimScrollSource = listNodes
                        scrimScrollNodes = listNodes + interopNodes
                    }
                    return scrimScrollNodes
                }
                if (interop != null) return interopNodes
                if (view === listView) return listNodes
                if (view === emptyView) return emptyNodes
                val player = content.pointerNodes(view)
                if (player.isNotEmpty()) return player
                if (click != null && (view !is NativeLibrarySummaryInput || view.sourceClickPresent)) return clickNodes
                return shieldNodes
            }
        override fun withinLayerBounds(local: Offset): Boolean = when {
            view is NativeGroupLayout -> view.containsVisualPoint(local.x, local.y)
            view is NativePointerLayerShape -> view.containsPointerLayer(local.x, local.y)
            view === listView || view === emptyView -> {
                val extra = ReverbLibraryLayout.horizontalOverflowPx(density)
                local.x >= -extra && local.x < size.width + extra && local.y >= 0f && local.y < view.height
            }
            view is LibraryExpansionViewport || view is LibraryPlayerView<*> || view is ReverbLibraryRecordingRow ->
                local.x >= 0f && local.x < view.width && local.y >= 0f && local.y < view.height
            else -> true // No source graphicsLayer clip on ordinary Column/Row/layout wrappers.
        }
        fun cancelInput() { try { click?.cancel() } finally { interop?.cancel() } }
        fun close() { try { click?.cancel() } finally { interop?.close() } }
    }
    override fun close() {
        if (closed) return
        closed = true
        indicatorHit?.retire()
        indicatorHit = null
        listScroll?.close(); listScroll = null; listView = null
        listNodes = emptyList()
        emptyScroll?.close(); emptyScroll = null; emptyView = null
        emptyNodes = emptyList()
        layouts.values.forEach { it.close() }
        layouts.clear()
        edgeNode.cancel()
    }
}
