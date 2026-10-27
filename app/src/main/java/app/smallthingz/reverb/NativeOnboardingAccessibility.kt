/*
 * Uncovered-node traversal adapted from AndroidX ui 1.11.2 SemanticsOwner.
 * Copyright 2020 The Android Open Source Project. Apache License 2.0.
 */
package app.smallthingz.reverb

import android.graphics.Rect
import android.graphics.Region
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import smallthingz.ui.geometry.Rect as ComposeRect
import smallthingz.ui.unit.roundToIntRect
import app.smallthingz.reverb.nativeui.*
import java.util.IdentityHashMap

/**
 * On-demand mapping of the actual three onboarding bodies and footer, not a retained semantic tree.
 * Source traversal visits siblings back-to-front, subtracting important nodes after their children.
 * A partly clipped merging row retains its original descendants in its UNCLIPPED local region.
 * Predictive hideFromAccessibility belongs to its vertical root only; alpha=0 hides descendants.
 */
internal class NativeOnboardingAccessibility(
    private val root: ViewGroup,
    private val pager: () -> NativeOnboardingPlacedPagerViewport,
    private val preview: () -> OnboardingPageContentView?,
    private val footer: OnboardingFooterView,
    private val externalOcclusion: () -> List<Rect> = { emptyList() },
    private val transformPolicy: NativeTextSemanticsTransformPolicy? = null,
) : NativeSourceAccessibilityNodeProvider {
    val geometry = NativePlainTextViewGeometry(root, NativeTextSemanticsClipPolicy(::clip),
        rootContentClip = { ComposeRect(0f, 0f, root.width.toFloat(), root.height.toFloat()) },
        transformPolicy = transformPolicy)
    private val unclipped = NativePlainTextViewGeometry(root,
        NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped }, transformPolicy = transformPolicy)

    private fun clip(view: View): NativeTextSemanticsClip = when {
        view === pager() || view is NativeVerticalViewport || view is SettingsExpansionView ||
            view.parent is ReverbGroupLayout || footer.clipsAccessibilityTextAt(view) ->
            NativeTextSemanticsClip.Rectangle(ComposeRect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
        else -> NativeTextSemanticsClip.Unclipped
    }

    fun node(view: View): NativeSourceAccessibilityNode =
        capture()[view] ?: NativeSourceAccessibilityNode.absent()

    override fun invoke(view: View): NativeSourceAccessibilityNode = node(view)

    override fun snapshotNodes(): Map<View, NativeSourceAccessibilityNode> = capture()

    fun children(view: View): List<View> {
        val captured = capture()
        return semanticChildren(view).filter { captured[it]?.present == true }
    }

    fun pagerNode(): NativeOnboardingPagerNode {
        val captured = capture()
        val facts = captured[pager()] ?: NativeSourceAccessibilityNode.absent()
        return NativeOnboardingPagerNode(facts.present, facts.hidden, facts.visibleToUser,
            semanticChildren(pager()).filter { captured[it]?.present == true })
    }

    private fun isSemantic(view: View): Boolean = view === pager() ||
        view is NativeVerticalViewport || view is NativeMergedRowAccessibilityHost ||
        view is ReverbParagraphView ||
        view is SilenceCurveView || view is SettingsCalibrationProgressView ||
        view.parent is CompressionSliderView ||
        (view is OnboardingIconView && !view.contentDescription.isNullOrEmpty())

    /** Existing row returns its exact marker/title/body/status order, excluding its fake role. */
    private fun semanticChildren(view: View): List<View> {
        if (view is NativeMergedRowAccessibilityHost)
            return view.mergedRowAccessibility.sourceOrderedChildren()
        val result = ArrayList<View>()
        fun visit(child: View) {
            if (child.visibility != View.VISIBLE ||
                child.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) return
            if (isSemantic(child)) result += child
            else if (child is ViewGroup) for (index in 0 until child.childCount) visit(child.getChildAt(index))
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        return result
    }

    private fun localBounds(view: View) = when (view) {
        is ReverbParagraphView -> view.originalAccessibilityBoundsInView()
        is SettingsCalibrationProgressView -> view.semanticsBoundsInView()
        else -> ComposeRect(0f, 0f, view.width.toFloat(), view.height.toFloat())
    }

    private fun hidden(view: View): Boolean {
        if (view === preview()?.sourceVerticalViewport) return true
        var current: View? = view
        while (current != null) {
            if (current.alpha <= 0f) return true
            if (current === root) break
            current = current.parent as? View
        }
        return false
    }

    private fun partialMergedRow(view: View): Boolean {
        if (view !is NativeMergedRowAccessibilityHost) return false
        var ancestor = view.parent as? View
        while (ancestor != null && ancestor !== root) {
            if (ancestor is NativeVerticalViewport || ancestor === pager()) {
                var bounds = boundsInAncestor(view, ancestor, localBounds(view)) ?: return false
                val shown = preview()
                if (shown != null && ancestor === shown.sourceVerticalViewport) {
                    // Source preview semantics precede graphicsLayer on the same layout. Its
                    // scroll-parent coordinates stay BEFORE the preview transform; descendants
                    // include that transform when localBoundingBoxOf(..., false) maps into it.
                    bounds = boundsInAncestor(ancestor, shown, bounds) ?: return false
                    bounds = nativeTextSemanticsMatrix(shown, transformPolicy).map(bounds)
                }
                return bounds != bounds.intersect(ComposeRect(0f, 0f, ancestor.width.toFloat(), ancestor.height.toFloat()))
            }
            ancestor = ancestor.parent as? View
        }
        return false
    }

    private fun boundsInAncestor(view: View, ancestor: View, initial: ComposeRect): ComposeRect? {
        var current = view
        var bounds = initial
        while (current !== ancestor) {
            val parent = current.parent as? View ?: return null
            bounds = nativeTextSemanticsMatrix(current, transformPolicy).map(bounds).translate(Offset(
                (current.left - parent.scrollX).toFloat(), (current.top - parent.scrollY).toFloat()))
            current = parent
        }
        return bounds
    }

    private fun touchBoundsInRoot(view: View): ComposeRect? {
        // AndroidComposeView's minimumTouchTargetSize is48dp. Every source merging row here has
        // OnClick even while disabled, so SemanticsModifierNode uses touchBoundsInRoot.
        val minimum = Size(48f * root.resources.displayMetrics.density,
            48f * root.resources.displayMetrics.density)
        var bounds = nativeOnboardingMinimumTouchBounds(
            Size(view.width.toFloat(), view.height.toFloat()), minimum)
        var current = view
        while (current !== root) {
            val parent = current.parent as? View ?: return null
            if (clip(current) is NativeTextSemanticsClip.Rectangle) {
                bounds = nativeOnboardingClipTouchBounds(bounds,
                    Size(current.width.toFloat(), current.height.toFloat()), minimum)
                if (bounds.isEmpty) return ComposeRect.Zero
            }
            bounds = nativeTextSemanticsMatrix(current, transformPolicy).map(bounds).translate(Offset(
                (current.left - parent.scrollX).toFloat(), (current.top - parent.scrollY).toFloat()))
            current = parent
        }
        // This View is also the source full-size Surface content layer. The native platform root
        // and that source layer share coordinates, but the source layer still clips touch bounds.
        return nativeOnboardingClipTouchBounds(bounds,
            Size(root.width.toFloat(), root.height.toFloat()), minimum)
    }

    private fun capture(): IdentityHashMap<View, NativeSourceAccessibilityNode> {
        val result = IdentityHashMap<View, NativeSourceAccessibilityNode>()
        if (!root.isAttachedToWindow || !root.isLaidOut) return result
        val uncovered = Region(0, 0, root.width, root.height)
        // Same-window siblings after Onboarding (the passive feedback Text) are visited first.
        // Only source-important, non-hidden semantic bounds subtract; the card background does not.
        externalOcclusion().forEach { uncovered.op(it, Region.Op.DIFFERENCE) }

        fun visit(view: View, remaining: Region, retainOffscreenDescendants: Boolean) {
            if (!view.isAttachedToWindow || view.visibility != View.VISIBLE || remaining.isEmpty) return
            val mapped = geometry.capture(view, Offset.Zero, localBounds(view)) ?: return
            val full = unclipped.capture(view, Offset.Zero, localBounds(view)) ?: return
            // Pinned addDescendantsOfPartiallyOffscreenNode uses unclipped bounds only when the
            // clipped child is wholly empty, retaining its Text/cursor but visibleToUser=false.
            val clippedTouch = if (view is NativeMergedRowAccessibilityHost)
                touchBoundsInRoot(view) ?: return else mapped.visibleBoundsInRoot
            val touch = if (retainOffscreenDescendants && clippedTouch.isEmpty)
                full.visibleBoundsInRoot else clippedTouch
            val rounded = touch.roundToIntRect()
            val bounds = Rect(rounded.left, rounded.top, rounded.right, rounded.bottom)
            val available = Region(bounds)
            if (!available.op(remaining, Region.Op.INTERSECT)) return
            val adjusted = available.bounds
            val adjustedGeometry = PlainTextAccessibilityGeometry(mapped.paragraphPositionInRoot,
                ComposeRect(adjusted.left.toFloat(), adjusted.top.toFloat(),
                    adjusted.right.toFloat(), adjusted.bottom.toFloat()), mapped.rootToScreen)
            val ownHidden = hidden(view)
            result[view] = NativeSourceAccessibilityNode(true, ownHidden,
                !ownHidden && !mapped.visibleBoundsInRoot.isEmpty,
                nativeMergedRoleBoundsInScreen(adjustedGeometry, true))
            val children = semanticChildren(view)
            if (!retainOffscreenDescendants && partialMergedRow(view)) {
                val raw = full.visibleBoundsInRoot.roundToIntRect()
                val childSpace = Region(raw.left, raw.top, raw.right, raw.bottom)
                for (index in children.indices.reversed()) visit(children[index], childSpace, true)
            } else {
                for (index in children.indices.reversed()) visit(children[index], remaining, retainOffscreenDescendants)
            }
            // Every known semantic node here has Text, ContentDescription, a scroll range, a
            // CollectionInfo, or merges descendants. Layout-only ancestors never subtract pixels.
            if (!ownHidden) remaining.op(bounds, Region.Op.DIFFERENCE)
        }
        val children = semanticChildren(root)
        for (index in children.indices.reversed()) visit(children[index], uncovered, false)
        return result
    }
}
