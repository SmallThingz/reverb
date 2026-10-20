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
) {
    private var retired = false
    private var completed = false
    private var terminal = false
    private var result: T? = null

    fun complete(value: T?) {
        if (completed || terminal) return
        completed = true
        result = value
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
        result = null
        try {
            if (retired) {
                if (value == null) reportUndeliveredFailure()
            } else {
                deliver(value)
            }
        } finally {
            finish()
        }
    }
}
