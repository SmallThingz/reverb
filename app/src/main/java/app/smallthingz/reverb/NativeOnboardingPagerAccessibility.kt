/*
 * Accessibility mapping adapted from AndroidX ui/foundation 1.11.2.
 * Copyright 2020-2023 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.MainThread
import app.smallthingz.reverb.nativeui.NativePlainTextViewGeometry
import app.smallthingz.reverb.nativeui.nativeMergedRoleBoundsInScreen
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** Values of the existing, placed source semantics node; hidden and absent are NOT synonyms. */
internal data class NativeOnboardingPagerNode(
    val present: Boolean,
    val hidden: Boolean,
    val visibleToUser: Boolean,
    /** Actual semantic viewport children, in source drawing order, after source occlusion pruning. */
    val children: List<View>,
)

internal typealias NativeHorizontalAccessibilityRange = NativeAccessibilityScrollRange

/**
 * Mandatory WINDOW-owned event boundary. Do not implement this as a per-pager timer or stock
 * View.onScrollChanged delivery: ui1.11.2 sends subtree then scrolled events in batches separated
 * by 100ms, shared with all vertical scrollers. Reads must happen at dispatch, not at scheduling.
 *
 * The window owner must retire a closed registration (including queued callbacks), preserve the
 * source isEnabled/attached admission, and send className=android.view.View / enabled=true with
 * only scrollX and maxScrollX from this range, no invented item indexes or scroll deltas.
 * Native accessibility focus bounds invalidation and semantic structure diffing stay window-owned.
 */
internal fun interface NativeWindowHorizontalAccessibilityEvents {
    fun register(
        source: View,
        sourcePresent: () -> Boolean,
        readRange: () -> NativeHorizontalAccessibilityRange,
    ): NativeHorizontalAccessibilityEventRegistration
}
internal interface NativeHorizontalAccessibilityEventRegistration : AutoCloseable {
    fun scrollChanged()
    fun layoutChanged()
}

/**
 * Direct delegate for the released fixed-three-page horizontal pager, not another renderer/tree.
 * The host installs this delegate, calls sourceStateChanged after its ONE pager changes, and closes
 * it when that host is disposed. Its actual View.createAccessibilityNodeInfo must call
 * isNodePresent before super; AccessibilityDelegate has no createAccessibilityNodeInfo hook.
 *
 * The host retains exact source page placement, clipped/occluded semantics membership and focus
 * bring-into-view. In particular it must not expose all retained native pages, blanket-delete a
 * hideFromAccessibility predictive preview, or block keyboard descendants as a visibility hack.
 * These policies cannot be inferred from the native isShown flag.
 *
 * Direct accepted a11y page actions use the same pager and scope as the source rememberCoroutineScope;
 * they intentionally do not acquire the footer navigation transaction/pending gate.
 */
@MainThread
internal class NativeOnboardingPagerAccessibility(
    private val host: ViewGroup,
    private val pager: NativeOnboardingPager,
    private val scope: CoroutineScope,
    private val geometry: NativePlainTextViewGeometry,
    private val userScrollEnabled: () -> Boolean,
    private val sourceNode: () -> NativeOnboardingPagerNode,
    windowEvents: NativeWindowHorizontalAccessibilityEvents,
) : View.AccessibilityDelegate(), AutoCloseable {
    private var closed = false
    private var previousOffset: Float? = null
    private val events = windowEvents.register(host, ::isNodePresent, ::readRange)

    fun isNodePresent(): Boolean = !closed && host.isAttachedToWindow && sourceNode().present

    /** Source first observation seeds oldXValue; it does not synthesize an initial scroll event. */
    fun sourceStateChanged() {
        if (closed) return
        val current = readOffset()
        val previous = previousOffset
        previousOffset = current
        if (previous != null && current != previous && isNodePresent()) events.scrollChanged()
    }

    /** Host invokes after actual viewport geometry/max-range changes, including max-only layout. */
    fun sourceLayoutChanged() {
        if (!closed) events.layoutChanged()
    }

    private fun readRange(): NativeHorizontalAccessibilityRange =
        NativeHorizontalAccessibilityRange(
            readOffset(),
            ((pager.pageCount - 1).toLong() * pager.pageWidthPx).toFloat(),
        )

    private fun readOffset(): Float = (pager.currentPage.toLong() * pager.pageWidthPx +
        (pager.currentPageOffsetFraction * pager.pageWidthPx).roundToLong()).toFloat()

    override fun onInitializeAccessibilityNodeInfo(view: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(view, info)
        val node = sourceNode()
        val scroll = readRange()
        val enabled = userScrollEnabled()
        info.className = "android.view.View" // CollectionInfo suppresses HorizontalScrollView class.
        info.text = null
        info.contentDescription = null
        info.isEnabled = true // Removing user-scroll semantics is NOT Disabled semantics.
        info.isClickable = false
        info.isLongClickable = false
        info.isFocusable = false // Source scrollable Focusability.Never has no Focused property.
        info.isFocused = false
        info.isCheckable = false
        info.isChecked = false
        info.isSelected = false
        info.isImportantForAccessibility = !node.hidden
        info.isVisibleToUser = node.visibleToUser && node.present
        info.isScreenReaderFocusable = false
        info.isScrollable = enabled && scroll.maximum > 0f
        info.maxTextLength = -1
        info.movementGranularities = 0
        info.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(
            1, pager.pageCount, false, AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_NONE)
        info.collectionItemInfo = null
        val mapped = geometry.capture(host, Offset.Zero,
            ComposeRect(0f, 0f, host.width.toFloat(), host.height.toFloat()))
        info.setBoundsInScreen(mapped?.let { nativeMergedRoleBoundsInScreen(it, host.isLaidOut) } ?: Rect())
        info.actionList.toList().forEach(info::removeAction)
        info.addAction(if (host.isAccessibilityFocused)
            AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        if (enabled) {
            if (scroll.value < scroll.maximum) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                info.addAction(if (pager.rtl) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
                    else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT)
            }
            if (scroll.value > 0f) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                info.addAction(if (pager.rtl) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
                    else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT)
            }
            if (Build.VERSION.SDK_INT >= 29) {
                // PageLeft/Right remain advertised at an edge; their source callback returns false.
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT)
            }
        }
        // The host provides actual semantics children. No joined labels and no page clones.
        for (index in 0 until host.childCount) info.removeChild(host.getChildAt(index))
        node.children.forEach(info::removeChild)
        node.children.forEach(info::addChild)
    }

    override fun performAccessibilityAction(view: View, action: Int, args: Bundle?): Boolean {
        if (!isNodePresent()) return false
        if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS ||
            action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
            return super.performAccessibilityAction(view, action, args)
        }
        if (!userScrollEnabled()) return false
        val forward = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> true
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> false
            android.R.id.accessibilityActionScrollRight -> !pager.rtl
            android.R.id.accessibilityActionScrollLeft -> pager.rtl
            // Explicit Page actions are LOGICAL; RTL reversal applies only physical Scroll actions.
            android.R.id.accessibilityActionPageRight -> if (Build.VERSION.SDK_INT >= 29) true else return false
            android.R.id.accessibilityActionPageLeft -> if (Build.VERSION.SDK_INT >= 29) false else return false
            else -> return false // No stock selection, keyboard-focus, show-on-screen, or scroll-to-index.
        }
        if (forward && !pager.canScrollForward || !forward && !pager.canScrollBackward) return false
        scope.launch {
            // Read currentPage inside the scheduled coroutine, as released pagerSemantics does.
            val target = pager.currentPage + if (forward) 1 else -1
            if (target in 0 until pager.pageCount) pager.animateScrollToPage(target)
        }
        return true
    }

    override fun onInitializeAccessibilityEvent(view: View, event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(view, event)
        event.className = "android.view.View"
        event.isEnabled = true
        event.isScrollable = false // Source generic createEvent does not add this payload.
        event.contentDescription = null
        event.text.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        events.close()
    }
}
