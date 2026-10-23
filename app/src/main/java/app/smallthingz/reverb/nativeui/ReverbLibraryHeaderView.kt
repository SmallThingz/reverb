package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.ViewGroup
import smallthingz.ui.unit.sp
import kotlin.math.roundToInt

/** FilesScreen.HeaderItem only. The containing list owns its separate 16dp horizontal inset. */
internal class ReverbLibraryHeaderView(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
) : ViewGroup(context) {
    private var environment = initialEnvironment
    private var compactTop = false
    private val label = initialEnvironment.paragraph(context, "", ReverbTextRole.LABEL_LARGE,
        initialEnvironment.palette.muted)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(label)
        bindTypography()
    }
    fun bind(text: String, compactTop: Boolean) {
        label.setText(text)
        if (this.compactTop != compactTop) { this.compactTop = compactTop; requestLayout() }
    }
    fun setEnvironment(value: ReverbViewEnvironment) {
        environment = value
        bindTypography()
    }
    private fun bindTypography() {
        label.setTypography(ReverbParagraphStyles[ReverbTextRole.LABEL_LARGE].copy(letterSpacing = 0.04.sp),
            environment.textDensity, environment.sharedFontResolver)
        label.setTextColor(environment.palette.muted)
        requestLayout()
    }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Source padding has independent rounded top/bottom edges; it is not a summed dp height.
        val verticalPadding = dp(if (compactTop) 8 else 28) + dp(8)
        label.measure(getChildMeasureSpec(widthMeasureSpec, 0, LayoutParams.WRAP_CONTENT),
            getChildMeasureSpec(heightMeasureSpec, verticalPadding, LayoutParams.WRAP_CONTENT))
        setMeasuredDimension(resolveSize(label.measuredWidth, widthMeasureSpec),
            resolveSize(label.measuredHeight + verticalPadding, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - label.measuredWidth else 0
        val y = dp(if (compactTop) 8 else 28)
        label.layout(x, y, x + label.measuredWidth, y + label.measuredHeight)
    }
    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection); requestLayout()
    }
}
