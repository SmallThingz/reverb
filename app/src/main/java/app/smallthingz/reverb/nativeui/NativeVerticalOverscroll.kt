/*
 * Copyright 2021 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 * Vertical-only adaptation of foundation 1.11.2 AndroidOverscroll.android.kt.
 */
package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.Build
import android.view.MotionEvent
import android.widget.EdgeEffect
import androidx.annotation.RequiresApi
import app.smallthingz.nativeui.unit.Density
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
 * Caller supplies the released LocalOverscrollFactory color (Android default 0xff666666) and
 * its actual Density. Recreate on density/factory changes, matching factory identity.
 */
internal class NativeVerticalOverscroll(
    private val context: Context,
    private val density: Density,
    private val glowColor: Int,
    private val invalidate: () -> Unit,
) {
    private var width = 0
    private var height = 0
    private var pointerId = -1
    private var pointerX = Float.NaN
    private var scrollCycle = false
    private var top: EdgeEffect? = null
    private var bottom: EdgeEffect? = null
    private var topNegation: EdgeEffect? = null
    private var bottomNegation: EdgeEffect? = null
    private var stretchRenderer: NativeVerticalStretchRenderer? = null

    /** Source uses distance, not isFinished: legacy glow is not reported as an active stretch. */
    val isInProgress get() = top.stretched || bottom.stretched
    private val EdgeEffect?.stretched get() = this != null && distanceCompat != 0f
    private val EdgeEffect?.animating get() = this != null && !isFinished

    fun measured(newWidth: Int, newHeight: Int) {
        val initial = width == 0 && height == 0
        val changed = width != newWidth || height != newHeight
        width = newWidth
        height = newHeight
        if (changed) forEachEffect(includeNegation = true) { it.setSize(width, height) }
        if (!initial && changed) release()
    }

    /** Observe every pointer event even when consumed elsewhere; retain last position after up. */
    fun pointer(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pointerId = event.getPointerId(0)
            pointerX = event.x
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
        pointerX = event.getX(index)
    }

    /**
     * Wrap nested pre/self/post dispatch. Positive physicalY pulls the top, negative the bottom.
     * Only drag/UserInput leftovers create a new pull; SideEffect destretches at exactly 4x.
     */
    fun scroll(physicalY: Float, source: NativeVerticalScrollSource, performScroll: (Float) -> Float): Float {
        if (width <= 0 || height <= 0) return performScroll(physicalY)
        if (!scrollCycle) {
            if (top.stretched) pullTop(0f)
            if (bottom.stretched) pullBottom(0f)
            scrollCycle = true
        }
        val multiplier = if (source == NativeVerticalScrollSource.SIDE_EFFECT) 4f else 1f
        val destretch = physicalY * multiplier
        val preConsumed = when {
            physicalY == 0f -> 0f
            top.stretched && physicalY < 0f -> {
                val consumed = pullTop(destretch)
                if (!top.stretched) topEffect().finish()
                if (consumed == destretch) physicalY else consumed / multiplier
            }
            bottom.stretched && physicalY > 0f -> {
                val consumed = pullBottom(destretch)
                if (!bottom.stretched) bottomEffect().finish()
                if (consumed == destretch) physicalY else consumed / multiplier
            }
            else -> 0f
        }
        if (preConsumed != 0f) invalidate()
        val available = physicalY - preConsumed
        val consumed = performScroll(available)
        val remainder = available - consumed
        if (available != 0f && consumed != 0f && isInProgress) release()
        var needsInvalidate = false
        if (source == NativeVerticalScrollSource.USER_INPUT) {
            if (remainder > .5f) { pullTop(remainder); needsInvalidate = true }
            else if (remainder < -.5f) { pullBottom(remainder); needsInvalidate = true }
        }
        if (available != 0f) {
            if (top.animating && physicalY < 0f) {
                topEffect().onReleaseWithOppositeDelta(physicalY)
                needsInvalidate = top.animating || needsInvalidate
            }
            if (bottom.animating && physicalY > 0f) {
                bottomEffect().onReleaseWithOppositeDelta(physicalY)
                needsInvalidate = bottom.animating || needsInvalidate
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
            top.stretched && physicalVelocity < 0f ->
                topEffect().absorbToRelaxIfNeeded(physicalVelocity, height.toFloat(), density)
            bottom.stretched && physicalVelocity > 0f ->
                -bottomEffect().absorbToRelaxIfNeeded(-physicalVelocity, height.toFloat(), density)
            else -> 0f
        }
        if (consumed != 0f) invalidate()
        return FlingStart(physicalVelocity - consumed, true)
    }

    /** After nested post-fling, including a zero-velocity drag release/cancel. */
    fun finishFling(start: FlingStart, leftoverPhysicalVelocity: Float) {
        if (!start.active) return
        scrollCycle = false
        if (leftoverPhysicalVelocity > 0f) topEffect().onAbsorbCompat(leftoverPhysicalVelocity.roundToInt())
        else if (leftoverPhysicalVelocity < 0f) bottomEffect().onAbsorbCompat(-leftoverPhysicalVelocity.roundToInt())
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

    private fun displacement(): Float = (if (pointerX.isNaN()) width / 2f else pointerX) / width

    private fun pullTop(delta: Float): Float {
        val edge = topEffect()
        val consumed = edge.onPullDistanceCompat(delta / height, displacement()) * height
        return if (edge.distanceCompat != 0f) delta else consumed
    }

    private fun pullBottom(delta: Float): Float {
        val edge = bottomEffect()
        val consumed = -edge.onPullDistanceCompat(-delta / height, 1f - displacement()) * height
        return if (edge.distanceCompat != 0f) delta else consumed
    }

    private fun newEdge(): EdgeEffect = NativeEdgeEffectCompat.create(context).apply {
        color = glowColor
        if (width != 0 || height != 0) setSize(width, height)
    }
    private fun topEffect() = top ?: newEdge().also { top = it }
    private fun bottomEffect() = bottom ?: newEdge().also { bottom = it }
    private fun topNegationEffect() = topNegation ?: newEdge().also { topNegation = it }
    private fun bottomNegationEffect() = bottomNegation ?: newEdge().also { bottomNegation = it }

    private inline fun forEachEffect(includeNegation: Boolean, action: (EdgeEffect) -> Unit) {
        top?.let(action); bottom?.let(action)
        if (includeNegation) { topNegation?.let(action); bottomNegation?.let(action) }
    }

    /** Content keeps the viewport's own dimensions and source scroll clip; only the backend expands. */
    fun draw(canvas: Canvas, drawContent: (Canvas) -> Unit) {
        if (width <= 0 || height <= 0) { drawContent(canvas); return }
        if (Build.VERSION.SDK_INT >= 31) {
            if (!canvas.isHardwareAccelerated) {
                forEachEffect(includeNegation = true) { it.finish() }
                drawContent(canvas)
                return
            }
            val active = top.animating || topNegation.stretched || bottom.animating || bottomNegation.stretched
            if (!active) { drawContent(canvas); return }
            val renderer = stretchRenderer ?: NativeVerticalStretchRenderer().also { stretchRenderer = it }
            renderer.draw(canvas, 30f * density.density, drawContent) { recordingCanvas ->
                var needs = false
                if (topNegation.stretched) { drawRotated(topNegationEffect(), recordingCanvas, 180f); topNegationEffect().finish() }
                if (top.animating) {
                    needs = drawRotated(topEffect(), recordingCanvas, 0f) || needs
                    if (top.stretched) topNegationEffect().onPullDistanceCompat(topEffect().distanceCompat, displacement())
                }
                if (bottomNegation.stretched) { drawRotated(bottomNegationEffect(), recordingCanvas, 0f); bottomNegationEffect().finish() }
                if (bottom.animating) {
                    needs = drawRotated(bottomEffect(), recordingCanvas, 180f) || needs
                    if (bottom.stretched) bottomNegationEffect().onPullDistanceCompat(bottomEffect().distanceCompat, 1f - displacement())
                }
                if (needs) invalidate()
            }
        } else {
            drawContent(canvas)
            var needs = false
            if (top.animating) needs = topEffect().draw(canvas) || needs // Source default padding is zero.
            if (bottom.animating) {
                val save = canvas.save()
                try {
                    canvas.rotate(180f)
                    canvas.translate(-width.toFloat(), -height.toFloat())
                    needs = bottomEffect().draw(canvas) || needs
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
private class NativeVerticalStretchRenderer {
    private val node = RenderNode("AndroidEdgeEffectOverscrollEffect")
    fun draw(canvas: Canvas, maxElevation: Float, content: (Canvas) -> Unit, effects: (Canvas) -> Unit) {
        node.setPosition(0, 0, canvas.width + maxElevation.roundToInt() * 2, canvas.height)
        val recording = node.beginRecording()
        try {
            effects(recording)
            recording.save()
            try { recording.translate(maxElevation, 0f); content(recording) }
            finally { recording.restore() }
        } finally { node.endRecording() }
        val saved = canvas.save()
        try { canvas.translate(-maxElevation, 0f); canvas.drawRenderNode(node) }
        finally { canvas.restoreToCount(saved) }
    }
    fun dispose() { node.discardDisplayList() }
}
