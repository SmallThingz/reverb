package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import app.smallthingz.reverb.RANGE_WAVEFORM_SETTLED_ENVELOPE_FRACTION
import kotlin.math.min
import kotlin.math.roundToInt

/** Exact released RangeTimelineMarkerVisual leaf; selection/position/animation remain the owner. */
internal class ReverbTimelineMarkerView(context: Context, private var environment: ReverbViewEnvironment) : ViewGroup(context) {
    private val line = MarkerShape(context)
    private val grip = MarkerShape(context)
    private var cursor = false
    private var active = false
    private var innerWidth = 0
    private var innerHeight = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        addView(line)
        addView(grip)
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    fun bind(value: ReverbViewEnvironment, active: Boolean, cursor: Boolean, visualAlpha: Float) {
        val dimensionsChanged = this.active != active || this.cursor != cursor ||
            environment.textDensity.density != value.textDensity.density
        environment = value
        this.active = active
        this.cursor = cursor
        alpha = visualAlpha.coerceIn(0f, 1f)
        val color = if (active) value.palette.tertiary else value.palette.onSurface
        line.setShape(color, 99f * value.textDensity.density, 0f)
        grip.setShape(color, if (cursor) Float.POSITIVE_INFINITY else 7f * value.textDensity.density,
            (if (active) (if (cursor) 4f else 3f) else 1f) * value.textDensity.density)
        if (dimensionsChanged) requestLayout()
    }

    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        // Pinned Box propagates relaxed minimum constraints to both child Boxes.
        innerWidth = min(width, dp(if (cursor) 10f else 8f))
        innerHeight = (height * (RANGE_WAVEFORM_SETTLED_ENVELOPE_FRACTION + .02f)).roundToInt().coerceIn(0, height)
        line.measure(timelineMarkerExact(min(innerWidth, dp(if (active) 1.4f else 1f))), timelineMarkerExact(innerHeight))
        grip.measure(timelineMarkerExact(min(innerWidth, dp(if (cursor) 6f else 8f))),
            timelineMarkerExact(min(innerHeight, dp(if (cursor) 6f else 28f))))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val innerLeft = ((width - innerWidth) / 2f).roundToInt()
        val innerTop = ((height - innerHeight) / 2f).roundToInt()
        fun place(child: View, atBottom: Boolean) {
            val x = innerLeft + ((innerWidth - child.measuredWidth) / 2f).roundToInt()
            val y = innerTop + if (atBottom) innerHeight - child.measuredHeight
                else ((innerHeight - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
        place(line, cursor)
        place(grip, cursor)
    }

    private class MarkerShape(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var requestedRadius = 0f
        private fun radius() = min(requestedRadius, min(width, height) / 2f)
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, width, height, radius())
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        }
        fun setShape(color: Int, radius: Float, shadow: Float) {
            val geometryChanged = requestedRadius != radius || elevation != shadow
            val colorChanged = paint.color != color
            if (!geometryChanged && !colorChanged) return
            paint.color = color
            requestedRadius = radius
            elevation = shadow
            if (geometryChanged) invalidateOutline()
            invalidate()
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { invalidateOutline() }
        override fun onDraw(canvas: Canvas) {
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius(), radius(), paint)
        }
    }
}

private fun timelineMarkerExact(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
