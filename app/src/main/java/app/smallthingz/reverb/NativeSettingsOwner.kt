package app.smallthingz.reverb

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Released Settings effects/actions owned by one retained native panel. The existing draft,
 * transaction, calibration, directory request and tile editor remain their respective authorities.
 * The host owns the registered directory launcher, keyboard/focus release and screen navigation.
 */
internal class NativeSettingsOwner(
    private val context: Context,
    private val view: View,
    private val lifecycle: Lifecycle,
    parentScope: CoroutineScope,
    initiallyActive: Boolean,
    savedDraft: List<Any> = emptyList(),
    savedDirectoryPicker: List<Any>? = null,
    savedTileEditor: List<Any>? = null,
    private val isPanelActive: () -> Boolean,
    onThemePreview: (AppThemeMode?) -> Unit,
    private val releaseInputFocus: () -> Unit,
    private val launchDirectory: (Uri?) -> Unit,
    private val onBack: () -> Unit,
) : AutoCloseable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    val scope = CoroutineScope(parentScope.coroutineContext + job)
    private var closed = false
    private var started = false
    private var changed: () -> Unit = {}
    private var draftReconciliationDepth = 0
    private var previousPresentation: SettingsDraftPresentation? = null
    private var hydrationKey: Pair<Boolean, Boolean>? = null
    private var hydrationJob: Job? = null
    private var moveRevision = 0
    private var batteryObserverInstalled = false
    private var routes = supportedInputRouteModes(context)
    var audioOptions = NativeSettingsAudioOptions(
        routes, AudioSourceMode.availableModes(), ChannelMode.entries, standardSampleRates(),
    )
        private set
    var errors = NativeSettingsRetentionErrors()
        private set
    var exportPathText = ""
        private set
    var canMove = false
        private set
    var batteryOptimizationRestricted = !isIgnoringBatteryOptimizations(context)
        private set
    val notificationDurationInteraction = NotificationDurationInteractionState()
    val quickTileEditorState = savedTileEditor?.let(QuickSettingsTilesEditorState::restore)
        ?: QuickSettingsTilesEditorState()
    val draft = SettingsDraftController(
        initiallyActive = initiallyActive,
        savedDraft = savedDraft,
        savedDirectoryPicker = savedDirectoryPicker,
        isPanelActive = isPanelActive,
        onChanged = ::draftChanged,
        onThemePreview = onThemePreview,
        onChildEditorReadiness = { active, ready ->
            if (!active) quickTileEditorState.closeEditor()
            else quickTileEditorState.restorePendingDraft(ready)
            // The controller calls this after draftChanged; publish once with child state settled.
            notifyChanged()
        },
    )
    val state get() = draft.state
    val snapshot get() = state.displayed.snapshot
    val estimates get() = nativeSettingsRetentionEstimates(snapshot)
    private val serviceBinding = NativeSettingsServiceBinding(context, lifecycle)
    private val persistence = NativeSettingsPersistence(context) { serviceBinding.service }
    private val moveAvailability = SettingsMoveAvailabilityOwner(
        scope,
        scan = { target, mayStart ->
            RecordingRepository.hasMovableKnownRecordings(context.applicationContext, target, mayStart)
        },
        publish = { canMove = it; notifyChanged() },
    )
    private val calibrationBinding = NativeSettingsCalibrationBinding(
        context, scope, view, lifecycle,
        readOwner = { config, input ->
            val current = state
            if (isPanelActive() && current.interactionReady && !current.persisting &&
                config.enabled && decodeSilenceGate(current.displayed.snapshot.silenceGate) == config &&
                current.displayed.snapshot.silenceCalibrationInput() == input &&
                current.baseline.silenceCalibrationInput() == input
            ) current.editRevision to current.calibrationResetRevision else null
        },
        onCalibrated = { config, histogram ->
            editSnapshot(snapshot.copy(
                silenceGate = encodeSilenceGate(config),
                silenceHistogram = encodeSilenceCalibrationHistogram(histogram),
            ))
        },
        onChanged = ::notifyChanged,
    )
    val calibration get() = calibrationBinding.controller

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> refreshMoveAvailability()
            Lifecycle.Event.ON_STOP -> moveAvailability.hide()
            Lifecycle.Event.ON_RESUME -> if (batteryObserverInstalled) refreshBattery()
            else -> Unit
        }
    }

    /** Called after the renderer and registered Activity Result callback exist. */
    fun start(onChanged: () -> Unit) {
        check(!closed)
        changed = onChanged
        if (!started) {
            started = true
            lifecycle.addObserver(lifecycleObserver)
            batteryObserverInstalled = true
            draftChanged(state)
        }
        notifyChanged()
    }

    fun setActive(active: Boolean) {
        if (closed) return
        draft.setActive(active)
    }

    fun acceptsInput(renderedActivation: Any = state.activation): Boolean =
        scope.isActive && draft.acceptsInput(renderedActivation, isPanelActive())

    fun featureOwnerReady(renderedActivation: Any = state.activation): Boolean = acceptsInput(renderedActivation) && !state.persisting &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && view.isAttachedToWindow

    fun featureInputReady(renderedActivation: Any = state.activation): Boolean = featureOwnerReady(renderedActivation) && view.hasWindowFocus()

    fun tileCapacityDraftReady(): Boolean {
        val current = state
        val values = current.displayed
        return !settingsTileCapacityDraftChanged(current.baseline, values.snapshot,
            values.oneShotTimeText, values.loopingTimeText, values.oneShotSizeText, values.loopingSizeText)
    }

    fun tileActionRevision(): Long? {
        val current = state
        return current.editRevision.takeIf { featureOwnerReady(current.activation) }
    }
    fun tileActionAdmission(): Long? = tileActionRevision()?.takeIf { view.hasWindowFocus() }
    fun tileDraftRevision(): Long? = tileActionRevision()?.let {
        settingsTileDurationRevision(it, tileCapacityDraftReady())
    }
    fun tileDraftAdmission(): Long? = tileDraftRevision()?.takeIf { view.hasWindowFocus() }
    fun notificationEditRevision(): Long? {
        val current = state
        return current.editRevision.takeIf { featureInputReady(current.activation) && snapshot.quickSaveEnabled }
    }

    /** Wheel/tile owners call this after their complete state transition, not on every draw. */
    fun childInteractionChanged() {
        if (closed) return
        val current = state
        if (!current.active) quickTileEditorState.closeEditor()
        else quickTileEditorState.restorePendingDraft(current.hydrated && current.interactionReady)
        notifyChanged()
    }

    private fun currentSaveAdmission(): SettingsSaveAdmission? {
        if (!acceptsInput() || quickTileEditorState.editorOpen || notificationDurationInteraction.pending) return null
        val current = state
        return settingsSaveAdmission(
            active = isPanelActive() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                view.isAttachedToWindow && view.hasWindowFocus(),
            ownerActive = scope.isActive,
            restorationConflict = false,
            panelOwner = current.activation,
            editRevision = current.editRevision,
        )
    }

    suspend fun persistSettings(): Boolean {
        if (state.persisting || calibration.running || quickTileEditorState.editorOpen) return false
        val admission = currentSaveAdmission() ?: return false
        val attempt = draft.beginPersistence() ?: return false
        try {
            errors = NativeSettingsRetentionErrors()
            notifyChanged()
            when (readSettingsRetentionPreflight(admission, { retentionMutationIsSafe(context) }, ::currentSaveAdmission)) {
                SettingsRetentionPreflightResult.SUPERSEDED -> return false
                SettingsRetentionPreflightResult.UNSAFE -> {
                    feedback(R.string.recorder_state_persist_failed)
                    return false
                }
                SettingsRetentionPreflightResult.READY -> Unit
            }
            val current = state
            val validated = validateNativeSettingsSave(current.baseline, current.displayed)
            errors = validated.errors
            validated.feedback?.let(::feedback)
            val submittedDraft = validated.draft ?: run { notifyChanged(); return false }
            val submission = draft.submission(attempt, submittedDraft)
            val committedFormat = persistence.persist(current.baseline, submission) ?: return false
            val mayContinue = draft.committed(submission, committedFormat)
            val saved = submission.snapshot
            val outputLimit = exportDurationLimitExactSeconds(
                committedFormat, ExportCodec.PCM_16, saved.sampleRate,
                requireNotNull(saved.channelMode).channelCount, saved.sampleFormat,
            ).toInt().coerceAtMost(86_400)
            if (saved.quickSaveEnabled && saved.quickSaveSeconds > outputLimit) {
                AppFeedbackCenter.post(context.getString(R.string.feature_duration_shortened,
                    committedFormat.name, formatRetentionTimeInput(outputLimit.toLong())))
            }
            return mayContinue
        } finally {
            draft.finishPersistence(attempt)
        }
    }

    fun done(renderedActivation: Any) {
        val current = state
        if (!acceptsInput(renderedActivation) || !current.hasUnsavedChanges || current.persisting ||
            calibration.running || quickTileEditorState.editorOpen || notificationDurationInteraction.pending) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runSettingsSaveContinuation(
                submittedOwner = renderedActivation,
                persistSettings = ::persistSettings,
                currentVisibleOwner = {
                    val visible = state
                    visible.activation.takeIf { visible.active && isPanelActive() && scope.isActive }
                },
                onSaved = { releaseInputFocus(); onBack() },
            )
        }
    }

    fun navigateBack(renderedActivation: Any) {
        if (!acceptsInput(renderedActivation)) return
        when {
            quickTileEditorState.editorOpen -> { quickTileEditorState.closeEditor(); notifyChanged() }
            calibration.running -> calibration.cancel()
            state.hasUnsavedChanges -> undo()
            else -> { releaseInputFocus(); onBack() }
        }
    }

    /** Released BackHandler has child-editor precedence; surrounding panel owns ordinary Back. */
    fun closeTileEditorOnBack(): Boolean {
        if (!state.active || !quickTileEditorState.editorOpen) return false
        quickTileEditorState.closeEditor()
        notifyChanged()
        return true
    }

    private fun undo() {
        val current = state
        if (!current.hasUnsavedChanges) return
        errors = NativeSettingsRetentionErrors()
        audioOptions = nativeSettingsAudioOptions(current.baseline, routes)
        draft.undo()
        refreshBattery()
        refreshMoveAvailability()
    }

    fun themeChanged(owner: Any, value: AppThemeMode) {
        if (!acceptsInput(owner)) return
        val current = snapshot
        if (value == current.themeMode) return
        editSnapshot(current.copy(themeMode = value))
        draft.previewTheme(value)
    }

    fun retentionModeChanged(owner: Any, mode: RetentionMode) {
        if (!acceptsInput(owner)) return
        val current = snapshot
        val change = settingsRetentionModeChange(current.retentionMode, mode) ?: return
        editSnapshot(current.copy(retentionMode = change.mode))
    }

    fun oneShotTimeChanged(owner: Any, raw: String) {
        if (!acceptsInput(owner)) return
        errors = errors.copy(oneShotTime = null)
        val values = state.displayed
        draft.edited(values.copy(oneShotTimeText = raw, snapshot = values.snapshot.copy(
            oneShotRetentionTime = parseRetentionTimeSeconds(raw) ?: values.snapshot.oneShotRetentionTime)))
    }

    fun loopingTimeChanged(owner: Any, raw: String) {
        if (!acceptsInput(owner)) return
        errors = errors.copy(loopingTime = null)
        val values = state.displayed
        draft.edited(values.copy(loopingTimeText = raw, snapshot = values.snapshot.copy(
            loopingRetentionTime = parseRetentionTimeSeconds(raw) ?: values.snapshot.loopingRetentionTime)))
    }

    fun oneShotSizeChanged(owner: Any, raw: String) {
        if (!acceptsInput(owner)) return
        errors = errors.copy(oneShotSize = null)
        val values = state.displayed
        val bytes = parseRetentionSizeMib(raw)?.takeIf { it >= 0.0 }?.let(::rawMegabytesToBytes)
        draft.edited(values.copy(oneShotSizeText = raw, snapshot = values.snapshot.copy(
            oneShotRetentionSizeBytes = bytes ?: values.snapshot.oneShotRetentionSizeBytes)))
    }

    fun loopingSizeChanged(owner: Any, raw: String) {
        if (!acceptsInput(owner)) return
        errors = errors.copy(loopingSize = null)
        val values = state.displayed
        val bytes = parseRetentionSizeMib(raw)?.takeIf { it >= 0.0 }?.let(::rawMegabytesToBytes)
        draft.edited(values.copy(loopingSizeText = raw, snapshot = values.snapshot.copy(
            loopingRetentionSizeBytes = bytes ?: values.snapshot.loopingRetentionSizeBytes)))
    }

    fun channelChanged(owner: Any, value: ChannelMode) {
        if (!acceptsInput(owner)) return
        val next = snapshot.copy(channelMode = value)
        audioOptions = nativeSettingsAudioOptions(next, routes)
        editSnapshot(next)
    }

    fun sampleFormatChanged(owner: Any, value: PcmSampleFormat) {
        if (!acceptsInput(owner)) return
        editResolvedAudio(snapshot.copy(sampleFormat = value))
    }

    fun sampleRateChanged(owner: Any, value: Int) {
        if (acceptsInput(owner)) editSnapshot(snapshot.copy(sampleRate = value))
    }

    fun sourceChanged(owner: Any, value: AudioSourceMode) {
        if (!acceptsInput(owner)) return
        editResolvedAudio(snapshot.copy(source = value))
    }

    fun routeChanged(owner: Any, value: InputRouteMode) {
        if (!acceptsInput(owner)) return
        editResolvedAudio(snapshot.copy(route = value))
    }

    fun outputCompressionChanged(owner: Any, enabled: Boolean) {
        if (featureInputReady(owner)) editSnapshot(
            snapshot.copy(format = if (enabled) ExportFormat.FLAC else ExportFormat.WAV),
            explicitOutputChoice = true,
        )
    }

    fun compressionLevelChanged(owner: Any, level: Int) {
        if (featureInputReady(owner)) editSnapshot(snapshot.copy(flacCompression = level))
    }

    fun silenceChanged(owner: Any, rendered: SilenceGateConfig, revision: Long, next: SilenceGateConfig) {
        if (!featureInputReady(owner)) return
        val current = state
        val value = current.displayed.snapshot
        if (decodeSilenceGate(value.silenceGate) == rendered && current.editRevision == revision)
            editSnapshot(value.copy(silenceGate = encodeSilenceGate(next)))
    }

    fun beginTimedCalibration() {
        val current = state
        calibration.beginTimed(decodeSilenceGate(current.displayed.snapshot.silenceGate),
            current.baseline.silenceCalibrationInput())
    }
    fun beginHeldCalibration(): Boolean {
        if (calibration.running) return false
        val current = state
        calibration.beginHeld(decodeSilenceGate(current.displayed.snapshot.silenceGate), current.baseline.silenceCalibrationInput())
        return calibration.running && calibration.holding
    }

    fun notificationEnabledChanged(owner: Any, enabled: Boolean) {
        if (featureInputReady(owner)) editSnapshot(snapshot.copy(quickSaveEnabled = enabled))
    }

    /** The wheel publishes after its binding accepts the receipt, including terminal/error paths. */
    fun notificationSecondsChanged(seconds: Int) {
        // Rebinding this wheel synchronously would invalidate its own admitted commit.
        // Keep the draft/revision current, then let childInteractionChanged publish the result.
        draftReconciliationDepth++
        try {
            editSnapshot(snapshot.copy(quickSaveSeconds = seconds))
        } finally {
            draftReconciliationDepth--
        }
    }

    fun wakeLockChanged(owner: Any, enabled: Boolean) {
        if (acceptsInput(owner)) editSnapshot(snapshot.copy(wakeLockEnabled = enabled))
    }

    private fun editSnapshot(next: SettingsSnapshot, explicitOutputChoice: Boolean = false) {
        draft.edited(state.displayed.copy(snapshot = next), explicitOutputChoice)
    }

    private fun editResolvedAudio(next: SettingsSnapshot) {
        val current = state.displayed
        val resolved = resolveNativeSettingsRestoredDraft(current.copy(snapshot = next), routes,
            fallbackRate = current.snapshot.sampleRate)
        audioOptions = nativeSettingsAudioOptions(resolved.snapshot, routes)
        draft.edited(resolved)
    }

    fun useDefaultDirectory(owner: Any) {
        if (!acceptsInput(owner)) return
        draft.directoryPicker.retire()
        editSnapshot(snapshot.copy(exportDirectoryUri = null))
    }

    fun chooseDirectory(owner: Any) {
        if (!acceptsInput(owner) || !draft.directoryPicker.beginRequest()) return
        try {
            launchDirectory(snapshot.exportDirectoryUri?.let(Uri::parse))
        } catch (_: RuntimeException) {
            draft.directoryPicker.receiveResult(null)
            feedback(R.string.cant_access_folder)
        }
    }

    /** Registered launcher calls this even when its originating activation has retired. */
    fun receiveDirectoryResult(treeUri: Uri?) {
        if (!isPanelActive()) draft.directoryPicker.setPanelActive(false)
        if (treeUri == null || !draft.directoryPicker.acceptsResult) {
            draft.receiveDirectoryResult(null)
            return
        }
        val canonicalTree = canonicalDocumentTreeId(treeUri.toString())?.let(Uri::parse)
        if (canonicalTree == null) {
            draft.receiveDirectoryResult(null)
            feedback(R.string.cant_access_folder)
            return
        }
        val permissionTaken = runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.isSuccess
        if (!permissionTaken) {
            draft.receiveDirectoryResult(null)
            feedback(R.string.cant_access_folder)
            return
        }
        draft.receiveDirectoryResult(canonicalTree.toString())
    }

    fun reviewBattery(owner: Any) {
        if (!acceptsInput(owner)) return
        if (!openBatteryOptimizationReview(context)) feedback(R.string.no_app_available)
    }

    fun moveExistingRecordings(renderedActivation: Any) {
        if (!acceptsInput(renderedActivation)) return
        val current = state
        if (current.persisting) return
        val moveTargetTreeUri = current.displayed.snapshot.exportDirectoryUri?.let(Uri::parse)
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
                    notifyChanged()
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
            refreshMoveAvailability(forceRefresh = true)
        }
    }

    private fun refreshBattery() {
        batteryOptimizationRestricted = !isIgnoringBatteryOptimizations(context)
        notifyChanged()
    }

    private fun refreshMoveAvailability(forceRefresh: Boolean = false) {
        val current = state
        if (!current.active || !current.interactionReady ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            moveAvailability.hide()
            return
        }
        val target = getOutputDirectoryId(context.applicationContext, current.displayed.snapshot.exportDirectoryUri?.let(Uri::parse))
        if (forceRefresh) moveRevision++
        moveAvailability.request(target, moveRevision)
    }

    private fun draftChanged(current: SettingsDraftPresentation) {
        if (!started || closed) return
        draftReconciliationDepth++
        try {
            val old = previousPresentation
            previousPresentation = current
            if (old == null || old.active != current.active || old.interactionReady != current.interactionReady ||
                old.persisting != current.persisting || old.editRevision != current.editRevision ||
                old.calibrationResetRevision != current.calibrationResetRevision) calibration.cancel()
            if (old == null || old.displayed.snapshot.silenceCalibrationInput() !=
                current.displayed.snapshot.silenceCalibrationInput() ||
                old.calibrationResetRevision != current.calibrationResetRevision) calibration.cancel(clearMeasurement = true)
            if (old == null || old.active != current.active || old.interactionReady != current.interactionReady) {
                if (!current.active || !current.interactionReady) releaseInputFocus()
            }
            if (current.hydrated && (old == null ||
                old.displayed.snapshot.exportDirectoryUri != current.displayed.snapshot.exportDirectoryUri ||
                old.hydrated != current.hydrated)) {
                exportPathText = describeOutputDirectory(context, current.displayed.snapshot.exportDirectoryUri?.let(Uri::parse))
            }
            if (old != null && old.hasUnsavedChanges && !current.hasUnsavedChanges &&
                old.calibrationResetRevision != current.calibrationResetRevision) errors = NativeSettingsRetentionErrors()
            serviceBinding.bind(current.active, current.persisting)
            if (old == null || old.active != current.active || old.interactionReady != current.interactionReady ||
                old.displayed.snapshot.exportDirectoryUri != current.displayed.snapshot.exportDirectoryUri) {
                refreshMoveAvailability()
            }
            restartHydrationEffect(current.active to current.persisting)
        } finally {
            draftReconciliationDepth--
        }
        // The controller's following child-readiness callback publishes the reconciled state.
        // start() is the only direct caller and publishes after this method returns as well.
    }

    private fun restartHydrationEffect(key: Pair<Boolean, Boolean>) {
        if (hydrationKey == key) return
        hydrationKey = key
        hydrationJob?.cancel()
        val next = scope.launch(start = CoroutineStart.LAZY) {
            var retryDelayMillis = DURABLE_UI_RETRY_INITIAL_MILLIS
            var failureReported = false
            while (draft.needsHydration) {
                val revision = state.editRevision
                val hydrated = runDurableUiBooleanAttempt {
                    val result = draft.hydrate(
                        readDurable = {
                            val stored = persistence.readStoredConfiguration()
                            routes = supportedInputRouteModes(context)
                            val resolved = resolveNativeSettingsBaseline(stored, routes,
                                fallbackRate = snapshot.sampleRate.takeIf { it > 0 } ?: 48_000)
                            audioOptions = nativeSettingsAudioOptions(resolved.normalized.snapshot, routes)
                            resolved
                        },
                        restoreFields = {
                            val resolved = resolveNativeSettingsRestoredDraft(it, routes,
                                fallbackRate = audioOptions.rates.first())
                            audioOptions = nativeSettingsAudioOptions(resolved.snapshot, routes)
                            resolved
                        },
                    )
                    if (result == SettingsDraftHydrationResult.APPLIED) refreshBattery()
                    result == SettingsDraftHydrationResult.APPLIED
                }
                if (hydrated) break
                if (!settingsHydrationMayApply(revision, state.editRevision)) {
                    if (draft.needsHydration) {
                        retryDelayMillis = DURABLE_UI_RETRY_INITIAL_MILLIS
                        continue
                    }
                    break
                }
                if (!failureReported) {
                    feedback(R.string.recorder_state_persist_failed)
                    failureReported = true
                }
                delay(retryDelayMillis)
                retryDelayMillis = nextDurableUiRetryDelayMillis(retryDelayMillis)
            }
        }
        hydrationJob = next
        next.start()
    }

    private fun feedback(resource: Int) {
        AppFeedbackCenter.post(context.getString(resource), FeedbackTone.ERROR)
    }

    private fun notifyChanged() {
        if (started && !closed && draftReconciliationDepth == 0) changed()
    }

    fun saveDraft(): List<Any> = draft.saveDraft()
    fun saveDirectoryPicker(): List<Any> = draft.saveDirectoryPicker()
    fun saveTileEditor(): List<Any> = quickTileEditorState.save(state.active && isPanelActive())

    override fun close() {
        if (closed) return
        closed = true
        draft.directoryPicker.setPanelActive(false)
        draft.dispose()
        lifecycle.removeObserver(lifecycleObserver)
        calibrationBinding.close()
        moveAvailability.close()
        serviceBinding.close()
        hydrationJob?.cancel()
        scope.cancel()
        changed = {}
    }
}
