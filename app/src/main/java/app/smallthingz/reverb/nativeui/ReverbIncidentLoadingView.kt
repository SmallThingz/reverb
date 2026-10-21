/*
 * Source-derived from Material3 1.4.0 ProgressIndicator.kt, Copyright The Android Open
 * Source Project, Apache-2.0. See incidents/APACHE-2.0.txt and SOURCE.md.
 */
package app.smallthingz.reverb.nativeui

import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Released indeterminate indicator: Incidents defaults to a 2dp round stroke; Library supplies 4dp. */
internal open class ReverbIncidentLoadingView(
    context: Context,
    color: Int,
    private val frames: MonotonicFrameClock,
    private val scope: CoroutineScope,
    private val durationScale: () -> Float,
    private val strokeWidthDp: Float = 2f,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }
    private val arc = RectF()
    private var frameJob: Job? = null
    private var origin = Long.MIN_VALUE
    private var lastScale = Float.NaN
    private var rotation = 0f
    private var progress = 0.1f
    private val scaleObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { updateScheduling() }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = false
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    fun setColor(value: Int) { if (paint.color != value) { paint.color = value; invalidate() } }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, scaleObserver)
        origin = Long.MIN_VALUE
        updateScheduling()
    }

    override fun onDetachedFromWindow() {
        context.contentResolver.unregisterContentObserver(scaleObserver)
        stopFrames()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateScheduling()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateScheduling()
    }

    private fun updateScheduling() {
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE) {
            stopFrames()
            return
        }
        // InfiniteTransition skips to each animation's target and suspends at zero motion scale.
        val scale = durationScale()
        if (scale == 0f) {
            stopFrames()
            rotation = 1080f + 360f
            progress = 0.87f
            lastScale = 0f
            origin = Long.MIN_VALUE
            invalidate()
        } else if (frameJob?.isActive != true) {
            frameJob = scope.launch {
                while (isActive && isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
                    frames.withFrameNanos(::frame)
                }
            }
        }
    }

    private fun stopFrames() {
        frameJob?.cancel()
        frameJob = null
    }

    private fun frame(frameTimeNanos: Long) {
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE) return
        val scale = durationScale()
        if (scale == 0f) { updateScheduling(); return }
        if (origin == Long.MIN_VALUE || lastScale != scale) {
            origin = frameTimeNanos
            lastScale = scale
        }
        val playTime = ((frameTimeNanos - origin) / scale).toLong()
        rotation = IncidentLoadingMotion.global.getValueFromNanos(playTime) +
            IncidentLoadingMotion.additional.getValueFromNanos(playTime)
        progress = IncidentLoadingMotion.progress.getValueFromNanos(playTime)
        invalidate()
    }

    /** Host-injected scale changes use the same wake-up as the platform setting observer. */
    fun motionScaleChanged() = updateScheduling()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.strokeWidth = strokeWidthDp * resources.displayMetrics.density
        val inset = paint.strokeWidth / 2f
        // Released drawCircularIndicator uses width for both arc dimensions, even if nonsquare.
        arc.set(inset, inset, width - inset, width - inset)
        val checkpoint = canvas.save()
        canvas.rotate(rotation, width / 2f, height / 2f)
        // Transparent track contributes no pixels; retain active draw at startAngle0, same sweep.
        canvas.drawArc(arc, 0f, progress * 360f, false, paint)
        canvas.restoreToCount(checkpoint)
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.ProgressBar"
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // AndroidComposeView exposes indeterminate progress without RangeInfo; no fake percentage.
        info.rangeInfo = null
        androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(info).stateDescription =
            context.getString(androidx.compose.ui.R.string.in_progress)
    }
}

/** The pinned keyframes/easings, shared by the View; no Composition or animation state tree. */
private object IncidentLoadingMotion {
    val global = TargetBasedAnimation(
        infiniteRepeatable(animation = tween<Float>(6000, easing = LinearEasing)),
        Float.VectorConverter, 0f, 1080f)
    val additional = TargetBasedAnimation(
        infiniteRepeatable(animation = keyframes<Float> {
            durationMillis = 6000
            90f at 300 using CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
            90f at 1500
            180f at 1800
            180f at 3000
            270f at 3300
            270f at 4500
            360f at 4800
            360f at 6000
        }), Float.VectorConverter, 0f, 360f)
    val progress = TargetBasedAnimation(
        infiniteRepeatable(animation = keyframes<Float> {
            durationMillis = 6000
            0.87f at 3000 using CubicBezierEasing(0.2f, 0f, 0f, 1f)
            0.1f at 6000
        }), Float.VectorConverter, 0.1f, 0.87f)
}
