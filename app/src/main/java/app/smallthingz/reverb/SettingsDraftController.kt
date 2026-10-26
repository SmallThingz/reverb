package app.smallthingz.reverb

/**
 * Main-thread Settings draft ownership. Persistence, field validation, options and rendering
 * remain with their existing owners. Nothing here starts recorder work or writes preferences.
 */
internal class SettingsDraftController(
    initiallyActive: Boolean,
    savedDraft: List<Any> = emptyList(),
    savedDirectoryPicker: List<Any>? = null,
    private val barrier: SettingsCommitBarrier = processSettingsCommitBarrier,
    private val isPanelActive: () -> Boolean,
    private val onChanged: (SettingsDraftPresentation) -> Unit,
    private val onThemePreview: (AppThemeMode?) -> Unit,
    private val onChildEditorReadiness: (active: Boolean, parentReady: Boolean) -> Unit,
) {
    private val restoration = SettingsDraftRestoration.restore(savedDraft)
    val directoryPicker = savedDirectoryPicker?.let(SettingsDirectoryPickerState::restore)
        ?: SettingsDirectoryPickerState(initiallyActive)

    private var active = initiallyActive
    private var alive = true
    private var activation = Any()
    private var hydrated = false
    private var interactionReady = false
    private var baseline = SettingsSnapshot()
    private var displayed = SettingsUiDraft.normalized(baseline)
    private var unsaved = false
    private var editRevision = 0L
    private var outputRevision = 0L
    private var calibrationResetRevision = 0
    private var silenceRaw: String? = null
    private var persistence: PersistenceAttempt? = null

    init {
        directoryPicker.setPanelActive(initiallyActive)
        if (!initiallyActive) restoration.discard()
    }

    val snapshot: SettingsSnapshot get() = displayed.snapshot

    val state: SettingsDraftPresentation
        get() = SettingsDraftPresentation(
            activation, active, hydrated, interactionReady, persistence != null,
            baseline, displayed, unsaved, editRevision, outputRevision,
            calibrationResetRevision, silenceRaw,
        )

    val needsHydration: Boolean
        get() = alive && settingsShouldRehydrate(active, persistence != null, unsaved)

    /** Rendered callbacks retain this identity, not a Boolean copied before close/reopen. */
    fun acceptsInput(renderedActivation: Any, panelIsActuallyActive: Boolean): Boolean =
        alive && active && hydrated && interactionReady &&
            activation === renderedActivation && panelIsActuallyActive

    fun setActive(value: Boolean) {
        if (!alive || active == value) return
        active = value
        activation = Any()
        interactionReady = false
        directoryPicker.setPanelActive(value)
        if (!value) {
            restoration.discard()
            if (persistence == null) {
                undoDraft()
                onThemePreview(null)
            }
        } else if (settingsRetainedDraftMayReleaseInteraction(
                active, hydrated, persistence != null, unsaved,
            )) {
            interactionReady = true
        }
        publish()
    }

    /**
     * [readDurable] is the released IO/retention-lock read and returns its already-resolved
     * baseline. [restoreFields] performs the released synchronous field/option binding only;
     * it must retain all four raw input strings. No suspension belongs between read and apply.
     */
    suspend fun hydrate(
        readDurable: suspend () -> SettingsDraftBaseline,
        restoreFields: (SettingsUiDraft) -> SettingsUiDraft,
    ): SettingsDraftHydrationResult {
        if (!needsHydration) return SettingsDraftHydrationResult.NOT_NEEDED
        val owner = activation
        val expectedRevision = editRevision
        interactionReady = false
        publish()
        val durable = barrier.readSettled(readDurable)
        if (!alive || !active || activation !== owner) {
            return SettingsDraftHydrationResult.RETIRED
        }
        val hydration = restoration.hydrate(expectedRevision, editRevision) {
            durable.normalized
        }
        if (hydration == null) {
            if (settingsSupersededHydrationMayReleaseInteraction(active, persistence != null, unsaved)) {
                interactionReady = true
                publish()
            }
            return SettingsDraftHydrationResult.SUPERSEDED
        }

        baseline = hydration.baseline
        silenceRaw = durable.silenceRaw
        outputRevision++
        calibrationResetRevision++
        displayed = if (hydration.restored) {
            // A restored output choice owns its value against a later save's normalization.
            outputRevision++
            restoreFields(hydration.displayed)
        } else hydration.displayed
        recomputeUnsaved()
        hydrated = true
        interactionReady = true
        onThemePreview(hydration.themePreview)
        applyPendingDirectoryResult()
        publish()
        return SettingsDraftHydrationResult.APPLIED
    }

    /**
     * The field owner supplies one immutable, fully rebuilt snapshot and the current raw strings.
     * Existing parsers update backing values only on valid input; invalid/inactive strings stay
     * verbatim. Time/Size switching calls this with unchanged strings and byte/second values.
     *
     * Callers first use acceptsInput plus their existing lifecycle/window/feature guards. This
     * method deliberately does not add a persistence gate: retained ordinary edits may continue.
     */
    fun edited(next: SettingsUiDraft, explicitOutputChoice: Boolean = false) {
        displayed = next
        if (explicitOutputChoice) outputRevision++
        editRevision++
        recomputeUnsaved()
        publish()
    }

    fun previewTheme(mode: AppThemeMode) {
        onThemePreview(mode)
    }

    /** Navigation's existing child-editor/calibration precedence remains outside this owner. */
    fun undo(): Boolean {
        if (!unsaved) return false
        undoDraft()
        publish()
        return true
    }

    /**
     * Called only after the existing preflight admission, before its first suspension. This is
     * UI ownership, not the process commit barrier: validation may still reject this attempt.
     */
    fun beginPersistence(): PersistenceAttempt? {
        if (persistence != null) return null
        return PersistenceAttempt().also {
            persistence = it
            publish()
        }
    }

    /**
     * Called after existing validation and before entering the unchanged durable transaction.
     * Validated time backing values may have been refreshed without creating a user edit.
     */
    fun submission(attempt: PersistenceAttempt, validatedDraft: SettingsUiDraft): Submission {
        check(persistence === attempt)
        displayed = validatedDraft
        recomputeUnsaved()
        return Submission(
            attempt, activation, validatedDraft.snapshot, editRevision, outputRevision, silenceRaw,
        )
    }

    /**
     * UI tail only. The existing transaction must first finish commit/rollback and runtime
     * convergence inside barrier.runAcceptedSave, and publish its committed theme under the
     * retention lock. A retired Activity never calls this tail.
     */
    fun committed(submission: Submission, committedFormat: ExportFormat): Boolean {
        check(persistence === submission.attempt)
        val editedWhileSaving = settingsEditedDuringPersistence(submission.editRevision, editRevision)
        val durable = submission.snapshot.copy(format = committedFormat)
        baseline = durable
        silenceRaw = submission.snapshot.silenceGate
        val output = reconcileSettingsOutputAfterCommit(
            currentFormat = displayed.snapshot.format ?: ExportFormat.WAV,
            committedFormat = committedFormat,
            submittedOutputRevision = submission.outputRevision,
            currentOutputRevision = outputRevision,
        )
        displayed = if (editedWhileSaving) {
            displayed.copy(snapshot = displayed.snapshot.copy(format = output))
        } else {
            SettingsUiDraft.normalized(durable)
        }
        recomputeUnsaved()
        if (alive && active && isPanelActive() && activation === submission.activation &&
            displayed.snapshot.themeMode == durable.themeMode
        ) {
            onThemePreview(null)
        }
        publish()
        return settingsSaveMayContinueAfterCommit(unsaved)
    }

    /** Always called from the UI attempt's finally, including rejected preflight and failure. */
    fun finishPersistence(attempt: PersistenceAttempt) {
        if (persistence !== attempt) return
        persistence = null
        if (!alive) return
        if (!active) {
            undoDraft()
            onThemePreview(null)
        } else {
            // This activation owns readiness; a retired save callback never owns that cell.
            interactionReady = settingsRetainedDraftMayReleaseInteraction(
                active, hydrated, false, unsaved,
            )
        }
        publish()
    }

    /** Canonicalization and persistable-permission acquisition remain in the registered launcher. */
    fun receiveDirectoryResult(canonicalTreeUri: String?) {
        if (!isPanelActive()) directoryPicker.setPanelActive(false)
        directoryPicker.receiveResult(canonicalTreeUri)
        if (applyPendingDirectoryResult()) publish()
    }

    fun saveDraft(): List<Any> = restoration.save(
        panelActive = alive && active && isPanelActive(),
        hydrated = hydrated,
        persisting = persistence != null,
        baseline = baseline,
        currentDraft = { displayed },
    )

    fun saveDirectoryPicker(): List<Any> = directoryPicker.save()

    /** Cancels UI ownership only. It must never cancel or retire an accepted durable save. */
    fun dispose() {
        if (!alive) return
        alive = false
        interactionReady = false
        activation = Any()
    }

    private fun applyPendingDirectoryResult(): Boolean {
        if (!alive || !active || !isPanelActive()) return false
        val value = directoryPicker.takePendingResult(alive && active && hydrated && interactionReady)
            ?: return false
        displayed = displayed.copy(snapshot = displayed.snapshot.copy(exportDirectoryUri = value))
        editRevision++
        recomputeUnsaved()
        return true
    }

    private fun undoDraft() {
        if (!unsaved) return
        restoration.discard()
        directoryPicker.retire()
        editRevision++
        outputRevision++
        calibrationResetRevision++
        displayed = SettingsUiDraft.normalized(baseline)
        unsaved = false
        onThemePreview(baseline.themeMode)
    }

    private fun recomputeUnsaved() {
        unsaved = displayed.hasUnsavedChanges(baseline)
    }

    private fun publish() {
        if (!alive) return
        onChanged(state)
        // Adapter calls existing closeEditor() when inactive; otherwise restorePendingDraft(ready).
        onChildEditorReadiness(active, hydrated && interactionReady)
    }

    internal class PersistenceAttempt internal constructor()
    internal class Submission internal constructor(
        internal val attempt: PersistenceAttempt,
        internal val activation: Any,
        val snapshot: SettingsSnapshot,
        internal val editRevision: Long,
        internal val outputRevision: Long,
        val originalSilenceRaw: String?,
    )
}

internal data class SettingsDraftBaseline(
    val normalized: SettingsUiDraft,
    val silenceRaw: String?,
)

internal data class SettingsDraftPresentation(
    val activation: Any,
    val active: Boolean,
    val hydrated: Boolean,
    val interactionReady: Boolean,
    val persisting: Boolean,
    val baseline: SettingsSnapshot,
    val displayed: SettingsUiDraft,
    val hasUnsavedChanges: Boolean,
    val editRevision: Long,
    val outputSelectionRevision: Long,
    val calibrationResetRevision: Int,
    val originalSilenceRaw: String?,
)

internal enum class SettingsDraftHydrationResult {
    APPLIED,
    SUPERSEDED,
    RETIRED,
    NOT_NEEDED,
}
