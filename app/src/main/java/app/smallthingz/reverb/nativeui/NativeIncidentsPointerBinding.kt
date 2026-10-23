package app.smallthingz.reverb.nativeui

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.NativeClickPhaseNode
import app.smallthingz.reverb.NativeDragPointerChange
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.NativeViewPointerHitLayout
import app.smallthingz.reverb.NativeWindowPointerCoordinates
import kotlinx.coroutines.CoroutineScope
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * IncidentsScreen's concrete modifier/input hierarchy in Main's existing phase dispatcher.
 * The RV only virtualizes and reports consumed integer pixels; the existing source scroll owner
 * handles drag/fling/nesting. No MotionEvent redispatch, platform RV scrolling or second root.
 *
 * Coordinates come from actual transformed Views, including the panel's translation and each
 * animateItem placement. The full source Surface contributes its inert pointer shield, so an
 * otherwise empty part of the translated panel cannot fall through to Home or Library.
 */
internal class NativeIncidentsPointerBinding(
    private val view: NativeIncidentsScreenView,
    private val geometry: NativeWindowPointerCoordinates,
    private val frames: MonotonicFrameClock,
    private val scope: CoroutineScope,
    private val parentInterested: (NativeDragPointerChange) -> Boolean,
) : AutoCloseable {
    private val layouts = IdentityHashMap<View, Layout>()
    private var listView: ReverbLibraryRecyclerView? = null
    private var listScroll: NativeLibraryScrollOwner? = null
    private var closed = false

    // Released Incidents has no surrounding scrollable ancestor or pull-to-refresh connection.
    private val nested = object : NativeVerticalNestedHost {
        override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
        override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
        override suspend fun preFling(availableY: Float) = 0f
        override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
    }

    val layout: NativeViewPointerHitLayout = object : NativeViewPointerHitLayout {
        override val placed get() = !closed && view.isAttachedToWindow && view.isLaidOut &&
            view.visibility == View.VISIBLE
        override val size get() = Size(view.width.toFloat(), view.height.toFloat())
        override val minimumTouchTarget get() = Size(48f * density, 48f * density)
        override val nodes = emptyList<NativeViewPhasePointerNode>()
        override val childrenInHitOrder: List<NativeViewPointerHitLayout>
            get() {
                refresh()
                return view.pointerContent?.let { listOf(layouts.getOrPut(it) { Layout(it) }) }
                    ?: emptyList()
            }
        override fun screenToLocal(screen: Offset) = geometry.coordinates(view).local(screen)
        override fun withinLayerBounds(local: Offset) = true // The wrapper has no source clip.
    }
    private val membership = view.observePointerMembership(::refresh)
    private val density get() = view.pointerEnvironment.textDensity.density

    init {
        try { refresh() }
        catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Called on source mount/branch changes and before DOWN; never from the panel animation. */
    fun refresh() {
        if (closed) return
        val next = view.pointerContent?.pointerList
        if (next !== listView) {
            val previous = listScroll
            listScroll = null
            listView = null
            previous?.close()
            listView = next
            listScroll = next?.let {
                NativeLibraryScrollOwner(it, view.pointerEnvironment, frames, scope, nested, parentInterested)
            }
        }
        val obsolete = layouts.keys.filterNot(::sourceAttached)
        obsolete.forEach { layouts.remove(it)?.retire() }
    }

    /** Main's preview-key phase preserves source PageUp/PageDown for a list descendant only. */
    fun pageKey(focused: View?, event: KeyEvent): Boolean {
        refresh()
        val list = listView ?: return false
        if (event.keyCode != KeyEvent.KEYCODE_PAGE_DOWN && event.keyCode != KeyEvent.KEYCODE_PAGE_UP) return false
        var ancestor = focused
        while (ancestor != null && ancestor !== list) ancestor = ancestor.parent as? View
        if (ancestor !== list) return false
        return listScroll?.pageKey(event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN,
            event.action == KeyEvent.ACTION_DOWN, event.isCtrlPressed) == true
    }

    private fun sourceAttached(child: View): Boolean {
        if (closed || !child.isAttachedToWindow) return false
        val content = view.pointerContent ?: return false
        var ancestor: View? = child
        while (ancestor != null) {
            if (ancestor.visibility != View.VISIBLE) return false
            if (ancestor === content) return true
            ancestor = ancestor.parent as? View
        }
        return false
    }

    private fun children(parent: View): List<NativeViewPointerHitLayout> {
        if (parent !is ViewGroup) return emptyList()
        val children = ArrayList<NativeViewPointerHitLayout>(parent.childCount)
        // Released Column/Row/LazyColumn do not assign sibling zIndex. Item transforms are taken
        // from each current matrix, not View.elevation or a second retained layout description.
        for (index in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(index)
            if (child.visibility == View.VISIBLE) children += layouts.getOrPut(child) { Layout(child) }
        }
        return children
    }

    private fun interested(child: View, change: NativeDragPointerChange): Boolean {
        var ancestor: View? = child
        while (ancestor != null && ancestor !== view) {
            if (ancestor === listView) return listScroll?.interested(change) == true || parentInterested(change)
            ancestor = ancestor.parent as? View
        }
        return parentInterested(change)
    }

    private inner class Layout(private val target: View) : NativeViewPointerHitLayout {
        private var retired = false
        private val click = (target as? NativeClickPhaseHost)?.let {
            NativeClickPhaseNode(target, it.nativeClickEndpoint) { change -> interested(target, change) }
        }
        private val clickNode = click?.let(::owned)
        private val shield = if (target is ReverbIncidentsContentView || target is ReverbGroupLayout) {
            owned(object : NativeViewPhasePointerNode {
                override val view get() = target
                override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
                override fun cancel() = Unit
            })
        } else null
        private var scrollOwner: NativeLibraryScrollOwner? = null
        private var scrollNodes = emptyList<NativeViewPhasePointerNode>()
        private val attachment = if (click != null || shield != null || target is ReverbLibraryRecyclerView) {
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    if (layouts[target] === this@Layout) layouts.remove(target)
                    retire()
                }
            }
        } else null

        init { attachment?.let(target::addOnAttachStateChangeListener) }

        private fun owned(node: NativeViewPhasePointerNode) = object : NativeViewPhasePointerNode by node {
            // A detached/rebound/removed source node must not revive a retained DOWN path when the
            // same physical row is subsequently reattached or the list branch is recreated.
            override val attached get() = !retired && sourceAttached(target) && node.attached
        }

        override val placed get() = !retired && sourceAttached(target) && target.isLaidOut
        override val size get() = Size(target.width.toFloat(), target.height.toFloat())
        override val minimumTouchTarget get() = Size(48f * density, 48f * density)
        override val childrenInHitOrder get() = children(target)
        override fun screenToLocal(screen: Offset) = geometry.coordinates(target).local(screen)
        override val nodes: List<NativeViewPhasePointerNode>
            get() {
                if (target === listView) {
                    val current = listScroll
                    if (scrollOwner !== current) {
                        scrollOwner = current
                        scrollNodes = current?.let { owner ->
                            listOf(owner.observerNode, owner.dragNode).map { delegate ->
                                owned(object : NativeViewPhasePointerNode by delegate {
                                    override val attached get() = listScroll === owner && delegate.attached
                                })
                            }
                        } ?: emptyList()
                    }
                    return scrollNodes
                }
                return clickNode?.let(::listOf) ?: shield?.let(::listOf) ?: emptyList()
            }
        override fun withinLayerBounds(local: Offset): Boolean = when {
            target is ReverbGroupLayout -> target.containsVisualPoint(local.x, local.y)
            target is NativePointerLayerShape -> target.containsPointerLayer(local.x, local.y)
            target === listView -> {
                val extra = (30f * density).roundToInt()
                local.x >= -extra && local.x < target.width + extra && local.y >= 0f && local.y < target.height
            }
            target is ReverbIncidentsContentView ->
                local.x >= 0f && local.x < target.width && local.y >= 0f && local.y < target.height
            else -> true // Unclipped structural Column/Row wrappers.
        }

        fun retire() {
            if (retired) return
            retired = true
            attachment?.let(target::removeOnAttachStateChangeListener)
            click?.cancel()
            scrollOwner = null
            scrollNodes = emptyList()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
        }
        cleanup { membership.close() }
        val scroll = listScroll
        listScroll = null
        listView = null
        cleanup { scroll?.close() }
        layouts.values.forEach { cleanup(it::retire) }
        layouts.clear()
        failure?.let { throw it }
    }
}
