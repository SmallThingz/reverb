/*
 * Default indeterminate LinearProgressIndicator from AndroidX Material3 1.4.0.
 * Copyright The Android Open Source Project. Apache-2.0.
 */
package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import smallthingz.ui.animation.core.withInfiniteAnimationFrameNanos
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Rect
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * ClearBufferProgressSheet's default indeterminate overload. Its owner mounts/removes this View
 * with the progress-null source branch, rather than keeping an invisible extra animation alive.
 * Offscreen clipping/alpha do not invent a new animation lifetime; the inherited window clock owns
 * lifecycle admission. Rendering, native node bounds and actual frame phase remain unqualified.
 */
internal class ReverbIndeterminateLinearProgressView(
    context: Context,
    private var environment: ReverbViewEnvironment,
    private val frames: MonotonicFrameClock,
    private val scope: CoroutineScope,
    private val durationScale: () -> Float,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val motion = NativeIndeterminateLinearMotion()
    private var frameJob: Job? = null
    private var scaleChanges: Channel<Unit>? = null
    private var scaleListener: AutoCloseable? = null
    private var geometry: NativePlainTextViewGeometry? = null
    private var exposure: (() -> Boolean)? = null
    private var geometryToken: Any? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this, true)
        isFocusable = false
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    fun bindEnvironment(next: ReverbViewEnvironment) {
        if (environment === next) return
        val densityChanged = environment.textDensity.density != next.textDensity.density
        environment = next
        if (densityChanged) requestLayout()
        invalidate()
    }

    /** Required wake-up for a caller-supplied duration-scale change not backed by ValueAnimator. */
    fun motionScaleChanged() { scaleChanges?.trySend(Unit) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        motion.reset()
        val wake = Channel<Unit>(Channel.CONFLATED)
        scaleChanges = wake
        try {
            scaleListener = if (Build.VERSION.SDK_INT >= 33) {
                LinearDurationScaleListenerApi33.register { wake.trySend(Unit) }
            } else {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) { wake.trySend(Unit) }
                }
                context.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
                AutoCloseable { context.contentResolver.unregisterContentObserver(observer) }
            }
            frameJob = scope.launch(frames) {
                while (isActive) {
                    var sampledScale = 1f
                    // Reuse the inherited clock and any installed infinite-animation policy.
                    withInfiniteAnimationFrameNanos { time ->
                        sampledScale = durationScale()
                        motion.frame(time, sampledScale)
                        invalidate()
                    }
                    if (sampledScale == 0f) {
                        // InfiniteTransition suspends after drawing all four target values.
                        // No busy loop, per-view timer or unscaled platform animator is introduced.
                        while (durationScale() == 0f) wake.receive()
                    }
                }
            }
        } catch (failure: Throwable) {
            try { releaseAnimation() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    override fun onDetachedFromWindow() {
        try { releaseAnimation() } finally { super.onDetachedFromWindow() }
    }

    private fun releaseAnimation() {
        frameJob?.cancel()
        frameJob = null
        scaleChanges?.close()
        scaleChanges = null
        val listener = scaleListener
        scaleListener = null
        motion.reset()
        listener?.close()
    }

    override fun onMeasure(w: Int, h: Int) {
        val density = environment.textDensity.density
        setMeasuredDimension(resolveSize((240f * density).roundToInt(), w),
            resolveSize((4f * density).roundToInt(), h))
    }

    override fun onDraw(canvas: Canvas) {
        val colors = environment.palette
        nativeIndeterminateLinearSegments(width.toFloat(), height.toFloat(), environment.textDensity.density,
            motion.firstHead, motion.firstTail, motion.secondHead, motion.secondTail) { start, end, active ->
            paint.color = if (active) colors.primary else colors.secondaryContainer
            paint.strokeWidth = height.toFloat()
            paint.style = Paint.Style.STROKE
            nativeLinearProgressLine(width.toFloat(), height.toFloat(),
                layoutDirection == LAYOUT_DIRECTION_RTL, start, end) { a, b, y, round ->
                paint.strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT
                canvas.drawLine(a, y, b, y, paint)
            }
        }
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        invalidate()
    }

    /** IncreaseVerticalSemanticsBounds applies 10 dp negative padding before clipping/mapping. */
    fun semanticsBoundsInView(): Rect {
        val padding = (10f * environment.textDensity.density).roundToInt().toFloat()
        return Rect(0f, -padding, width.toFloat(), height + padding)
    }

    fun bindAccessibility(value: NativePlainTextViewGeometry, exposed: () -> Boolean): AutoCloseable {
        val token = Any()
        geometryToken = token
        geometry = value
        exposure = exposed
        return AutoCloseable {
            if (geometryToken === token) { geometryToken = null; geometry = null; exposure = null }
        }
    }

    private fun sourceNode(): NativeSourceAccessibilityNode? {
        val mapping = geometry ?: return null
        val captured = mapping.capture(this, Offset.Zero, semanticsBoundsInView())
        val bounds = captured?.let { nativeMergedRoleBoundsInScreen(it, isLaidOut) } ?: android.graphics.Rect()
        var hidden = exposure?.invoke() != true
        var ancestor: View? = this
        while (ancestor != null) {
            if (ancestor.alpha == 0f || ancestor.visibility != VISIBLE) hidden = true
            ancestor = ancestor.parent as? View
        }
        val present = isAttachedToWindow && isShown && captured != null && !captured.visibleBoundsInRoot.isEmpty
        return NativeSourceAccessibilityNode(present, hidden, present && !hidden, bounds)
    }

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (sourceNode()?.present == false) null else super.createAccessibilityNodeInfo()

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.ProgressBar"
        info.isEnabled = true
        info.isFocusable = false
        info.isFocused = false
        info.isClickable = false
        info.isLongClickable = false
        info.isSelected = false
        info.isCheckable = false
        info.isChecked = false
        info.text = null
        info.contentDescription = null
        info.setBoundsInParent(android.graphics.Rect())
        info.rangeInfo = null
        AccessibilityNodeInfoCompat.wrap(info).stateDescription = context.getString(smallthingz.ui.R.string.in_progress)
        val actions = info.actionList
        for (index in actions.size - 1 downTo 0) info.removeAction(actions[index])
        info.addAction(if (isAccessibilityFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        sourceNode()?.populate(info)
    }

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> super.performAccessibilityAction(action, args)
        else -> false
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.className = "android.view.View"
        event.isEnabled = true
        event.text.clear()
        event.contentDescription = null
    }
}

@RequiresApi(33)
private object LinearDurationScaleListenerApi33 {
    fun register(wake: () -> Unit): AutoCloseable {
        val listener = ValueAnimator.DurationScaleChangeListener { wake() }
        ValueAnimator.registerDurationScaleChangeListener(listener)
        return AutoCloseable { ValueAnimator.unregisterDurationScaleChangeListener(listener) }
    }
}
