package app.smallthingz.reverb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.dp
import app.smallthingz.reverb.nativeui.*
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.geometry.Rect as ComposeRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Pinned PagerMeasure Fill-page placement; offscreen state retention is owned outside this View. */
internal fun nativeOnboardingPlacedPages(width: Int, offset: Long): IntRange {
    if (width == 0) return 2..2
    val first = (offset / width).toInt().coerceIn(0, 2)
    val last = ((offset + width - 1L) / width).toInt().coerceIn(first, 2)
    return first..last
}

/** Fixed three-page membership; callers already validate focus-search indices. */
internal fun nativeOnboardingPageMembershipMask(
    placed: IntRange,
    focusSearchPages: Set<Int>,
    focusedPage: Int?,
): Int {
    var mask = 0
    for (page in placed) mask = mask or (1 shl page)
    // Ordinary motion has no focus-search pin; avoid even an empty-set iterator on that path.
    if (focusSearchPages.isNotEmpty()) {
        for (page in focusSearchPages) mask = mask or (1 shl page)
    }
    if (focusedPage != null) mask = mask or (1 shl focusedPage)
    return mask
}

/**
 * Direct native placement owner. Retains the three page bodies/state but only attaches actually
 * placed pages. Unplaced nodes therefore cannot receive input, own a fling or request permission.
 * This intentionally does not reproduce the unobservable Compose prefetch/cache implementation.
 */
internal class NativeOnboardingPlacedPagerViewport(
    context: Context,
    private val pager: NativeOnboardingPager,
    private val density: Density,
    private val pages: List<View>,
    private val overscroll: NativeHorizontalOverscroll,
    private val frames: MonotonicFrameClock,
    private val parentScope: CoroutineScope,
    private val placementChanged: () -> Unit,
) : ViewGroup(context), NativeBringIntoViewParent {
    override val bringIntoViewView: View get() = this
    private var attachedScope: CoroutineScope? = null
    private var focusedDescendant: View? = null
    private val contentInView = NativePagerContentInViewOwner(pager, frames,
        { requireNotNull(attachedScope) }, ::focusedBounds,
        NativePagerBringIntoViewSpec(pager, nativeBringIntoViewSpec(context)))
    private val focusSearchPages = mutableSetOf<Int>()
    private var attachedPageMask = 0
    private var measuring = false
    var sourceAccessibility: NativeOnboardingPagerAccessibility? = null
    var placedPages: IntRange = IntRange.EMPTY
        private set

    init {
        require(pages.size == NATIVE_ONBOARDING_PAGE_COUNT)
        clipChildren = false
        clipToPadding = false
        isFocusable = false
    }

    fun onPagerChanged() {
        if (measuring) return
        if (pager.needsRemeasure || !isLaidOut || isLayoutRequested) requestLayout()
        else {
            reconcilePlaced(width, height)
            place(width, height)
        }
        invalidate()
    }

    private fun reconcilePlaced(width: Int, height: Int) {
        val next = nativeOnboardingPlacedPages(width, pager.layoutAbsoluteOffsetPx)
        val focusedPage = pages.indexOfFirst { it.hasFocus() }.takeIf { it >= 0 }
        val wantedMask = nativeOnboardingPageMembershipMask(next, focusSearchPages, focusedPage)
        if (wantedMask == attachedPageMask && next == placedPages) return
        val previousMask = attachedPageMask
        for (index in 0 until NATIVE_ONBOARDING_PAGE_COUNT) {
            val bit = 1 shl index
            if (previousMask and bit != 0 && wantedMask and bit == 0) removeView(pages[index])
        }
        for (index in 0 until NATIVE_ONBOARDING_PAGE_COUNT) if (
            wantedMask and (1 shl index) != 0 && pages[index].parent !== this
        ) {
            val insertion = (0 until childCount).count { pages.indexOf(getChildAt(it)) < index }
            addView(pages[index], insertion, LayoutParams(-1, -1))
            pages[index].measure(exact(width), exact(height))
        }
        placedPages = next
        attachedPageMask = wantedMask
        placementChanged()
    }

    fun focusPlacedPages(): List<Int> {
        val mask = attachedPageMask
        val result = ArrayList<Int>(NATIVE_ONBOARDING_PAGE_COUNT)
        for (index in 0 until NATIVE_ONBOARDING_PAGE_COUNT) {
            if (mask and (1 shl index) != 0) result.add(index)
        }
        return result
    }

    /** Synchronous source beyond-bounds placement; accepted focus pins the new page afterwards. */
    fun withFocusSearchPage(page: Int, block: () -> Boolean): Boolean {
        check(page in pages.indices && isAttachedToWindow && isLaidOut)
        val added = focusSearchPages.add(page)
        reconcilePlaced(width, height)
        place(width, height)
        return try { block() } finally {
            if (added) focusSearchPages.remove(page)
            reconcilePlaced(width, height)
            place(width, height)
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        check(MeasureSpec.getMode(w) == MeasureSpec.EXACTLY && MeasureSpec.getMode(h) == MeasureSpec.EXACTLY)
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        setMeasuredDimension(width, height)
        measuring = true
        try {
            pager.rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            pager.updateLayout(width, density)
            contentInView.measured(width)
            reconcilePlaced(width, height)
            val mask = attachedPageMask
            for (index in 0 until NATIVE_ONBOARDING_PAGE_COUNT) {
                if (mask and (1 shl index) != 0) pages[index].measure(exact(width), exact(height))
            }
        } finally { measuring = false }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        place(r - l, b - t)
        if (changed) sourceAccessibility?.sourceLayoutChanged()
    }

    override fun createAccessibilityNodeInfo(): android.view.accessibility.AccessibilityNodeInfo? =
        if (sourceAccessibility?.isNodePresent() == false) null else super.createAccessibilityNodeInfo()

    private fun place(width: Int, height: Int) {
        val mask = attachedPageMask
        for (index in 0 until NATIVE_ONBOARDING_PAGE_COUNT) if (mask and (1 shl index) != 0) {
            val x = pager.pageTranslationX(index).toInt()
            pages[index].layout(x, 0, x + width, height)
        }
        sourceAccessibility?.sourceStateChanged()
    }

    override fun draw(canvas: Canvas) {
        // AndroidOverscroll.updateSize is a draw-boundary read; not an onLayout callback.
        overscroll.measured(width, height)
        overscroll.draw(canvas) { super.draw(it) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val margin = with(density) { 30.dp.roundToPx() }.toFloat()
        val checkpoint = canvas.save()
        try {
            canvas.clipRect(0f, -margin, width.toFloat(), height + margin)
            super.dispatchDraw(canvas)
        } finally { canvas.restoreToCount(checkpoint) }
    }

    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
        val requested = ComposeRect(rectangle.left.toFloat(), rectangle.top.toFloat(),
            rectangle.right.toFloat(), rectangle.bottom.toFloat())
        attachedScope?.launch { bringDescendantIntoView(child) { requested } }
        return true
    }

    override fun requestChildFocus(child: View, focused: View) {
        super.requestChildFocus(child, focused)
        if (focusedDescendant === focused) return
        focusedDescendant = focused
        // Each FocusableNode makes ONE request. A nested native vertical viewport already owns
        // that launch and propagates it to this responder; do not launch a duplicate here.
        var ancestor = focused.parent
        while (ancestor is View && ancestor !== this) {
            if (ancestor is NativeBringIntoViewParent) return
            ancestor = ancestor.parent
        }
        attachedScope?.launch {
            bringDescendantIntoView(focused) {
                if (focused.isAttachedToWindow) ComposeRect(0f, 0f, focused.width.toFloat(), focused.height.toFloat())
                else null
            }
        }
    }

    override fun clearChildFocus(child: View) {
        super.clearChildFocus(child)
        focusedDescendant = null
        // Release a source focus pin when native focus leaves the page. No delayed reservation.
        requestLayout()
    }

    private fun focusedBounds(): ComposeRect? {
        val focused = findFocus()?.takeIf { it !== this } ?: return null
        return nativeBringIntoViewLocalRect(this, focused,
            ComposeRect(0f, 0f, focused.width.toFloat(), focused.height.toFloat()))
    }

    override suspend fun bringDescendantIntoView(child: View, bounds: () -> ComposeRect?) {
        fun localRect(): ComposeRect? {
            if (!isAttachedToWindow || !isLaidOut) return null
            return bounds()?.let { nativeBringIntoViewLocalRect(this, child, it) }
        }
        val parentBounds = { localRect()?.let(contentInView::calculateRectForParent) }
        coroutineScope {
            launch { contentInView.bringChildIntoView(::localRect) }
            launch {
                var ancestor = parent
                while (ancestor is View && ancestor !is NativeBringIntoViewParent) ancestor = ancestor.parent
                if (ancestor is NativeBringIntoViewParent) {
                    ancestor.bringDescendantIntoView(this@NativeOnboardingPlacedPagerViewport, parentBounds)
                } else {
                    parentBounds()?.let { requestRectangleOnScreen(it.toNativeBringIntoViewRect(), false) }
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        attachedScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        focusedDescendant = null
        attachedScope?.cancel()
        attachedScope = null
        overscroll.dispose()
        super.onDetachedFromWindow()
    }

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(-1, -1)
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
}
