package app.smallthingz.reverb.nativeui

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class ReverbIconFit(
    val drawWidth: Float,
    val drawHeight: Float,
    val cacheWidth: Int,
    val cacheHeight: Int,
    val left: Int,
    val top: Int,
)

/** PainterModifier Fit/Center uses Float draw sizes, then rounds alignment only. */
internal fun fitReverbIcon(density: Float, width: Int, height: Int): ReverbIconFit {
    if (width <= 0 || height <= 0) return ReverbIconFit(0f, 0f, 0, 0, 0, 0)
    val source = 24f * density
    val scale = minOf(width.toFloat() / source, height.toFloat() / source)
    val drawWidth = source * scale
    val drawHeight = source * scale
    return ReverbIconFit(
        drawWidth = drawWidth,
        drawHeight = drawHeight,
        cacheWidth = ceil(drawWidth).toInt(),
        cacheHeight = ceil(drawHeight).toInt(),
        left = ((width - drawWidth.roundToInt()) / 2f).roundToInt(),
        top = ((height - drawHeight.roundToInt()) / 2f).roundToInt(),
    )
}

/**
 * Native renderer for the released, monochrome Reverb glyphs.
 *
 * Compose UI 1.11.2 VectorComponent renders these tintable paths into an ALPHA_8
 * software bitmap, then composites it with SrcIn tint. Drawing paths directly
 * into a hardware View canvas changes rasterization, so this follows that same
 * mask/composite boundary. Tint and RTL changes reuse the mask.
 *
 * Bounds are the host's measured content rectangle. The drawable performs the
 * released Icon's ContentScale.Fit/Center math, preserving the Float result even
 * when it differs slightly from the rounded bounds. Do not fit a second time.
 * This drawable does not add padding, hit targets, or semantics.
 * Create a separate instance per host. There is no animation scheduler.
 *
 * Supported coloring is the released monochrome SrcIn tint (including stateful
 * tint lists and tint alpha). Arbitrary ColorFilters and other tint modes are
 * deliberately unsupported: Compose uses a different bitmap configuration for
 * those, and no released Reverb icon needs that broader contract.
 */
internal class ReverbIconDrawable(
    val icon: ReverbIcon,
    private val density: Float,
    tint: Int,
) : Drawable() {
    private val intrinsicSize = (24f * density).roundToInt()
    private val maskPaint = Paint(PAINT_FLAGS)
    private val compositePaint = Paint(PAINT_FLAGS)
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val sourceRect = Rect()
    private var maskBitmap: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private var fit = fitReverbIcon(density, 0, 0)
    private var renderedWidth = 0f
    private var renderedHeight = 0f
    private var tintColors: ColorStateList? = ColorStateList.valueOf(tint)
    private var currentTint: Int? = tint
    private var tintFilter: ColorFilter? = srcInFilter(tint)
    private var tintEnabled = true
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        if (fit.cacheWidth <= 0 || fit.cacheHeight <= 0 || drawableAlpha == 0) return
        val bitmap = ensureMask()
        compositePaint.alpha = drawableAlpha
        compositePaint.colorFilter = if (tintEnabled) tintFilter else null
        sourceRect.set(0, 0, fit.cacheWidth, fit.cacheHeight)

        // VectorPainter mirrors the final cached image, not its source geometry.
        val mirrored = icon.autoMirrored && layoutDirection == View.LAYOUT_DIRECTION_RTL
        val checkpoint = canvas.save()
        try {
            canvas.translate((bounds.left + fit.left).toFloat(), (bounds.top + fit.top).toFloat())
            if (mirrored) canvas.scale(-1f, 1f, fit.drawWidth / 2f, fit.drawHeight / 2f)
            // Compose composites the ceil-sized cache at 1:1, not rescaled to the
            // Float viewport. Explicit rectangles also avoid density scaling.
            canvas.drawBitmap(bitmap, sourceRect, sourceRect, compositePaint)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        fit = fitReverbIcon(density, bounds.width(), bounds.height())
    }

    private fun ensureMask(): Bitmap {
        val width = fit.cacheWidth
        val height = fit.cacheHeight
        var bitmap = maskBitmap
        if (bitmap == null || width > bitmap.width || height > bitmap.height) {
            // Like Compose DrawCache: grow on demand, retain the allocation when
            // shrinking, and never recycle a bitmap still referenced by a frame.
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            maskBitmap = bitmap
            maskCanvas = Canvas(bitmap)
            renderedWidth = 0f
            renderedHeight = 0f
        }
        if (renderedWidth != fit.drawWidth || renderedHeight != fit.drawHeight) {
            val target = checkNotNull(maskCanvas)
            target.drawRect(0f, 0f, width.toFloat(), height.toFloat(), clearPaint)
            val checkpoint = target.save()
            try {
                target.scale(fit.drawWidth / VIEWPORT_SIZE, fit.drawHeight / VIEWPORT_SIZE)
                ReverbIconPaths.drawMask(icon, target, maskPaint)
            } finally {
                target.restoreToCount(checkpoint)
            }
            bitmap.prepareToDraw()
            renderedWidth = fit.drawWidth
            renderedHeight = fit.drawHeight
        }
        return bitmap
    }

    override fun getIntrinsicWidth(): Int = intrinsicSize

    override fun getIntrinsicHeight(): Int = intrinsicSize

    override fun setAlpha(alpha: Int) {
        val next = alpha.coerceIn(0, 255)
        if (drawableAlpha == next) return
        drawableAlpha = next
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        require(colorFilter == null) { "Reverb monochrome icons support SrcIn tint via setTint/setTintList only" }
    }

    override fun getColorFilter(): ColorFilter? = null

    override fun setTintMode(tintMode: PorterDuff.Mode?) {
        require(tintMode == null || tintMode == PorterDuff.Mode.SRC_IN) { "Reverb icons use SrcIn tint only" }
        setTintEnabled(tintMode != null)
    }

    @androidx.annotation.RequiresApi(29)
    override fun setTintBlendMode(blendMode: BlendMode?) {
        require(blendMode == null || blendMode == BlendMode.SRC_IN) { "Reverb icons use SrcIn tint only" }
        setTintEnabled(blendMode != null)
    }

    private fun setTintEnabled(enabled: Boolean) {
        if (tintEnabled == enabled) return
        tintEnabled = enabled
        invalidateSelf()
    }

    override fun setTint(tintColor: Int) {
        setTintList(ColorStateList.valueOf(tintColor))
    }

    override fun setTintList(tint: ColorStateList?) {
        if (tintColors === tint) return
        tintColors = tint
        if (updateTint()) invalidateSelf()
    }

    override fun isStateful(): Boolean = tintColors?.isStateful == true

    override fun onStateChange(state: IntArray): Boolean {
        val changed = updateTint()
        if (changed) invalidateSelf()
        return changed
    }

    private fun updateTint(): Boolean {
        val next = tintColors?.let { it.getColorForState(state, it.defaultColor) }
        if (next == currentTint) return false
        currentTint = next
        tintFilter = next?.let(::srcInFilter)
        return true
    }

    override fun isAutoMirrored(): Boolean = icon.autoMirrored

    override fun onLayoutDirectionChanged(layoutDirection: Int): Boolean {
        if (!icon.autoMirrored) return false
        invalidateSelf()
        return true
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val VIEWPORT_SIZE = 24f
        const val PAINT_FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG

        fun srcInFilter(color: Int): ColorFilter =
            if (Build.VERSION.SDK_INT >= 29) {
                BlendModeColorFilter(color, BlendMode.SRC_IN)
            } else {
                PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            }
    }
}
