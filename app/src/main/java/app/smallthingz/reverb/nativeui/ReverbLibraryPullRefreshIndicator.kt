/*
 * Derived from AndroidX Material3 1.4.0 PullToRefresh.kt, Copyright 2023 The Android
 * Open Source Project, Apache-2.0. See pull-refresh/SOURCE.md and incidents/APACHE-2.0.txt.
 */
package app.smallthingz.reverb.nativeui

import android.content.Context
import smallthingz.ui.components.NativeBoundsClipLayout
import smallthingz.ui.components.NativePullRefreshArrowView
import smallthingz.ui.components.NativePullRefreshSurface
import smallthingz.ui.animation.core.Animatable
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
) : NativeBoundsClipLayout(context, topOnly = true) {
    private val density get() = environment.textDensity.density
    private val style get() = environment.appearance.listDecorations.refresh
    private val surface = NativePullRefreshSurface(context, density, style, style.fill(environment.palette))
    private var scope: CoroutineScope? = null
    private var observation: AutoCloseable? = null
    private var transition: Job? = null
    private var transitionGeneration = 0L
    private var targetRefreshing = owner.isRefreshing
    private var currentRefreshing = owner.isRefreshing
    private var arrow: NativePullRefreshArrowView? = null
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

    fun bindEnvironment(value: ReverbViewEnvironment) {
        val metricsChanged = density != value.textDensity.density || style != value.appearance.listDecorations.refresh
        environment = value
        surface.bindStyle(density, style)
        surface.color = style.fill(value.palette)
        val tint = style.tint(value.palette)
        arrow?.let {
            it.bindStyle(density, style)
            it.setColor(tint)
        }
        spinner?.setStrokeWidthDp(style.strokeDp)
        spinner?.setColor(tint)
        if (metricsChanged) requestLayout()
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
        bindEnvironment(environment)
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
            spinner = ReverbIncidentLoadingView(context, style.tint(environment.palette),
                frames, scope, durationScale, strokeWidthDp = style.strokeDp).also {
                it.alpha = alpha
                surface.addView(it)
            }
        } else if (!refreshing && arrow == null) {
            arrowFade = Animatable(alpha)
            arrow = NativePullRefreshArrowView(context, density, style, style.tint(environment.palette),
                owner.distanceFraction, scope).also {
                it.alpha = alpha
                surface.addView(it)
            }
        }
    }

    private fun bindVisual() {
        val attached = scope ?: return
        surface.translationY = owner.distanceFraction * owner.thresholdPx - surface.height
        surface.elevation = if (owner.distanceFraction > 0f || owner.isRefreshing) style.elevationDp * density else 0f
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
                        animation.animateTo(if (targetRefreshing) 0f else 1f, style.effects,
                            initialVelocity = arrowVelocity) { arrow?.alpha = value }
                    } },
                    spinnerFade?.let { animation -> launch {
                        animation.animateTo(if (targetRefreshing) 1f else 0f, style.effects,
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
        val size = (style.diameterDp * density).roundToInt()
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
}
