package app.smallthingz.reverb

import android.view.View
import android.view.ViewTreeObserver
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.runtime.withFrameNanos
import smallthingz.ui.platform.AccessibilityManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The released AppFeedbackHost delivery effect, attached to its existing native window.
 * The process queue, terminal acknowledgement, accessibility timeout and two visible-frame waits
 * remain the original helpers. This owner never consumes or owns Library's actionable Undo.
 */
internal class NativeAppFeedbackOwner(
    private val root: View,
    private val lifecycleOwner: LifecycleOwner,
    parentScope: CoroutineScope,
    private val accessibilityManager: AccessibilityManager?,
    private val display: (FeedbackEvent?) -> Unit,
    private val visibilityAllowed: (Boolean) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))
    private var deliveryAllowed = true
    private var closed = false
    private var observer: ViewTreeObserver? = null
    private val allowed = MutableStateFlow(false)
    private val focus = ViewTreeObserver.OnWindowFocusChangeListener { updateAdmission() }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) { observeFocus(); updateAdmission() }
        override fun onViewDetachedFromWindow(view: View) { releaseFocus(); updateAdmission() }
    }

    init {
        requireNotNull(scope.coroutineContext[MonotonicFrameClock]) {
            "Feedback delivery must inherit its owning window's frame clock"
        }
        root.addOnAttachStateChangeListener(attachment)
        if (root.isAttachedToWindow) observeFocus()
        updateAdmission()
        scope.launch {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                allowed.collectLatest { canDeliver ->
                    if (!canDeliver) return@collectLatest
                    AppFeedbackCenter.events
                        .map { it.firstOrNull() }
                        .distinctUntilChangedBy { it?.id }
                        .collect { next ->
                            if (next != null) deliverFeedbackEvent(
                                event = next,
                                canDisplay = {
                                    !closed && deliveryAllowed && root.hasWindowFocus() &&
                                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                        root.isAttachedToWindow && root.isShown
                                },
                                display = display,
                                awaitVisibleFrame = {
                                    withFrameNanos { }
                                    withFrameNanos { }
                                },
                                acknowledge = AppFeedbackCenter::acknowledge,
                                accessibilityManager = accessibilityManager,
                            )
                        }
                }
            }
        }
    }

    fun bind(deliveryAllowed: Boolean) {
        if (closed || this.deliveryAllowed == deliveryAllowed) return
        this.deliveryAllowed = deliveryAllowed
        updateAdmission()
    }

    private fun updateAdmission() {
        if (closed) return
        val next = deliveryAllowed && root.hasWindowFocus()
        // Suppression removes the entire source AnimatedVisibility immediately, not after its fade.
        // Publishing this before cancelling delivery prevents an actionable Undo overlap.
        visibilityAllowed(next)
        allowed.value = next
    }

    private fun observeFocus() {
        releaseFocus()
        root.viewTreeObserver.also {
            observer = it
            it.addOnWindowFocusChangeListener(focus)
        }
    }

    private fun releaseFocus() {
        observer?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focus)
        observer = null
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        release { visibilityAllowed(false) }
        release(::releaseFocus)
        release { root.removeOnAttachStateChangeListener(attachment) }
        release { scope.cancel() }
        failure?.let { throw it }
    }
}
