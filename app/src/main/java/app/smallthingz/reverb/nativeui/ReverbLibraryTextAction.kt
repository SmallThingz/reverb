package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.components.NativeButtonInputPolicy
import smallthingz.ui.components.NativeMaterialButtonStyle
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.max
import kotlin.math.roundToInt

/** Natural-width released TextButton used by Library Retry and Undo; no timer/operation owner. */
internal class ReverbLibraryTextAction(context: Context, environment: ReverbViewEnvironment, inputRoot: View) : ViewGroup(context) {
    private var density = environment.textDensity.density
    private val visual = NativeMaterialButtonView(context, false, reverbButtonTextStyle(),
        environment.textDensity, environment.sharedFontResolver, NativeMaterialButtonStyle(12),
        NativeButtonInputPolicy(inputRoot = { inputRoot }), {})
    val textView: ParagraphView get() = visual.label
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        addView(visual)
    }
    fun bind(environment: ReverbViewEnvironment, text: String, textColor: Int = environment.palette.primary, onClick: () -> Unit) {
        density = environment.textDensity.density
        visual.bindTypography(reverbButtonTextStyle(), environment.textDensity, environment.sharedFontResolver)
        visual.bind(text, true, Color.TRANSPARENT, textColor, onClick, environment.palette.primary)
        requestLayout()
    }

    override fun addChildrenForAccessibility(children: ArrayList<View>) {
        val first = children.size
        super.addChildrenForAccessibility(children)
        if (first == children.size) return
        if (!visual.mergedRowAccessibility.isNodePresent()) {
            for (index in children.lastIndex downTo first) {
                if (children[index] === visual) children.removeAt(index)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        visual.measure(widthMeasureSpec, heightMeasureSpec)
        val minimum = (48f * density).roundToInt()
        setMeasuredDimension(resolveSize(max(minimum, visual.measuredWidth), widthMeasureSpec),
            resolveSize(max(minimum, visual.measuredHeight), heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = ((width - visual.measuredWidth) / 2f).roundToInt()
        val y = ((height - visual.measuredHeight) / 2f).roundToInt()
        visual.layout(x, y, x + visual.measuredWidth, y + visual.measuredHeight)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(-visual.left.toFloat(), -visual.top.toFloat())
        return try { visual.dispatchTouchEvent(copy) } finally { copy.recycle() }
    }
}
