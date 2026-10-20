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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
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
    val flacCompression: Int = DEFAULT_FLAC_COMPRESSION,
    val quickSaveEnabled: Boolean = true,
    val quickSaveSeconds: Int = DEFAULT_QUICK_SAVE_SECONDS,
    val silenceGate: String = encodeSilenceGate(SilenceGateConfig()),
)

private data class SettingsInitialConfiguration(
    val format: ExportFormat,
    val themeMode: AppThemeMode,
    val retention: RetentionConfiguration,
    val sampleFormat: PcmSampleFormat,
    val route: InputRouteMode,
    val source: AudioSourceMode,
    val channelMode: ChannelMode,
    val sampleRate: Int,
    val exportTreeUri: Uri?,
    val wakeLockEnabled: Boolean,
    val flacCompression: Int,
    val quickSaveEnabled: Boolean,
    val quickSaveSeconds: Int,
    val silence: SilenceGatePreferenceSnapshot,
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
    PrefKey.FLAC_COMPRESSION_LEVEL,
    PrefKey.QUICK_SAVE_ENABLED,
    PrefKey.QUICK_SAVE_SECONDS,
    PrefKey.SILENCE_GATE,
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
    isPanelActive: () -> Boolean = { active },
    onBack: () -> Unit = {},
    onThemeChanged: (AppThemeMode?) -> Unit = {},
    focusRetentionBuffer: ReverbService.BufferSlot? = null,
) {
    val context = LocalContext.current
    val settingsLifecycle = LocalLifecycleOwner.current.lifecycle
    val settingsView = androidx.compose.ui.platform.LocalView.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val chrome = appChrome()
    val topBarNoiseBrush = rememberAppNoiseBrush(APP_NOISE_SEED_TOP_BAR)

    var originalSnapshot by remember { mutableStateOf(SettingsSnapshot()) }
    var currentSnapshot by remember { mutableStateOf(SettingsSnapshot()) }
    var hasUnsavedChanges by remember { mutableStateOf(false) }
    var settingsPersisting by remember { mutableStateOf(false) }
    var settingsHydrated by remember { mutableStateOf(false) }
    var settingsInteractionReady by remember(active) { mutableStateOf(false) }
    val directoryPicker = rememberSaveable(
        saver = listSaver<SettingsDirectoryPickerState, Any>(
            save = { it.save() },
            restore = { SettingsDirectoryPickerState.restore(it) },
        ),
    ) { SettingsDirectoryPickerState(active) }
    DisposableEffect(active) {
        directoryPicker.setPanelActive(active)
        onDispose { directoryPicker.setPanelActive(false) }
    }

    var service by remember { mutableStateOf<ReverbService?>(null) }

    val moveAvailabilityRevision = remember { intArrayOf(0) }
    val settingsEditRevision = remember { longArrayOf(0L) }
    val outputSelectionRevision = remember { longArrayOf(0L) }
    val savePanelOwner = remember(active) { Any() }
    val savePanelOwnerState = androidx.compose.runtime.rememberUpdatedState(savePanelOwner)
    val notificationDurationInteraction = remember { NotificationDurationInteractionState() }
    val activeState = androidx.compose.runtime.rememberUpdatedState(active)
    val inputReadyState = androidx.compose.runtime.rememberUpdatedState(
        active && settingsHydrated && settingsInteractionReady,
    )
    val panelActiveState = androidx.compose.runtime.rememberUpdatedState(isPanelActive)
    val quickTileEditorState = rememberSaveable(
        saver = listSaver<QuickSettingsTilesEditorState, Any>(
            save = { it.save(activeState.value && panelActiveState.value()) },
            restore = QuickSettingsTilesEditorState::restore,
        ),
    ) { QuickSettingsTilesEditorState() }
    DisposableEffect(active, settingsHydrated, settingsInteractionReady, quickTileEditorState.mutationInProgress) {
        if (!active) quickTileEditorState.closeEditor()
        else quickTileEditorState.restorePendingDraft(settingsHydrated && settingsInteractionReady)
        onDispose { }
    }

    // Retained controls must not edit stale pixels while their fresh snapshot is loading.
    // An accepted save may still coexist with a newer draft, so persistence is not a gate.
    fun settingsInputReady(): Boolean = inputReadyState.value && settingsInteractionReady &&
        savePanelOwnerState.value === savePanelOwner && panelActiveState.value() && scope.isActive

    // Selected values
    var selectedFormat by remember { mutableStateOf(ExportFormat.WAV) }
    var selectedFlacCompression by remember { mutableIntStateOf(DEFAULT_FLAC_COMPRESSION) }
    var selectedQuickSaveEnabled by remember { mutableStateOf(true) }
    var selectedQuickSaveSeconds by remember { mutableIntStateOf(DEFAULT_QUICK_SAVE_SECONDS) }
    var selectedSilence by remember { mutableStateOf(SilenceGateConfig()) }
    var originalSilenceRaw by remember { mutableStateOf<String?>(null) }
    var calibrationResetRevision by remember { mutableIntStateOf(0) }
    var selectedTheme by remember { mutableStateOf(AppThemeMode.SYSTEM) }
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

    val moveAvailabilityContext = context.applicationContext
    val moveAvailabilityOwner = remember(scope, moveAvailabilityContext) {
        SettingsMoveAvailabilityOwner(
            scope = scope,
            scan = { target, mayStart ->
                RecordingRepository.hasMovableKnownRecordings(moveAvailabilityContext, target, mayStart)
            },
            publish = { canMove = it },
        )
    }

    fun refreshMoveRecordingsAvailability(forceRefresh: Boolean = false) {
        if (!activeState.value || !settingsInteractionReady ||
            !settingsLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            moveAvailabilityOwner.hide()
            return
        }
        // Capture the destination before enqueueing; later picker edits must not retarget a scan.
        val target = getOutputDirectoryId(moveAvailabilityContext, selectedExportTreeUri)
        if (forceRefresh) moveAvailabilityRevision[0]++
        moveAvailabilityOwner.request(target, moveAvailabilityRevision[0])
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

    fun currentSettingsSnapshot(wakeLockEnabled: Boolean = currentSnapshot.wakeLockEnabled) = SettingsSnapshot(
        themeMode = selectedTheme,
        retentionMode = activeRetentionMode,
        oneShotRetentionTime = oneShotRetentionTimeSecondsValue,
        oneShotRetentionSizeBytes = oneShotRetentionSizeBytesValue,
        loopingRetentionTime = loopingRetentionTimeSecondsValue,
        loopingRetentionSizeBytes = loopingRetentionSizeBytesValue,
        format = selectedFormat,
        codec = ExportCodec.PCM_16,
        sampleFormat = selectedSampleFormat,
        source = selectedSource,
        channelMode = selectedChannelMode,
        route = selectedRoute,
        sampleRate = selectedSampleRate,
        exportDirectoryUri = selectedExportTreeUri?.toString(),
        wakeLockEnabled = wakeLockEnabled,
        flacCompression = selectedFlacCompression,
        quickSaveEnabled = selectedQuickSaveEnabled,
        quickSaveSeconds = selectedQuickSaveSeconds,
        silenceGate = encodeSilenceGate(selectedSilence),
    )

    fun currentSettingsDraft(snapshot: SettingsSnapshot) = SettingsUiDraft(
        snapshot = snapshot,
        oneShotTimeText = oneShotRetentionTimeText,
        oneShotSizeText = oneShotRetentionSizeText,
        loopingTimeText = loopingRetentionTimeText,
        loopingSizeText = loopingRetentionSizeText,
    )

    val draftRestoration = rememberSaveable(
        saver = listSaver<SettingsDraftRestoration, Any>(
            save = {
                it.save(
                    panelActive = activeState.value && panelActiveState.value(),
                    hydrated = settingsHydrated,
                    persisting = settingsPersisting,
                    baseline = originalSnapshot,
                    currentDraft = { currentSettingsDraft(currentSettingsSnapshot()) },
                )
            },
            restore = SettingsDraftRestoration::restore,
        ),
    ) { SettingsDraftRestoration() }
    DisposableEffect(active) {
        if (!active) draftRestoration.discard()
        onDispose { }
    }

    fun recomputeUnsavedState() {
        hasUnsavedChanges = currentSettingsDraft(currentSnapshot).hasUnsavedChanges(originalSnapshot)
    }

    fun pushUndoState() {
        settingsEditRevision[0]++
        recomputeUnsavedState()
    }

    fun applyPendingDirectorySelection() {
        if (!isPanelActive() || !scope.isActive || savePanelOwnerState.value !== savePanelOwner) return
        // Hydration can finish before the readiness value reaches the next composition. This
        // accepted picker result needs the complete baseline, not a rendered control callback.
        val selected = directoryPicker.takePendingResult(
            hydrated = settingsHydrated && settingsInteractionReady,
        ) ?: return
        selectedExportTreeUri = Uri.parse(selected)
        refreshExportDirectoryUi()
        currentSnapshot = currentSettingsSnapshot()
        pushUndoState()
        refreshMoveRecordingsAvailability()
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

    val calibration = rememberSettingsSilenceCalibration(
        readOwner = { config, input ->
            if (isPanelActive() && settingsInteractionReady && !settingsPersisting &&
                config.enabled && selectedSilence == config &&
                currentSettingsSnapshot().silenceCalibrationInput() == input &&
                originalSnapshot.silenceCalibrationInput() == input
            ) settingsEditRevision[0] to calibrationResetRevision else null
        },
        onCalibrated = { config -> selectedSilence = config; finishSettingsEdit() },
    )
    LaunchedEffect(active, settingsInteractionReady, settingsPersisting,
        settingsEditRevision[0], calibrationResetRevision) { calibration.cancel() }
    LaunchedEffect(selectedSampleRate, selectedSampleFormat, selectedChannelMode,
        selectedSource, selectedRoute, calibrationResetRevision) {
        calibration.cancel(clearMeasurement = true)
    }
    BackHandler(enabled = active && quickTileEditorState.editorOpen) { quickTileEditorState.closeEditor() }

    fun featureOwnerReady(): Boolean = settingsInputReady() &&
        !settingsPersisting && scope.isActive &&
        settingsLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        settingsView.isAttachedToWindow

    fun featureInputReady(): Boolean = featureOwnerReady() && settingsView.hasWindowFocus()

    fun tileCapacityDraftReady(): Boolean =
        !settingsTileCapacityDraftChanged(originalSnapshot, currentSettingsSnapshot(),
            oneShotRetentionTimeText, loopingRetentionTimeText,
            oneShotRetentionSizeText, loopingRetentionSizeText)

    // A modal confirmation owns a different window; its own view supplies the focus guard.
    fun tileDraftRevision(): Long? = if (featureOwnerReady() && tileCapacityDraftReady())
        settingsEditRevision[0] else null

    fun tileDraftAdmission(): Long? = tileDraftRevision()?.takeIf { settingsView.hasWindowFocus() }

    fun activateRetentionMode(mode: RetentionMode) {
        if (!settingsInputReady()) return
        val change = settingsRetentionModeChange(activeRetentionMode, mode) ?: return
        // onValueChange keeps the active backing value current. Switching modes only
        // changes presentation; it must never reparse the rounded display string or erase drafts.
        activeRetentionMode = change.mode
        finishSettingsEdit(preserveActiveInputs = change.preserveInputDrafts)
    }

    fun restorePreviousSettings() {
        if (!hasUnsavedChanges) return
        draftRestoration.discard()
        directoryPicker.retire()
        settingsEditRevision[0]++
        val prev = originalSnapshot
        calibration.cancel(clearMeasurement = true)
        outputSelectionRevision[0]++
        selectedFormat = prev.format ?: ExportFormat.WAV
        selectedFlacCompression = prev.flacCompression
        selectedQuickSaveEnabled = prev.quickSaveEnabled
        selectedQuickSaveSeconds = prev.quickSaveSeconds
        selectedSilence = decodeSilenceGate(prev.silenceGate)
        calibrationResetRevision++
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
        selectedRoute = prev.route ?: availableRouteModes.first()
        selectedSampleFormat = prev.sampleFormat
        selectedSource = prev.source ?: availableSourceModes.first()
        selectedChannelMode = prev.channelMode ?: ChannelMode.MONO
        selectedSampleRate = prev.sampleRate.takeIf { it > 0 } ?: selectedSampleRate

        refreshSourceModes(
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

    fun currentSaveAdmission(): SettingsSaveAdmission? = if (!settingsInputReady() || quickTileEditorState.editorOpen || notificationDurationInteraction.pending) null else settingsSaveAdmission(
        active = isPanelActive() && settingsLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            settingsView.isAttachedToWindow && settingsView.hasWindowFocus(),
        ownerActive = scope.isActive,
        restorationConflict = false,
        panelOwner = savePanelOwnerState.value,
        editRevision = settingsEditRevision[0],
    )

    @SuppressLint("UseKtx") // commit() Boolean is required by the retention transaction.
    suspend fun persistSettings(): Boolean {
        if (settingsPersisting || calibration.running || quickTileEditorState.editorOpen) return false
        val preflightAdmission = currentSaveAdmission() ?: return false
        settingsPersisting = true
        try {
        oneShotRetentionTimeError = null
        oneShotRetentionSizeError = null
        loopingRetentionTimeError = null
        loopingRetentionSizeError = null

        when (readSettingsRetentionPreflight(
            admission = preflightAdmission,
            read = { retentionMutationIsSafe(context) },
            currentAdmission = ::currentSaveAdmission,
        )) {
            SettingsRetentionPreflightResult.SUPERSEDED -> return false
            SettingsRetentionPreflightResult.UNSAFE -> {
                AppFeedbackCenter.post(resources.getString(R.string.recorder_state_persist_failed), FeedbackTone.ERROR)
                return false
            }
            SettingsRetentionPreflightResult.READY -> Unit
        }

        val format = selectedFormat
        val codec = ExportCodec.PCM_16
        val sampleFormat = selectedSampleFormat
        if (format != originalSnapshot.format && format == ExportFormat.FLAC && sampleFormat != PcmSampleFormat.PCM_16) {
            AppFeedbackCenter.post(resources.getString(R.string.flac_pcm_required), FeedbackTone.ERROR)
            return false
        }
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
        val submittedOutputRevision = outputSelectionRevision[0]
        val submittedSilenceRaw = originalSilenceRaw
        var committedOutputFormat = format

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
            .putInt(PrefKey.OUTPUT_CODEC, codec.storageCode.toInt())
            .putInt(PrefKey.FLAC_COMPRESSION_LEVEL, submittedSnapshot.flacCompression)
            .putBoolean(PrefKey.QUICK_SAVE_ENABLED, submittedSnapshot.quickSaveEnabled)
            .putInt(PrefKey.QUICK_SAVE_SECONDS, submittedSnapshot.quickSaveSeconds)
            .putString(PrefKey.SILENCE_GATE, submittedSnapshot.silenceGate)
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
            processSettingsCommitBarrier.runAcceptedSave {
                val committed = withContext(Dispatchers.IO) {
                withRetentionPersistenceLock {
                    val settingsCommitted = SilenceGatePreferences.withSettingsTransaction(context, submittedSilenceRaw) {
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

                    // A replacement can sample SharedPreferences' attempted value during commit.
                    // Retain the authoritative pre-write theme even if this first save rolls back.
                    processCommittedTheme.initializeBaseline(getConfiguredThemeMode(context))

                    var transactionCommitted = false
                    try {
                        transactionCommitted = persistRetentionTransaction(
                            // Recovery is the write-ahead side of the transaction. If the process dies before
                            // preferences commit, restart sees a mismatch and existing history fails closed.
                            writeNewRecovery = { writeRetentionRecoveryConfiguration(context, retentionConfiguration) },
                            commitNewPreferences = {
                                // An untouched control follows a newer durable output preference;
                                // an explicit selection owns its value in this atomic transaction.
                                commitSettingsForSelectedOutput(
                                    originalFormat = previous.format ?: ExportFormat.WAV,
                                    selectedFormat = format,
                                    readCurrentFormat = { getConfiguredOutputFormat(context) },
                                    codec = codec,
                                    sampleRate = sampleRate,
                                    channelMode = channelMode,
                                    sampleFormat = sampleFormat,
                                    commit = { output ->
                                        committedOutputFormat = output
                                        settingsEditor.putInt(PrefKey.OUTPUT_FORMAT, output.storageCode.toInt()).commit()
                                    },
                                )
                            },
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
                    // Publish the exact durable value before releasing transaction ordering.
                    // Replacement Activities observe this independently of the old UI tail.
                    if (settingsCommitted) processCommittedTheme.publishCommitted(submittedSnapshot.themeMode)
                    settingsCommitted
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
        val editedWhileSaving = settingsEditedDuringPersistence(
            submittedRevision = submittedEditRevision,
            currentRevision = settingsEditRevision[0],
        )
        // Only the submitted snapshot became durable. If the user edited retained Settings
        // while IO was in flight, preserve those live inputs and keep them visibly unsaved.
        // Otherwise normalize presentation from the exact committed backing values.
        val durableSnapshot = submittedSnapshot.copy(format = committedOutputFormat)
        originalSnapshot = durableSnapshot
        originalSilenceRaw = submittedSnapshot.silenceGate
        selectedFormat = reconcileSettingsOutputAfterCommit(
            currentFormat = selectedFormat,
            committedFormat = committedOutputFormat,
            submittedOutputRevision = submittedOutputRevision,
            currentOutputRevision = outputSelectionRevision[0],
        )
        if (editedWhileSaving) {
            refreshRetentionFields(preserveActiveInputs = true)
            currentSnapshot = currentSettingsSnapshot()
            recomputeUnsavedState()
        } else {
            refreshRetentionFields(preserveActiveInputs = false)
            currentSnapshot = durableSnapshot
            hasUnsavedChanges = false
        }
        if (savePanelOwnerState.value === savePanelOwner && panelActiveState.value() &&
            scope.isActive && selectedTheme == durableSnapshot.themeMode
        ) {
            onThemeChanged(null)
        }
        val outputLimit = exportDurationLimitExactSeconds(
            committedOutputFormat, codec, sampleRate, channelMode.channelCount, sampleFormat,
        ).toInt().coerceAtMost(86_400)
        if (submittedSnapshot.quickSaveEnabled && submittedSnapshot.quickSaveSeconds > outputLimit) {
            AppFeedbackCenter.post(resources.getString(R.string.feature_duration_shortened,
                committedOutputFormat.name, formatRetentionTimeInput(outputLimit.toLong())))
        }
        return settingsSaveMayContinueAfterCommit(hasUnsavedChanges)
        } finally {
            settingsPersisting = false
        }
    }

    suspend fun bindUiFromPreferences(): Boolean {
        val hydrationEditRevision = settingsEditRevision[0]
        val initial = processSettingsCommitBarrier.readSettled {
            withContext(Dispatchers.IO) {
            withRetentionPersistenceLock {
            SettingsInitialConfiguration(
                format = getConfiguredOutputFormat(context),
                themeMode = getConfiguredThemeMode(context),
                retention = retentionConfigurationForRead(context),
                sampleFormat = getConfiguredPcmSampleFormat(context),
                route = getConfiguredInputRouteMode(context),
                source = getConfiguredAudioSourceMode(context),
                channelMode = getConfiguredChannelMode(context),
                sampleRate = getConfiguredSampleRate(context),
                exportTreeUri = getConfiguredExportTreeUriForSettings(context),
                wakeLockEnabled = isWakeLockEnabled(context),
                flacCompression = getConfiguredFlacCompression(context),
                quickSaveEnabled = getConfiguredQuickSaveEnabled(context),
                quickSaveSeconds = getConfiguredQuickSaveSeconds(context),
                silence = SilenceGatePreferences.read(context),
            )
            }
            }
        }
        val hydration = draftRestoration.hydrate(hydrationEditRevision, settingsEditRevision[0]) {
            val configuredThemeMode = initial.themeMode
            val retention = initial.retention
            val configuredMode = retention.mode
            val configuredOneShotTime = retention.oneShotSeconds
                .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            val configuredLoopingTime = retention.loopingSeconds
                .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            val storedOneShotSizeBytes = retention.oneShotSizeBytes
            val storedLoopingSizeBytes = retention.loopingSizeBytes
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

            calibration.cancel(clearMeasurement = true)
            outputSelectionRevision[0]++
            selectedFormat = initial.format
            selectedFlacCompression = initial.flacCompression
            selectedQuickSaveEnabled = initial.quickSaveEnabled
            selectedQuickSaveSeconds = initial.quickSaveSeconds
            selectedSilence = initial.silence.config
            originalSilenceRaw = initial.silence.raw
            calibrationResetRevision++
            selectedTheme = configuredThemeMode
            availableRouteModes = supportedInputRouteModes(context)
            selectedRoute = configuredRouteVal.takeIf { it in availableRouteModes } ?: availableRouteModes.first()

            selectedSampleFormat = configuredSampleFormatVal

            refreshSourceModes(
                preferredSource = configuredSourceVal,
                preferredChannelMode = configuredChannelModeVal,
                preferredRate = configuredRateVal,
            )
            refreshRetentionFields()
            refreshExportDirectoryUi()
            refreshBatteryOptimizationUi()

            currentSettingsDraft(currentSettingsSnapshot(initial.wakeLockEnabled))
        } ?: return false
        originalSnapshot = hydration.baseline
        if (hydration.restored) {
            val draft = hydration.displayed
            val values = draft.snapshot
            selectedTheme = values.themeMode
            activeRetentionMode = values.retentionMode
            oneShotRetentionTimeSecondsValue = values.oneShotRetentionTime
            oneShotRetentionSizeBytesValue = values.oneShotRetentionSizeBytes
            loopingRetentionTimeSecondsValue = values.loopingRetentionTime
            loopingRetentionSizeBytesValue = values.loopingRetentionSizeBytes
            selectedFormat = values.format ?: ExportFormat.WAV
            selectedFlacCompression = values.flacCompression
            selectedQuickSaveEnabled = values.quickSaveEnabled
            selectedQuickSaveSeconds = values.quickSaveSeconds
            selectedSilence = decodeSilenceGate(values.silenceGate)
            selectedSampleFormat = values.sampleFormat
            selectedRoute = values.route ?: InputRouteMode.AUTO
            selectedExportTreeUri = values.exportDirectoryUri?.let(Uri::parse)
            refreshSourceModes(values.source, values.channelMode, values.sampleRate)
            oneShotRetentionTimeText = draft.oneShotTimeText
            oneShotRetentionSizeText = draft.oneShotSizeText
            loopingRetentionTimeText = draft.loopingTimeText
            loopingRetentionSizeText = draft.loopingSizeText
            refreshRetentionFields(preserveActiveInputs = true)
            refreshExportDirectoryUi()
            // A restored explicit output edit remains newer than later save normalization.
            outputSelectionRevision[0]++
        }
        currentSnapshot = currentSettingsSnapshot(hydration.displayed.snapshot.wakeLockEnabled)
        recomputeUnsavedState()
        onThemeChanged(hydration.themePreview)
        settingsHydrated = true
        settingsInteractionReady = true
        applyPendingDirectorySelection()
        return true
    }

    val exportDirectoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (!isPanelActive()) directoryPicker.setPanelActive(false)
        if (treeUri == null || !directoryPicker.acceptsResult) {
            directoryPicker.receiveResult(null)
            return@rememberLauncherForActivityResult
        }
        val canonicalTree = canonicalDocumentTreeId(treeUri.toString())?.let(Uri::parse)
        if (canonicalTree == null) {
            directoryPicker.receiveResult(null)
            AppFeedbackCenter.post(resources.getString(R.string.cant_access_folder), FeedbackTone.ERROR)
            return@rememberLauncherForActivityResult
        }
        val permissionTaken = runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (!permissionTaken) {
            directoryPicker.receiveResult(null)
            AppFeedbackCenter.post(resources.getString(R.string.cant_access_folder), FeedbackTone.ERROR)
            return@rememberLauncherForActivityResult
        }
        directoryPicker.receiveResult(canonicalTree.toString())
        applyPendingDirectorySelection()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val refreshMoveAvailabilityState = androidx.compose.runtime.rememberUpdatedState {
        refreshMoveRecordingsAvailability()
    }
    DisposableEffect(moveAvailabilityOwner) {
        onDispose { moveAvailabilityOwner.close() }
    }
    DisposableEffect(active, settingsLifecycle, moveAvailabilityOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> refreshMoveAvailabilityState.value()
                Lifecycle.Event.ON_STOP -> moveAvailabilityOwner.hide()
                else -> Unit
            }
        }
        settingsLifecycle.addObserver(observer)
        if (!active) moveAvailabilityOwner.hide()
        onDispose {
            settingsLifecycle.removeObserver(observer)
            moveAvailabilityOwner.hide()
        }
    }
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
        if (!settingsInputReady()) return
        if (!openBatteryOptimizationReview(context)) {
            AppFeedbackCenter.post(resources.getString(R.string.no_app_available), FeedbackTone.ERROR)
        }
    }

    fun moveExistingRecordings() {
        if (!settingsInputReady() || settingsPersisting) return
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
            refreshMoveRecordingsAvailability(forceRefresh = true)
        }
    }

    fun releaseInputFocus() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    LaunchedEffect(active, settingsInteractionReady) {
        if (!active || !settingsInteractionReady) releaseInputFocus()
    }

    LaunchedEffect(active, settingsPersisting) {
        if (!active && !settingsPersisting) {
            // A save transaction owns the edited values until it completes. If it succeeds,
            // hasUnsavedChanges becomes false; if it fails, this reruns and restores the last
            // durable snapshot instead of leaving hidden retained UI with uncommitted values.
            if (hasUnsavedChanges) restorePreviousSettings()
            onThemeChanged(null)
            // Focus was released when this panel became inactive. A delayed save completion
            // must not clear focus now owned by another visible surface.
        }
    }
    LaunchedEffect(active, settingsPersisting) {
        // A reopened activation may inherit a draft owned by an accepted save. Once that
        // save ends, keep the draft and release this activation's readiness cell; an old
        // save callback still holds the retired cell and must not decide input ownership.
        if (settingsRetainedDraftMayReleaseInteraction(
                active = active,
                hydrated = settingsHydrated,
                persisting = settingsPersisting,
                hasUnsavedChanges = hasUnsavedChanges,
            )
        ) {
            settingsInteractionReady = true
            return@LaunchedEffect
        }
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
    LaunchedEffect(active, settingsInteractionReady, selectedExportTreeUri) {
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
            .focusProperties {
                onEnter = { if (!settingsInputReady()) cancelFocusChange() }
            }
            .focusGroup()
            .pointerInput(active, settingsInteractionReady) {
                if (!settingsInputReady()) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .semantics { if (!settingsInputReady()) hideFromAccessibility() },
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .appNoise(topBarNoiseBrush),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                title = {
                    Text(stringResource(R.string.settings_title))
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!settingsInputReady()) return@IconButton
                        if (quickTileEditorState.editorOpen) {
                            quickTileEditorState.closeEditor()
                        } else if (calibration.running) {
                            calibration.cancel()
                        } else if (hasUnsavedChanges) {
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
                            if (!settingsInputReady() || !hasUnsavedChanges || settingsPersisting || calibration.running || quickTileEditorState.editorOpen || notificationDurationInteraction.pending) return@IconButton
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                runSettingsSaveContinuation(
                                    submittedOwner = savePanelOwner,
                                    persistSettings = ::persistSettings,
                                    currentVisibleOwner = {
                                        savePanelOwnerState.value.takeIf {
                                            activeState.value && panelActiveState.value() && scope.isActive
                                        }
                                    },
                                    onSaved = {
                                        releaseInputFocus()
                                        onBack()
                                    },
                                )
                            }
                        },
                        enabled = hasUnsavedChanges && !settingsPersisting && !calibration.running && !quickTileEditorState.editorOpen && !notificationDurationInteraction.pending,
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
                        SettingsSegmentedControl(
                            items = AppThemeMode.entries,
                            selected = selectedTheme,
                            onSelected = edit@{ theme ->
                                if (!settingsInputReady()) return@edit
                                if (theme != selectedTheme) {
                                    selectedTheme = theme
                                    onThemeChanged(theme)
                                    currentSnapshot = currentSettingsSnapshot()
                                    pushUndoState()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { theme, contentColor ->
                            val icon = when (theme) {
                                AppThemeMode.SYSTEM -> AppIcons.themeSystem
                                AppThemeMode.LIGHT -> AppIcons.themeLight
                                AppThemeMode.DARK -> AppIcons.themeDark
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(icon, null, Modifier.size(16.dp), contentColor)
                                Text(
                                    stringResource(theme.labelRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            item(key = "retention") {
                RetentionSection(
                    activeMode = activeRetentionMode,
                    focusBuffer = focusRetentionBuffer.takeIf { settingsInputReady() },
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
                    onOneShotTimeChange = edit@{ value ->
                        if (!settingsInputReady()) return@edit
                        oneShotRetentionTimeText = value
                        oneShotRetentionTimeError = null
                        parseRetentionTimeSeconds(value)?.let { oneShotRetentionTimeSecondsValue = it }
                        finishSettingsEdit()
                    },
                    onOneShotSizeChange = edit@{ value ->
                        if (!settingsInputReady()) return@edit
                        oneShotRetentionSizeText = value
                        oneShotRetentionSizeError = null
                        parseRetentionSizeMib(value)?.takeIf { it >= 0.0 }
                            ?.let { oneShotRetentionSizeBytesValue = rawMegabytesToBytes(it) }
                        finishSettingsEdit()
                    },
                    onLoopingTimeChange = edit@{ value ->
                        if (!settingsInputReady()) return@edit
                        loopingRetentionTimeText = value
                        loopingRetentionTimeError = null
                        parseRetentionTimeSeconds(value)?.let { loopingRetentionTimeSecondsValue = it }
                        finishSettingsEdit()
                    },
                    onLoopingSizeChange = edit@{ value ->
                        if (!settingsInputReady()) return@edit
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
                    val showChannels = availableChannelModes.size > 1
                    val showSampleFormat = PcmSampleFormat.entries.size > 1
                    val showSampleRate = availableSampleRates.size > 1
                    val showSource = availableSourceModes.size > 1
                    val showRoute = availableRouteModes.size > 1
                    SettingsAudioGroup {
                        if (showChannels) {
                            SettingsDropdown(
                                active = active,
                                label = stringResource(R.string.channel_mode_label),
                                selectedValue = selectedChannelMode,
                                options = availableChannelModes,
                                optionLabel = { resources.getString(it.labelRes) },
                                onOptionSelected = edit@{ channelMode ->
                                    if (!settingsInputReady()) return@edit
                                    selectedChannelMode = channelMode
                                    refreshSampleRates(selectedSampleRate)
                                    finishSettingsEdit()
                                },
                            )
                        }
                        if (showSampleFormat || showSampleRate) {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                                    .settingsAudioDivider(top = showChannels),
                            ) {
                                if (showSampleFormat) {
                                    SettingsDropdown(
                                        active = active,
                                        label = stringResource(R.string.sample_format_label),
                                        selectedValue = selectedSampleFormat,
                                        options = PcmSampleFormat.entries,
                                        optionLabel = { resources.getString(it.labelRes) },
                                        onOptionSelected = edit@{ sampleFormat ->
                                            if (!settingsInputReady()) return@edit
                                            selectedSampleFormat = sampleFormat
                                            refreshSourceModes(
                                                preferredSource = selectedSource,
                                                preferredChannelMode = selectedChannelMode,
                                                preferredRate = selectedSampleRate,
                                            )
                                            finishSettingsEdit()
                                        },
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                                if (showSampleRate) {
                                    SettingsDropdown(
                                        active = active,
                                        label = stringResource(R.string.sample_rate_label),
                                        selectedValue = selectedSampleRate,
                                        options = availableSampleRates,
                                        optionLabel = ::sampleRateLabel,
                                        onOptionSelected = edit@{ sampleRate ->
                                            if (!settingsInputReady()) return@edit
                                            selectedSampleRate = sampleRate
                                            finishSettingsEdit()
                                        },
                                        modifier = Modifier.weight(1f)
                                            .fillMaxHeight()
                                            .settingsAudioDivider(start = showSampleFormat),
                                    )
                                }
                            }
                        }
                        if (showSource || showRoute) {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                                    .settingsAudioDivider(
                                        top = showChannels || showSampleFormat || showSampleRate,
                                    ),
                            ) {
                                if (showSource) {
                                    SettingsDropdown(
                                        active = active,
                                        label = stringResource(R.string.audio_source_label),
                                        selectedValue = selectedSource,
                                        options = availableSourceModes,
                                        optionLabel = { resources.getString(it.labelRes) },
                                        onOptionSelected = edit@{ source ->
                                            if (!settingsInputReady()) return@edit
                                            selectedSource = source
                                            refreshChannelModes(selectedChannelMode, selectedSampleRate)
                                            finishSettingsEdit()
                                        },
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                                if (showRoute) {
                                    SettingsDropdown(
                                        active = active,
                                        label = stringResource(R.string.input_route_label),
                                        selectedValue = selectedRoute,
                                        options = availableRouteModes,
                                        optionLabel = { resources.getString(it.labelRes) },
                                        onOptionSelected = edit@{ route ->
                                            if (!settingsInputReady()) return@edit
                                            selectedRoute = route
                                            refreshSourceModes(
                                                preferredSource = selectedSource,
                                                preferredChannelMode = selectedChannelMode,
                                                preferredRate = selectedSampleRate,
                                            )
                                            finishSettingsEdit()
                                        },
                                        modifier = Modifier.weight(1f)
                                            .fillMaxHeight()
                                            .settingsAudioDivider(start = showSource),
                                    )
                                }
                            }
                        }
                        OutputCompressionSettings(
                            enabled = selectedFormat == ExportFormat.FLAC,
                            level = selectedFlacCompression,
                            onEnabledChange = { enabled ->
                                if (!featureInputReady()) return@OutputCompressionSettings
                                outputSelectionRevision[0]++
                                selectedFormat = if (enabled) ExportFormat.FLAC else ExportFormat.WAV
                                finishSettingsEdit()
                            },
                            onLevelChange = { if (featureInputReady()) { selectedFlacCompression = it; finishSettingsEdit() } },
                            interactionEnabled = settingsInteractionReady && !settingsPersisting,
                            modifier = Modifier.settingsAudioDivider(top = true),
                        )
                        if (selectedFormat == ExportFormat.FLAC && selectedSampleFormat != PcmSampleFormat.PCM_16) {
                            Text(stringResource(R.string.flac_pcm_required),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        val renderedSilence = selectedSilence
                        val renderedSilenceRevision = settingsEditRevision[0]
                        SkipSilenceSettings(
                            config = renderedSilence,
                            onConfigChange = {
                                if (featureInputReady() && selectedSilence == renderedSilence &&
                                    settingsEditRevision[0] == renderedSilenceRevision) {
                                    selectedSilence = it
                                    finishSettingsEdit()
                                }
                            },
                            onCalibrate = { calibration.beginTimed(selectedSilence, originalSnapshot.silenceCalibrationInput()) },
                            onBeginHeldCalibration = {
                                // Recheck the controller synchronously: a keyboard click can have
                                // started sampling before Compose refreshes pointer eligibility.
                                if (calibration.running) false else {
                                    calibration.beginHeld(selectedSilence, originalSnapshot.silenceCalibrationInput())
                                    calibration.running && calibration.holding
                                }
                            },
                            onCalibrationTap = { calibration.completeTap() },
                            onFinishHeldCalibration = { calibration.finishHeld() },
                            onCancelCalibration = { calibration.cancel() },
                            enabled = settingsInteractionReady && !settingsPersisting,
                            maximumHz = minOf(SILENCE_MAX_HZ, selectedSampleRate / 2),
                            sampledPeaks = calibration.measured?.peaks,
                            calibrationAllowed = originalSnapshot.silenceCalibrationInput() == currentSettingsSnapshot().silenceCalibrationInput(),
                            calibrating = calibration.running,
                            holdingCalibration = calibration.holding,
                            calibrationProgress = calibration.progress,
                            calibrationError = calibration.error,
                            calibrationUnavailableReason = resources.getString(R.string.silence_apply_input_first),
                            modifier = Modifier.settingsAudioDivider(top = true),
                        )
                        NotificationSaveSettings(
                            enabled = selectedQuickSaveEnabled,
                            seconds = selectedQuickSaveSeconds,
                            maxSeconds = exportDurationLimitExactSeconds(selectedFormat, ExportCodec.PCM_16,
                                selectedSampleRate, selectedChannelMode.channelCount, selectedSampleFormat)
                                .toInt().coerceIn(1, 86_400),
                            onEnabledChange = { if (featureInputReady()) { selectedQuickSaveEnabled = it; finishSettingsEdit() } },
                            onSecondsChange = { selectedQuickSaveSeconds = it; finishSettingsEdit() },
                            interactionReady = settingsInteractionReady && !settingsPersisting,
                            interactionState = notificationDurationInteraction,
                            editRevision = settingsEditRevision[0],
                            readEditRevision = {
                                if (featureInputReady() && selectedQuickSaveEnabled)
                                    settingsEditRevision[0] else null
                            },
                            modifier = Modifier.settingsAudioDivider(top = true),
                        )
                    }
                }
            }

            item(key = "quick-save-tiles") {
                QuickSettingsTilesControls(
                    active = active,
                    interactionReady = settingsInteractionReady && !settingsPersisting,
                    durationSubmissionAllowed = tileCapacityDraftReady(),
                    editorState = quickTileEditorState,
                    editRevision = settingsEditRevision[0],
                    readEditRevision = ::tileDraftAdmission,
                    readSheetEditRevision = ::tileDraftRevision,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
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
                            val storageActionShape = RoundedCornerShape(14.dp)
                            Surface(
                                onClick = {
                                    if (!settingsInputReady()) return@Surface
                                    directoryPicker.retire()
                                    selectedExportTreeUri = null
                                    refreshExportDirectoryUi()
                                    refreshMoveRecordingsAvailability()
                                    currentSnapshot = currentSettingsSnapshot()
                                    pushUndoState()
                                },
                                modifier = Modifier.size(48.dp),
                                shape = storageActionShape,
                                color = chrome.raised,
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = AppIcons.reset,
                                        contentDescription = stringResource(R.string.default_folder),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Surface(
                            onClick = {
                                if (settingsInputReady() && directoryPicker.beginRequest()) {
                                    try {
                                        exportDirectoryLauncher.launch(selectedExportTreeUri)
                                    } catch (_: RuntimeException) {
                                        directoryPicker.receiveResult(null)
                                        AppFeedbackCenter.post(resources.getString(R.string.cant_access_folder), FeedbackTone.ERROR)
                                    }
                                }
                            },
                            modifier = Modifier.size(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = chrome.raised,
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = AppIcons.folder,
                                    contentDescription = stringResource(R.string.choose_folder),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = { moveExistingRecordings() },
                        enabled = canMove && !settingsPersisting,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = chrome.raised,
                            disabledContainerColor = chrome.raised,
                        ),
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
                        checked = currentSnapshot.wakeLockEnabled,
                        onCheckedChange = edit@{ enabled ->
                            if (!settingsInputReady()) return@edit
                            currentSnapshot = currentSettingsSnapshot(enabled)
                            pushUndoState()
                        },
                        trailing = { interactionSource ->
                            ReverbSwitch(
                                checked = currentSnapshot.wakeLockEnabled,
                                interactionSource = interactionSource,
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
    interactionSource: MutableInteractionSource,
) {
    val colors = MaterialTheme.colorScheme
    val chrome = appChrome()
    val pressed by interactionSource.collectIsPressedAsState()
    val pressedOverlayAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.12f else 0f,
        label = "reverbSwitchPressedOverlay",
    )
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
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        val trackShape = RoundedCornerShape(13.dp)
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 26.dp)
                .clip(trackShape)
                .background(trackColor),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.onSurface.copy(alpha = pressedOverlayAlpha)),
            )
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .fillMaxSize(),
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
}

@Composable
private fun ReliabilityRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    trailing: @Composable (MutableInteractionSource) -> Unit,
) {
    val chrome = appChrome()
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = chrome.field,
        border = BorderStroke(1.dp, chrome.border),
    ) {
        Row(
            modifier = Modifier
                .toggleable(
                    value = checked,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
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
            trailing(interactionSource)
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
            SettingsSegmentedControl(
                items = RETENTION_MODE_OPTIONS,
                selected = activeMode,
                onSelected = onModeSelected,
                modifier = Modifier.width(126.dp),
            ) { mode, contentColor ->
                Text(
                    text = stringResource(
                        if (mode == RetentionMode.TIME) R.string.retention_time_label
                        else R.string.retention_size_mode_label,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    maxLines = 1,
                )
            }
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
private fun <T> SettingsSegmentedControl(
    items: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (item: T, contentColor: Color) -> Unit,
) {
    val chrome = appChrome()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        color = chrome.field,
        border = BorderStroke(1.dp, chrome.border),
    ) {
        Row(Modifier.padding(4.dp).selectableGroup()) {
            items.forEach { item ->
                val isSelected = item == selected
                val backgroundColor by animateColorAsState(
                    if (isSelected) chrome.raised else Color.Transparent,
                    label = "settings-segment-background",
                )
                val contentColor by animateColorAsState(
                    if (isSelected) chrome.ink else chrome.muted,
                    label = "settings-segment-content",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(backgroundColor)
                        .selectable(
                            selected = isSelected,
                            onClick = { onSelected(item) },
                            role = Role.RadioButton,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    content(item, contentColor)
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
internal fun SettingsAudioGroup(content: @Composable ColumnScope.() -> Unit) {
    val chrome = appChrome()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = chrome.field,
        border = BorderStroke(1.dp, chrome.border),
    ) {
        Column(content = content)
    }
}

@Composable
internal fun Modifier.settingsAudioDivider(top: Boolean = false, start: Boolean = false): Modifier {
    if (!top && !start) return this
    val color = appChrome().border
    return drawWithContent {
        drawContent()
        if (top) {
            drawLine(color, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1f)
        }
        if (start) {
            val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) 0.5f else size.width - 0.5f
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
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

    Box(modifier = modifier, propagateMinConstraints = true) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape,
            color = containerColor,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
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
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)),
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


private fun bytesToMegabytes(bytes: Long): Double =
    bytes.coerceAtLeast(0L) / BYTES_IN_MEGABYTE.toDouble()

internal fun rawMegabytesToBytes(memoryInMegabytes: Double): Long {
    if (memoryInMegabytes <= 0.0) return 0L
    if (memoryInMegabytes >= Long.MAX_VALUE / BYTES_IN_MEGABYTE.toDouble()) return Long.MAX_VALUE
    return (memoryInMegabytes * BYTES_IN_MEGABYTE.toDouble()).roundToLong()
}

internal fun parseRetentionSizeMib(value: String): Double? =
    value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

internal fun formatRetentionSizeBytes(bytes: Long): String =
    retentionSizeFormatter.format(bytesToMegabytes(bytes))
