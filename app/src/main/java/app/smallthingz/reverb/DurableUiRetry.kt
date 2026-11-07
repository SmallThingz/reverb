package app.smallthingz.reverb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val DURABLE_UI_RETRY_INITIAL_MILLIS = 500L
private const val DURABLE_UI_RETRY_MAX_MILLIS = 30_000L

private val committedDurableUiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal fun nextDurableUiRetryDelayMillis(currentMillis: Long): Long =
    (currentMillis * 2L).coerceAtMost(DURABLE_UI_RETRY_MAX_MILLIS)

/** A false-only platform result carries no invented cause. Cancellation keeps coroutine semantics. */
internal data class DurableUiAttemptResult(val succeeded: Boolean, val failure: Exception? = null)

internal suspend fun runDurableUiAttempt(block: suspend () -> Boolean): DurableUiAttemptResult = try {
    DurableUiAttemptResult(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    DurableUiAttemptResult(false, failure)
}

internal suspend fun runDurableUiBooleanAttempt(
    block: suspend () -> Boolean,
): Boolean = runDurableUiAttempt(block).succeeded

internal suspend fun runCommittedDurableUiBooleanAttempt(
    block: suspend () -> Boolean,
): Boolean = runDurableUiBooleanAttempt {
    withContext(Dispatchers.IO + NonCancellable) { block() }
}

internal fun submitCommittedDurableUiBooleanAttempt(
    block: suspend () -> Boolean,
    onTerminal: (Boolean) -> Unit,
) {
    committedDurableUiScope.launch {
        val result = runCommittedDurableUiBooleanAttempt(block)
        runCatching { onTerminal(result) }
    }
}
