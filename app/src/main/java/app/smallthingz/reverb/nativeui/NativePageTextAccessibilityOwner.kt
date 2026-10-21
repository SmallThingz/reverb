package app.smallthingz.reverb.nativeui

import android.view.View
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.compose.ui.geometry.Rect
import java.util.IdentityHashMap

/**
 * One direct owner per native window; no semantic node/state tree and no listeners/frame work.
 * Keep this object across page replacement and configuration/style rebinding within that window.
 * A new native Window gets a new owner, exactly as a new AndroidComposeView gets a new cursor.
 */
@MainThread
internal class NativeWindowTextAccessibilityOwner(
    private val windowRoot: View,
    private val hostClips: NativeTextSemanticsClipPolicy,
) {
    private val cursor = PlainTextAccessibilityWindowScope(windowRoot)

    /**
     * The page owner calls refresh after an actual row-membership change, not on every frame.
     * sourceExposure must describe source hideFromAccessibility/transparent or excluded pager
     * content; it must NOT hide Text simply because an ancestor is clickable/merges descendants.
     * The ordinary native hierarchy still owns node visibility and current accessibility focus.
     *
     * sourceScrollViewport is the actual native counterpart of OnboardingPage.verticalScroll.
     * The native owner supplies this reference; a ScrollView class-name search is not provenance.
     */
    fun bindPage(
        page: OnboardingPageContentView,
        sourceScrollViewport: View,
        sourceExposure: (View) -> Boolean,
    ): NativePageTextAccessibilityOwner {
        require(sourceScrollViewport.isWithin(page)) { "Source viewport must belong to this page" }
        return NativePageTextAccessibilityOwner(
        page, cursor,
        NativePlainTextViewGeometry(windowRoot, NativeTextSemanticsClipPolicy { view ->
            when {
                view === sourceScrollViewport ->
                    NativeTextSemanticsClip.Rectangle(Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
                // These exact native ancestors implement Surface graphicsLayer(clip=true).
                // Source rectInParent clips semantics to the rectangular layer extent even when
                // drawing uses a rounded outline. Keep the clip at the actual content ancestor.
                view.isWithin(page) && view.parent is ReverbGroupLayout &&
                    (view.parent as ReverbGroupLayout).getChildAt(0) === view ->
                    NativeTextSemanticsClip.Rectangle(Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
                view.isWithin(page) -> NativeTextSemanticsClip.Unclipped
                else -> hostClips.localClip(view)
            }
        }),
        sourceExposure,
        ).also { it.refresh() }
    }
}

/**
 * Only owns existing label/row/status binding handles. The Views retain their semantics identities;
 * this map contains no copied text/layout/state, observers or tasks.
 * Detach does NOT close this owner: pager reattachment must preserve the label's semantic identity.
 * Explicit disposal or native window replacement closes it. Old close handles are token guarded by
 * ReverbParagraphView and cannot remove a subsequently installed binding.
 */
@MainThread
internal class NativePageTextAccessibilityOwner internal constructor(
    private val page: ViewGroup,
    private val cursor: PlainTextAccessibilityWindowScope,
    private val geometry: NativePlainTextViewGeometry,
    private val sourceExposure: (View) -> Boolean,
) : AutoCloseable {
    private val leases = IdentityHashMap<View, AutoCloseable>()
    private var closed = false

    fun refresh() {
        check(!closed) { "A disposed page cannot regain native text ownership" }
        val present = IdentityHashMap<View, Boolean>()
        fun visit(view: View) {
            if (view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) return
            if (view is ReverbParagraphView || view is NativeMergedRowAccessibilityHost ||
                (view is OnboardingIconView && !view.contentDescription.isNullOrEmpty())) present[view] = true
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(page)
        val retired = leases.keys.filter { !present.containsKey(it) }
        retired.forEach { leases.remove(it)?.close() }
        present.keys.forEach { view ->
            if (view is ReverbParagraphView) {
                // SemanticsNode.isUnmergedLeafNode excludes a leaf with a merging ancestor, but
                // the source still exposes that leaf and all of its cursor/character-box actions.
                view.isScreenReaderFocusable = !view.hasMergingRowAncestor(page)
            }
            if (!leases.containsKey(view)) leases[view] = when (view) {
                is ReverbParagraphView -> view.bindPlainTextAccessibility(cursor, geometry) { exposed(view) }
                is NativeMergedRowAccessibilityHost -> view.mergedRowAccessibility.bindGeometry(geometry) { exposed(view) }
                is OnboardingIconView -> view.bindDescriptionAccessibility(cursor) { exposed(view) }
                else -> error("Unexpected page accessibility binding")
            }
        }
    }

    private fun exposed(view: View): Boolean = !closed && view.isAttachedToWindow && view.isShown &&
        view.isWithin(page) && sourceExposure(view)

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        val owned = leases.values.toList()
        leases.clear()
        owned.forEach { lease ->
            try { lease.close() }
            catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}

private fun View.hasMergingRowAncestor(page: View): Boolean {
    var ancestor = parent as? View
    while (ancestor != null) {
        if (ancestor is NativeMergedRowAccessibilityHost) return true
        if (ancestor === page) return false
        ancestor = ancestor.parent as? View
    }
    return false
}

private fun View.isWithin(ancestor: View): Boolean {
    var current: View? = this
    while (current != null) {
        if (current === ancestor) return true
        current = current.parent as? View
    }
    return false
}
