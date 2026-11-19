package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import app.smallthingz.reverb.R
import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.view.ViewColorTransition
import smallthingz.ui.animation.view.ViewSpringDriver
import smallthingz.ui.components.NativeButtonInputPolicy
import smallthingz.ui.components.NativeMaterialButtonStyle
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.toArgb
import smallthingz.ui.text.view.ParagraphView
import kotlin.math.max
import kotlin.math.roundToInt

/** Shared action with opt-in filled calibration progress; Initial gesture owns the stable outer box. */
internal class SettingsCalibrationActionView(
    context:Context,
    environment:ReverbViewEnvironment,
    inputRoot:()->View?,
    leadingIcon:ReverbIcon?=null,
    private val stableLabels:List<String> = emptyList(),
    private val progressSurface:Boolean=false,
    action:()->Unit,
):ViewGroup(context),AutoCloseable {
    private var environment=environment
    private val visual=CalibrationActionSurface(context,environment,inputRoot,leadingIcon,progressSurface,action)
    private val sizingLabel=if(stableLabels.isEmpty())null else ParagraphView(context,reverbButtonTextStyle(),
        environment.textDensity,environment.sharedFontResolver,environment.palette.primary)
    val textView get()=visual.label
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren=false;clipToPadding=false;addView(visual)
    }
    fun bind(text:String,enabled:Boolean,next:ReverbViewEnvironment) {
        environment=next;visual.bind(text,enabled,next)
    }
    fun bindProgress(progress:Float?,running:Boolean) = visual.bindProgress(progress,running)
    override fun onMeasure(w:Int,h:Int) {
        visual.measure(w,h)
        val minimum=(48f*environment.textDensity.density).roundToInt()
        var stableHeight=minimum
        sizingLabel?.let {label->
            label.setTypography(reverbButtonTextStyle(),environment.textDensity,environment.sharedFontResolver)
            val padding=2*(12f*environment.textDensity.density).roundToInt()
            val textWidth=if(MeasureSpec.getMode(w)==MeasureSpec.UNSPECIFIED)unspecified()
                else atMost((MeasureSpec.getSize(w)-padding).coerceAtLeast(0))
            stableLabels.forEach {text->
                label.setText(text);label.measure(textWidth,unspecified())
                stableHeight=max(stableHeight,label.measuredHeight+2*(8f*environment.textDensity.density).roundToInt())
            }
        }
        val measuredW=resolveSize(max(minimum,visual.measuredWidth),w)
        val measuredH=resolveSize(max(stableHeight,visual.measuredHeight),h)
        if(progressSurface)visual.measure(exact(measuredW),exact(measuredH))
        setMeasuredDimension(measuredW,measuredH)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val x=((width-visual.measuredWidth)/2f).roundToInt()
        val y=((height-visual.measuredHeight)/2f).roundToInt()
        visual.layout(x,y,x+visual.measuredWidth,y+visual.measuredHeight)
    }
    override fun close()=visual.close()
}

private class CalibrationActionSurface(
    context: Context,
    private var environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    private val leadingIcon: ReverbIcon?,
    private val progressSurface: Boolean,
    action: () -> Unit,
) : NativeMaterialButtonView(context, false, reverbButtonTextStyle(), environment.textDensity,
    environment.sharedFontResolver, NativeMaterialButtonStyle(12, iconSizeDp = 20, iconGapDp = 8),
    NativeButtonInputPolicy(inputRoot), action) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress: Float? = null
    private var running = false
    private var closed = false
    private val fill = calibrationProgressSpring()
    private var fillDriver: ViewSpringDriver<Float, AnimationVector1D>? = null
    private var heldPulse: ValueAnimator? = null
    private var heldOpacity = .7f
    private val progressColors = if (progressSurface) ViewColorTransition(this,
        intArrayOf(progressTint()), { invalidate() }) else null

    init { bindIcon() }

    private fun bindIcon() {
        leadingIcon?.let { setLeadingIcon(ReverbIconDrawable(it, environment.textDensity.density, environment.palette.primary)) }
    }

    fun bind(text: String, enabled: Boolean, next: ReverbViewEnvironment) {
        val densityChanged = environment.textDensity.density != next.textDensity.density
        environment = next
        if (densityChanged) bindIcon()
        bindTypography(reverbButtonTextStyle(), next.textDensity, next.sharedFontResolver)
        bindColors(text, enabled)
    }

    private fun progressTint() = Color(environment.palette.primary).copy(alpha = .22f).toArgb()

    private fun bindColors(text: String, enabled: Boolean) {
        val palette = environment.palette
        val ink = when {
            progressSurface && (enabled || running) -> palette.ink
            enabled -> palette.primary
            else -> Color(palette.onSurfaceVariant).copy(alpha = .38f).toArgb()
        }
        val container = if (progressSurface) Color(palette.primary)
            .copy(alpha = if (enabled || running) .12f else .06f).toArgb() else 0
        bind(text, enabled, container, ink)
        progressColors?.retarget(progressTint())
    }

    fun bindProgress(value: Float?, active: Boolean) {
        val starting = active && !running
        progress = value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        running = active
        if (starting) {
            stopMotion()
            fill.snapTo(0f)
            heldOpacity = .7f
        }
        updateMotion()
        bindColors(label.getText(), isEnabled)
        ViewCompat.setStateDescription(this, if (active) progressDescription() else null)
        invalidate()
    }

    private fun progressDescription() = progress?.let { "${(it * 100).roundToInt()}%" }
        ?: context.getString(R.string.silence_sampling)

    private fun stopMotion() {
        fillDriver?.close()
        fillDriver = null
        heldPulse?.cancel()
        heldPulse = null
    }

    private fun updateMotion() {
        if (closed || !isAttachedToWindow || !isShown || windowVisibility != VISIBLE || !running) {
            stopMotion()
            fill.snapTo(progress ?: 0f)
            return
        }
        val target = progress
        if (target == null) {
            fillDriver?.close()
            fillDriver = null
            if (heldPulse == null && ValueAnimator.areAnimatorsEnabled()) {
                heldPulse = ValueAnimator.ofFloat(.45f, 1f).apply {
                    duration = 900L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { heldOpacity = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        } else {
            heldPulse?.cancel()
            heldPulse = null
            val driver = fillDriver ?: ViewSpringDriver(this, fill, { invalidate() }).also { fillDriver = it }
            driver.retarget(target)
        }
    }

    override fun drawButtonOverlay(canvas: Canvas, clip: Path) {
        if (!running || progressColors == null) return
        val checkpoint = canvas.save()
        try {
            canvas.clipPath(clip)
            paint.color = progressColors.color(0)
            val fraction = if (progress == null) {
                paint.alpha = (paint.alpha * heldOpacity).roundToInt()
                1f
            } else fill.value.coerceIn(0f, 1f)
            if (layoutDirection == LAYOUT_DIRECTION_RTL) {
                canvas.drawRect(width * (1f - fraction), 0f, width.toFloat(), height.toFloat(), paint)
            } else canvas.drawRect(0f, 0f, width * fraction, height.toFloat(), paint)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? = super.createAccessibilityNodeInfo()?.also { node ->
        if (progressSurface && running) {
            AccessibilityNodeInfoCompat.wrap(node).stateDescription = progressDescription()
            progress?.let { node.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 1f, it) }
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateMotion() }
    override fun onDetachedFromWindow() { stopMotion(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) updateMotion()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) updateMotion()
    }
    override fun close() {
        if (closed) return
        closed = true
        stopMotion()
        progressColors?.close()
        super.close()
    }
}
