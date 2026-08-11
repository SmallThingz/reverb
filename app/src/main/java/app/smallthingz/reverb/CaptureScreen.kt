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
import android.content.ClipData
import android.content.ClipboardManager
import android.os.IBinder
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val MIB: Double = 1024.0 * 1024.0

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
    var totalMemorySeconds by remember { mutableFloatStateOf(0f) }
    var recordedSeconds by remember { mutableFloatStateOf(0f) }

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
                totalMemorySeconds = totalMemory
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

    DisposableEffect(lifecycleOwner) {
        var bound = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (!bound) {
                        bound = context.bindService(
                            Intent(context, ReverbService::class.java),
                            connection,
                            Context.BIND_AUTO_CREATE,
                        )
                    }
                }

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
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (bound) {
                context.unbindService(connection)
                bound = false
            }
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
                totalMemorySeconds = totalMemorySeconds,
                isListening = isListening,
                isRecording = isRecording,
                isSaving = isSaving,
                service = service,
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
        val currentBufferBytes = remember(context, service, memorizedSeconds) {
            currentBufferExportBytes(context, service, memorizedSeconds)
        }
        ExportRangeDialog(
            currentBufferSeconds = currentSeconds,
            currentBufferBytes = currentBufferBytes,
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
    totalMemorySeconds: Float,
    isListening: Boolean,
    isRecording: Boolean,
    isSaving: Boolean,
    service: ReverbService?,
    onListenToggle: () -> Unit,
    onClearBuffer: () -> Unit,
    onExportFull: () -> Unit,
    onExportCustom: () -> Unit,
) {
    val context = LocalContext.current
    val retentionMode = getConfiguredRetentionMode(context)
    val retentionSeconds = getConfiguredRetentionSeconds(context)

    val displayedCurrentSeconds = memorizedSeconds.coerceAtLeast(0f).toInt()
    val displayedLimitSeconds = when (retentionMode) {
        RetentionMode.TIME -> retentionSeconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        RetentionMode.SIZE -> totalMemorySeconds.coerceAtLeast(0f).toInt()
    }

    val exportConfig = currentExportConfig(context, service)
    val currentBytes = remember(exportConfig, displayedCurrentSeconds) {
        estimateExportSizeBytes(
            exportConfig.format, exportConfig.codec, exportConfig.sampleRate,
            exportConfig.channelCount, displayedCurrentSeconds.toLong(),
            exportConfig.bitrateKbps, exportConfig.sampleFormat,
        )
    }
    val limitBytes = remember(exportConfig, displayedLimitSeconds) {
        estimateExportSizeBytes(
            exportConfig.format, exportConfig.codec, exportConfig.sampleRate,
            exportConfig.channelCount, displayedLimitSeconds.toLong(),
            exportConfig.bitrateKbps, exportConfig.sampleFormat,
        )
    }
    val configuredLimitBytes = remember(retentionMode, limitBytes) {
        when (retentionMode) {
            RetentionMode.TIME -> limitBytes
            RetentionMode.SIZE -> getConfiguredRetentionSizeBytes(context)
        }
    }
    val exportLimitBytes = remember(exportConfig.format) { exportFileSizeLimitBytes(exportConfig.format) }
    val overExportLimit = remember(currentBytes, exportLimitBytes) { currentBytes > exportLimitBytes }

    val timerText = remember(
        retentionMode, displayedCurrentSeconds, displayedLimitSeconds,
        currentBytes, configuredLimitBytes,
    ) {
        when (retentionMode) {
            RetentionMode.TIME -> "${formatShortTimer(displayedCurrentSeconds.toFloat())} / ${formatShortTimer(displayedLimitSeconds.toFloat())}"
            RetentionMode.SIZE -> "${formatShortFileSize(currentBytes)} / ${formatShortFileSize(configuredLimitBytes)}"
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
    val fillProgress = if (totalMemorySeconds > 0f) {
        (memorizedSeconds / totalMemorySeconds).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(10.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (isListening) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = timerText,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = summaryColor,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { fillProgress },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        ListenCircle(
            isListening = isListening,
            isRecording = false,
            isSaving = isSaving,
            enabled = serviceReady,
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
                    contentDescription = stringResource(R.string.custom_time),
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
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(34.dp))
            ListenCircle(
                isListening = true,
                isRecording = true,
                isSaving = isSaving,
                onClick = onStopRecording,
            )
        }
    }
}

@Composable
private fun ListenCircle(
    isListening: Boolean,
    isRecording: Boolean,
    isSaving: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val active = isListening || isRecording
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(110),
        label = "pressScale",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = when {
            !active -> 1f
            isRecording -> 1.08f
            else -> 1.055f
        },
        animationSpec = infiniteRepeatable<Float>(
            animation = tween<Float>(
                durationMillis = if (isRecording) 1800 else if (active) 3000 else 1,
                easing = EaseInOutCubic,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    val ringColor = when {
        isSaving -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        isRecording -> MaterialTheme.colorScheme.error.copy(alpha = 0.20f)
        active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
    }
    val fillColor = when {
        isSaving -> MaterialTheme.colorScheme.surfaceContainerHigh
        isRecording -> MaterialTheme.colorScheme.error
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = when {
        isRecording -> MaterialTheme.colorScheme.surface
        active -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val interactionEnabled = enabled && !isSaving
    val innerStrokeColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { 2.dp.toPx() }
    val strokeStyle = remember(strokeWidthPx) { Stroke(strokeWidthPx) }
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

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(210.dp)
            .graphicsLayer(alpha = if (interactionEnabled) 1f else 0.62f),
    ) {
        Canvas(
            modifier = Modifier
                .size(202.dp)
                .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale),
        ) {
            drawCircle(
                color = ringColor,
                radius = size.minDimension / 2f - 2.dp.toPx(),
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        Box(
            modifier = Modifier
                .size(158.dp)
                .clip(CircleShape)
                .drawBehind {
                    drawCircle(fillColor)
                    drawCircle(innerStrokeColor, style = strokeStyle)
                }
                .graphicsLayer(scaleX = pressScale, scaleY = pressScale)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = interactionEnabled,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(actionIcon),
                contentDescription = actionDescription,
                tint = contentColor,
                modifier = Modifier.size(if (isRecording) 42.dp else 48.dp),
            )
        }
    }
}

@Composable
private fun ErrorDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.close),
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.error)) },
        text = {
            SelectionContainer {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? ClipboardManager ?: return@TextButton
                clipboard.setPrimaryClip(ClipData.newPlainText("error", message))
            }) {
                Text(stringResource(R.string.copy))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                }
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
            }) {
                Text(stringResource(R.string.share))
            }
        },
    )
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
                    text = stringResource(R.string.clear_buffer),
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
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = stringResource(R.string.clear_buffer),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.clear_buffer))
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
    currentBufferBytes: Long,
    onExport: (ExportRange) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { getRecorderPreferences(context) }

    var scopeMode by remember { mutableStateOf(getConfiguredCustomExportMode(context)) }
    var unitMode by remember { mutableStateOf(getConfiguredCustomExportUnit(context)) }

    var startTimeText by remember { mutableStateOf("0:00") }
    var endTimeText by remember {
        mutableStateOf(formatDurationInput(currentBufferSeconds.toInt()))
    }
    var startSizeText by remember { mutableStateOf(formatSizeInputMib(0L)) }
    var endSizeText by remember { mutableStateOf(formatSizeInputMib(currentBufferBytes)) }
    var pastTimeText by remember {
        val defaultSeconds = currentBufferSeconds.toInt().coerceAtLeast(1)
        val preferredSeconds = prefs.getInt(PrefKey.CUSTOM_EXPORT_PAST_SECONDS, defaultSeconds).coerceAtLeast(1)
        mutableStateOf(formatDurationInput(preferredSeconds))
    }
    var pastSizeText by remember {
        mutableStateOf(
            prefs.getString(PrefKey.CUSTOM_EXPORT_PAST_SIZE_MIB, formatSizeInputMib(currentBufferBytes))
                ?: formatSizeInputMib(currentBufferBytes),
        )
    }

    var startTimeError by remember { mutableStateOf<String?>(null) }
    var endTimeError by remember { mutableStateOf<String?>(null) }
    var startSizeError by remember { mutableStateOf<String?>(null) }
    var endSizeError by remember { mutableStateOf<String?>(null) }
    var pastTimeError by remember { mutableStateOf<String?>(null) }
    var pastSizeError by remember { mutableStateOf<String?>(null) }

    fun clampExportRange(startSeconds: Float, endSeconds: Float): ExportRange {
        val exportConfig = currentExportConfig(context, null)
        val maxDurationSeconds = exportDurationLimitSeconds(
            exportConfig.format, exportConfig.codec, exportConfig.sampleRate,
            exportConfig.channelCount, exportConfig.bitrateKbps,
            exportConfig.sampleFormat,
        ).toFloat().coerceAtLeast(1f)
        val boundedEnd = endSeconds.coerceAtLeast(startSeconds)
        val requestedDuration = boundedEnd - startSeconds
        if (requestedDuration <= maxDurationSeconds) {
            return ExportRange(startSeconds, boundedEnd, null)
        }
        return ExportRange(
            startSeconds = (boundedEnd - maxDurationSeconds).coerceAtLeast(0f),
            endSeconds = boundedEnd,
            warningDurationSeconds = maxDurationSeconds,
        )
    }

    val firstFieldFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { firstFieldFocusRequester.requestFocus() }

    fun submit() {
        val currentSeconds = currentBufferSeconds
        if (currentSeconds <= 0f) {
            Toast.makeText(context, R.string.nothing_to_export, Toast.LENGTH_SHORT).show()
            return
        }
        val rangeMode = scopeMode == CustomExportMode.RANGE
        val timeUnit = unitMode == CustomExportUnit.TIME

        if (rangeMode && timeUnit) {
            val startSec = parseDurationInput(startTimeText)?.toFloat()
            val endSec = parseDurationInput(endTimeText)?.toFloat()
            if (startSec == null || startSec < 0f) {
                startTimeError = context.getString(R.string.retention_time_invalid)
                return
            }
            startTimeError = null
            if (endSec == null || endSec <= 0f) {
                endTimeError = context.getString(R.string.retention_time_invalid)
                return
            }
            endTimeError = null
            if (startSec > currentSeconds) {
                startTimeError = context.getString(R.string.custom_export_range_invalid)
                return
            }
            startTimeError = null
            if (endSec <= startSec || endSec > currentSeconds) {
                endTimeError = context.getString(R.string.custom_export_range_invalid)
                return
            }
            endTimeError = null
            onExport(clampExportRange(startSec, endSec))
            return
        }

        if (rangeMode && !timeUnit) {
            val startBytes = parseSizeInputMib(startSizeText)
            val endBytes = parseSizeInputMib(endSizeText)
            if (startBytes == null || startBytes < 0L) {
                startSizeError = context.getString(R.string.custom_export_size_invalid)
                return
            }
            startSizeError = null
            if (endBytes == null || endBytes <= 0L) {
                endSizeError = context.getString(R.string.custom_export_size_invalid)
                return
            }
            endSizeError = null
            if (startBytes > currentBufferBytes) {
                startSizeError = context.getString(R.string.custom_export_range_invalid)
                return
            }
            startSizeError = null
            if (endBytes <= startBytes || endBytes > currentBufferBytes) {
                endSizeError = context.getString(R.string.custom_export_range_invalid)
                return
            }
            endSizeError = null
            onExport(
                clampExportRange(
                    sizeBytesToExportSeconds(startBytes, context),
                    sizeBytesToExportSeconds(endBytes, context),
                ),
            )
            return
        }

        if (!rangeMode && timeUnit) {
            val pastSec = parseDurationInput(pastTimeText)?.toFloat()
            if (pastSec == null || pastSec <= 0f) {
                pastTimeError = context.getString(R.string.retention_time_invalid)
                return
            }
            pastTimeError = null
            prefs.edit().putInt(PrefKey.CUSTOM_EXPORT_PAST_SECONDS, pastSec.roundToInt()).apply()
            onExport(clampExportRange((currentSeconds - pastSec).coerceAtLeast(0f), currentSeconds))
            return
        }

        val pastBytes = parseSizeInputMib(pastSizeText)
        if (pastBytes == null || pastBytes <= 0L) {
            pastSizeError = context.getString(R.string.custom_export_size_invalid)
            return
        }
        pastSizeError = null
        if (pastBytes > currentBufferBytes) {
            pastSizeError = context.getString(R.string.custom_export_range_invalid)
            return
        }
        pastSizeError = null
        prefs.edit().putString(PrefKey.CUSTOM_EXPORT_PAST_SIZE_MIB, pastSizeText).apply()
        onExport(
            clampExportRange(
                (currentSeconds - sizeBytesToExportSeconds(pastBytes, context)).coerceAtLeast(0f),
                currentSeconds,
            ),
        )
    }

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
                    text = stringResource(R.string.export),
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
            BoxWithConstraints {
                val pad = if (maxWidth > 360.dp) 16.dp else 0.dp
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = pad)
                ) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SegmentedButton(
                        selected = scopeMode == CustomExportMode.PAST,
                        onClick = {
                            scopeMode = CustomExportMode.PAST
                            setConfiguredCustomExportMode(context, CustomExportMode.PAST)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.custom_export_mode_past)) }
                    SegmentedButton(
                        selected = scopeMode == CustomExportMode.RANGE,
                        onClick = {
                            scopeMode = CustomExportMode.RANGE
                            setConfiguredCustomExportMode(context, CustomExportMode.RANGE)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.custom_export_mode_range)) }
                }

                Spacer(Modifier.height(8.dp))

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SegmentedButton(
                        selected = unitMode == CustomExportUnit.TIME,
                        onClick = {
                            unitMode = CustomExportUnit.TIME
                            setConfiguredCustomExportUnit(context, CustomExportUnit.TIME)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.retention_time_label)) }
                    SegmentedButton(
                        selected = unitMode == CustomExportUnit.SIZE,
                        onClick = {
                            unitMode = CustomExportUnit.SIZE
                            setConfiguredCustomExportUnit(context, CustomExportUnit.SIZE)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.custom_export_unit_size)) }
                }

                Spacer(Modifier.height(16.dp))

                when {
                    scopeMode == CustomExportMode.PAST && unitMode == CustomExportUnit.TIME -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = pastTimeText,
                                onValueChange = { pastTimeText = it; pastTimeError = null },
                                label = { Text(stringResource(R.string.custom_export_past_label)) },
                                isError = pastTimeError != null,
                                supportingText = pastTimeError?.let { { Text(it) } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.weight(1f).focusRequester(firstFieldFocusRequester),
                            )
                            IconButton(onClick = { submit() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = stringResource(R.string.export),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    scopeMode == CustomExportMode.PAST && unitMode == CustomExportUnit.SIZE -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = pastSizeText,
                                onValueChange = { pastSizeText = it; pastSizeError = null },
                                label = { Text(stringResource(R.string.custom_export_past_label)) },
                                isError = pastSizeError != null,
                                supportingText = pastSizeError?.let { { Text(it) } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.weight(1f).focusRequester(firstFieldFocusRequester),
                            )
                            IconButton(onClick = { submit() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = stringResource(R.string.export),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    scopeMode == CustomExportMode.RANGE && unitMode == CustomExportUnit.TIME -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = startTimeText,
                                onValueChange = { startTimeText = it; startTimeError = null },
                                label = { Text(stringResource(R.string.custom_export_start_label)) },
                                isError = startTimeError != null,
                                supportingText = startTimeError?.let { { Text(it) } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next,
                                ),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.weight(1f).focusRequester(firstFieldFocusRequester),
                            )
                            OutlinedTextField(
                                value = endTimeText,
                                onValueChange = { endTimeText = it; endTimeError = null },
                                label = { Text(stringResource(R.string.custom_export_end_label)) },
                                isError = endTimeError != null,
                                supportingText = endTimeError?.let { { Text(it) } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { submit() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = stringResource(R.string.export),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    scopeMode == CustomExportMode.RANGE && unitMode == CustomExportUnit.SIZE -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = startSizeText,
                                onValueChange = { startSizeText = it; startSizeError = null },
                                label = { Text(stringResource(R.string.custom_export_start_label)) },
                                isError = startSizeError != null,
                                supportingText = startSizeError?.let { { Text(it) } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next,
                                ),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.weight(1f).focusRequester(firstFieldFocusRequester),
                            )
                            OutlinedTextField(
                                value = endSizeText,
                                onValueChange = { endSizeText = it; endSizeError = null },
                                label = { Text(stringResource(R.string.custom_export_end_label)) },
                                isError = endSizeError != null,
                                supportingText = endSizeError?.let { { Text(it) } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { submit() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = stringResource(R.string.export),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                }
            }
        },
        confirmButton = {},
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

private fun currentBufferExportBytes(
    context: Context,
    recorder: ReverbService?,
    memorizedSeconds: Float,
): Long {
    val exportConfig = currentExportConfig(context, recorder)
    return estimateExportSizeBytes(
        exportConfig.format, exportConfig.codec, exportConfig.sampleRate,
        exportConfig.channelCount, memorizedSeconds.coerceAtLeast(0f).toLong(),
        exportConfig.bitrateKbps, exportConfig.sampleFormat,
    )
}

private fun sizeBytesToExportSeconds(sizeBytes: Long, context: Context): Float {
    val exportConfig = currentExportConfig(context, null)
    return estimateExportDurationSeconds(
        exportConfig.format, exportConfig.codec, exportConfig.sampleRate,
        exportConfig.channelCount, sizeBytes, exportConfig.bitrateKbps, exportConfig.sampleFormat,
    ).toFloat()
}

private fun parseSizeInputMib(value: String): Long? {
    val mib = value.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (!mib.isFinite() || mib < 0.0) return null
    if (mib >= Long.MAX_VALUE / MIB) return null
    return (mib * MIB).roundToLong()
}

private fun formatSizeInputMib(sizeBytes: Long): String {
    val mebibytes = sizeBytes.coerceAtLeast(0L) / MIB
    return String.format(Locale.US, "%.1f", mebibytes)
}
