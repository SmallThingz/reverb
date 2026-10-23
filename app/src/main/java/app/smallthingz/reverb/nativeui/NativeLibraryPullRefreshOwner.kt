/*
 * Derived from AndroidX Material3 1.4.0 PullToRefresh.kt, Copyright 2023 The Android
 * Open Source Project, Apache-2.0. See pull-refresh/SOURCE.md and incidents/APACHE-2.0.txt.
 */
package app.smallthingz.reverb.nativeui

import smallthingz.ui.animation.core.Animatable
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Released PullToRefreshBox node/state only. The existing Library controller remains the refresh
 * source of truth. Existing list/empty scroll owners feed physical Y into [nested]; no new detector,
 * pointer dispatcher, list engine or refresh job is created here.
 */
internal class NativeLibraryPullRefreshOwner(
    private val parentScope: CoroutineScope,
    density: Density,
    initialRefreshing: Boolean,
    private val ancestor: NativeVerticalNestedHost,
    private var onRefresh: () -> Unit,
    initialDistanceFraction: Float = 0f,
) : AutoCloseable {
    val thresholdPx = with(density) { 80.dp.roundToPx() }
    private val drag = NativePullRefreshDrag(thresholdPx)
    private val distance = Animatable(initialDistanceFraction)
    private var attachedScope: CoroutineScope? = null
    private var closed = false
    private var observers: Array<() -> Unit> = emptyArray()
    var isRefreshing = initialRefreshing
        private set
    val distanceFraction get() = distance.value
    val isAnimating get() = distance.isRunning

    init { requireNotNull(parentScope.coroutineContext[MonotonicFrameClock]) }

    /** Parent pre runs first; current post runs before parent, like NestedScrollNode. */
    val nested: NativeVerticalNestedHost = object : NativeVerticalNestedHost {
        override fun preScroll(availableY: Float, source: NativeVerticalScrollSource): Float {
            val parent = ancestor.preScroll(availableY, source)
            return parent + preScrollHere(availableY - parent, source)
        }
        override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource): Float {
            val own = postScrollHere(availableY, source)
            return own + ancestor.postScroll(consumedY + own, availableY - own, source)
        }
        override suspend fun preFling(availableY: Float): Float {
            val parent = ancestor.preFling(availableY)
            return parent + release(availableY - parent)
        }
        override suspend fun postFling(consumedY: Float, availableY: Float) =
            ancestor.postFling(consumedY, availableY)
    }

    fun observeVisual(observer: () -> Unit): AutoCloseable {
        check(!closed)
        observers += observer
        var ended = false
        return AutoCloseable {
            if (!ended) { ended = true; observers = observers.filterNot { it === observer }.toTypedArray() }
        }
    }

    /** Same modifier-node lifetime as the always-present indicator, not the loaded list subtree. */
    fun attach() {
        check(!closed)
        if (attachedScope != null) return
        val scope = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))
        attachedScope = scope
        scope.launch { distance.snapTo(if (isRefreshing) 1f else 0f); changed() }
        drag.attach(isRefreshing)
    }

    fun detach() {
        val scope = attachedScope
        attachedScope = null
        scope?.cancel()
    }

    fun bind(refreshing: Boolean, onRefresh: () -> Unit = this.onRefresh) {
        if (closed) return
        this.onRefresh = onRefresh
        if (isRefreshing == refreshing) return
        isRefreshing = refreshing
        changed()
        attachedScope?.launch {
            if (!isRefreshing) animateHidden() else animateThreshold()
        }
    }

    internal fun preScrollHere(availableY: Float, source: NativeVerticalScrollSource): Float = when {
        attachedScope == null || isAnimating -> 0f
        source == NativeVerticalScrollSource.USER_INPUT && availableY < 0f -> drag.consume(availableY, isRefreshing)
        else -> 0f
    }

    internal fun postScrollHere(availableY: Float, source: NativeVerticalScrollSource): Float {
        val scope = attachedScope ?: return 0f
        if (isAnimating || source != NativeVerticalScrollSource.USER_INPUT) return 0f
        val consumed = drag.consume(availableY, isRefreshing)
        scope.launch {
            if (!isAnimating) { distance.snapTo(drag.verticalOffset / thresholdPx); changed() }
        }
        return consumed
    }

    internal suspend fun release(availableY: Float): Float {
        if (attachedScope == null || isRefreshing) return 0f
        // Source intentionally does not reject release simply because state.isAnimating is true.
        if (drag.shouldRefresh) onRefresh()
        val consumed = drag.consumedReleaseVelocity(availableY)
        animateHidden()
        drag.hidden()
        return consumed
    }

    private suspend fun animateThreshold() {
        try { distance.animateTo(1f) { changed() } }
        finally { if (attachedScope != null) drag.atThreshold() }
    }

    private suspend fun animateHidden() {
        try { distance.animateTo(0f) { changed() } }
        finally { drag.hidden() }
    }

    private fun changed() { if (!closed) observers.forEach { it() } }

    override fun close() {
        if (closed) return
        closed = true
        observers = emptyArray()
        detach()
    }
}
