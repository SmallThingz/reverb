package app.smallthingz.reverb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal const val DURABLE_UI_RETRY_INITIAL_MILLIS = 500L
private const val DURABLE_UI_RETRY_MAX_MILLIS = 30_000L

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
