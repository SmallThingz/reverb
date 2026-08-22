package app.smallthingz.reverb

import android.content.ComponentName
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
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
    var oneShotRetentionTime: Int = 0,
    var oneShotRetentionSizeMb: Double = 0.0,
    var loopingRetentionTime: Int = 0,
    var loopingRetentionSizeMb: Double = 0.0,
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
        oneShotRetentionSizeMb = other.oneShotRetentionSizeMb
        loopingRetentionTime = other.loopingRetentionTime
        loopingRetentionSizeMb = other.loopingRetentionSizeMb
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
    onBack: () -> Unit = {},
    onThemeChanged: (AppThemeMode) -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

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
    var oneShotRetentionSizeMbValue by remember { mutableDoubleStateOf(0.0) }
    var loopingRetentionTimeSecondsValue by remember { mutableIntStateOf(0) }
    var loopingRetentionSizeMbValue by remember { mutableDoubleStateOf(0.0) }
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
    var computedExportLimitSeconds by remember { mutableLongStateOf(0L) }
    var oneShotComputedSizeMb by remember { mutableDoubleStateOf(0.0) }
    var loopingComputedSizeMb by remember { mutableDoubleStateOf(0.0) }
    var exportPathText by remember { mutableStateOf("") }
    var canMove by remember { mutableStateOf(false) }
    var batteryOptimizationRestricted by remember { mutableStateOf(!isIgnoringBatteryOptimizations(context)) }

    // Pre-computed label lists
    val themeLabels = remember { AppThemeMode.entries.map { resources.getString(it.labelRes) } }
    var formatLabels by remember { mutableStateOf(availableFormats.map { resources.getString(it.labelRes) }) }
    var sampleFormatLabels by remember {
        mutableStateOf(PcmSampleFormat.entries.map { resources.getString(it.labelRes) })
    }
    var sourceLabels by remember { mutableStateOf(availableSourceModes.map { resources.getString(it.labelRes) }) }
    var channelModeLabels by remember { mutableStateOf(ChannelMode.entries.map { resources.getString(it.labelRes) }) }
    var routeLabels by remember { mutableStateOf(InputRouteMode.entries.map { resources.getString(it.labelRes) }) }
    var sampleRateLabels by remember { mutableStateOf(emptyList<String>()) }

    // Selection labels
    var selectedThemeLabel by remember { mutableStateOf(resources.getString(AppThemeMode.SYSTEM.labelRes)) }
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
        snapshot.oneShotRetentionSizeMb = oneShotRetentionSizeMbValue
        snapshot.loopingRetentionTime = loopingRetentionTimeSecondsValue
        snapshot.loopingRetentionSizeMb = loopingRetentionSizeMbValue
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
            RetentionMode.TIME -> {
                parseDurationInput(oneShotRetentionTimeText.trim())?.let { oneShotRetentionTimeSecondsValue = it }
                parseDurationInput(loopingRetentionTimeText.trim())?.let { loopingRetentionTimeSecondsValue = it }
            }
            RetentionMode.SIZE -> {
                parseRetentionSizeMib(oneShotRetentionSizeText.trim())?.takeIf { it > 0.0 }
                    ?.let { oneShotRetentionSizeMbValue = it }
                parseRetentionSizeMib(loopingRetentionSizeText.trim())?.takeIf { it > 0.0 }
                    ?.let { loopingRetentionSizeMbValue = it }
            }
        }
    }

    fun refreshRetentionFields(preserveActiveInputs: Boolean = false) {
        val sr = selectedSampleRate
        if (sr <= 0) {
            computedExportLimitSeconds = 0
            oneShotComputedSizeMb = 0.0
            loopingComputedSizeMb = 0.0
            if (!preserveActiveInputs) {
                oneShotRetentionTimeText = ""
                oneShotRetentionSizeText = ""
                loopingRetentionTimeText = ""
                loopingRetentionSizeText = ""
            }
            return
        }
        val chCount = selectedChannelMode.channelCount
        val exportLimitBytes = exportFileSizeLimitBytes(selectedFormat)
        val exportLimitDurationSeconds = estimateExportDurationSeconds(
            selectedFormat, selectedCodec, sr, chCount, exportLimitBytes, selectedSampleFormat,
        )
        computedExportLimitSeconds = exportLimitDurationSeconds
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

        if (activeRetentionMode == RetentionMode.TIME) {
            if (!preserveActiveInputs) {
                oneShotRetentionTimeText = formatDurationInput(oneShotRetentionTimeSecondsValue)
                loopingRetentionTimeText = formatDurationInput(loopingRetentionTimeSecondsValue)
            }
            oneShotRetentionSizeText = formatRetentionSizeMib(oneShotComputedSizeMb)
            loopingRetentionSizeText = formatRetentionSizeMib(loopingComputedSizeMb)
        } else {
            oneShotRetentionTimeText = formatDurationInput(
                retentionSecondsForBytes(
                    rawMegabytesToBytes(oneShotRetentionSizeMbValue), sr, chCount, selectedSampleFormat,
                ),
            )
            loopingRetentionTimeText = formatDurationInput(
                retentionSecondsForBytes(
                    rawMegabytesToBytes(loopingRetentionSizeMbValue), sr, chCount, selectedSampleFormat,
                ),
            )
            if (!preserveActiveInputs) {
                oneShotRetentionSizeText = formatRetentionSizeMib(oneShotRetentionSizeMbValue)
                loopingRetentionSizeText = formatRetentionSizeMib(loopingRetentionSizeMbValue)
            }
        }
    }

    fun activateRetentionMode(mode: RetentionMode) {
        if (activeRetentionMode == mode) return
        activeRetentionMode = mode
        refreshRetentionFields(preserveActiveInputs = true)
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
        oneShotRetentionSizeMbValue = prev.oneShotRetentionSizeMb
        loopingRetentionTimeSecondsValue = prev.loopingRetentionTime
        loopingRetentionSizeMbValue = prev.loopingRetentionSizeMb
        selectedExportTreeUri = prev.exportDirectoryUri?.let(Uri::parse)
        if (abandonedExportTreeUri != selectedExportTreeUri) {
            RecordingRepository.releasePendingDirectoryAndCleanup(context, abandonedExportTreeUri)
        }

        selectedTheme = prev.themeMode
        selectedThemeLabel = resources.getString(prev.themeMode.labelRes)
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
            parseDurationInput(oneShotRetentionTimeText.trim())
        } else {
            oneShotRetentionTimeSecondsValue
        }
        if (oneShotRetentionTime == null || oneShotRetentionTime <= 0) {
            oneShotRetentionTimeError = resources.getString(R.string.retention_time_invalid)
            return false
        }

        val loopingRetentionTime = if (activeRetentionMode == RetentionMode.TIME) {
            parseDurationInput(loopingRetentionTimeText.trim())
        } else {
            loopingRetentionTimeSecondsValue
        }
        if (loopingRetentionTime == null || loopingRetentionTime <= 0) {
            loopingRetentionTimeError = resources.getString(R.string.retention_time_invalid)
            return false
        }

        val oneShotSizeMb = if (activeRetentionMode == RetentionMode.SIZE) {
            parseRetentionSizeMib(oneShotRetentionSizeText.trim())
        } else {
            oneShotRetentionSizeMbValue
        }
        if (oneShotSizeMb == null || oneShotSizeMb <= 0.0) {
            oneShotRetentionSizeError = resources.getString(R.string.custom_memory_size_invalid)
            return false
        }

        val loopingSizeMb = if (activeRetentionMode == RetentionMode.SIZE) {
            parseRetentionSizeMib(loopingRetentionSizeText.trim())
        } else {
            loopingRetentionSizeMbValue
        }
        if (loopingSizeMb == null || loopingSizeMb <= 0.0) {
            loopingRetentionSizeError = resources.getString(R.string.custom_memory_size_invalid)
            return false
        }

        if (sampleRate <= 0 || !isCodecSupported(format, codec, sampleRate, channelMode)) return false

        val requestedOneShotSizeBytes = rawMegabytesToBytes(oneShotSizeMb)
        val requestedLoopingSizeBytes = rawMegabytesToBytes(loopingSizeMb)

        oneShotRetentionTimeSecondsValue = oneShotRetentionTime
        oneShotRetentionSizeMbValue = oneShotSizeMb
        loopingRetentionTimeSecondsValue = loopingRetentionTime
        loopingRetentionSizeMbValue = loopingSizeMb

        val settingsEditor = getRecorderPreferences(context).edit()
            .putInt(PrefKey.RETENTION_MODE, activeRetentionMode.ordinal)
            .putLong(PrefKey.ONE_SHOT_RETENTION_SECONDS, oneShotRetentionTime.toLong())
            .putLong(PrefKey.ONE_SHOT_AUDIO_MEMORY_SIZE, requestedOneShotSizeBytes)
            .putLong(PrefKey.RETENTION_SECONDS, loopingRetentionTime.toLong())
            .putLong(PrefKey.AUDIO_MEMORY_SIZE, requestedLoopingSizeBytes)
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
            AppFeedbackCenter.post(resources.getString(R.string.recorder_state_persist_failed), FeedbackTone.ERROR)
            return false
        }
        RecordingRepository.releasePendingDirectoryAndCleanup(context, selectedExportTreeUri)
        onThemeChanged(selectedTheme)

        val currentService = service
        if (currentService == null) {
            saveCurrentToSnapshot(currentSnapshot)
            originalSnapshot.copyFrom(currentSnapshot)
            hasUnsavedChanges = false
            return true
        }

        currentService.applyUpdatedPreferences()
        saveCurrentToSnapshot(currentSnapshot)
        originalSnapshot.copyFrom(currentSnapshot)
        hasUnsavedChanges = false
        return true
    }

    fun bindUiFromPreferences() {
        val configuredThemeMode = getConfiguredThemeMode(context)
        val configuredMode = getConfiguredRetentionMode(context)
        val configuredOneShotTime = getConfiguredOneShotRetentionSeconds(context)
            .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val configuredLoopingTime = getConfiguredRetentionSeconds(context)
            .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
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
        oneShotRetentionSizeMbValue = bytesToMegabytes(storedOneShotSizeBytes)
        loopingRetentionTimeSecondsValue = configuredLoopingTime
        loopingRetentionSizeMbValue = bytesToMegabytes(storedLoopingSizeBytes)
        selectedExportTreeUri = configuredExportTreeUriVal

        selectedTheme = configuredThemeMode
        selectedThemeLabel = resources.getString(configuredThemeMode.labelRes)

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
            RecordingRepository.releasePendingDirectoryAndCleanup(context, selectedExportTreeUri)
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

    @SuppressLint("BatteryLife")
    fun openBatteryOptimizationSettings() {
        val intents = buildList {
            if (!isIgnoringBatteryOptimizations(context)) {
                add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:${context.packageName}".toUri()
                })
            }
            add(Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL").apply {
                data = "package:${context.packageName}".toUri()
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
            runCatching { context.startActivity(intent); true }.getOrDefault(false)
        }
        if (!launched) AppFeedbackCenter.post(resources.getString(R.string.no_app_available), FeedbackTone.ERROR)
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

    LaunchedEffect(Unit) { bindUiFromPreferences() }

    BackHandler {
        if (hasUnsavedChanges) {
            restorePreviousSettings()
        }
        onBack()
    }

    val estimatePrefixVal = ReverbConfig.ESTIMATE_EXACT_PREFIX

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(stringResource(R.string.settings_title))
                },
                navigationIcon = {
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
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!hasUnsavedChanges) return@IconButton
                            if (persistSettings()) onBack()
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
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (batteryOptimizationRestricted) {
                item(key = "background-reliability") {
                    BackgroundReliabilitySection(
                        onBatterySettingsClick = { openBatteryOptimizationSettings() },
                    )
                }
            }

            item(key = "theme") {
                Column {
            SettingsDropdown(
                label = stringResource(R.string.theme_title),
                selectedValue = selectedThemeLabel,
                options = themeLabels,
                onOptionSelected = { label ->
                    selectedThemeLabel = label
                    selectedTheme = AppThemeMode.entries.first { resources.getString(it.labelRes) == label }
                    onThemeChanged(selectedTheme)
                    saveCurrentToSnapshot(currentSnapshot)
                    pushUndoState()
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            Spacer(Modifier.height(12.dp))
                }
            }

            item(key = "retention") {
                Column {
            SectionTitle(stringResource(R.string.retention_mode_title))
            BufferRetentionFields(
                bufferLabel = stringResource(R.string.buffer_one_shot),
                timeText = oneShotRetentionTimeText,
                sizeText = oneShotRetentionSizeText,
                timeError = oneShotRetentionTimeError,
                sizeError = oneShotRetentionSizeError,
                computedSizeMb = oneShotComputedSizeMb,
                activeMode = activeRetentionMode,
                estimatePrefix = estimatePrefixVal,
                onTimeChange = { value ->
                    oneShotRetentionTimeText = value
                    activateRetentionMode(RetentionMode.TIME)
                    updateRetentionValuesFromActiveInput()
                    refreshRetentionFields(preserveActiveInputs = true)
                    saveCurrentToSnapshot(currentSnapshot)
                    pushUndoState()
                },
                onSizeChange = { value ->
                    oneShotRetentionSizeText = value
                    activateRetentionMode(RetentionMode.SIZE)
                    updateRetentionValuesFromActiveInput()
                    refreshRetentionFields(preserveActiveInputs = true)
                    saveCurrentToSnapshot(currentSnapshot)
                    pushUndoState()
                },
                onActivateMode = ::activateRetentionMode,
            )
            Spacer(Modifier.height(8.dp))
            BufferRetentionFields(
                bufferLabel = stringResource(R.string.buffer_loop),
                timeText = loopingRetentionTimeText,
                sizeText = loopingRetentionSizeText,
                timeError = loopingRetentionTimeError,
                sizeError = loopingRetentionSizeError,
                computedSizeMb = loopingComputedSizeMb,
                activeMode = activeRetentionMode,
                estimatePrefix = estimatePrefixVal,
                onTimeChange = { value ->
                    loopingRetentionTimeText = value
                    activateRetentionMode(RetentionMode.TIME)
                    updateRetentionValuesFromActiveInput()
                    refreshRetentionFields(preserveActiveInputs = true)
                    saveCurrentToSnapshot(currentSnapshot)
                    pushUndoState()
                },
                onSizeChange = { value ->
                    loopingRetentionSizeText = value
                    activateRetentionMode(RetentionMode.SIZE)
                    updateRetentionValuesFromActiveInput()
                    refreshRetentionFields(preserveActiveInputs = true)
                    saveCurrentToSnapshot(currentSnapshot)
                    pushUndoState()
                },
                onActivateMode = ::activateRetentionMode,
            )
            if (computedExportLimitSeconds > 0) {
                Text(
                    text = stringResource(
                        R.string.export_limit_label,
                        formatDurationInput(computedExportLimitSeconds),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
                }
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
                                val previousTreeUri = selectedExportTreeUri
                                selectedExportTreeUri = null
                                RecordingRepository.releasePendingDirectoryAndCleanup(context, previousTreeUri)
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
                }
            }

            item(key = "wake-lock") {
                Column {
                    Spacer(Modifier.height(16.dp))
                    ReliabilityRow(
                        title = stringResource(R.string.wake_lock_label),
                        summary = stringResource(R.string.wake_lock_summary),
                        trailing = {
                            Switch(
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
private fun BackgroundReliabilitySection(
    onBatterySettingsClick: () -> Unit,
) {
    Column {
        SectionTitle(stringResource(R.string.background_persistence_title))
        ReliabilityRow(
            title = stringResource(R.string.background_reliability_0_title),
            summary = stringResource(R.string.battery_optimization_status_limited),
            trailing = {
                TextButton(onClick = onBatterySettingsClick) {
                    Text(stringResource(R.string.battery_optimization_button))
                }
            },
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ReliabilityRow(
    title: String,
    summary: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
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
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun BufferRetentionFields(
    bufferLabel: String,
    timeText: String,
    sizeText: String,
    timeError: String?,
    sizeError: String?,
    computedSizeMb: Double,
    activeMode: RetentionMode,
    estimatePrefix: String,
    onTimeChange: (String) -> Unit,
    onSizeChange: (String) -> Unit,
    onActivateMode: (RetentionMode) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = bufferLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsTextField(
                label = stringResource(R.string.retention_time_label),
                value = timeText,
                onValueChange = onTimeChange,
                error = timeError,
                prefix = if (activeMode == RetentionMode.TIME) null else estimatePrefix,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (activeMode == RetentionMode.TIME) 1f else 0.6f)
                    .onFocusChanged { if (it.isFocused) onActivateMode(RetentionMode.TIME) },
            )
            SettingsTextField(
                label = stringResource(R.string.retention_size_label),
                value = sizeText,
                onValueChange = onSizeChange,
                error = sizeError,
                prefix = if (activeMode == RetentionMode.SIZE) null else estimatePrefix,
                supportingText = if (computedSizeMb > 0) {
                    {
                        Text(
                            stringResource(
                                R.string.estimated_file_size_label,
                                String.format(Locale.US, "%.1f", computedSizeMb),
                            ),
                        )
                    }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (activeMode == RetentionMode.SIZE) 1f else 0.6f)
                    .onFocusChanged { if (it.isFocused) onActivateMode(RetentionMode.SIZE) },
            )
        }
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
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
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
    modifier: Modifier = Modifier,
    error: String? = null,
    prefix: String? = null,
    supportingText: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
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
        prefix = if (prefix != null) { { Text(prefix) } } else null,
        keyboardOptions = keyboardOptions,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier,
    )
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
