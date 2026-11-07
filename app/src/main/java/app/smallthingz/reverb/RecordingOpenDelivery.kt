package app.smallthingz.reverb

/** Settle the failed row before deciding whether its original request can still navigate. */
internal inline fun finishInlinePlaybackFailure(
    retireFailedPlayer: () -> Boolean,
    navigationAllowed: () -> Boolean,
    openRecording: () -> Unit,
    reportUnavailable: () -> Unit,
) {
    val ownedFailedPlayer = retireFailedPlayer()
    if (ownedFailedPlayer && navigationAllowed()) {
        try {
            openRecording()
            return
        } catch (_: RuntimeException) {
            // Failure feedback must also survive a rejected Activity start.
        }
    }
    reportUnavailable()
}

/**
 * Verification can finish before a translucent Activity resumes or a share popup returns focus.
 * Hold that result for its foreground handoff, but never revive a retired request.
 * All methods and callbacks belong to the main thread.
 */
internal class RecordingOpenResultDelivery<T : Any>(
    private val navigationAllowed: () -> Boolean,
    private val deliver: (T?) -> Unit,
    private val reportUndeliveredFailure: () -> Unit,
    private val finish: () -> Unit,
    private val deliverFailure: ((Throwable?) -> Unit)? = null,
    private val reportUndeliveredFailureWithCause: ((Throwable?) -> Unit)? = null,
) {
    private var retired = false
    private var completed = false
    private var terminal = false
    private var result: T? = null
    private var failure: Throwable? = null

    fun complete(value: T?, failure: Throwable? = null) {
        if (completed || terminal) return
        completed = true
        result = value
        this.failure = failure.takeIf { value == null }
        drain()
    }

    fun foregroundChanged() = drain()

    fun retire() {
        retired = true
        drain()
    }

    private fun drain() {
        if (!completed || terminal || (!retired && !navigationAllowed())) return
        terminal = true
        val value = result
        val error = failure
        result = null
        failure = null
        deliverTerminalResult(
            deliver = {
                if (retired) {
                    if (value == null) reportFailure(error)
                } else if (value == null) {
                    deliverVisibleTerminalOrFallback(
                        deliverVisible = {
                            if (deliverFailure != null) deliverFailure.invoke(error) else deliver(null)
                            true
                        },
                        fallback = { reportFailure(error) },
                    )
                } else {
                    deliver(value)
                }
            },
            finish = finish,
        )
    }

    private fun reportFailure(error: Throwable?) {
        if (reportUndeliveredFailureWithCause != null) reportUndeliveredFailureWithCause.invoke(error)
        else reportUndeliveredFailure()
    }
}
