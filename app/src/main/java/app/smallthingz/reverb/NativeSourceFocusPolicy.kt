package app.smallthingz.reverb

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import app.smallthingz.nativeui.focus.FocusDirection
import app.smallthingz.nativeui.geometry.Rect as FocusRect

/**
 * Window-owner focus policy for Views which replace the released Compose focus targets.
 * Traversal uses the current placed Views only when a focus request occurs.
 */
internal class NativeSourceFocusPolicy(
    private val root: ViewGroup,
    private val takeOwnerFocus: () -> Boolean,
    private val clearOwnerFocus: () -> Unit,
) : AutoCloseable, View.OnAttachStateChangeListener {
    private val state = NativeSourceFocusState<View>()
    private val matrixLocation = IntArray(2)
    private var observer: ViewTreeObserver? = null
    private var invalidationLayoutRequested = false
    private var parking = false
    private var closed = false
    private val globalFocus = ViewTreeObserver.OnGlobalFocusChangeListener { _, next ->
        when {
            parking && next === root -> Unit
            next != null && next !== root && belongs(next) -> {
                state.focused(next)
                retireScheduledInvalidation()
            }
            next === root && state.pendingOwnerInvalidation -> Unit
            else -> { state.clear(); retireScheduledInvalidation() }
        }
    }
    /** Called by the owner before measuring any child, while old placement is still intact. */
    fun beforeMeasure() {
        if (!state.pendingOwnerInvalidation) return
        val restore = !closed && root.isAttachedToWindow && root.isFocused
        retireScheduledInvalidation()
        // Retire before clearing: View.clearFocus can synchronously re-enter requestFocus.
        state.retireInvalidation()
        if (restore) clearOwnerFocus()
    }

    init {
        root.addOnAttachStateChangeListener(this)
        if (root.isAttachedToWindow) onViewAttachedToWindow(root)
    }

    val hasPendingInvalidation: Boolean get() = state.pendingOwnerInvalidation

    /** Only the original AndroidView callsites retain framework availability notifications. */
    fun reportsAvailability(view: View): Boolean =
        !closed && nativeReportsSourceFocusAvailability(retainedAndroidView(view) != null)

    fun childFocused(focused: View) {
        if (!closed && focused !== root) {
            state.focused(focused)
            retireScheduledInvalidation()
        }
    }

    /** Called after the root's super.clearChildFocus has cleared its direct-child pointer. */
    fun childCleared(child: View) {
        if (closed) return
        val old = state.active ?: return
        if (root.hasFocus()) return // Another real target already replaced it.
        // A removal has already removed its subtree from the parent's array before this callback.
        // A retained AndroidView disabled in place still has an attached wrapper and source rect.
        state.childCleared(retainedAndroidView(old) != null && belongs(old) && old.isAttachedToWindow)
        if (state.pendingOwnerInvalidation) scheduleInvalidation()
    }

    fun requestFocus(direction: Int, previousRect: Rect?): Boolean {
        if (closed) return false
        return nativeRequestSourceOwnerFocus(
            state = state,
            ownerFocused = root.isFocused,
            activeIsMigrated = { it.isFocused && belongs(it) && retainedAndroidView(it) == null },
            direction = direction,
            previousRect = previousRect,
            hasFocus = root.hasFocus() || state.active != null,
            isOneDimensional = { it == View.FOCUS_FORWARD || it == View.FOCUS_BACKWARD },
            parkOwner = {
                parking = true
                try { takeOwnerFocus() } finally { parking = false }
            },
            search = ::search,
            reset = { state.clear(); search(it, null, null) },
        )
    }

    private fun scheduleInvalidation() {
        if (invalidationLayoutRequested || !root.isAttachedToWindow) return
        invalidationLayoutRequested = true
        // A source owner invalidation runs after changes and before placement. Request one
        // owner measure even when this mutation did not otherwise require layout.
        root.requestLayout()
    }

    private fun retireScheduledInvalidation() {
        invalidationLayoutRequested = false
    }

    private fun search(direction: Int, previousRect: Rect?, active: View?): Boolean {
        val all = ArrayList<View>()
        fun collect(group: ViewGroup) {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child.visibility != View.VISIBLE || !child.isAttachedToWindow) continue
                all += child
                if (child is ViewGroup) collect(child)
            }
        }
        collect(root)
        val candidates = all.filter(::canFocus)
        if (direction == View.FOCUS_FORWARD || direction == View.FOCUS_BACKWARD) {
            val order = if (direction == View.FOCUS_FORWARD) all else all.asReversed()
            val activeIndex = active?.let(order::indexOf) ?: -1
            for (index in activeIndex + 1 until order.size) {
                val candidate = order[index]
                if (candidate in candidates && eligibleForSearch(candidate) && candidate !== active &&
                    requestTarget(candidate, direction)) return true
            }
            return false
        }
        val focusDirection = when (direction) {
            View.FOCUS_LEFT -> FocusDirection.Left
            View.FOCUS_RIGHT -> FocusDirection.Right
            View.FOCUS_UP -> FocusDirection.Up
            View.FOCUS_DOWN -> FocusDirection.Down
            else -> return false
        }
        val source = previousRect?.let { FocusRect(it.left.toFloat(), it.top.toFloat(),
            it.right.toFloat(), it.bottom.toFloat()) } ?: active?.takeIf(::belongs)?.let(::bounds)
        if (source == null) {
            val corner = if (direction == View.FOCUS_LEFT || direction == View.FOCUS_UP)
                FocusRect(root.width.toFloat(), root.height.toFloat(), root.width.toFloat(), root.height.toFloat())
            else FocusRect.Zero
            val initial = accessibleChildren(root, candidates)
            // Source initial entry counts all focusable children before filtering placement.
            if (initial.size <= 1) return initial.firstOrNull()?.let { requestTarget(it, direction) } ?: false
            return requestBest(initial, corner, focusDirection, direction)
        }
        var parent = active?.parent as? ViewGroup
        while (parent != null && parent !== root && !canFocus(parent)) parent = parent.parent as? ViewGroup
        var group = parent ?: root
        while (true) {
            if (requestBest(accessibleChildren(group, candidates).filter { it !== active },
                    source, focusDirection, direction)) return true
            if (group === root) return false
            var ancestor = group.parent as? ViewGroup
            while (ancestor != null && ancestor !== root && !canFocus(ancestor))
                ancestor = ancestor.parent as? ViewGroup
            group = ancestor ?: root
        }
    }

    private fun accessibleChildren(group: ViewGroup, candidates: List<View>): List<View> {
        val result = ArrayList<View>()
        fun visit(parent: ViewGroup) {
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                if (child in candidates) result += child
                else if (child is ViewGroup && child.visibility == View.VISIBLE) visit(child)
            }
        }
        visit(group)
        return result
    }

    private fun requestBest(candidates: List<View>, origin: FocusRect,
        direction: FocusDirection, androidDirection: Int): Boolean {
        val candidate = nativeBestDirectionalFocusCandidate(candidates.filter(::eligibleForSearch), origin, direction, ::bounds)
            ?: return false
        return requestTarget(candidate, androidDirection)
    }

    private fun requestTarget(candidate: View, direction: Int): Boolean {
        // FocusGroupNode supplies the prior actual target rect in the embedded View's coordinates.
        val previous = state.active?.let(::bounds)
        val local = if (retainedAndroidView(candidate) != null && previous != null) {
            val ownerOrigin = IntArray(2).also(root::getLocationOnScreen)
            val targetOrigin = IntArray(2).also(candidate::getLocationOnScreen)
            Rect(previous.left.toInt() + ownerOrigin[0] - targetOrigin[0],
                previous.top.toInt() + ownerOrigin[1] - targetOrigin[1],
                previous.right.toInt() + ownerOrigin[0] - targetOrigin[0],
                previous.bottom.toInt() + ownerOrigin[1] - targetOrigin[1])
        } else null
        return candidate.requestFocus(direction, local)
    }

    private fun canFocus(view: View): Boolean =
        view !== root && view.isAttachedToWindow && view.visibility == View.VISIBLE &&
            view.isEnabled && view.isFocusable && (!root.isInTouchMode || view.isFocusableInTouchMode)

    private fun eligibleForSearch(view: View): Boolean = view.isAttachedToWindow && view.isLaidOut

    private fun belongs(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            val parent = current.parent as? ViewGroup ?: return false
            if (parent.indexOfChild(current) < 0) return false
            current = parent
        }
        return false
    }

    private fun retainedAndroidView(view: View): View? {
        var current: View? = view
        while (current != null && current !== root) {
            if (current is RangeDurationWheelView || current is SilenceCurveView || current is AudioBlobView)
                return current
            current = current.parent as? View
        }
        return null
    }

    private fun bounds(view: View): FocusRect? {
        if (!belongs(view) || !view.isLaidOut) return null
        val matrix = Matrix()
        val ownerMatrix = Matrix()
        if (Build.VERSION.SDK_INT >= 29) {
            view.transformMatrixToGlobal(matrix)
            root.transformMatrixToGlobal(ownerMatrix)
        } else {
            // SDK28 uses the same pinned fallback as pointer coordinates, including scroll order.
            matrix.set(nativeViewMatrixToWindow(view, matrixLocation))
            ownerMatrix.set(nativeViewMatrixToWindow(root, matrixLocation))
        }
        val inverse = Matrix()
        if (!ownerMatrix.invert(inverse)) return null
        matrix.postConcat(inverse)
        val rect = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        matrix.mapRect(rect)
        return FocusRect(rect.left, rect.top, rect.right, rect.bottom)
    }

    override fun onViewAttachedToWindow(view: View) {
        if (closed || observer != null) return
        observer = root.viewTreeObserver.also { it.addOnGlobalFocusChangeListener(globalFocus) }
        root.findFocus()?.takeIf { it !== root }?.let(state::focused)
    }
    override fun onViewDetachedFromWindow(view: View) {
        retireScheduledInvalidation()
        observer?.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(globalFocus)
        observer = null
        state.clear()
    }
    override fun close() {
        if (closed) return
        closed = true
        onViewDetachedFromWindow(root)
        root.removeOnAttachStateChangeListener(this)
    }
}
