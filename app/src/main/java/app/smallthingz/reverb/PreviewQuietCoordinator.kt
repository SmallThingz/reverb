package app.smallthingz.reverb

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Tracks retirement proof only. No track, player, Activity, or View enters this registry. */
internal class PreviewQuietCoordinator {
    internal class Ticket internal constructor(internal val id: Long) {
        internal var status = Status.PENDING
    }
    internal enum class Status { PENDING, UNSAFE, QUIET }
    private class Waiter(val complete: (Boolean) -> Unit)
    private val lock = Any()
    private var nextId = 0L
    private val retiring = LinkedHashMap<Long, Ticket>()
    private val waiters = LinkedHashSet<Waiter>()

    fun register(): Ticket = synchronized(lock) {
        Ticket(++nextId).also { retiring[it.id] = it }
    }

    fun quiet(ticket: Ticket) = transition(ticket, Status.QUIET)
    fun failed(ticket: Ticket) = transition(ticket, Status.UNSAFE)

    private fun transition(ticket: Ticket, status: Status) {
        val delivery: Pair<List<Waiter>, Boolean>? = synchronized(lock) {
            if (retiring[ticket.id] !== ticket || ticket.status == Status.QUIET) return
            ticket.status = status
            if (status == Status.QUIET) retiring.remove(ticket.id)
            resultLocked()?.let { result ->
                waiters.toList().also { waiters.clear() } to result
            }
        }
        delivery?.let { (waiting, result) -> waiting.forEach { it.complete(result) } }
    }

    private fun resultLocked(): Boolean? = when {
        retiring.values.any { it.status == Status.UNSAFE } -> false
        retiring.isEmpty() -> true
        else -> null
    }

    /** False is a terminal fail-closed result for this waiter, never absence of an unsafe owner. */
    suspend fun awaitQuiet(): Boolean = suspendCancellableCoroutine { continuation ->
        val waiter = Waiter { continuation.resume(it) }
        val immediate = synchronized(lock) {
            resultLocked().also { if (it == null) waiters.add(waiter) }
        }
        continuation.invokeOnCancellation { synchronized(lock) { waiters.remove(waiter) } }
        if (immediate != null) continuation.resume(immediate)
    }

    internal fun retiringCount(): Int = synchronized(lock) { retiring.size }
}

internal val previewQuietCoordinator = PreviewQuietCoordinator()
