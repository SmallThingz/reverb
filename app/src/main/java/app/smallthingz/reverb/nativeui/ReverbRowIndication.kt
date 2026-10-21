/*
 * Copyright 2021 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 * See interaction/LICENSE-2.0.txt for the source license.
 *
 * Native row adaptation of AndroidX material-ripple 1.11.2.
 * Native integration candidate: foreground/render-thread phase remains unqualified.
 */
package app.smallthingz.reverb.nativeui

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.Choreographer
import android.view.View
import android.view.animation.AnimationUtils
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Decoration on the existing row, not another View or renderer tree.
 *
 * Install before ReverbGroupLayout wraps the row foreground with its separator. The owning row
 * supplies accepted press/hover/focus interactions; this object never clicks, toggles, requests
 * focus, or manages accessibility. Its state is deliberately separate from View.drawableState:
 * passing focused/hovered state to RippleDrawable would draw the platform state layer twice.
 */
internal class ReverbRowIndication private constructor(
    private val owner: View,
    private val durationScale: () -> Float,
) : DrawableWrapper(null), View.OnAttachStateChangeListener, Choreographer.FrameCallback {
    private var ripple: RowRippleDrawable? = null
    private val statePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fullBounds = Rect()
    private val states = ArrayList<StateToken>()
    private val pendingPresses = ArrayList<PressCommand>()
    private var contentColor = android.graphics.Color.BLACK
    private var widthPx = 0
    private var heightPx = 0
    private var radiusPx = 0f
    private var measured = false
    private var lastRippleStateChangeTimeMillis: Long? = null
    private var resetRippleRunnable: Runnable? = null
    private var framePosted = false
    private val scheduledRippleWork = HashMap<Runnable, Runnable>()
    private val stateAlpha = RowStateAlpha()
    private var currentState: StateToken? = null

    enum class StateKind { HOVER, FOCUS, DRAG }
    class StateToken internal constructor(internal val kind: StateKind)

    private sealed interface PressCommand {
        data class Press(val x: Float, val y: Float) : PressCommand
        data object End : PressCommand
    }

    /** Call from the actual row's measurement/layout hook, before interaction delivery. */
    fun onMeasured(width: Int, height: Int, density: Float) {
        widthPx = width
        heightPx = height
        radiusPx = sqrt(width.toFloat() * width + height.toFloat() * height) / 2f + 10f * density
        measured = true
        updateRippleProperties()
        if (pendingPresses.isNotEmpty()) {
            val pending = pendingPresses.toList()
            pendingPresses.clear()
            pending.forEach(::applyPress)
        }
    }

    /** Actual inherited LocalContentColor, supplied by the caller; never a selected-row tint guess. */
    fun updateColor(color: Int) {
        if (contentColor == color) return
        contentColor = color
        invalidateSelf()
    }

    fun press(x: Float, y: Float) = deliverPress(PressCommand.Press(x, y))
    fun release() = deliverPress(PressCommand.End)
    fun cancel() = deliverPress(PressCommand.End)

    private fun deliverPress(command: PressCommand) {
        if (measured) applyPress(command) else pendingPresses.add(command)
    }

    private fun applyPress(command: PressCommand) {
        when (command) {
            is PressCommand.Press -> {
                if (ripple == null) {
                    ripple = RowRippleDrawable().also {
                        it.callback = this
                        it.setVisible(isVisible, false)
                    }
                }
                updateRippleProperties()
                ripple?.setHotspot(command.x, command.y)
                setRippleState(pressed = true)
            }
            PressCommand.End -> setRippleState(pressed = false)
        }
    }

    private fun updateRippleProperties() {
        val ripple = ripple ?: return
        // Match AndroidRippleNode: explicitly computed radius, set before bounds. RADIUS_AUTO differs.
        val roundedRadius = radiusPx.roundToInt()
        if (ripple.radius != roundedRadius) ripple.radius = roundedRadius
        ripple.updateColor(contentColor)
        ripple.setBounds(0, 0, widthPx, heightPx)
    }

    private fun setRippleState(pressed: Boolean) {
        val now = AnimationUtils.currentAnimationTimeMillis()
        resetRippleRunnable?.let { runnable ->
            owner.removeCallbacks(runnable)
            runnable.run()
        }
        val sincePrevious = now - (lastRippleStateChangeTimeMillis ?: 0L)
        if (!pressed && sincePrevious < 5L) {
            resetRippleRunnable = Runnable {
                ripple?.state = RESTING
                resetRippleRunnable = null
            }
            owner.postDelayed(resetRippleRunnable, 50L)
        } else {
            ripple?.state = if (pressed) PRESSED else RESTING
        }
        lastRippleStateChangeTimeMillis = now
    }

    fun enterState(kind: StateKind): StateToken = StateToken(kind).also {
        states.add(it)
        updateState()
    }

    fun leaveState(token: StateToken) {
        states.remove(token)
        updateState()
    }

    private fun updateState() {
        val next = states.lastOrNull()
        if (currentState === next) return
        val previous = currentState
        currentState = next
        val target = when (next?.kind) {
            StateKind.HOVER -> 0.08f
            StateKind.FOCUS -> 0.10f
            StateKind.DRAG -> 0.16f
            null -> 0f
        }
        val durationMillis = if (next != null) {
            when (next.kind) {
                StateKind.HOVER -> 15
                StateKind.FOCUS, StateKind.DRAG -> 45
            }
        } else {
            if (previous?.kind == StateKind.DRAG) 150 else 15
        }
        stateAlpha.animateTo(target, durationMillis)
        requestFrame()
    }

    private fun requestFrame() {
        if (!framePosted && stateAlpha.running && owner.isAttachedToWindow) {
            framePosted = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePosted = false
        stateAlpha.frame(frameTimeNanos, durationScale())
        invalidateSelf()
        requestFrame()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas) // Preserve any prior foreground before this indication.
        if (!owner.isAttachedToWindow) {
            resetForDetach()
            return
        }
        val alpha = stateAlpha.value
        if (alpha > 0f) {
            statePaint.color = reverbColorWithAlpha(contentColor, alpha)
            val checkpoint = canvas.save()
            canvas.clipRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
            canvas.drawCircle(widthPx / 2f, heightPx / 2f, radiusPx, statePaint)
            canvas.restoreToCount(checkpoint)
        }
        // Native row foreground supplies the attached View/callback/RenderNode lifetime.
        // This avoids Compose's bridge-only RippleHostView/pool. Actual frame-phase parity is
        // still a required device comparison, not inferred merely from using RippleDrawable.
        updateRippleProperties()
        ripple?.draw(canvas)
    }

    override fun getDirtyBounds(): Rect =
        fullBounds.apply { set(0, 0, widthPx, heightPx) }

    override fun scheduleDrawable(who: Drawable, what: Runnable, whenMillis: Long) {
        if (who !== ripple) {
            super.scheduleDrawable(who, what, whenMillis)
            return
        }
        scheduledRippleWork.remove(what)?.let(::unscheduleSelf)
        lateinit var tracked: Runnable
        tracked = Runnable {
            if (scheduledRippleWork[what] === tracked) scheduledRippleWork.remove(what)
            what.run()
        }
        scheduledRippleWork[what] = tracked
        super.scheduleDrawable(who, tracked, whenMillis)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        val tracked = if (who === ripple) scheduledRippleWork.remove(what) ?: what else what
        super.unscheduleDrawable(who, tracked)
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        return (ripple?.setVisible(visible, restart) ?: false) || changed
    }

    override fun onViewAttachedToWindow(view: View) {
        ripple?.setVisible(isVisible, false)
    }
    override fun onViewDetachedFromWindow(view: View) = resetForDetach()

    fun resetForDetach() {
        if (framePosted) {
            Choreographer.getInstance().removeFrameCallback(this)
            framePosted = false
        }
        resetRippleRunnable?.let {
            owner.removeCallbacks(it)
            it.run()
        } ?: run { ripple?.state = RESTING }
        ripple?.setVisible(false, false)
        // The callback token is the outer foreground wrapper, not this nested RippleDrawable.
        // Unschedule through that same wrapper chain, without cancelling the preserved foreground.
        scheduledRippleWork.values.toList().forEach(::unscheduleSelf)
        scheduledRippleWork.clear()
        states.clear()
        currentState = null
        pendingPresses.clear()
        stateAlpha.reset()
    }

    companion object {
        private val PRESSED = intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled)
        private val RESTING = intArrayOf()

        fun install(owner: View, contentColor: Int, durationScale: () -> Float): ReverbRowIndication {
            val previous = owner.foreground
            return ReverbRowIndication(owner, durationScale).also {
                // View.setForeground clears the old drawable callback: install before rewrapping.
                owner.foreground = it
                it.drawable = previous
                it.updateColor(contentColor)
                owner.addOnAttachStateChangeListener(it)
            }
        }
    }
}

/** Bounded AndroidX ripple: rectangle mask and no ancestor projection. No themed selector. */
private class RowRippleDrawable : RippleDrawable(
    ColorStateList.valueOf(android.graphics.Color.BLACK),
    null,
    ColorDrawable(android.graphics.Color.WHITE),
) {
    private var lastColor: Int? = null

    fun updateColor(contentColor: Int) {
        // Reverb minSdk28: the AndroidX <28 doubled-alpha compatibility branch cannot run.
        val color = reverbColorWithAlpha(contentColor, 0.10f)
        if (lastColor == color) return
        lastColor = color
        setColor(ColorStateList.valueOf(color))
    }

    override fun isProjected(): Boolean = false
}

/**
 * Exact linear TweenSpec value arithmetic, with source start-frame/retarget rules.
 * Choreographer dispatch phase versus Compose MonotonicFrameClock is NOT qualified by this port.
 */
internal class RowStateAlpha {
    var value = 0f
        private set
    var running = false
        private set
    private var initial = 0f
    private var target = 0f
    private var durationNanos = 0L
    private var startFrame = Long.MIN_VALUE
    private var lastFrame = Long.MIN_VALUE

    fun animateTo(target: Float, durationMillis: Int) {
        initial = value
        this.target = target
        durationNanos = durationMillis * 1_000_000L
        // Animatable retains the previous frame on interruption, but resets it after completion.
        startFrame = if (running) lastFrame else Long.MIN_VALUE
        running = true
    }

    fun frame(frameTimeNanos: Long, durationScale: Float) {
        if (!running) return
        require(durationScale >= 0f)
        if (startFrame == Long.MIN_VALUE) startFrame = frameTimeNanos
        val playTime = if (durationScale == 0f) durationNanos
            else ((frameTimeNanos - startFrame) / durationScale).toLong()
        val fraction = playTime.coerceIn(0L, durationNanos) / durationNanos.toFloat()
        value = initial * (1f - fraction) + target * fraction
        lastFrame = frameTimeNanos
        if (playTime >= durationNanos) {
            running = false
            lastFrame = Long.MIN_VALUE
        }
    }

    fun reset() {
        value = 0f
        running = false
        initial = 0f
        target = 0f
        durationNanos = 0L
        startFrame = Long.MIN_VALUE
        lastFrame = Long.MIN_VALUE
    }
}
