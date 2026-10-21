/*
 * Window event batching adapted from AndroidX ui 1.11.2.
 * Copyright 2020 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.collection.ArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.IdentityHashMap

internal data class NativeAccessibilityScrollRange(val value: Float, val maximum: Float)

internal interface NativeScrollAccessibilityRegistration : AutoCloseable {
    /** Invoke only after the source offset changes, not at initial observation or a max-only change. */
    fun scrollChanged()
    /** Actual source layout/semantic range membership changed; does not invent a scroll delta. */
    fun layoutChanged()
}

/**
 * One queue per existing native Window, alongside its one text cursor. There is no per-control
 * timer, draw callback, polling loop, new renderer or copy of the semantics tree.
 *
 * scope is the same main-thread, window-lifetime scope used by this native renderer. Closing a
 * page registration does not close the window queue. Detach/reattach preserves registrations and
 * clears service caches as the pinned delegate does; disposal closes the queue and all listeners.
 *
 * Native coordinates must already reflect the new placement before scrollChanged is called.
 * onScrollGeometryChanged refreshes the existing window's focus geometry and invalidates its root.
 * onBatchFinished posts the existing window's semantics structure/bounds reconciliation; source
 * delegate coalesces that posted reconciliation independently from this100ms event batch.
 * These callbacks must not create another text cursor or reset native keyboard/accessibility focus.
 */
@MainThread
internal class NativeWindowScrollAccessibilityEvents(
    private val windowRoot: View,
    scope: CoroutineScope,
    private val onScrollGeometryChanged: () -> Unit,
    private val onBatchFinished: () -> Unit,
    private val onClosed: () -> Unit,
) : NativeWindowHorizontalAccessibilityEvents, AutoCloseable {
    private val services = NativeWindowAccessibilityServices(windowRoot)
    private val registrations = IdentityHashMap<View, Registration>()
    private val pending = ArraySet<Registration>()
    // Pinned source is Channel(1), not a periodic ticker or debounce reset by each movement.
    private val notifications = Channel<Unit>(capacity = 1)
    private var closed = false
    @VisibleForTesting
    internal var accessibilityForceEnabledForTesting = false
    @VisibleForTesting
    internal var onSendAccessibilityEvent: (AccessibilityEvent) -> Boolean = { event ->
        windowRoot.parent?.requestSendAccessibilityEvent(windowRoot, event) == true
    }
    private val isEnabled: Boolean
        get() = accessibilityForceEnabledForTesting || services.isEnabled
    private val loop: Job = scope.launch {
        try {
            val subtreeTargets = ArraySet<View>()
            for (ignored in notifications) {
                if (isEnabled) {
                    // A short batch snapshot makes native callback/disposal reentrancy safe. It
                    // retains ArraySet order and does not retain a second semantic hierarchy.
                    val batch = pending.toList()
                    for (registration in batch) {
                        if (!registration.isLiveAndAttached()) continue
                        val target = registration.subtreeTarget()
                        if (target != null && target.isAttachedToWindow && target.belongsTo(windowRoot) &&
                            subtreeTargets.add(target)) {
                            send(createEvent(target, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
                                contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
                            })
                        }
                        // The source may be retired synchronously while dispatching the first event.
                        if (!registration.isLiveAndAttached()) continue
                        if (registration.pendingHorizontal || registration.pendingVertical) {
                            val event = createEvent(registration.source, AccessibilityEvent.TYPE_VIEW_SCROLLED)
                            if (registration.pendingHorizontal) registration.horizontal?.invoke()?.let {
                                event.scrollX = it.value.toInt()
                                event.maxScrollX = it.maximum.toInt()
                            }
                            if (registration.pendingVertical) registration.vertical?.invoke()?.let {
                                event.scrollY = it.value.toInt()
                                event.maxScrollY = it.maximum.toInt()
                            }
                            send(event)
                        }
                    }
                    subtreeTargets.clear()
                    if (!closed) onBatchFinished()
                }
                pending.forEach { it.pendingHorizontal = false; it.pendingVertical = false }
                pending.clear()
                delay(100L) // Immediate first batch, then source minimum gap between batches.
            }
        } finally {
            pending.clear()
        }
    }

    init {
        // Parent-scope cancellation is disposal too; it must not strand platform listeners or
        // retain page closures. Callback dispatch stays on the Android main thread.
        loop.invokeOnCompletion {
            if (Looper.myLooper() == Looper.getMainLooper()) close()
            else Handler(Looper.getMainLooper()).post { close() }
        }
    }

    /**
     * Horizontal adapter used by the fixed onboarding pager. Its nearest source merging ancestor
     * is absent, so its subtree event source is the pager's own semantic View.
     */
    override fun register(
        source: View,
        sourcePresent: () -> Boolean,
        readRange: () -> NativeHorizontalAccessibilityRange,
    ): NativeHorizontalAccessibilityEventRegistration {
        val registration = registerScroll(source, sourcePresent, { source }, horizontal = readRange)
        return object : NativeHorizontalAccessibilityEventRegistration {
            override fun scrollChanged() = registration.scrollChanged()
            override fun layoutChanged() = registration.layoutChanged()
            override fun close() = registration.close()
        }
    }

    /**
     * readRange is sampled at delivery, matching ScrollAxisRange.value/maxValue lambdas. The
     * caller supplies the nearest source merging semantic ancestor (or source itself), not a
     * guessed native clickable parent. For onboarding page/ordinary About scrollers this is source.
     * Source presence admits a new notification; dispatch later requires attachment/liveness,
     * not continuing semantic visibility. A queued hidden-but-attached source is not deleted.
     */
    fun registerScroll(
        source: View,
        sourcePresent: () -> Boolean,
        subtreeTarget: () -> View?,
        horizontal: (() -> NativeAccessibilityScrollRange)? = null,
        vertical: (() -> NativeAccessibilityScrollRange)? = null,
    ): NativeScrollAccessibilityRegistration {
        check(!closed) { "Native window accessibility events are disposed" }
        require(horizontal != null || vertical != null) { "A scroll registration needs an axis" }
        require(source.belongsTo(windowRoot)) { "Scroller must belong to this native window root" }
        check(!registrations.containsKey(source)) { "This source already owns a scroll registration" }
        return Registration(source, sourcePresent, subtreeTarget, horizontal, vertical).also {
            registrations[source] = it
        }
    }

    private inner class Registration(
        val source: View,
        val present: () -> Boolean,
        val subtreeTarget: () -> View?,
        val horizontal: (() -> NativeAccessibilityScrollRange)?,
        val vertical: (() -> NativeAccessibilityScrollRange)?,
    ) : NativeScrollAccessibilityRegistration {
        var retired = false
        var pendingHorizontal = false
        var pendingVertical = false

        fun isLiveAndAttached(): Boolean = !closed && !retired &&
            registrations[source] === this && source.isAttachedToWindow && source.belongsTo(windowRoot)

        override fun scrollChanged() {
            if (!isLiveAndAttached()) return
            onScrollGeometryChanged()
            windowRoot.invalidate()
            // Source scroll observation remains valid while services are disabled. Admission is
            // semantic membership, not service state; the shared delivery loop checks isEnabled.
            if (!present()) return
            pendingHorizontal = horizontal != null
            pendingVertical = vertical != null
            notifyPending(this)
        }

        override fun layoutChanged() {
            // Source onLayoutChange invalidates/schedules even an attached node currently pruned
            // from currentSemanticsNodes; an ancestor may become exposed through this layout.
            if (!isLiveAndAttached() || !isEnabled) return
            notifyPending(this)
        }

        override fun close() {
            if (retired) return
            retired = true
            if (registrations[source] === this) registrations.remove(source)
            pending.remove(this)
            pendingHorizontal = false
            pendingVertical = false
        }
    }

    private fun notifyPending(registration: Registration) {
        if (pending.add(registration)) notifications.trySend(Unit)
    }

    @Suppress("DEPRECATION")
    private fun createEvent(source: View, type: Int): AccessibilityEvent =
        AccessibilityEvent.obtain(type).apply {
            isEnabled = true
            className = "android.view.View"
            packageName = windowRoot.context.packageName
            setSource(source)
            // These plain native scrollers have neither Password nor IsSensitiveData semantics.
            // No itemCount/fromIndex/toIndex, text, contentDescription or delta is synthesized.
        }

    private fun send(event: AccessibilityEvent): Boolean {
        if (closed || !isEnabled) return false
        // Match source root.parent transport. Do not pass through individual native child
        // onInitializeAccessibilityEvent, which would alter the source's generic event payload.
        return onSendAccessibilityEvent(event)
    }

    override fun close() {
        if (closed) return
        closed = true
        notifications.close()
        loop.cancel()
        registrations.values.forEach { it.retired = true }
        registrations.clear()
        pending.clear()
        var failure: Throwable? = null
        try { services.close() } catch (error: Throwable) { failure = error }
        try { onClosed() } catch (error: Throwable) {
            val previous = failure
            if (previous == null) failure = error else previous.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}

/** Pinned isEnabled admission excludes UIAutomator-only service state; no permissions are changed. */
@MainThread
private class NativeWindowAccessibilityServices(private val root: View) :
    View.OnAttachStateChangeListener,
    AccessibilityManager.AccessibilityStateChangeListener,
    AccessibilityManager.TouchExplorationStateChangeListener,
    AutoCloseable {
    private val manager = root.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private var enabledServices: List<AccessibilityServiceInfo>? = null
    private var listening = false
    private var closed = false

    val isEnabled: Boolean
        get() = !closed && manager.isEnabled &&
            (enabledServices ?: manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK).also { enabledServices = it }).isNotEmpty()

    init {
        root.addOnAttachStateChangeListener(this)
        if (root.isAttachedToWindow) onViewAttachedToWindow(root)
    }

    override fun onViewAttachedToWindow(view: View) {
        if (closed || listening) return
        if (manager.isEnabled) enabledServices = null
        manager.addAccessibilityStateChangeListener(this)
        manager.addTouchExplorationStateChangeListener(this)
        listening = true
    }

    override fun onViewDetachedFromWindow(view: View) {
        if (!listening) return
        manager.removeAccessibilityStateChangeListener(this)
        manager.removeTouchExplorationStateChangeListener(this)
        listening = false
    }

    override fun onAccessibilityStateChanged(enabled: Boolean) { enabledServices = null }
    override fun onTouchExplorationStateChanged(enabled: Boolean) { enabledServices = null }

    override fun close() {
        if (closed) return
        closed = true
        root.removeOnAttachStateChangeListener(this)
        onViewDetachedFromWindow(root)
        enabledServices = null
    }
}

private fun View.belongsTo(root: View): Boolean {
    var current: View? = this
    while (current != null) {
        if (current === root) return true
        current = current.parent as? View
    }
    return false
}
