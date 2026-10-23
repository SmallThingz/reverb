package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.compositeOver
import smallthingz.ui.graphics.toArgb
import app.smallthingz.reverb.R
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Library's direct FeedbackCard(INFO, Undo), without an invented entrance/exit or timeout.
 * Owner retains LibraryUndoNotice.window/deletionState and places this at BottomCenter with
 * 16dp horizontal and12dp vertical outer padding. This card never owns that full-screen hit box.
 */
internal class ReverbLibraryUndoCard(context: Context, private var environment: ReverbViewEnvironment, inputRoot: View) : ViewGroup(context) {
    private val surface = ReverbGroupLayout(context, 18f)
    private val row = LibraryUndoRow(context, environment, inputRoot)
    val messageView: ReverbParagraphView get() = row.message
    val actionTextView: ReverbParagraphView get() = row.actionText
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surface.addRow(row, ReverbGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(surface)
    }
    fun bind(value: ReverbViewEnvironment, message: String, onUndo: () -> Unit) {
        environment = value
        surface.setPalette(libraryFeedbackSurface(value.palette), android.graphics.Color.TRANSPARENT)
        row.bind(value, message, onUndo)
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        surface.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(surface.measuredWidth, surface.measuredHeight)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) { surface.layout(0, 0, width, height) }
}

private class LibraryUndoRow(context: Context, private var environment: ReverbViewEnvironment, inputRoot: View) : ViewGroup(context) {
    val message = environment.paragraph(context, "", ReverbTextRole.BODY_MEDIUM)
    private val action = ReverbLibraryTextAction(context, environment, inputRoot)
    val actionText get() = action.textView
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        message.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(message); addView(action)
    }
    fun bind(value: ReverbViewEnvironment, text: String, onUndo: () -> Unit) {
        environment = value
        message.setText(text)
        value.bindParagraphStyle(message, ReverbTextRole.BODY_MEDIUM, value.palette.onSurface)
        action.bind(value, context.getString(R.string.undo), value.palette.onSurface, onUndo)
        requestLayout()
    }
    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = max(0, width - dp(16f) - dp(8f))
        val boundedHeight = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
        val contentHeight = if (boundedHeight) max(0, MeasureSpec.getSize(heightMeasureSpec) - dp(8f) - dp(8f)) else 0
        val h = MeasureSpec.makeMeasureSpec(contentHeight, if (boundedHeight) MeasureSpec.AT_MOST else MeasureSpec.UNSPECIFIED)
        // Nonweighted action is measured first; weighted message fills the remaining width.
        action.measure(MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.AT_MOST), h)
        message.measure(MeasureSpec.makeMeasureSpec(max(0, contentWidth - action.measuredWidth - dp(8f)), MeasureSpec.EXACTLY), h)
        setMeasuredDimension(width, resolveSize(max(message.measuredHeight, action.measuredHeight) + dp(8f) + dp(8f), heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val contentHeight = height - dp(8f) - dp(8f)
        fun place(child: View, logicalStart: Int) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - logicalStart - child.measuredWidth else logicalStart
            val y = dp(8f) + ((contentHeight - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
        place(message, dp(16f))
        place(action, dp(16f) + message.measuredWidth + dp(8f))
    }
}

/** M3 surfaceColorAtElevation applies overlay only when the supplied color equals surface. */
private fun libraryFeedbackSurface(palette: ReverbPalette): Int {
    val container = palette.surfaceContainerHigh
    if (container != palette.surface) return container
    val alpha = ((4.5f * ln(2f + 1f)) + 2f) / 100f
    return Color(palette.surfaceTint).copy(alpha = alpha).compositeOver(Color(container)).toArgb()
}
