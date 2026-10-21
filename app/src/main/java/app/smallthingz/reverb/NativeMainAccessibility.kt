/*
 * Uncovered-node traversal adapted from AndroidX UI 1.11.2 SemanticsOwner.
 * Copyright 2020 The Android Open Source Project. Apache License 2.0.
 */
package app.smallthingz.reverb

import android.graphics.Rect
import android.graphics.Region
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.setFrom
import androidx.recyclerview.widget.RecyclerView
import app.smallthingz.reverb.nativeui.*
import java.util.IdentityHashMap

/**
 * Finite Main-screen source mapping over the actual placed Views. The caller supplies the released
 * layer order and only its explicit own-node hideFromAccessibility flags. No retained semantic tree,
 * replacement text cursor, alpha-based subtree removal or layout reconstruction is introduced.
 */
internal class NativeMainAccessibility(
    private val windowRoot: View,
    private val root: ViewGroup,
    private val layersInDrawOrder: () -> List<View>,
    private val ownNodeHidden: (View) -> Boolean,
    private val clip: (View) -> NativeTextSemanticsClip,
    private val minimumTouch: (View) -> Float,
) {
    val geometry = NativePlainTextViewGeometry(windowRoot, NativeTextSemanticsClipPolicy(clip))
    private val unclipped = NativePlainTextViewGeometry(windowRoot,
        NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped })

    /** Every node uses its OWN source minimum, independently of which node is queried. */
    fun node(view: View): NativeSourceAccessibilityNode =
        capture()[view] ?: NativeSourceAccessibilityNode.absent()

    private fun semantic(view: View, layers: List<View>): Boolean {
        if (view is NativeMergedRowAccessibilityHost) return view.hasAccessibilitySemanticNode
        return view in layers || view is ReverbParagraphView || view is NativeClickPhaseHost ||
            view is NativeRangeEndpointView || view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES ||
            view is NativeVerticalViewport || view is RecyclerView ||
            view.isScreenReaderFocusable || !view.contentDescription.isNullOrEmpty()
    }

    private fun children(view: View, layers: List<View>): List<View> {
        if (view === root) return layers
        if (view is NativeMergedRowAccessibilityHost)
            return view.mergedRowAccessibility.sourceOrderedChildren()
        val result = ArrayList<View>()
        fun collect(child: View) {
            if (child.visibility != View.VISIBLE) return
            if (semantic(child, layers)) result += child
            else if (child is ViewGroup) for (index in 0 until child.childCount) collect(child.getChildAt(index))
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        return result
    }

    private fun bounds(view: View, minimumTouch: Float): ComposeRect {
        val base = if (view is ReverbParagraphView) view.originalAccessibilityBoundsInView()
            else ComposeRect(0f, 0f, view.width.toFloat(), view.height.toFloat())
        if (minimumTouch <= 0f) return base
        val dx = ((minimumTouch - base.width) / 2f).coerceAtLeast(0f)
        val dy = ((minimumTouch - base.height) / 2f).coerceAtLeast(0f)
        return ComposeRect(base.left - dx, base.top - dy, base.right + dx, base.bottom + dy)
    }

    /** NodeCoordinator975–1002/1302–1337: click touch bounds use the minimum-aware clip at
     * EACH clipping layer before its float transform. Ordinary text bounds use geometry.capture. */
    private fun touchGeometry(view: View, local: ComposeRect, minimum: Float): PlainTextAccessibilityGeometry? {
        val plain = unclipped.capture(view, Offset.Zero, local) ?: return null
        var bounds = local
        var current = view
        while (current !== windowRoot) {
            val parent = current.parent as? View ?: return null
            val sourceClip = clip(current)
            if (sourceClip is NativeTextSemanticsClip.Rectangle) {
                bounds = nativeMinimumTouchClip(bounds, sourceClip.bounds, minimum)
                if (bounds.isEmpty) break
            }
            bounds = Matrix().apply { setFrom(current.matrix) }.map(bounds).translate(Offset(
                current.left.toFloat() - parent.scrollX, current.top.toFloat() - parent.scrollY))
            if (bounds.isEmpty) break
            current = parent
        }
        return PlainTextAccessibilityGeometry(plain.paragraphPositionInRoot,
            if (bounds.isEmpty) ComposeRect.Zero else bounds, plain.rootToScreen)
    }

    /** SemanticsOwner339–363: only the nearest source scroll parent enables this rule. */
    private fun partiallyOutsideScrollParent(view: View): Boolean {
        var parent = view.parent as? View
        while (parent != null && parent !== windowRoot) {
            if (parent is NativeVerticalViewport || parent is RecyclerView) {
                val mapped = NativePlainTextViewGeometry(parent,
                    NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped }).capture(view,
                    Offset.Zero, ComposeRect(0f, 0f, view.width.toFloat(), view.height.toFloat())) ?: return false
                val bounds = mapped.visibleBoundsInRoot
                return bounds != bounds.intersect(ComposeRect(0f, 0f, parent.width.toFloat(), parent.height.toFloat()))
            }
            parent = parent.parent as? View
        }
        return false
    }

    private fun hidden(view: View): Boolean {
        if (ownNodeHidden(view)) return true
        var current: View? = view
        while (current != null) {
            if (current.alpha <= 0f) return true
            if (current === windowRoot) break
            current = current.parent as? View
        }
        return false
    }

    private fun important(view: View): Boolean {
        if (view is NativeMergedRowAccessibilityHost) return view.hasAccessibilitySemanticNode
        return view is ReverbParagraphView || view is NativeClickPhaseHost ||
            view is NativeRangeEndpointView || view is android.widget.TextView ||
            view is NativeVerticalViewport || view is RecyclerView ||
            view.isScreenReaderFocusable || !view.contentDescription.isNullOrEmpty()
    }

    private fun capture(): IdentityHashMap<View, NativeSourceAccessibilityNode> {
        val result = IdentityHashMap<View, NativeSourceAccessibilityNode>()
        if (!root.isAttachedToWindow || !root.isLaidOut) return result
        val layers = layersInDrawOrder()
        val rootGeometry = geometry.capture(root, Offset.Zero,
            ComposeRect(0f, 0f, root.width.toFloat(), root.height.toFloat())) ?: return result
        val rootBounds = rootGeometry.visibleBoundsInRoot.roundToIntRect()
        val uncovered = Region(rootBounds.left, rootBounds.top, rootBounds.right, rootBounds.bottom)

        fun visit(view: View, remaining: Region, retainOffscreen: Boolean) {
            if (!view.isAttachedToWindow || view.visibility != View.VISIBLE || remaining.isEmpty) return
            val minimum = minimumTouch(view)
            val local = bounds(view, minimum)
            val mapped = if (minimum > 0f) touchGeometry(view, local, minimum)
                else geometry.capture(view, Offset.Zero, local)
            if (mapped == null) return
            // effectiveBoundsInRoot(useMinimumTouchTarget=true) uses touchBoundsInRoot for BOTH
            // clipped and unclipped calls. Never substitute a fully unclipped click rectangle.
            val full = if (minimum > 0f) mapped else unclipped.capture(view, Offset.Zero, local) ?: return
            val touch = if (retainOffscreen && mapped.visibleBoundsInRoot.isEmpty)
                full.visibleBoundsInRoot else mapped.visibleBoundsInRoot
            val rounded = touch.roundToIntRect()
            val rawBounds = Rect(rounded.left, rounded.top, rounded.right, rounded.bottom)
            val available = Region(rawBounds)
            if (!available.op(remaining, Region.Op.INTERSECT)) return
            val adjusted = available.bounds
            val adjustedGeometry = PlainTextAccessibilityGeometry(mapped.paragraphPositionInRoot,
                ComposeRect(adjusted.left.toFloat(), adjusted.top.toFloat(), adjusted.right.toFloat(),
                    adjusted.bottom.toFloat()), mapped.rootToScreen)
            val ownHidden = hidden(view)
            result[view] = NativeSourceAccessibilityNode(true, ownHidden,
                !ownHidden && !mapped.visibleBoundsInRoot.isEmpty,
                nativeMergedRoleBoundsInScreen(adjustedGeometry, true))
            val descendants = children(view, layers)
            val partialMerge = ((view is NativeMergedRowAccessibilityHost && view.mergesAccessibilityDescendants) ||
                (view is NativeClickPhaseHost && view !is NativeMergedRowAccessibilityHost)) &&
                partiallyOutsideScrollParent(view)
            if (!retainOffscreen && partialMerge) {
                val fullBounds = full.visibleBoundsInRoot.roundToIntRect()
                val childSpace = Region(fullBounds.left, fullBounds.top, fullBounds.right, fullBounds.bottom)
                for (index in descendants.indices.reversed()) visit(descendants[index], childSpace, true)
            } else {
                for (index in descendants.indices.reversed()) visit(descendants[index], remaining, retainOffscreen)
            }
            if (!ownHidden && important(view)) remaining.op(rawBounds, Region.Op.DIFFERENCE)
        }
        for (layer in layers.asReversed()) visit(layer, uncovered, false)
        return result
    }
}

/** Exact pinned calculateMinimumTouchTargetOffset + rectInParent intersection, in layer coordinates. */
internal fun nativeMinimumTouchClip(bounds: ComposeRect, layer: ComposeRect, minimum: Float): ComposeRect {
    val child = bounds.translate(Offset(-layer.left, -layer.top))
    val outside = child.right < 0f || child.left > layer.width || child.bottom < 0f || child.top > layer.height
    val underWidth = (minimum - child.width) / 2f
    val underHeight = (minimum - child.height) / 2f
    val left = if (outside) 0f else if (underWidth > 0f) child.left - underWidth else child.left.coerceAtLeast(-minimum / 2f)
    val top = if (outside) 0f else if (underHeight > 0f) child.top - underHeight else child.top.coerceAtLeast(-minimum / 2f)
    val right = minOf(layer.width + minimum, maxOf(layer.width, left + minimum))
    val bottom = minOf(layer.height + minimum, maxOf(layer.height, top + minimum))
    val clipped = child.intersect(ComposeRect(left, top, right, bottom))
    return if (clipped.isEmpty) ComposeRect.Zero else clipped.translate(Offset(layer.left, layer.top))
}
