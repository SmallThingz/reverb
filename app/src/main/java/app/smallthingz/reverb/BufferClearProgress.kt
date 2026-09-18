package app.smallthingz.reverb

internal enum class BufferClearPhase {
    STARTING,
    RUNNING,
    CANCELLING,
    COMPLETED,
    CANCELLED,
    FAILED,
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == CANCELLED || this == FAILED

    val isActive: Boolean
        get() = !isTerminal
}

internal data class BufferClearStatus(
    val operationId: Long,
    val bufferSlot: ReverbService.BufferSlot,
    val phase: BufferClearPhase,
    val totalBytes: Long = 0L,
    val remainingBytes: Long = totalBytes,
    val totalChunks: Int = 0,
    val remainingChunks: Int = totalChunks,
)

internal fun bufferClearProgressFraction(status: BufferClearStatus): Float? {
    if (status.phase == BufferClearPhase.COMPLETED) return 1f
    if (status.totalBytes > 0L) {
        val remaining = status.remainingBytes.coerceIn(0L, status.totalBytes)
        return ((status.totalBytes - remaining).toDouble() / status.totalBytes.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }
    if (status.totalChunks > 0) {
        val remaining = status.remainingChunks.coerceIn(0, status.totalChunks)
        return ((status.totalChunks - remaining).toFloat() / status.totalChunks.toFloat())
            .coerceIn(0f, 1f)
    }
    return null
}

internal fun bufferClearCancellationTerminal(reportFailure: Boolean): BufferClearPhase =
    if (reportFailure) BufferClearPhase.FAILED else BufferClearPhase.CANCELLED

internal fun bufferClearCanContinue(
    cancelRequested: Boolean,
    serviceDestroying: Boolean,
    requestedBuffer: ReverbService.BufferSlot,
    activeBuffer: ReverbService.BufferSlot,
    listeningIntentEnabled: Boolean,
    acceptedGeneration: Long,
    currentGeneration: Long,
): Boolean = !cancelRequested &&
    !serviceDestroying &&
    clearBufferCommandMayExecute(
        requestedBuffer = requestedBuffer,
        activeBuffer = activeBuffer,
        listeningIntentEnabled = listeningIntentEnabled,
        acceptedGeneration = acceptedGeneration,
        currentGeneration = currentGeneration,
    )
