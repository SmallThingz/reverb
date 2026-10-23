/*
 * Source: ui1.11.2 OneDimensionalFocusSearch; foundation1.11.2
 * LazyLayoutBeyondBoundsProviderModifierNode/PagerBeyondBoundsState.
 * Copyright 2021-2023 The Android Open Source Project. Apache License 2.0.
 */
package app.smallthingz.reverb

import android.view.View
import smallthingz.ui.focus.FocusDirection
import smallthingz.ui.geometry.Rect

/** Fill pages, zero beyondViewportPageCount: source lays out at most two extra items per search. */
internal fun nativePagerBeyondBoundsIndices(
    firstPlaced: Int, lastPlaced: Int, forward: Boolean, enabled: Boolean, pageCount: Int = 3,
): List<Int> {
    if (!enabled || pageCount <= 0 || firstPlaced !in 0 until pageCount ||
        lastPlaced !in firstPlaced until pageCount) return emptyList()
    val start = if (forward) lastPlaced else firstPlaced
    val step = if (forward) 1 else -1
    return (1..minOf(2, pageCount)).map { start + it * step }.takeWhile { it in 0 until pageCount }
}

/**
 * Bounded 1-D source focus search on existing native row/button focus targets.
 * The host performs accepted requestFocus INSIDE withExtraPage, then retains its focused page as
 * the source FocusableNode's PinnableContainer does. Returning a candidate and tearing it down
 * before Android later calls requestFocus is incorrect.
 *
 * Call only after the focused descendant's key handler declined the corresponding Tab event.
 * This object does not intercept activation keys, create key-repeat policy, or take over native
 * focus lifecycle. Physical 2-D search retains the current-vertical-before-pager hierarchy. No screenshot/position-based sort is used:
 * source 1-D order is actual placement order, independent of LTR/RTL.
 */
internal class NativePagerBeyondBoundsFocus(
    private val focused: () -> View?,
    private val placed: () -> IntRange,
    private val existingPages: () -> List<Int>,
    private val userScrollEnabled: () -> Boolean,
    private val pageOf: (View) -> Int?,
    private val candidatesInPlacementOrder: (Int) -> List<View>,
    private val rtl: () -> Boolean,
    private val pageBounds: (Int) -> Rect?,
    private val focusBounds: (View) -> Rect?,
    private val withExtraPage: (Int, () -> Boolean) -> Boolean,
) {
    fun move(forward: Boolean): Boolean {
        val initial = focused() ?: return false
        if (pageOf(initial) == null) return false
        val direction = if (forward) View.FOCUS_FORWARD else View.FOCUS_BACKWARD
        fun accept(view: View): Boolean = view.isAttachedToWindow && view.visibility == View.VISIBLE &&
            view.isEnabled && view.isFocusable && view.requestFocus(direction)
        val range = placed()
        if (range.isEmpty()) return false
        val existing = existingPages().flatMap(candidatesInPlacementOrder)
        val start = existing.indexOfFirst { it === initial }
        if (start >= 0) {
            val indices = if (forward) (start + 1 until existing.size) else (start - 1 downTo 0)
            for (index in indices) if (accept(existing[index])) return true
        }
        val extra = nativePagerBeyondBoundsIndices(range.first, range.last, forward, userScrollEnabled())
            .filterNot { it in existingPages() }
        fun generate(at: Int): Boolean {
            if (at >= extra.size) return false
            val page = extra[at]
            return withExtraPage(page) {
                // Source's growing interval keeps earlier generated pages placed until search ends.
                if (focused() !== initial) true else {
                    val added = candidatesInPlacementOrder(page)
                    val ordered = if (forward) added else added.asReversed()
                    ordered.any(::accept) || generate(at + 1)
                }
            }
        }
        return generate(0)
    }

    /** Actual scroll-container hierarchy: current vertical first, then sibling pages, then beyond. */
    fun moveDirectional(direction: FocusDirection): Boolean {
        val initial = focused() ?: return false
        val currentPage = pageOf(initial) ?: return false
        val focusedRect = focusBounds(initial) ?: return false
        val androidDirection = when (direction) {
            FocusDirection.Left -> View.FOCUS_LEFT
            FocusDirection.Right -> View.FOCUS_RIGHT
            FocusDirection.Up -> View.FOCUS_UP
            FocusDirection.Down -> View.FOCUS_DOWN
            else -> return false
        }
        fun eligible(view: View) = view.isAttachedToWindow && view.visibility == View.VISIBLE &&
            view.isEnabled && view.isFocusable
        fun searchPage(page: Int): Boolean {
            val candidate = nativeBestDirectionalFocusCandidate(
                candidatesInPlacementOrder(page).filter { it !== initial && eligible(it) },
                focusedRect, direction, focusBounds) ?: return false
            // Pinned 2-D search returns this request's result; it does not skip a rejected target.
            return candidate.requestFocus(androidDirection)
        }
        if (searchPage(currentPage)) return true
        val range = placed()
        if (range.isEmpty()) return false
        val remaining = existingPages().filter { it != currentPage }.toMutableList()
        while (remaining.isNotEmpty()) {
            val next = nativeBestDirectionalFocusCandidate(remaining, focusedRect, direction, pageBounds) ?: break
            if (searchPage(next)) return true
            remaining.remove(next)
        }
        // A horizontal pager never generates Above/Below items. RTL reverses physical X only.
        val forward = when (direction) {
            FocusDirection.Left -> rtl()
            FocusDirection.Right -> !rtl()
            else -> return false
        }
        val extra = nativePagerBeyondBoundsIndices(range.first, range.last, forward, userScrollEnabled())
            .filterNot { it in existingPages() }
        fun generate(at: Int): Boolean {
            if (at >= extra.size) return false
            val next = extra[at]
            return withExtraPage(next) {
                focused() !== initial || searchPage(next) || generate(at + 1)
            }
        }
        return generate(0)
    }

}
