package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import app.smallthingz.reverb.RangeWaveformPass
import kotlin.math.round
import kotlin.math.roundToInt

internal data class ProgressiveWaveformPalette(
    @ColorInt val primary: Int,
    @ColorInt val onSurfaceVariant: Int,
    @ColorInt val tertiary: Int,
)

/**
 * Native rendering counterpart of ProgressiveWaveformCanvas at v0.1.2.
 *
 * The host supplies the measured bounds and each animation frame. This View owns no sampling,
 * playback, clock, animation, input gesture or accessibility action. The timeline/player host
 * supplies those, including the source-to-timeline transform for a blob morph. Keeping samples,
 * palette and frame updates separate avoids copying waveform arrays on animation frames.
 *
 * Palette inputs are the same sRGB ARGB values as the released Material color scheme. Drawing
 * covers the full measured bounds, matching Compose Canvas rather than adding View padding.
 */
internal class ProgressiveWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val waveformPath = Path()
    private val contour = ProgressiveWaveformContourCache()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var waveform: ProgressiveWaveformData? = null
    private var frame: ProgressiveWaveformFrame? = null
    private var palette: ProgressiveWaveformPalette? = null
    private var builtShader: Shader? = null
    private var selectedShader: Shader? = null
    private var selectedLightShader: Shader? = null
    private var unresolvedShader: Shader? = null

    init {
        // Interactive semantics belong to the encompassing timeline, as in the Compose Canvas.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Retire the previous source with null before loading a new one; the initial blob oval remains available. */
    fun setWaveform(value: ProgressiveWaveformData?) {
        if (waveform === value) return
        waveform = value
        if (value != null) contour.prepare(value.detail.size) else contour.clear()
        invalidate()
    }

    fun setFrame(value: ProgressiveWaveformFrame) {
        if (frame == value) return
        frame = value
        invalidate()
    }

    fun setPalette(value: ProgressiveWaveformPalette) {
        if (palette == value) return
        palette = value
        rebuildShaders()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    private fun rebuildShaders() {
        val colors = palette ?: return
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        builtShader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(
                withAlpha(colors.primary, 0.24f),
                withAlpha(colors.primary, 0.32f),
                withAlpha(colors.primary, 0.24f),
            ),
            null, Shader.TileMode.CLAMP,
        )
        selectedShader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(
                withAlpha(colors.primary, 0.94f),
                colors.primary,
                withAlpha(colors.primary, 0.94f),
            ),
            null, Shader.TileMode.CLAMP,
        )
        selectedLightShader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                withAlpha(Color.WHITE, 0.18f),
                Color.TRANSPARENT,
                withAlpha(colors.primary, 0.10f),
            ),
            null, Shader.TileMode.CLAMP,
        )
        unresolvedShader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(
                withAlpha(colors.primary, 0.08f),
                withAlpha(colors.primary, 0.14f),
                withAlpha(colors.primary, 0.08f),
            ),
            null, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val colors = palette ?: return
        val current = frame ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val density = resources.displayMetrics.density
        val morph = current.morphProgress.coerceIn(0f, 1f)
        if (current.morphFromBlob && morph <= 0f) {
            solidPaint(colors.primary)
            canvas.drawOval(0f, 0f, w, h, paint)
            return
        }
        val data = waveform ?: return
        val availableCoarse = current.visibleCoarseFraction
        val availableDetail = minOf(current.visibleDetailFraction, availableCoarse)
        val coarseGate = waveformSmoothStep(((morph - 0.56f) / 0.16f).coerceIn(0f, 1f))
        val detailGate = waveformSmoothStep(((morph - 0.86f) / 0.12f).coerceIn(0f, 1f))
        val effectiveVisibleCoarse = availableCoarse * coarseGate
        val effectiveVisibleDetail = minOf(availableDetail * detailGate, effectiveVisibleCoarse)
        val contourChanged = contour.update(
            data = data,
            visibleCoarseFraction = effectiveVisibleCoarse,
            visibleDetailFraction = effectiveVisibleDetail,
            morphProgress = morph,
            phase = current.wobblePhaseSeconds,
            width = w,
            height = h,
        )
        val pointCount = contour.pointCount
        if (pointCount == 0) return

        val centerY = h * 0.5f
        if (contourChanged) {
            waveformPath.rewind()
            val renderDenominator = (pointCount - 1).coerceAtLeast(1).toFloat()
            for (point in 0 until pointCount) {
                val x = w * point.toFloat() / renderDenominator
                val y = centerY - contour.amplitudeAt(point)
                if (point == 0) waveformPath.moveTo(x, y) else waveformPath.lineTo(x, y)
            }
            for (point in pointCount - 1 downTo 0) {
                val x = w * point.toFloat() / renderDenominator
                waveformPath.lineTo(x, centerY + contour.amplitudeAt(point))
            }
            waveformPath.close()
        }

        val builtRight = w * effectiveVisibleCoarse.coerceIn(0f, 1f)
        val detailRight = w * effectiveVisibleDetail.coerceIn(0f, 1f)
        val selectedLeft = w * current.startFraction.coerceIn(0f, 1f)
        val selectedRight = w * current.endFraction.coerceIn(current.startFraction, 1f)
        val materialT = waveformSmoothStep(((morph - 0.06f) / 0.82f).coerceIn(0f, 1f))
        val finalLayerAlpha = if (!current.morphFromBlob) {
            1f
        } else {
            waveformSmoothStep(((morph - 0.78f) / 0.22f).coerceIn(0f, 1f))
        }
        line(
            canvas,
            withAlpha(colors.onSurfaceVariant, if (!current.morphFromBlob) 0.10f else 0.10f * materialT),
            0f, centerY, w, centerY, density,
        )

        // The unresolved material owns only the unbuilt suffix, never the selected/dim prefix.
        if (builtRight < w) {
            val save = canvas.save()
            canvas.clipRect(builtRight.coerceAtLeast(0f), 0f, w, h)
            if (current.morphFromBlob) {
                solidPaint(colors.primary)
                canvas.drawPath(waveformPath, paint)
            } else {
                shaderPath(canvas, unresolvedShader, 1f)
            }
            strokePath(
                canvas,
                if (current.morphFromBlob) Color.TRANSPARENT else withAlpha(colors.primary, 0.08f),
                density,
            )
            canvas.restoreToCount(save)
        }

        if (builtRight > 0f && finalLayerAlpha > 0.01f) {
            val save = canvas.save()
            canvas.clipRect(0f, 0f, builtRight, h)
            shaderPath(canvas, builtShader, finalLayerAlpha)
            strokePath(canvas, withAlpha(colors.primary, 0.16f * finalLayerAlpha), 1.25f * density)
            val selectedSave = canvas.save()
            canvas.clipRect(selectedLeft, 0f, selectedRight, h)
            shaderPath(canvas, selectedShader, finalLayerAlpha)
            shaderPath(canvas, selectedLightShader, finalLayerAlpha)
            strokePath(canvas, withAlpha(colors.primary, 0.24f * finalLayerAlpha), 1.7f * density)
            canvas.restoreToCount(selectedSave)
            canvas.restoreToCount(save)
        }

        if (finalLayerAlpha > 0.01f && effectiveVisibleCoarse in 0.002f..0.998f) {
            line(
                canvas, withAlpha(colors.primary, 0.12f * finalLayerAlpha),
                builtRight, h * 0.10f, builtRight, h * 0.90f, 13f * density, round = true,
            )
            line(
                canvas, withAlpha(Color.WHITE, 0.36f * finalLayerAlpha),
                builtRight, h * 0.13f, builtRight, h * 0.87f, 1.15f * density, round = true,
            )
        }
        if (
            finalLayerAlpha > 0.01f &&
            data.pass == RangeWaveformPass.DETAIL &&
            effectiveVisibleDetail in 0.002f..0.998f &&
            effectiveVisibleCoarse > 0.95f
        ) {
            line(
                canvas, withAlpha(colors.tertiary, 0.13f * finalLayerAlpha),
                detailRight, h * 0.13f, detailRight, h * 0.87f, 9f * density, round = true,
            )
            line(
                canvas, withAlpha(Color.WHITE, 0.28f * finalLayerAlpha),
                detailRight, h * 0.16f, detailRight, h * 0.84f, 0.9f * density, round = true,
            )
        }
    }

    private fun solidPaint(@ColorInt color: Int) {
        paint.shader = null
        paint.color = color
        paint.style = Paint.Style.FILL
    }

    private fun shaderPath(canvas: Canvas, shader: Shader?, alpha: Float) {
        paint.color = Color.BLACK
        paint.alpha = round(alpha * 255f).toInt()
        paint.shader = shader
        paint.style = Paint.Style.FILL
        canvas.drawPath(waveformPath, paint)
    }

    private fun strokePath(canvas: Canvas, @ColorInt color: Int, width: Float) {
        solidPaint(color)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeMiter = 4f
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
        canvas.drawPath(waveformPath, paint)
    }

    private fun line(
        canvas: Canvas,
        @ColorInt color: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        width: Float,
        round: Boolean = false,
    ) {
        solidPaint(color)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT
        canvas.drawLine(startX, startY, endX, endY, paint)
    }
}

@ColorInt
private fun withAlpha(@ColorInt color: Int, alpha: Float): Int =
    (color and 0x00FFFFFF) or ((alpha * 255f).roundToInt() shl 24)
