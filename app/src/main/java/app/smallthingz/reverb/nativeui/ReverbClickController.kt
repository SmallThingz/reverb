/*
 * Copyright 2025 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 * Source-derived native adaptation of foundation 1.11.2 Clickable.kt.
 *
 * Native integration candidate. Event routing and frame/interaction dispatch require actual A/B.
 */
package app.smallthingz.reverb.nativeui

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import java.util.ArrayDeque
import kotlin.math.max

/** Identity tokens let indication/elevation adapters pair the exact interactions they received. */
internal class NativePressToken internal constructor(val x: Float, val y: Float)
internal class NativeStateToken internal constructor(val kind: Kind) {
    enum class Kind { HOVER, FOCUS }
}

internal interface NativeClickIndication {
    fun press(token: NativePressToken)
    fun release(token: NativePressToken)
    fun cancel(token: NativePressToken)
    fun stateEnter(token: NativeStateToken)
    fun stateExit(token: NativeStateToken)
}

/**
 * Immutable model key, mutable current actions. Value-equal keys mean the same gesture owner.
 * Rebinding enriched data must keep the same key; recycled rows must supply the new key.
 * No double-click callback exists in released Reverb, so this proposal intentionally adds none.
 */
internal data class NativeClickBinding(
    val ownerKey: Any,
    val enabled: Boolean,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
    val hapticFeedbackEnabled: Boolean = true,
)

/**
 * The parent supplies shape admission and the equivalent gesture-consumption interest.
 * A plain native View does not know Compose's interested parent connections or Final pass.
 */
internal interface NativeClickEnvironment {
    /** Includes the released shape + touch-only minimum-target admission, not a guessed rectangle. */
    fun admitDown(event: MotionEvent): Boolean
    /** Parent gesture connection interested in this specific down; not every parent/pager blindly. */
    fun interestedParent(event: MotionEvent): Boolean
    /** Coordinates consumption after long press with the real native parent gesture owner. */
    fun longPressOwnershipChanged(owns: Boolean)
    val minimumTouchWidthPx: Float
    val minimumTouchHeightPx: Float
}

/**
 * Direct controller on an existing View. The host forwards touch/key/hover/focus/lifecycle methods.
 * It does not create a View, set platform pressed state, change accessibility, or synthesize clicks.
 * Callbacks may route to inputHost.performClick(), whose listener holds the current app action.
 */
internal class ReverbClickController(
    private val host: View,
    private val environment: NativeClickEnvironment,
    private val indication: NativeClickIndication,
) {
    private val main = Handler(Looper.getMainLooper())
    private var binding: NativeClickBinding? = null
    private var hapticFeedbackEnabled = true
    private var attached = false
    private var generation = 0L
    private var pointer: PointerGesture? = null
    private var ownsTouchStream = false
    private val keys = HashMap<Int, KeyGesture>()
    private var hover: NativeStateToken? = null
    private var focus: NativeStateToken? = null
    private val pendingInteractions = ArrayDeque<QueuedInteraction>()
    private val deliveredPresses = LinkedHashSet<NativePressToken>()
    private val deliveredStates = LinkedHashSet<NativeStateToken>()
    private var drainPosted = false

    private class PointerGesture(
        val generation: Long,
        val press: NativePressToken,
        val enabledAtDown: Boolean,
    ) {
        var delayedPress: Runnable? = null
        var longPress: Runnable? = null
        var longTriggered = false
        var pressQueued = false
    }

    private class KeyGesture(val generation: Long, val press: NativePressToken) {
        var longPress: Runnable? = null
        var longTriggered = false
    }

    private data class QueuedInteraction(val generation: Long, val action: () -> Unit)
    private val drain = Runnable {
        drainPosted = false
        while (pendingInteractions.isNotEmpty()) {
            val event = pendingInteractions.removeFirst()
            if (event.generation == generation && attached) event.action()
        }
    }

    /**
     * Same key + same long-click capability updates actions without retiring an active gesture.
     * Disable, missing binding, key replacement, or long-click presence changes retire ownership.
     */
    fun bind(next: NativeClickBinding?) {
        val old = binding
        val mustRetire = old?.ownerKey != next?.ownerKey ||
            old?.enabled != next?.enabled ||
            (old?.onLongClick == null) != (next?.onLongClick == null)
        if (mustRetire) {
            // FocusableNode is not replaced when only combinedClickable's long-click capability
            // changes. Keep its exact focus interaction, rather than waiting for a nonexistent
            // second native focus callback to restore the state layer.
            val keepFocus = old?.ownerKey == next?.ownerKey && old?.enabled == true && next?.enabled == true
            retireInteractions(preserveFocus = keepFocus)
        }
        binding = next
        if (next != null) hapticFeedbackEnabled = next.hapticFeedbackEnabled
    }

    fun attached() {
        attached = true
    }

    fun detached() {
        retireInteractions()
        attached = false
        binding = null // Recycled/detached views must reconnect to an explicitly current owner.
    }

    /** Cancels active input without inventing a focus loss on an otherwise still-focused View. */
    fun cancel() {
        cancelPointer()
        ownsTouchStream = false
        cancelKeys()
        cancelHover()
    }

    /**
     * consumedByOther is the parent's source-equivalent Main-pass consumption, never our own DOWN.
     * Standard native interception also arrives as ACTION_CANCEL; never hide that cancellation.
     */
    fun touch(event: MotionEvent, consumedByOther: Boolean = false): Boolean {
        val current = binding ?: return false
        if (!attached || !host.isAttachedToWindow) return false
        // Non-suspending CombinedClickable checks deep press before both normal-up and move logic.
        // It can therefore win over a consumed up; explicit ACTION_CANCEL still always cancels.
        if (event.actionMasked != MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_CANCEL &&
            Build.VERSION.SDK_INT >= 29 && event.classification == MotionEvent.CLASSIFICATION_DEEP_PRESS
        ) pointer?.let(::triggerPointerLongPress)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPointer()
                ownsTouchStream = false
                if (consumedByOther || event.pointerCount != 1 || !environment.admitDown(event)) return false
                ownsTouchStream = true
                val gesture = PointerGesture(generation, NativePressToken(event.x, event.y), current.enabled)
                pointer = gesture
                if (current.enabled) {
                    val delay = environment.interestedParent(event) || hasNativeDelayedPressAncestor()
                    if (delay) {
                        val job = Runnable {
                            if (owns(gesture)) {
                                gesture.delayedPress = null
                                queuePress(gesture)
                            }
                        }
                        gesture.delayedPress = job
                        main.postDelayed(job, ViewConfiguration.getTapTimeout().toLong())
                    } else {
                        queuePress(gesture)
                    }
                    if (current.onLongClick != null) {
                        val job = Runnable { triggerPointerLongPress(gesture) }
                        gesture.longPress = job
                        main.postDelayed(job, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                }
                return true // Disabled-but-present clickable also consumes its down in the source.
            }
            MotionEvent.ACTION_CANCEL -> {
                val handled = ownsTouchStream
                cancelPointer()
                cancelHover()
                ownsTouchStream = false
                return handled
            }
            MotionEvent.ACTION_UP -> {
                val handled = ownsTouchStream
                val gesture = pointer
                if (gesture != null) {
                    if (consumedByOther && !gesture.longTriggered) {
                        cancelPointer()
                    } else {
                        finishPointer(gesture)
                    }
                }
                ownsTouchStream = false
                return handled
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                val gesture = pointer ?: return ownsTouchStream
                if (gesture.longTriggered) return true // Source consumes all events until every pointer is up.
                if (consumedByOther || anyPointerOutside(event)) cancelPointer()
                return ownsTouchStream
            }
            else -> return ownsTouchStream
        }
    }

    /** Native parent/router supplies the source Final-pass result after its gesture processing. */
    fun afterParentDispatch(consumedByOther: Boolean) {
        if (consumedByOther && pointer?.longTriggered == false) cancelPointer()
    }

    private fun anyPointerOutside(event: MotionEvent): Boolean {
        for (index in 0 until event.pointerCount) {
            val touch = event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER
            val horizontal = if (touch) max(0f, environment.minimumTouchWidthPx - host.width) / 2f else 0f
            val vertical = if (touch) max(0f, environment.minimumTouchHeightPx - host.height) / 2f else 0f
            val x = event.getX(index)
            val y = event.getY(index)
            // Match isOutOfBounds exactly: right/bottom equality is still inside. No touch-slop test.
            if (x < -horizontal || x > host.width + horizontal ||
                y < -vertical || y > host.height + vertical
            ) return true
        }
        return false
    }

    private fun owns(gesture: PointerGesture): Boolean =
        pointer === gesture && gesture.generation == generation && attached && binding?.enabled == true

    private fun queuePress(gesture: PointerGesture) {
        if (gesture.pressQueued) return
        gesture.pressQueued = true
        enqueue(gesture.generation) { deliverPress(gesture.press) }
    }

    private fun finishPointer(gesture: PointerGesture) {
        pointer = null
        gesture.longPress?.let(main::removeCallbacks)
        gesture.longPress = null
        val wasEnabled = gesture.enabledAtDown && gesture.generation == generation && binding?.enabled == true
        if (wasEnabled) {
            val delay = gesture.delayedPress
            if (delay != null) {
                main.removeCallbacks(delay) // Cancel the exact job before the next gesture can replace it.
                gesture.delayedPress = null
                enqueue(gesture.generation) {
                    deliverPress(gesture.press)
                    deliverRelease(gesture.press)
                }
            } else if (gesture.pressQueued) {
                enqueue(gesture.generation) { deliverRelease(gesture.press) }
            }
        }
        if (gesture.longTriggered) environment.longPressOwnershipChanged(false)
        if (wasEnabled && !gesture.longTriggered) binding?.onClick?.invoke()
    }

    private fun triggerPointerLongPress(gesture: PointerGesture) {
        if (!owns(gesture) || gesture.longTriggered) return
        if (binding?.onLongClick == null) return
        gesture.longPress?.let(main::removeCallbacks)
        gesture.longPress = null
        gesture.longTriggered = true
        environment.longPressOwnershipChanged(true)
        if (!owns(gesture)) return
        val action = binding?.onLongClick ?: return
        action()
        // Source invokes pointer haptic after the callback; keyboard long click has no such haptic.
        // Read the current mutable flag exactly as CombinedClickable does after its callback.
        // Detach/disable does not add a new guard inside an already-executing source callback.
        if (hapticFeedbackEnabled) {
            host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun cancelPointer() {
        val gesture = pointer ?: return
        pointer = null
        gesture.delayedPress?.let(main::removeCallbacks)
        gesture.longPress?.let(main::removeCallbacks)
        if (gesture.pressQueued) enqueue(gesture.generation) { deliverCancel(gesture.press) }
        if (gesture.longTriggered) environment.longPressOwnershipChanged(false)
    }

    fun key(event: KeyEvent): Boolean {
        val current = binding ?: return false
        if (!attached || !host.isAttachedToWindow || !current.enabled || !isActivationKey(event.keyCode)) return false
        val keyCode = event.keyCode // Do not retain a potentially recycled KeyEvent in delayed work.
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (keys.containsKey(keyCode)) return false // Do not emit repeated Press interactions.
                val gesture = KeyGesture(generation, NativePressToken(host.width / 2f, host.height / 2f))
                keys[keyCode] = gesture
                enqueue(gesture.generation) { deliverPress(gesture.press) }
                if (current.onLongClick != null) {
                    val job = Runnable {
                        if (keys[keyCode] === gesture && gesture.generation == generation &&
                            attached && binding?.enabled == true
                        ) {
                            gesture.longPress = null
                            gesture.longTriggered = true
                            binding?.onLongClick?.invoke()
                        }
                    }
                    gesture.longPress = job
                    main.postDelayed(job, ViewConfiguration.getLongPressTimeout().toLong())
                }
                true
            }
            KeyEvent.ACTION_UP -> {
                val gesture = keys.remove(keyCode) ?: return false
                gesture.longPress?.let(main::removeCallbacks)
                enqueue(gesture.generation) { deliverRelease(gesture.press) }
                if (!gesture.longTriggered) binding?.onClick?.invoke()
                true
            }
            else -> false
        }
    }

    /** Call in addition to super.onHoverEvent; the latter emits native accessibility hover events. */
    fun hover(event: MotionEvent): Boolean {
        if (!attached || binding?.enabled != true) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                if (hover == null) {
                    val token = NativeStateToken(NativeStateToken.Kind.HOVER)
                    hover = token
                    enqueue(generation) { deliverStateEnter(token) }
                }
                true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                val handled = hover != null
                cancelHover()
                handled
            }
            else -> false
        }
    }

    fun focusChanged(focused: Boolean) {
        if (focused && attached && binding?.enabled == true) {
            if (focus == null) {
                val token = NativeStateToken(NativeStateToken.Kind.FOCUS)
                focus = token
                enqueue(generation) { deliverStateEnter(token) }
            }
        } else {
            val token = focus
            focus = null
            if (token != null) enqueue(generation) { deliverStateExit(token) }
            cancelKeys()
        }
    }

    private fun cancelKeys() {
        keys.values.forEach {
            it.longPress?.let(main::removeCallbacks)
            enqueue(it.generation) { deliverCancel(it.press) }
        }
        keys.clear()
    }

    private fun cancelHover() {
        val token = hover
        hover = null
        if (token != null) enqueue(generation) { deliverStateExit(token) }
    }

    private fun retireInteractions(preserveFocus: Boolean = false) {
        val retainedFocus = if (preserveFocus) focus else null
        cancelPointer()
        keys.values.forEach { it.longPress?.let(main::removeCallbacks) }
        keys.clear()
        ownsTouchStream = false
        hover = null
        focus = retainedFocus
        generation++
        main.removeCallbacks(drain)
        drainPosted = false
        pendingInteractions.clear()
        // Detach/disable cannot leave a delivered press alive behind a cancelled dispatcher job.
        deliveredPresses.toList().forEach { indication.cancel(it) }
        deliveredPresses.clear()
        deliveredStates.toList().forEach {
            if (it !== retainedFocus) {
                deliveredStates.remove(it)
                indication.stateExit(it)
            }
        }
        // A queued focus entry belonged to the old dispatcher generation but is still valid.
        if (retainedFocus != null && retainedFocus !in deliveredStates) {
            enqueue(generation) { deliverStateEnter(retainedFocus) }
        }
    }

    private fun enqueue(ownerGeneration: Long, action: () -> Unit) {
        pendingInteractions.addLast(QueuedInteraction(ownerGeneration, action))
        if (!drainPosted) {
            drainPosted = true
            main.post(drain)
        }
    }

    private fun deliverPress(token: NativePressToken) {
        deliveredPresses.add(token)
        indication.press(token)
    }
    private fun deliverRelease(token: NativePressToken) {
        if (deliveredPresses.remove(token)) indication.release(token)
    }
    private fun deliverCancel(token: NativePressToken) {
        if (deliveredPresses.remove(token)) indication.cancel(token)
    }
    private fun deliverStateEnter(token: NativeStateToken) {
        deliveredStates.add(token)
        indication.stateEnter(token)
    }
    private fun deliverStateExit(token: NativeStateToken) {
        if (deliveredStates.remove(token)) indication.stateExit(token)
    }

    private fun hasNativeDelayedPressAncestor(): Boolean {
        var ancestor = host.parent
        while (ancestor is ViewGroup) {
            if (ancestor.shouldDelayChildPressedState()) return true
            ancestor = ancestor.parent
        }
        return false
    }

    private fun isActivationKey(key: Int): Boolean = when (key) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> true
        else -> false
    }
}
