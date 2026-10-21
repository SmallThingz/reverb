package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/**
 * The three released MainScreen Library Boxes, with the same transform/alpha ordering.
 * Background noise belongs below statusTop + AppTopBarContentHeight, not the whole overlay.
 */
internal class NativeMainLibraryLayer(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    val content: ReverbLibraryScreenContent,
) : ViewGroup(context) {
    private var environment = initialEnvironment
    private var statusTop = 0
    private var contentTop = 0
    private var progress = 0f
    private val scrim = View(context)
    private val translated = object : ViewGroup(context) {
        init { clipChildren = false; clipToPadding = false }
        // Parent owns these two source Box placements; do not measure the content twice.
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
    }
    private val backgroundPanel = View(context)
    private val noise = ReverbNoiseDrawable(resources, environment.palette.surface)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        scrim.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        translated.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        backgroundPanel.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        backgroundPanel.background = noise
        scrim.setBackgroundColor(reverbColorWithAlpha(environment.palette.scrim, .32f))
        addView(scrim)
        addView(translated)
        translated.addView(backgroundPanel)
        translated.addView(content)
    }

    fun bind(environment: ReverbViewEnvironment, statusTop: Int, progress: Float, visible: Boolean) {
        if (this.environment !== environment) {
            this.environment = environment
            noise.setBackgroundColor(environment.palette.surface)
            scrim.setBackgroundColor(reverbColorWithAlpha(environment.palette.scrim, .32f))
        }
        val nextContentTop = statusTop + (66f * environment.textDensity.density).roundToInt()
        if (this.statusTop != statusTop || contentTop != nextContentTop) {
            this.statusTop = statusTop
            contentTop = nextContentTop
            requestLayout()
        }
        this.progress = progress
        alpha = if (progress > 0f || visible) 1f else .01f
        applyMotion()
    }

    private fun applyMotion() {
        scrim.alpha = progress
        translated.translationY = (1f - progress) * height
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        scrim.measure(exact(width), exact((height - contentTop).coerceAtLeast(0)))
        translated.measure(exact(width), exact(height))
        backgroundPanel.measure(exact(width), exact((height - contentTop).coerceAtLeast(0)))
        content.measure(exact(width), exact((height - statusTop).coerceAtLeast(0)))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        scrim.layout(0, contentTop, width, contentTop + scrim.measuredHeight)
        translated.layout(0, 0, width, height)
        backgroundPanel.layout(0, contentTop, width, contentTop + backgroundPanel.measuredHeight)
        content.layout(0, statusTop, width, statusTop + content.measuredHeight)
        applyMotion()
    }

    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
}
