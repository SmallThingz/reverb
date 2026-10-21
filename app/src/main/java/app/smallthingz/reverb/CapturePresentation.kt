package app.smallthingz.reverb

internal fun shouldApplyRecorderStateSnapshot(
    snapshotConnectionGeneration: Long,
    currentConnectionGeneration: Long,
    snapshotGeneration: Long,
    latestCommandGeneration: Long,
): Boolean = snapshotConnectionGeneration == currentConnectionGeneration &&
    snapshotGeneration >= latestCommandGeneration

internal fun captureSelectedBufferAfterRecorderState(
    currentSelection: ReverbService.BufferSlot,
    wasListening: Boolean,
    previousActiveBuffer: ReverbService.BufferSlot?,
    listening: Boolean,
    activeBuffer: ReverbService.BufferSlot?,
): ReverbService.BufferSlot {
    if (!listening || activeBuffer == null) return currentSelection
    return if (!wasListening || previousActiveBuffer != activeBuffer) activeBuffer else currentSelection
}

internal fun timelineSnapshotRequestIsCurrent(
    requestGeneration: Long,
    latestRequestGeneration: Long,
): Boolean = requestGeneration == latestRequestGeneration

internal fun shouldApplyCustomRangeSnapshot(
    requestGeneration: Long,
    latestRequestGeneration: Long,
    requestedBuffer: ReverbService.BufferSlot,
    pendingBuffer: ReverbService.BufferSlot?,
    selectedBuffer: ReverbService.BufferSlot,
): Boolean = requestGeneration == latestRequestGeneration &&
    pendingBuffer == requestedBuffer &&
    selectedBuffer == requestedBuffer

internal enum class CaptureBufferUiState {
    READY,
    RECORDING,
    FILLED,
    DISABLED,
}

internal fun isBufferActivelyRecording(
    bufferSlot: ReverbService.BufferSlot,
    isListening: Boolean,
    activeBuffer: ReverbService.BufferSlot?,
): Boolean = isListening && activeBuffer == bufferSlot

internal fun captureBufferUiState(
    bufferSlot: ReverbService.BufferSlot,
    enabled: Boolean,
    oneShotFull: Boolean,
    isListening: Boolean,
    activeBuffer: ReverbService.BufferSlot?,
): CaptureBufferUiState {
    return when {
        !enabled -> CaptureBufferUiState.DISABLED
        isBufferActivelyRecording(bufferSlot, isListening, activeBuffer) -> CaptureBufferUiState.RECORDING
        bufferSlot == ReverbService.BufferSlot.ONE_SHOT && oneShotFull -> CaptureBufferUiState.FILLED
        else -> CaptureBufferUiState.READY
    }
}

internal fun isCaptureBlockedByOtherBuffer(
    bufferSlot: ReverbService.BufferSlot,
    isListening: Boolean,
    activeBuffer: ReverbService.BufferSlot?,
): Boolean = isListening && activeBuffer != null && activeBuffer != bufferSlot

internal enum class CaptureBlobTapAction {
    START,
    STOP,
    SWITCH,
    NONE,
}

internal fun captureBlobTapAction(
    requested: ReverbService.BufferSlot,
    isListening: Boolean,
    activeBuffer: ReverbService.BufferSlot?,
    oneShotEnabled: Boolean,
    oneShotFull: Boolean,
    loopingEnabled: Boolean,
): CaptureBlobTapAction = when {
    isListening && activeBuffer == requested -> CaptureBlobTapAction.STOP
    !canActivateCaptureBuffer(requested, oneShotEnabled, oneShotFull, loopingEnabled) -> CaptureBlobTapAction.NONE
    isListening -> CaptureBlobTapAction.SWITCH
    else -> CaptureBlobTapAction.START
}

internal fun oppositeBufferSlot(bufferSlot: ReverbService.BufferSlot): ReverbService.BufferSlot =
    when (bufferSlot) {
        ReverbService.BufferSlot.ONE_SHOT -> ReverbService.BufferSlot.LOOPING
        ReverbService.BufferSlot.LOOPING -> ReverbService.BufferSlot.ONE_SHOT
    }

internal fun bufferSwipeProgress(
    source: ReverbService.BufferSlot,
    horizontalDragPx: Float,
    viewportWidthPx: Float,
): Float {
    if (viewportWidthPx <= 0f) return 0f
    val forwardDistance = when (source) {
        ReverbService.BufferSlot.ONE_SHOT -> -horizontalDragPx
        ReverbService.BufferSlot.LOOPING -> horizontalDragPx
    }
    return (forwardDistance / viewportWidthPx).coerceIn(0f, 1f)
}

private const val BUFFER_SWIPE_COMMIT_PROGRESS = 0.16f
private const val BUFFER_FLIP_MIDPOINT_SCALE = 0.94f

internal fun shouldCommitBufferSwipe(progress: Float): Boolean =
    progress.coerceIn(0f, 1f) >= BUFFER_SWIPE_COMMIT_PROGRESS

internal fun bufferTransitionDisplayedSlot(
    source: ReverbService.BufferSlot,
    target: ReverbService.BufferSlot,
    progress: Float,
): ReverbService.BufferSlot = if (progress.coerceIn(0f, 1f) >= 0.5f) target else source

internal fun bufferTransitionFlipDegrees(
    source: ReverbService.BufferSlot,
    target: ReverbService.BufferSlot,
    progress: Float,
): Float {
    if (source == target) return 0f
    val p = progress.coerceIn(0f, 1f)
    // Opposite navigation directions use opposite rotations. The outgoing face reaches
    // +/-90 degrees and the incoming face appears from the complementary angle.
    val direction = when (target) {
        ReverbService.BufferSlot.LOOPING -> -1f
        ReverbService.BufferSlot.ONE_SHOT -> 1f
    }
    return if (p < 0.5f) {
        direction * p * 180f
    } else {
        // The rendered face switches to the target at the midpoint, so use the
        // complementary edge-on angle from that exact frame onward.
        direction * (p - 1f) * 180f
    }
}

internal fun bufferTransitionPivotFractionX(): Float = 0.5f

internal fun bufferTransitionDepthScale(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    val distanceFromMidpoint = kotlin.math.abs(p * 2f - 1f)
    return BUFFER_FLIP_MIDPOINT_SCALE + (1f - BUFFER_FLIP_MIDPOINT_SCALE) * distanceFromMidpoint
}

