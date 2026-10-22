/*
 * Interaction/animation selection adapted from AndroidX Material3 1.4.0 Button.kt and
 * internal/Elevation.kt, Copyright 2021 The Android Open Source Project.
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
import android.graphics.Color
import android.graphics.Outline
import android.os.Build
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewOutlineProvider
import app.smallthingz.nativeui.animation.core.CubicBezierEasing
import app.smallthingz.nativeui.animation.core.FastOutSlowInEasing
import app.smallthingz.nativeui.animation.core.FloatTweenSpec
import java.io.Closeable
import kotlin.math.min

/**
 * Pinned Surface's outer shadow shape, independent from the inner content clip. The host supplies
 * the fill/border itself and keeps its EXISTING inner content layer clipped to the same capsule.
 */
internal object ReverbButtonCapsuleOutline : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        if (view.width <= 0 || view.height <= 0) {
            outline.setEmpty()
        } else {
            outline.setRoundRect(0, 0, view.width, view.height, min(view.width, view.height) / 2f)
        }
        // Surface's shadow graphicsLayer has its own default alpha1. Ancestor alpha is separate.
        outline.alpha = 1f
    }
}

/**
 * Call only on the exclusively owned ordinary button host, not an arbitrary platform Button.
 * No platform state-list elevation or translationZ may compete with the pinned M3 controller.
 *
 * This installs the outer native RenderNode shadow; it does not clip the fill or create a View.
 * Source Surface.clip(shape) still belongs to the separate, existing label/indication content.
 */
internal fun configureReverbButtonShadowHost(host: View) {
    host.outlineProvider = ReverbButtonCapsuleOutline
    host.clipToOutline = false
    host.stateListAnimator = null
    host.translationZ = 0f
    if (Build.VERSION.SDK_INT >= 28) {
        host.outlineAmbientShadowColor = Color.BLACK
        host.outlineSpotShadowColor = Color.BLACK
    }
    host.invalidateOutline()
}

/** Filled Continue gets the controller; released TextButton/Back has no elevation animation. */
internal fun createReverbOnboardingButtonElevation(
    host: View,
    filled: Boolean,
    initialEnabled: Boolean,
    setShadowElevationPx: (Float) -> Unit,
): ReverbFilledButtonElevation? {
    if (filled) return ReverbFilledButtonElevation(host, initialEnabled, setShadowElevationPx)
    configureReverbButtonShadowHost(host)
    setShadowElevationPx(0f)
    return null
}

/**
 * Narrow dependency for the filled Continue button's existing interaction factory.
 *
 * The same admitted identity-bound interaction events must drive this controller and the ripple.
 * Never infer their order from View drawableState: the latest START among active interactions wins,
 * even when an older Press remains active underneath a newer Hover/Focus.
 *
 * TextButton/Back uses null elevation in the release: do not instantiate this class for it.
 * Its host should remain at elevation0 with the same capsule shape for content clipping.
 *
 * [attached]/[detached] map to the button's Composition-equivalent owner lifetime. Detach retires
 * interactions and resets hidden state to the new-owner initial elevation0. A host that retains
 * the original logical owner across a temporary window detach needs separate lifecycle parity
 * qualification; this helper does not invent that ownership policy.
 *
 * Exact tween math is public pinned animation-core; target-acceptance and Choreographer scheduling
 * phase are unqualified. Native View elevation also participates in sibling Z ordering. In this
 * footer only Continue elevates and it is already the last child after Back, so their order stays
 * unchanged. That source-specific fact must not be generalized to arbitrary Surface siblings.
 */
internal class ReverbFilledButtonElevation(
    private val host: View,
    initialEnabled: Boolean,
    private val setShadowElevationPx: (Float) -> Unit,
) : Choreographer.FrameCallback, Closeable {
    private val state = ReverbFilledButtonElevationState(initialEnabled)
    private val choreographer = Choreographer.getInstance()
    private var attached = false
    private var framePosted = false
    private var closed = false

    init {
        checkMainThread()
        configureReverbButtonShadowHost(host)
        applyElevation()
    }

    fun bindEnabled(enabled: Boolean) {
        checkMainThread()
        if (closed) return
        state.setEnabled(enabled, ValueAnimator.getDurationScale())
        stateChanged()
    }

    /** Null means an obsolete/detached input owner; it must not create a new shadow animation. */
    fun begin(kind: ReverbButtonElevationInteraction): ReverbButtonElevationToken? {
        checkMainThread()
        if (closed || !attached || !host.isAttachedToWindow) return null
        val token = state.begin(kind, ValueAnimator.getDurationScale())
        stateChanged()
        return token
    }

    fun end(token: ReverbButtonElevationToken?) {
        checkMainThread()
        if (closed || token == null) return
        state.end(token, ValueAnimator.getDurationScale())
        stateChanged()
    }

    /**
     * Input cancellation is a sequence of end events, not disposal. The input owner should normally
     * call end for its exact Press/Focus/Hover tokens. This clears all of THIS helper's active events
     * when the existing factory retires every one together.
     */
    fun cancelInteractions() {
        checkMainThread()
        if (closed) return
        state.clearInteractions(ValueAnimator.getDurationScale())
        stateChanged()
    }

    /** Owner forwards its size/configuration refresh; elevation is Dp and density is applied here. */
    fun measured() {
        checkMainThread()
        if (closed) return
        host.invalidateOutline()
        applyElevation()
    }

    fun attached() {
        checkMainThread()
        if (closed || attached) return
        attached = true
        measured()
        postFrameIfNeeded()
    }

    fun detached() {
        checkMainThread()
        if (closed) return
        attached = false
        choreographer.removeFrameCallback(this)
        framePosted = false
        state.retireOwner()
        applyElevation()
    }

    override fun close() {
        checkMainThread()
        if (closed) return
        detached()
        closed = true
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePosted = false
        if (closed || !attached) return
        if (!host.isAttachedToWindow) {
            // A missed late lifecycle forwarding cannot keep a dead View animated.
            detached()
            return
        }
        state.frame(frameTimeNanos, ValueAnimator.getDurationScale())
        applyElevation()
        postFrameIfNeeded()
    }

    private fun stateChanged() {
        applyElevation()
        if (!state.isRunning && framePosted) {
            choreographer.removeFrameCallback(this)
            framePosted = false
        }
        postFrameIfNeeded()
    }

    private fun applyElevation() {
        // Surface converts its Dp animation to Float pixels; do not round to an integer pixel.
        setShadowElevationPx(state.valueDp * host.resources.displayMetrics.density)
    }

    private fun postFrameIfNeeded() {
        if (!closed && attached && host.isAttachedToWindow && state.isRunning && !framePosted) {
            framePosted = true
            choreographer.postFrameCallback(this)
        }
    }

    private fun checkMainThread() = check(Looper.myLooper() === Looper.getMainLooper())
}

internal enum class ReverbButtonElevationInteraction { PRESS, HOVER, FOCUS }

/** Identity, not enum equality, binds an ending interaction to its particular start. */
internal class ReverbButtonElevationToken internal constructor(
    internal val kind: ReverbButtonElevationInteraction,
)

/**
 * Pure source-derived state/math seam. No Android View, snapshot state, coroutine or render tree.
 * Only actual default filled-button targets exist: hover1dp and every other state0dp.
 */
internal class ReverbFilledButtonElevationState(initialEnabled: Boolean = true) {
    private val active = ArrayList<ReverbButtonElevationToken>(3)
    private var enabled = initialEnabled
    private var initialValue = 0f
    private var initialVelocity = 0f
    private var velocity = 0f
    private var startFrame = UNSET_FRAME
    private var lastFrame = UNSET_FRAME
    private var tween = INCOMING
    var valueDp: Float = 0f
        private set
    var targetDp: Float = 0f
        private set
    var isRunning: Boolean = false
        private set

    fun begin(kind: ReverbButtonElevationInteraction, durationScale: Float = 1f): ReverbButtonElevationToken {
        val token = ReverbButtonElevationToken(kind)
        active.add(token)
        selectTarget(durationScale)
        return token
    }

    fun end(token: ReverbButtonElevationToken, durationScale: Float = 1f) {
        // Tokens do not retain this owner; a late end from a retired/foreign owner is inert.
        if (active.remove(token)) selectTarget(durationScale)
    }

    fun setEnabled(value: Boolean, durationScale: Float = 1f) {
        if (enabled == value) return
        enabled = value
        selectTarget(durationScale)
    }

    fun clearInteractions(durationScale: Float = 1f) {
        if (active.isEmpty()) return
        active.clear()
        selectTarget(durationScale)
    }

    private fun selectTarget(durationScale: Float) {
        val interaction = active.lastOrNull()?.kind
        val next = if (enabled && interaction == ReverbButtonElevationInteraction.HOVER) 1f else 0f
        // Button.kt LaunchedEffect is keyed only by target. In particular, disabling an already
        // zero-target outgoing animation must NOT add a new snap merely because enabled changed.
        if (targetDp == next) return
        if (!enabled) {
            snap(next)
            return
        }

        // Any new active interaction uses DefaultIncomingSpec. Returning from hover to no
        // interaction uses HoveredOutgoingSpec. The general150ms outgoing spec is unreachable
        // for these default tokens: press/focus/default all have the identical zero target.
        tween = if (interaction != null) INCOMING else HOVER_OUTGOING
        initialValue = valueDp
        initialVelocity = velocity
        targetDp = next
        startFrame = lastFrame
        isRunning = true
        if (lastFrame != UNSET_FRAME) frame(lastFrame, durationScale)
    }

    fun frame(frameTimeNanos: Long, durationScale: Float) {
        if (!isRunning) return
        require(durationScale.isFinite() && durationScale >= 0f)
        val start = if (startFrame == UNSET_FRAME) frameTimeNanos.also { startFrame = it } else startFrame
        val playTime = if (durationScale == 0f) DURATION_NS else ((frameTimeNanos - start) / durationScale).toLong()
        lastFrame = frameTimeNanos
        if (playTime >= DURATION_NS) {
            valueDp = targetDp
            stop()
        } else {
            valueDp = tween.getValueFromNanos(playTime, initialValue, targetDp, initialVelocity)
            velocity = tween.getVelocityFromNanos(playTime, initialValue, targetDp, initialVelocity)
        }
    }

    fun retireOwner() {
        active.clear()
        snap(0f)
    }

    private fun snap(target: Float) {
        valueDp = target
        targetDp = target
        stop()
    }

    private fun stop() {
        isRunning = false
        velocity = 0f
        startFrame = UNSET_FRAME
        lastFrame = UNSET_FRAME
    }

    private companion object {
        const val UNSET_FRAME = Long.MIN_VALUE
        const val DURATION_NS = 120_000_000L
        val INCOMING = FloatTweenSpec(duration = 120, easing = FastOutSlowInEasing)
        val HOVER_OUTGOING = FloatTweenSpec(duration = 120, easing = CubicBezierEasing(0.40f, 0f, 0.60f, 1f))
    }
}
