package app.smallthingz.reverb.nativeui

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt

/**
 * Native integration candidate; actual rendering still requires release A/B qualification.
 *
 * Draws generated grain in component-local coordinates. A null background is the
 * appNoise modifier alone; an explicit background reproduces background(color).appNoise(brush).
 * The owning native root must retain AndroidComposeView's isForceDarkAllowed=false on API29+.
 */
internal class ReverbNoiseDrawable(
    @Suppress("UNUSED_PARAMETER") resources: Resources,
    @ColorInt backgroundColor: Int? = null,
    private val seed: Int = SEED_BACKGROUND,
) : Drawable() {
    private val backgroundPaint = Paint(PAINT_FLAGS)
    private val noisePaint = Paint(PAINT_FLAGS).apply {
        // ShaderBrush.applyTo sets black, alpha=1, no color filter, and SrcOver blending.
        color = Color.BLACK
        shader = ReverbNoiseTexture.shader(seed)
    }
    private var drawsBackground = backgroundColor != null
    private var drawableAlpha = 255
    private var outputColorFilter: ColorFilter? = null
    private var compositePaint: Paint? = null

    init {
        backgroundColor?.let { backgroundPaint.color = it }
        ReverbNoiseTexture.observe(this)
    }

    fun setBackgroundColor(@ColorInt color: Int?) {
        if (drawsBackground == (color != null) && (color == null || backgroundPaint.color == color)) return
        drawsBackground = color != null
        color?.let { backgroundPaint.color = it }
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || drawableAlpha == 0) return
        val checkpoint = canvas.save()
        try {
            // Never move the cached shader's local matrix: other same-seed instances share it.
            // Compose's modifier also starts at its own local origin rather than the window origin.
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            if (drawableAlpha != 255 || outputColorFilter != null) {
                // Optional Drawable-level effects apply once to the composed fill+noise. Applying
                // alpha independently to both paints would change translucent overlap pixels.
                val paint = compositePaint ?: Paint(PAINT_FLAGS).also { compositePaint = it }
                paint.alpha = drawableAlpha
                paint.colorFilter = outputColorFilter
                canvas.saveLayer(0f, 0f, width, height, paint)
            }
            if (drawsBackground) canvas.drawRect(0f, 0f, width, height, backgroundPaint)
            if (noisePaint.shader == null) noisePaint.shader = ReverbNoiseTexture.shader(seed)
            // First launch may draw before background generation finishes. Keep the exact base
            // color, with no launch gate/fade; completion invalidates this drawable once.
            if (noisePaint.shader != null) canvas.drawRect(0f, 0f, width, height, noisePaint)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    override fun setAlpha(alpha: Int) {
        val next = alpha.coerceIn(0, 255)
        if (drawableAlpha == next) return
        drawableAlpha = next
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        if (outputColorFilter === colorFilter) return
        outputColorFilter = colorFilter
        invalidateSelf()
    }

    override fun getColorFilter(): ColorFilter? = outputColorFilter

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val SEED_BACKGROUND = 0
        const val SEED_TOP_BAR = 0x5A17
        private const val PAINT_FLAGS =
            Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
    }

}
