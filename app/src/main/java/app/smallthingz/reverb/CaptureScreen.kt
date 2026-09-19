package app.smallthingz.reverb

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import android.os.IBinder
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

internal fun recordingSavedPendingIntentAction(packageName: String, recordingId: String): String =
    packageName + RECORDING_SAVED_NOTIFICATION_ACTION_PREFIX + recordingSavedNotificationKey(recordingId)

internal fun closeCaptureSnapshotsBestEffort(vararg snapshots: Closeable?) {
    snapshots.forEach { snapshot ->
        when (snapshot) {
            is ReverbService.TimelineSnapshot -> snapshot.releaseBestEffort()
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
        action = recordingSavedPendingIntentAction(context.packageName, recording.id)
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

private data class ExportRangeMemory(
    val bufferSlot: ReverbService.BufferSlot,
    val availableSeconds: Double,
)

private data class ExportRange(
    val startSeconds: Float,
    val endSeconds: Float,
    val warningDurationSeconds: Float?,
    val rememberOnSave: ExportRangeMemory? = null,
)

private data class ExportUiConfig(
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

internal fun captureServiceBindingCallbackIsCurrent(
    callbackBindingGeneration: Long,
    currentBindingGeneration: Long,
    screenAlive: Boolean,
): Boolean = screenAlive && callbackBindingGeneration == currentBindingGeneration

private class CaptureScreenBookkeeping {
    var startupBufferChosen = false
    var latestListeningCommandGeneration = Long.MIN_VALUE
    var serviceConnectionGeneration = 0L
    var timelineSnapshotRequestGeneration = 0L
    var pendingCustomRangeBuffer: ReverbService.BufferSlot? = null
    var activeSaveReceiver: SaveResultReceiver? = null
}

@Composable
fun CaptureScreen(
    visualizerVisible: Boolean = true,
    onOpenLibrary: () -> Unit = {},
    onRecordingSaved: () -> Unit = {},
    onOpenBufferSettings: (ReverbService.BufferSlot) -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var service by remember { mutableStateOf<ReverbService?>(null) }
    var serviceStateHydrated by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var activeBuffer by remember { mutableStateOf<ReverbService.BufferSlot?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var oneShotDurationSeconds by remember { mutableFloatStateOf(0f) }
    var oneShotPayloadBytes by remember { mutableLongStateOf(0L) }
    var loopingDurationSeconds by remember { mutableFloatStateOf(0f) }
    var loopingPayloadBytes by remember { mutableLongStateOf(0L) }
    // Until the Service publishes its resolved retention state, controls stay locked and the
    // readout is neutral. Do not synchronously inspect recovery files from Compose startup.
    var oneShotEnabled by remember { mutableStateOf(true) }
    var oneShotFull by remember { mutableStateOf(false) }
    var loopingEnabled by remember { mutableStateOf(true) }
    var resolvedRetentionMode by remember { mutableStateOf<RetentionMode?>(null) }
    var selectedBuffer by rememberSaveable {
        mutableStateOf(
            if (oneShotEnabled) ReverbService.BufferSlot.ONE_SHOT else ReverbService.BufferSlot.LOOPING,
        )
    }
    val bookkeeping = remember { CaptureScreenBookkeeping() }
    val uiForegroundOwner = remember { Any() }
    val oneShotBlobController = remember { AudioBlobController() }
    val loopingBlobController = remember { AudioBlobController() }
    // Sampled only when range export opens; visualization updates must not recompose CaptureScreen.
    val latestBlobActivity = remember { FloatArray(2) }

    var pendingClearBuffer by remember { mutableStateOf<ReverbService.BufferSlot?>(null) }
    var activeClearOperationId by remember { mutableStateOf<Long?>(null) }
    var bufferClearStatus by remember { mutableStateOf<BufferClearStatus?>(null) }
    var showExportClampDialog by remember { mutableStateOf(false) }
    var clampWarningSeconds by remember { mutableFloatStateOf(0f) }
    var pendingExportRange by remember { mutableStateOf<ExportRange?>(null) } // Not saveable — non-serializable
    var rangeSnapshot by remember { mutableStateOf<ReverbService.TimelineSnapshot?>(null) }
    var rangeSnapshotBuffer by remember { mutableStateOf<ReverbService.BufferSlot?>(null) }
    var pendingExportSnapshot by remember { mutableStateOf<ReverbService.TimelineSnapshot?>(null) }
    var isPreparingRange by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var saveStatus by remember { mutableStateOf<CaptureSaveStatus?>(null) }
    val screenAlive = remember { AtomicBoolean(true) }

    fun invalidateTimelineSnapshotPreparation() {
        bookkeeping.timelineSnapshotRequestGeneration++
        bookkeeping.pendingCustomRangeBuffer = null
        rangeSnapshotBuffer = null
        isPreparingRange = false
    }

    fun detachActiveSaveUi() {
        bookkeeping.activeSaveReceiver?.detachUi()
        bookkeeping.activeSaveReceiver = null
        if (isSaving) {
            isSaving = false
            saveStatus = null
        }
    }

    fun requestRecorderState(recorder: ReverbService) {
        val requestConnectionGeneration = bookkeeping.serviceConnectionGeneration
        recorder.getState(
            object : ReverbService.StateCallback {
                override fun state(
                    commandGeneration: Long,
                    listeningEnabled: Boolean,
                    activeBufferSlot: ReverbService.BufferSlot?,
                    oneShotSeconds: Float,
                    oneShotBytes: Long,
                    loopingSeconds: Float,
                    loopingBytes: Long,
                    oneShotIsEnabled: Boolean,
                    oneShotIsFull: Boolean,
                    loopingIsEnabled: Boolean,
                    retentionMode: RetentionMode?,
                    exporting: Boolean,
                ) {
                    if (!shouldApplyRecorderStateSnapshot(
                            snapshotConnectionGeneration = requestConnectionGeneration,
                            currentConnectionGeneration = bookkeeping.serviceConnectionGeneration,
                            snapshotGeneration = commandGeneration,
                            latestCommandGeneration = bookkeeping.latestListeningCommandGeneration,
                        )
                    ) return
                    bookkeeping.latestListeningCommandGeneration = commandGeneration
                    val previousListening = isListening
                    val previousActiveBuffer = activeBuffer
                    isListening = listeningEnabled
                    activeBuffer = activeBufferSlot
                    oneShotDurationSeconds = oneShotSeconds
                    oneShotPayloadBytes = oneShotBytes
                    loopingDurationSeconds = loopingSeconds
                    loopingPayloadBytes = loopingBytes
                    val resolvedBuffers = captureResolvedBufferState(
                        retentionMode = retentionMode,
                        oneShotEnabled = oneShotIsEnabled,
                        oneShotFull = oneShotIsFull,
                        loopingEnabled = loopingIsEnabled,
                    )
                    oneShotEnabled = resolvedBuffers.oneShotEnabled
                    oneShotFull = resolvedBuffers.oneShotFull
                    loopingEnabled = resolvedBuffers.loopingEnabled
                    resolvedRetentionMode = retentionMode
                    val receiverAttached = bookkeeping.activeSaveReceiver != null
                    saveStatus = reconcileCaptureExportStatus(
                        exporting = exporting,
                        receiverAttached = receiverAttached,
                        status = saveStatus,
                    )
                    isSaving = captureExportUiBusy(
                        exporting = exporting,
                        receiverAttached = receiverAttached,
                        status = saveStatus,
                    )
                    serviceStateHydrated = true

                    if (!bookkeeping.startupBufferChosen) {
                        selectedBuffer = activeBufferSlot ?: defaultStartupBufferSlot(
                            oneShotEnabled = resolvedBuffers.oneShotEnabled,
                            oneShotFull = resolvedBuffers.oneShotFull,
                            loopingEnabled = resolvedBuffers.loopingEnabled,
                        )
                        bookkeeping.startupBufferChosen = true
                    } else {
                        selectedBuffer = captureSelectedBufferAfterRecorderState(
                            currentSelection = selectedBuffer,
                            wasListening = previousListening,
                            previousActiveBuffer = previousActiveBuffer,
                            listening = listeningEnabled,
                            activeBuffer = activeBufferSlot,
                        )
                    }
                }
            },
        )
    }

    val activeBufferState = androidx.compose.runtime.rememberUpdatedState(activeBuffer)
    val selectedBufferState = androidx.compose.runtime.rememberUpdatedState(selectedBuffer)
    val visualizationCallback = remember {
        ReverbService.VisualizationCallback { frame ->
            when (activeBufferState.value) {
                ReverbService.BufferSlot.ONE_SHOT -> {
                    latestBlobActivity[0] = frame.activity.coerceIn(0f, 1f)
                    oneShotBlobController.submit(frame)
                }
                ReverbService.BufferSlot.LOOPING -> {
                    latestBlobActivity[1] = frame.activity.coerceIn(0f, 1f)
                    loopingBlobController.submit(frame)
                }
                null -> Unit
            }
        }
    }

    LaunchedEffect(selectedBuffer) {
        val pendingBuffer = bookkeeping.pendingCustomRangeBuffer
        if (pendingBuffer != null && pendingBuffer != selectedBuffer) {
            invalidateTimelineSnapshotPreparation()
        }
    }

    LaunchedEffect(activeBuffer) {
        when (activeBuffer) {
            ReverbService.BufferSlot.ONE_SHOT -> {
                latestBlobActivity[1] = 0f
                loopingBlobController.clear()
            }
            ReverbService.BufferSlot.LOOPING -> {
                latestBlobActivity[0] = 0f
                oneShotBlobController.clear()
            }
            null -> {
                latestBlobActivity.fill(0f)
                oneShotBlobController.clear()
                loopingBlobController.clear()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            screenAlive.set(false)
            closeCaptureSnapshotsBestEffort(rangeSnapshot, pendingExportSnapshot)
        }
    }

    DisposableEffect(lifecycleOwner) {
        var boundConnection: ServiceConnection? = null

        fun clearConnectedServiceState(markSavingAsCancelRequested: Boolean) {
            closeCaptureSnapshotsBestEffort(rangeSnapshot, pendingExportSnapshot)
            rangeSnapshot = null
            rangeSnapshotBuffer = null
            pendingExportSnapshot = null
            pendingExportRange = null
            showExportClampDialog = false
            pendingClearBuffer = null
            activeClearOperationId = null
            bufferClearStatus = null
            invalidateTimelineSnapshotPreparation()
            if (markSavingAsCancelRequested && isSaving) {
                // Service teardown does not cancel already-started export work. Keep the
                // saving card until its terminal receiver callback, but disable cancellation
                // because there is no live binder to deliver a new cancel request through.
                saveStatus = markExportCancelRequested(saveStatus)
            }
            serviceStateHydrated = false
            service = null
        }

        fun unbindCurrentConnection() {
            val current = boundConnection ?: return
            // Invalidate callbacks before unbinding. Android can already have a callback queued
            // on the main looper; that callback belongs to this retired bind lifetime.
            boundConnection = null
            bookkeeping.serviceConnectionGeneration++
            runCatching { context.unbindService(current) }
        }

        fun bindIfNeeded() {
            if (boundConnection != null ||
                !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) return

            val bindingGeneration = longArrayOf(++bookkeeping.serviceConnectionGeneration)
            val candidate = object : ServiceConnection {
                private fun isCurrent(): Boolean = captureServiceBindingCallbackIsCurrent(
                    callbackBindingGeneration = bindingGeneration[0],
                    currentBindingGeneration = bookkeeping.serviceConnectionGeneration,
                    screenAlive = screenAlive.get(),
                )

                override fun onServiceConnected(className: ComponentName, binder: IBinder) {
                    if (!isCurrent()) return
                    val typedBinder = binder as? ReverbService.BackgroundRecorderBinder
                        ?: run {
                            clearConnectedServiceState(markSavingAsCancelRequested = false)
                            bindingGeneration[0] = ++bookkeeping.serviceConnectionGeneration
                            return
                        }
                    val connectedService = typedBinder.service
                    if (service != null && service !== connectedService) {
                        clearConnectedServiceState(markSavingAsCancelRequested = false)
                    }
                    bookkeeping.latestListeningCommandGeneration = Long.MIN_VALUE
                    serviceStateHydrated = false
                    service = connectedService
                    connectedService.currentBufferClearStatus()
                        ?.takeIf { it.phase.isActive }
                        ?.let { clearStatus ->
                            activeClearOperationId = clearStatus.operationId
                            bufferClearStatus = clearStatus
                        }
                    requestRecorderState(connectedService)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    if (!isCurrent()) return
                    clearConnectedServiceState(markSavingAsCancelRequested = true)
                    // This binding remains registered and Android may reconnect it. Advance both
                    // the global generation and this connection's token so old state callbacks
                    // die while a legitimate reconnect on the same binding remains acceptable.
                    bindingGeneration[0] = ++bookkeeping.serviceConnectionGeneration
                }
            }
            boundConnection = candidate
            val bound = runCatching {
                context.bindService(
                    Intent(context, ReverbService::class.java),
                    candidate,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
            if (!bound && boundConnection === candidate) {
                boundConnection = null
                if (bookkeeping.serviceConnectionGeneration == bindingGeneration[0]) {
                    bookkeeping.serviceConnectionGeneration++
                }
                clearConnectedServiceState(markSavingAsCancelRequested = false)
            }
        }

        // LifecycleRegistry catches newly added observers up to the current state
        // synchronously. Do not let that catch-up ON_START bypass the deliberate first-frame
        // bind below; real later starts occur after addObserver returns.
        var observerInstalled = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (observerInstalled) bindIfNeeded()

                Lifecycle.Event.ON_STOP -> {
                    detachActiveSaveUi()
                    clearConnectedServiceState(markSavingAsCancelRequested = false)
                    unbindCurrentConnection()
                }

                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        observerInstalled = true
        val initialBind = Runnable { bindIfNeeded() }
        // Keep normal startup frame-first, but keyguard/occlusion can suppress app frames
        // indefinitely. A delayed main-loop fallback restores durable capture intent even when
        // postOnAnimation never fires; bindIfNeeded() makes the duplicate callback harmless.
        view.postOnAnimation(initialBind)
        view.postDelayed(initialBind, CAPTURE_INITIAL_BIND_FALLBACK_MILLIS)
        onDispose {
            view.removeCallbacks(initialBind)
            lifecycleOwner.lifecycle.removeObserver(observer)
            detachActiveSaveUi()
            clearConnectedServiceState(markSavingAsCancelRequested = false)
            unbindCurrentConnection()
        }
    }

    DisposableEffect(service, lifecycleOwner, view, uiForegroundOwner) {
        val recorderService = service
        var resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        var windowFocused = view.hasWindowFocus()

        fun updateUiForeground() {
            recorderService?.setAppUiForeground(uiForegroundOwner, resumed && windowFocused)
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    updateUiForeground()
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    resumed = false
                    updateUiForeground()
                }
                else -> Unit
            }
        }
        val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            windowFocused = hasFocus
            updateUiForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        updateUiForeground()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            }
            recorderService?.setAppUiForeground(uiForegroundOwner, false)
        }
    }

    DisposableEffect(service, lifecycleOwner, visualizationCallback, view, visualizerVisible) {
        val recorderService = service
        var registered = false
        var resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        var windowFocused = view.hasWindowFocus()

        fun updateRegistration() {
            val visible = resumed && windowFocused && visualizerVisible
            if (visible && !registered) {
                recorderService?.setVisualizationCallback(visualizationCallback)
                registered = recorderService != null
            } else if (!visible && registered) {
                recorderService?.clearVisualizationCallback(visualizationCallback)
                registered = false
                oneShotBlobController.clear()
                loopingBlobController.clear()
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    updateRegistration()
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    resumed = false
                    updateRegistration()
                }
                else -> Unit
            }
        }
        val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            windowFocused = hasFocus
            updateRegistration()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        updateRegistration()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            }
            if (registered) {
                recorderService?.clearVisualizationCallback(visualizationCallback)
            }
            oneShotBlobController.clear()
            loopingBlobController.clear()
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val s = service
                if (s != null) {
                    requestRecorderState(s)
                    s.consumePendingError()?.let { errorMessage = it }
                }
                delay(500)
            }
        }
    }

    LaunchedEffect(service, activeClearOperationId) {
        val recorder = service ?: return@LaunchedEffect
        val operationId = activeClearOperationId ?: return@LaunchedEffect
        while (service === recorder && activeClearOperationId == operationId) {
            val status = recorder.currentBufferClearStatus()
            if (status?.operationId != operationId) {
                activeClearOperationId = null
                bufferClearStatus = null
                requestRecorderState(recorder)
                return@LaunchedEffect
            }
            bufferClearStatus = status
            if (status.phase.isTerminal) {
                requestRecorderState(recorder)
                when (status.phase) {
                    BufferClearPhase.CANCELLED -> AppFeedbackCenter.post(
                        resources.getString(R.string.clear_buffer_cancelled),
                        FeedbackTone.INFO,
                    )
                    BufferClearPhase.FAILED -> {
                        val clearError = recorder.consumePendingError()
                        if (clearError != null) {
                            errorMessage = clearError
                        } else if (errorMessage == null) {
                            errorMessage = resources.getString(R.string.clear_buffer_failed)
                        }
                    }
                    else -> Unit
                }
                activeClearOperationId = null
                bufferClearStatus = null
                return@LaunchedEffect
            }
            delay(100L)
        }
    }

    pendingClearBuffer?.let { bufferSlot ->
        ClearBufferSheet(
            bufferSlot = bufferSlot,
            onConfirm = {
                val recorder = service
                val operationId = recorder?.startClearBuffer(bufferSlot)
                pendingClearBuffer = null
                if (recorder == null || operationId == null) {
                    errorMessage = resources.getString(R.string.clear_buffer_failed)
                } else {
                    activeClearOperationId = operationId
                    bufferClearStatus = recorder.currentBufferClearStatus()
                        ?: BufferClearStatus(
                            operationId = operationId,
                            bufferSlot = bufferSlot,
                            phase = BufferClearPhase.STARTING,
                        )
                }
            },
            onDismiss = { pendingClearBuffer = null },
        )
    }

    val clearStatus = bufferClearStatus
    val clearOperationId = activeClearOperationId
    if (clearStatus != null && clearOperationId == clearStatus.operationId) {
        ClearBufferProgressSheet(
            status = clearStatus,
            onCancel = {
                val recorder = service
                if (recorder != null && recorder.cancelBufferClear(clearOperationId)) {
                    bufferClearStatus = clearStatus.copy(phase = BufferClearPhase.CANCELLING)
                }
            },
        )
    }

    if (showExportClampDialog) {
        if (pendingExportRange == null) {
            showExportClampDialog = false
        } else {
            ExportClampSheet(
                clampedDurationSeconds = clampWarningSeconds,
                onProceed = {
                    showExportClampDialog = false
                    val range = pendingExportRange ?: return@ExportClampSheet
                    val snapshot = pendingExportSnapshot
                    pendingExportRange = null
                    pendingExportSnapshot = null
                    startExport(
                        context, service, range,
                        snapshot = snapshot,
                        setSaving = { isSaving = it },
                        onStatus = { saveStatus = it },
                        onError = { errorMessage = it },
                        onSaved = onRecordingSaved,
                        onReceiverCreated = { receiver -> bookkeeping.activeSaveReceiver = receiver },
                        onReceiverTerminal = { receiver ->
                            if (bookkeeping.activeSaveReceiver === receiver) bookkeeping.activeSaveReceiver = null
                        },
                    )
                },
                onDismiss = {
                    showExportClampDialog = false
                    pendingExportRange = null
                    closeCaptureSnapshotsBestEffort(pendingExportSnapshot)
                    pendingExportSnapshot = null
                },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val onListenToggle = remember(
            service, serviceStateHydrated, isSaving, isListening, activeBuffer,
            oneShotEnabled, oneShotFull, loopingEnabled,
        ) {
            { bufferSlot: ReverbService.BufferSlot ->
                val recorder = service
                if (recorder != null && serviceStateHydrated && !isSaving) {
                    val action = captureBlobTapAction(
                        requested = bufferSlot,
                        isListening = isListening,
                        activeBuffer = activeBuffer,
                        oneShotEnabled = oneShotEnabled,
                        oneShotFull = oneShotFull,
                        loopingEnabled = loopingEnabled,
                    )
                    val result = when (action) {
                        CaptureBlobTapAction.STOP -> recorder.disableListening()
                        CaptureBlobTapAction.SWITCH -> recorder.selectCaptureBuffer(bufferSlot)
                        CaptureBlobTapAction.START -> recorder.enableListening(bufferSlot)
                        CaptureBlobTapAction.NONE -> null
                    }
                    if (result?.accepted == true) {
                        bookkeeping.latestListeningCommandGeneration = maxOf(
                            bookkeeping.latestListeningCommandGeneration,
                            result.generation,
                        )
                        isListening = action != CaptureBlobTapAction.STOP
                        activeBuffer = bufferSlot
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
            }
        }
        val onClearBuffer = remember(
            isSaving, service, serviceStateHydrated, activeClearOperationId,
        ) {
            { bufferSlot: ReverbService.BufferSlot ->
                if (
                    captureServiceInteractionReady(service != null, serviceStateHydrated) &&
                    !isSaving &&
                    activeClearOperationId == null
                ) {
                    pendingClearBuffer = bufferSlot
                }
            }
        }
        val onExportFull = remember(service, serviceStateHydrated, isSaving, isPreparingRange) {
            { bufferSlot: ReverbService.BufferSlot ->
                val s = service
                if (s != null && serviceStateHydrated && !isSaving && !isPreparingRange) {
                    val requestGeneration = bookkeeping.timelineSnapshotRequestGeneration + 1L
                    bookkeeping.timelineSnapshotRequestGeneration = requestGeneration
                    isPreparingRange = true
                    s.acquireTimelineSnapshot(bufferSlot) { snapshot ->
                        if (!timelineSnapshotRequestIsCurrent(
                                requestGeneration,
                                bookkeeping.timelineSnapshotRequestGeneration,
                            )
                        ) {
                            closeCaptureSnapshotsBestEffort(snapshot)
                            return@acquireTimelineSnapshot
                        }
                        isPreparingRange = false
                        if (!screenAlive.get() || service !== s) {
                            closeCaptureSnapshotsBestEffort(snapshot)
                            return@acquireTimelineSnapshot
                        }
                        if (snapshot == null || snapshot.durationSeconds <= 0.0) {
                            closeCaptureSnapshotsBestEffort(snapshot)
                            AppFeedbackCenter.post(
                                resources.getString(R.string.nothing_to_export),
                                FeedbackTone.INFO,
                            )
                            return@acquireTimelineSnapshot
                        }
                        handleExport(context, s, snapshot.durationSeconds.toFloat()) { builtRange ->
                            val range = builtRange.copy(
                                rememberOnSave = ExportRangeMemory(bufferSlot, snapshot.durationSeconds),
                            )
                            if (range.warningDurationSeconds != null) {
                                clampWarningSeconds = range.warningDurationSeconds
                                pendingExportRange = range
                                closeCaptureSnapshotsBestEffort(pendingExportSnapshot)
                                pendingExportSnapshot = snapshot
                                showExportClampDialog = true
                            } else {
                                startExport(
                                    context, s, range,
                                    snapshot = snapshot,
                                    setSaving = { isSaving = it },
                                    onStatus = { saveStatus = it },
                                    onError = { errorMessage = it },
                                    onSaved = onRecordingSaved,
                                    onReceiverCreated = { receiver -> bookkeeping.activeSaveReceiver = receiver },
                                    onReceiverTerminal = { receiver ->
                                        if (bookkeeping.activeSaveReceiver === receiver) bookkeeping.activeSaveReceiver = null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        val onExportCustom = remember(
            service,
            serviceStateHydrated,
            isSaving,
            isPreparingRange,
            oneShotDurationSeconds,
            loopingDurationSeconds,
        ) {
            { bufferSlot: ReverbService.BufferSlot ->
                if (serviceStateHydrated && !isSaving && !isPreparingRange) {
                    val s = service
                    if (s != null) {
                        val secs = when (bufferSlot) {
                            ReverbService.BufferSlot.ONE_SHOT -> oneShotDurationSeconds
                            ReverbService.BufferSlot.LOOPING -> loopingDurationSeconds
                        }.coerceAtLeast(0f)
                        if (secs > 0f) {
                            val requestGeneration = bookkeeping.timelineSnapshotRequestGeneration + 1L
                            bookkeeping.timelineSnapshotRequestGeneration = requestGeneration
                            bookkeeping.pendingCustomRangeBuffer = bufferSlot
                            rangeSnapshotBuffer = bufferSlot
                            isPreparingRange = true
                            s.acquireTimelineSnapshot(bufferSlot) { snapshot ->
                                val currentRequest = shouldApplyCustomRangeSnapshot(
                                    requestGeneration = requestGeneration,
                                    latestRequestGeneration = bookkeeping.timelineSnapshotRequestGeneration,
                                    requestedBuffer = bufferSlot,
                                    pendingBuffer = bookkeeping.pendingCustomRangeBuffer,
                                    selectedBuffer = selectedBufferState.value,
                                )
                                if (!currentRequest) {
                                    closeCaptureSnapshotsBestEffort(snapshot)
                                    if (requestGeneration == bookkeeping.timelineSnapshotRequestGeneration) {
                                        bookkeeping.pendingCustomRangeBuffer = null
                                        isPreparingRange = false
                                    }
                                    return@acquireTimelineSnapshot
                                }
                                bookkeeping.pendingCustomRangeBuffer = null
                                isPreparingRange = false
                                if (!screenAlive.get() || service !== s) {
                                    closeCaptureSnapshotsBestEffort(snapshot)
                                } else if (snapshot != null && snapshot.durationSeconds > 0.0) {
                                    closeCaptureSnapshotsBestEffort(rangeSnapshot)
                                    rangeSnapshot = snapshot
                                    rangeSnapshotBuffer = bufferSlot
                                } else {
                                    closeCaptureSnapshotsBestEffort(snapshot)
                                    rangeSnapshotBuffer = null
                                    AppFeedbackCenter.post(
                                        resources.getString(R.string.nothing_to_export),
                                        FeedbackTone.INFO,
                                    )
                                }
                            }
                        } else {
                            AppFeedbackCenter.post(
                                resources.getString(R.string.nothing_to_export),
                                FeedbackTone.INFO,
                            )
                        }
                    }
                }
            }
        }
        val rangeConfig = currentExportConfig(context, service)
        val rangeMaxDurationSeconds = exportDurationLimitExactSeconds(
            rangeConfig.format,
            rangeConfig.codec,
            rangeConfig.sampleRate,
            rangeConfig.channelCount,
            rangeConfig.sampleFormat,
        )
        val dismissRangeExport: () -> Unit = {
            closeCaptureSnapshotsBestEffort(rangeSnapshot)
            rangeSnapshot = null
            rangeSnapshotBuffer = null
            invalidateTimelineSnapshotPreparation()
        }
        val rangeBackMotion = rememberPredictiveBackMotion(
            enabled = rangeSnapshotBuffer != null && visualizerVisible,
            onBack = dismissRangeExport,
        )
        val submitRangeExport: (Float, Float) -> Unit = submitRange@ { startSeconds, endSeconds ->
            val snapshot = rangeSnapshot ?: return@submitRange
            val bufferSlot = rangeSnapshotBuffer ?: return@submitRange
            val range = buildCustomExportRange(
                availableSeconds = snapshot.durationSeconds,
                requestedStartSeconds = startSeconds,
                requestedEndSeconds = endSeconds,
                exportConfig = rangeConfig,
            ).copy(
                rememberOnSave = ExportRangeMemory(bufferSlot, snapshot.durationSeconds),
            )
            rangeSnapshot = null
            rangeSnapshotBuffer = null
            invalidateTimelineSnapshotPreparation()
            if (range.warningDurationSeconds != null) {
                clampWarningSeconds = range.warningDurationSeconds
                pendingExportRange = range
                closeCaptureSnapshotsBestEffort(pendingExportSnapshot)
                pendingExportSnapshot = snapshot
                showExportClampDialog = true
            } else {
                startExport(
                    context, service, range,
                    snapshot = snapshot,
                    setSaving = { isSaving = it },
                    onStatus = { saveStatus = it },
                    onError = { errorMessage = it },
                    onSaved = onRecordingSaved,
                    onReceiverCreated = { receiver -> bookkeeping.activeSaveReceiver = receiver },
                    onReceiverTerminal = { receiver ->
                        if (bookkeeping.activeSaveReceiver === receiver) bookkeeping.activeSaveReceiver = null
                    },
                )
            }
        }

        MainCaptureContent(
            selectedBuffer = selectedBuffer,
            activeBuffer = activeBuffer,
            oneShotMetrics = BufferMetrics(oneShotDurationSeconds, oneShotPayloadBytes),
            loopingMetrics = BufferMetrics(loopingDurationSeconds, loopingPayloadBytes),
            oneShotEnabled = oneShotEnabled,
            oneShotFull = oneShotFull,
            loopingEnabled = loopingEnabled,
            retentionMode = resolvedRetentionMode,
            isListening = isListening,
            isSaving = isSaving,
            service = service,
            serviceStateHydrated = serviceStateHydrated,
            oneShotBlobController = oneShotBlobController,
            loopingBlobController = loopingBlobController,
            oneShotBlobActivity = latestBlobActivity[0],
            loopingBlobActivity = latestBlobActivity[1],
            rangeSnapshot = rangeSnapshot,
            rangeSnapshotBuffer = rangeSnapshotBuffer,
            rangeMaxExportDurationSeconds = rangeMaxDurationSeconds,
            rangeBackProgress = if (rangeBackMotion.gestureActive) rangeBackMotion.progress.value else 0f,
            onCancelRangeExport = dismissRangeExport,
            onSubmitRangeExport = submitRangeExport,
            onListenToggle = onListenToggle,
            onClearBuffer = onClearBuffer,
            onExportFull = onExportFull,
            onExportCustom = onExportCustom,
            onSelectBuffer = { selectedBuffer = it },
            onOpenBufferSettings = onOpenBufferSettings,
            visualizerVisible = visualizerVisible,
            onOpenLibrary = onOpenLibrary,
        )

        CaptureSaveStatusCard(
            status = saveStatus,
            onCancel = {
                val recorder = service
                if (recorder != null && recorder.cancelCurrentExport()) {
                    // Cancellation can lose the race with verified publication. Only switch the
                    // card to non-cancellable when the service actually accepted the request.
                    saveStatus = markExportCancelRequested(saveStatus)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 18.dp)
                .padding(
                    bottom = CAPTURE_CONTROL_BOTTOM_SPACER +
                        CAPTURE_CONTROL_CLUSTER_HEIGHT +
                        CAPTURE_SAVE_STATUS_GAP,
                ),
        )
    }

    LaunchedEffect(saveStatus) {
        val current = saveStatus
        if (current is CaptureSaveStatus.Saved) {
            delay(1_500L)
            if (saveStatus == current) saveStatus = null
        }
    }

    errorMessage?.let { msg ->
        ErrorSheet(
            message = msg,
            onDismiss = { errorMessage = null },
        )
    }
}

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

private const val CAPTURE_INITIAL_BIND_FALLBACK_MILLIS = 250L
private const val BUFFER_SWIPE_COMMIT_PROGRESS = 0.16f
private const val BUFFER_FLIP_DURATION_MILLIS = 260
private const val BUFFER_FLIP_MIDPOINT_SCALE = 0.94f
private val CAPTURE_CONTROL_CLUSTER_HEIGHT = 112.dp
private val CAPTURE_CONTROL_BOTTOM_SPACER = 18.dp
private val CAPTURE_SAVE_STATUS_GAP = 12.dp

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

@Composable
private fun MainCaptureContent(
    selectedBuffer: ReverbService.BufferSlot,
    activeBuffer: ReverbService.BufferSlot?,
    oneShotMetrics: BufferMetrics,
    loopingMetrics: BufferMetrics,
    oneShotEnabled: Boolean,
    oneShotFull: Boolean,
    loopingEnabled: Boolean,
    retentionMode: RetentionMode?,
    isListening: Boolean,
    isSaving: Boolean,
    service: ReverbService?,
    serviceStateHydrated: Boolean,
    oneShotBlobController: AudioBlobController,
    loopingBlobController: AudioBlobController,
    oneShotBlobActivity: Float,
    loopingBlobActivity: Float,
    rangeSnapshot: ReverbService.TimelineSnapshot?,
    rangeSnapshotBuffer: ReverbService.BufferSlot?,
    rangeMaxExportDurationSeconds: Double,
    rangeBackProgress: Float,
    onCancelRangeExport: () -> Unit,
    onSubmitRangeExport: (Float, Float) -> Unit,
    onListenToggle: (ReverbService.BufferSlot) -> Unit,
    onClearBuffer: (ReverbService.BufferSlot) -> Unit,
    onExportFull: (ReverbService.BufferSlot) -> Unit,
    onExportCustom: (ReverbService.BufferSlot) -> Unit,
    onSelectBuffer: (ReverbService.BufferSlot) -> Unit,
    onOpenBufferSettings: (ReverbService.BufferSlot) -> Unit,
    visualizerVisible: Boolean,
    onOpenLibrary: () -> Unit,
) {
    // Measured from the actual composed home layout. These plain holders intentionally do not
    // trigger recomposition; opening range export samples the last committed layout geometry.
    val homeRootBoundsInRoot = remember { arrayOfNulls<Rect>(1) }
    val homeBlobBoundsInRoot = remember { arrayOfNulls<Rect>(1) }

    val rangeBuffer = rangeSnapshotBuffer
    if (rangeBuffer != null) {
        val activeRangeSnapshot = rangeSnapshot
        val rangeMetrics = when (rangeBuffer) {
            ReverbService.BufferSlot.ONE_SHOT -> oneShotMetrics
            ReverbService.BufferSlot.LOOPING -> loopingMetrics
        }
        val rangeEnabled = when (rangeBuffer) {
            ReverbService.BufferSlot.ONE_SHOT -> oneShotEnabled
            ReverbService.BufferSlot.LOOPING -> loopingEnabled
        }
        val rangeBlobController = when (rangeBuffer) {
            ReverbService.BufferSlot.ONE_SHOT -> oneShotBlobController
            ReverbService.BufferSlot.LOOPING -> loopingBlobController
        }
        val rangeBlobActivity = when (rangeBuffer) {
            ReverbService.BufferSlot.ONE_SHOT -> oneShotBlobActivity
            ReverbService.BufferSlot.LOOPING -> loopingBlobActivity
        }
        // Freeze the source model at the renderer handoff. The home blob is no longer present
        // after this branch is entered, so later visualizer/service updates must not move the
        // starting endpoint underneath an in-flight morph.
        val sourceGeometry = remember(rangeBuffer, activeRangeSnapshot) {
            rangeMorphSourceGeometry(
                rootBoundsInRoot = homeRootBoundsInRoot[0],
                blobBoundsInRoot = homeBlobBoundsInRoot[0],
                active = isListening && activeBuffer == rangeBuffer,
                enabled = rangeEnabled,
                activity = rangeBlobActivity,
                renderedBaseRadiusFraction = rangeBlobController.currentBaseRadiusFraction(),
            )
        }
        RangeExportHomeContent(
            snapshot = activeRangeSnapshot,
            initialDurationSeconds = rangeMetrics.seconds.coerceAtLeast(0.05f),
            selectedBuffer = rangeBuffer,
            activeBuffer = activeBuffer,
            blobMetrics = rangeMetrics,
            blobEnabled = rangeEnabled,
            blobController = rangeBlobController,
            sourceGeometry = sourceGeometry,
            isListening = isListening,
            isSaving = isSaving,
            service = service,
            oneShotEnabled = oneShotEnabled,
            oneShotFull = oneShotFull,
            loopingEnabled = loopingEnabled,
            maxExportDurationSeconds = rangeMaxExportDurationSeconds,
            backProgress = rangeBackProgress,
            visualizerVisible = visualizerVisible,
            onCancel = onCancelRangeExport,
            onExport = onSubmitRangeExport,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
        )
        return
    }

    var displayedBuffer by remember { mutableStateOf(selectedBuffer) }
    var transitionTarget by remember { mutableStateOf<ReverbService.BufferSlot?>(null) }
    var pendingNavigationCommit by remember { mutableStateOf<ReverbService.BufferSlot?>(null) }
    var bufferDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var transitionProgressTarget by remember { mutableFloatStateOf(0f) }

    val transitionProgress by animateFloatAsState(
        targetValue = if (bufferDragging) dragProgress else transitionProgressTarget,
        animationSpec = if (bufferDragging) snap() else tween(durationMillis = BUFFER_FLIP_DURATION_MILLIS),
        label = "buffer-transition-progress",
        finishedListener = { settledProgress ->
            val target = transitionTarget ?: return@animateFloatAsState
            if (bufferDragging) return@animateFloatAsState
            when {
                settledProgress >= 0.999f -> {
                    val shouldCommit = pendingNavigationCommit == target
                    displayedBuffer = target
                    pendingNavigationCommit = null
                    transitionTarget = null
                    transitionProgressTarget = 0f
                    dragProgress = 0f
                    if (shouldCommit) onSelectBuffer(target)
                }
                settledProgress <= 0.001f && transitionProgressTarget <= 0f -> {
                    pendingNavigationCommit = null
                    transitionTarget = null
                    dragProgress = 0f
                }
            }
        },
    )

    LaunchedEffect(selectedBuffer) {
        if (selectedBuffer == displayedBuffer) return@LaunchedEffect
        bufferDragging = false
        dragProgress = 0f
        pendingNavigationCommit = null
        transitionTarget = selectedBuffer
        transitionProgressTarget = 1f
    }

    val targetBuffer = transitionTarget
    val renderedBuffer = if (targetBuffer != null) {
        bufferTransitionDisplayedSlot(displayedBuffer, targetBuffer, transitionProgress)
    } else {
        displayedBuffer
    }
    val flipDegrees = if (targetBuffer != null) {
        bufferTransitionFlipDegrees(displayedBuffer, targetBuffer, transitionProgress)
    } else {
        0f
    }
    val flipPivotX = bufferTransitionPivotFractionX()
    val flipDepthScale = if (targetBuffer != null) bufferTransitionDepthScale(transitionProgress) else 1f

    val displayedMetrics = when (renderedBuffer) {
        ReverbService.BufferSlot.ONE_SHOT -> oneShotMetrics
        ReverbService.BufferSlot.LOOPING -> loopingMetrics
    }
    val displayedEnabled = when (renderedBuffer) {
        ReverbService.BufferSlot.ONE_SHOT -> oneShotEnabled
        ReverbService.BufferSlot.LOOPING -> loopingEnabled
    }
    val displayedUiState = captureBufferUiState(
        bufferSlot = renderedBuffer,
        enabled = displayedEnabled,
        oneShotFull = oneShotFull,
        isListening = isListening,
        activeBuffer = activeBuffer,
    )
    val displayedRecording = displayedUiState == CaptureBufferUiState.RECORDING
    val serviceReady = captureServiceInteractionReady(service != null, serviceStateHydrated)
    val hasHistory = displayedMetrics.seconds > 0f

    val requestBufferNavigation: (ReverbService.BufferSlot) -> Unit = { target ->
        if (
            !isSaving &&
            !bufferDragging &&
            transitionTarget == null &&
            target != displayedBuffer
        ) {
            transitionTarget = target
            pendingNavigationCommit = target
            transitionProgressTarget = 1f
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .onGloballyPositioned { coordinates ->
                homeRootBoundsInRoot[0] = coordinates.boundsInRoot()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(displayedBuffer, isSaving) {
                    var horizontalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            horizontalDrag = 0f
                            if (!isSaving && transitionTarget == null) {
                                bufferDragging = true
                                dragProgress = 0f
                                transitionProgressTarget = 0f
                                pendingNavigationCommit = null
                                transitionTarget = oppositeBufferSlot(displayedBuffer)
                            }
                        },
                        onHorizontalDrag = { change, amount ->
                            if (bufferDragging) {
                                horizontalDrag += amount
                                dragProgress = bufferSwipeProgress(
                                    source = displayedBuffer,
                                    horizontalDragPx = horizontalDrag,
                                    viewportWidthPx = size.width.toFloat(),
                                )
                                change.consume()
                            }
                        },
                        onDragEnd = {
                            if (bufferDragging) {
                                val target = transitionTarget
                                val shouldCommit = target != null && shouldCommitBufferSwipe(dragProgress)
                                bufferDragging = false
                                if (shouldCommit) {
                                    pendingNavigationCommit = target
                                    transitionProgressTarget = 1f
                                } else if (dragProgress <= 0.001f) {
                                    pendingNavigationCommit = null
                                    transitionTarget = null
                                    transitionProgressTarget = 0f
                                } else {
                                    pendingNavigationCommit = null
                                    transitionProgressTarget = 0f
                                }
                            }
                        },
                        onDragCancel = {
                            if (bufferDragging) {
                                bufferDragging = false
                                pendingNavigationCommit = null
                                if (dragProgress <= 0.001f) transitionTarget = null
                                transitionProgressTarget = 0f
                            }
                        },
                    )
                },
        ) {
            BufferBlobPage(
                bufferSlot = renderedBuffer,
                activeBuffer = activeBuffer,
                metrics = displayedMetrics,
                retentionMode = retentionMode,
                bufferEnabled = displayedEnabled,
                oneShotFull = oneShotFull,
                isListening = isListening,
                isSaving = isSaving,
                service = service,
                blobController = when (renderedBuffer) {
                    ReverbService.BufferSlot.ONE_SHOT -> oneShotBlobController
                    ReverbService.BufferSlot.LOOPING -> loopingBlobController
                },
                flipDegrees = flipDegrees,
                flipPivotX = flipPivotX,
                flipDepthScale = flipDepthScale,
                onListenToggle = { onListenToggle(renderedBuffer) },
                onOpenBufferSettings = { onOpenBufferSettings(renderedBuffer) },
                visualizerVisible = visualizerVisible,
                interactionEnabled = serviceReady,
                onBlobBoundsInRoot = { bounds -> homeBlobBoundsInRoot[0] = bounds },
            )
        }

        CaptureControlCluster(
            selectedBuffer = renderedBuffer,
            activeBuffer = activeBuffer,
            isListening = isListening,
            oneShotEnabled = oneShotEnabled,
            oneShotFull = oneShotFull,
            loopingEnabled = loopingEnabled,
            serviceReady = serviceReady,
            isSaving = isSaving,
            hasHistory = hasHistory,
            selectedRecording = displayedRecording,
            flipDegrees = flipDegrees,
            flipPivotX = flipPivotX,
            flipDepthScale = flipDepthScale,
            onSelectBuffer = requestBufferNavigation,
            onExportFull = { onExportFull(renderedBuffer) },
            onExportCustom = { onExportCustom(renderedBuffer) },
            onClearBuffer = { onClearBuffer(renderedBuffer) },
            onOpenLibrary = onOpenLibrary,
        )
        Spacer(Modifier.height(CAPTURE_CONTROL_BOTTOM_SPACER))
    }
}

@Composable
private fun CaptureControlCluster(
    selectedBuffer: ReverbService.BufferSlot,
    activeBuffer: ReverbService.BufferSlot?,
    isListening: Boolean,
    oneShotEnabled: Boolean,
    oneShotFull: Boolean,
    loopingEnabled: Boolean,
    serviceReady: Boolean,
    isSaving: Boolean,
    hasHistory: Boolean,
    selectedRecording: Boolean,
    flipDegrees: Float,
    flipPivotX: Float,
    flipDepthScale: Float,
    onSelectBuffer: (ReverbService.BufferSlot) -> Unit,
    onExportFull: () -> Unit,
    onExportCustom: () -> Unit,
    onClearBuffer: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val chrome = appChrome()
    val selectorWidth = 242.dp
    val selectorHeight = 54.dp
    val actionWidth = 256.dp
    val actionHeight = 70.dp
    val clusterWidth = maxOf(selectorWidth, actionWidth)
    val clusterHeight = CAPTURE_CONTROL_CLUSTER_HEIGHT
    val selectorFraction = selectorWidth.value / clusterWidth.value
    val actionFraction = actionWidth.value / clusterWidth.value
    val unionShape = remember(selectorFraction, actionFraction) {
        captureControlUnionShape(selectorFraction, actionFraction)
    }

    Surface(
        modifier = Modifier
            .size(clusterWidth, clusterHeight)
            .animateContentSize(),
        shape = unionShape,
        color = chrome.field,
        border = BorderStroke(1.dp, chrome.border),
    ) {
        Box(Modifier.fillMaxSize()) {
            BufferSelector(
                selectedBuffer = selectedBuffer,
                activeBuffer = activeBuffer,
                isListening = isListening,
                oneShotEnabled = oneShotEnabled,
                oneShotFull = oneShotFull,
                loopingEnabled = loopingEnabled,
                onSelectBuffer = onSelectBuffer,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(selectorWidth, selectorHeight),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .width(actionWidth)
                    .height(actionHeight)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CaptureActionButton(
                    icon = AppIcons.save,
                    contentDescription = stringResource(R.string.record_all_memory),
                    enabled = serviceReady && !isSaving && hasHistory,
                    flipDegrees = flipDegrees,
                    flipPivotX = flipPivotX,
                    flipDepthScale = flipDepthScale,
                    onClick = onExportFull,
                )
                CaptureActionButton(
                    icon = AppIcons.exportRange,
                    contentDescription = stringResource(R.string.export_range_title),
                    enabled = serviceReady && !isSaving && hasHistory,
                    flipDegrees = flipDegrees,
                    flipPivotX = flipPivotX,
                    flipDepthScale = flipDepthScale,
                    onClick = onExportCustom,
                )
                CaptureActionButton(
                    icon = AppIcons.delete,
                    contentDescription = stringResource(R.string.clear_buffer),
                    enabled = serviceReady && !isSaving && hasHistory && !selectedRecording,
                    destructive = true,
                    flipDegrees = flipDegrees,
                    flipPivotX = flipPivotX,
                    flipDepthScale = flipDepthScale,
                    onClick = onClearBuffer,
                )
                CaptureActionButton(
                    icon = AppIcons.library,
                    contentDescription = stringResource(R.string.files_tab),
                    enabled = !isSaving,
                    onClick = onOpenLibrary,
                )
            }
        }
    }
}

private fun captureControlUnionShape(
    selectorWidthFraction: Float,
    actionWidthFraction: Float,
) = androidx.compose.foundation.shape.GenericShape { size, _ ->
    // The lobes genuinely overlap. We trace only their exterior silhouette, so there is no
    // fake gap/neck and no internal corner to reveal where one rounded rectangle ends.
    val centerX = size.width * 0.5f
    val topHeight = size.height * (54f / 112f)
    val bottomTop = size.height * (42f / 112f)
    val topWidth = size.width * selectorWidthFraction
    val bottomWidth = size.width * actionWidthFraction
    val topLeft = centerX - topWidth * 0.5f
    val topRight = centerX + topWidth * 0.5f
    val bottomLeft = centerX - bottomWidth * 0.5f
    val bottomRight = centerX + bottomWidth * 0.5f
    val topRadius = topHeight * 0.40f
    val bottomRadius = (size.height - bottomTop) * 0.40f
    val squircleControl = 0.44f

    // Morph from the top lobe's side to the bottom lobe's side while both lobes overlap.
    // Vertical endpoint tangents make this visually continuous with both squircle walls.
    val transitionStart = topHeight * 0.52f
    val transitionEnd = bottomTop + bottomRadius * 0.62f
    val transitionSpan = (transitionEnd - transitionStart).coerceAtLeast(1f)
    val handle = transitionSpan * 0.46f

    moveTo(topLeft + topRadius, 0f)
    lineTo(topRight - topRadius, 0f)
    cubicTo(
        topRight - topRadius * squircleControl, 0f,
        topRight, topRadius * squircleControl,
        topRight, topRadius,
    )
    lineTo(topRight, transitionStart)
    cubicTo(
        topRight, transitionStart + handle,
        bottomRight, transitionEnd - handle,
        bottomRight, transitionEnd,
    )
    lineTo(bottomRight, size.height - bottomRadius)
    cubicTo(
        bottomRight, size.height - bottomRadius * squircleControl,
        bottomRight - bottomRadius * squircleControl, size.height,
        bottomRight - bottomRadius, size.height,
    )
    lineTo(bottomLeft + bottomRadius, size.height)
    cubicTo(
        bottomLeft + bottomRadius * squircleControl, size.height,
        bottomLeft, size.height - bottomRadius * squircleControl,
        bottomLeft, size.height - bottomRadius,
    )
    lineTo(bottomLeft, transitionEnd)
    cubicTo(
        bottomLeft, transitionEnd - handle,
        topLeft, transitionStart + handle,
        topLeft, transitionStart,
    )
    lineTo(topLeft, topRadius)
    cubicTo(
        topLeft, topRadius * squircleControl,
        topLeft + topRadius * squircleControl, 0f,
        topLeft + topRadius, 0f,
    )
    close()
}

@Composable
internal fun BufferSelector(
    selectedBuffer: ReverbService.BufferSlot,
    activeBuffer: ReverbService.BufferSlot?,
    isListening: Boolean,
    oneShotEnabled: Boolean,
    oneShotFull: Boolean,
    loopingEnabled: Boolean,
    onSelectBuffer: (ReverbService.BufferSlot) -> Unit,
    modifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .selectableGroup()
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BufferSegment(
            label = stringResource(R.string.buffer_one_shot),
            icon = if (oneShotFull) AppIcons.check else AppIcons.oneShot,
            selected = selectedBuffer == ReverbService.BufferSlot.ONE_SHOT,
            recording = isBufferActivelyRecording(
                ReverbService.BufferSlot.ONE_SHOT,
                isListening,
                activeBuffer,
            ),
            enabled = oneShotEnabled && !oneShotFull,
            filled = oneShotFull,
            interactionEnabled = interactionEnabled,
            onClick = { onSelectBuffer(ReverbService.BufferSlot.ONE_SHOT) },
        )
        BufferSegment(
            label = stringResource(R.string.buffer_loop),
            icon = AppIcons.looping,
            selected = selectedBuffer == ReverbService.BufferSlot.LOOPING,
            recording = isBufferActivelyRecording(
                ReverbService.BufferSlot.LOOPING,
                isListening,
                activeBuffer,
            ),
            enabled = loopingEnabled,
            filled = false,
            interactionEnabled = interactionEnabled,
            onClick = { onSelectBuffer(ReverbService.BufferSlot.LOOPING) },
        )
    }
}

@Composable
private fun BufferSegment(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    recording: Boolean,
    enabled: Boolean,
    filled: Boolean,
    interactionEnabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val chrome = appChrome()
    val containerColor = when {
        selected && recording -> colors.primary
        filled && selected -> colors.tertiary
        filled -> colors.tertiaryContainer.copy(alpha = 0.58f)
        selected -> colors.primaryContainer.copy(alpha = 0.74f)
        else -> Color.Transparent
    }
    val contentColor = when {
        selected && recording -> colors.onPrimary
        filled && selected -> colors.onTertiary
        filled -> colors.onTertiaryContainer
        selected && !enabled -> colors.onPrimaryContainer.copy(alpha = 0.62f)
        selected -> colors.onPrimaryContainer
        else -> chrome.muted.copy(alpha = if (enabled) 0.52f else 0.30f)
    }

    Surface(
        modifier = Modifier.selectable(
            selected = selected,
            enabled = interactionEnabled,
            role = Role.Tab,
            onClick = onClick,
        ),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .width(112.dp)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun BufferBlobPage(
    bufferSlot: ReverbService.BufferSlot,
    activeBuffer: ReverbService.BufferSlot?,
    metrics: BufferMetrics,
    retentionMode: RetentionMode?,
    bufferEnabled: Boolean,
    oneShotFull: Boolean,
    isListening: Boolean,
    isSaving: Boolean,
    service: ReverbService?,
    blobController: AudioBlobController,
    flipDegrees: Float,
    modifier: Modifier = Modifier,
    flipPivotX: Float = 0.5f,
    flipDepthScale: Float = 1f,
    onListenToggle: () -> Unit,
    onOpenBufferSettings: () -> Unit,
    visualizerVisible: Boolean,
    interactionEnabled: Boolean = true,
    contentAlpha: Float = 1f,
    onBlobBoundsInRoot: ((Rect) -> Unit)? = null,
) {
    val resources = LocalResources.current
    val uiState = captureBufferUiState(
        bufferSlot = bufferSlot,
        enabled = bufferEnabled,
        oneShotFull = oneShotFull,
        isListening = isListening,
        activeBuffer = activeBuffer,
    )
    val blockedByOther = isCaptureBlockedByOtherBuffer(bufferSlot, isListening, activeBuffer)
    val recordingThisBuffer = uiState == CaptureBufferUiState.RECORDING
    val filled = uiState == CaptureBufferUiState.FILLED
    val disabled = uiState == CaptureBufferUiState.DISABLED
    val serviceReady = service != null
    val captureEnabled = interactionEnabled && serviceReady &&
        (uiState == CaptureBufferUiState.READY || recordingThisBuffer)
    val clickEnabled = interactionEnabled && !isSaving && (disabled || captureEnabled)

    val readout = remember(retentionMode, metrics.seconds, metrics.bytes, disabled, resources) {
        captureBufferReadout(
            retentionMode = retentionMode,
            disabled = disabled,
            seconds = metrics.seconds,
            bytes = metrics.bytes,
            disabledLabel = resources.getString(R.string.buffer_disabled),
        )
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val blobSize = minOf(maxWidth * 0.90f, maxHeight * 0.94f, 372.dp)
        AudioBlobControl(
            isListening = recordingThisBuffer,
            isSaving = isSaving,
            enabled = captureEnabled,
            clickableEnabled = clickEnabled,
            filled = filled,
            dimmed = blockedByOther || disabled,
            blobController = blobController,
            primaryText = readout.primary,
            secondaryText = readout.secondary,
            visualizerVisible = visualizerVisible,
            flipDegrees = flipDegrees,
            flipPivotX = flipPivotX,
            flipDepthScale = flipDepthScale,
            contentAlpha = contentAlpha,
            modifier = Modifier
                .size(blobSize)
                .then(
                    if (onBlobBoundsInRoot != null) {
                        Modifier.onGloballyPositioned { coordinates ->
                            onBlobBoundsInRoot(coordinates.boundsInRoot())
                        }
                    } else {
                        Modifier
                    },
                ),
            onClick = if (disabled) onOpenBufferSettings else onListenToggle,

        )
    }
}

@Composable
private fun CaptureActionButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    destructive: Boolean = false,
    flipDegrees: Float = 0f,
    flipPivotX: Float = 0.5f,
    flipDepthScale: Float = 1f,
    onClick: () -> Unit,
) {
    val chrome = appChrome()
    val tint = when {
        !enabled -> chrome.muted.copy(alpha = 0.28f)
        destructive -> MaterialTheme.colorScheme.error
        else -> chrome.ink
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(54.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier
                .size(25.dp)
                .graphicsLayer {
                    rotationY = flipDegrees
                    transformOrigin = TransformOrigin(flipPivotX, 0.5f)
                    scaleY = flipDepthScale
                    cameraDistance = 24.dp.toPx()
                },
        )
    }
}

@Composable
private fun CaptureSaveStatusCard(
    status: CaptureSaveStatus?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renderedStatus by remember { mutableStateOf<CaptureSaveStatus?>(status) }
    LaunchedEffect(status) {
        if (status != null) renderedStatus = status
    }

    AnimatedVisibility(
        visible = status != null,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        when (val current = renderedStatus) {
            is CaptureSaveStatus.Saving -> SavingRecordingCard(
                modifier = Modifier.fillMaxWidth(),
                onCancel = if (current.cancellable) onCancel else null,
            )
            is CaptureSaveStatus.Saved -> RecordingEntityCard(
                recording = current.recording,
                modifier = Modifier.fillMaxWidth(),
            )
            null -> Unit
        }
    }
}

@Composable
private fun AudioBlobControl(
    isListening: Boolean,
    isSaving: Boolean,
    blobController: AudioBlobController,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    clickableEnabled: Boolean = enabled,
    filled: Boolean = false,
    dimmed: Boolean = false,
    primaryText: String? = null,
    secondaryText: String? = null,
    visualizerVisible: Boolean = true,
    flipDegrees: Float = 0f,
    flipPivotX: Float = 0.5f,
    flipDepthScale: Float = 1f,
    contentAlpha: Float = 1f,
) {
    val active = isListening
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val attachedView = remember { arrayOfNulls<AudioBlobView>(1) }
    val attachedController = remember { arrayOfNulls<AudioBlobController>(1) }

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = tween(100),
        label = "pressScale",
    )
    val colors = MaterialTheme.colorScheme
    val interactionEnabled = clickableEnabled && !isSaving
    val visuallyEnabled = enabled || filled
    val actionIcon = when {
        filled -> AppIcons.check
        isListening -> AppIcons.pause
        else -> AppIcons.capture
    }
    val actionDescription = when {
        filled -> stringResource(R.string.buffer_filled)
        isListening -> stringResource(R.string.tap_to_pause_buffer)
        else -> stringResource(R.string.tap_to_start_buffer)
    }
    val contentColor = when {
        filled -> colors.onPrimaryContainer
        active -> colors.onPrimary
        else -> colors.onSurfaceVariant
    }
    val flipCameraDistancePx = with(LocalDensity.current) { 24.dp.toPx() }

    DisposableEffect(Unit) {
        onDispose {
            val view = attachedView[0]
            if (view != null) attachedController[0]?.detach(view)
            attachedController[0] = null
            attachedView[0] = null
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer(
                alpha = if (!dimmed && visuallyEnabled && !isSaving) 1f else 0.56f,
                scaleX = pressScale,
                scaleY = pressScale * flipDepthScale,
                rotationY = flipDegrees,
                transformOrigin = TransformOrigin(flipPivotX, 0.5f),
                cameraDistance = flipCameraDistancePx,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = interactionEnabled,
                onClick = onClick,
            ),
    ) {
        AndroidView(
            factory = { context ->
                AudioBlobView(context).also { view ->
                    attachedView[0] = view
                    attachedController[0] = blobController
                    blobController.attach(view)
                }
            },
            update = { view ->
                // AndroidView reuses this same View when the displayed buffer changes. Transfer
                // the renderer to the new buffer controller here; factory is not called again.
                if (attachedView[0] !== view) {
                    attachedView[0]?.let { oldView -> attachedController[0]?.detach(oldView) }
                    attachedView[0] = view
                    attachedController[0] = null
                }
                if (attachedController[0] !== blobController) {
                    attachedController[0]?.detach(view)
                    blobController.attach(view)
                    attachedController[0] = blobController
                }
                view.updateState(
                    active = active,
                    enabled = visuallyEnabled,
                    saving = isSaving,
                    visible = visualizerVisible,
                    primary = colors.primary.toArgb(),
                    tertiary = colors.tertiary.toArgb(),
                    paused = if (filled) {
                        colors.primaryContainer.toArgb()
                    } else {
                        colors.surfaceContainerHighest.toArgb()
                    },
                )
            },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) },
        ) {
            Icon(
                imageVector = actionIcon,
                contentDescription = actionDescription,
                tint = contentColor,
                modifier = Modifier.size(38.dp),
            )
            if (primaryText != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = primaryText,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                    ),
                    color = contentColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            if (secondaryText != null && (active || filled)) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = secondaryText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                    ),
                    color = contentColor.copy(alpha = 0.76f),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ErrorSheet(
    message: String,
    onDismiss: () -> Unit,
) {
    ReverbActionSheet(
        title = stringResource(R.string.error),
        onDismiss = onDismiss,
        content = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun ClearBufferSheet(
    bufferSlot: ReverbService.BufferSlot,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (bufferSlot) {
        ReverbService.BufferSlot.ONE_SHOT -> stringResource(R.string.clear_one_shot_title)
        ReverbService.BufferSlot.LOOPING -> stringResource(R.string.clear_loop_title)
    }
    ReverbActionSheet(
        title = title,
        onDismiss = onDismiss,
        content = {
            Text(
                text = stringResource(R.string.clear_buffer_confirmation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(
                    imageVector = AppIcons.delete,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.clear_buffer))
            }
        },
    )
}

@Composable
private fun ClearBufferProgressSheet(
    status: BufferClearStatus,
    onCancel: () -> Unit,
) {
    val title = when (status.bufferSlot) {
        ReverbService.BufferSlot.ONE_SHOT -> stringResource(R.string.clear_one_shot_progress_title)
        ReverbService.BufferSlot.LOOPING -> stringResource(R.string.clear_loop_progress_title)
    }
    val progress = bufferClearProgressFraction(status)
    val cancelling = status.phase == BufferClearPhase.CANCELLING
    ReverbActionSheet(
        title = title,
        onDismiss = {},
        content = {
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            val detail = if (status.totalBytes > 0L) {
                val percent = ((progress ?: 0f) * 100f).toInt().coerceIn(0, 100)
                stringResource(
                    R.string.clear_buffer_progress_detail,
                    percent,
                    formatShortFileSize(status.remainingBytes),
                )
            } else {
                stringResource(R.string.clear_buffer_progress_starting)
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.clear_buffer_cancel_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            TextButton(
                onClick = onCancel,
                enabled = !cancelling,
            ) {
                Text(
                    stringResource(
                        if (cancelling) R.string.clear_buffer_cancelling else R.string.cancel,
                    ),
                )
            }
        },
    )
}

@Composable
private fun ExportClampSheet(
    clampedDurationSeconds: Float,
    onProceed: () -> Unit,
    onDismiss: () -> Unit,
) {
    ReverbActionSheet(
        title = stringResource(R.string.export_limit_dialog_title),
        onDismiss = onDismiss,
        content = {
            Text(
                text = stringResource(R.string.export_limit_dialog_message, formatShortTimer(clampedDurationSeconds)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onProceed) { Text(stringResource(R.string.export)) }
        },
    )
}

private fun startExport(
    context: Context,
    service: ReverbService?,
    range: ExportRange,
    snapshot: ReverbService.TimelineSnapshot? = null,
    setSaving: (Boolean) -> Unit,
    onStatus: (CaptureSaveStatus?) -> Unit,
    onError: (String) -> Unit = {},
    onSaved: () -> Unit = {},
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

private fun buildCustomExportRange(
    availableSeconds: Double,
    requestedStartSeconds: Float,
    requestedEndSeconds: Float,
    exportConfig: ExportUiConfig,
): ExportRange {
    val available = availableSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    val start = requestedStartSeconds.toDouble().coerceIn(0.0, available).toFloat()
    val end = requestedEndSeconds.toDouble().coerceIn(start.toDouble(), available).toFloat()
    val maxDuration = exportDurationLimitExactSeconds(
        exportConfig.format,
        exportConfig.codec,
        exportConfig.sampleRate,
        exportConfig.channelCount,
        exportConfig.sampleFormat,
    )
    val selectedDuration = rangeSelectionDurationExactSeconds(start, end)
    return if (rangeExportSelectionWithinLimit(selectedDuration, maxDuration)) {
        ExportRange(start, end, null)
    } else {
        ExportRange(
            startSeconds = (end.toDouble() - maxDuration).coerceAtLeast(0.0).toFloat(),
            endSeconds = end,
            warningDurationSeconds = maxDuration.toFloat(),
        )
    }
}

private fun handleExport(
    context: Context,
    service: ReverbService?,
    bufferSeconds: Float,
    onRange: (ExportRange) -> Unit,
) {
    val exportConfig = currentExportConfig(context, service)
    val maxDuration = exportDurationLimitExactSeconds(
        exportConfig.format, exportConfig.codec, exportConfig.sampleRate,
        exportConfig.channelCount, exportConfig.sampleFormat,
    )
    if (rangeExportSelectionWithinLimit(bufferSeconds.toDouble(), maxDuration)) {
        onRange(ExportRange(0f, bufferSeconds, null))
    } else {
        onRange(
            ExportRange(
                startSeconds = (bufferSeconds.toDouble() - maxDuration).coerceAtLeast(0.0).toFloat(),
                endSeconds = bufferSeconds,
                warningDurationSeconds = maxDuration.toFloat(),
            ),
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
) {
    private var setSavingCallback: ((Boolean) -> Unit)? = setSaving
    private var statusCallback: ((CaptureSaveStatus?) -> Unit)? = onStatus
    private var errorCallback: ((String) -> Unit)? = onError
    private var savedCallback: (() -> Unit)? = onSaved
    var attached: Boolean = true
        private set

    fun detach() {
        attached = false
        setSavingCallback = null
        statusCallback = null
        errorCallback = null
        savedCallback = null
    }

    fun saved(recording: RecordingEntity): Boolean {
        if (!attached) return false
        statusCallback?.invoke(CaptureSaveStatus.Saved(recording))
        setSavingCallback?.invoke(false)
        savedCallback?.invoke()
        return true
    }

    fun failed(message: String): Boolean {
        if (!attached) return false
        setSavingCallback?.invoke(false)
        statusCallback?.invoke(null)
        errorCallback?.invoke(message)
        return true
    }

    fun cancelled(): Boolean {
        if (!attached) return false
        setSavingCallback?.invoke(false)
        statusCallback?.invoke(null)
        return true
    }
}

private class SaveResultReceiver(
    context: Context,
    setSaving: (Boolean) -> Unit,
    onStatus: (CaptureSaveStatus?) -> Unit,
    onError: (String) -> Unit = {},
    onSaved: () -> Unit = {},
    private val onCommitted: (RecordingEntity) -> Unit = {},
    private val onTerminal: (SaveResultReceiver) -> Unit = {},
) : ReverbService.AudioFileReceiver {
    private val appContext = context.applicationContext
    private val uiCallbacks = SaveUiCallbackGate(setSaving, onStatus, onError, onSaved)
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
                    fallback = { NotifyFileReceiver(appContext).fileReady(recording) },
                )
            },
            finish = ::finish,
        )
    }

    override fun fileFailed(message: String, error: Throwable?) {
        if (!terminalDelivered.compareAndSet(false, true)) return
        deliverTerminalResult(
            deliver = {
                val text = if (message.isBlank()) appContext.getString(R.string.save_failed) else message
                deliverVisibleTerminalOrFallback(
                    deliverVisible = { uiCallbacks.failed(text) },
                    fallback = { NotifyFileReceiver(appContext).fileFailed(message, error) },
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

private fun currentExportConfig(context: Context, recorder: ReverbService?): ExportUiConfig {
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
