package app.smallthingz.reverb.nativeui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import app.smallthingz.reverb.R
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Source-derived counterpart of ReverbBrandMark: the released resource palette and foreground
 * geometry inside CircleShape, with FillBounds rather than Fit. No click target, tint override,
 * independent animation, extra inset, or icon/theme selection policy belongs to this leaf.
 *
 * The host owns any surrounding padding, action/ripple and its description merging. Resource
 * qualifiers follow this Context, just as colorResource/painterResource do in the release.
 * A host that changes its theme in place must call [refreshResourcePalette].
 */
internal class ReverbBrandMarkView(
    context: Context,
    description: CharSequence? = null,
) : View(context) {
    private var palette = readReverbBrandPalette(context)
    private val backgroundPaint = Paint(BRAND_PAINT_FLAGS)
    private val strokePaint = Paint(BRAND_PAINT_FLAGS).apply {
        style = Paint.Style.STROKE
        strokeWidth = 26f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeMiter = 4f
    }
    private val bitmapPaint = Paint(BRAND_PAINT_FLAGS)
    private val clearPaint = Paint(BRAND_PAINT_FLAGS).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val body = Path().apply {
        moveTo(127.686f, 358f)
        lineTo(156.357f, 154f)
        lineTo(222.357f, 154f)
        cubicTo(272.357f, 154f, 301.859f, 186f, 295.676f, 230f)
        cubicTo(289.492f, 274f, 250.995f, 306f, 200.995f, 306f)
        lineTo(134.995f, 306f)
    }
    private val leg = Path().apply {
        moveTo(196.995f, 306f)
        lineTo(281.686f, 358f)
    }
    private val softwareClip = Path()
    private val bounds = RectF()
    private val cacheRect = Rect()
    private var bitmap: Bitmap? = null
    private var cacheCanvas: Canvas? = null
    private var cacheDirty = true
    private var previousDrawWidth = Float.NaN
    private var previousDrawHeight = Float.NaN
    private var geometry = reverbBrandFillBounds(resources.displayMetrics.density, 0, 0)
    private var cornerRadius = 0f

    init {
        isClickable = false
        isLongClickable = false
        isFocusable = false
        contentDescription = description
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    override fun setContentDescription(contentDescription: CharSequence?) {
        super.setContentDescription(contentDescription)
        importantForAccessibility = if (contentDescription == null) {
            IMPORTANT_FOR_ACCESSIBILITY_NO
        } else {
            IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.ImageView"

    /** Re-resolves the same three released resources, without selecting a new palette locally. */
    fun refreshResourcePalette() {
        val next = readReverbBrandPalette(context)
        if (next != palette) {
            palette = next
            cacheDirty = true
            invalidate()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshResourcePalette()
        updateGeometry()
        requestLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A retained View may have been detached while its resource configuration changed.
        refreshResourcePalette()
        updateGeometry()
    }

    override fun onDetachedFromWindow() {
        // Drop our references; do not recycle a bitmap still referenced by a hardware display list.
        bitmap = null
        cacheCanvas = null
        cacheDirty = true
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val intrinsic = reverbBrandIntrinsicPx(resources.displayMetrics.density).roundToInt()
        fun dimension(spec: Int): Int = when (MeasureSpec.getMode(spec)) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> MeasureSpec.getSize(spec)
            else -> intrinsic
        }
        // Image.fillMaxSize consumes bounded constraints; intrinsic size supplies an unbounded axis.
        setMeasuredDimension(dimension(widthMeasureSpec), dimension(heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGeometry()
    }

    private fun updateGeometry() {
        geometry = reverbBrandFillBounds(resources.displayMetrics.density, width, height)
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        cornerRadius = min(width, height) / 2f
        softwareClip.rewind()
        softwareClip.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
        invalidateOutline()
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) {
            // CircleShape's simple round rect uses Android's outline-backed clipping too.
            super.draw(canvas)
        } else {
            val checkpoint = canvas.save()
            canvas.clipPath(softwareClip)
            super.draw(canvas)
            canvas.restoreToCount(checkpoint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        backgroundPaint.color = palette.background
        canvas.drawRect(bounds, backgroundPaint)
        if (geometry.cacheWidth <= 0 || geometry.cacheHeight <= 0) return
        ensureCache()
        val image = bitmap ?: return
        val checkpoint = canvas.save()
        canvas.translate(geometry.left.toFloat(), geometry.top.toFloat())
        // Compose draws the logical ceil-sized cache 1:1. Explicit rectangles prevent Android's
        // density-aware drawBitmap(left, top) overload from silently scaling the image.
        canvas.drawBitmap(image, cacheRect, cacheRect, bitmapPaint)
        canvas.restoreToCount(checkpoint)
    }

    private fun ensureCache() {
        val old = bitmap
        if (old == null || old.width < geometry.cacheWidth || old.height < geometry.cacheHeight) {
            bitmap = Bitmap.createBitmap(geometry.cacheWidth, geometry.cacheHeight, Bitmap.Config.ARGB_8888)
            cacheCanvas = Canvas(checkNotNull(bitmap))
            cacheDirty = true
        }
        if (!cacheDirty && previousDrawWidth == geometry.drawWidth &&
            previousDrawHeight == geometry.drawHeight) return
        val target = checkNotNull(cacheCanvas)
        cacheRect.set(0, 0, geometry.cacheWidth, geometry.cacheHeight)
        target.drawRect(0f, 0f, geometry.cacheWidth.toFloat(), geometry.cacheHeight.toFloat(), clearPaint)
        val checkpoint = target.save()
        target.scale(geometry.drawWidth / 512f, geometry.drawHeight / 512f)
        // Keep root/child transforms separate, in the released vector's exact draw order.
        target.translate(44.5f, 0f)
        drawGlyph(target, 56f, palette.accent, 0.16f)
        drawGlyph(target, 28f, palette.accent, 0.34f)
        drawGlyph(target, 0f, palette.main, 1f)
        target.restoreToCount(checkpoint)
        checkNotNull(bitmap).prepareToDraw()
        previousDrawWidth = geometry.drawWidth
        previousDrawHeight = geometry.drawHeight
        cacheDirty = false
    }

    private fun drawGlyph(canvas: Canvas, translateX: Float, color: Int, strokeAlpha: Float) {
        val checkpoint = canvas.save()
        canvas.translate(translateX, 0f)
        // SolidColor.applyTo multiplies Color alpha then converts to sRGB with half-up rounding.
        val alpha = (Color.alpha(color) / 255f * strokeAlpha * 255f + 0.5f).toInt()
        strokePaint.color = (color and 0x00ffffff) or (alpha shl 24)
        // These are separate stroked paths in the source; joining them changes overlap alpha.
        // Source transparent-black fills are no-ops, but make its cache non-tintable/ARGB_8888.
        canvas.drawPath(body, strokePaint)
        canvas.drawPath(leg, strokePaint)
        canvas.restoreToCount(checkpoint)
    }
}

private const val BRAND_PAINT_FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG

internal data class ReverbBrandPalette(val background: Int, val accent: Int, val main: Int)

internal fun readReverbBrandPalette(context: Context): ReverbBrandPalette = ReverbBrandPalette(
    context.resources.getColor(R.color.launcher_background, context.theme),
    context.resources.getColor(R.color.launcher_accent, context.theme),
    context.resources.getColor(R.color.launcher_main, context.theme),
)

internal data class ReverbBrandFillBounds(
    val drawWidth: Float,
    val drawHeight: Float,
    val cacheWidth: Int,
    val cacheHeight: Int,
    val left: Int,
    val top: Int,
)

internal fun reverbBrandIntrinsicPx(density: Float): Float {
    // XmlVectorParser: getDimension -> divide resource density -> Dp.toPx. Keep its Float order.
    val resourcePx = 108f * density
    return (resourcePx / density) * density
}

internal fun reverbBrandFillBounds(density: Float, width: Int, height: Int): ReverbBrandFillBounds {
    require(density.isFinite() && density > 0f && width >= 0 && height >= 0)
    if (width == 0 || height == 0) return ReverbBrandFillBounds(0f, 0f, 0, 0, (width / 2f).roundToInt(), (height / 2f).roundToInt())
    val intrinsic = reverbBrandIntrinsicPx(density)
    // Deliberately retain Float multiply after division: replacing this with integer bounds can
    // lose a fractional cache pixel, just as with PainterModifier's Fit path.
    val drawWidth = intrinsic * (width.toFloat() / intrinsic)
    val drawHeight = intrinsic * (height.toFloat() / intrinsic)
    return ReverbBrandFillBounds(
        drawWidth, drawHeight, ceil(drawWidth).toInt(), ceil(drawHeight).toInt(),
        ((width - drawWidth.roundToInt()) / 2f).roundToInt(),
        ((height - drawHeight.roundToInt()) / 2f).roundToInt(),
    )
}
