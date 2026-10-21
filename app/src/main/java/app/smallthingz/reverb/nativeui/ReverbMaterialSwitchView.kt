/*
 * Geometry and token selection adapted from AndroidX Material3 1.4.0 Switch.kt
 * and tokens/SwitchTokens.kt, Copyright 2022 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.Choreographer
import android.view.View
import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Released M3 Switch(checked, onCheckedChange = null, enabled), with no thumb content.
 * The parent owns the row's click target, checked semantics and press feedback. This View adds
 * no click listener, focus stop, minimum 48dp target, ripple or platform Switch styling.
 *
 * All released callers leave interactionSource null. Their enclosing toggleable row therefore
 * does NOT press this thumb. [setVisualPressed] exists only for explicit-source parity probes;
 * do not connect it to row presses when migrating those callers.
 *
 * Public pinned FloatSpringSpec supplies exact scalar spring equations. Acceptance/dispatch
 * timing and frame callback phase require separate qualification against the released implementation.
 */
internal class ReverbMaterialSwitchView(
    context: Context,
    palette: ReverbPalette,
    initialChecked: Boolean = false,
    initialEnabled: Boolean = true,
) : View(context), Choreographer.FrameCallback {
    private val trackPaint = Paint(PAINT_FLAGS)
    private val thumbPaint = Paint(PAINT_FLAGS)
    private val borderPaint = Paint(PAINT_FLAGS).apply { style = Paint.Style.STROKE }
    private var colors = MaterialSwitchColors.from(palette)
    private var checked = initialChecked
    private var visuallyPressed = false
    private var trackWidth = 0
    private var trackHeight = 0
    private var borderWidth = 0f
    private var sizeMotion: SwitchFloatMotion? = null
    private var offsetMotion: SwitchFloatMotion? = null
    private var framePosted = false
    private val choreographer = Choreographer.getInstance()

    init {
        minimumWidth = 0
        minimumHeight = 0
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isEnabled = initialEnabled
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    fun setPalette(palette: ReverbPalette) {
        colors = MaterialSwitchColors.from(palette)
        invalidate()
    }

    fun setState(checked: Boolean, enabled: Boolean) {
        isEnabled = enabled
        if (this.checked == checked) return
        this.checked = checked
        requestLayout()
        invalidate()
    }

    fun setVisualPressed(pressed: Boolean) {
        if (visuallyPressed == pressed) return
        visuallyPressed = pressed
        requestLayout()
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        val changed = isEnabled != enabled
        super.setEnabled(enabled)
        if (changed) invalidate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestLayout()
        invalidate()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        trackWidth = (52f * density).roundToInt()
        trackHeight = (32f * density).roundToInt()
        borderWidth = min(ceil(2f * density), ceil(min(trackWidth, trackHeight) / 2f))
        setMeasuredDimension(
            resolveSize(trackWidth, widthMeasureSpec),
            resolveSize(trackHeight, heightMeasureSpec),
        )

        val targetSize = (if (visuallyPressed) 28f else if (checked) 24f else 16f) * density
        // Preserve the source's px -> dp -> px ordering, rather than algebraically simplifying it.
        val minBound = ((32f - targetSize / density) / 2f) * density
        val maxBound = ((52f - 24f) - (32f - 24f) / 2f) * density
        val targetOffset = when {
            visuallyPressed && checked -> maxBound - 2f * density
            visuallyPressed -> 2f * density
            checked -> maxBound
            else -> minBound
        }
        val size = sizeMotion
        val offset = offsetMotion
        if (size == null || offset == null) {
            // Initial state is already at its target: the release has no entrance animation.
            sizeMotion = SwitchFloatMotion(targetSize)
            offsetMotion = SwitchFloatMotion(targetOffset)
        } else {
            val scale = ValueAnimator.getDurationScale()
            size.retarget(targetSize, snap = visuallyPressed, durationScale = scale)
            offset.retarget(targetOffset, snap = visuallyPressed, durationScale = scale)
            postFrameIfNeeded()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = sizeMotion ?: return
        val offset = offsetMotion ?: return
        val originX = ((width - trackWidth) / 2f).roundToInt().toFloat()
        val originY = ((height - trackHeight) / 2f).roundToInt().toFloat()
        val radius = min(trackWidth, trackHeight) / 2f
        trackPaint.color = colors.track(isEnabled, checked)
        thumbPaint.color = colors.thumb(isEnabled, checked)
        borderPaint.color = colors.border(isEnabled, checked)
        borderPaint.strokeWidth = borderWidth
        canvas.drawRoundRect(
            originX, originY, originX + trackWidth, originY + trackHeight,
            radius, radius, trackPaint,
        )

        // ThumbNode truncates its animated pixel size/offset; it does not round them or animate dp.
        val actualSize = size.value.toInt()
        val logicalOffset = offset.value.toInt()
        val thumbX = if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            trackWidth - actualSize - logicalOffset
        } else logicalOffset
        val thumbY = ((trackHeight - actualSize) / 2f).roundToInt()
        val thumbRadius = actualSize / 2f
        canvas.drawRoundRect(
            originX + thumbX, originY + thumbY,
            originX + thumbX + actualSize, originY + thumbY + actualSize,
            thumbRadius, thumbRadius, thumbPaint,
        )

        // Modifier.border draws after background and children, including the thumb.
        val halfStroke = borderWidth / 2f
        val strokeRadius = (radius - halfStroke).coerceAtLeast(0f)
        canvas.drawRoundRect(
            originX + halfStroke, originY + halfStroke,
            originX + trackWidth - halfStroke, originY + trackHeight - halfStroke,
            strokeRadius, strokeRadius, borderPaint,
        )
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePosted = false
        if (!isAttachedToWindow) return
        val scale = ValueAnimator.getDurationScale()
        sizeMotion?.frame(frameTimeNanos, scale)
        offsetMotion?.frame(frameTimeNanos, scale)
        invalidate()
        postFrameIfNeeded()
    }

    private fun postFrameIfNeeded() {
        if (isAttachedToWindow && !framePosted &&
            (sizeMotion?.isRunning == true || offsetMotion?.isRunning == true)
        ) {
            framePosted = true
            choreographer.postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postFrameIfNeeded()
    }

    override fun onDetachedFromWindow() {
        choreographer.removeFrameCallback(this)
        framePosted = false
        sizeMotion?.stop()
        offsetMotion?.stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val PAINT_FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
    }
}

/** Immediate color selection from SwitchDefaults; the released M3 switch does not animate colors. */
private class MaterialSwitchColors(
    private val checkedThumb: Int,
    private val checkedTrack: Int,
    private val uncheckedThumb: Int,
    private val uncheckedTrack: Int,
    private val uncheckedBorder: Int,
    private val disabledCheckedThumb: Int,
    private val disabledCheckedTrack: Int,
    private val disabledUncheckedThumb: Int,
    private val disabledUncheckedTrack: Int,
    private val disabledUncheckedBorder: Int,
) {
    fun thumb(enabled: Boolean, checked: Boolean): Int = when {
        enabled && checked -> checkedThumb
        enabled -> uncheckedThumb
        checked -> disabledCheckedThumb
        else -> disabledUncheckedThumb
    }

    fun track(enabled: Boolean, checked: Boolean): Int = when {
        enabled && checked -> checkedTrack
        enabled -> uncheckedTrack
        checked -> disabledCheckedTrack
        else -> disabledUncheckedTrack
    }

    fun border(enabled: Boolean, checked: Boolean): Int = when {
        checked -> 0
        enabled -> uncheckedBorder
        else -> disabledUncheckedBorder
    }

    companion object {
        fun from(palette: ReverbPalette): MaterialSwitchColors {
            val surface = Color(palette.surface)
            // Retain Color.copy alpha quantization and compositeOver Float arithmetic exactly.
            fun composite(color: Int, alpha: Float): Int =
                Color(color).copy(alpha = alpha).compositeOver(surface).toArgb()
            return MaterialSwitchColors(
                checkedThumb = palette.onPrimary,
                checkedTrack = palette.primary,
                uncheckedThumb = palette.outline,
                uncheckedTrack = palette.surfaceContainerHighest,
                uncheckedBorder = palette.outline,
                disabledCheckedThumb = composite(palette.surface, 1f),
                disabledCheckedTrack = composite(palette.onSurface, 0.12f),
                disabledUncheckedThumb = composite(palette.onSurface, 0.38f),
                disabledUncheckedTrack = composite(palette.surfaceContainerHighest, 0.12f),
                disabledUncheckedBorder = composite(palette.onSurface, 0.12f),
            )
        }
    }
}

/**
 * Scalar sampling seam for ThumbNode's Animatable<Float>, not a replacement spring integrator.
 * Uses pinned AndroidX 1.11.2 FloatSpringSpec with Material3's Standard FastSpatial tokens.
 */
private class SwitchFloatMotion(initial: Float) {
    var value = initial
        private set
    private var target = initial
    private var initialValue = initial
    private var initialVelocity = 0f
    private var velocity = 0f
    private var durationNanos = 0L
    private var startFrame = UNSET_FRAME
    private var lastFrame = UNSET_FRAME
    private var snap = false
    var isRunning = false
        private set

    fun retarget(next: Float, snap: Boolean, durationScale: Float) {
        if (target == next) return
        initialValue = value
        initialVelocity = velocity
        target = next
        this.snap = snap
        durationNanos = if (snap) 0L else SPRING.getDurationNanos(initialValue, target, initialVelocity)
        startFrame = lastFrame
        isRunning = true
        if (lastFrame != UNSET_FRAME) frame(lastFrame, durationScale)
    }

    fun frame(frameNanos: Long, durationScale: Float) {
        if (!isRunning) return
        require(durationScale >= 0f)
        val start = if (startFrame == UNSET_FRAME) frameNanos.also { startFrame = it } else startFrame
        val playTime = if (durationScale == 0f) durationNanos
            else ((frameNanos - start) / durationScale).toLong()
        lastFrame = frameNanos
        if (snap || playTime >= durationNanos) {
            value = target
            stop()
        } else {
            value = SPRING.getValueFromNanos(playTime, initialValue, target, initialVelocity)
            velocity = SPRING.getVelocityFromNanos(playTime, initialValue, target, initialVelocity)
        }
    }

    fun stop() {
        isRunning = false
        velocity = 0f
        startFrame = UNSET_FRAME
        lastFrame = UNSET_FRAME
    }

    private companion object {
        const val UNSET_FRAME = Long.MIN_VALUE
        val SPRING = FloatSpringSpec(dampingRatio = 0.9f, stiffness = 1400f, visibilityThreshold = 0.01f)
    }
}
