package app.smallthingz.reverb

/**
 * Exact Clear ownership is admitted under its short bookkeeping lock, never the intent/store lock.
 * Callbacks must only read ownership and publish cancellation flags/status; no storage work belongs
 * here. Teardown closes this same admission lock before waiting for an in-flight durable step.
 */
internal inline fun <T> requestCurrentBufferClearCancellation(
    admissionLock: Any,
    admissionClosed: () -> Boolean,
    currentOperation: () -> T?,
    matches: (T) -> Boolean,
    request: (T) -> Unit,
): Boolean = synchronized(admissionLock) {
    if (admissionClosed()) return@synchronized false
    val operation = currentOperation()?.takeIf(matches) ?: return@synchronized false
    request(operation)
    true
}

/** A progress sample taken before Cancel must not repaint RUNNING after Cancel was accepted. */
internal fun bufferClearProgressPhase(
    sampledPhase: BufferClearPhase,
    cancelRequested: Boolean,
): BufferClearPhase =
    if (cancelRequested && sampledPhase.isActive) BufferClearPhase.CANCELLING else sampledPhase
