package app.smallthingz.reverb

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private val backgroundRecordingResultScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private const val RECORDING_SAVED_NOTIFICATION_ID = 43
private const val RECORDING_SAVE_FAILED_NOTIFICATION_ID = 44
private const val RECORDING_SAVED_NOTIFICATION_TAG_PREFIX = "recording-saved:"
private const val RECORDING_SAVED_NOTIFICATION_ACTION_PREFIX = ".action.OPEN_SAVED_RECORDING."

internal fun recordingSavedNotificationKey(recordingId: String): String =
    UUID.nameUUIDFromBytes(recordingId.toByteArray(Charsets.UTF_8)).toString()

internal fun recordingSavedNotificationTag(recordingId: String): String =
    RECORDING_SAVED_NOTIFICATION_TAG_PREFIX + recordingSavedNotificationKey(recordingId)

internal fun recordingSavedPendingIntentKey(recordingId: String, fileIdentity: String): String {
    require(recordingId.isNotBlank()) { "Recording ID is required" }
    require(fileIdentity.isNotBlank()) { "Recording identity is required" }
    return UUID.nameUUIDFromBytes(
        (recordingId + "\u0000" + fileIdentity).toByteArray(Charsets.UTF_8),
    ).toString()
}

internal fun recordingSavedPendingIntentAction(
    packageName: String,
    recordingId: String,
    fileIdentity: String,
): String = packageName + RECORDING_SAVED_NOTIFICATION_ACTION_PREFIX +
    recordingSavedPendingIntentKey(recordingId, fileIdentity)

private fun releaseCaptureTimelineSnapshotOffMain(snapshot: ReverbService.TimelineSnapshot) {
    backgroundRecordingResultScope.launch {
        snapshot.releaseBestEffort()
    }
}

internal fun closeCaptureSnapshotsBestEffort(
    vararg snapshots: Closeable?,
    timelineRelease: (ReverbService.TimelineSnapshot) -> Unit = ::releaseCaptureTimelineSnapshotOffMain,
) {
    snapshots.forEach { snapshot ->
        when (snapshot) {
            is ReverbService.TimelineSnapshot -> timelineRelease(snapshot)
            null -> Unit
            else -> runCatching { snapshot.close() }
        }
    }
}

internal fun detachedSaveSuccessNeedsInAppFallback(
    notificationsEnabled: Boolean,
    channelEnabled: Boolean,
    runtimePermissionRequired: Boolean,
    runtimePermissionGranted: Boolean,
): Boolean = !notificationsEnabled || !channelEnabled ||
    (runtimePermissionRequired && !runtimePermissionGranted)

internal inline fun deliverDetachedSaveSuccess(
    notificationAvailable: () -> Boolean,
    notify: () -> Unit,
    fallback: () -> Unit,
): Boolean {
    val notified = try {
        if (!notificationAvailable()) {
            false
        } else {
            notify()
            true
        }
    } catch (_: Exception) {
        false
    }
    if (!notified) fallback()
    return notified
}

private fun ensureCaptureResultNotificationChannel(context: Context) {
    context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
        NotificationChannel(
            ReverbService.NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.app_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ),
    )
}

class NotifyFileReceiver(
    private val context: Context,
) : ReverbService.AudioFileReceiver {
    private val appContext = context.applicationContext
    override fun fileReady(recording: RecordingEntity) {
        backgroundRecordingResultScope.launch {
            deliverDetachedSaveSuccess(
                notificationAvailable = {
                    ensureCaptureResultNotificationChannel(appContext)
                    val notificationManager = NotificationManagerCompat.from(appContext)
                    val platformNotificationManager = appContext.getSystemService(NotificationManager::class.java)
                    val runtimePermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    val runtimePermissionGranted = !runtimePermissionRequired ||
                        ActivityCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                    !detachedSaveSuccessNeedsInAppFallback(
                        notificationsEnabled = notificationManager.areNotificationsEnabled(),
                        channelEnabled = platformNotificationManager
                            ?.getNotificationChannel(ReverbService.NOTIFICATION_CHANNEL_ID)
                            ?.importance
                            ?.let { importance -> importance != NotificationManager.IMPORTANCE_NONE }
                            ?: false,
                        runtimePermissionRequired = runtimePermissionRequired,
                        runtimePermissionGranted = runtimePermissionGranted,
                    )
                },
                notify = {
                    NotificationManagerCompat.from(appContext).notify(
                        recordingSavedNotificationTag(recording.id),
                        RECORDING_SAVED_NOTIFICATION_ID,
                        buildCaptureNotification(appContext, recording),
                    )
                },
                fallback = {
                    AppFeedbackCenter.post(appContext.getString(R.string.recording_saved), FeedbackTone.SUCCESS)
                },
            )
        }
    }

    override fun fileFailed(message: String, error: Throwable?) {
        val text = normalizedCaptureSaveFailureMessage(
            message = message,
            fallback = appContext.getString(R.string.save_failed),
        )
        AppFeedbackCenter.post(text, FeedbackTone.ERROR)
        backgroundRecordingResultScope.launch {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return@launch
            runCatching {
                NotificationManagerCompat.from(appContext).notify(
                    RECORDING_SAVE_FAILED_NOTIFICATION_ID,
                    buildCaptureFailureNotification(appContext, text),
                )
            }
        }
    }
}

internal fun normalizedCaptureSaveFailureMessage(message: String, fallback: String): String =
    message.trim().ifBlank { fallback.trim() }

fun buildCaptureNotification(context: Context, recording: RecordingEntity): Notification {
    ensureCaptureResultNotificationChannel(context)
    val intent = RecordingOpenActivity.intentFor(context, recording).apply {
        action = recordingSavedPendingIntentAction(
            context.packageName,
            recording.id,
            recording.fileIdentity,
        )
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(context, ReverbService.NOTIFICATION_CHANNEL_ID)
        .setContentTitle(context.getString(R.string.recording_saved))
        .setContentText(recording.displayName)
        .setSmallIcon(R.drawable.ic_notification_saved)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .build()
}

internal fun buildCaptureFailureNotification(context: Context, message: String): Notification {
    ensureCaptureResultNotificationChannel(context)
    val text = normalizedCaptureSaveFailureMessage(message, context.getString(R.string.save_failed))
    val pendingIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(context, ReverbService.NOTIFICATION_CHANNEL_ID)
        .setContentTitle(context.getString(R.string.save_failed))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setSmallIcon(R.drawable.ic_notification_recording)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setCategory(NotificationCompat.CATEGORY_ERROR)
        .build()
}

internal data class ExportRangeMemory(
    val bufferSlot: ReverbService.BufferSlot,
    val availableSeconds: Double,
)

internal data class ExportRange(
    val startSeconds: Float,
    val endSeconds: Float,
    val warningDurationSeconds: Float?,
    val rememberOnSave: ExportRangeMemory? = null,
)

internal data class ExportUiConfig(
    val format: ExportFormat,
    val codec: ExportCodec,
    val sampleFormat: PcmSampleFormat,
    val sampleRate: Int,
    val channelCount: Int,
)

internal data class BufferMetrics(
    val seconds: Float,
    val bytes: Long,
)

internal sealed interface CaptureSaveStatus {
    data class Saving(val cancellable: Boolean) : CaptureSaveStatus
    data class Saved(val recording: RecordingEntity) : CaptureSaveStatus
}

internal fun markExportCancelRequested(status: CaptureSaveStatus?): CaptureSaveStatus? =
    if (status is CaptureSaveStatus.Saving) status.copy(cancellable = false) else status

internal fun reconcileCaptureExportStatus(
    exporting: Boolean,
    receiverAttached: Boolean,
    status: CaptureSaveStatus?,
): CaptureSaveStatus? = when {
    exporting && status == null -> CaptureSaveStatus.Saving(cancellable = true)
    !exporting && !receiverAttached && status is CaptureSaveStatus.Saving -> null
    else -> status
}

internal fun captureExportUiBusy(
    exporting: Boolean,
    receiverAttached: Boolean,
    status: CaptureSaveStatus?,
): Boolean = exporting || (receiverAttached && status is CaptureSaveStatus.Saving)

internal fun captureServiceInteractionReady(
    serviceConnected: Boolean,
    stateHydrated: Boolean,
): Boolean = serviceConnected && stateHydrated

internal data class CaptureBufferReadout(
    val primary: String,
    val secondary: String?,
)

internal data class CaptureResolvedBufferState(
    val oneShotEnabled: Boolean,
    val oneShotFull: Boolean,
    val loopingEnabled: Boolean,
)

internal fun captureResolvedBufferState(
    retentionMode: RetentionMode?,
    oneShotEnabled: Boolean,
    oneShotFull: Boolean,
    loopingEnabled: Boolean,
): CaptureResolvedBufferState {
    val resolved = retentionMode != null
    val resolvedOneShotEnabled = resolved && oneShotEnabled
    return CaptureResolvedBufferState(
        oneShotEnabled = resolvedOneShotEnabled,
        oneShotFull = resolvedOneShotEnabled && oneShotFull,
        loopingEnabled = resolved && loopingEnabled,
    )
}

internal fun captureBufferReadout(
    retentionMode: RetentionMode?,
    disabled: Boolean,
    seconds: Float,
    bytes: Long,
    disabledLabel: String,
): CaptureBufferReadout {
    if (disabled) return CaptureBufferReadout(disabledLabel, null)
    if (retentionMode == null) return CaptureBufferReadout("—", null)
    val time = formatShortTimer(seconds.coerceAtLeast(0f))
    val size = formatShortFileSize(bytes.coerceAtLeast(0L))
    return if (retentionMode == RetentionMode.TIME) {
        CaptureBufferReadout(time, size)
    } else {
        CaptureBufferReadout(size, time)
    }
}


internal fun startExport(
    context: Context,
    service: ReverbService?,
    range: ExportRange,
    snapshot: ReverbService.TimelineSnapshot? = null,
    setSaving: (Boolean) -> Unit,
    onStatus: (CaptureSaveStatus?) -> Unit,
    onError: (String) -> Unit = {},
    onSaved: () -> Unit = {},
    canDisplay: () -> Boolean = { true },
    onReceiverCreated: (SaveResultReceiver) -> Unit = {},
    onReceiverTerminal: (SaveResultReceiver) -> Unit = {},
) {
    val recorder = service ?: run {
        closeCaptureSnapshotsBestEffort(snapshot)
        setSaving(false)
        onStatus(null)
        onError(context.getString(R.string.save_failed))
        return
    }
    setSaving(true)
    onStatus(CaptureSaveStatus.Saving(cancellable = true))
    val appContext = context.applicationContext
    val receiver = SaveResultReceiver(
        context = appContext,
        setSaving = setSaving,
        onStatus = onStatus,
        onError = onError,
        onSaved = onSaved,
        canDisplay = canDisplay,
        onCommitted = { recording ->
            range.rememberOnSave?.let { memory ->
                rememberSuccessfulRangeExport(
                    context = appContext,
                    bufferSlot = memory.bufferSlot,
                    availableSeconds = memory.availableSeconds,
                    startSeconds = range.startSeconds,
                    endSeconds = range.endSeconds,
                    actualSelectionMillis = recording.durationMillis,
                )
            }
        },
        onTerminal = onReceiverTerminal,
    )
    onReceiverCreated(receiver)
    try {
        if (snapshot != null) {
            recorder.dumpRecordingRange(snapshot, range.startSeconds, range.endSeconds, receiver, "")
        } else {
            recorder.dumpRecordingRange(range.startSeconds, range.endSeconds, receiver, "")
        }
    } catch (error: Exception) {
        closeCaptureSnapshotsBestEffort(snapshot)
        receiver.fileFailed(context.getString(R.string.save_failed), error)
    }
}

internal fun buildCustomExportRange(
    availableSeconds: Double,
    requestedStartSeconds: Float,
    requestedEndSeconds: Float,
    exportConfig: ExportUiConfig,
): ExportRange {
    val available = availableSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    val exactStart = rangeSnapshotStartSeconds(available, requestedStartSeconds, requestedEndSeconds)
    val exactEnd = rangeSnapshotEndSeconds(available, requestedStartSeconds, requestedEndSeconds)
    val start = exactStart.toFloat()
    val end = exactEnd.toFloat()
    val maxDuration = exportDurationLimitExactSeconds(
        exportConfig.format,
        exportConfig.codec,
        exportConfig.sampleRate,
        exportConfig.channelCount,
        exportConfig.sampleFormat,
    )
    val selectedDuration = exactEnd - exactStart
    // Empty input is rejection, not overflow: never expand it into a different export.
    return if (selectedDuration <= 0.0 || rangeExportSelectionWithinLimit(selectedDuration, maxDuration)) {
        ExportRange(start, end, null)
    } else {
        ExportRange(
            startSeconds = (exactEnd - maxDuration).coerceAtLeast(0.0).toFloat(),
            endSeconds = end,
            warningDurationSeconds = maxDuration.toFloat(),
        )
    }
}

internal inline fun <T> deliverTerminalResult(
    deliver: () -> T,
    finish: () -> Unit,
): T {
    var deliveryFailure: Throwable? = null
    try {
        return deliver()
    } catch (error: Throwable) {
        deliveryFailure = error
        throw error
    } finally {
        try {
            finish()
        } catch (cleanupError: Throwable) {
            val primary = deliveryFailure
            if (primary == null) {
                throw cleanupError
            }
            if (cleanupError !== primary) primary.addSuppressed(cleanupError)
        }
    }
}

internal inline fun deliverVisibleTerminalOrFallback(
    deliverVisible: () -> Boolean,
    fallback: () -> Unit,
) {
    var deliveryFailure: Throwable? = null
    val delivered = try {
        deliverVisible()
    } catch (error: Throwable) {
        deliveryFailure = error
        false
    }
    if (!delivered) {
        try {
            fallback()
        } catch (fallbackError: Throwable) {
            val primary = deliveryFailure
            if (primary == null) throw fallbackError
            if (fallbackError !== primary) primary.addSuppressed(fallbackError)
        }
    }
    deliveryFailure?.let { throw it }
}

internal class SaveUiCallbackGate(
    setSaving: (Boolean) -> Unit,
    onStatus: (CaptureSaveStatus?) -> Unit,
    onError: (String) -> Unit,
    onSaved: () -> Unit,
    canDisplay: () -> Boolean = { true },
    onHiddenSaved: ((RecordingEntity) -> Unit)? = null,
) {
    private var canDisplayCallback: (() -> Boolean)? = canDisplay
    private var hiddenSavedCallback: ((RecordingEntity) -> Unit)? = onHiddenSaved
    private var setSavingCallback: ((Boolean) -> Unit)? = setSaving
    private var statusCallback: ((CaptureSaveStatus?) -> Unit)? = onStatus
    private var errorCallback: ((String) -> Unit)? = onError
    private var savedCallback: (() -> Unit)? = onSaved
    var attached: Boolean = true
        private set

    fun detach() {
        attached = false
        canDisplayCallback = null
        hiddenSavedCallback = null
        setSavingCallback = null
        statusCallback = null
        errorCallback = null
        savedCallback = null
    }

    fun saved(recording: RecordingEntity): Boolean {
        if (!attached) return false
        val visible = canDisplayCallback?.invoke() == true
        statusCallback?.invoke(if (visible) CaptureSaveStatus.Saved(recording) else null)
        setSavingCallback?.invoke(false)
        savedCallback?.invoke()
        if (visible) return true
        val deliverHidden = hiddenSavedCallback ?: return false
        deliverHidden(recording)
        return true
    }

    fun failed(message: String): Boolean {
        if (!attached) return false
        val visible = canDisplayCallback?.invoke() == true
        setSavingCallback?.invoke(false)
        statusCallback?.invoke(null)
        if (visible) errorCallback?.invoke(message)
        return visible
    }

    fun cancelled(): Boolean {
        if (!attached) return false
        setSavingCallback?.invoke(false)
        statusCallback?.invoke(null)
        return true
    }
}

internal class SaveResultReceiver(
    private val uiCallbacks: SaveUiCallbackGate,
    private val fallback: ReverbService.AudioFileReceiver,
    private val defaultFailureMessage: () -> String,
    private val onCommitted: (RecordingEntity) -> Unit = {},
    private val onTerminal: (SaveResultReceiver) -> Unit = {},
) : ReverbService.AudioFileReceiver {
    constructor(
        context: Context,
        setSaving: (Boolean) -> Unit,
        onStatus: (CaptureSaveStatus?) -> Unit,
        onError: (String) -> Unit = {},
        onSaved: () -> Unit = {},
        canDisplay: () -> Boolean = { true },
        onCommitted: (RecordingEntity) -> Unit = {},
        onTerminal: (SaveResultReceiver) -> Unit = {},
    ) : this(
        uiCallbacks = SaveUiCallbackGate(
            setSaving, onStatus, onError, onSaved, canDisplay,
            onHiddenSaved = context.applicationContext.let { appContext ->
                { _ ->
                    AppFeedbackCenter.post(
                        appContext.getString(R.string.recording_saved),
                        FeedbackTone.SUCCESS,
                    )
                }
            },
        ),
        fallback = NotifyFileReceiver(context.applicationContext),
        defaultFailureMessage = context.applicationContext.let { appContext ->
            { appContext.getString(R.string.save_failed) }
        },
        onCommitted = onCommitted,
        onTerminal = onTerminal,
    )

    private val terminalDelivered = AtomicBoolean(false)

    fun detachUi() = uiCallbacks.detach()

    override fun fileReady(recording: RecordingEntity) {
        if (!terminalDelivered.compareAndSet(false, true)) return
        deliverTerminalResult(
            deliver = {
                // Range-memory bookkeeping is convenience state; it must never suppress terminal
                // delivery for a recording that is already durably committed.
                runCatching { onCommitted(recording) }
                deliverVisibleTerminalOrFallback(
                    deliverVisible = { uiCallbacks.saved(recording) },
                    fallback = { fallback.fileReady(recording) },
                )
            },
            finish = ::finish,
        )
    }

    override fun fileFailed(message: String, error: Throwable?) {
        if (!terminalDelivered.compareAndSet(false, true)) return
        deliverTerminalResult(
            deliver = {
                val text = if (message.isBlank()) defaultFailureMessage() else message
                deliverVisibleTerminalOrFallback(
                    deliverVisible = { uiCallbacks.failed(text) },
                    fallback = { fallback.fileFailed(message, error) },
                )
            },
            finish = ::finish,
        )
    }

    override fun fileCancelled() {
        if (!terminalDelivered.compareAndSet(false, true)) return
        deliverTerminalResult(
            deliver = { uiCallbacks.cancelled() },
            finish = ::finish,
        )
    }

    private fun finish() {
        uiCallbacks.detach()
        onTerminal(this)
    }
}

internal fun currentExportConfig(context: Context, recorder: ReverbService?): ExportUiConfig {
    val activeConfig = recorder?.getConfigurationSnapshot()
    val format = activeConfig?.format ?: getConfiguredOutputFormat(context)
    val codec = activeConfig?.codec ?: getConfiguredOutputCodec(context)
    val sampleFormat = activeConfig?.sampleFormat ?: getConfiguredPcmSampleFormat(context)
    val channelMode = activeConfig?.channelMode ?: getConfiguredChannelMode(context)
    val sampleRate = activeConfig?.sampleRate ?: getConfiguredSampleRate(context)
    return ExportUiConfig(
        format = format,
        codec = codec,
        sampleFormat = sampleFormat,
        sampleRate = sampleRate,
        channelCount = channelMode.channelCount,
    )
}
