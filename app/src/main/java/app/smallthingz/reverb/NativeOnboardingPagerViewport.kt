package app.smallthingz.reverb

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * Geometry/clip seam only. Not a production-ready pager: the source-matched touch, nested-scroll,
 * overscroll, accessibility and predictive-back host must surround this viewport before wiring.
 *
 * The parent supplies exactly the released Box(weight=1f) allocation as exact width/height specs.
 * The three page bodies are stable native Views, each owning its independent vertical scroll.
 */
internal class NativeOnboardingPagerViewport(
    context: Context,
    private val pager: NativeOnboardingPager,
    private var density: Density,
    pages: List<View>,
) : ViewGroup(context) {
    private var measuring = false

    init {
        require(pages.size == NATIVE_ONBOARDING_PAGE_COUNT)
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        for (page in pages) addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Bind this to the engine's typed onChanged callback; it does not create another state tree. */
    fun onPagerChanged() {
        if (pager.needsRemeasure) requestLayout()
        else if (!measuring && isLaidOut && !isLayoutRequested) placePagesWithoutRemeasure()
        invalidate()
    }

    /** Environment replacement is explicit; page typography environments are replaced by their owner. */
    fun replaceDensity(value: Density) {
        density = value
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        check(MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY)
        check(MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        for (index in 0 until childCount) {
            getChildAt(index).measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }
        // This is the actual measurement boundary required by requestScrollToPage.
        measuring = true
        try {
            pager.rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            pager.updateLayout(width, density)
        } finally {
            measuring = false
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        for (index in 0 until childCount) {
            val x = pager.pageTranslationX(index).toInt()
            getChildAt(index).layout(x, 0, x + width, height)
        }
    }

    private fun placePagesWithoutRemeasure() {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val x = pager.pageTranslationX(index).toInt()
            child.offsetLeftAndRight(x - child.left)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Pinned HorizontalScrollableClipShape clips X exactly, but allows +/-30dp on Y.
        // A blanket clipChildren=true would cut a different cross-axis region.
        val margin = with(density) { 30.dp.roundToPx() }.toFloat()
        val checkpoint = canvas.save()
        canvas.clipRect(0f, -margin, width.toFloat(), height + margin)
        try {
            super.dispatchDraw(canvas)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
}
