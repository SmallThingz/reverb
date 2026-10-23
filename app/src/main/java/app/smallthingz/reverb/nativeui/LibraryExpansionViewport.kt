package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import smallthingz.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * enter/exit modifier layout: measure the original child at natural size, constrain the animated
 * viewport, then bottom-align the full child inside that viewport. Its separate alpha layer does
 * not overwrite the inline player's own alpha. Source layout uses LTR alignment for this modifier.
 */
internal class LibraryExpansionViewport(
    context: Context,
    private val currentMotion: () -> LibraryExpansionMotion,
    private val needsFrames: () -> Unit,
) : ViewGroup(context) {
    private val layer = FrameLayout(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
    }
    private var content: View? = null
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = true
        clipToPadding = true
        addView(layer)
    }
    fun setContent(view: View?) {
        if (content === view) return
        layer.removeAllViews()
        content = view
        view?.let {
            layer.addView(it, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        requestLayout()
    }
    fun publishAlpha() { layer.alpha = currentMotion().alpha }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        layer.measure(widthMeasureSpec, heightMeasureSpec)
        val motion = currentMotion()
        motion.measured(IntSize(layer.measuredWidth, layer.measuredHeight))
        val size = motion.measuredSize
        setMeasuredDimension(resolveSize(size.width.coerceAtLeast(0), widthMeasureSpec),
            resolveSize(size.height.coerceAtLeast(0), heightMeasureSpec))
        publishAlpha()
        needsFrames()
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // Alignment.Bottom centers x using roundToInt in the source; width normally stays full.
        val x = ((width - layer.measuredWidth) / 2f).roundToInt()
        val y = height - layer.measuredHeight
        layer.layout(x, y, x + layer.measuredWidth, y + layer.measuredHeight)
    }
}
