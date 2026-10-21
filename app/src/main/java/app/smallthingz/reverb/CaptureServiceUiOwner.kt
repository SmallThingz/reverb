package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.View
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.io.Closeable

private const val CAPTURE_INITIAL_BIND_FALLBACK_MILLIS = 250L

internal fun captureServiceBindingCallbackIsCurrent(
    callbackBindingGeneration: Long,
    currentBindingGeneration: Long,
    screenAlive: Boolean,
): Boolean = screenAlive && callbackBindingGeneration == currentBindingGeneration

/**
 * Main-thread binding/registration ownership only. No recorder state, export state or UI tree.
 * The existing host owns state hydration and accepted-operation cleanup through [BindingCallbacks].
 */
@MainThread
internal class CaptureServiceUiOwner {
    var connectionGeneration: Long = 0L
        private set

    /** Last actual unbind attempt; retained for tests/diagnostics, never thrown by close. */
    var lastUnbindResult: Result<Unit>? = null
        private set

    private var bindingAttached = false

    interface BindingCallbacks {
        fun connected(connectedService: ReverbService)
        fun unavailable(markSavingAsCancelRequested: Boolean)
        fun stopped()
    }

    fun bind(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        view: View,
        isAlive: () -> Boolean,
        callbacks: BindingCallbacks,
    ): Closeable {
        check(!bindingAttached) { "Close the previous Capture binding before replacing it" }
        bindingAttached = true
        var closed = false
        var boundConnection: ServiceConnection? = null

        fun unbindCurrentConnection(): Result<Unit> {
            val current = boundConnection ?: return Result.success(Unit)
            // Invalidate callbacks before unbinding. Android can already have a callback queued
            // on the main looper; that callback belongs to this retired bind lifetime.
            boundConnection = null
            connectionGeneration++
            return runCatching { context.unbindService(current) }.also {
                lastUnbindResult = it
            }
        }

        fun bindIfNeeded() {
            if (closed || boundConnection != null ||
                !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) return

            val bindingGeneration = longArrayOf(++connectionGeneration)
            val candidate = object : ServiceConnection {
                private fun isCurrent(): Boolean = captureServiceBindingCallbackIsCurrent(
                    callbackBindingGeneration = bindingGeneration[0],
                    currentBindingGeneration = connectionGeneration,
                    screenAlive = !closed && isAlive(),
                )

                override fun onServiceConnected(className: ComponentName, binder: IBinder) {
                    if (!isCurrent()) return
                    val typedBinder = binder as? ReverbService.BackgroundRecorderBinder
                        ?: run {
                            callbacks.unavailable(markSavingAsCancelRequested = false)
                            bindingGeneration[0] = ++connectionGeneration
                            return
                        }
                    callbacks.connected(typedBinder.service)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    if (!isCurrent()) return
                    callbacks.unavailable(markSavingAsCancelRequested = true)
                    // This binding remains registered and Android may reconnect it. Advance both
                    // the global generation and this connection's token so old state callbacks
                    // die while a legitimate reconnect on the same binding remains acceptable.
                    bindingGeneration[0] = ++connectionGeneration
                }
            }
            boundConnection = candidate
            val bound = runCatching {
                context.bindService(
                    Intent(context, ReverbService::class.java),
                    candidate,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
            if (!bound && boundConnection === candidate) {
                boundConnection = null
                if (connectionGeneration == bindingGeneration[0]) {
                    connectionGeneration++
                }
                callbacks.unavailable(markSavingAsCancelRequested = false)
            }
        }

        // LifecycleRegistry catches newly added observers up to the current state
        // synchronously. Do not let that catch-up ON_START bypass the deliberate first-frame
        // bind below; real later starts occur after addObserver returns.
        var observerInstalled = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (observerInstalled) bindIfNeeded()

                Lifecycle.Event.ON_STOP -> {
                    callbacks.stopped()
                    unbindCurrentConnection()
                }

                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        observerInstalled = true
        val initialBind = Runnable { bindIfNeeded() }
        // Keep normal startup frame-first, but keyguard/occlusion can suppress app frames
        // indefinitely. A delayed main-loop fallback restores durable capture intent even when
        // postOnAnimation never fires; bindIfNeeded() makes the duplicate callback harmless.
        view.postOnAnimation(initialBind)
        view.postDelayed(initialBind, CAPTURE_INITIAL_BIND_FALLBACK_MILLIS)
        return Closeable {
            if (closed) return@Closeable
            closed = true
            try {
                view.removeCallbacks(initialBind)
                lifecycleOwner.lifecycle.removeObserver(observer)
                callbacks.stopped()
            } finally {
                try {
                    unbindCurrentConnection()
                } finally {
                    bindingAttached = false
                }
            }
        }
    }

    /** Close any previous lease before reusing [uiForegroundOwner] with the same service. */
    fun foregroundLease(
        recorderService: ReverbService?,
        lifecycleOwner: LifecycleOwner,
        view: View,
        uiForegroundOwner: Any,
    ): Closeable {
        var closed = false
        var resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        var windowFocused = view.hasWindowFocus()

        fun updateUiForeground() {
            if (closed) return
            recorderService?.setAppUiForeground(uiForegroundOwner, resumed && windowFocused)
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    updateUiForeground()
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    resumed = false
                    updateUiForeground()
                }
                else -> Unit
            }
        }
        val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            windowFocused = hasFocus
            updateUiForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        updateUiForeground()

        return Closeable {
            if (closed) return@Closeable
            closed = true
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            }
            recorderService?.setAppUiForeground(uiForegroundOwner, false)
        }
    }

    /** Close any previous lease before reusing [visualizationCallback] with the same service. */
    fun visualizationLease(
        recorderService: ReverbService?,
        lifecycleOwner: LifecycleOwner,
        view: View,
        visualizationCallback: ReverbService.VisualizationCallback,
        visualizerVisible: Boolean,
        clearFrames: () -> Unit,
    ): Closeable {
        var closed = false
        var registered = false
        var resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        var windowFocused = view.hasWindowFocus()

        fun updateRegistration() {
            if (closed) return
            val visible = resumed && windowFocused && visualizerVisible
            if (visible && !registered) {
                recorderService?.setVisualizationCallback(visualizationCallback)
                registered = recorderService != null
            } else if (!visible && registered) {
                recorderService?.clearVisualizationCallback(visualizationCallback)
                registered = false
                clearFrames()
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    updateRegistration()
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    resumed = false
                    updateRegistration()
                }
                else -> Unit
            }
        }
        val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            windowFocused = hasFocus
            updateRegistration()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        updateRegistration()

        return Closeable {
            if (closed) return@Closeable
            closed = true
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            }
            if (registered) {
                recorderService?.clearVisualizationCallback(visualizationCallback)
            }
            clearFrames()
        }
    }
}
