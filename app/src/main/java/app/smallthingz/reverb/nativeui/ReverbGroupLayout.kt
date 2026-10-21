package app.smallthingz.reverb.nativeui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.DrawableWrapper
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import kotlin.math.ceil
import kotlin.math.min

private const val GROUP_PAINT_FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG

/**
 * Native counterpart of SettingsAudioGroup's surface, without a second rendering/state tree.
 *
 * The caller owns the existing 16dp external inset and every child's padding. This layout adds
 * neither gaps nor padding. Divider flags are explicit: the source does not separate every child
 * (for example, a validation message belongs to the preceding setting).
 *
 * This is not wired into production until the release/native rendering fixture qualifies it.
 */
internal class ReverbGroupLayout(
    context: Context,
    private val cornerRadiusDp: Float = 16f,
) : FrameLayout(context) {
    private val fillPaint = Paint(GROUP_PAINT_FLAGS)
    private val borderPaint = Paint(GROUP_PAINT_FLAGS).apply { style = Paint.Style.STROKE }
    private val bounds = RectF()
    private val borderBounds = RectF()
    private var geometry = reverbGroupGeometry(0f, 0f, resources.displayMetrics.density, cornerRadiusDp)
    private val content = ReverbClippedGroupContent(context)

    init {
        require(cornerRadiusDp.isFinite() && cornerRadiusDp >= 0f)
        setWillNotDraw(false)
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        // Keep the fill and outer border outside this child layer, exactly as Surface's
        // border -> background -> graphicsLayer(clip) modifier chain does.
        super.addView(
            content,
            0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        // AndroidComposeView explicitly disables Force Dark; preserve that per-view contract.
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    fun addRow(child: View, params: LayoutParams) {
        content.addView(child, params)
    }

    fun removeRow(child: View) {
        content.removeView(child)
    }

    fun setPalette(@ColorInt field: Int, @ColorInt border: Int) {
        if (fillPaint.color != field || borderPaint.color != border) {
            fillPaint.color = field
            borderPaint.color = border
            invalidate()
        }
        content.setDividerColor(border)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShape()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateShape()
        invalidate()
    }

    private fun updateShape() {
        geometry = reverbGroupGeometry(width.toFloat(), height.toFloat(), resources.displayMetrics.density, cornerRadiusDp)
        bounds.set(0f, 0f, geometry.width, geometry.height)
        borderBounds.set(bounds)
        borderBounds.inset(geometry.strokeWidth / 2f, geometry.strokeWidth / 2f)
        borderPaint.strokeWidth = geometry.strokeWidth
        content.setCornerRadiusPx(geometry.radius)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(bounds, geometry.radius, geometry.radius, fillPaint)
        super.onDraw(canvas)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        // Compose's border modifier draws after its content, including the per-row dividers.
        if (geometry.strokeWidth == 0f) return
        if (geometry.fillBorder) {
            borderPaint.style = Paint.Style.FILL
            canvas.drawRoundRect(bounds, geometry.radius, geometry.radius, borderPaint)
            borderPaint.style = Paint.Style.STROKE
        } else {
            canvas.drawRoundRect(borderBounds, geometry.strokeRadius, geometry.strokeRadius, borderPaint)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Clipping drawing alone does not clip Android's rectangular child hit testing. Admit a
        // gesture only inside the same outer shape; an admitted gesture keeps normal View ownership.
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            !geometry.acceptsPointer(
                event.x,
                event.y,
                touch = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER,
                minimumTarget = 48f * resources.displayMetrics.density,
            )
        ) return false
        return super.dispatchTouchEvent(event)
    }

    // Material Surface's empty pointerInput node shields underlying siblings even in inert areas.
    // This does not make the group clickable or intercept child/ancestor scroll ownership.
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    class LayoutParams : LinearLayout.LayoutParams {
        var dividerTop = false
        var dividerStart = false

        constructor(width: Int, height: Int, weight: Float = 0f) : super(width, height, weight)

        constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

        constructor(source: ViewGroup.LayoutParams) : super(source)

        constructor(source: LinearLayout.LayoutParams) : super(source) {
            if (source is LayoutParams) {
                dividerTop = source.dividerTop
                dividerStart = source.dividerStart
            }
        }
    }
}

/**
 * Compose's simple round-rect graphics layer uses Android Outline hardware clipping. An always-on
 * Canvas.clipPath is not equivalent at fractional radii (the physical-device parity fixture caught
 * the edge coverage difference). A normal child View supplies that same outline-backed layer on
 * every supported API, without a separate RenderNode implementation or an offscreen bitmap.
 */
private class ReverbClippedGroupContent(context: Context) : ReverbDividerLayout(context) {
    private var radius = 0f
    private val softwareClip = Path()
    private val softwareBounds = RectF()

    init {
        orientation = VERTICAL
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    fun setCornerRadiusPx(value: Float) {
        if (radius == value) return
        radius = value
        rebuildClip()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildClip()
    }

    private fun rebuildClip() {
        softwareBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        softwareClip.rewind()
        softwareClip.addRoundRect(softwareBounds, radius, radius, Path.Direction.CW)
        invalidateOutline()
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) {
            // View's RenderNode applies the rounded outline around this entire child subtree.
            super.dispatchDraw(canvas)
        } else {
            // Compose's ViewLayer/OutlineResolver uses the path fallback for software Canvas too.
            val checkpoint = canvas.save()
            canvas.clipPath(softwareClip)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }
    }
}

/** A paired settings row; no extra wrapper, surface, padding, or independent corner shape. */
internal class ReverbGroupRow(context: Context) : ReverbDividerLayout(context) {
    init {
        orientation = HORIZONTAL
    }
}

/** Draws the same explicit top/start strokes as Modifier.settingsAudioDivider, after each child. */
internal open class ReverbDividerLayout(context: Context) : LinearLayout(context) {
    @ColorInt private var dividerColor = 0

    init {
        isBaselineAligned = false
        isFocusable = false
        isClickable = false
        // The surface is not a second actionable accessibility node. NO preserves its descendants;
        // NO_HIDE_DESCENDANTS would incorrectly remove the child controls from accessibility.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    fun setDividerColor(@ColorInt color: Int) {
        if (dividerColor == color) return
        dividerColor = color
        for (index in 0 until childCount) updateDivider(getChildAt(index))
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        updateDivider(child)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        for (index in 0 until childCount) updateDivider(getChildAt(index))
    }

    override fun onViewRemoved(child: View) {
        val foreground = child.foreground
        if (foreground is GroupDividerForeground && foreground.owner === this) {
            restoreForeground(child, foreground)
        }
        super.onViewRemoved(child)
    }

    private fun updateDivider(child: View) {
        val params = child.layoutParams as? ReverbGroupLayout.LayoutParams
        val foreground = child.foreground
        if (params == null || (!params.dividerTop && !params.dividerStart)) {
            if (foreground is GroupDividerForeground && foreground.owner === this) {
                restoreForeground(child, foreground)
            }
            return
        }
        val divider = if (foreground is GroupDividerForeground && foreground.owner === this) {
            foreground
        } else {
            GroupDividerForeground(this, child).also {
                // Install first: View.setForeground clears the old drawable's callback. Wrapping
                // it beforehand would accidentally clear DrawableWrapper's callback ownership.
                child.foreground = it
                it.drawable = foreground
                child.requestLayout()
            }
        }
        divider.update(dividerColor, params.dividerTop, params.dividerStart)
    }

    private fun restoreForeground(child: View, foreground: GroupDividerForeground) {
        val original = foreground.drawable
        foreground.drawable = null
        child.foreground = original
    }

    override fun generateDefaultLayoutParams(): ReverbGroupLayout.LayoutParams =
        ReverbGroupLayout.LayoutParams(super.generateDefaultLayoutParams())

    override fun generateLayoutParams(attrs: AttributeSet?): ReverbGroupLayout.LayoutParams =
        ReverbGroupLayout.LayoutParams(context, attrs)

    override fun generateLayoutParams(params: ViewGroup.LayoutParams): ReverbGroupLayout.LayoutParams =
        if (params is LinearLayout.LayoutParams) ReverbGroupLayout.LayoutParams(params)
        else ReverbGroupLayout.LayoutParams(params)

    override fun checkLayoutParams(params: ViewGroup.LayoutParams): Boolean =
        params is ReverbGroupLayout.LayoutParams
}

/**
 * A child-owned decoration, not a wrapper View. The row and its divider must enter the same native
 * alpha/transform/layer: painting a separately alpha-scaled divider from the parent is not equivalent
 * when translucent content overlaps it. DrawableWrapper retains existing ripple, state and bounds.
 */
private class GroupDividerForeground(
    val owner: ReverbDividerLayout,
    private val child: View,
) : DrawableWrapper(null) {
    private val paint = Paint(GROUP_PAINT_FLAGS).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        strokeCap = Paint.Cap.BUTT
    }
    private val dirtyBounds = Rect()
    private var top = false
    private var start = false

    fun update(@ColorInt color: Int, top: Boolean, start: Boolean) {
        if (paint.color == color && this.top == top && this.start == start) return
        paint.color = color
        this.top = top
        this.start = start
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        // Use full child bounds even if an existing foreground has CENTER gravity/intrinsic size.
        // Its own bounds remain unchanged; native View does not translate the foreground canvas.
        if (top) canvas.drawLine(0f, 0.5f, child.width.toFloat(), 0.5f, paint)
        if (start) {
            val x = reverbGroupStartDividerX(child.width.toFloat(), child.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            canvas.drawLine(x, 0f, x, child.height.toFloat(), paint)
        }
    }

    override fun getDirtyBounds(): Rect = dirtyBounds.apply { set(0, 0, child.width, child.height) }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Pure geometry kept independent of Android objects for density/RTL/corner regression coverage. */
internal data class ReverbGroupGeometry(
    val width: Float,
    val height: Float,
    val radius: Float,
    val strokeWidth: Float,
    val strokeRadius: Float,
    val fillBorder: Boolean,
) {
    fun contains(x: Float, y: Float): Boolean {
        if (x < 0f || y < 0f || x >= width || y >= height || !x.isFinite() || !y.isFinite()) return false
        val dx = x - x.coerceIn(radius, width - radius)
        val dy = y - y.coerceIn(radius, height - radius)
        return dx * dx + dy * dy <= radius * radius
    }

    fun acceptsPointer(x: Float, y: Float, touch: Boolean, minimumTarget: Float): Boolean {
        if (contains(x, y)) return true
        if (!touch || width <= 0f || height <= 0f || !x.isFinite() || !y.isFinite() ||
            (width >= minimumTarget && height >= minimumTarget)
        ) return false
        // NodeCoordinator permits touch-only near hits for undersized clipped layouts. Native
        // parents still need a TouchDelegate if a future caller needs delivery outside View bounds;
        // Settings groups already exceed the minimum in both dimensions.
        val horizontal = ((minimumTarget - width) / 2f).coerceAtLeast(0f)
        val vertical = ((minimumTarget - height) / 2f).coerceAtLeast(0f)
        return x >= -horizontal && x <= width + horizontal &&
            y >= -vertical && y <= height + vertical
    }
}

internal fun reverbGroupGeometry(
    width: Float,
    height: Float,
    density: Float,
    cornerRadiusDp: Float = 16f,
): ReverbGroupGeometry {
    val minimum = min(width, height).coerceAtLeast(0f)
    val radius = min(cornerRadiusDp * density, minimum / 2f)
    // Compose BorderModifierNode rounds a non-hairline dp stroke up to device pixels.
    val stroke = min(ceil(density), ceil(minimum / 2f))
    return ReverbGroupGeometry(
        width = width,
        height = height,
        radius = radius,
        strokeWidth = stroke,
        strokeRadius = (radius - stroke / 2f).coerceAtLeast(0f),
        fillBorder = stroke * 2f > minimum,
    )
}

internal fun reverbGroupStartDividerX(width: Float, rtl: Boolean): Float =
    if (rtl) width - 0.5f else 0.5f
