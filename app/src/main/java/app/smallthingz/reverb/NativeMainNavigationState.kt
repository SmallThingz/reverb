package app.smallthingz.reverb

/** Exactly the five released rememberSaveable fields; motion/selection/retention stay transient. */
internal data class NativeMainSavedNavigation(
    val showSettings: Boolean = false,
    val settingsBufferTargetCode: Int = -1,
    val showAboutDialog: Boolean = false,
    val showIncidents: Boolean = false,
    val showLibrary: Boolean = false,
)

internal enum class NativeMainPanel { SETTINGS, LIBRARY }

internal data class NativeMainFeedbackPlacement(
    val deliveryAllowed: Boolean,
    val z: Float,
    val horizontalPaddingDp: Int,
    val bottomPaddingDp: Int,
    val addNavigationBarBottom: Boolean,
)

/**
 * Direct renderer-neutral owner of MainActivity.MainScreen's existing fields and decisions.
 * No permission, recording, persistence, generic navigation stack or duplicate screen state.
 */
internal class NativeMainNavigationState(restored: NativeMainSavedNavigation = NativeMainSavedNavigation()) {
    var showSettings = restored.showSettings
        private set
    var settingsBufferTargetCode = restored.settingsBufferTargetCode
        private set
    var showAboutDialog = restored.showAboutDialog
        private set
    var showIncidents = restored.showIncidents
        private set
    var showLibrary = restored.showLibrary
        private set
    var librarySelectionActive = false
    var libraryExpandedRecordingActive = false
    var libraryUndoVisible = false
    private var incidentMotionVisible = restored.showIncidents
    var dragTarget: NativeMainPanel? = null
        private set
    var settingsDragProgress = 0f
        private set
    var libraryDragProgress = 0f
        private set
    private var settingsRetained = false
    private var libraryRetained = false

    val incidentsOverlayVisible get() = showIncidents || incidentMotionVisible
    val settingsBackEnabled get() = showSettings && !showAboutDialog
    val incidentsBackEnabled get() = showIncidents && !showAboutDialog
    val libraryBackEnabled get() = showLibrary && !showSettings && !incidentsOverlayVisible &&
        !librarySelectionActive && !libraryExpandedRecordingActive && !showAboutDialog
    val homeGesturesEnabled get() = !showSettings && !showLibrary && !incidentsOverlayVisible && !showAboutDialog
    val homeVisualizerVisible get() = homeGesturesEnabled
    val libraryActive get() = showLibrary && !incidentsOverlayVisible && !showAboutDialog
    val settingsPanelActive get() = showSettings && !showAboutDialog

    fun save() = NativeMainSavedNavigation(showSettings, settingsBufferTargetCode, showAboutDialog, showIncidents, showLibrary)
    fun openSettings(bufferTargetCode: Int = -1) { showSettings = true; settingsBufferTargetCode = bufferTargetCode }
    fun closeSettings() { showSettings = false; settingsBufferTargetCode = -1 }
    fun openLibrary() { showLibrary = true }
    fun closeLibrary() { showLibrary = false }
    fun openSettingsFromLibrary() { closeLibrary(); openSettings() }
    fun openIncidents() { showIncidents = true }
    fun openIncidentsFromLibrary() { closeLibrary(); openIncidents() }
    fun closeIncidents() { showIncidents = false }
    fun openAbout() { showAboutDialog = true }
    fun closeAbout() { showAboutDialog = false }
    fun bindIncidentsOverlayVisibility(visible: Boolean) { incidentMotionVisible = visible }

    /** Return target so the corresponding retained Back owner calls beginForwardMotion. */
    fun beginVerticalReveal(startY: Float, height: Float): NativeMainPanel? {
        settingsDragProgress = 0f
        libraryDragProgress = 0f
        dragTarget = when {
            startY <= height * .48f -> NativeMainPanel.SETTINGS
            startY >= height * .52f -> NativeMainPanel.LIBRARY
            else -> null
        }
        if (dragTarget == NativeMainPanel.SETTINGS) settingsBufferTargetCode = -1
        return dragTarget
    }

    fun verticalRevealDelta(amount: Float, height: Float) {
        when (dragTarget) {
            NativeMainPanel.SETTINGS -> settingsDragProgress =
                panelRevealProgress(settingsDragProgress * height + amount, height)
            NativeMainPanel.LIBRARY -> libraryDragProgress =
                panelRevealProgress(libraryDragProgress * height - amount, height)
            null -> Unit
        }
    }

    fun endVerticalReveal() {
        when (dragTarget) {
            NativeMainPanel.SETTINGS -> {
                showSettings = shouldCommitPanelReveal(settingsDragProgress)
                if (!showSettings) settingsBufferTargetCode = -1
            }
            NativeMainPanel.LIBRARY -> showLibrary = shouldCommitPanelReveal(libraryDragProgress)
            null -> Unit
        }
        dragTarget = null
    }
    fun cancelVerticalReveal() { dragTarget = null }

    fun settingsTarget() = if (dragTarget == NativeMainPanel.SETTINGS) settingsDragProgress else if (showSettings) 1f else 0f
    fun libraryTarget() = if (dragTarget == NativeMainPanel.LIBRARY) libraryDragProgress else if (showLibrary) 1f else 0f

    fun retainSettings(progress: Float): Boolean {
        settingsRetained = shouldComposeMainPanel(settingsRetained,
            showSettings || dragTarget == NativeMainPanel.SETTINGS, progress)
        return settingsRetained
    }
    fun retainLibrary(progress: Float): Boolean {
        libraryRetained = shouldComposeMainPanel(libraryRetained,
            showLibrary || dragTarget == NativeMainPanel.LIBRARY, progress)
        return libraryRetained
    }

    fun saveFeedbackVisible(settingsSettledProgress: Float, librarySettledProgress: Float): Boolean =
        homeGesturesEnabled && dragTarget == null && settingsSettledProgress == 0f && librarySettledProgress == 0f

    fun feedbackPlacement(libraryPanelProgress: Float): NativeMainFeedbackPlacement {
        val abovePanel = incidentsOverlayVisible || showLibrary || libraryPanelProgress > 0f
        return NativeMainFeedbackPlacement(
            deliveryAllowed = !showAboutDialog && !(showLibrary && !incidentsOverlayVisible && libraryUndoVisible),
            z = when {
                incidentsOverlayVisible -> 7f
                showLibrary || libraryPanelProgress > 0f -> 5f
                else -> 2f
            },
            horizontalPaddingDp = 18,
            bottomPaddingDp = if (abovePanel || showSettings) 20 else 104,
            addNavigationBarBottom = abovePanel,
        )
    }
}
