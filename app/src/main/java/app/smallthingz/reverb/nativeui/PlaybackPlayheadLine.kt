package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import app.smallthingz.reverb.RANGE_WAVEFORM_SETTLED_ENVELOPE_FRACTION
import kotlin.math.min
import smallthingz.ui.animation.view.ViewColorTransition

/** Display-only sampled position; editable range/trim handles stay in separate children above it. */
internal class PlaybackPlayheadLine(context: Context, private var environment: ReverbViewEnvironment) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fraction = 0f
    private var shown = false
    private var colorMotion: ViewColorTransition? = null
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }
    fun bind(value: ReverbViewEnvironment, shown: Boolean) {
        environment = value
        this.shown = shown
        val color = value.palette.onSurface
        val motion = colorMotion ?: ViewColorTransition(this, intArrayOf(color), {
            colorMotion?.let { paint.color = it.color(0); invalidate() }
        }).also { colorMotion = it; paint.color = color }
        motion.retarget(color)
        invalidate()
    }
    fun setPosition(value: Float) {
        if (fraction == value) return
        fraction = value
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        if (!shown || width <= 0 || height <= 0) return
        val stroke = min(width.toFloat(), environment.textDensity.density)
        val inset = stroke * .5f
        val x = (width * fraction).coerceIn(inset, width - inset)
        val lineHeight = height * (RANGE_WAVEFORM_SETTLED_ENVELOPE_FRACTION + .02f)
        val top = (height - lineHeight) * .5f
        paint.strokeWidth = stroke
        paint.alpha = 160
        canvas.drawLine(x, top, x, height - top, paint)
    }
}
