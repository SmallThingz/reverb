package app.smallthingz.reverb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Serializes Library refresh requests on their UI owner: one running scan and one latest request.
 * Calls and callbacks stay on the owner's dispatcher. Each action owns its normal failure reporting.
 * Dropping pending work never cancels a running scan, which may be completing durable recovery.
 */
internal class LibraryRefreshQueue(
    private val scope: CoroutineScope,
    private val canRefresh: () -> Boolean,
) {
    private var running = false
    private var pending: (suspend () -> Unit)? = null

    fun request(refresh: suspend () -> Unit) {
        if (!scope.isActive || !canRefresh()) return
        pending = refresh
        if (running) return
        running = true
        // Enter the finally owner immediately, including when cancellation races admission.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    if (!canRefresh()) break
                    val next = pending ?: break
                    pending = null
                    next()
                }
            } finally {
                val next = pending
                pending = null
                running = false
                // A cancelled attempt must not discard a newer request while its UI owner lives.
                // Owner cancellation or a hidden panel still rejects admission in request().
                if (next != null) request(next)
            }
        }
    }

    fun dropPending() {
        pending = null
    }
}

/** A newer refresh inherits the spinner; a non-refresh owner explicitly retires it. */
internal class LibraryRefreshGeneration(private val setRefreshing: (Boolean) -> Unit) {
    private var current = 0L

    fun request(showSpinner: Boolean): Long {
        current++
        if (showSpinner) setRefreshing(true)
        return current
    }

    fun invalidate(): Long {
        current++
        setRefreshing(false)
        return current
    }

    fun isCurrent(generation: Long): Boolean = generation == current

    fun complete(generation: Long) {
        if (isCurrent(generation)) setRefreshing(false)
    }
}
