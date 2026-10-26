package app.smallthingz.reverb

import android.content.Context
import androidx.core.net.toUri
import java.io.Closeable
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean

internal fun canEnterInlineTrim(prepared: Boolean, durationMillis: Int): Boolean =
    prepared && durationMillis > 0

internal enum class InlineFineSeekTarget {
    PLAYHEAD,
    TRIM_START,
    TRIM_END,
}

internal data class InlineFineSeekValues(
    val cursorMillis: Int,
    val startMillis: Int,
    val endMillis: Int,
)

internal fun adjustInlineTrimTarget(
    values: InlineFineSeekValues,
    durationMillis: Int,
    target: InlineFineSeekTarget,
    requestedMillis: Int,
): InlineFineSeekValues {
    require(target != InlineFineSeekTarget.PLAYHEAD)
    val duration = durationMillis.coerceAtLeast(1)
    val minimumRange = minOf(50, duration)
    var start = values.startMillis.coerceIn(0, duration)
    var end = values.endMillis.coerceIn(start, duration)
    if (end - start < minimumRange) {
        end = (start.toLong() + minimumRange).coerceAtMost(duration.toLong()).toInt()
        start = (end - minimumRange).coerceAtLeast(0)
    }
    when (target) {
        InlineFineSeekTarget.TRIM_START -> {
            val requested = requestedMillis.coerceIn(0, (duration - minimumRange).coerceAtLeast(0))
            if (requested > end - minimumRange) {
                start = requested
                end = (start + minimumRange).coerceAtMost(duration)
            } else {
                start = requested
            }
        }
        InlineFineSeekTarget.TRIM_END -> {
            val requested = requestedMillis.coerceIn(minimumRange.coerceAtMost(duration), duration)
            if (requested < start + minimumRange) {
                end = requested
                start = (end - minimumRange).coerceAtLeast(0)
            } else {
                end = requested
            }
        }
        InlineFineSeekTarget.PLAYHEAD -> error("Playhead is not a trim boundary")
    }
    return InlineFineSeekValues(
        cursorMillis = values.cursorMillis.coerceIn(0, duration),
        startMillis = start,
        endMillis = end,
    )
}

internal fun adjustInlineFineSeekTarget(
    values: InlineFineSeekValues,
    durationMillis: Int,
    target: InlineFineSeekTarget,
    deltaMillis: Int,
): InlineFineSeekValues {
    val duration = durationMillis.coerceAtLeast(1)
    val cursor = values.cursorMillis.coerceIn(0, duration)
    if (target == InlineFineSeekTarget.PLAYHEAD) {
        val requested = (cursor.toLong() + deltaMillis.toLong()).coerceIn(0L, duration.toLong()).toInt()
        val start = values.startMillis.coerceIn(0, duration)
        val end = values.endMillis.coerceIn(start, duration)
        return InlineFineSeekValues(requested, start, end)
    }
    val current = when (target) {
        InlineFineSeekTarget.TRIM_START -> values.startMillis
        InlineFineSeekTarget.TRIM_END -> values.endMillis
        InlineFineSeekTarget.PLAYHEAD -> cursor
    }
    val requested = (current.toLong() + deltaMillis.toLong())
        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        .toInt()
    return adjustInlineTrimTarget(values, duration, target, requested)
}

internal fun inlineTrimGestureTarget(
    pointerMillis: Int,
    startMillis: Int,
    endMillis: Int,
    handleThresholdMillis: Int,
    activeBoundary: InlineFineSeekTarget,
): InlineFineSeekTarget {
    require(activeBoundary != InlineFineSeekTarget.PLAYHEAD)
    val threshold = handleThresholdMillis.coerceAtLeast(0)
    val startDistance = kotlin.math.abs(pointerMillis - startMillis)
    val endDistance = kotlin.math.abs(pointerMillis - endMillis)
    return when {
        startDistance <= threshold && startDistance <= endDistance -> InlineFineSeekTarget.TRIM_START
        endDistance <= threshold -> InlineFineSeekTarget.TRIM_END
        else -> activeBoundary
    }
}

internal data class InlinePlayerMediaSource(
    val descriptor: FileDescriptor,
    val owner: Closeable,
) : Closeable {
    override fun close() = owner.close()
}

internal fun openInlinePlayerMediaSource(
    context: Context,
    recording: RecordingEntity,
    cleanup: RecordingReadCleanup,
): InlinePlayerMediaSource = when (recording.storageType) {
    RecordingStorageType.FILE -> {
        val stream = openVerifiedFileInputStream(recording, cleanup)
            ?: throw IllegalStateException("Recording changed on disk")
        InlinePlayerMediaSource(stream.fd, stream)
    }
    RecordingStorageType.DOCUMENT,
    RecordingStorageType.MEDIASTORE,
    -> {
        if (!recordingReadIdentityIsStable(recording) ||
            !recordingContentIdentityMatches(context, recording)
        ) {
            throw IllegalStateException("Recording changed in provider")
        }
        val descriptor = context.contentResolver.openFileDescriptor(recording.id.toUri(), "r")
            ?: throw IllegalStateException("Recording unavailable in provider")
        if (!recordingContentIdentityMatches(context, recording) ||
            !providerDescriptorMatchesIdentity(recording.fileIdentity, descriptor.fileDescriptor)
        ) {
            throwAfterClosePreservingPrimary(
                IllegalStateException("Recording changed in provider while opening"),
            ) {
                cleanup.close { descriptor.close() }
            }
        }
        InlinePlayerMediaSource(descriptor.fileDescriptor, descriptor)
    }
}

internal fun inlineShuttleFailureShouldResume(
    shuttleActive: Boolean,
    resumeAfterScrub: Boolean,
    lifecycleResumed: Boolean,
): Boolean = shuttleActive && resumeAfterScrub && lifecycleResumed

internal inline fun handleInlinePlaybackError(
    ownsPlaybackResources: Boolean,
    shouldReportFailure: Boolean,
    releaseResources: () -> Unit,
    reportFailure: () -> Unit,
) {
    if (!ownsPlaybackResources) return
    releaseResources()
    if (shouldReportFailure) reportFailure()
}

// MediaPlayer seeks are asynchronous. Serialize them so an older completion cannot
// start playback or expose its stale position to the trim-end monitor.
internal class InlinePlaybackSeekQueue {
    var pending: Boolean = false
        private set
    var resume: Boolean = false
    private var queuedPosition: Int? = null

    fun request(position: Int, resumePlayback: Boolean): Int? {
        resume = resumePlayback
        if (pending) {
            queuedPosition = position
            return null
        }
        pending = true
        return position
    }

    fun completed(): Int? {
        val next = queuedPosition
        queuedPosition = null
        if (next == null) pending = false
        return next
    }

    fun cancel() {
        pending = false
        queuedPosition = null
        resume = false
    }
}

internal fun inlineTrimPlaybackStartMillis(
    startMillis: Int,
    endMillis: Int,
    target: InlineFineSeekTarget,
): Int = if (target == InlineFineSeekTarget.TRIM_END) {
    (endMillis.toLong() - 3_000L).coerceAtLeast(startMillis.toLong()).toInt()
} else {
    startMillis
}

internal class InlinePlayerBookkeeping {
    val seeks = InlinePlaybackSeekQueue()
    val fineSeekPosition = FineSeekPosition<InlineFineSeekTarget>()
    var seekResumeOwner: InlinePlaybackRowOwner? = null
        private set
    var resumeAfterScrub = false
    var released = false
    var fineSeekShuttleActive = false
    var initialAutoStartPending = true

    fun requestSeek(position: Int, resumeOwner: InlinePlaybackRowOwner?): Int? {
        seekResumeOwner = resumeOwner
        return seeks.request(position, resumePlayback = resumeOwner != null)
    }

    fun revokeSeekResume() {
        seeks.resume = false
        seekResumeOwner = null
    }

    fun revokeResume() {
        fineSeekPosition.reset()
        revokeSeekResume()
        resumeAfterScrub = false
    }

    fun retireExpansion(pause: () -> Unit) {
        initialAutoStartPending = false
        revokeResume()
        pause()
    }
}

// The expansion itself owns playback; an animated outgoing row may still be composed.
internal class InlinePlaybackRowOwner(val recordingId: String) {
    // The expansion survives lazy-row disposal; its initial playback grant must too.
    private var initialAutoStartAvailable = true

    fun consumeInitialAutoStart(): Boolean {
        if (!initialAutoStartAvailable) return false
        initialAutoStartAvailable = false
        return true
    }
}

internal inline fun startInlinePlaybackForCurrentRow(
    owner: InlinePlaybackRowOwner?,
    isCurrentOwner: (InlinePlaybackRowOwner) -> Boolean,
    start: () -> Unit,
): Boolean {
    if (owner == null || !isCurrentOwner(owner)) return false
    start()
    return true
}

internal inline fun finishInlineBackForCurrentRow(
    owner: InlinePlaybackRowOwner?,
    isCurrentOwner: (InlinePlaybackRowOwner) -> Boolean,
    trimSaving: Boolean,
    trimMode: Boolean,
    leaveTrim: () -> Unit,
    collapse: () -> Unit,
): Boolean {
    if (owner == null || !isCurrentOwner(owner) || trimSaving) return false
    // A lazy row can be recreated while its accepted trim still owns this recording.
    // Its new local saving flag is false; the process reservation remains authoritative.
    if (recordingMutations.isActive(owner.recordingId)) return false
    if (trimMode) leaveTrim() else collapse()
    return true
}

internal class InlineTrimUiCallbackGate(
    onSaved: (RecordingEntity) -> Unit,
    onFailed: (Throwable) -> Unit,
    visible: Boolean,
) {
    private var savedCallback: ((RecordingEntity) -> Unit)? = onSaved
    private var failedCallback: ((Throwable) -> Unit)? = onFailed
    var attached: Boolean = true
        private set
    var visible: Boolean = visible
        private set

    fun setVisible(visible: Boolean) {
        if (attached) this.visible = visible
    }

    fun detach() {
        attached = false
        visible = false
        savedCallback = null
        failedCallback = null
    }

    fun saved(recording: RecordingEntity): Boolean {
        if (!attached) return false
        savedCallback?.invoke(recording)
        return visible
    }

    fun failed(error: Throwable): Boolean {
        if (!attached) return false
        failedCallback?.invoke(error)
        return visible
    }
}

internal class InlineTrimResultReceiver(
    onSaved: (RecordingEntity) -> Unit,
    onFailed: (Throwable) -> Unit,
    uiVisible: Boolean,
    private val onDetachedSuccess: (RecordingEntity) -> Unit,
    private val onDetachedFailure: (Throwable) -> Unit,
    private val onTerminal: (InlineTrimResultReceiver) -> Unit,
) {
    private val uiCallbacks = InlineTrimUiCallbackGate(onSaved, onFailed, uiVisible)
    private val terminalDelivered = AtomicBoolean(false)

    fun setUiVisible(visible: Boolean) = uiCallbacks.setVisible(visible)

    fun detachUi() = uiCallbacks.detach()

    fun terminal(result: Result<RecordingEntity>) {
        if (!terminalDelivered.compareAndSet(false, true)) return
        deliverTerminalResult(
            deliver = {
                result.fold(
                    onSuccess = { recording ->
                        deliverVisibleTerminalOrFallback(
                            deliverVisible = { uiCallbacks.saved(recording) },
                            fallback = { onDetachedSuccess(recording) },
                        )
                    },
                    onFailure = { error ->
                        deliverVisibleTerminalOrFallback(
                            deliverVisible = { uiCallbacks.failed(error) },
                            fallback = { onDetachedFailure(error) },
                        )
                    },
                )
            },
            finish = {
                uiCallbacks.detach()
                onTerminal(this)
            },
        )
    }
}

internal fun inlinePlaybackCallbackIsCurrent(
    released: Boolean,
    disposed: Boolean,
    samePlayer: Boolean,
): Boolean = !released && !disposed && samePlayer

internal fun inlinePlaybackShouldAutoStart(
    prepared: Boolean,
    initialAutoStartPending: Boolean,
    lifecycleResumed: Boolean,
    blocked: Boolean = false,
): Boolean = prepared && initialAutoStartPending && lifecycleResumed && !blocked

internal inline fun submitInlinePlaybackAutoStart(
    playback: InlinePlayerBookkeeping,
    owner: InlinePlaybackRowOwner?,
    prepared: Boolean,
    lifecycleResumed: Boolean,
    blocked: Boolean,
    seekAndResume: () -> Unit,
): Boolean {
    if (!inlinePlaybackShouldAutoStart(prepared, playback.initialAutoStartPending, lifecycleResumed, blocked)) {
        return false
    }
    if (owner?.consumeInitialAutoStart() != true) return false
    // A pending native seek is drained by the queue; it must not strand this new intent.
    playback.initialAutoStartPending = false
    seekAndResume()
    return true
}

