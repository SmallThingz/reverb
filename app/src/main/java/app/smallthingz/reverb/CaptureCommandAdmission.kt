package app.smallthingz.reverb

/**
 * Short admission bookkeeping only. The supplied queue owns accepted work independently of the
 * screen; storage work must run on that queue, never while this admission monitor is held.
 */
internal class CaptureCommandAdmission {
    private val lock = Any()
    private val pending = HashSet<Any>()
    private var closed = false

    fun submit(
        retainLifetime: () -> Boolean,
        enqueue: (Runnable) -> Boolean,
        onIdle: () -> Unit,
        command: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val ticket = Any()
        pending += ticket
        var queued = false
        var retained = false
        try {
            retained = retainLifetime()
            if (!retained) return@synchronized false
            queued = enqueue(Runnable {
                try {
                    command()
                } finally {
                    // Work may have posted a Stop seal/release tail to the same FIFO.
                    // Keep lifetime ownership until that tail has passed, without waiting here.
                    val completion = Runnable { if (finish(ticket)) onIdle() }
                    val completionQueued = try {
                        enqueue(completion)
                    } catch (_: RuntimeException) {
                        false
                    }
                    if (!completionQueued) completion.run()
                }
            })
            queued
        } finally {
            if (!queued && finish(ticket) && retained) onIdle()
        }
    }

    /** Rejects new submissions without cancelling work already transferred to the service. */
    fun closeAdmission() = synchronized(lock) { closed = true }

    fun isActive(): Boolean = synchronized(lock) { pending.isNotEmpty() }

    private fun finish(ticket: Any): Boolean = synchronized(lock) {
        pending.remove(ticket) && pending.isEmpty()
    }
}

/** Only the UI feedback is retired on disposal; an accepted recorder command remains owned. */
internal class CaptureCommandFeedback<T>(callback: (T) -> Boolean) {
    private var callback: ((T) -> Boolean)? = callback

    /** Recheck the service owner when the queued completion runs; failed posts have no fallback. */
    fun postIfCurrent(
        value: T,
        enqueue: (Runnable) -> Boolean,
        isOwnerCurrent: () -> Boolean,
    ): Boolean = enqueue(Runnable {
        if (isOwnerCurrent()) deliver(value)
    })

    @Synchronized
    fun deliver(value: T): Boolean {
        val current = callback ?: return false
        return current(value)
    }

    @Synchronized
    fun retire() {
        callback = null
    }
}
/** A refused old start ID must not remove foreground protection from newer started work. */
internal fun releaseStartedServiceIfCurrent(
    startId: Int,
    stopSelfResult: (Int) -> Boolean,
    stopForeground: () -> Unit,
): Boolean {
    if (!stopSelfResult(startId)) return false
    stopForeground()
    return true
}
