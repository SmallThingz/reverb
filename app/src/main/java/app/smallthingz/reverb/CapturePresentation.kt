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

internal typealias CaptureBufferUiState = smallthingz.ui.reverb.home.ReverbHomeCaptureState

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
): Float = smallthingz.ui.reverb.home.bufferSwipeProgress(
    source == ReverbService.BufferSlot.ONE_SHOT, horizontalDragPx, viewportWidthPx,
)

internal fun shouldCommitBufferSwipe(progress: Float): Boolean =
    smallthingz.ui.reverb.home.shouldCommitBufferSwipe(progress)

internal fun bufferTransitionDisplayedSlot(
    source: ReverbService.BufferSlot,
    target: ReverbService.BufferSlot,
    progress: Float,
): ReverbService.BufferSlot = smallthingz.ui.reverb.home.bufferTransitionDisplayedSlot(source, target, progress)

internal fun bufferTransitionFlipDegrees(
    source: ReverbService.BufferSlot,
    target: ReverbService.BufferSlot,
    progress: Float,
): Float = smallthingz.ui.reverb.home.bufferTransitionFlipDegrees(
    source == target, target == ReverbService.BufferSlot.ONE_SHOT, progress,
)

internal fun bufferTransitionPivotFractionX(): Float = smallthingz.ui.reverb.home.bufferTransitionPivotFractionX()

internal fun bufferTransitionDepthScale(progress: Float): Float =
    smallthingz.ui.reverb.home.bufferTransitionDepthScale(progress)
