/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Horizontal scalar-X adaptation of foundation 1.11.2 AndroidOverscroll.android.kt.
package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.Build
import android.view.MotionEvent
import android.widget.EdgeEffect
import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.Density
import app.smallthingz.reverb.nativeui.NativeEdgeEffectCompat.absorbToRelaxIfNeeded
import app.smallthingz.reverb.nativeui.NativeEdgeEffectCompat.distanceCompat
import app.smallthingz.reverb.nativeui.NativeEdgeEffectCompat.onAbsorbCompat
import app.smallthingz.reverb.nativeui.NativeEdgeEffectCompat.onPullDistanceCompat
import app.smallthingz.reverb.nativeui.NativeEdgeEffectCompat.onReleaseWithOppositeDelta
import kotlin.math.roundToInt

/**
 * Existing View's draw/touch companion. No added View/layer tree or permanent frame callback.
 * The one lazy RenderNode is the released API31 stretch backend, not an extra UI renderer.
 *
 * Physical X is not logical page order: a rightward finger delta pulls the LEFT edge in both
 * LTR and RTL. Only the pager's self-scroll callback applies its reverse-direction conversion.
 * This is the HorizontalPager scalar-X path, not a generic simultaneous two-axis effect.
 *
 * Caller supplies the released LocalOverscrollFactory color (Android default 0xff666666) and
 * its actual Density. Recreate on density/factory changes, matching factory identity.
 */
internal class NativeHorizontalOverscroll(
    private val context: Context,
    private val density: Density,
    private val glowColor: Int,
    private val invalidate: () -> Unit,
) {
    private var width = 0
    private var height = 0
    private var pointerId = -1
    private var pointerY = Float.NaN
    private var scrollCycle = false
    private var left: EdgeEffect? = null
    private var right: EdgeEffect? = null
    private var leftNegation: EdgeEffect? = null
    private var rightNegation: EdgeEffect? = null
    private var stretchRenderer: NativeHorizontalStretchRenderer? = null

    /** Source uses distance, not isFinished: legacy glow is not reported as an active stretch. */
    val isInProgress get() = left.stretched || right.stretched
    private val EdgeEffect?.stretched get() = this != null && distanceCompat != 0f
    private val EdgeEffect?.animating get() = this != null && !isFinished

    /** Call at the draw boundary: source updateSize observes DrawScope.size, not layout callbacks. */
    fun measured(newWidth: Int, newHeight: Int) {
        val initial = width == 0 && height == 0
        val changed = width != newWidth || height != newHeight
        width = newWidth
        height = newHeight
        if (changed) forEachEffect(includeNegation = true) { it.setSize(height, width) }
        if (!initial && changed) release()
    }

    /** Observe every pointer event even when consumed elsewhere; retain last position after up. */
    fun pointer(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pointerId = event.getPointerId(0)
            pointerY = event.y
            return
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pointerId = -1
            return
        }
        val lifted = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val retained = (0 until event.pointerCount).firstOrNull {
            it != lifted && event.getPointerId(it) == pointerId
        }
        val index = retained ?: (0 until event.pointerCount).firstOrNull { it != lifted } ?: return
        pointerId = event.getPointerId(index)
        pointerY = event.getY(index)
    }

    /**
     * Wrap nested pre/self/post dispatch. Positive physicalX pulls the left, negative the right.
     * Only drag/UserInput leftovers create a new pull; SideEffect destretches at exactly 4x.
     */
    fun scroll(physicalX: Float, source: NativeVerticalScrollSource, performScroll: (Float) -> Float): Float {
        if (width <= 0 || height <= 0) return performScroll(physicalX)
        if (!scrollCycle) {
            if (left.stretched) pullLeft(0f)
            if (right.stretched) pullRight(0f)
            scrollCycle = true
        }
        val multiplier = if (source == NativeVerticalScrollSource.SIDE_EFFECT) 4f else 1f
        val destretch = physicalX * multiplier
        val preConsumed = when {
            physicalX == 0f -> 0f
            left.stretched && physicalX < 0f -> {
                val consumed = pullLeft(destretch)
                if (!left.stretched) leftEffect().finish()
                if (consumed == destretch) physicalX else consumed / multiplier
            }
            right.stretched && physicalX > 0f -> {
                val consumed = pullRight(destretch)
                if (!right.stretched) rightEffect().finish()
                if (consumed == destretch) physicalX else consumed / multiplier
            }
            else -> 0f
        }
        if (preConsumed != 0f) invalidate()
        val available = physicalX - preConsumed
        val consumed = performScroll(available)
        val remainder = available - consumed
        if (available != 0f && consumed != 0f && isInProgress) release()
        var needsInvalidate = false
        if (source == NativeVerticalScrollSource.USER_INPUT) {
            if (remainder > .5f) { pullLeft(remainder); needsInvalidate = true }
            else if (remainder < -.5f) { pullRight(remainder); needsInvalidate = true }
        }
        if (available != 0f) {
            if (left.animating && physicalX < 0f) {
                leftEffect().onReleaseWithOppositeDelta(physicalX)
                needsInvalidate = left.animating || needsInvalidate
            }
            if (right.animating && physicalX > 0f) {
                rightEffect().onReleaseWithOppositeDelta(physicalX)
                needsInvalidate = right.animating || needsInvalidate
            }
        }
        if (needsInvalidate) invalidate()
        // Fresh overscroll is intentionally not added to consumption; this is the source contract.
        return preConsumed + consumed
    }

    internal class FlingStart(val remainingVelocity: Float, internal val active: Boolean)

    /** Must precede nested pre-fling, exactly where OverscrollEffect wraps performFling. */
    fun beginFling(physicalVelocity: Float): FlingStart {
        if (width <= 0 || height <= 0) return FlingStart(physicalVelocity, false)
        val consumed = when {
            left.stretched && physicalVelocity < 0f ->
                leftEffect().absorbToRelaxIfNeeded(physicalVelocity, width.toFloat(), density)
            right.stretched && physicalVelocity > 0f ->
                -rightEffect().absorbToRelaxIfNeeded(-physicalVelocity, width.toFloat(), density)
            else -> 0f
        }
        if (consumed != 0f) invalidate()
        return FlingStart(physicalVelocity - consumed, true)
    }

    /**
     * After normal nested post-fling completion, including a zero-velocity drag release/cancel.
     * Do not call from a finally block if performFling itself was cancelled: the source has none.
     */
    fun finishFling(start: FlingStart, leftoverPhysicalVelocity: Float) {
        if (!start.active) return
        scrollCycle = false
        if (leftoverPhysicalVelocity > 0f) leftEffect().onAbsorbCompat(leftoverPhysicalVelocity.roundToInt())
        else if (leftoverPhysicalVelocity < 0f) rightEffect().onAbsorbCompat(-leftoverPhysicalVelocity.roundToInt())
        release()
    }

    private fun release() {
        var needs = false
        forEachEffect(includeNegation = false) {
            it.onRelease()
            needs = !it.isFinished || needs
        }
        if (needs) invalidate()
    }

    private fun displacement(): Float = (if (pointerY.isNaN()) height / 2f else pointerY) / height

    private fun pullLeft(delta: Float): Float {
        val edge = leftEffect()
        val consumed = edge.onPullDistanceCompat(delta / width, 1f - displacement()) * width
        return if (edge.distanceCompat != 0f) delta else consumed
    }

    private fun pullRight(delta: Float): Float {
        val edge = rightEffect()
        val consumed = -edge.onPullDistanceCompat(-delta / width, displacement()) * width
        return if (edge.distanceCompat != 0f) delta else consumed
    }

    private fun newEdge(): EdgeEffect = NativeEdgeEffectCompat.create(context).apply {
        color = glowColor
        if (width != 0 || height != 0) setSize(height, width)
    }
    private fun leftEffect() = left ?: newEdge().also { left = it }
    private fun rightEffect() = right ?: newEdge().also { right = it }
    private fun leftNegationEffect() = leftNegation ?: newEdge().also { leftNegation = it }
    private fun rightNegationEffect() = rightNegation ?: newEdge().also { rightNegation = it }

    private inline fun forEachEffect(includeNegation: Boolean, action: (EdgeEffect) -> Unit) {
        left?.let(action); right?.let(action)
        if (includeNegation) { leftNegation?.let(action); rightNegation?.let(action) }
    }

    /** Content keeps the viewport's own dimensions and source scroll clip; only the backend expands. */
    fun draw(canvas: Canvas, drawContent: (Canvas) -> Unit) {
        // DrawScope.size is the viewport's size. Canvas dimensions may include the parent's clip
        // expansion; only RenderNode bounds below use canvas.width/height, exactly as the source.
        if (width <= 0 || height <= 0) { drawContent(canvas); return }
        if (Build.VERSION.SDK_INT >= 31) {
            if (!canvas.isHardwareAccelerated) {
                forEachEffect(includeNegation = true) { it.finish() }
                drawContent(canvas)
                return
            }
            val active = left.animating || leftNegation.stretched || right.animating || rightNegation.stretched
            if (!active) { drawContent(canvas); return }
            val renderer = stretchRenderer ?: NativeHorizontalStretchRenderer().also { stretchRenderer = it }
            renderer.draw(canvas, 30f * density.density, drawContent) { recordingCanvas ->
                var needs = false
                if (leftNegation.stretched) { drawRotated(leftNegationEffect(), recordingCanvas, 90f); leftNegationEffect().finish() }
                if (left.animating) {
                    needs = drawRotated(leftEffect(), recordingCanvas, 270f) || needs
                    if (left.stretched) leftNegationEffect().onPullDistanceCompat(leftEffect().distanceCompat, 1f - displacement())
                }
                if (rightNegation.stretched) { drawRotated(rightNegationEffect(), recordingCanvas, 270f); rightNegationEffect().finish() }
                if (right.animating) {
                    needs = drawRotated(rightEffect(), recordingCanvas, 90f) || needs
                    if (right.stretched) rightNegationEffect().onPullDistanceCompat(rightEffect().distanceCompat, displacement())
                }
                if (needs) invalidate()
            }
        } else {
            drawContent(canvas)
            var needs = false
            if (left.animating) {
                val saved = canvas.save()
                try {
                    canvas.rotate(270f)
                    canvas.translate(-height.toFloat(), 0f) // Source default left padding is zero.
                    needs = leftEffect().draw(canvas) || needs
                } finally { canvas.restoreToCount(saved) }
            }
            if (right.animating) {
                val save = canvas.save()
                try {
                    canvas.rotate(90f)
                    canvas.translate(0f, -width.toFloat()) // Source default right padding is zero.
                    needs = rightEffect().draw(canvas) || needs
                } finally { canvas.restoreToCount(save) }
            }
            if (needs) invalidate()
        }
    }

    /** Explicit discarded-owner boundary; hidden but retained pages must not call this. */
    fun dispose() {
        forEachEffect(includeNegation = true) { it.finish() }
        if (Build.VERSION.SDK_INT >= 31) stretchRenderer?.dispose()
        stretchRenderer = null
    }

    private fun drawRotated(edge: EdgeEffect, canvas: Canvas, rotation: Float): Boolean {
        if (rotation == 0f) return edge.draw(canvas)
        val saved = canvas.save()
        return try { canvas.rotate(rotation); edge.draw(canvas) } finally { canvas.restoreToCount(saved) }
    }
}

@RequiresApi(31)
private class NativeHorizontalStretchRenderer {
    private val node = RenderNode("AndroidEdgeEffectOverscrollEffect")
    fun draw(canvas: Canvas, maxElevation: Float, content: (Canvas) -> Unit, effects: (Canvas) -> Unit) {
        node.setPosition(0, 0, canvas.width, canvas.height + maxElevation.roundToInt() * 2)
        val recording = node.beginRecording()
        try {
            effects(recording)
            recording.save()
            try { recording.translate(0f, maxElevation); content(recording) }
            finally { recording.restore() }
        } finally { node.endRecording() }
        val saved = canvas.save()
        try { canvas.translate(0f, -maxElevation); canvas.drawRenderNode(node) }
        finally { canvas.restoreToCount(saved) }
    }
    fun dispose() { node.discardDisplayList() }
}
