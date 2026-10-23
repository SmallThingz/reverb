/*
 * Derived from AndroidX Material3 1.4.0 PullToRefresh.kt, Copyright 2023 The Android
 * Open Source Project, Apache-2.0. See pull-refresh/SOURCE.md and incidents/APACHE-2.0.txt.
 */
package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.animation.core.Animatable
import smallthingz.ui.animation.core.spring
import smallthingz.ui.runtime.MonotonicFrameClock
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Always-present40dp top-center overlay used by the released Library PullToRefreshBox.
 * Parent places this above the list/status, below Undo, with unclipped horizontal shadow overflow.
 * It owns only its source node/indicator lifetime; caller closes the longer-lived refresh owner.
 */
internal class ReverbLibraryPullRefreshIndicator(
    context: Context,
    private var environment: ReverbViewEnvironment,
    private val owner: NativeLibraryPullRefreshOwner,
    private val frames: MonotonicFrameClock,
    private val parentScope: CoroutineScope,
    private val durationScale: () -> Float,
) : ViewGroup(context) {
    private val density = environment.textDensity.density
    private val surface = IndicatorSurface(context, density)
    private var scope: CoroutineScope? = null
    private var observation: AutoCloseable? = null
    private var transition: Job? = null
    private var transitionGeneration = 0L
    private var targetRefreshing = owner.isRefreshing
    private var currentRefreshing = owner.isRefreshing
    private var arrow: PullArrowView? = null
    private var spinner: ReverbIncidentLoadingView? = null
    private var arrowFade: Animatable<Float, smallthingz.ui.animation.core.AnimationVector1D>? = null
    private var spinnerFade: Animatable<Float, smallthingz.ui.animation.core.AnimationVector1D>? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        addView(surface)
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    fun bindPalette(palette: ReverbPalette) {
        environment = ReverbViewEnvironment(environment.textDensity, environment.sharedFontResolver, palette)
        surface.color = palette.surfaceContainerHigh
        arrow?.setColor(palette.onSurfaceVariant)
        spinner?.setColor(palette.onSurfaceVariant)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val attached = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))
        scope = attached
        targetRefreshing = owner.isRefreshing
        currentRefreshing = targetRefreshing
        ensureContent(targetRefreshing, 1f)
        observation = owner.observeVisual(::bindVisual)
        owner.attach()
        bindPalette(environment.palette)
        bindVisual()
    }

    override fun onDetachedFromWindow() {
        observation?.close()
        observation = null
        owner.detach()
        transitionGeneration++
        transition = null
        scope?.cancel()
        scope = null
        surface.removeAllViews()
        arrow = null; spinner = null; arrowFade = null; spinnerFade = null
        super.onDetachedFromWindow()
    }

    private fun ensureContent(refreshing: Boolean, alpha: Float) {
        val scope = checkNotNull(scope)
        if (refreshing && spinner == null) {
            spinnerFade = Animatable(alpha)
            spinner = ReverbIncidentLoadingView(context, environment.palette.onSurfaceVariant,
                frames, scope, durationScale, strokeWidthDp = 2.5f).also {
                it.alpha = alpha
                surface.addView(it)
            }
        } else if (!refreshing && arrow == null) {
            arrowFade = Animatable(alpha)
            arrow = PullArrowView(context, density, environment.palette.onSurfaceVariant,
                owner.distanceFraction, scope).also {
                it.alpha = alpha
                surface.addView(it)
            }
        }
    }

    private fun bindVisual() {
        val attached = scope ?: return
        surface.translationY = owner.distanceFraction * owner.thresholdPx - surface.height
        surface.elevation = if (owner.distanceFraction > 0f || owner.isRefreshing) 3f * density else 0f
        arrow?.bindProgress(owner.distanceFraction)
        if (targetRefreshing == owner.isRefreshing) return
        targetRefreshing = owner.isRefreshing
        ensureContent(targetRefreshing, 0f)
        val generation = ++transitionGeneration
        // Transition.animateFloat preserves a child's delivered value/velocity when retargeting.
        val arrowVelocity = arrowFade?.velocity ?: 0f
        val spinnerVelocity = spinnerFade?.velocity ?: 0f
        transition?.cancel()
        transition = attached.launch {
            coroutineScope {
                listOfNotNull(
                    arrowFade?.let { animation -> launch {
                        animation.animateTo(if (targetRefreshing) 0f else 1f, DefaultEffects,
                            initialVelocity = arrowVelocity) { arrow?.alpha = value }
                    } },
                    spinnerFade?.let { animation -> launch {
                        animation.animateTo(if (targetRefreshing) 1f else 0f, DefaultEffects,
                            initialVelocity = spinnerVelocity) { spinner?.alpha = value }
                    } },
                ).joinAll()
            }
            if (generation == transitionGeneration) {
                currentRefreshing = targetRefreshing
                if (currentRefreshing) {
                    arrow?.let(surface::removeView); arrow = null; arrowFade = null
                } else {
                    spinner?.let(surface::removeView); spinner = null; spinnerFade = null
                }
            }
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val size = (40f * density).roundToInt()
        val width = resolveSize(size, w)
        val height = resolveSize(size, h)
        surface.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        surface.layout(0, 0, width, height)
        bindVisual()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        // Source IndicatorBox clips only above its original top, including translated shadow.
        canvas.clipRect(-Float.MAX_VALUE, 0f, Float.MAX_VALUE, Float.MAX_VALUE)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    private companion object {
        val DefaultEffects = spring<Float>(dampingRatio = 1f, stiffness = 1600f)
    }
}

private class IndicatorSurface(context: Context, private val density: Float) : ViewGroup(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()
    private val clip = Path()
    var color: Int
        get() = paint.color
        set(value) { paint.color = value; invalidate() }
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false; clipToPadding = false
        setWillNotDraw(false)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, min(view.width, view.height) / 2f)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            outlineAmbientShadowColor = Color.BLACK
            outlineSpotShadowColor = Color.BLACK
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.rewind()
        clip.addRoundRect(bounds, min(w, h) / 2f, min(w, h) / 2f, Path.Direction.CW)
        invalidateOutline()
    }
    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) super.draw(canvas)
        else {
            val save = canvas.save(); canvas.clipPath(clip)
            super.draw(canvas); canvas.restoreToCount(save)
        }
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(bounds, min(width, height) / 2f, min(width, height) / 2f, paint)
    }
    override fun onMeasure(w: Int, h: Int) {
        val size = (16f * density).roundToInt()
        for (i in 0 until childCount) getChildAt(i).measure(
            MeasureSpec.makeMeasureSpec(size.coerceAtMost(MeasureSpec.getSize(w)), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size.coerceAtMost(MeasureSpec.getSize(h)), MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val x = ((width - child.measuredWidth) / 2f).roundToInt()
            val y = ((height - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }
}

private class PullArrowView(
    context: Context,
    private val density: Float,
    private var color: Int,
    initialProgress: Float,
    private val scope: CoroutineScope,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = 2.5f * density
    }
    private val bounds = RectF()
    private val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private var progress = initialProgress
    private var values = nativePullRefreshArrow(progress)
    private var targetAlpha = if (progress >= 1f) 1f else .3f
    private val inkAlpha = Animatable(targetAlpha)
    private var inkJob: Job? = null
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this, true)
    }

    fun setColor(value: Int) { color = value; invalidate() }

    fun bindProgress(value: Float) {
        if (progress == value) return
        progress = value
        values = nativePullRefreshArrow(value)
        val alpha = if (value >= 1f) 1f else .3f
        if (targetAlpha != alpha) {
            targetAlpha = alpha
            // Animatable's mutator performs source cancellation with its current velocity.
            inkJob = scope.launch {
                inkAlpha.animateTo(alpha, spring(dampingRatio = 1f, stiffness = 1600f)) { invalidate() }
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        inkJob?.cancel()
        inkJob = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = 2.5f * density
        val radius = 5.5f * density + stroke / 2f
        val cx = width / 2f
        val cy = height / 2f
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.color = (color and 0x00ffffff) or
            (((Color.alpha(color) * inkAlpha.value + .5f).toInt().coerceIn(0, 255)) shl 24)
        val save = canvas.save()
        canvas.rotate(values.rotation, cx, cy)
        canvas.drawArc(bounds, values.startAngle, values.endAngle - values.startAngle, false, paint)
        val arrowWidth = 10f * density * values.scale
        val arrowHeight = 5f * density * values.scale
        val x = radius + cx - arrowWidth / 2f
        val y = cy - stroke
        path.rewind()
        path.moveTo(x, y)
        path.lineTo(x + arrowWidth / 2f, y + arrowHeight)
        path.lineTo(x + arrowWidth, y)
        canvas.rotate(values.endAngle - stroke, cx, cy)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }

    override fun getAccessibilityClassName(): CharSequence =
        if (progress > 0f) "android.widget.ProgressBar" else View::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = accessibilityClassName
        if (progress > 0f) {
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 1f, progress)
            val normalized = progress.coerceIn(0f, 1f)
            val percent = when (normalized) {
                0f -> 0
                1f -> 100
                else -> (normalized * 100f).roundToInt().coerceIn(1, 99)
            }
            AccessibilityNodeInfoCompat.wrap(info).stateDescription =
                context.getString(smallthingz.ui.R.string.template_percent, percent)
        }
    }
}
