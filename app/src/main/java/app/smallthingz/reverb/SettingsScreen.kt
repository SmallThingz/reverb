package app.smallthingz.reverb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardOptions
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToLong

private val BYTES_IN_MEGABYTE = 1024L * 1024L
private val retentionSizeFormatter =
    DecimalFormat(ReverbConfig.FORMAT_RETENTION_SIZE_MIB, DecimalFormatSymbols(Locale.US))

data class SettingsSnapshot(
    var themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    var retentionMode: RetentionMode = RetentionMode.TIME,
    var retentionTime: Int = 0,
    var retentionSizeMb: Double = 0.0,
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
        retentionTime = other.retentionTime
        retentionSizeMb = other.retentionSizeMb
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

@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onThemeChanged: (AppThemeMode) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var originalSnapshot by remember { mutableStateOf(SettingsSnapshot()) }
    var currentSnapshot by remember { mutableStateOf(SettingsSnapshot()) }
    var hasUnsavedChanges by remember { mutableStateOf(false) }

    var service by remember { mutableStateOf<ReverbService?>(null) }

    var capabilityUiReady by remember { mutableStateOf(false) }
    var capabilityRefreshGeneration by remember { mutableIntStateOf(0) }
    var moveAvailabilityGeneration by remember { mutableIntStateOf(0) }

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
    var retentionTimeSecondsValue by remember { mutableIntStateOf(0) }
    var retentionSizeMbValue by remember { mutableStateOf(0.0) }
    var selectedExportTreeUri by remember { mutableStateOf<Uri?>(null) }

    // Available options lists (recomputed on changes)
    var availableFormats by remember { mutableStateOf(supportedFormats()) }
    var availableCodecs by remember { mutableStateOf(supportedCodecs(supportedFormats().first())) }
    var availableSourceModes by remember { mutableStateOf(AudioSourceMode.availableModes()) }
    var availableChannelModes by remember { mutableStateOf(ChannelMode.entries.toList()) }
    var availableRouteModes by remember { mutableStateOf(InputRouteMode.entries.toList()) }
    var availableSampleRates by remember { mutableStateOf(standardSampleRates()) }

    // Text inputs
    var retentionTimeText by remember { mutableStateOf("") }
    var retentionSizeText by remember { mutableStateOf("") }

    // Errors
    var retentionTimeError by remember { mutableStateOf<String?>(null) }
    var retentionSizeError by remember { mutableStateOf<String?>(null) }
    var sampleRateUnsupported by remember { mutableStateOf(false) }
    var computedExportLimitSeconds by remember { mutableStateOf(0L) }
    var computedExportSizeMb by remember { mutableStateOf(0.0) }
    var exportPathText by remember { mutableStateOf("") }
    var canMove by remember { mutableStateOf(false) }
    var batteryOptimizationRestricted by remember { mutableStateOf(true) }
    var showBufferResetWarning by remember { mutableStateOf(false) }

    // Pre-computed label lists
    val themeLabels = remember { AppThemeMode.entries.map { context.getString(it.labelRes) } }
    var formatLabels by remember { mutableStateOf(availableFormats.map { context.getString(it.labelRes) }) }
    var codecLabels by remember { mutableStateOf(availableCodecs.map { context.getString(it.labelRes) }) }
    var sampleFormatLabels by remember {
        mutableStateOf(PcmSampleFormat.entries.map { context.getString(it.labelRes) })
    }
    var sourceLabels by remember { mutableStateOf(availableSourceModes.map { context.getString(it.labelRes) }) }
    var channelModeLabels by remember { mutableStateOf(ChannelMode.entries.map { context.getString(it.labelRes) }) }
    var routeLabels by remember { mutableStateOf(InputRouteMode.entries.map { context.getString(it.labelRes) }) }
    var sampleRateLabels by remember { mutableStateOf(emptyList<String>()) }

    // Selection labels
    var selectedThemeLabel by remember { mutableStateOf(context.getString(AppThemeMode.SYSTEM.labelRes)) }
    var selectedFormatLabel by remember { mutableStateOf(context.getString(supportedFormats().first().labelRes)) }
    var selectedCodecLabel by remember {
        mutableStateOf(
            context.getString(supportedCodecs(supportedFormats().first()).first().labelRes),
        )
    }
    var selectedSampleFormatLabel by remember { mutableStateOf(context.getString(PcmSampleFormat.PCM_16.labelRes)) }
    var selectedSourceLabel by remember {
        mutableStateOf(context.getString(AudioSourceMode.availableModes().first().labelRes))
    }
    var selectedChannelModeLabel by remember { mutableStateOf(context.getString(ChannelMode.MONO.labelRes)) }
    var selectedRouteLabel by remember { mutableStateOf(context.getString(InputRouteMode.AUTO.labelRes)) }
    var selectedSampleRateLabel by remember { mutableStateOf(sampleRateLabel(48_000)) }

    fun refreshExportDirectoryUi() {
        exportPathText = describeOutputDirectory(context, selectedExportTreeUri)
    }

    fun refreshBatteryOptimizationUi() {
        batteryOptimizationRestricted = !isIgnoringBatteryOptimizations(context)
    }

    fun refreshMoveRecordingsAvailability() {
        val gen = ++moveAvailabilityGeneration
        canMove = false
        scope.launch {
            val result = runCatching {
                RecordingRepository.syncAndCheckMovableRecordings(
                    context,
                    getOutputDirectoryId(context, selectedExportTreeUri),
                )
            }.getOrDefault(false)
            if (gen == moveAvailabilityGeneration) canMove = result
        }
    }

    fun refreshSampleRates(preferredRate: Int? = null) {
        availableSampleRates = supportedSampleRates(
            context, selectedSource, selectedRoute, selectedFormat, selectedCodec,
            selectedChannelMode, selectedSampleFormat,
        )
        sampleRateUnsupported = availableSampleRates.isEmpty()
        if (availableSampleRates.isNotEmpty()) {
            val rate = orderSampleRatesByPreference(availableSampleRates, preferredRate ?: selectedSampleRate).first()
            selectedSampleRate = rate
            sampleRateLabels = availableSampleRates.map { sampleRateLabel(it) }
            selectedSampleRateLabel = sampleRateLabel(rate)
        } else {
            sampleRateLabels = emptyList()
            selectedSampleRateLabel = ""
        }
    }

    fun refreshChannelModes(
        preferredChannelMode: ChannelMode? = null,
        preferredRate: Int? = null,
    ) {
        availableChannelModes = supportedChannelModes(
            context, selectedSource, selectedRoute, selectedFormat, selectedCodec, selectedSampleFormat,
        )
        val cm = preferredChannelMode?.takeIf { it in availableChannelModes } ?: availableChannelModes.first()
        selectedChannelMode = cm
        channelModeLabels = availableChannelModes.map { context.getString(it.labelRes) }
        selectedChannelModeLabel = context.getString(cm.labelRes)
        refreshSampleRates(preferredRate)
    }

    fun refreshSourceModes(
        preferredSource: AudioSourceMode? = null,
        preferredChannelMode: ChannelMode? = null,
        preferredRate: Int? = null,
    ) {
        availableSourceModes = supportedAudioSourceModes(
            context, selectedRoute, selectedFormat, selectedCodec, selectedSampleFormat,
        )
        val s = preferredSource?.takeIf { it in availableSourceModes } ?: availableSourceModes.first()
        selectedSource = s
        sourceLabels = availableSourceModes.map { context.getString(it.labelRes) }
        selectedSourceLabel = context.getString(s.labelRes)
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
        codecLabels = availableCodecs.map { context.getString(it.labelRes) }
        selectedCodecLabel = context.getString(codec.labelRes)
        refreshSourceModes(preferredSource, preferredChannelMode, preferredRate)
    }

    fun saveCurrentToSnapshot(snapshot: SettingsSnapshot) {
        snapshot.themeMode = selectedTheme
        snapshot.retentionMode = activeRetentionMode
        snapshot.retentionTime = retentionTimeSecondsValue
        snapshot.retentionSizeMb = retentionSizeMbValue
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
        hasUnsavedChanges = originalSnapshot != currentSnapshot
    }

    fun updateRetentionValuesFromActiveInput() {
        when (activeRetentionMode) {
            RetentionMode.TIME -> parseDurationInput(retentionTimeText.trim())?.let { retentionTimeSecondsValue = it }
            RetentionMode.SIZE ->
                parseRetentionSizeMib(retentionSizeText.trim())?.takeIf { it > 0.0 }
                    ?.let { retentionSizeMbValue = it }
        }
    }

    fun refreshRetentionFields(preserveActiveInputs: Boolean = false) {
        val sr = selectedSampleRate
        if (sr <= 0) {
            computedExportLimitSeconds = 0
            computedExportSizeMb = 0.0
            if (!preserveActiveInputs) { retentionTimeText = ""; retentionSizeText = "" }
            return
        }
        val chCount = selectedChannelMode.channelCount
        val bitrate = getConfiguredCodecBitrateKbps(context, selectedCodec, sr, chCount)
        val exportLimitBytes = exportFileSizeLimitBytes(selectedFormat)
        val exportLimitDurationSeconds = estimateExportDurationSeconds(
            selectedFormat, selectedCodec, sr, chCount, exportLimitBytes, bitrate, selectedSampleFormat,
        )
        computedExportLimitSeconds = exportLimitDurationSeconds
        val estimatedSizeMb = bytesToMegabytes(
            estimateExportSizeBytes(
                selectedFormat, selectedCodec, sr, chCount,
                retentionTimeSecondsValue.toLong(), bitrate, selectedSampleFormat,
            ),
        )
        computedExportSizeMb = estimatedSizeMb

        if (activeRetentionMode == RetentionMode.TIME) {
            if (!preserveActiveInputs) retentionTimeText = formatDurationInput(retentionTimeSecondsValue)
            retentionSizeText = formatRetentionSizeMib(estimatedSizeMb)
        } else {
            retentionTimeText = formatDurationInput(
                estimateExportDurationSeconds(
                    selectedFormat, selectedCodec, sr, chCount,
                    rawMegabytesToBytes(retentionSizeMbValue), bitrate, selectedSampleFormat,
                ),
            )
            if (!preserveActiveInputs) retentionSizeText = formatRetentionSizeMib(retentionSizeMbValue)
        }
    }

    fun activateRetentionMode(mode: RetentionMode) {
        if (activeRetentionMode == mode) return
        activeRetentionMode = mode
        refreshRetentionFields(preserveActiveInputs = true)
        saveCurrentToSnapshot(currentSnapshot)
        pushUndoState()
    }

    fun applyResolvedCapabilityUi(preferred: SettingsSnapshot, resetOriginalSnapshot: Boolean) {
        val shouldResetOriginalSnapshot = resetOriginalSnapshot && !hasUnsavedChanges

        availableRouteModes = supportedInputRouteModes(context)
        val selRoute = preferred.route?.takeIf { it in availableRouteModes } ?: availableRouteModes.first()
        selectedRoute = selRoute
        selectedRouteLabel = context.getString(selRoute.labelRes)
        routeLabels = availableRouteModes.map { context.getString(it.labelRes) }

        refreshCodecOptions(
            preferredCodec = preferred.codec,
            preferredSource = preferred.source,
            preferredChannelMode = preferred.channelMode,
            preferredRate = preferred.sampleRate,
        )
        capabilityUiReady = true
        refreshRetentionFields(preserveActiveInputs = true)
        refreshMoveRecordingsAvailability()
        saveCurrentToSnapshot(currentSnapshot)
        if (shouldResetOriginalSnapshot) {
            originalSnapshot.copyFrom(currentSnapshot)
            hasUnsavedChanges = false
        } else {
            pushUndoState()
        }
    }

    fun primeCapabilityPath(preferred: SettingsSnapshot, capabilityContext: Context) {
        val formats = supportedFormats()
        val format = preferred.format?.takeIf { it in formats } ?: formats.first()
        val codecs = supportedCodecs(format)
        val codec = preferred.codec?.takeIf { it in codecs } ?: codecs.first()
        val sampleFormat = preferred.sampleFormat
        val routes = supportedInputRouteModes(capabilityContext)
        val route = preferred.route?.takeIf { it in routes } ?: routes.first()
        val sources = supportedAudioSourceModes(capabilityContext, route, format, codec, sampleFormat)
        val source = preferred.source?.takeIf { it in sources } ?: sources.first()
        val channels = supportedChannelModes(capabilityContext, source, route, format, codec, sampleFormat)
        val channel = preferred.channelMode?.takeIf { it in channels } ?: channels.first()
        supportedSampleRates(capabilityContext, source, route, format, codec, channel, sampleFormat)
    }

    fun refreshCapabilityUiAsync(resetOriginalSnapshot: Boolean) {
        val generation = ++capabilityRefreshGeneration
        val preferred = SettingsSnapshot().also { saveCurrentToSnapshot(it) }
        val appContext = context.applicationContext
        scope.launch(Dispatchers.Default) {
            val primed = runCatching { primeCapabilityPath(preferred, appContext) }.isSuccess
            withContext(Dispatchers.Main) {
                if (generation != capabilityRefreshGeneration) return@withContext
                if (primed) {
                    applyResolvedCapabilityUi(preferred, resetOriginalSnapshot)
                } else {
                    capabilityUiReady = true
                    sampleRateUnsupported = true
                }
            }
        }
    }

    fun restorePreviousSettings() {
        if (!hasUnsavedChanges) return
        val prev = originalSnapshot
        retentionTimeError = null
        retentionSizeError = null
        sampleRateUnsupported = false

        activeRetentionMode = prev.retentionMode
        retentionTimeSecondsValue = prev.retentionTime
        retentionSizeMbValue = prev.retentionSizeMb
        selectedExportTreeUri = prev.exportDirectoryUri?.let(Uri::parse)

        selectedTheme = prev.themeMode
        selectedThemeLabel = context.getString(prev.themeMode.labelRes)
        onThemeChanged(prev.themeMode)
        selectedFormat = prev.format ?: availableFormats.first()
        selectedFormatLabel = context.getString((prev.format ?: availableFormats.first()).labelRes)
        selectedCodec = prev.codec ?: availableCodecs.first()
        selectedCodecLabel = context.getString((prev.codec ?: availableCodecs.first()).labelRes)
        selectedRoute = prev.route ?: availableRouteModes.first()
        selectedRouteLabel = context.getString((prev.route ?: availableRouteModes.first()).labelRes)
        selectedSampleFormat = prev.sampleFormat
        selectedSampleFormatLabel = context.getString(selectedSampleFormat.labelRes)
        selectedSource = prev.source ?: availableSourceModes.first()
        selectedSourceLabel = context.getString(selectedSource.labelRes)
        selectedChannelMode = prev.channelMode ?: ChannelMode.MONO
        selectedChannelModeLabel = context.getString(selectedChannelMode.labelRes)
        selectedSampleRate = prev.sampleRate.takeIf { it > 0 } ?: selectedSampleRate
        selectedSampleRateLabel = sampleRateLabel(selectedSampleRate)

        if (capabilityUiReady) {
            refreshCodecOptions(
                preferredCodec = prev.codec,
                preferredSource = prev.source,
                preferredChannelMode = prev.channelMode,
                preferredRate = prev.sampleRate,
            )
        }

        refreshRetentionFields()
        refreshExportDirectoryUi()
        refreshMoveRecordingsAvailability()
        refreshBatteryOptimizationUi()

        currentSnapshot = prev.copy()
        hasUnsavedChanges = false
        if (!capabilityUiReady) refreshCapabilityUiAsync(false)
    }

    fun persistSettings(showFeedback: Boolean): Boolean {
        retentionTimeError = null
        retentionSizeError = null
        sampleRateUnsupported = false

        if (service?.isRecordingActive() == true) {
            if (showFeedback) {
                Toast.makeText(context, R.string.settings_apply_blocked_recording, Toast.LENGTH_SHORT).show()
            }
            return false
        }

        val format = selectedFormat
        val codec = selectedCodec
        val sampleFormat = selectedSampleFormat
        val channelMode = selectedChannelMode
        val route = selectedRoute
        val source = selectedSource
        val sampleRate = selectedSampleRate

        if (sampleRate <= 0 ||
            !isCodecSupported(format, codec, sampleRate, channelMode) ||
            !isInputConfigSupported(context, sampleRate, source, route, channelMode, sampleFormat)
        ) {
            sampleRateUnsupported = true
            return false
        }

        val retentionTime = if (activeRetentionMode == RetentionMode.TIME) {
            parseDurationInput(retentionTimeText.trim())
        } else {
            retentionTimeSecondsValue
        }
        if (retentionTime == null || retentionTime <= 0) {
            retentionTimeError = context.getString(R.string.retention_time_invalid)
            return false
        }

        val sizeMb = if (activeRetentionMode == RetentionMode.SIZE) {
            parseRetentionSizeMib(retentionSizeText.trim())
        } else {
            retentionSizeMbValue
        }
        if (sizeMb == null || sizeMb <= 0.0) {
            retentionSizeError = context.getString(R.string.custom_memory_size_invalid)
            return false
        }

        val requestedSizeBytes = rawMegabytesToBytes(sizeMb)

        retentionTimeSecondsValue = retentionTime
        retentionSizeMbValue = sizeMb

        setConfiguredThemeMode(context, selectedTheme)
        getRecorderPreferences(context).edit()
            .putInt(PrefKey.RETENTION_MODE, activeRetentionMode.ordinal)
            .putLong(PrefKey.RETENTION_SECONDS, retentionTime.toLong())
            .putLong(PrefKey.AUDIO_MEMORY_SIZE, requestedSizeBytes)
            .putString(PrefKey.PCM_SAMPLE_FORMAT, sampleFormat.prefValue)
            .putInt(PrefKey.AUDIO_SOURCE, source.sourceValue)
            .putString(PrefKey.CHANNEL_MODE, channelMode.prefValue)
            .putString(PrefKey.INPUT_ROUTE, route.prefValue)
            .putInt(PrefKey.SAMPLE_RATE, sampleRate)
            .putBoolean(PrefKey.WAKE_LOCK_ENABLED, currentSnapshot.wakeLockEnabled)
            .apply()
        setConfiguredExportTreeUri(context, selectedExportTreeUri)
        onThemeChanged(selectedTheme)

        val currentService = service
        if (currentService == null) {
            saveCurrentToSnapshot(currentSnapshot)
            originalSnapshot.copyFrom(currentSnapshot)
            hasUnsavedChanges = false
            if (showFeedback) Toast.makeText(context, R.string.settings_saved_next_start, Toast.LENGTH_SHORT).show()
            return true
        }

        currentService.applyUpdatedPreferences()
        saveCurrentToSnapshot(currentSnapshot)
        originalSnapshot.copyFrom(currentSnapshot)
        hasUnsavedChanges = false
        if (showFeedback) Toast.makeText(context, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        return true
    }

    fun bindUiFromPreferences() {
        val prefs = getRecorderPreferences(context)

        val configuredThemeMode = getConfiguredThemeMode(context)
        val configuredMode = getConfiguredRetentionMode(context)
        val configuredTime = getConfiguredRetentionSeconds(context).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val storedSizeBytes = prefs.getLong(PrefKey.AUDIO_MEMORY_SIZE, 512L * BYTES_IN_MEGABYTE)
        val configuredFormat = getConfiguredOutputFormat(context)
        val configuredCodec = getConfiguredOutputCodec(context)
        val configuredSampleFormatVal = getConfiguredPcmSampleFormat(context)
        val configuredRouteVal = getConfiguredInputRouteMode(context)
        val configuredSourceVal = getConfiguredAudioSourceMode(context)
        val configuredChannelModeVal = getConfiguredChannelMode(context)
        val configuredRateVal = prefs.getInt(PrefKey.SAMPLE_RATE, ReverbConfig.PREFERRED_DEFAULT_SAMPLE_RATE)
            .takeIf { it > 0 }
            ?: ReverbConfig.PREFERRED_DEFAULT_SAMPLE_RATE
        val configuredExportTreeUriVal = getConfiguredExportTreeUri(context)

        activeRetentionMode = configuredMode
        retentionTimeSecondsValue = configuredTime
        retentionSizeMbValue = bytesToMegabytes(storedSizeBytes)
        selectedExportTreeUri = configuredExportTreeUriVal

        selectedTheme = configuredThemeMode
        selectedThemeLabel = context.getString(configuredThemeMode.labelRes)

        availableFormats = supportedFormats()
        selectedFormat = configuredFormat.takeIf { it in availableFormats } ?: availableFormats.first()
        formatLabels = availableFormats.map { context.getString(it.labelRes) }
        selectedFormatLabel = context.getString(selectedFormat.labelRes)

        availableRouteModes = InputRouteMode.entries
        selectedRoute = configuredRouteVal
        routeLabels = availableRouteModes.map { context.getString(it.labelRes) }
        selectedRouteLabel = context.getString(configuredRouteVal.labelRes)

        availableCodecs = supportedCodecs(selectedFormat)
        selectedCodec = configuredCodec.takeIf { it in availableCodecs } ?: availableCodecs.first()
        codecLabels = availableCodecs.map { context.getString(it.labelRes) }
        selectedCodecLabel = context.getString(selectedCodec.labelRes)

        selectedSampleFormat = configuredSampleFormatVal
        sampleFormatLabels = PcmSampleFormat.entries.map { context.getString(it.labelRes) }
        selectedSampleFormatLabel = context.getString(configuredSampleFormatVal.labelRes)

        availableSourceModes = AudioSourceMode.availableModes()
        selectedSource = configuredSourceVal
        sourceLabels = availableSourceModes.map { context.getString(it.labelRes) }
        selectedSourceLabel = context.getString(configuredSourceVal.labelRes)

        availableChannelModes = ChannelMode.entries
        selectedChannelMode = configuredChannelModeVal
        channelModeLabels = availableChannelModes.map { context.getString(it.labelRes) }
        selectedChannelModeLabel = context.getString(configuredChannelModeVal.labelRes)

        availableSampleRates = orderSampleRatesByPreference(buildList {
            add(configuredRateVal)
            addAll(standardSampleRates())
        }.filter { it > 0 }.distinct(), configuredRateVal)
        selectedSampleRate = configuredRateVal
        sampleRateLabels = availableSampleRates.map { sampleRateLabel(it) }
        selectedSampleRateLabel = sampleRateLabel(configuredRateVal)

        refreshRetentionFields()
        refreshExportDirectoryUi()
        refreshMoveRecordingsAvailability()
        refreshBatteryOptimizationUi()

        currentSnapshot = currentSnapshot.copy(wakeLockEnabled = isWakeLockEnabled(context))

        saveCurrentToSnapshot(currentSnapshot)
        originalSnapshot.copyFrom(currentSnapshot)
        hasUnsavedChanges = false
        refreshCapabilityUiAsync(resetOriginalSnapshot = true)
    }

    val exportDirectoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        val permissionTaken = runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (!permissionTaken) {
            Toast.makeText(context, R.string.cant_access_folder, Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        selectedExportTreeUri = treeUri
        exportPathText = describeOutputDirectory(context, treeUri)
        saveCurrentToSnapshot(currentSnapshot)
        pushUndoState()
        refreshMoveRecordingsAvailability()
    }

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
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose {
            if (bound) {
                context.unbindService(connection)
            }
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

    fun openBatteryOptimizationSettings() {
        val intents = buildList {
            if (!isIgnoringBatteryOptimizations(context)) {
                add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
            }
            add(Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL").apply {
                data = Uri.parse("package:${context.packageName}")
                putExtra("package_name", context.packageName)
                putExtra("packageName", context.packageName)
            })
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            })
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            add(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        }
        val launched = intents.any { intent ->
            if (intent.resolveActivity(context.packageManager) == null) return@any false
            runCatching { context.startActivity(intent); true }.getOrDefault(false)
        }
        if (!launched) Toast.makeText(context, R.string.no_app_available, Toast.LENGTH_SHORT).show()
    }

    fun moveExistingRecordings() {
        if (!persistSettings(showFeedback = false)) return
        canMove = false
        scope.launch {
            val result = runCatching { RecordingRepository.moveAllToConfiguredDirectory(context) }
                .getOrElse {
                    Toast.makeText(context, R.string.move_recordings_failed, Toast.LENGTH_SHORT).show()
                    refreshMoveRecordingsAvailability()
                    return@launch
                }
            val message = when {
                result.moved == 0 && result.removedMissing == 0 -> context.getString(R.string.move_recordings_none)
                result.removedMissing > 0 -> {
                    val movedMessage = context.resources.getQuantityString(
                        R.plurals.move_recordings_done, result.moved, result.moved,
                    )
                    val removedMessage = context.resources.getQuantityString(
                        R.plurals.move_recordings_removed_missing,
                        result.removedMissing, result.removedMissing,
                    )
                    "$movedMessage $removedMessage"
                }
                else -> context.resources.getQuantityString(R.plurals.move_recordings_done, result.moved, result.moved)
            }
            refreshMoveRecordingsAvailability()
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) { bindUiFromPreferences() }

    BackHandler {
        if (hasUnsavedChanges) {
            restorePreviousSettings()
        }
        onBack()
    }

    val estimatePrefixVal = remember(selectedFormat) {
        if (selectedFormat.isPcmContainer) {
            ReverbConfig.ESTIMATE_EXACT_PREFIX
        } else {
            ReverbConfig.ESTIMATE_APPROX_PREFIX
        }
    }

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp)
                        .height(64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        if (hasUnsavedChanges) restorePreviousSettings() else onBack()
                    }) {
                        Icon(
                            painter = painterResource(
                                if (hasUnsavedChanges) R.drawable.ic_undo else R.drawable.ic_close,
                            ),
                            contentDescription = stringResource(
                                if (hasUnsavedChanges) R.string.undo else R.string.close,
                            ),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            if (!hasUnsavedChanges) return@IconButton
                            val s = service
                            val rateChanged = currentSnapshot.sampleRate != originalSnapshot.sampleRate
                            val formatChanged = currentSnapshot.sampleFormat != originalSnapshot.sampleFormat
                            val channelChanged = currentSnapshot.channelMode != originalSnapshot.channelMode
                            val retentionChanged = currentSnapshot.retentionMode != originalSnapshot.retentionMode ||
                                when (currentSnapshot.retentionMode) {
                                    RetentionMode.TIME -> currentSnapshot.retentionTime != originalSnapshot.retentionTime
                                    RetentionMode.SIZE -> currentSnapshot.retentionSizeMb != originalSnapshot.retentionSizeMb
                                }
                            if ((rateChanged || formatChanged || channelChanged || retentionChanged) &&
                                s != null && s.hasBufferedAudio()
                            ) {
                                showBufferResetWarning = true
                            } else {
                                if (persistSettings(showFeedback = false)) onBack()
                            }
                        },
                        enabled = hasUnsavedChanges,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = stringResource(R.string.done),
                            tint = if (hasUnsavedChanges) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        val view = LocalView.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .pointerInput(Unit) {
                    detectTapGestures {
                        view.clearFocus()
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(view.windowToken, 0)
                    }
                },
        ) {
            SettingsDropdown(
                label = stringResource(R.string.theme_title),
                selectedValue = selectedThemeLabel,
                options = themeLabels,
                onOptionSelected = { label ->
                    selectedThemeLabel = label
                    selectedTheme = AppThemeMode.entries.first { context.getString(it.labelRes) == label }
                    onThemeChanged(selectedTheme)
                    saveCurrentToSnapshot(currentSnapshot)
                    pushUndoState()
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            Spacer(Modifier.height(12.dp))

            SectionTitle(stringResource(R.string.retention_mode_title))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsTextField(
                    label = stringResource(R.string.retention_time_label),
                    value = retentionTimeText,
                    onValueChange = { v ->
                        retentionTimeText = v
                        activateRetentionMode(RetentionMode.TIME)
                        updateRetentionValuesFromActiveInput()
                        refreshRetentionFields(preserveActiveInputs = true)
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                    error = retentionTimeError,
                    prefix = if (activeRetentionMode == RetentionMode.TIME) null else estimatePrefixVal,
                    supportingText = if (computedExportLimitSeconds > 0) {
                        {
                            Text(
                                stringResource(
                                    R.string.export_limit_label,
                                    formatDurationInput(computedExportLimitSeconds),
                                ),
                            )
                        }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (activeRetentionMode == RetentionMode.TIME) 1f else 0.6f)
                        .onFocusChanged { if (it.isFocused) activateRetentionMode(RetentionMode.TIME) },
                )
                SettingsTextField(
                    label = stringResource(R.string.retention_size_label),
                    value = retentionSizeText,
                    onValueChange = { v ->
                        retentionSizeText = v
                        activateRetentionMode(RetentionMode.SIZE)
                        updateRetentionValuesFromActiveInput()
                        refreshRetentionFields(preserveActiveInputs = true)
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                    error = retentionSizeError,
                    prefix = if (activeRetentionMode == RetentionMode.SIZE) null else estimatePrefixVal,
                    supportingText = if (computedExportSizeMb > 0) {
                        {
                            Text(
                                stringResource(
                                    R.string.estimated_file_size_label,
                                    String.format(Locale.US, "%.1f", computedExportSizeMb),
                                ),
                            )
                        }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (activeRetentionMode == RetentionMode.SIZE) 1f else 0.6f)
                        .onFocusChanged { if (it.isFocused) activateRetentionMode(RetentionMode.SIZE) },
                )
            }

            Spacer(Modifier.height(12.dp))

            SectionTitle(stringResource(R.string.recording_settings_title))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (formatLabels.size > 1) {
                    SettingsDropdown(
                        label = stringResource(R.string.format_label),
                        selectedValue = selectedFormatLabel,
                        options = formatLabels,
                        onOptionSelected = { label ->
                            selectedFormatLabel = label
                            selectedFormat = availableFormats.first { context.getString(it.labelRes) == label }
                            saveCurrentToSnapshot(currentSnapshot)
                            pushUndoState()
                            capabilityUiReady = false
                            refreshCapabilityUiAsync(false)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (channelModeLabels.size > 1) {
                    SettingsDropdown(
                        label = stringResource(R.string.channel_mode_label),
                        selectedValue = selectedChannelModeLabel,
                        options = channelModeLabels,
                        onOptionSelected = { label ->
                            selectedChannelModeLabel = label
                            selectedChannelMode = availableChannelModes.first {
                                context.getString(it.labelRes) == label
                            }
                            saveCurrentToSnapshot(currentSnapshot)
                            pushUndoState()
                            capabilityUiReady = false
                            refreshCapabilityUiAsync(false)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (selectedFormat.isPcmContainer) {
                    if (sampleFormatLabels.size > 1) {
                        SettingsDropdown(
                            label = stringResource(R.string.sample_format_label),
                            selectedValue = selectedSampleFormatLabel,
                            options = sampleFormatLabels,
                            onOptionSelected = { label ->
                                selectedSampleFormatLabel = label
                                selectedSampleFormat = PcmSampleFormat.entries.first {
                                    context.getString(it.labelRes) == label
                                }
                                saveCurrentToSnapshot(currentSnapshot)
                                pushUndoState()
                                capabilityUiReady = false
                                refreshCapabilityUiAsync(false)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    if (codecLabels.size > 1) {
                        SettingsDropdown(
                            label = stringResource(R.string.codec_label),
                            selectedValue = selectedCodecLabel,
                            options = codecLabels,
                            onOptionSelected = { label ->
                                selectedCodecLabel = label
                                selectedCodec = availableCodecs.first { context.getString(it.labelRes) == label }
                                saveCurrentToSnapshot(currentSnapshot)
                                pushUndoState()
                                capabilityUiReady = false
                                refreshCapabilityUiAsync(false)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (sampleRateLabels.size > 1) {
                    SettingsDropdown(
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
                        enabled = !sampleRateUnsupported,
                        error = if (sampleRateUnsupported) {
                            stringResource(R.string.unsupported_config_message)
                        } else {
                            null
                        },
                    )
                }
            }
            if (sampleRateUnsupported) {
                Text(
                    text = stringResource(R.string.unsupported_config_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (sourceLabels.size > 1) {
                    SettingsDropdown(
                        label = stringResource(R.string.audio_source_label),
                        selectedValue = selectedSourceLabel,
                        options = sourceLabels,
                        onOptionSelected = { label ->
                            selectedSourceLabel = label
                            selectedSource = availableSourceModes.first { context.getString(it.labelRes) == label }
                            saveCurrentToSnapshot(currentSnapshot)
                            pushUndoState()
                            capabilityUiReady = false
                            refreshCapabilityUiAsync(false)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (routeLabels.size > 1) {
                    SettingsDropdown(
                        label = stringResource(R.string.input_route_label),
                        selectedValue = selectedRouteLabel,
                        options = routeLabels,
                        onOptionSelected = { label ->
                            selectedRouteLabel = label
                            selectedRoute = availableRouteModes.first { context.getString(it.labelRes) == label }
                            saveCurrentToSnapshot(currentSnapshot)
                            pushUndoState()
                            capabilityUiReady = false
                            refreshCapabilityUiAsync(false)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            SectionTitle(stringResource(R.string.storage_settings_title))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = exportPathText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (selectedExportTreeUri != null) {
                            IconButton(onClick = {
                                selectedExportTreeUri = null
                                refreshExportDirectoryUi()
                                refreshMoveRecordingsAvailability()
                                saveCurrentToSnapshot(currentSnapshot)
                                pushUndoState()
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_reset),
                                    contentDescription = stringResource(R.string.default_folder),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { exportDirectoryLauncher.launch(selectedExportTreeUri) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_folder),
                                contentDescription = stringResource(R.string.choose_folder),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(
                        onClick = { moveExistingRecordings() },
                        enabled = canMove,
                    ) {
                        Text(stringResource(R.string.move_recordings))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            SectionTitle(stringResource(R.string.background_persistence_title))
            SwitchRow(
                label = stringResource(R.string.wake_lock_label),
                summary = stringResource(R.string.wake_lock_summary),
                checked = currentSnapshot.wakeLockEnabled,
                onCheckedChange = {
                    currentSnapshot = currentSnapshot.copy(wakeLockEnabled = it)
                    saveCurrentToSnapshot(currentSnapshot)
                    pushUndoState()
                },
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            if (batteryOptimizationRestricted) R.string.battery_optimization_status_limited
                            else R.string.battery_optimization_status_ok,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (batteryOptimizationRestricted) {
                        TextButton(onClick = { openBatteryOptimizationSettings() }) {
                            Text(stringResource(R.string.battery_optimization_button))
                        }
                    }
                }
            }

        }
    }

    if (showBufferResetWarning) {
        AlertDialog(
            onDismissRequest = { showBufferResetWarning = false },
            shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            confirmButton = {
                TextButton(onClick = {
                    showBufferResetWarning = false
                    service?.clearBuffer()
                    if (persistSettings(showFeedback = false)) onBack()
                }) {
                    Text(stringResource(R.string.settings_buffer_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBufferResetWarning = false }) {
                    Text(stringResource(R.string.settings_buffer_reset_cancel))
                }
            },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_buffer_reset_warning),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showBufferResetWarning = false }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            text = { Text(stringResource(R.string.settings_buffer_reset_warning_body)) },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SettingsDropdown(
    label: String,
    selectedValue: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = selectedValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = stringResource(R.string.open_options),
                )
            },
            enabled = enabled,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        if (enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { expanded = true }
            )
        }
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String? = null,
    prefix: String? = null,
    supportingText: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = {
            Column {
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                supportingText?.invoke()
            }
        },
        singleLine = true,
        prefix = if (prefix != null) {{ Text(prefix) }} else null,
        keyboardOptions = keyboardOptions,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier,
    )
}

@Composable
private fun SwitchRow(
    label: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onCheckedChange(!checked) },
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private fun bytesToMegabytes(bytes: Long): Double {
    return (bytes.coerceAtLeast(0L) / BYTES_IN_MEGABYTE.toDouble())
}

private fun rawMegabytesToBytes(memoryInMegabytes: Double): Long {
    if (memoryInMegabytes <= 0.0) return 0L
    if (memoryInMegabytes >= Long.MAX_VALUE / BYTES_IN_MEGABYTE.toDouble()) return Long.MAX_VALUE
    return (memoryInMegabytes * BYTES_IN_MEGABYTE.toDouble()).roundToLong()
}

private fun parseRetentionSizeMib(value: String): Double? {
    return value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun formatRetentionSizeMib(value: Double): String {
    return retentionSizeFormatter.format(value.coerceAtLeast(0.0))
}
