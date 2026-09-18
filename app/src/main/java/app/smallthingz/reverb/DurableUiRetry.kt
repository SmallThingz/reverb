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

internal suspend fun runDurableUiBooleanAttempt(
    block: suspend () -> Boolean,
): Boolean = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

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
