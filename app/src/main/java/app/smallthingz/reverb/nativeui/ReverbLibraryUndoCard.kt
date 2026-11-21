package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeFeedbackTone
import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.smallthingz.reverb.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Library's direct FeedbackCard(INFO, Undo), without an invented entrance/exit or timeout.
 * Owner retains LibraryUndoNotice.window/deletionState and places this at BottomCenter with
 * 16dp horizontal and12dp vertical outer padding. This card never owns that full-screen hit box.
 */
internal class ReverbLibraryUndoCard(context: Context, private var environment: ReverbViewEnvironment, inputRoot: View) : ViewGroup(context) {
    private val surfaceStyle = environment.appearance.groupSurfaces.feedback
    private val surface = surfaceStyle.create(context, ::invalidateSettingsTextContrast)
    private val row = LibraryUndoRow(context, environment, inputRoot)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surface.addRow(row, NativeGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(surface)
    }
    fun bind(value: ReverbViewEnvironment, message: String, onUndo: () -> Unit) {
        environment = value
        val ink = surfaceStyle.bind(surface, value.palette, NativeFeedbackTone.INFO)
        row.bind(value, message, ink, onUndo)
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        surface.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(surface.measuredWidth, surface.measuredHeight)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) { surface.layout(0, 0, width, height) }
}

private class LibraryUndoRow(context: Context, private var environment: ReverbViewEnvironment, inputRoot: View) : ViewGroup(context) {
    private val layout = environment.appearance.groupSurfaces.feedback
    val message = environment.paragraph(context, "", ReverbTextRole.BODY_MEDIUM)
    private val action = ReverbLibraryTextAction(context, environment, inputRoot, environment.appearance.buttons.neutralText)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        message.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(message); addView(action)
    }
    fun bind(value: ReverbViewEnvironment, text: String, ink: Int, onUndo: () -> Unit) {
        environment = value
        message.setText(text)
        value.bindParagraphStyle(message, ReverbTextRole.BODY_MEDIUM, ink)
        action.bind(value, context.getString(R.string.undo), onUndo)
        requestLayout()
    }
    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = max(0, width - dp(layout.contentStartDp) - dp(layout.contentEndDp))
        val vertical = dp(layout.contentVerticalInsetDp)
        val boundedHeight = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
        val contentHeight = if (boundedHeight) max(0, MeasureSpec.getSize(heightMeasureSpec) - 2 * vertical) else 0
        val h = MeasureSpec.makeMeasureSpec(contentHeight, if (boundedHeight) MeasureSpec.AT_MOST else MeasureSpec.UNSPECIFIED)
        // Nonweighted action is measured first; weighted message fills the remaining width.
        action.measure(MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.AT_MOST), h)
        message.measure(MeasureSpec.makeMeasureSpec(max(0, contentWidth - action.measuredWidth - dp(layout.actionGapDp)),
            MeasureSpec.EXACTLY), h)
        setMeasuredDimension(width, resolveSize(max(message.measuredHeight, action.measuredHeight) + 2 * vertical, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val vertical = dp(layout.contentVerticalInsetDp)
        val contentHeight = height - 2 * vertical
        fun place(child: View, logicalStart: Int) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - logicalStart - child.measuredWidth else logicalStart
            val y = vertical + ((contentHeight - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
        val start = dp(layout.contentStartDp)
        place(message, start)
        place(action, start + message.measuredWidth + dp(layout.actionGapDp))
    }
}
