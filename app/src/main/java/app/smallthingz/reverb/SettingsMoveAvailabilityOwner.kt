package app.smallthingz.reverb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * UI-owned destination observations: one admitted scan and one latest pending target.
 * The volatile request stamp is read only for admission on the repository IO dispatcher.
 * Once admitted, recovery keeps its original target and runs to completion.
 */
internal class SettingsMoveAvailabilityOwner(
    private val scope: CoroutineScope,
    private val scan: suspend (target: String, mayStart: () -> Boolean) -> Boolean,
    private val publish: (Boolean) -> Unit,
) {
    @Volatile private var generation = 0L
    @Volatile private var active = false
    @Volatile private var closed = false
    private var lastTarget: String? = null
    private var lastRevision = 0
    private val queue = LibraryRefreshQueue(scope) { active && !closed }

    fun request(target: String, revision: Int) {
        if (closed) return
        if (active && lastTarget == target && lastRevision == revision) return
        active = true
        lastTarget = target
        lastRevision = revision
        val submitted = ++generation
        publish(false)
        queue.request {
            val result = try {
                // Disposal may retire UI/pending ownership, but cannot interrupt an admitted
                // repository recovery or strand its exact-target catalog transaction.
                withContext(NonCancellable) {
                    scan(target) { isCurrent(submitted) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (isCurrent(submitted)) publish(result)
        }
    }

    fun hide() {
        active = false
        generation++
        lastTarget = null
        lastRevision = 0
        queue.dropPending()
        if (!closed) publish(false)
    }

    fun close() {
        closed = true
        hide()
    }

    private fun isCurrent(submitted: Long): Boolean =
        !closed && active && scope.isActive && generation == submitted
}

/** Check after acquiring the shared mutex; a request can become stale while waiting for it. */
internal suspend fun withMoveAvailabilityAdmission(
    mutex: Mutex,
    mayStart: () -> Boolean,
    scan: suspend () -> Boolean,
): Boolean = mutex.withLock {
    if (!mayStart()) false else scan()
}
