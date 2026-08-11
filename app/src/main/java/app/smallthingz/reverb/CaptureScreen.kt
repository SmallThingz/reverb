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
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class NotifyFileReceiver(
    private val context: Context,
    private val scope: CoroutineScope,
) : ReverbService.AudioFileReceiver {
    private val appContext = context.applicationContext
    override fun fileReady(recording: RecordingEntity) {
        scope.launch(Dispatchers.IO) {
            val saved = runCatching { RecordingRepository.register(appContext, recording) }
                .getOrDefault(recording)
            if (
                ActivityCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return@launch
            runCatching {
                NotificationManagerCompat.from(appContext).notify(43, buildCaptureNotification(appContext, saved))
            }
        }
    }

    override fun fileFailed(message: String, error: Throwable?) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(
                appContext, message.ifBlank { appContext.getString(R.string.save_failed) },
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

fun buildCaptureNotification(context: Context, recording: RecordingEntity): Notification {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                ReverbService.NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.app_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
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
    val bitrateKbps: Int?,
)

@Composable
fun CaptureScreen() {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var service by remember { mutableStateOf<ReverbService?>(null) }
    var isListening by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var memorizedSeconds by remember { mutableFloatStateOf(0f) }
    var recordedSeconds by remember { mutableFloatStateOf(0f) }
    val blobController = remember { AudioBlobController() }

    var showClearDialog by remember { mutableStateOf(false) }
    var showExportRangeDialog by remember { mutableStateOf(false) }
    var showExportClampDialog by remember { mutableStateOf(false) }
    var clampWarningSeconds by remember { mutableFloatStateOf(0f) }
    var pendingExportRange by remember { mutableStateOf<ExportRange?>(null) } // Not saveable — non-serializable
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val stateCallback = remember {
        object : ReverbService.StateCallback {
            override fun state(
                listeningEnabled: Boolean,
                recording: Boolean,
                memorized: Float,
                totalMemory: Float,
                recorded: Float,
            ) {
                isListening = listeningEnabled
                isRecording = recording
                memorizedSeconds = memorized
                recordedSeconds = recorded
            }
        }
    }

    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, binder: IBinder) {
                val typedBinder = binder as? ReverbService.BackgroundRecorderBinder
                    ?: run {
                        service = null
                        return
                    }
                service = typedBinder.service
                service?.getState(stateCallback)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }
    }

    val visualizationCallback = remember {
        ReverbService.VisualizationCallback { frame ->
            blobController.submit(frame)
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
                    if (bound) {
                        context.unbindService(connection)
                        bound = false
                    }
                    service = null
                }

                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        bindIfNeeded()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (bound) {
                context.unbindService(connection)
                bound = false
            }
        }
    }

    DisposableEffect(service, lifecycleOwner, visualizationCallback, view) {
        val recorderService = service
        var registered = false
        var resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        var windowFocused = view.hasWindowFocus()

        fun updateRegistration() {
            val visible = resumed && windowFocused
            if (visible && !registered) {
                recorderService?.setVisualizationCallback(visualizationCallback)
                registered = recorderService != null
            } else if (!visible && registered) {
                recorderService?.setVisualizationCallback(null)
                registered = false
                blobController.clear()
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
            blobController.clear()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val s = service
            if (s != null) {
                s.getState(stateCallback)
                s.consumePendingError()?.let { errorMessage = it }
            }
            delay(500)
        }
    }

    if (showClearDialog) {
        ClearBufferDialog(
            onConfirm = {
                showClearDialog = false
                service?.clearBuffer()
            },
            onDismiss = { showClearDialog = false },
        )
    }

    if (showExportClampDialog) {
        if (pendingExportRange == null) {
            showExportClampDialog = false
        } else {
            ExportClampDialog(
                clampedDurationSeconds = clampWarningSeconds,
                onProceed = {
                    showExportClampDialog = false
                    val range = pendingExportRange ?: return@ExportClampDialog
                    pendingExportRange = null
                    startExport(
                        context, service, range, scope, snackbarHostState,
                        setSaving = { isSaving = it }, onError = { errorMessage = it },
                    )
                },
                onDismiss = {
                    showExportClampDialog = false
                    pendingExportRange = null
                },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isRecording) {
            val onStopRecording = remember(service, isSaving) {
                {
                    val s = service
                    if (s != null && !isSaving) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        isSaving = true
                        s.stopRecording(
                            SaveResultReceiver(
                                context, scope, snackbarHostState,
                                { isSaving = it }, { errorMessage = it },
                            ),
                        )
                    }
                }
            }
            RecordingOverlay(
                recordedSeconds = recordedSeconds,
                isSaving = isSaving,
                blobController = blobController,
                onStopRecording = onStopRecording,
            )
        } else {
            val onListenToggle = remember(service, isSaving, isListening) {
                {
                    val s = service
                    if (s != null && !isSaving) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        isListening = !isListening
                        if (isListening) s.enableListening() else s.disableListening()
                    }
                }
            }
            val onClearBuffer = remember(isSaving, isRecording) {
                {
                    if (!isSaving && !isRecording) {
                        showClearDialog = true
                    }
                }
            }
            val handler = remember(service) {
                { range: ExportRange ->
                    if (range.warningDurationSeconds != null) {
                        clampWarningSeconds = range.warningDurationSeconds
                        pendingExportRange = range
                        showExportClampDialog = true
                    } else {
                        startExport(
                            context, service, range, scope, snackbarHostState,
                            setSaving = { isSaving = it }, onError = { errorMessage = it },
                        )
                    }
                }
            }
            val onExportFull = remember(service, isSaving, memorizedSeconds, handler) {
                {
                    val s = service
                    if (s != null && !isSaving) {
                        val secs = memorizedSeconds.coerceAtLeast(0f)
                        if (secs > 0f) {
                            handleExport(context, s, secs, handler)
                        } else {
                            Toast.makeText(context, R.string.nothing_to_export, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            val onExportCustom = remember(isSaving, memorizedSeconds) {
                {
                    if (!isSaving) {
                        val secs = memorizedSeconds.coerceAtLeast(0f)
                        if (secs > 0f) {
                            showExportRangeDialog = true
                        } else {
                            Toast.makeText(context, R.string.nothing_to_export, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            MainCaptureContent(
                memorizedSeconds = memorizedSeconds,
                isListening = isListening,
                isRecording = isRecording,
                isSaving = isSaving,
                service = service,
                blobController = blobController,
                onListenToggle = onListenToggle,
                onClearBuffer = onClearBuffer,
                onExportFull = onExportFull,
                onExportCustom = onExportCustom,
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        ) { data ->
            Snackbar(snackbarData = data)
        }
    }

    if (showExportRangeDialog) {
        val currentSeconds = memorizedSeconds.coerceAtLeast(0f)
        ExportRangeDialog(
            currentBufferSeconds = currentSeconds,
            onExport = { range ->
                showExportRangeDialog = false
                if (range.warningDurationSeconds != null) {
                    clampWarningSeconds = range.warningDurationSeconds
                    pendingExportRange = range
                    showExportClampDialog = true
                } else {
                    startExport(
                        context, service, range, scope, snackbarHostState,
                        setSaving = { isSaving = it }, onError = { errorMessage = it },
                    )
                }
            },
            onDismiss = { showExportRangeDialog = false },
        )
    }

    errorMessage?.let { msg ->
        ErrorDialog(
            message = msg,
            onDismiss = { errorMessage = null },
        )
    }
}

@Composable
private fun MainCaptureContent(
    memorizedSeconds: Float,
    isListening: Boolean,
    isRecording: Boolean,
    isSaving: Boolean,
    service: ReverbService?,
    blobController: AudioBlobController,
    onListenToggle: () -> Unit,
    onClearBuffer: () -> Unit,
    onExportFull: () -> Unit,
    onExportCustom: () -> Unit,
) {
    val context = LocalContext.current
    val retentionMode = getConfiguredRetentionMode(context)

    val displayedCurrentSeconds = memorizedSeconds.coerceAtLeast(0f).toInt()
    val exportConfig = currentExportConfig(context, service)
    val currentBytes = remember(exportConfig, displayedCurrentSeconds) {
        estimateExportSizeBytes(
            exportConfig.format, exportConfig.codec, exportConfig.sampleRate,
            exportConfig.channelCount, displayedCurrentSeconds.toLong(),
            exportConfig.bitrateKbps, exportConfig.sampleFormat,
        )
    }
    val exportLimitBytes = remember(exportConfig.format) { exportFileSizeLimitBytes(exportConfig.format) }
    val overExportLimit = remember(currentBytes, exportLimitBytes) { currentBytes > exportLimitBytes }

    val timerText = remember(retentionMode, displayedCurrentSeconds, currentBytes) {
        when (retentionMode) {
            RetentionMode.TIME -> formatShortTimer(displayedCurrentSeconds.toFloat())
            RetentionMode.SIZE -> formatShortFileSize(currentBytes)
        }
    }
    val summaryText = remember(
        retentionMode, overExportLimit, currentBytes,
        displayedCurrentSeconds, exportLimitBytes, context,
    ) {
        val exportLimitSummary = context.getString(R.string.export_limit_summary, formatShortFileSize(exportLimitBytes))
        when (retentionMode) {
            RetentionMode.TIME -> if (overExportLimit) exportLimitSummary else formatShortFileSize(currentBytes)
            RetentionMode.SIZE -> if (overExportLimit) exportLimitSummary else formatShortTimer(displayedCurrentSeconds.toFloat())
        }
    }
    val summaryColor = if (overExportLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

    val serviceReady = service != null
    val hasHistory = memorizedSeconds > 0f
    val exportBlocked = !serviceReady || isSaving || isRecording || !hasHistory
    val clearEnabled = serviceReady && !isSaving && !isRecording && hasHistory

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(
                        if (isListening) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = timerText,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = summaryText,
                style = MaterialTheme.typography.bodySmall,
                color = summaryColor,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }

        Spacer(Modifier.weight(1f))

        AudioBlobControl(
            isListening = isListening,
            isRecording = false,
            isSaving = isSaving,
            enabled = serviceReady,
            blobController = blobController,
            onClick = onListenToggle,
        )

        Spacer(Modifier.weight(1f))

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CaptureActionButton(
                    icon = R.drawable.ic_export_all,
                    contentDescription = stringResource(R.string.record_all_memory),
                    enabled = !exportBlocked,
                    onClick = onExportFull,
                )
                CaptureActionButton(
                    icon = R.drawable.ic_export_range,
                    contentDescription = stringResource(R.string.export_range_title),
                    enabled = !exportBlocked,
                    onClick = onExportCustom,
                )
                CaptureActionButton(
                    icon = R.drawable.ic_delete,
                    contentDescription = stringResource(R.string.clear_buffer),
                    enabled = clearEnabled,
                    destructive = true,
                    onClick = onClearBuffer,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun CaptureActionButton(
    icon: Int,
    contentDescription: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(54.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(25.dp),
        )
    }
}

@Composable
private fun RecordingOverlay(
    recordedSeconds: Float,
    isSaving: Boolean,
    blobController: AudioBlobController,
    onStopRecording: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val timerText = remember(recordedSeconds) { formatShortTimer(recordedSeconds) }
            Text(
                text = timerText,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(34.dp))
            AudioBlobControl(
                isListening = true,
                isRecording = true,
                isSaving = isSaving,
                blobController = blobController,
                onClick = onStopRecording,
            )
        }
    }
}

@Composable
private fun AudioBlobControl(
    isListening: Boolean,
    isRecording: Boolean,
    isSaving: Boolean,
    blobController: AudioBlobController,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val active = isListening || isRecording
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val attachedView = remember { arrayOfNulls<AudioBlobView>(1) }

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = tween(100),
        label = "pressScale",
    )
    val colors = MaterialTheme.colorScheme
    val interactionEnabled = enabled && !isSaving
    val actionIcon = when {
        isRecording -> R.drawable.ic_stop
        isListening -> R.drawable.ic_player_pause
        else -> R.drawable.ic_capture_wave
    }
    val actionDescription = when {
        isRecording -> stringResource(R.string.done)
        isListening -> stringResource(R.string.tap_to_pause_buffer)
        else -> stringResource(R.string.tap_to_start_buffer)
    }
    val contentColor = when {
        isRecording -> colors.onError
        active -> colors.onPrimary
        else -> colors.onSurfaceVariant
    }

    DisposableEffect(blobController) {
        onDispose {
            attachedView[0]?.let(blobController::detach)
            attachedView[0] = null
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(236.dp)
            .graphicsLayer(
                alpha = if (interactionEnabled) 1f else 0.56f,
                scaleX = pressScale,
                scaleY = pressScale,
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
                    recording = isRecording,
                    enabled = enabled,
                    saving = isSaving,
                    primary = colors.primary.toArgb(),
                    tertiary = colors.tertiary.toArgb(),
                    paused = colors.surfaceContainerHighest.toArgb(),
                    error = colors.error.toArgb(),
                )
            },
            modifier = Modifier.fillMaxSize(),
        )

        Icon(
            painter = painterResource(actionIcon),
            contentDescription = actionDescription,
            tint = contentColor,
            modifier = Modifier.size(if (isRecording) 38.dp else 42.dp),
        )
    }
}

@Composable
private fun ClearBufferDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.clear_history_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.clear_buffer),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    )
}

@Composable
private fun ExportClampDialog(
    clampedDurationSeconds: Float,
    onProceed: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.export_limit_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Text(
                text = stringResource(R.string.export_limit_dialog_message, formatShortTimer(clampedDurationSeconds)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onProceed) {
                Text(stringResource(R.string.export))
            }
        },
    )
}

@Composable
private fun ExportRangeDialog(
    currentBufferSeconds: Float,
    onExport: (ExportRange) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val availableSeconds = remember { currentBufferSeconds.coerceAtLeast(0f) }
    val maxSeconds = availableSeconds.coerceAtLeast(1f)
    var rangeStart by remember { mutableFloatStateOf(0f) }
    var rangeEnd by remember { mutableFloatStateOf(availableSeconds) }
    var startText by remember { mutableStateOf("0:00") }
    var endText by remember {
        mutableStateOf(formatDurationInput(availableSeconds.roundToInt()))
    }
    var startError by remember { mutableStateOf<String?>(null) }
    var endError by remember { mutableStateOf<String?>(null) }

    fun clampExportRange(startSeconds: Float, endSeconds: Float): ExportRange {
        val exportConfig = currentExportConfig(context, null)
        val maxDurationSeconds = exportDurationLimitSeconds(
            exportConfig.format,
            exportConfig.codec,
            exportConfig.sampleRate,
            exportConfig.channelCount,
            exportConfig.bitrateKbps,
            exportConfig.sampleFormat,
        ).toFloat().coerceAtLeast(1f)
        val boundedEnd = endSeconds.coerceAtLeast(startSeconds)
        val requestedDuration = boundedEnd - startSeconds
        return if (requestedDuration <= maxDurationSeconds) {
            ExportRange(startSeconds, boundedEnd, null)
        } else {
            ExportRange(
                startSeconds = (boundedEnd - maxDurationSeconds).coerceAtLeast(0f),
                endSeconds = boundedEnd,
                warningDurationSeconds = maxDurationSeconds,
            )
        }
    }

    fun applyTextRange() {
        val parsedStart = parseDurationInput(startText)?.toFloat()
        val parsedEnd = parseDurationInput(endText)?.toFloat()
        startError = if (parsedStart == null || parsedStart < 0f || parsedStart >= availableSeconds) {
            context.getString(R.string.custom_export_range_invalid)
        } else null
        endError = if (parsedEnd == null || parsedEnd <= 0f || parsedEnd > availableSeconds ||
            (parsedStart != null && parsedEnd <= parsedStart)
        ) {
            context.getString(R.string.custom_export_range_invalid)
        } else null
        if (startError == null && endError == null && parsedStart != null && parsedEnd != null) {
            rangeStart = parsedStart
            rangeEnd = parsedEnd
        }
    }

    fun submit() {
        applyTextRange()
        if (startError != null || endError != null || availableSeconds <= 0f) return
        onExport(clampExportRange(rangeStart, rangeEnd))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(22.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_export_range),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.export_range_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "${formatShortTimer(rangeStart)}  –  ${formatShortTimer(rangeEnd)}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                RangeSlider(
                    value = rangeStart..rangeEnd,
                    onValueChange = { range ->
                        val start = range.start.coerceIn(0f, availableSeconds)
                        val end = range.endInclusive.coerceIn(start, availableSeconds)
                        rangeStart = start
                        rangeEnd = end
                        startText = formatDurationInput(start.roundToInt())
                        endText = formatDurationInput(end.roundToInt())
                        startError = null
                        endError = null
                    },
                    valueRange = 0f..maxSeconds,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { value ->
                            startText = value
                            startError = null
                            val parsed = parseDurationInput(value)?.toFloat()
                            if (parsed != null && parsed >= 0f && parsed < rangeEnd) rangeStart = parsed
                        },
                        label = { Text(stringResource(R.string.custom_export_start_label)) },
                        isError = startError != null,
                        supportingText = startError?.let { { Text(it) } },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { value ->
                            endText = value
                            endError = null
                            val parsed = parseDurationInput(value)?.toFloat()
                            if (parsed != null && parsed > rangeStart && parsed <= availableSeconds) rangeEnd = parsed
                        },
                        label = { Text(stringResource(R.string.custom_export_end_label)) },
                        isError = endError != null,
                        supportingText = endError?.let { { Text(it) } },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = stringResource(R.string.export_range_buffer_hint, formatShortTimer(availableSeconds)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_export_all),
                    contentDescription = stringResource(R.string.export),
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    )
}

private fun startExport(
    context: Context,
    service: ReverbService?,
    range: ExportRange,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    setSaving: (Boolean) -> Unit,
    onError: (String) -> Unit = {},
) {
    val s = service ?: return
    setSaving(true)
    scope.launch {
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.saving),
            actionLabel = context.getString(R.string.cancel),
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            s.cancelCurrentExport()
        }
    }
    s.dumpRecordingRange(
        range.startSeconds,
        range.endSeconds,
        SaveResultReceiver(context, scope, snackbarHostState, setSaving, onError),
        "",
    )
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
        exportConfig.channelCount, exportConfig.bitrateKbps, exportConfig.sampleFormat,
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
    private val context: Context,
    private val scope: CoroutineScope,
    private val snackbarHostState: SnackbarHostState,
    private val setSaving: (Boolean) -> Unit,
    private val onError: (String) -> Unit = {},
) : ReverbService.AudioFileReceiver {
    private val appContext = context.applicationContext
    override fun fileReady(recording: RecordingEntity) {
        setSaving(false)
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            runCatching { RecordingRepository.register(appContext, recording) }
            snackbarHostState.showSnackbar(
                message = appContext.getString(R.string.saved_snackbar),
                duration = SnackbarDuration.Short,
            )
        }
    }

    override fun fileFailed(message: String, error: Throwable?) {
        setSaving(false)
        val text = if (message.isBlank()) appContext.getString(R.string.save_failed) else message
        onError(text)
        scope.launch { snackbarHostState.currentSnackbarData?.dismiss() }
    }

    override fun fileCancelled() {
        setSaving(false)
        scope.launch { snackbarHostState.currentSnackbarData?.dismiss() }
    }
}

private fun currentExportConfig(context: Context, recorder: ReverbService?): ExportUiConfig {
    val activeConfig = recorder?.getConfigurationSnapshot()
    val format = activeConfig?.format ?: getConfiguredOutputFormat(context)
    val codec = activeConfig?.codec ?: getConfiguredOutputCodec(context)
    val sampleFormat = activeConfig?.sampleFormat ?: getConfiguredPcmSampleFormat(context)
    val sourceMode = activeConfig?.sourceMode ?: getConfiguredAudioSourceMode(context)
    val channelMode = activeConfig?.channelMode ?: getConfiguredChannelMode(context)
    val routeMode = activeConfig?.routeMode ?: getConfiguredInputRouteMode(context)
    val sampleRate = activeConfig?.sampleRate
        ?: getConfiguredSampleRate(context, sourceMode, routeMode, format, codec, channelMode)
    return ExportUiConfig(
        format = format,
        codec = codec,
        sampleFormat = sampleFormat,
        sampleRate = sampleRate,
        channelCount = channelMode.channelCount,
        bitrateKbps = getConfiguredCodecBitrateKbps(context, codec, sampleRate, channelMode.channelCount),
    )
}
