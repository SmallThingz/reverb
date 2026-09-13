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
import androidx.compose.ui.graphics.Color
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
import java.util.concurrent.atomic.AtomicBoolean

private val backgroundRecordingResultScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class NotifyFileReceiver(
    private val context: Context,
) : ReverbService.AudioFileReceiver {
    private val appContext = context.applicationContext
    override fun fileReady(recording: RecordingEntity) {
        backgroundRecordingResultScope.launch {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return@launch
            runCatching {
                NotificationManagerCompat.from(appContext).notify(43, buildCaptureNotification(appContext, recording))
            }
        }
    }

    override fun fileFailed(message: String, error: Throwable?) {
        AppFeedbackCenter.post(
            message.ifBlank { appContext.getString(R.string.save_failed) },
            FeedbackTone.ERROR,
        )
    }
}

fun buildCaptureNotification(context: Context, recording: RecordingEntity): Notification {
    context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
        NotificationChannel(
            ReverbService.NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.app_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ),
    )
    val intent = buildOpenRecordingIntent(context, recording)
    val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
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

private data class ExportRange(
    val startSeconds: Float,
    val endSeconds: Float,
    val warningDurationSeconds: Float?,
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
    var isListening by remember { mutableStateOf(false) }
    var activeBuffer by remember { mutableStateOf<ReverbService.BufferSlot?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var oneShotDurationSeconds by remember { mutableFloatStateOf(0f) }
    var oneShotPayloadBytes by remember { mutableLongStateOf(0L) }
    var loopingDurationSeconds by remember { mutableFloatStateOf(0f) }
    var loopingPayloadBytes by remember { mutableLongStateOf(0L) }
    var oneShotEnabled by remember { mutableStateOf(isConfiguredOneShotBufferEnabled(context)) }
    var oneShotFull by remember { mutableStateOf(false) }
    var loopingEnabled by remember { mutableStateOf(isConfiguredLoopingBufferEnabled(context)) }
    var selectedBuffer by rememberSaveable {
        mutableStateOf(
            if (oneShotEnabled) ReverbService.BufferSlot.ONE_SHOT else ReverbService.BufferSlot.LOOPING,
        )
    }
    var startupBufferChosen by remember { mutableStateOf(false) }
    var latestListeningCommandGeneration by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var serviceConnectionGeneration by remember { mutableLongStateOf(0L) }
    val oneShotBlobController = remember { AudioBlobController() }
    val loopingBlobController = remember { AudioBlobController() }

    var pendingClearBuffer by remember { mutableStateOf<ReverbService.BufferSlot?>(null) }
    var showExportClampDialog by remember { mutableStateOf(false) }
    var clampWarningSeconds by remember { mutableFloatStateOf(0f) }
    var pendingExportRange by remember { mutableStateOf<ExportRange?>(null) } // Not saveable — non-serializable
    var rangeSnapshot by remember { mutableStateOf<ReverbService.TimelineSnapshot?>(null) }
    var rangeSnapshotBuffer by remember { mutableStateOf<ReverbService.BufferSlot?>(null) }
    var pendingExportSnapshot by remember { mutableStateOf<ReverbService.TimelineSnapshot?>(null) }
    var isPreparingRange by remember { mutableStateOf(false) }
    var customRangeRequestGeneration by remember { mutableLongStateOf(0L) }
    var pendingCustomRangeBuffer by remember { mutableStateOf<ReverbService.BufferSlot?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var saveStatus by remember { mutableStateOf<CaptureSaveStatus?>(null) }
    val screenAlive = remember { AtomicBoolean(true) }

    fun invalidateCustomRangePreparation() {
        customRangeRequestGeneration++
        pendingCustomRangeBuffer = null
        rangeSnapshotBuffer = null
        isPreparingRange = false
    }

    fun requestRecorderState(recorder: ReverbService) {
        val requestConnectionGeneration = serviceConnectionGeneration
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
                ) {
                    if (!shouldApplyRecorderStateSnapshot(
                            snapshotConnectionGeneration = requestConnectionGeneration,
                            currentConnectionGeneration = serviceConnectionGeneration,
                            snapshotGeneration = commandGeneration,
                            latestCommandGeneration = latestListeningCommandGeneration,
                        )
                    ) return
                    latestListeningCommandGeneration = commandGeneration
                    val previousActiveBuffer = activeBuffer
                    isListening = listeningEnabled
                    activeBuffer = activeBufferSlot
                    oneShotDurationSeconds = oneShotSeconds
                    oneShotPayloadBytes = oneShotBytes
                    loopingDurationSeconds = loopingSeconds
                    loopingPayloadBytes = loopingBytes
                    oneShotEnabled = oneShotIsEnabled
                    oneShotFull = oneShotIsFull
                    loopingEnabled = loopingIsEnabled

                    if (!startupBufferChosen) {
                        selectedBuffer = activeBufferSlot ?: defaultStartupBufferSlot(
                            oneShotEnabled = oneShotIsEnabled,
                            oneShotFull = oneShotIsFull,
                            loopingEnabled = loopingIsEnabled,
                        )
                        startupBufferChosen = true
                    } else if (
                        listeningEnabled &&
                        previousActiveBuffer == ReverbService.BufferSlot.ONE_SHOT &&
                        activeBufferSlot == ReverbService.BufferSlot.LOOPING
                    ) {
                        selectedBuffer = ReverbService.BufferSlot.LOOPING
                    }
                }
            },
        )
    }

    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, binder: IBinder) {
                val typedBinder = binder as? ReverbService.BackgroundRecorderBinder
                    ?: run {
                        rangeSnapshot?.close()
                        rangeSnapshot = null
                        rangeSnapshotBuffer = null
                        pendingExportSnapshot?.close()
                        pendingExportSnapshot = null
                        pendingExportRange = null
                        showExportClampDialog = false
                        pendingClearBuffer = null
                        invalidateCustomRangePreparation()
                        serviceConnectionGeneration++
                        service = null
                        return
                    }
                val connectedService = typedBinder.service
                if (service != null && service !== connectedService) {
                    rangeSnapshot?.close()
                    rangeSnapshot = null
                    rangeSnapshotBuffer = null
                    pendingExportSnapshot?.close()
                    pendingExportSnapshot = null
                    pendingExportRange = null
                    showExportClampDialog = false
                    pendingClearBuffer = null
                    invalidateCustomRangePreparation()
                }
                serviceConnectionGeneration++
                latestListeningCommandGeneration = Long.MIN_VALUE
                service = connectedService
                requestRecorderState(connectedService)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                rangeSnapshot?.close()
                rangeSnapshot = null
                rangeSnapshotBuffer = null
                pendingExportSnapshot?.close()
                pendingExportSnapshot = null
                pendingExportRange = null
                showExportClampDialog = false
                pendingClearBuffer = null
                invalidateCustomRangePreparation()
                if (isSaving) {
                    isSaving = false
                    saveStatus = null
                    errorMessage = resources.getString(R.string.save_failed)
                }
                serviceConnectionGeneration++
                service = null
            }
        }
    }

    val activeBufferState = androidx.compose.runtime.rememberUpdatedState(activeBuffer)
    val selectedBufferState = androidx.compose.runtime.rememberUpdatedState(selectedBuffer)
    val visualizationCallback = remember {
        ReverbService.VisualizationCallback { frame ->
            when (activeBufferState.value) {
                ReverbService.BufferSlot.ONE_SHOT -> oneShotBlobController.submit(frame)
                ReverbService.BufferSlot.LOOPING -> loopingBlobController.submit(frame)
                null -> Unit
            }
        }
    }

    LaunchedEffect(selectedBuffer) {
        val pendingBuffer = pendingCustomRangeBuffer
        if (pendingBuffer != null && pendingBuffer != selectedBuffer) {
            invalidateCustomRangePreparation()
        }
    }

    LaunchedEffect(activeBuffer) {
        when (activeBuffer) {
            ReverbService.BufferSlot.ONE_SHOT -> loopingBlobController.clear()
            ReverbService.BufferSlot.LOOPING -> oneShotBlobController.clear()
            null -> {
                oneShotBlobController.clear()
                loopingBlobController.clear()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            screenAlive.set(false)
            rangeSnapshot?.close()
            pendingExportSnapshot?.close()
        }
    }

    DisposableEffect(lifecycleOwner) {
        var bound = false
        fun bindIfNeeded() {
            if (!bound && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                bound = context.bindService(
                    Intent(context, ReverbService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> bindIfNeeded()

                Lifecycle.Event.ON_STOP -> {
                    rangeSnapshot?.close()
                    rangeSnapshot = null
                    rangeSnapshotBuffer = null
                    pendingExportSnapshot?.close()
                    pendingExportSnapshot = null
                    pendingExportRange = null
                    showExportClampDialog = false
                    pendingClearBuffer = null
                    invalidateCustomRangePreparation()
                    if (bound) {
                        context.unbindService(connection)
                        bound = false
                    }
                    serviceConnectionGeneration++
                    service = null
                }

                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        bindIfNeeded()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            rangeSnapshot?.close()
            rangeSnapshot = null
            rangeSnapshotBuffer = null
            pendingExportSnapshot?.close()
            pendingExportSnapshot = null
            pendingClearBuffer = null
            invalidateCustomRangePreparation()
            if (bound) {
                context.unbindService(connection)
                bound = false
            }
            serviceConnectionGeneration++
            service = null
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
                recorderService?.setVisualizationCallback(null)
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
                recorderService?.setVisualizationCallback(null)
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

    pendingClearBuffer?.let { bufferSlot ->
        ClearBufferSheet(
            bufferSlot = bufferSlot,
            onConfirm = {
                pendingClearBuffer = null
                service?.clearBuffer(bufferSlot)
            },
            onDismiss = { pendingClearBuffer = null },
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
                        context, service, range, scope,
                        snapshot = snapshot,
                        setSaving = { isSaving = it },
                        onStatus = { saveStatus = it },
                        onError = { errorMessage = it },
                        onSaved = onRecordingSaved,
                    )
                },
                onDismiss = {
                    showExportClampDialog = false
                    pendingExportRange = null
                    pendingExportSnapshot?.close()
                    pendingExportSnapshot = null
                },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val onListenToggle = remember(service, isSaving, isListening, activeBuffer) {
            { bufferSlot: ReverbService.BufferSlot ->
                val s = service
                if (s != null && !isSaving) {
                    val recordingThisBuffer = isListening && activeBuffer == bufferSlot
                    val result = when {
                        recordingThisBuffer -> s.disableListening()
                        isListening -> s.selectCaptureBuffer(bufferSlot)
                        else -> s.enableListening(bufferSlot)
                    }
                    if (result.accepted) {
                        latestListeningCommandGeneration = maxOf(
                            latestListeningCommandGeneration,
                            result.generation,
                        )
                        isListening = !recordingThisBuffer
                        activeBuffer = bufferSlot
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
            }
        }
        val onActivateBuffer = remember(
            service, isSaving, oneShotEnabled, oneShotFull, loopingEnabled,
        ) {
            { bufferSlot: ReverbService.BufferSlot ->
                selectedBuffer = bufferSlot
                val recorder = service
                val canActivate = canActivateCaptureBuffer(
                    requested = bufferSlot,
                    oneShotEnabled = oneShotEnabled,
                    oneShotFull = oneShotFull,
                    loopingEnabled = loopingEnabled,
                )
                if (recorder != null && !isSaving && canActivate) {
                    val result = recorder.selectCaptureBuffer(bufferSlot)
                    if (result.accepted) {
                        latestListeningCommandGeneration = maxOf(
                            latestListeningCommandGeneration,
                            result.generation,
                        )
                        activeBuffer = bufferSlot
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
            }
        }
        val onClearBuffer = remember(isSaving) {
            { bufferSlot: ReverbService.BufferSlot ->
                if (!isSaving) pendingClearBuffer = bufferSlot
            }
        }
        val onExportFull = remember(service, isSaving, isPreparingRange) {
            { bufferSlot: ReverbService.BufferSlot ->
                val s = service
                if (s != null && !isSaving && !isPreparingRange) {
                    isPreparingRange = true
                    s.acquireTimelineSnapshot(bufferSlot) { snapshot ->
                        isPreparingRange = false
                        if (!screenAlive.get() || service !== s) {
                            snapshot?.close()
                            return@acquireTimelineSnapshot
                        }
                        if (snapshot == null || snapshot.durationSeconds <= 0.0) {
                            snapshot?.close()
                            AppFeedbackCenter.post(
                                resources.getString(R.string.nothing_to_export),
                                FeedbackTone.INFO,
                            )
                            return@acquireTimelineSnapshot
                        }
                        handleExport(context, s, snapshot.durationSeconds.toFloat()) { range ->
                            if (range.warningDurationSeconds != null) {
                                clampWarningSeconds = range.warningDurationSeconds
                                pendingExportRange = range
                                pendingExportSnapshot?.close()
                                pendingExportSnapshot = snapshot
                                showExportClampDialog = true
                            } else {
                                startExport(
                                    context, s, range, scope,
                                    snapshot = snapshot,
                                    setSaving = { isSaving = it },
                                    onStatus = { saveStatus = it },
                                    onError = { errorMessage = it },
                                    onSaved = onRecordingSaved,
                                )
                            }
                        }
                    }
                }
            }
        }
        val onExportCustom = remember(
            service,
            isSaving,
            isPreparingRange,
            oneShotDurationSeconds,
            loopingDurationSeconds,
        ) {
            { bufferSlot: ReverbService.BufferSlot ->
                if (!isSaving && !isPreparingRange) {
                    val s = service
                    if (s != null) {
                        val secs = when (bufferSlot) {
                            ReverbService.BufferSlot.ONE_SHOT -> oneShotDurationSeconds
                            ReverbService.BufferSlot.LOOPING -> loopingDurationSeconds
                        }.coerceAtLeast(0f)
                        if (secs > 0f) {
                            val requestGeneration = customRangeRequestGeneration + 1L
                            customRangeRequestGeneration = requestGeneration
                            pendingCustomRangeBuffer = bufferSlot
                            rangeSnapshotBuffer = bufferSlot
                            isPreparingRange = true
                            s.acquireTimelineSnapshot(bufferSlot) { snapshot ->
                                val currentRequest = shouldApplyCustomRangeSnapshot(
                                    requestGeneration = requestGeneration,
                                    latestRequestGeneration = customRangeRequestGeneration,
                                    requestedBuffer = bufferSlot,
                                    pendingBuffer = pendingCustomRangeBuffer,
                                    selectedBuffer = selectedBufferState.value,
                                )
                                if (!currentRequest) {
                                    snapshot?.close()
                                    if (requestGeneration == customRangeRequestGeneration) {
                                        pendingCustomRangeBuffer = null
                                        isPreparingRange = false
                                    }
                                    return@acquireTimelineSnapshot
                                }
                                pendingCustomRangeBuffer = null
                                isPreparingRange = false
                                if (!screenAlive.get() || service !== s) {
                                    snapshot?.close()
                                } else if (snapshot != null && snapshot.durationSeconds > 0.0) {
                                    rangeSnapshot?.close()
                                    rangeSnapshot = snapshot
                                    rangeSnapshotBuffer = bufferSlot
                                } else {
                                    snapshot?.close()
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
        val rangeMaxDurationSeconds = exportDurationLimitSeconds(
            rangeConfig.format,
            rangeConfig.codec,
            rangeConfig.sampleRate,
            rangeConfig.channelCount,
            rangeConfig.sampleFormat,
        ).toFloat().coerceAtLeast(1f)
        val dismissRangeExport: () -> Unit = {
            rangeSnapshot?.close()
            rangeSnapshot = null
            rangeSnapshotBuffer = null
            invalidateCustomRangePreparation()
        }
        val rangeBackMotion = rememberPredictiveBackMotion(
            enabled = rangeSnapshotBuffer != null && visualizerVisible,
            onBack = dismissRangeExport,
        )
        val submitRangeExport: (Float, Float) -> Unit = submitRange@ { startSeconds, endSeconds ->
            val snapshot = rangeSnapshot ?: return@submitRange
            val range = buildCustomExportRange(
                availableSeconds = snapshot.durationSeconds,
                requestedStartSeconds = startSeconds,
                requestedEndSeconds = endSeconds,
                exportConfig = rangeConfig,
            )
            rangeSnapshot = null
            rangeSnapshotBuffer = null
            invalidateCustomRangePreparation()
            if (range.warningDurationSeconds != null) {
                clampWarningSeconds = range.warningDurationSeconds
                pendingExportRange = range
                pendingExportSnapshot?.close()
                pendingExportSnapshot = snapshot
                showExportClampDialog = true
            } else {
                startExport(
                    context, service, range, scope,
                    snapshot = snapshot,
                    setSaving = { isSaving = it },
                    onStatus = { saveStatus = it },
                    onError = { errorMessage = it },
                    onSaved = onRecordingSaved,
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
            isListening = isListening,
            isSaving = isSaving,
            service = service,
            oneShotBlobController = oneShotBlobController,
            loopingBlobController = loopingBlobController,
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
            onActivateBuffer = onActivateBuffer,
            onOpenBufferSettings = onOpenBufferSettings,
            visualizerVisible = visualizerVisible,
            onOpenLibrary = onOpenLibrary,
        )

        CaptureSaveStatusCard(
            status = saveStatus,
            onCancel = {
                val recorder = service
                if (recorder != null) {
                    recorder.cancelCurrentExport()
                    // Cancellation can lose a race with final commit. Keep the status visible
                    // and non-cancellable until the service delivers the terminal callback.
                    saveStatus = markExportCancelRequested(saveStatus)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 18.dp)
                .padding(bottom = 104.dp),
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
    val direction = if (source == ReverbService.BufferSlot.ONE_SHOT) -1f else 1f
    return if (p <= 0.5f) {
        direction * p * 180f
    } else {
        direction * (p - 1f) * 180f
    }
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
    isListening: Boolean,
    isSaving: Boolean,
    service: ReverbService?,
    oneShotBlobController: AudioBlobController,
    loopingBlobController: AudioBlobController,
    rangeSnapshot: ReverbService.TimelineSnapshot?,
    rangeSnapshotBuffer: ReverbService.BufferSlot?,
    rangeMaxExportDurationSeconds: Float,
    rangeBackProgress: Float,
    onCancelRangeExport: () -> Unit,
    onSubmitRangeExport: (Float, Float) -> Unit,
    onListenToggle: (ReverbService.BufferSlot) -> Unit,
    onClearBuffer: (ReverbService.BufferSlot) -> Unit,
    onExportFull: (ReverbService.BufferSlot) -> Unit,
    onExportCustom: (ReverbService.BufferSlot) -> Unit,
    onSelectBuffer: (ReverbService.BufferSlot) -> Unit,
    onActivateBuffer: (ReverbService.BufferSlot) -> Unit,
    onOpenBufferSettings: (ReverbService.BufferSlot) -> Unit,
    visualizerVisible: Boolean,
    onOpenLibrary: () -> Unit,
) {
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
        RangeExportHomeContent(
            snapshot = activeRangeSnapshot,
            initialDurationSeconds = rangeMetrics.seconds.coerceAtLeast(0.05f),
            selectedBuffer = rangeBuffer,
            activeBuffer = activeBuffer,
            blobMetrics = rangeMetrics,
            blobEnabled = rangeEnabled,
            blobController = rangeBlobController,
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
        animationSpec = if (bufferDragging) snap() else tween(durationMillis = 180),
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
    val serviceReady = service != null
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
            .padding(horizontal = 18.dp),
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
                onListenToggle = { onListenToggle(renderedBuffer) },
                onOpenBufferSettings = { onOpenBufferSettings(renderedBuffer) },
                visualizerVisible = visualizerVisible,
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
            onSelectBuffer = onActivateBuffer,
            onExportFull = { onExportFull(renderedBuffer) },
            onExportCustom = { onExportCustom(renderedBuffer) },
            onClearBuffer = { onClearBuffer(renderedBuffer) },
            onOpenLibrary = onOpenLibrary,
        )
        Spacer(Modifier.height(18.dp))
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
    val clusterHeight = 112.dp
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
                    onClick = onExportFull,
                )
                CaptureActionButton(
                    icon = AppIcons.exportRange,
                    contentDescription = stringResource(R.string.export_range_title),
                    enabled = serviceReady && !isSaving && hasHistory,
                    flipDegrees = flipDegrees,
                    onClick = onExportCustom,
                )
                CaptureActionButton(
                    icon = AppIcons.delete,
                    contentDescription = stringResource(R.string.clear_buffer),
                    enabled = serviceReady && !isSaving && hasHistory && !selectedRecording,
                    destructive = true,
                    flipDegrees = flipDegrees,
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
    bufferEnabled: Boolean,
    oneShotFull: Boolean,
    isListening: Boolean,
    isSaving: Boolean,
    service: ReverbService?,
    blobController: AudioBlobController,
    flipDegrees: Float,
    onListenToggle: () -> Unit,
    onOpenBufferSettings: () -> Unit,
    visualizerVisible: Boolean,
    modifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
    contentAlpha: Float = 1f,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val retentionMode = getConfiguredRetentionMode(context)
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
    val captureEnabled = serviceReady && !blockedByOther &&
        (uiState == CaptureBufferUiState.READY || recordingThisBuffer)
    val clickEnabled = interactionEnabled && !isSaving && (disabled || (!blockedByOther && captureEnabled))

    val displayedCurrentSeconds = metrics.seconds.coerceAtLeast(0f).toInt()
    val currentBytes = metrics.bytes.coerceAtLeast(0L)
    val exportConfig = currentExportConfig(context, service)
    val estimatedExportBytes = remember(exportConfig, displayedCurrentSeconds) {
        estimateExportSizeBytes(
            exportConfig.format, exportConfig.codec, exportConfig.sampleRate,
            exportConfig.channelCount, displayedCurrentSeconds.toLong(),
            exportConfig.sampleFormat,
        )
    }
    val exportLimitBytes = remember(exportConfig.format) { exportFileSizeLimitBytes(exportConfig.format) }
    val overExportLimit = remember(estimatedExportBytes, exportLimitBytes) { estimatedExportBytes > exportLimitBytes }
    val timerText = remember(retentionMode, displayedCurrentSeconds, currentBytes, disabled, resources) {
        when {
            disabled -> resources.getString(R.string.buffer_disabled)
            retentionMode == RetentionMode.TIME -> formatShortTimer(displayedCurrentSeconds.toFloat())
            else -> formatShortFileSize(currentBytes)
        }
    }
    val summaryText: String? = remember(
        retentionMode, overExportLimit, currentBytes, disabled,
        displayedCurrentSeconds, exportLimitBytes, context,
    ) {
        if (disabled) {
            null
        } else {
            val exportLimitSummary = resources.getString(
                R.string.export_limit_summary,
                formatShortFileSize(exportLimitBytes),
            )
            when {
                overExportLimit -> exportLimitSummary
                retentionMode == RetentionMode.TIME -> formatShortFileSize(currentBytes)
                else -> formatShortTimer(displayedCurrentSeconds.toFloat())
            }
        }
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
            primaryText = timerText,
            secondaryText = summaryText,
            showWarning = overExportLimit,
            visualizerVisible = visualizerVisible,
            flipDegrees = flipDegrees,
            contentAlpha = contentAlpha,
            modifier = Modifier.size(blobSize),
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
    showWarning: Boolean = false,
    visualizerVisible: Boolean = true,
    flipDegrees: Float = 0f,
    contentAlpha: Float = 1f,
) {
    val active = isListening
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val attachedView = remember { arrayOfNulls<AudioBlobView>(1) }

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

    DisposableEffect(blobController) {
        onDispose {
            attachedView[0]?.let(blobController::detach)
            attachedView[0] = null
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer(
                alpha = if (!dimmed && visuallyEnabled && !isSaving) 1f else 0.56f,
                scaleX = pressScale,
                scaleY = pressScale,
                rotationY = flipDegrees,
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
                    blobController.attach(view)
                }
            },
            update = { view ->
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
                    color = if (showWarning) colors.error else contentColor.copy(alpha = 0.76f),
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
    scope: CoroutineScope,
    snapshot: ReverbService.TimelineSnapshot? = null,
    setSaving: (Boolean) -> Unit,
    onStatus: (CaptureSaveStatus?) -> Unit,
    onError: (String) -> Unit = {},
    onSaved: () -> Unit = {},
) {
    val recorder = service ?: run {
        snapshot?.close()
        setSaving(false)
        onStatus(null)
        onError(context.getString(R.string.save_failed))
        return
    }
    setSaving(true)
    onStatus(CaptureSaveStatus.Saving(cancellable = true))
    val receiver = SaveResultReceiver(
            context = context,
            scope = scope,
            setSaving = setSaving,
            onStatus = onStatus,
            onError = onError,
            onSaved = onSaved,
    )
    try {
        if (snapshot != null) {
            recorder.dumpRecordingRange(snapshot, range.startSeconds, range.endSeconds, receiver, "")
        } else {
            recorder.dumpRecordingRange(range.startSeconds, range.endSeconds, receiver, "")
        }
    } catch (_: Exception) {
        runCatching { snapshot?.close() }
        setSaving(false)
        onStatus(null)
        onError(context.getString(R.string.save_failed))
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
    val maxDuration = exportDurationLimitSeconds(
        exportConfig.format,
        exportConfig.codec,
        exportConfig.sampleRate,
        exportConfig.channelCount,
        exportConfig.sampleFormat,
    ).toFloat().coerceAtLeast(1f)
    return if (end - start <= maxDuration) {
        ExportRange(start, end, null)
    } else {
        ExportRange(
            startSeconds = (end - maxDuration).coerceAtLeast(0f),
            endSeconds = end,
            warningDurationSeconds = maxDuration,
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
    val maxDuration = exportDurationLimitSeconds(
        exportConfig.format, exportConfig.codec, exportConfig.sampleRate,
        exportConfig.channelCount, exportConfig.sampleFormat,
    ).toFloat().coerceAtLeast(1f)
    if (bufferSeconds <= maxDuration) {
        onRange(ExportRange(0f, bufferSeconds, null))
    } else {
        onRange(
            ExportRange(
                startSeconds = (bufferSeconds - maxDuration).coerceAtLeast(0f),
                endSeconds = bufferSeconds,
                warningDurationSeconds = maxDuration,
            ),
        )
    }
}

private class SaveResultReceiver(
    context: Context,
    private val scope: CoroutineScope,
    private val setSaving: (Boolean) -> Unit,
    private val onStatus: (CaptureSaveStatus?) -> Unit,
    private val onError: (String) -> Unit = {},
    private val onSaved: () -> Unit = {},
) : ReverbService.AudioFileReceiver {
    private val appContext = context.applicationContext

    override fun fileReady(recording: RecordingEntity) {
        setSaving(false)
        scope.launch {
            onStatus(CaptureSaveStatus.Saved(recording))
            onSaved()
        }
    }

    override fun fileFailed(message: String, error: Throwable?) {
        setSaving(false)
        onStatus(null)
        val text = if (message.isBlank()) appContext.getString(R.string.save_failed) else message
        onError(text)
    }

    override fun fileCancelled() {
        setSaving(false)
        onStatus(null)
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
