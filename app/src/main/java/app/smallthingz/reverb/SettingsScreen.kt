package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.activity.BackEventCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToLong

private val BYTES_IN_MEGABYTE = 1024L * 1024L
private val retentionSizeFormatter =
    DecimalFormat(ReverbConfig.FORMAT_RETENTION_SIZE_MIB, DecimalFormatSymbols(Locale.US))
private val retentionTimeFormatter =
    DecimalFormat("0.###", DecimalFormatSymbols(Locale.US))
data class SettingsSnapshot(
    var themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    var retentionMode: RetentionMode = RetentionMode.TIME,
    var oneShotRetentionTime: Int = 0,
    var oneShotRetentionSizeBytes: Long = 0L,
    var loopingRetentionTime: Int = 0,
    var loopingRetentionSizeBytes: Long = 0L,
    var format: ExportFormat? = null,
    var codec: ExportCodec? = null,
    var sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
    var source: AudioSourceMode? = null,
    var channelMode: ChannelMode? = null,
    var route: InputRouteMode? = null,
    var sampleRate: Int = 0,
    var exportDirectoryUri: String? = null,
    var wakeLockEnabled: Boolean = false,
) {
    fun copyFrom(other: SettingsSnapshot) {
        themeMode = other.themeMode
        retentionMode = other.retentionMode
        oneShotRetentionTime = other.oneShotRetentionTime
        oneShotRetentionSizeBytes = other.oneShotRetentionSizeBytes
        loopingRetentionTime = other.loopingRetentionTime
        loopingRetentionSizeBytes = other.loopingRetentionSizeBytes
        format = other.format
        codec = other.codec
        sampleFormat = other.sampleFormat
        source = other.source
        channelMode = other.channelMode
        route = other.route
        sampleRate = other.sampleRate
        exportDirectoryUri = other.exportDirectoryUri
        wakeLockEnabled = other.wakeLockEnabled
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    onBack: () -> Unit = {},
    onThemeChanged: (AppThemeMode) -> Unit = {},
    focusRetentionBuffer: ReverbService.BufferSlot? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val chrome = appChrome()

    var originalSnapshot by remember { mutableStateOf(SettingsSnapshot()) }
    var currentSnapshot by remember { mutableStateOf(SettingsSnapshot()) }
    var hasUnsavedChanges by remember { mutableStateOf(false) }

    var service by remember { mutableStateOf<ReverbService?>(null) }

    val moveAvailabilityGeneration = remember { intArrayOf(0) }

    // Selected values
    var selectedTheme by remember { mutableStateOf(AppThemeMode.SYSTEM) }
    var selectedFormat by remember { mutableStateOf(supportedFormats().first()) }
    var selectedCodec by remember { mutableStateOf(supportedCodecs(supportedFormats().first()).first()) }
    var selectedSampleFormat by remember { mutableStateOf(PcmSampleFormat.PCM_16) }
    var selectedSource by remember { mutableStateOf(AudioSourceMode.availableModes().first()) }
    var selectedChannelMode by remember { mutableStateOf(ChannelMode.MONO) }
    var selectedRoute by remember { mutableStateOf(InputRouteMode.AUTO) }
    var selectedSampleRate by remember { mutableIntStateOf(48_000) }

    var activeRetentionMode by remember { mutableStateOf(RetentionMode.TIME) }
    var oneShotRetentionTimeSecondsValue by remember { mutableIntStateOf(0) }
    var oneShotRetentionSizeBytesValue by remember { mutableLongStateOf(0L) }
    var loopingRetentionTimeSecondsValue by remember { mutableIntStateOf(0) }
    var loopingRetentionSizeBytesValue by remember { mutableLongStateOf(0L) }
    var selectedExportTreeUri by remember { mutableStateOf<Uri?>(null) }

    // Available options lists (recomputed on changes)
    var availableFormats by remember { mutableStateOf(supportedFormats()) }
    var availableCodecs by remember { mutableStateOf(supportedCodecs(supportedFormats().first())) }
    var availableSourceModes by remember { mutableStateOf(AudioSourceMode.availableModes()) }
    var availableChannelModes by remember { mutableStateOf(ChannelMode.entries.toList()) }
    var availableRouteModes by remember { mutableStateOf(InputRouteMode.entries.toList()) }
    var availableSampleRates by remember { mutableStateOf(standardSampleRates()) }

    // Text inputs
    var oneShotRetentionTimeText by remember { mutableStateOf("") }
    var oneShotRetentionSizeText by remember { mutableStateOf("") }
    var loopingRetentionTimeText by remember { mutableStateOf("") }
    var loopingRetentionSizeText by remember { mutableStateOf("") }

    // Errors
    var oneShotRetentionTimeError by remember { mutableStateOf<String?>(null) }
    var oneShotRetentionSizeError by remember { mutableStateOf<String?>(null) }
    var loopingRetentionTimeError by remember { mutableStateOf<String?>(null) }
    var loopingRetentionSizeError by remember { mutableStateOf<String?>(null) }
    var oneShotComputedSizeMb by remember { mutableDoubleStateOf(0.0) }
    var loopingComputedSizeMb by remember { mutableDoubleStateOf(0.0) }
    var oneShotComputedTimeSeconds by remember { mutableLongStateOf(0L) }
    var loopingComputedTimeSeconds by remember { mutableLongStateOf(0L) }
    var exportPathText by remember { mutableStateOf("") }
    var canMove by remember { mutableStateOf(false) }
    var batteryOptimizationRestricted by remember { mutableStateOf(!isIgnoringBatteryOptimizations(context)) }

    // Pre-computed label lists
    var formatLabels by remember { mutableStateOf(availableFormats.map { resources.getString(it.labelRes) }) }
    var sampleFormatLabels by remember {
        mutableStateOf(PcmSampleFormat.entries.map { resources.getString(it.labelRes) })
    }
    var sourceLabels by remember { mutableStateOf(availableSourceModes.map { resources.getString(it.labelRes) }) }
    var channelModeLabels by remember { mutableStateOf(ChannelMode.entries.map { resources.getString(it.labelRes) }) }
    var routeLabels by remember { mutableStateOf(InputRouteMode.entries.map { resources.getString(it.labelRes) }) }
    var sampleRateLabels by remember { mutableStateOf(emptyList<String>()) }

    // Selection labels
    var selectedFormatLabel by remember { mutableStateOf(resources.getString(supportedFormats().first().labelRes)) }
    var selectedSampleFormatLabel by remember { mutableStateOf(resources.getString(PcmSampleFormat.PCM_16.labelRes)) }
    var selectedSourceLabel by remember {
        mutableStateOf(resources.getString(AudioSourceMode.availableModes().first().labelRes))
    }
    var selectedChannelModeLabel by remember { mutableStateOf(resources.getString(ChannelMode.MONO.labelRes)) }
    var selectedRouteLabel by remember { mutableStateOf(resources.getString(InputRouteMode.AUTO.labelRes)) }
    var selectedSampleRateLabel by remember { mutableStateOf(sampleRateLabel(48_000)) }

    fun refreshExportDirectoryUi() {
        exportPathText = describeOutputDirectory(context, selectedExportTreeUri)
    }

    fun refreshBatteryOptimizationUi() {
        batteryOptimizationRestricted = !isIgnoringBatteryOptimizations(context)
    }

    fun refreshMoveRecordingsAvailability() {
        val gen = ++moveAvailabilityGeneration[0]
        canMove = false
        scope.launch {
            val result = try {
                RecordingRepository.hasMovableKnownRecordings(
                    context,
                    getOutputDirectoryId(context, selectedExportTreeUri),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (gen == moveAvailabilityGeneration[0]) canMove = result
        }
    }

    fun refreshSampleRates(preferredRate: Int? = null) {
        val preferred = preferredRate?.takeIf { it > 0 } ?: selectedSampleRate
        availableSampleRates = orderSampleRatesByPreference(
            buildList {
                if (preferred > 0) add(preferred)
                addAll(standardSampleRates())
            }.distinct(),
            preferred,
        )
        val rate = preferred.takeIf { it in availableSampleRates } ?: availableSampleRates.first()
        selectedSampleRate = rate
        sampleRateLabels = availableSampleRates.map { sampleRateLabel(it) }
        selectedSampleRateLabel = sampleRateLabel(rate)
    }

    fun refreshChannelModes(
        preferredChannelMode: ChannelMode? = null,
        preferredRate: Int? = null,
    ) {
        availableChannelModes = ChannelMode.entries
        val cm = preferredChannelMode?.takeIf { it in availableChannelModes } ?: availableChannelModes.first()
        selectedChannelMode = cm
        channelModeLabels = availableChannelModes.map { resources.getString(it.labelRes) }
        selectedChannelModeLabel = resources.getString(cm.labelRes)
        refreshSampleRates(preferredRate)
    }

    fun refreshSourceModes(
        preferredSource: AudioSourceMode? = null,
        preferredChannelMode: ChannelMode? = null,
        preferredRate: Int? = null,
    ) {
        availableSourceModes = AudioSourceMode.availableModes()
        val s = preferredSource?.takeIf { it in availableSourceModes } ?: availableSourceModes.first()
        selectedSource = s
        sourceLabels = availableSourceModes.map { resources.getString(it.labelRes) }
        selectedSourceLabel = resources.getString(s.labelRes)
        refreshChannelModes(preferredChannelMode, preferredRate)
    }

    fun refreshCodecOptions(
        preferredCodec: ExportCodec? = null,
        preferredSource: AudioSourceMode? = null,
        preferredChannelMode: ChannelMode? = null,
        preferredRate: Int? = null,
    ) {
        availableCodecs = supportedCodecs(selectedFormat)
        val codec = preferredCodec?.takeIf { it in availableCodecs } ?: availableCodecs.first()
        selectedCodec = codec
        refreshSourceModes(preferredSource, preferredChannelMode, preferredRate)
    }

    fun saveCurrentToSnapshot(snapshot: SettingsSnapshot) {
        snapshot.themeMode = selectedTheme
        snapshot.retentionMode = activeRetentionMode
        snapshot.oneShotRetentionTime = oneShotRetentionTimeSecondsValue
        snapshot.oneShotRetentionSizeBytes = oneShotRetentionSizeBytesValue
        snapshot.loopingRetentionTime = loopingRetentionTimeSecondsValue
        snapshot.loopingRetentionSizeBytes = loopingRetentionSizeBytesValue
        snapshot.format = selectedFormat
        snapshot.codec = selectedCodec
        snapshot.sampleFormat = selectedSampleFormat
        snapshot.source = selectedSource
        snapshot.channelMode = selectedChannelMode
        snapshot.route = selectedRoute
        snapshot.sampleRate = selectedSampleRate
        snapshot.exportDirectoryUri = selectedExportTreeUri?.toString()
        snapshot.wakeLockEnabled = currentSnapshot.wakeLockEnabled
    }

    fun pushUndoState() {
        val invalidRetentionInput = when (activeRetentionMode) {
            RetentionMode.TIME ->
                parseRetentionTimeSeconds(oneShotRetentionTimeText) == null ||
                    parseRetentionTimeSeconds(loopingRetentionTimeText) == null
            RetentionMode.SIZE ->
                parseRetentionSizeMib(oneShotRetentionSizeText)?.takeIf { it >= 0.0 } == null ||
                    parseRetentionSizeMib(loopingRetentionSizeText)?.takeIf { it >= 0.0 } == null
        }
        hasUnsavedChanges = originalSnapshot != currentSnapshot || invalidRetentionInput
    }

    fun refreshRetentionFields(preserveActiveInputs: Boolean = false) {
        val sr = selectedSampleRate
        val chCount = selectedChannelMode.channelCount
        if (sr > 0) {
            oneShotComputedSizeMb = bytesToMegabytes(
                bytesForRetentionSeconds(
                    oneShotRetentionTimeSecondsValue.toLong(), sr, chCount, selectedSampleFormat,
                ),
            )
            loopingComputedSizeMb = bytesToMegabytes(
                bytesForRetentionSeconds(
                    loopingRetentionTimeSecondsValue.toLong(), sr, chCount, selectedSampleFormat,
                ),
            )
            oneShotComputedTimeSeconds = retentionSecondsForBytes(
                oneShotRetentionSizeBytesValue, sr, chCount, selectedSampleFormat,
            )
            loopingComputedTimeSeconds = retentionSecondsForBytes(
                loopingRetentionSizeBytesValue, sr, chCount, selectedSampleFormat,
            )
        } else {
            oneShotComputedSizeMb = 0.0
            loopingComputedSizeMb = 0.0
            oneShotComputedTimeSeconds = 0L
            loopingComputedTimeSeconds = 0L
        }

        if (!preserveActiveInputs) {
            // The editable modes are independent. These are presentation strings only;
            // never derive one stored retention value from the other mode.
            oneShotRetentionTimeText = formatRetentionTimeInput(oneShotRetentionTimeSecondsValue.toLong())
            loopingRetentionTimeText = formatRetentionTimeInput(loopingRetentionTimeSecondsValue.toLong())
            oneShotRetentionSizeText = formatRetentionSizeBytes(oneShotRetentionSizeBytesValue)
            loopingRetentionSizeText = formatRetentionSizeBytes(loopingRetentionSizeBytesValue)
        }
    }

    fun activateRetentionMode(mode: RetentionMode) {
        if (activeRetentionMode == mode) return
        // onValueChange keeps the active backing value current. Switching modes only
        // changes presentation; it must never reparse the rounded display string.
        activeRetentionMode = mode
        refreshRetentionFields(preserveActiveInputs = false)
        saveCurrentToSnapshot(currentSnapshot)
        pushUndoState()
    }

    fun restorePreviousSettings() {
        if (!hasUnsavedChanges) return
        val prev = originalSnapshot
        val abandonedExportTreeUri = selectedExportTreeUri
        oneShotRetentionTimeError = null
        oneShotRetentionSizeError = null
        loopingRetentionTimeError = null
        loopingRetentionSizeError = null

        activeRetentionMode = prev.retentionMode
        oneShotRetentionTimeSecondsValue = prev.oneShotRetentionTime
        oneShotRetentionSizeBytesValue = prev.oneShotRetentionSizeBytes
        loopingRetentionTimeSecondsValue = prev.loopingRetentionTime
        loopingRetentionSizeBytesValue = prev.loopingRetentionSizeBytes
        selectedExportTreeUri = prev.exportDirectoryUri?.let(Uri::parse)
        if (abandonedExportTreeUri != selectedExportTreeUri) {
            RecordingRepository.releasePendingDirectoryAndCleanup(context, abandonedExportTreeUri)
        }

        selectedTheme = prev.themeMode
        onThemeChanged(prev.themeMode)
        selectedFormat = prev.format ?: availableFormats.first()
        selectedFormatLabel = resources.getString((prev.format ?: availableFormats.first()).labelRes)
        selectedCodec = prev.codec ?: availableCodecs.first()
        selectedRoute = prev.route ?: availableRouteModes.first()
        selectedRouteLabel = resources.getString((prev.route ?: availableRouteModes.first()).labelRes)
        selectedSampleFormat = prev.sampleFormat
        selectedSampleFormatLabel = resources.getString(selectedSampleFormat.labelRes)
        selectedSource = prev.source ?: availableSourceModes.first()
        selectedSourceLabel = resources.getString(selectedSource.labelRes)
        selectedChannelMode = prev.channelMode ?: ChannelMode.MONO
        selectedChannelModeLabel = resources.getString(selectedChannelMode.labelRes)
        selectedSampleRate = prev.sampleRate.takeIf { it > 0 } ?: selectedSampleRate
        selectedSampleRateLabel = sampleRateLabel(selectedSampleRate)

        refreshCodecOptions(
            preferredCodec = prev.codec,
            preferredSource = prev.source,
            preferredChannelMode = prev.channelMode,
            preferredRate = prev.sampleRate,
        )

        refreshRetentionFields()
        refreshExportDirectoryUi()
        refreshMoveRecordingsAvailability()
        refreshBatteryOptimizationUi()

        currentSnapshot = prev.copy()
        hasUnsavedChanges = false
    }

    fun persistSettings(): Boolean {
        oneShotRetentionTimeError = null
        oneShotRetentionSizeError = null
        loopingRetentionTimeError = null
        loopingRetentionSizeError = null

        val format = selectedFormat
        val codec = selectedCodec
        val sampleFormat = selectedSampleFormat
        val channelMode = selectedChannelMode
        val route = selectedRoute
        val source = selectedSource
        val sampleRate = selectedSampleRate

        val oneShotRetentionTime = if (activeRetentionMode == RetentionMode.TIME) {
            parseRetentionTimeSeconds(oneShotRetentionTimeText.trim())
        } else {
            oneShotRetentionTimeSecondsValue
        }
        if (oneShotRetentionTime == null || oneShotRetentionTime < 0) {
            oneShotRetentionTimeError = resources.getString(R.string.retention_time_invalid)
            return false
        }

        val loopingRetentionTime = if (activeRetentionMode == RetentionMode.TIME) {
            parseRetentionTimeSeconds(loopingRetentionTimeText.trim())
        } else {
            loopingRetentionTimeSecondsValue
        }
        if (loopingRetentionTime == null || loopingRetentionTime < 0) {
            loopingRetentionTimeError = resources.getString(R.string.retention_time_invalid)
            return false
        }

        if (activeRetentionMode == RetentionMode.SIZE) {
            val oneShotSizeInput = parseRetentionSizeMib(oneShotRetentionSizeText.trim())
            if (oneShotSizeInput == null || oneShotSizeInput < 0.0) {
                oneShotRetentionSizeError = resources.getString(R.string.custom_memory_size_invalid)
                return false
            }
            val loopingSizeInput = parseRetentionSizeMib(loopingRetentionSizeText.trim())
            if (loopingSizeInput == null || loopingSizeInput < 0.0) {
                loopingRetentionSizeError = resources.getString(R.string.custom_memory_size_invalid)
                return false
            }
            // Validity is checked against the visible rounded text. The exact backing byte
            // counts were already updated by onValueChange and are intentionally retained.
        }

        if (sampleRate <= 0 || !isCodecSupported(format, codec, sampleRate, channelMode)) return false

        val requestedOneShotSizeBytes = oneShotRetentionSizeBytesValue
        val requestedLoopingSizeBytes = loopingRetentionSizeBytesValue
        if (activeRetentionMode == RetentionMode.SIZE) {
            val frameBytes = channelMode.channelCount.toLong() * sampleFormat.bytesPerSample
            if (requestedOneShotSizeBytes != 0L && requestedOneShotSizeBytes < frameBytes) {
                oneShotRetentionSizeError = resources.getString(R.string.custom_memory_size_invalid)
                return false
            }
            if (requestedLoopingSizeBytes != 0L && requestedLoopingSizeBytes < frameBytes) {
                loopingRetentionSizeError = resources.getString(R.string.custom_memory_size_invalid)
                return false
            }
        }

        val oneShotEnabled = when (activeRetentionMode) {
            RetentionMode.TIME -> oneShotRetentionTime > 0
            RetentionMode.SIZE -> requestedOneShotSizeBytes > 0L
        }
        val loopingEnabled = when (activeRetentionMode) {
            RetentionMode.TIME -> loopingRetentionTime > 0
            RetentionMode.SIZE -> requestedLoopingSizeBytes > 0L
        }
        if (!oneShotEnabled && !loopingEnabled) {
            val message = resources.getString(R.string.buffer_required)
            if (activeRetentionMode == RetentionMode.TIME) {
                oneShotRetentionTimeError = message
                loopingRetentionTimeError = message
            } else {
                oneShotRetentionSizeError = message
                loopingRetentionSizeError = message
            }
            return false
        }

        oneShotRetentionTimeSecondsValue = oneShotRetentionTime
        loopingRetentionTimeSecondsValue = loopingRetentionTime

        val settingsEditor = getRecorderPreferences(context).edit()
            .putInt(PrefKey.RETENTION_MODE, activeRetentionMode.ordinal)
            .putLong(PrefKey.ONE_SHOT_RETENTION_SECONDS, oneShotRetentionTime.toLong())
            .putLong(PrefKey.ONE_SHOT_AUDIO_MEMORY_SIZE, requestedOneShotSizeBytes)
            .putLong(PrefKey.RETENTION_SECONDS, loopingRetentionTime.toLong())
            .putLong(PrefKey.AUDIO_MEMORY_SIZE, requestedLoopingSizeBytes)
            .putString(PrefKey.OUTPUT_FORMAT, format.prefValue)
            .putString(PrefKey.OUTPUT_CODEC, codec.prefValue)
            .putString(PrefKey.PCM_SAMPLE_FORMAT, sampleFormat.prefValue)
            .putInt(PrefKey.AUDIO_SOURCE, source.sourceValue)
            .putString(PrefKey.CHANNEL_MODE, channelMode.prefValue)
            .putString(PrefKey.INPUT_ROUTE, route.prefValue)
            .putInt(PrefKey.SAMPLE_RATE, sampleRate)
            .putBoolean(PrefKey.WAKE_LOCK_ENABLED, currentSnapshot.wakeLockEnabled)
            .putString(PrefKey.THEME_MODE, selectedTheme.prefValue)
        if (selectedExportTreeUri != null) {
            settingsEditor.putString(PrefKey.EXPORT_DIRECTORY_URI, selectedExportTreeUri.toString())
        } else {
            settingsEditor.remove(PrefKey.EXPORT_DIRECTORY_URI)
        }
        if (!settingsEditor.commit()) {
            val previous = originalSnapshot
            getRecorderPreferences(context).edit()
                .putInt(PrefKey.RETENTION_MODE, previous.retentionMode.ordinal)
                .putLong(PrefKey.ONE_SHOT_RETENTION_SECONDS, previous.oneShotRetentionTime.toLong())
                .putLong(PrefKey.ONE_SHOT_AUDIO_MEMORY_SIZE, previous.oneShotRetentionSizeBytes)
                .putLong(PrefKey.RETENTION_SECONDS, previous.loopingRetentionTime.toLong())
                .putLong(PrefKey.AUDIO_MEMORY_SIZE, previous.loopingRetentionSizeBytes)
                .putString(PrefKey.OUTPUT_FORMAT, (previous.format ?: ExportFormat.WAV).prefValue)
                .putString(PrefKey.OUTPUT_CODEC, (previous.codec ?: ExportCodec.PCM_16).prefValue)
                .putString(PrefKey.PCM_SAMPLE_FORMAT, previous.sampleFormat.prefValue)
                .putInt(PrefKey.AUDIO_SOURCE, previous.source?.sourceValue ?: AudioSourceMode.defaultMode().sourceValue)
                .putString(PrefKey.CHANNEL_MODE, (previous.channelMode ?: ChannelMode.MONO).prefValue)
                .putString(PrefKey.INPUT_ROUTE, (previous.route ?: InputRouteMode.AUTO).prefValue)
                .putInt(PrefKey.SAMPLE_RATE, previous.sampleRate)
                .putBoolean(PrefKey.WAKE_LOCK_ENABLED, previous.wakeLockEnabled)
                .putString(PrefKey.THEME_MODE, previous.themeMode.prefValue)
                .apply {
                    val previousExportDirectoryUri = previous.exportDirectoryUri
                    if (previousExportDirectoryUri == null) remove(PrefKey.EXPORT_DIRECTORY_URI)
                    else putString(PrefKey.EXPORT_DIRECTORY_URI, previousExportDirectoryUri)
                }
                .apply()
            AppFeedbackCenter.post(resources.getString(R.string.recorder_state_persist_failed), FeedbackTone.ERROR)
            return false
        }
        RecordingRepository.releasePendingDirectoryAndCleanup(context, selectedExportTreeUri)
        onThemeChanged(selectedTheme)

        val currentService = service
        if (currentService != null) {
            currentService.applyUpdatedPreferences()
        } else if (getRecorderPreferences(context).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)) {
            runCatching {
                context.startService(
                    Intent(context, ReverbService::class.java).setAction(ReverbService.ACTION_APPLY_SETTINGS),
                )
            }.onFailure { error ->
                AppFeedbackCenter.post(
                    resources.getString(R.string.settings_apply_failed),
                    FeedbackTone.ERROR,
                )
            }
        } else {
            RecordingQuickTiles.requestRefresh(context)
        }
        // Re-render from the precise backing values after commit. This keeps the large
        // fields intentionally rounded without feeding that rounding back into storage.
        refreshRetentionFields(preserveActiveInputs = false)
        saveCurrentToSnapshot(currentSnapshot)
        originalSnapshot.copyFrom(currentSnapshot)
        hasUnsavedChanges = false
        return true
    }

    fun bindUiFromPreferences() {
        val configuredThemeMode = getConfiguredThemeMode(context)
        val configuredMode = getConfiguredRetentionMode(context)
        val configuredOneShotTime = getConfiguredOneShotRetentionSeconds(context)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val configuredLoopingTime = getConfiguredRetentionSeconds(context)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val storedOneShotSizeBytes = getConfiguredOneShotRetentionSizeBytes(context)
        val storedLoopingSizeBytes = getConfiguredRetentionSizeBytes(context)
        val configuredFormat = getConfiguredOutputFormat(context)
        val configuredCodec = getConfiguredOutputCodec(context)
        val configuredSampleFormatVal = getConfiguredPcmSampleFormat(context)
        val configuredRouteVal = getConfiguredInputRouteMode(context)
        val configuredSourceVal = getConfiguredAudioSourceMode(context)
        val configuredChannelModeVal = getConfiguredChannelMode(context)
        val configuredRateVal = getConfiguredSampleRate(context)
        val configuredExportTreeUriVal = getConfiguredExportTreeUri(context)

        activeRetentionMode = configuredMode
        oneShotRetentionTimeSecondsValue = configuredOneShotTime
        oneShotRetentionSizeBytesValue = storedOneShotSizeBytes
        loopingRetentionTimeSecondsValue = configuredLoopingTime
        loopingRetentionSizeBytesValue = storedLoopingSizeBytes
        selectedExportTreeUri = configuredExportTreeUriVal

        selectedTheme = configuredThemeMode

        availableFormats = supportedFormats()
        selectedFormat = configuredFormat.takeIf { it in availableFormats } ?: availableFormats.first()
        formatLabels = availableFormats.map { resources.getString(it.labelRes) }
        selectedFormatLabel = resources.getString(selectedFormat.labelRes)

        availableRouteModes = InputRouteMode.entries
        selectedRoute = configuredRouteVal
        routeLabels = availableRouteModes.map { resources.getString(it.labelRes) }
        selectedRouteLabel = resources.getString(configuredRouteVal.labelRes)

        selectedSampleFormat = configuredSampleFormatVal
        sampleFormatLabels = PcmSampleFormat.entries.map { resources.getString(it.labelRes) }
        selectedSampleFormatLabel = resources.getString(configuredSampleFormatVal.labelRes)

        refreshCodecOptions(
            preferredCodec = configuredCodec,
            preferredSource = configuredSourceVal,
            preferredChannelMode = configuredChannelModeVal,
            preferredRate = configuredRateVal,
        )
        refreshRetentionFields()
        refreshExportDirectoryUi()
        refreshMoveRecordingsAvailability()
        refreshBatteryOptimizationUi()

        currentSnapshot = currentSnapshot.copy(wakeLockEnabled = isWakeLockEnabled(context))
        saveCurrentToSnapshot(currentSnapshot)
        originalSnapshot.copyFrom(currentSnapshot)
        hasUnsavedChanges = false
    }

    val exportDirectoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        RecordingRepository.retainPendingDirectory(treeUri)
        val permissionTaken = runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (!permissionTaken) {
            RecordingRepository.releasePendingDirectoryAndCleanup(context, treeUri)
            AppFeedbackCenter.post(resources.getString(R.string.cant_access_folder), FeedbackTone.ERROR)
            return@rememberLauncherForActivityResult
        }
        val previousTreeUri = selectedExportTreeUri
        selectedExportTreeUri = treeUri
        if (previousTreeUri != treeUri) {
            RecordingRepository.releasePendingDirectoryAndCleanup(context, previousTreeUri)
        }
        exportPathText = describeOutputDirectory(context, treeUri)
        saveCurrentToSnapshot(currentSnapshot)
        pushUndoState()
        refreshMoveRecordingsAvailability()
    }

    val currentExportTreeUri by rememberUpdatedState(selectedExportTreeUri)

    val connection = remember {
        object : android.content.ServiceConnection {
            override fun onServiceConnected(className: ComponentName, binder: IBinder) {
                val typedBinder = binder as? ReverbService.BackgroundRecorderBinder
                    ?: run {
                        service = null
                        return
                    }
                service = typedBinder.service
            }
            override fun onServiceDisconnected(arg0: ComponentName) {
                service = null
            }
        }
    }

    DisposableEffect(Unit) {
        val intent = Intent(context, ReverbService::class.java)
        val bound = context.bindService(intent, connection, 0)
        onDispose {
            if (bound) {
                context.unbindService(connection)
            }
            RecordingRepository.releasePendingDirectoryAndCleanup(context, currentExportTreeUri)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshBatteryOptimizationUi()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun reviewBatteryOptimization() {
        if (!openBatteryOptimizationReview(context)) {
            AppFeedbackCenter.post(resources.getString(R.string.no_app_available), FeedbackTone.ERROR)
        }
    }

    fun moveExistingRecordings() {
        if (!persistSettings()) return
        canMove = false
        scope.launch {
            val result = try {
                RecordingRepository.moveAllToConfiguredDirectory(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AppFeedbackCenter.post(resources.getString(R.string.move_recordings_failed), FeedbackTone.ERROR)
                refreshMoveRecordingsAvailability()
                return@launch
            }
            val message = when {
                result.moved == 0 && result.removedMissing == 0 -> resources.getString(R.string.move_recordings_none)
                result.removedMissing > 0 -> {
                    val movedMessage = resources.getQuantityString(
                        R.plurals.move_recordings_done, result.moved, result.moved,
                    )
                    val removedMessage = resources.getQuantityString(
                        R.plurals.move_recordings_removed_missing,
                        result.removedMissing, result.removedMissing,
                    )
                    "$movedMessage $removedMessage"
                }
                else -> resources.getQuantityString(R.plurals.move_recordings_done, result.moved, result.moved)
            }
            refreshMoveRecordingsAvailability()
            AppFeedbackCenter.post(message, FeedbackTone.SUCCESS)
        }
    }

    fun releaseInputFocus() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    LaunchedEffect(active) {
        if (!active) releaseInputFocus()
    }
    LaunchedEffect(Unit) { bindUiFromPreferences() }
    LaunchedEffect(focusRetentionBuffer, batteryOptimizationRestricted) {
        if (focusRetentionBuffer != null) {
            listState.scrollToItem(if (batteryOptimizationRestricted) 2 else 1)
        }
    }

    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var predictiveBackEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_NONE) }
    var predictiveBackCloses by remember { mutableStateOf(false) }
    PredictiveBackHandler(enabled = active) { progress ->
        predictiveBackCloses = !hasUnsavedChanges
        try {
            progress.collect { event ->
                predictiveBackProgress = event.progress.coerceIn(0f, 1f)
                predictiveBackEdge = event.swipeEdge
            }
            if (predictiveBackCloses) {
                releaseInputFocus()
                onBack()
            } else {
                restorePreviousSettings()
            }
        } finally {
            predictiveBackProgress = 0f
            predictiveBackEdge = BackEventCompat.EDGE_NONE
            predictiveBackCloses = false
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                val progress = if (predictiveBackCloses) {
                    predictiveBackProgress.coerceIn(0f, 1f)
                } else {
                    0f
                }
                val direction = if (predictiveBackEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                translationX = direction * size.width * 0.12f * progress
                val scale = 1f - 0.035f * progress
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(
                    pivotFractionX = if (direction < 0f) 1f else 0f,
                    pivotFractionY = 0.5f,
                )
            },
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                title = {
                    Text(stringResource(R.string.settings_title))
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasUnsavedChanges) {
                            restorePreviousSettings()
                        } else {
                            releaseInputFocus()
                            onBack()
                        }
                    }) {
                        Icon(
                            imageVector = if (hasUnsavedChanges) AppIcons.undo else AppIcons.back,
                            contentDescription = stringResource(
                                if (hasUnsavedChanges) R.string.undo else R.string.onboarding_back,
                            ),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!hasUnsavedChanges) return@IconButton
                            if (persistSettings()) {
                                releaseInputFocus()
                                onBack()
                            }
                        },
                        enabled = hasUnsavedChanges,
                    ) {
                        Icon(
                            imageVector = AppIcons.check,
                            contentDescription = stringResource(R.string.done),
                            tint = if (hasUnsavedChanges) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (batteryOptimizationRestricted) {
                item(key = "background-reliability") {
                    BackgroundOptimizationWarning(
                        restricted = true,
                        onReview = ::reviewBatteryOptimization,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            item(key = "theme") {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.theme_title),
                            fontSize = 19.sp,
                            lineHeight = 26.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        ThemeSelector(
                            selectedTheme = selectedTheme,
                            onThemeSelected = { theme ->
                                if (theme != selectedTheme) {
                                    selectedTheme = theme
                                    onThemeChanged(theme)
                                    saveCurrentToSnapshot(currentSnapshot)
                                    pushUndoState()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            item(key = "retention") {
                RetentionSection(
                    activeMode = activeRetentionMode,
                    focusBuffer = focusRetentionBuffer,
                    oneShotTimeText = oneShotRetentionTimeText,
                    oneShotSizeText = oneShotRetentionSizeText,
                    loopingTimeText = loopingRetentionTimeText,
                    loopingSizeText = loopingRetentionSizeText,
                    oneShotTimeError = oneShotRetentionTimeError,
                    oneShotSizeError = oneShotRetentionSizeError,
                    loopingTimeError = loopingRetentionTimeError,
                    loopingSizeError = loopingRetentionSizeError,
                    oneShotComputedSizeMb = oneShotComputedSizeMb,
                    loopingComputedSizeMb = loopingComputedSizeMb,
                    oneShotComputedTimeSeconds = oneShotComputedTimeSeconds,
                    loopingComputedTimeSeconds = loopingComputedTimeSeconds,
                    onModeSelected = ::activateRetentionMode,
                    onOneShotTimeChange = { value ->
                        oneShotRetentionTimeText = value
                        oneShotRetentionTimeError = null
                        parseRetentionTimeSeconds(value)?.let { oneShotRetentionTimeSecondsValue = it }
                        refreshRetentionFields(preserveActiveInputs = true)
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                    onOneShotSizeChange = { value ->
                        oneShotRetentionSizeText = value
                        oneShotRetentionSizeError = null
                        parseRetentionSizeMib(value)?.takeIf { it >= 0.0 }
                            ?.let { oneShotRetentionSizeBytesValue = rawMegabytesToBytes(it) }
                        refreshRetentionFields(preserveActiveInputs = true)
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                    onLoopingTimeChange = { value ->
                        loopingRetentionTimeText = value
                        loopingRetentionTimeError = null
                        parseRetentionTimeSeconds(value)?.let { loopingRetentionTimeSecondsValue = it }
                        refreshRetentionFields(preserveActiveInputs = true)
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                    onLoopingSizeChange = { value ->
                        loopingRetentionSizeText = value
                        loopingRetentionSizeError = null
                        parseRetentionSizeMib(value)?.takeIf { it >= 0.0 }
                            ?.let { loopingRetentionSizeBytesValue = rawMegabytesToBytes(it) }
                        refreshRetentionFields(preserveActiveInputs = true)
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                )
                Spacer(Modifier.height(12.dp))
            }

            item(key = "recording") {
                Column {
            SectionTitle(stringResource(R.string.recording_settings_title))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (formatLabels.size > 1) {
                    SettingsDropdown(
                        active = active,
                        label = stringResource(R.string.format_label),
                        selectedValue = selectedFormatLabel,
                        options = formatLabels,
                        onOptionSelected = { label ->
                            selectedFormatLabel = label
                            selectedFormat = availableFormats.first { resources.getString(it.labelRes) == label }
                            refreshCodecOptions(
                                preferredCodec = selectedCodec,
                                preferredSource = selectedSource,
                                preferredChannelMode = selectedChannelMode,
                                preferredRate = selectedSampleRate,
                            )
                            refreshRetentionFields(preserveActiveInputs = true)
                            saveCurrentToSnapshot(currentSnapshot)
                            pushUndoState()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (channelModeLabels.size > 1) {
                    SettingsDropdown(
                        active = active,
                        label = stringResource(R.string.channel_mode_label),
                        selectedValue = selectedChannelModeLabel,
                        options = channelModeLabels,
                        onOptionSelected = { label ->
                            selectedChannelModeLabel = label
                            selectedChannelMode = availableChannelModes.first {
                                resources.getString(it.labelRes) == label
                            }
                            refreshSampleRates(selectedSampleRate)
                            refreshRetentionFields(preserveActiveInputs = true)
                            saveCurrentToSnapshot(currentSnapshot)
                            pushUndoState()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (sampleFormatLabels.size > 1) {
                    SettingsDropdown(
                        active = active,
                        label = stringResource(R.string.sample_format_label),
                        selectedValue = selectedSampleFormatLabel,
                        options = sampleFormatLabels,
                        onOptionSelected = { label ->
                            selectedSampleFormatLabel = label
                            selectedSampleFormat = PcmSampleFormat.entries.first {
                                resources.getString(it.labelRes) == label
                            }
                            refreshSourceModes(
                                preferredSource = selectedSource,
                                preferredChannelMode = selectedChannelMode,
                                preferredRate = selectedSampleRate,
                            )
                            refreshRetentionFields(preserveActiveInputs = true)
                            saveCurrentToSnapshot(currentSnapshot)
                            pushUndoState()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (sampleRateLabels.size > 1) {
                    SettingsDropdown(
                        active = active,
                        label = stringResource(R.string.sample_rate_label),
                        selectedValue = selectedSampleRateLabel,
                        options = sampleRateLabels,
                        onOptionSelected = { label ->
                            selectedSampleRateLabel = label
                            availableSampleRates.firstOrNull { sampleRateLabel(it) == label }
                                ?.let { selectedSampleRate = it }
                            refreshRetentionFields(preserveActiveInputs = true)
                            saveCurrentToSnapshot(currentSnapshot)
                            pushUndoState()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (sourceLabels.size > 1) {
                    SettingsDropdown(
                        active = active,
                        label = stringResource(R.string.audio_source_label),
                        selectedValue = selectedSourceLabel,
                        options = sourceLabels,
                        onOptionSelected = { label ->
                            selectedSourceLabel = label
                            selectedSource = availableSourceModes.first { resources.getString(it.labelRes) == label }
                            refreshChannelModes(selectedChannelMode, selectedSampleRate)
                            refreshRetentionFields(preserveActiveInputs = true)
                            saveCurrentToSnapshot(currentSnapshot)
                            pushUndoState()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (routeLabels.size > 1) {
                    SettingsDropdown(
                        active = active,
                        label = stringResource(R.string.input_route_label),
                        selectedValue = selectedRouteLabel,
                        options = routeLabels,
                        onOptionSelected = { label ->
                            selectedRouteLabel = label
                            selectedRoute = availableRouteModes.first { resources.getString(it.labelRes) == label }
                            refreshSourceModes(
                                preferredSource = selectedSource,
                                preferredChannelMode = selectedChannelMode,
                                preferredRate = selectedSampleRate,
                            )
                            refreshRetentionFields(preserveActiveInputs = true)
                            saveCurrentToSnapshot(currentSnapshot)
                            pushUndoState()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
                }
            }

            item(key = "storage") {
                Column {
            LaunchedEffect(selectedExportTreeUri) {
                refreshMoveRecordingsAvailability()
            }
            SectionTitle(stringResource(R.string.storage_settings_title))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(20.dp),
                color = chrome.field,
                border = BorderStroke(1.dp, chrome.border),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = exportPathText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = chrome.muted,
                            modifier = Modifier.weight(1f),
                        )
                        if (selectedExportTreeUri != null) {
                            IconButton(
                                onClick = {
                                    val previousTreeUri = selectedExportTreeUri
                                    selectedExportTreeUri = null
                                    RecordingRepository.releasePendingDirectoryAndCleanup(context, previousTreeUri)
                                    refreshExportDirectoryUi()
                                    refreshMoveRecordingsAvailability()
                                    saveCurrentToSnapshot(currentSnapshot)
                                    pushUndoState()
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(chrome.raised),
                            ) {
                                Icon(
                                    imageVector = AppIcons.reset,
                                    contentDescription = stringResource(R.string.default_folder),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(
                            onClick = { exportDirectoryLauncher.launch(selectedExportTreeUri) },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(chrome.raised),
                        ) {
                            Icon(
                                imageVector = AppIcons.folder,
                                contentDescription = stringResource(R.string.choose_folder),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(
                        onClick = { moveExistingRecordings() },
                        enabled = canMove,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(chrome.raised),
                    ) {
                        Text(stringResource(R.string.move_recordings))
                    }
                }
            }
                }
            }

            item(key = "wake-lock") {
                Column {
                    Spacer(Modifier.height(16.dp))
                    ReliabilityRow(
                        title = stringResource(R.string.wake_lock_label),
                        summary = stringResource(R.string.wake_lock_summary),
                        trailing = {
                            ReverbSwitch(
                                checked = currentSnapshot.wakeLockEnabled,
                                onCheckedChange = { enabled ->
                                    currentSnapshot = currentSnapshot.copy(wakeLockEnabled = enabled)
                                    saveCurrentToSnapshot(currentSnapshot)
                                    pushUndoState()
                                },
                            )
                        },
                    )
                }
            }
        }
    }

}

@Composable
private fun ReverbSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val chrome = appChrome()
    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.primary else chrome.raised,
        label = "reverbSwitchTrack",
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = 0.84f),
        label = "reverbSwitchThumb",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 18.dp else 0.dp,
        label = "reverbSwitchThumbOffset",
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 26.dp)
                .background(trackColor, RoundedCornerShape(13.dp))
                .padding(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(18.dp)
                    .background(thumbColor, RoundedCornerShape(50)),
            )
        }
    }
}

@Composable
private fun ReliabilityRow(
    title: String,
    summary: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    val chrome = appChrome()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = chrome.field,
        border = BorderStroke(1.dp, chrome.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = chrome.ink,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = chrome.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun RetentionSection(
    activeMode: RetentionMode,
    focusBuffer: ReverbService.BufferSlot?,
    oneShotTimeText: String,
    oneShotSizeText: String,
    loopingTimeText: String,
    loopingSizeText: String,
    oneShotTimeError: String?,
    oneShotSizeError: String?,
    loopingTimeError: String?,
    loopingSizeError: String?,
    oneShotComputedSizeMb: Double,
    loopingComputedSizeMb: Double,
    oneShotComputedTimeSeconds: Long,
    loopingComputedTimeSeconds: Long,
    onModeSelected: (RetentionMode) -> Unit,
    onOneShotTimeChange: (String) -> Unit,
    onOneShotSizeChange: (String) -> Unit,
    onLoopingTimeChange: (String) -> Unit,
    onLoopingSizeChange: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.retention_mode_title),
                fontSize = 19.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            RetentionModeSelector(activeMode, onModeSelected)
        }

        val chrome = appChrome()
        val cardShape = RoundedCornerShape(23.dp)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            color = chrome.field,
            border = BorderStroke(1.dp, chrome.border),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    RetentionValue(
                        modifier = Modifier.weight(1f),
                        icon = AppIcons.oneShot,
                        label = stringResource(R.string.buffer_one_shot),
                        activeMode = activeMode,
                        timeText = oneShotTimeText,
                        sizeText = oneShotSizeText,
                        computedSizeMb = oneShotComputedSizeMb,
                        computedTimeSeconds = oneShotComputedTimeSeconds,
                        requestFocus = focusBuffer == ReverbService.BufferSlot.ONE_SHOT,
                        onTimeChange = onOneShotTimeChange,
                        onSizeChange = onOneShotSizeChange,
                    )
                    RetentionValue(
                        modifier = Modifier.weight(1f),
                        icon = AppIcons.looping,
                        label = stringResource(R.string.retention_loop_label),
                        activeMode = activeMode,
                        timeText = loopingTimeText,
                        sizeText = loopingSizeText,
                        computedSizeMb = loopingComputedSizeMb,
                        computedTimeSeconds = loopingComputedTimeSeconds,
                        requestFocus = focusBuffer == ReverbService.BufferSlot.LOOPING,
                        onTimeChange = onLoopingTimeChange,
                        onSizeChange = onLoopingSizeChange,
                    )
                }

                val error = if (activeMode == RetentionMode.TIME) {
                    oneShotTimeError ?: loopingTimeError
                } else {
                    oneShotSizeError ?: loopingSizeError
                }
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RetentionModeSelector(
    activeMode: RetentionMode,
    onModeSelected: (RetentionMode) -> Unit,
) {
    val modes = listOf(RetentionMode.TIME, RetentionMode.SIZE)
    SettingsSegmentedControl(
        itemCount = modes.size,
        selectedIndex = modes.indexOf(activeMode),
        onSelected = { onModeSelected(modes[it]) },
        modifier = Modifier.width(126.dp),
    ) { index, contentColor ->
        Text(
            text = stringResource(
                if (modes[index] == RetentionMode.TIME) R.string.retention_time_label
                else R.string.retention_size_mode_label,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun SettingsSegmentedControl(
    itemCount: Int,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (index: Int, contentColor: Color) -> Unit,
) {
    val chrome = appChrome()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        color = chrome.field,
        border = BorderStroke(1.dp, chrome.border),
    ) {
        Row(
            modifier = Modifier
                .padding(4.dp)
                .selectableGroup(),
        ) {
            repeat(itemCount) { index ->
                val selected = index == selectedIndex
                val backgroundColor by animateColorAsState(
                    targetValue = if (selected) chrome.raised else Color.Transparent,
                    label = "settings-segment-background",
                )
                val contentColor by animateColorAsState(
                    targetValue = if (selected) chrome.ink else chrome.muted,
                    label = "settings-segment-content",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(backgroundColor)
                        .selectable(
                            selected = selected,
                            onClick = { onSelected(index) },
                            role = Role.RadioButton,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    content(index, contentColor)
                }
            }
        }
    }
}

@Composable
private fun RetentionValue(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    activeMode: RetentionMode,
    timeText: String,
    sizeText: String,
    computedSizeMb: Double,
    computedTimeSeconds: Long,
    requestFocus: Boolean,
    onTimeChange: (String) -> Unit,
    onSizeChange: (String) -> Unit,
) {
    val chrome = appChrome()
    val isTime = activeMode == RetentionMode.TIME
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(requestFocus) {
        if (requestFocus) focusRequester.requestFocus()
    }
    val value = if (isTime) timeText else sizeText
    val unit = if (isTime) {
        stringResource(R.string.retention_minutes_unit)
    } else {
        stringResource(R.string.retention_mib_unit)
    }
    val estimate = if (isTime) {
        stringResource(
            R.string.retention_size_estimate,
            computedSizeMb.coerceAtLeast(0.0).roundToLong().toString(),
        )
    } else {
        stringResource(
            R.string.retention_time_estimate,
            formatRetentionMinutesEstimate(computedTimeSeconds),
        )
    }

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = chrome.muted,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = label,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = chrome.muted,
            )
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = if (isTime) onTimeChange else onSizeChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = chrome.ink,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = (-1.1).sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = label },
            )
            Text(
                text = unit,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = chrome.muted,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Text(
            text = estimate,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = chrome.muted,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 19.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun ThemeSelector(
    selectedTheme: AppThemeMode,
    onThemeSelected: (AppThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val themes = AppThemeMode.entries
    SettingsSegmentedControl(
        itemCount = themes.size,
        selectedIndex = themes.indexOf(selectedTheme),
        onSelected = { onThemeSelected(themes[it]) },
        modifier = modifier,
    ) { index, contentColor ->
        val theme = themes[index]
        val icon = when (theme) {
            AppThemeMode.SYSTEM -> AppIcons.themeSystem
            AppThemeMode.LIGHT -> AppIcons.themeLight
            AppThemeMode.DARK -> AppIcons.themeDark
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(theme.labelRes),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SettingsDropdown(
    active: Boolean,
    label: String,
    selectedValue: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active) expanded = false
    }
    val chrome = appChrome()
    val containerColor by animateColorAsState(
        targetValue = if (expanded) chrome.raised else chrome.field,
        label = "settings-choice-background",
    )

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            shape = RoundedCornerShape(16.dp),
            color = containerColor,
            border = BorderStroke(1.dp, chrome.border),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = chrome.muted,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = selectedValue,
                        style = MaterialTheme.typography.bodyLarge,
                        color = chrome.ink,
                        maxLines = 1,
                    )
                }
                Icon(
                    AppIcons.arrowDropDown,
                    contentDescription = stringResource(R.string.open_options),
                    tint = chrome.muted,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(18.dp),
            containerColor = chrome.raised,
        ) {
            options.forEach { option ->
                val selected = option == selectedValue
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            color = if (selected) chrome.ink else chrome.muted,
                        )
                    },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    },
                    trailingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = AppIcons.check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else null,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

internal fun parseRetentionTimeSeconds(value: String): Int? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    if (':' in trimmed) return parseDurationInput(trimmed)
    val minutes = trimmed.replace(',', '.').toDoubleOrNull() ?: return null
    if (!minutes.isFinite() || minutes < 0.0) return null
    val seconds = minutes * 60.0
    if (seconds > Int.MAX_VALUE.toDouble()) return null
    return seconds.roundToLong().toInt()
}

internal fun formatRetentionTimeInput(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    return retentionTimeFormatter.format(safeSeconds / 60.0)
}

private fun formatRetentionMinutesEstimate(seconds: Long): String {
    if (seconds <= 0L) return "0"
    return if (seconds % 60L == 0L) {
        (seconds / 60L).toString()
    } else {
        String.format(Locale.US, "%.1f", seconds / 60.0)
    }
}


private fun bytesToMegabytes(bytes: Long): Double {
    return (bytes.coerceAtLeast(0L) / BYTES_IN_MEGABYTE.toDouble())
}

internal fun rawMegabytesToBytes(memoryInMegabytes: Double): Long {
    if (memoryInMegabytes <= 0.0) return 0L
    if (memoryInMegabytes >= Long.MAX_VALUE / BYTES_IN_MEGABYTE.toDouble()) return Long.MAX_VALUE
    return (memoryInMegabytes * BYTES_IN_MEGABYTE.toDouble()).roundToLong()
}

internal fun parseRetentionSizeMib(value: String): Double? {
    return value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
}

internal fun formatRetentionSizeMib(value: Double): String {
    return retentionSizeFormatter.format(value.coerceAtLeast(0.0))
}

internal fun formatRetentionSizeBytes(bytes: Long): String =
    formatRetentionSizeMib(bytesToMegabytes(bytes))
