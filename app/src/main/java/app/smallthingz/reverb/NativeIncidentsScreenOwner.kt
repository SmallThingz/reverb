package app.smallthingz.reverb

import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import smallthingz.ui.animation.core.Animatable
import smallthingz.ui.animation.core.FastOutSlowInEasing
import smallthingz.ui.animation.core.tween
import smallthingz.ui.runtime.MonotonicFrameClock
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal interface NativeIncidentsRenderer {
    fun historyChanged(state: IncidentHistoryUiState)
    fun panelChanged(visible: Boolean, openProgress: Float)
}

/**
 * Main-screen lifetime, including while the incident overlay is absent. There is exactly one
 * durable revision observer. Accepted toggle/delete calls still transfer synchronously to the
 * existing IncidentHistoryController/store; closing this UI cancels only its observation/motion.
 */
internal class NativeIncidentsScreenOwner(
    context: Context,
    parentScope: CoroutineScope,
    initiallyOpen: Boolean,
    private var onBackRequested: () -> Unit,
    private var onAlertChanged: (Boolean) -> Unit,
    val history: IncidentHistoryController = IncidentHistoryController(context),
) : AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))
    init { requireNotNull(scope.coroutineContext[MonotonicFrameClock]) { "Incidents requires its window frame clock" } }
    private var open = initiallyOpen
    private var allowBack = initiallyOpen
    private var closed = false
    private var renderer: NativeIncidentsRenderer? = null
    private var rendererToken: Any? = null
    private var backRegistration: OnBackPressedCallback? = null
    private var events: Channel<BackEventCompat>? = null
    private var gestureJob: Job? = null
    private val back = IncidentsPredictiveBackMotionState()
    private val panel = NativeIncidentPanelMotion(initiallyOpen, scope, ::publishMotion)
    private var lastAlert: Boolean? = null
    val state get() = history.state
    val overlayVisible get() = open || panel.progress > 0f
    val openProgress get() = incidentsPanelOpenProgress(panel.progress, back.gestureActive,
        back.progress.value, back.commitCompleted, open, back.startOpenProgress)

    init {
        scope.launch {
            history.observeHistory { state ->
                renderer?.historyChanged(state)
                val alert = state.history.hasAlert
                if (lastAlert != alert) { lastAlert = alert; onAlertChanged(alert) }
            }
        }
    }

    fun connect(value: NativeIncidentsRenderer): AutoCloseable {
        check(!closed && renderer == null) { "Incidents already has a renderer" }
        val token = Any()
        rendererToken = token
        renderer = value
        try {
            value.historyChanged(state)
            value.panelChanged(overlayVisible, openProgress)
        } catch (failure: Throwable) {
            if (rendererToken === token) { renderer = null; rendererToken = null }
            throw failure
        }
        return AutoCloseable {
            if (rendererToken === token) { renderer = null; rendererToken = null }
        }
    }

    /** Parent supplies the existing saved showIncidents and showAboutDialog admission. */
    fun bindNavigation(open: Boolean, allowBack: Boolean,
                       onBackRequested: () -> Unit = this.onBackRequested,
                       onAlertChanged: (Boolean) -> Unit = this.onAlertChanged) {
        if (closed) return
        this.onBackRequested = onBackRequested
        this.onAlertChanged = onAlertChanged
        val changedOpen = this.open != open
        val wasEnabled = this.allowBack
        this.open = open
        this.allowBack = allowBack && open
        if (wasEnabled != this.allowBack) {
            if (this.allowBack) {
                back.commitCompleted = false
                if (!back.gestureActive) scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    back.progress.snapTo(0f); publishMotion()
                }
            } else if (!back.commitCompleted) {
                back.abandonGesture { interrupted -> panel.resumeAfterBack(interrupted, open) }
                retireGesture()
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    back.progress.snapTo(0f); publishMotion()
                }
            }
        }
        if (changedOpen) panel.settleTo(open)
        backRegistration?.isEnabled = this.allowBack
        publishMotion()
    }

    /** Header Back changes the same parent navigation flag; it does not skip the close animation. */
    fun requestBack() { if (!closed) onBackRequested() }

    fun registerBack(dispatcher: OnBackPressedDispatcher, lifecycleOwner: LifecycleOwner): AutoCloseable {
        check(!closed && backRegistration == null)
        val callback = object : OnBackPressedCallback(allowBack) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) { startGesture() }
            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (events == null) startGesture()
                events?.trySend(backEvent)
            }
            override fun handleOnBackCancelled() = retireGesture()
            override fun handleOnBackPressed() {
                if (events == null) startGesture()
                events?.close()
                events = null
            }
        }
        backRegistration = callback
        dispatcher.addCallback(lifecycleOwner, callback)
        return AutoCloseable {
            if (backRegistration === callback) {
                backRegistration = null
                retireGesture()
            }
            callback.remove()
        }
    }

    private fun startGesture() {
        if (!allowBack || closed) return
        val interrupted = if (back.gestureActive)
            back.startOpenProgress * predictiveBackOpenProgress(back.progress.value) else null
        val generation = back.claimGesture()
        // Claim first so the old cancelled stream cannot schedule a return for this new owner.
        retireGesture()
        back.commitCompleted = false
        val channel = Channel<BackEventCompat>(Channel.BUFFERED)
        events = channel
        gestureJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var committed = false
            var cancellationScheduled = false
            try {
                back.startOpenProgress = panel.beginBack(interrupted).coerceIn(0f, 1f)
                back.progress.snapTo(0f)
                back.gestureActive = true
                publishMotion()
                for (event in channel) {
                    back.swipeEdge = event.swipeEdge
                    back.progress.snapTo(event.progress.coerceIn(0f, 1f))
                    publishMotion()
                }
                continuePredictiveBackCommit(back.progress.value,
                    animateToEnd = { duration ->
                        if (duration == 0) { back.progress.snapTo(1f); publishMotion() }
                        else back.progress.animateTo(1f, tween(duration, easing = FastOutSlowInEasing)) {
                            publishMotion()
                        }
                    },
                    onBack = {
                        if (back.generation == generation && allowBack && !closed) {
                            committed = true
                            back.commitCompleted = true
                            panel.snapTo(0f)
                            // MainScreen's callback mutates saved navigation only after visual terminal.
                            onBackRequested()
                        }
                    })
            } catch (cancelled: CancellationException) {
                if (back.generation == generation && allowBack && scope.isActive && !closed) {
                    cancellationScheduled = true
                    back.cancellationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            back.progress.animateTo(0f, tween(160, easing = FastOutSlowInEasing)) { publishMotion() }
                        } finally {
                            if (back.generation == generation) {
                                back.gestureActive = false
                                back.swipeEdge = BackEventCompat.EDGE_NONE
                                if (allowBack && scope.isActive && !closed) panel.settleTo(open)
                                publishMotion()
                            }
                        }
                    }
                }
                throw cancelled
            } finally {
                if (events === channel) events = null
                if (back.generation == generation && !cancellationScheduled) {
                    if (!committed) back.commitCompleted = false
                    back.gestureActive = false
                    back.swipeEdge = BackEventCompat.EDGE_NONE
                    if (!committed && allowBack && scope.isActive && !closed) panel.settleTo(open)
                    publishMotion()
                }
            }
        }
    }

    private fun retireGesture() {
        events?.cancel()
        events = null
        gestureJob?.cancel()
        gestureJob = null
    }
    private fun publishMotion() { if (!closed) renderer?.panelChanged(overlayVisible, openProgress) }

    override fun close() {
        if (closed) return
        closed = true
        renderer = null
        rendererToken = null
        backRegistration?.remove()
        backRegistration = null
        retireGesture()
        back.cancellationJob?.cancel()
        scope.cancel()
    }
}

/** Direct native counterpart of IncidentsPanelMotionState; unchanged220ms tween/interrupt rules. */
private class NativeIncidentPanelMotion(
    initiallyOpen: Boolean,
    private val scope: CoroutineScope,
    private val changed: () -> Unit,
) {
    var progress = if (initiallyOpen) 1f else 0f
        private set
    private val animation = Animatable(progress)
    private var settling: Job? = null
    private var target: Float? = null
    private var generation = 0L
    private fun stop(): Float {
        generation++
        settling?.cancel()
        settling = null
        target = null
        return progress
    }
    fun snapTo(value: Float) {
        stop()
        progress = value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: progress
        changed()
    }
    fun beginBack(interrupted: Float?): Float {
        if (interrupted != null) snapTo(interrupted)
        return stop()
    }
    fun resumeAfterBack(interrupted: Float?, open: Boolean) {
        if (interrupted != null) snapTo(interrupted)
        settleTo(open)
    }
    fun settleTo(open: Boolean) {
        val end = if (open) 1f else 0f
        if (target == end && settling?.isActive == true) return
        val start = stop()
        if (start == end) return
        target = end
        val owner = generation
        settling = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            animation.snapTo(start)
            animation.animateTo(end, tween(220)) {
                if (generation == owner) { progress = value; changed() }
            }
        }
    }
}
