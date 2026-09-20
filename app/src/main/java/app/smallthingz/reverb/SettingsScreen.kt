package app.smallthingz.reverb

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.IBinder
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToLong

private val BYTES_IN_MEGABYTE = 1024L * 1024L
private val retentionSizeFormatter =
    DecimalFormat(FORMAT_RETENTION_SIZE_MIB, DecimalFormatSymbols(Locale.US))
data class SettingsSnapshot(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val retentionMode: RetentionMode = RetentionMode.TIME,
    val oneShotRetentionTime: Int = 0,
    val oneShotRetentionSizeBytes: Long = 0L,
    val loopingRetentionTime: Int = 0,
    val loopingRetentionSizeBytes: Long = 0L,
    val format: ExportFormat? = null,
    val codec: ExportCodec? = null,
    val sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
    val source: AudioSourceMode? = null,
    val channelMode: ChannelMode? = null,
    val route: InputRouteMode? = null,
    val sampleRate: Int = 0,
    val exportDirectoryUri: String? = null,
    val wakeLockEnabled: Boolean = false,
)

private data class SettingsInitialConfiguration(
    val themeMode: AppThemeMode,
    val retention: RetentionConfiguration,
    val format: ExportFormat,
    val codec: ExportCodec,
    val sampleFormat: PcmSampleFormat,
    val route: InputRouteMode,
    val source: AudioSourceMode,
    val channelMode: ChannelMode,
    val sampleRate: Int,
    val exportTreeUri: Uri?,
    val wakeLockEnabled: Boolean,
)

private val SETTINGS_ALWAYS_WRITTEN_PREFERENCE_KEYS = listOf(
    PrefKey.RETENTION_MODE,
    PrefKey.ONE_SHOT_RETENTION_SECONDS,
    PrefKey.ONE_SHOT_AUDIO_MEMORY_SIZE,
    PrefKey.RETENTION_SECONDS,
    PrefKey.AUDIO_MEMORY_SIZE,
    PrefKey.RETENTION_CONFIG_DIGEST,
    PrefKey.OUTPUT_FORMAT,
    PrefKey.OUTPUT_CODEC,
    PrefKey.PCM_SAMPLE_FORMAT,
    PrefKey.AUDIO_SOURCE,
    PrefKey.CHANNEL_MODE,
    PrefKey.INPUT_ROUTE,
    PrefKey.SAMPLE_RATE,
    PrefKey.WAKE_LOCK_ENABLED,
    PrefKey.THEME_MODE,
    PrefKey.EXPORT_DIRECTORY_URI,
)

internal fun settingsPreferenceRollbackKeys(
    invalidateCachedOneShotFull: Boolean,
): List<PrefKey> = if (invalidateCachedOneShotFull) {
    SETTINGS_ALWAYS_WRITTEN_PREFERENCE_KEYS + PrefKey.QUICK_TILE_ONE_SHOT_FULL
} else {
    SETTINGS_ALWAYS_WRITTEN_PREFERENCE_KEYS
}

internal fun settingsPreferenceRollbackSnapshot(
    rawPreferences: Map<String, *>,
    invalidateCachedOneShotFull: Boolean,
    coordinatedOneShotFullSnapshot: DurablePreferenceValueSnapshot? = null,
): Map<PrefKey, DurablePreferenceValueSnapshot> =
    settingsPreferenceRollbackKeys(invalidateCachedOneShotFull).associateWith { key ->
        if (key == PrefKey.QUICK_TILE_ONE_SHOT_FULL && coordinatedOneShotFullSnapshot != null) {
            coordinatedOneShotFullSnapshot
        } else {
            durablePreferenceValueSnapshot(rawPreferences, key)
        }
    }

internal fun shouldInvalidateCachedOneShotFull(
    previousMode: RetentionMode,
    newMode: RetentionMode,
    previousTimeSeconds: Int,
    newTimeSeconds: Int,
    previousSizeBytes: Long,
    newSizeBytes: Long,
    previousSampleRate: Int,
    newSampleRate: Int,
    previousChannelMode: ChannelMode?,
    newChannelMode: ChannelMode,
    previousSampleFormat: PcmSampleFormat,
    newSampleFormat: PcmSampleFormat,
): Boolean {
    if (previousMode != newMode) return true
    if (
        previousSampleRate != newSampleRate ||
        previousChannelMode != newChannelMode ||
        previousSampleFormat != newSampleFormat
    ) return true
    return when (newMode) {
        RetentionMode.TIME -> newTimeSeconds > previousTimeSeconds
        RetentionMode.SIZE -> newSizeBytes > previousSizeBytes
    }
}

private val RETENTION_MODE_OPTIONS = listOf(RetentionMode.TIME, RetentionMode.SIZE)

internal fun settingsServiceBindingCallbackIsCurrent(
    callbackGeneration: Long,
    currentGeneration: Long,
    bindingOwned: Boolean,
): Boolean = bindingOwned && callbackGeneration == currentGeneration

internal fun settingsShouldOwnServiceBinding(active: Boolean, persisting: Boolean): Boolean =
    active || persisting

internal fun settingsEditedDuringPersistence(submittedRevision: Long, currentRevision: Long): Boolean =
    submittedRevision != currentRevision

internal data class SettingsRetentionModeChange(
    val mode: RetentionMode,
    val preserveInputDrafts: Boolean,
)

internal fun settingsRetentionModeChange(
    currentMode: RetentionMode,
    requestedMode: RetentionMode,
): SettingsRetentionModeChange? =
    if (currentMode == requestedMode) null
    else SettingsRetentionModeChange(requestedMode, preserveInputDrafts = true)

internal fun settingsSnapshotHasUnsavedChanges(
    durableSnapshot: SettingsSnapshot,
    currentSnapshot: SettingsSnapshot,
    invalidRetentionInput: Boolean,
): Boolean = durableSnapshot != currentSnapshot || invalidRetentionInput

internal fun settingsSaveMayContinueAfterCommit(hasUnsavedChanges: Boolean): Boolean =
    !hasUnsavedChanges

internal fun settingsShouldRehydrate(
    active: Boolean,
    persisting: Boolean,
    hasUnsavedChanges: Boolean,
): Boolean = active && !persisting && !hasUnsavedChanges

internal fun settingsHydrationMayApply(
    expectedEditRevision: Long,
    currentEditRevision: Long,
): Boolean = expectedEditRevision == currentEditRevision

internal fun settingsSupersededHydrationMayReleaseInteraction(
    active: Boolean,
    persisting: Boolean,
    hasUnsavedChanges: Boolean,
): Boolean = active && !persisting && hasUnsavedChanges

internal fun settingsMoveAvailabilityResultIsCurrent(
    active: Boolean,
    requestGeneration: Int,
    currentGeneration: Int,
): Boolean = active && requestGeneration == currentGeneration

internal suspend fun <T> runCommittedSettingsMove(
    persistSettings: suspend () -> Boolean,
    move: suspend () -> T,
    onTerminal: (Result<T>) -> Unit,
): Boolean = withContext(NonCancellable) {
    if (!persistSettings()) return@withContext false
    val result = try {
        Result.success(move())
    } catch (error: Exception) {
        Result.failure(error)
    }
    onTerminal(result)
    true
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
    var settingsPersisting by remember { mutableStateOf(false) }
    var settingsHydrated by remember { mutableStateOf(false) }
    var settingsInteractionReady by remember(active) { mutableStateOf(false) }

    var service by remember { mutableStateOf<ReverbService?>(null) }

    val moveAvailabilityGeneration = remember { intArrayOf(0) }
    val settingsEditRevision = remember { longArrayOf(0L) }
    val activeState = androidx.compose.runtime.rememberUpdatedState(active)

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
    var availableRouteModes by remember { mutableStateOf(supportedInputRouteModes(context)) }
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

    fun refreshExportDirectoryUi() {
        exportPathText = describeOutputDirectory(context, selectedExportTreeUri)
    }

    fun refreshBatteryOptimizationUi() {
        batteryOptimizationRestricted = !isIgnoringBatteryOptimizations(context)
    }

    fun refreshMoveRecordingsAvailability() {
        val gen = ++moveAvailabilityGeneration[0]
        canMove = false
        if (!activeState.value) return
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
            if (settingsMoveAvailabilityResultIsCurrent(
                    active = activeState.value,
                    requestGeneration = gen,
                    currentGeneration = moveAvailabilityGeneration[0],
                )
            ) {
                canMove = result
            }
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
    }

    fun refreshChannelModes(
        preferredChannelMode: ChannelMode? = null,
        preferredRate: Int? = null,
    ) {
        availableChannelModes = ChannelMode.entries
        val cm = preferredChannelMode?.takeIf { it in availableChannelModes } ?: availableChannelModes.first()
        selectedChannelMode = cm
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

    fun currentSettingsSnapshot(wakeLockEnabled: Boolean = currentSnapshot.wakeLockEnabled) = SettingsSnapshot(
        themeMode = selectedTheme,
        retentionMode = activeRetentionMode,
        oneShotRetentionTime = oneShotRetentionTimeSecondsValue,
        oneShotRetentionSizeBytes = oneShotRetentionSizeBytesValue,
        loopingRetentionTime = loopingRetentionTimeSecondsValue,
        loopingRetentionSizeBytes = loopingRetentionSizeBytesValue,
        format = selectedFormat,
        codec = selectedCodec,
        sampleFormat = selectedSampleFormat,
        source = selectedSource,
        channelMode = selectedChannelMode,
        route = selectedRoute,
        sampleRate = selectedSampleRate,
        exportDirectoryUri = selectedExportTreeUri?.toString(),
        wakeLockEnabled = wakeLockEnabled,
    )

    fun recomputeUnsavedState() {
        val invalidRetentionInput = when (activeRetentionMode) {
            RetentionMode.TIME ->
                parseRetentionTimeSeconds(oneShotRetentionTimeText) == null ||
                    parseRetentionTimeSeconds(loopingRetentionTimeText) == null
            RetentionMode.SIZE ->
                parseRetentionSizeMib(oneShotRetentionSizeText)?.takeIf { it >= 0.0 } == null ||
                    parseRetentionSizeMib(loopingRetentionSizeText)?.takeIf { it >= 0.0 } == null
        }
        hasUnsavedChanges = settingsSnapshotHasUnsavedChanges(
            durableSnapshot = originalSnapshot,
            currentSnapshot = currentSnapshot,
            invalidRetentionInput = invalidRetentionInput,
        )
    }

    fun pushUndoState() {
        settingsEditRevision[0]++
        recomputeUnsavedState()
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

    fun finishSettingsEdit(preserveActiveInputs: Boolean = true) {
        refreshRetentionFields(preserveActiveInputs = preserveActiveInputs)
        currentSnapshot = currentSettingsSnapshot()
        pushUndoState()
    }

    fun activateRetentionMode(mode: RetentionMode) {
        val change = settingsRetentionModeChange(activeRetentionMode, mode) ?: return
        // onValueChange keeps the active backing value current. Switching modes only
        // changes presentation; it must never reparse the rounded display string or erase drafts.
        activeRetentionMode = change.mode
        finishSettingsEdit(preserveActiveInputs = change.preserveInputDrafts)
    }

    fun restorePreviousSettings() {
        if (!hasUnsavedChanges) return
        settingsEditRevision[0]++
        val prev = originalSnapshot
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
        selectedTheme = prev.themeMode
        onThemeChanged(prev.themeMode)
        selectedFormat = prev.format ?: availableFormats.first()
        selectedCodec = prev.codec ?: availableCodecs.first()
        selectedRoute = prev.route ?: availableRouteModes.first()
        selectedSampleFormat = prev.sampleFormat
        selectedSource = prev.source ?: availableSourceModes.first()
        selectedChannelMode = prev.channelMode ?: ChannelMode.MONO
        selectedSampleRate = prev.sampleRate.takeIf { it > 0 } ?: selectedSampleRate

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

        currentSnapshot = prev
        hasUnsavedChanges = false
    }

    fun refreshStoppedQuickTileFallback() {
        val fallback = RecordingQuickTileStateCache.markRuntimeUnavailable(context)
        RecordingQuickTiles.refreshCachedSnapshot(
            context = context,
            snapshot = fallback,
            requestSystemRefresh = true,
        )
    }

    fun applyCommittedSettingsToRuntime(transactionService: ReverbService?) {
        val currentService = service ?: transactionService
        if (currentService?.applyUpdatedPreferences() == true) return
        val appContext = context.applicationContext
        if (getRecorderPreferences(appContext).safeBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)) {
            runCatching {
                requireServiceStarted {
                    appContext.startService(
                        Intent(appContext, ReverbService::class.java).setAction(ReverbService.ACTION_APPLY_SETTINGS),
                    )
                }
            }.onFailure {
                // Durable settings changed but no recorder accepted the reload. Replace stale
                // runtime tile state immediately with a fail-closed snapshot, then hydrate the
                // committed stopped settings on the tile IO worker.
                refreshStoppedQuickTileFallback()
                AppFeedbackCenter.post(
                    resources.getString(R.string.settings_apply_failed),
                    FeedbackTone.ERROR,
                )
            }
        } else {
            refreshStoppedQuickTileFallback()
        }
    }

    @SuppressLint("UseKtx") // commit() Boolean is required by the retention transaction.
    suspend fun persistSettings(): Boolean {
        if (settingsPersisting) return false
        settingsPersisting = true
        try {
        oneShotRetentionTimeError = null
        oneShotRetentionSizeError = null
        loopingRetentionTimeError = null
        loopingRetentionSizeError = null

        if (!retentionMutationIsSafe(context)) {
            AppFeedbackCenter.post(resources.getString(R.string.recorder_state_persist_failed), FeedbackTone.ERROR)
            return false
        }

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
        val retentionConfiguration = RetentionConfiguration(
            mode = activeRetentionMode,
            oneShotSeconds = oneShotRetentionTime.toLong(),
            oneShotSizeBytes = requestedOneShotSizeBytes,
            loopingSeconds = loopingRetentionTime.toLong(),
            loopingSizeBytes = requestedLoopingSizeBytes,
        )
        // Capture the exact UI state owned by this transaction before the first suspension.
        // Later edits may continue in the retained Settings composition, but they are a new
        // unsaved revision and must never be promoted to the durable baseline by this save.
        val submittedSnapshot = currentSettingsSnapshot()
        val submittedEditRevision = settingsEditRevision[0]

        val preferences = getRecorderPreferences(context)
        val previous = originalSnapshot

        val invalidateCachedOneShotFull = shouldInvalidateCachedOneShotFull(
            previousMode = originalSnapshot.retentionMode,
            newMode = activeRetentionMode,
            previousTimeSeconds = originalSnapshot.oneShotRetentionTime,
            newTimeSeconds = oneShotRetentionTime,
            previousSizeBytes = originalSnapshot.oneShotRetentionSizeBytes,
            newSizeBytes = requestedOneShotSizeBytes,
            previousSampleRate = originalSnapshot.sampleRate,
            newSampleRate = sampleRate,
            previousChannelMode = originalSnapshot.channelMode,
            newChannelMode = channelMode,
            previousSampleFormat = originalSnapshot.sampleFormat,
            newSampleFormat = sampleFormat,
        )
        val settingsEditor = preferences.edit()
            .putInt(PrefKey.RETENTION_MODE, activeRetentionMode.storageCode.toInt())
            .putLong(PrefKey.ONE_SHOT_RETENTION_SECONDS, oneShotRetentionTime.toLong())
            .putLong(PrefKey.ONE_SHOT_AUDIO_MEMORY_SIZE, requestedOneShotSizeBytes)
            .putLong(PrefKey.RETENTION_SECONDS, loopingRetentionTime.toLong())
            .putLong(PrefKey.AUDIO_MEMORY_SIZE, requestedLoopingSizeBytes)
            .putString(PrefKey.RETENTION_CONFIG_DIGEST, retentionConfigurationDigest(retentionConfiguration))
            .putInt(PrefKey.OUTPUT_FORMAT, format.storageCode.toInt())
            .putInt(PrefKey.OUTPUT_CODEC, codec.storageCode.toInt())
            .putInt(PrefKey.PCM_SAMPLE_FORMAT, sampleFormat.storageCode.toInt())
            .putInt(PrefKey.AUDIO_SOURCE, source.storageCode.toInt())
            .putInt(PrefKey.CHANNEL_MODE, channelMode.storageCode.toInt())
            .putInt(PrefKey.INPUT_ROUTE, route.storageCode.toInt())
            .putInt(PrefKey.SAMPLE_RATE, sampleRate)
            .putBoolean(PrefKey.WAKE_LOCK_ENABLED, currentSnapshot.wakeLockEnabled)
            .putInt(PrefKey.THEME_MODE, selectedTheme.storageCode.toInt())
        if (invalidateCachedOneShotFull) {
            settingsEditor.putBoolean(PrefKey.QUICK_TILE_ONE_SHOT_FULL, false)
        }
        if (selectedExportTreeUri != null) {
            settingsEditor.putString(PrefKey.EXPORT_DIRECTORY_URI, selectedExportTreeUri.toString())
        } else {
            settingsEditor.remove(PrefKey.EXPORT_DIRECTORY_URI)
        }
        val transactionService = service
        // Once the recovery/preferences transaction starts, a configuration change or Activity
        // disposal may cancel only the UI tail. A successful durable commit must still reach the
        // surviving recorder (or stopped tile fallback) before this section can terminate.
        val persisted = runDurableUiBooleanAttempt {
            withContext(NonCancellable) {
                val committed = withContext(Dispatchers.IO) {
                withRetentionPersistenceLock {
                    // Roll back to the state that was actually durable when this transaction started,
                    // not to the UI's older edit snapshot. Another writer may have committed since
                    // Settings opened.
                    val rollbackRetention = retentionConfigurationForRead(context)
                    val oneShotFullRollback = if (invalidateCachedOneShotFull) {
                        RecordingQuickTileStateCache.snapshotOneShotFullPreferenceRollback(preferences)
                    } else {
                        null
                    }
                    val rollbackPreferences = settingsPreferenceRollbackSnapshot(
                        rawPreferences = preferences.all,
                        invalidateCachedOneShotFull = invalidateCachedOneShotFull,
                        coordinatedOneShotFullSnapshot = oneShotFullRollback?.rawValue,
                    )

                    var transactionCommitted = false
                    try {
                        transactionCommitted = persistRetentionTransaction(
                            // Recovery is the write-ahead side of the transaction. If the process dies before
                            // preferences commit, restart sees a mismatch and existing history fails closed.
                            writeNewRecovery = { writeRetentionRecoveryConfiguration(context, retentionConfiguration) },
                            commitNewPreferences = { settingsEditor.commit() },
                            restoreRecovery = { writeRetentionRecoveryConfiguration(context, rollbackRetention) },
                            restorePreferences = {
                                val editor = preferences.edit()
                                rollbackPreferences.forEach { (key, snapshot) ->
                                    editor.restoreDurablePreferenceValue(key, snapshot)
                                }
                                editor.commit()
                            },
                        )
                        transactionCommitted
                    } finally {
                        if (!transactionCommitted && oneShotFullRollback != null) {
                            RecordingQuickTileStateCache.reconcileOneShotFullAfterFailedSettings(
                                preferences = preferences,
                                token = oneShotFullRollback,
                            )
                        }
                    }
                }
                }
                if (committed) applyCommittedSettingsToRuntime(transactionService)
                committed
            }
        }
        if (!persisted) {
            AppFeedbackCenter.post(resources.getString(R.string.recorder_state_persist_failed), FeedbackTone.ERROR)
            return false
        }
        onThemeChanged(selectedTheme)

        val editedWhileSaving = settingsEditedDuringPersistence(
            submittedRevision = submittedEditRevision,
            currentRevision = settingsEditRevision[0],
        )
        // Only the submitted snapshot became durable. If the user edited retained Settings
        // while IO was in flight, preserve those live inputs and keep them visibly unsaved.
        // Otherwise normalize presentation from the exact committed backing values.
        originalSnapshot = submittedSnapshot
        if (editedWhileSaving) {
            refreshRetentionFields(preserveActiveInputs = true)
            currentSnapshot = currentSettingsSnapshot()
            recomputeUnsavedState()
        } else {
            refreshRetentionFields(preserveActiveInputs = false)
            currentSnapshot = submittedSnapshot
            hasUnsavedChanges = false
        }
        return settingsSaveMayContinueAfterCommit(hasUnsavedChanges)
        } finally {
            settingsPersisting = false
        }
    }

    suspend fun bindUiFromPreferences(): Boolean {
        val hydrationEditRevision = settingsEditRevision[0]
        val initial = withContext(Dispatchers.IO) {
            SettingsInitialConfiguration(
                themeMode = getConfiguredThemeMode(context),
                retention = retentionConfigurationForRead(context),
                format = getConfiguredOutputFormat(context),
                codec = getConfiguredOutputCodec(context),
                sampleFormat = getConfiguredPcmSampleFormat(context),
                route = getConfiguredInputRouteMode(context),
                source = getConfiguredAudioSourceMode(context),
                channelMode = getConfiguredChannelMode(context),
                sampleRate = getConfiguredSampleRate(context),
                exportTreeUri = getConfiguredExportTreeUriForSettings(context),
                wakeLockEnabled = isWakeLockEnabled(context),
            )
        }
        if (!settingsHydrationMayApply(hydrationEditRevision, settingsEditRevision[0])) return false
        val configuredThemeMode = initial.themeMode
        val retention = initial.retention
        val configuredMode = retention.mode
        val configuredOneShotTime = retention.oneShotSeconds
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val configuredLoopingTime = retention.loopingSeconds
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val storedOneShotSizeBytes = retention.oneShotSizeBytes
        val storedLoopingSizeBytes = retention.loopingSizeBytes
        val configuredFormat = initial.format
        val configuredCodec = initial.codec
        val configuredSampleFormatVal = initial.sampleFormat
        val configuredRouteVal = initial.route
        val configuredSourceVal = initial.source
        val configuredChannelModeVal = initial.channelMode
        val configuredRateVal = initial.sampleRate
        val configuredExportTreeUriVal = initial.exportTreeUri

        activeRetentionMode = configuredMode
        oneShotRetentionTimeSecondsValue = configuredOneShotTime
        oneShotRetentionSizeBytesValue = storedOneShotSizeBytes
        loopingRetentionTimeSecondsValue = configuredLoopingTime
        loopingRetentionSizeBytesValue = storedLoopingSizeBytes
        selectedExportTreeUri = configuredExportTreeUriVal

        selectedTheme = configuredThemeMode

        availableFormats = supportedFormats()
        selectedFormat = configuredFormat.takeIf { it in availableFormats } ?: availableFormats.first()

        availableRouteModes = supportedInputRouteModes(context)
        selectedRoute = configuredRouteVal.takeIf { it in availableRouteModes } ?: availableRouteModes.first()

        selectedSampleFormat = configuredSampleFormatVal

        refreshCodecOptions(
            preferredCodec = configuredCodec,
            preferredSource = configuredSourceVal,
            preferredChannelMode = configuredChannelModeVal,
            preferredRate = configuredRateVal,
        )
        refreshRetentionFields()
        refreshExportDirectoryUi()
        refreshBatteryOptimizationUi()

        currentSnapshot = currentSettingsSnapshot(initial.wakeLockEnabled)
        originalSnapshot = currentSnapshot
        hasUnsavedChanges = false
        settingsHydrated = true
        settingsInteractionReady = true
        return true
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
            AppFeedbackCenter.post(resources.getString(R.string.cant_access_folder), FeedbackTone.ERROR)
            return@rememberLauncherForActivityResult
        }
        selectedExportTreeUri = treeUri
        exportPathText = describeOutputDirectory(context, treeUri)
        currentSnapshot = currentSettingsSnapshot()
        pushUndoState()
        refreshMoveRecordingsAvailability()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val serviceBindingGeneration = remember { longArrayOf(0L) }
    val shouldOwnServiceBinding = settingsShouldOwnServiceBinding(active, settingsPersisting)
    DisposableEffect(shouldOwnServiceBinding, lifecycleOwner) {
        var boundConnection: android.content.ServiceConnection? = null

        fun unbindCurrentConnection() {
            val current = boundConnection ?: return
            boundConnection = null
            serviceBindingGeneration[0]++
            service = null
            runCatching { context.unbindService(current) }
        }

        fun bindIfNeeded() {
            if (!shouldOwnServiceBinding ||
                boundConnection != null ||
                !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) return

            val bindingGeneration = ++serviceBindingGeneration[0]
            val candidate = object : android.content.ServiceConnection {
                private fun isCurrent(): Boolean = settingsServiceBindingCallbackIsCurrent(
                    callbackGeneration = bindingGeneration,
                    currentGeneration = serviceBindingGeneration[0],
                    bindingOwned = boundConnection === this,
                )

                override fun onServiceConnected(className: ComponentName, binder: IBinder) {
                    if (!isCurrent()) return
                    service = (binder as? ReverbService.BackgroundRecorderBinder)?.service
                }

                override fun onServiceDisconnected(arg0: ComponentName) {
                    if (!isCurrent()) return
                    service = null
                }
            }
            boundConnection = candidate
            val bound = runCatching {
                context.bindService(Intent(context, ReverbService::class.java), candidate, 0)
            }.getOrDefault(false)
            if (!bound && boundConnection === candidate) {
                boundConnection = null
                serviceBindingGeneration[0]++
                service = null
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> bindIfNeeded()
                Lifecycle.Event.ON_STOP -> unbindCurrentConnection()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        bindIfNeeded()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unbindCurrentConnection()
        }
    }

    DisposableEffect(lifecycleOwner) {
        // The initial value was read above during composition. Ignore LifecycleRegistry's
        // synchronous catch-up ON_RESUME and refresh only on later real resumes.
        var observerInstalled = false
        val observer = LifecycleEventObserver { _, event ->
            if (observerInstalled && event == Lifecycle.Event.ON_RESUME) {
                refreshBatteryOptimizationUi()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        observerInstalled = true
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
        if (settingsPersisting) return
        val moveTargetTreeUri = selectedExportTreeUri
        val appContext = context.applicationContext
        val appResources = appContext.resources
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // A Move action first commits the exact Settings snapshot that selected this target.
            // Once that commit succeeds, the physical batch and its process-level terminal result
            // outlive Activity/Compose cancellation. The repository already pins moveTargetTreeUri
            // for the full batch, so newer Settings edits cannot retarget work already accepted.
            val moveStarted = runCommittedSettingsMove(
                persistSettings = ::persistSettings,
                move = {
                    canMove = false
                    RecordingRepository.moveAllToDirectory(appContext, moveTargetTreeUri)
                },
                onTerminal = { outcome ->
                    outcome.fold(
                        onSuccess = { result ->
                            val messageParts = buildList {
                                if (result.moved > 0) {
                                    add(
                                        appResources.getQuantityString(
                                            R.plurals.move_recordings_done, result.moved, result.moved,
                                        ),
                                    )
                                }
                                if (result.failed > 0) {
                                    add(
                                        appResources.getQuantityString(
                                            R.plurals.move_recordings_failed_count, result.failed, result.failed,
                                        ),
                                    )
                                }
                                if (result.cleanupFailed > 0) {
                                    add(
                                        appResources.getQuantityString(
                                            R.plurals.move_recordings_cleanup_failed,
                                            result.cleanupFailed, result.cleanupFailed,
                                        ),
                                    )
                                }
                            }
                            val message = messageParts.joinToString(" ").ifBlank {
                                appResources.getString(R.string.move_recordings_none)
                            }
                            AppFeedbackCenter.post(
                                message,
                                if (result.hasFailures) FeedbackTone.ERROR else FeedbackTone.SUCCESS,
                            )
                        },
                        onFailure = {
                            AppFeedbackCenter.post(
                                appResources.getString(R.string.move_recordings_failed),
                                FeedbackTone.ERROR,
                            )
                        },
                    )
                },
            )
            if (!moveStarted) return@launch
            refreshMoveRecordingsAvailability()
        }
    }

    fun releaseInputFocus() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    LaunchedEffect(active, settingsPersisting) {
        if (!active && !settingsPersisting) {
            // A save transaction owns the edited values until it completes. If it succeeds,
            // hasUnsavedChanges becomes false; if it fails, this reruns and restores the last
            // durable snapshot instead of leaving hidden retained UI with uncommitted values.
            if (hasUnsavedChanges) restorePreviousSettings()
            releaseInputFocus()
        }
    }
    LaunchedEffect(active, settingsPersisting) {
        if (settingsShouldRehydrate(active, settingsPersisting, hasUnsavedChanges)) {
            settingsInteractionReady = false
            var retryDelayMillis = DURABLE_UI_RETRY_INITIAL_MILLIS
            var failureReported = false
            while (settingsShouldRehydrate(active, settingsPersisting, hasUnsavedChanges)) {
                val hydrationAttemptRevision = settingsEditRevision[0]
                val hydrated = runDurableUiBooleanAttempt { bindUiFromPreferences() }
                if (hydrated) break

                val superseded = !settingsHydrationMayApply(
                    hydrationAttemptRevision,
                    settingsEditRevision[0],
                )
                if (superseded) {
                    if (settingsSupersededHydrationMayReleaseInteraction(
                            active = active,
                            persisting = settingsPersisting,
                            hasUnsavedChanges = hasUnsavedChanges,
                        )
                    ) {
                        settingsInteractionReady = true
                    }
                    if (settingsShouldRehydrate(active, settingsPersisting, hasUnsavedChanges)) {
                        retryDelayMillis = DURABLE_UI_RETRY_INITIAL_MILLIS
                        continue
                    }
                    break
                }

                if (!failureReported) {
                    AppFeedbackCenter.post(
                        resources.getString(R.string.recorder_state_persist_failed),
                        FeedbackTone.ERROR,
                    )
                    failureReported = true
                }
                delay(retryDelayMillis)
                retryDelayMillis = nextDurableUiRetryDelayMillis(retryDelayMillis)
            }
        }
    }
    if (!settingsHydrated) {
        Box(modifier = modifier.fillMaxSize())
        return
    }
    LaunchedEffect(active, selectedExportTreeUri) {
        refreshMoveRecordingsAvailability()
    }
    LaunchedEffect(focusRetentionBuffer, batteryOptimizationRestricted) {
        if (focusRetentionBuffer != null) {
            listState.scrollToItem(if (batteryOptimizationRestricted) 2 else 1)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(settingsInteractionReady) {
                if (!settingsInteractionReady) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .semantics { if (!settingsInteractionReady) hideFromAccessibility() },
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
                            if (!hasUnsavedChanges || settingsPersisting) return@IconButton
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                if (persistSettings()) {
                                    releaseInputFocus()
                                    onBack()
                                }
                            }
                        },
                        enabled = hasUnsavedChanges && !settingsPersisting,
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
                                    currentSnapshot = currentSettingsSnapshot()
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
                        finishSettingsEdit()
                    },
                    onOneShotSizeChange = { value ->
                        oneShotRetentionSizeText = value
                        oneShotRetentionSizeError = null
                        parseRetentionSizeMib(value)?.takeIf { it >= 0.0 }
                            ?.let { oneShotRetentionSizeBytesValue = rawMegabytesToBytes(it) }
                        finishSettingsEdit()
                    },
                    onLoopingTimeChange = { value ->
                        loopingRetentionTimeText = value
                        loopingRetentionTimeError = null
                        parseRetentionTimeSeconds(value)?.let { loopingRetentionTimeSecondsValue = it }
                        finishSettingsEdit()
                    },
                    onLoopingSizeChange = { value ->
                        loopingRetentionSizeText = value
                        loopingRetentionSizeError = null
                        parseRetentionSizeMib(value)?.takeIf { it >= 0.0 }
                            ?.let { loopingRetentionSizeBytesValue = rawMegabytesToBytes(it) }
                        finishSettingsEdit()
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
                if (availableFormats.size > 1) {
                    SettingsDropdown(
                        active = active,
                        label = stringResource(R.string.format_label),
                        selectedValue = selectedFormat,
                        options = availableFormats,
                        optionLabel = { resources.getString(it.labelRes) },
                        onOptionSelected = { format ->
                            selectedFormat = format
                            refreshCodecOptions(
                                preferredCodec = selectedCodec,
                                preferredSource = selectedSource,
                                preferredChannelMode = selectedChannelMode,
                                preferredRate = selectedSampleRate,
                            )
                            finishSettingsEdit()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (availableChannelModes.size > 1) {
                    SettingsDropdown(
                        active = active,
                        label = stringResource(R.string.channel_mode_label),
                        selectedValue = selectedChannelMode,
                        options = availableChannelModes,
                        optionLabel = { resources.getString(it.labelRes) },
                        onOptionSelected = { channelMode ->
                            selectedChannelMode = channelMode
                            refreshSampleRates(selectedSampleRate)
                            finishSettingsEdit()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (PcmSampleFormat.entries.size > 1) {
                    SettingsDropdown(
                        active = active,
                        label = stringResource(R.string.sample_format_label),
                        selectedValue = selectedSampleFormat,
                        options = PcmSampleFormat.entries,
                        optionLabel = { resources.getString(it.labelRes) },
                        onOptionSelected = { sampleFormat ->
                            selectedSampleFormat = sampleFormat
                            refreshSourceModes(
                                preferredSource = selectedSource,
                                preferredChannelMode = selectedChannelMode,
                                preferredRate = selectedSampleRate,
                            )
                            finishSettingsEdit()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (availableSampleRates.size > 1) {
                    SettingsDropdown(
                        active = active,
                        label = stringResource(R.string.sample_rate_label),
                        selectedValue = selectedSampleRate,
                        options = availableSampleRates,
                        optionLabel = ::sampleRateLabel,
                        onOptionSelected = { sampleRate ->
                            selectedSampleRate = sampleRate
                            finishSettingsEdit()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (availableSourceModes.size > 1) {
                    SettingsDropdown(
                        active = active,
                        label = stringResource(R.string.audio_source_label),
                        selectedValue = selectedSource,
                        options = availableSourceModes,
                        optionLabel = { resources.getString(it.labelRes) },
                        onOptionSelected = { source ->
                            selectedSource = source
                            refreshChannelModes(selectedChannelMode, selectedSampleRate)
                            finishSettingsEdit()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (availableRouteModes.size > 1) {
                    SettingsDropdown(
                        active = active,
                        label = stringResource(R.string.input_route_label),
                        selectedValue = selectedRoute,
                        options = availableRouteModes,
                        optionLabel = { resources.getString(it.labelRes) },
                        onOptionSelected = { route ->
                            selectedRoute = route
                            refreshSourceModes(
                                preferredSource = selectedSource,
                                preferredChannelMode = selectedChannelMode,
                                preferredRate = selectedSampleRate,
                            )
                            finishSettingsEdit()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
                }
            }

            item(key = "storage") {
                Column {
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
                                    selectedExportTreeUri = null
                                    refreshExportDirectoryUi()
                                    refreshMoveRecordingsAvailability()
                                    currentSnapshot = currentSettingsSnapshot()
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
                        enabled = canMove && !settingsPersisting,
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
                                    currentSnapshot = currentSettingsSnapshot(enabled)
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
                    .offset { androidx.compose.ui.unit.IntOffset(thumbOffset.roundToPx(), 0) }
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
    SettingsSegmentedControl(
        itemCount = RETENTION_MODE_OPTIONS.size,
        selectedIndex = RETENTION_MODE_OPTIONS.indexOf(activeMode),
        onSelected = { onModeSelected(RETENTION_MODE_OPTIONS[it]) },
        modifier = Modifier.width(126.dp),
    ) { index, contentColor ->
        Text(
            text = stringResource(
                if (RETENTION_MODE_OPTIONS[index] == RetentionMode.TIME) R.string.retention_time_label
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
    val unit = if (isTime) null else stringResource(R.string.retention_mib_unit)
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
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isTime) KeyboardType.Ascii else KeyboardType.Decimal,
                ),
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
            if (unit != null) {
                Text(
                    text = unit,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = chrome.muted,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
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
private fun <T> SettingsDropdown(
    active: Boolean,
    label: String,
    selectedValue: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onOptionSelected: (T) -> Unit,
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
                        text = optionLabel(selectedValue),
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
                val optionText = optionLabel(option)
                DropdownMenuItem(
                    text = {
                        Text(
                            text = optionText,
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
    val total = seconds.coerceAtLeast(0L)
    val hours = total / 3600L
    val minutes = total % 3600L / 60L
    val secs = total % 60L
    return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
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
