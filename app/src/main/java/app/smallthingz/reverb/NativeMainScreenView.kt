package app.smallthingz.reverb

import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Rect
import smallthingz.ui.geometry.Size
import smallthingz.ui.platform.AccessibilityManager
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.ReverbService.BufferSlot
import app.smallthingz.reverb.nativeui.*
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class NativeMainScreenInput(val permissionsGranted: Boolean, val showPermissionDenied: Boolean)

/**
 * The released MainScreen's concrete native assembly. Screen/backend owners remain their existing
 * authorities. This is one pointer root, one inherited frame runtime and the Activity's window text
 * cursor. Panels keep the released composition lifetimes; no production route is switched here.
 */
internal class NativeMainScreenView private constructor(
    context: Context,
    private var environment: ReverbViewEnvironment,
    private var input: NativeMainScreenInput,
    private val lifecycleOwner: LifecycleOwner,
    private val dispatcher: OnBackPressedDispatcher,
    parentScope: CoroutineScope,
    private val accessibilityManager: AccessibilityManager?,
    private val windowRoot: View,
    private val windowText: NativeWindowTextAccessibilityOwner,
    private val restored: NativeMainScreenSavedState?,
    private val onReviewPermission: () -> Unit,
    private val onThemeChanged: (AppThemeMode?) -> Unit,
    private val registerDirectoryResult: ((Uri?) -> Unit) -> NativeDirectoryPickerRegistration,
    private val releaseInputFocus: () -> Unit,
    private val pointer: MainPointerGeometry,
) : NativePagerTouchRoot(context, pointer.coordinates::rootToScreen, pointer.coordinates::coordinates,
    pointer.layout, pointer.coordinates::begin, mouseButtons = true), AutoCloseable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val frames = requireNotNull(scope.coroutineContext[MonotonicFrameClock])
    private val navigation = NativeMainNavigationState(restored?.navigation ?: NativeMainSavedNavigation())
    private var started = false
    private var closed = false
    private var rendering = false
    private var renderAgain = false
    private var windowInsets = WindowInsetsCompat.CONSUMED
    private var visualInsets = Insets.NONE
    private var statusInsets = Insets.NONE
    private var navigationBottom = 0
    private var librarySnapshot = emptyList<RecordingEntity>()
    private var libraryRefreshGeneration = 0
    private var captureOwner: NativeCaptureUiOwner? = null
    private var captureContent: NativeCaptureContentView? = null
    private var captureCollection: Job? = null
    private var permissionContent: NativePermissionRequiredView? = null
    private var settings: NativeSettingsScreenContent? = null
    private var settingsPointer: NativeSettingsPointerBinding? = null
    private var settingsText: NativeSettingsAccessibilityBinding? = null
    private var directoryRegistration: NativeDirectoryPickerRegistration? = null
    private var library: ReverbLibraryScreenContent? = null
    private var libraryLayer: NativeMainLibraryLayer? = null
    private var libraryPointer: NativeLibraryPointerBinding? = null
    private var libraryText: NativeLibraryAccessibilityBinding? = null
    private var libraryToolbar: ReverbAppTopBarView? = null
    private var libraryToolbarText: AutoCloseable? = null
    private var about: ReverbAboutDialog? = null
    private val clipLeases = IdentityHashMap<View, Any>()
    private val shellLayouts = IdentityHashMap<View, ShellLayout>()
    private var drawOrder = emptyList<View>()
    private var orderedSettings: View? = null
    private var orderedLibrary: View? = null
    private var orderedLibraryToolbar: View? = null
    private var orderedLibraryAboveHome = false
    private var orderedFeedbackZBits = 0
    private var lastSaveFeedbackVisible: Boolean? = null
    private var measuredFeedbackBottom = Int.MIN_VALUE
    private var captureRestorationAvailable = true
    private var pendingAboutScrollPosition = restored?.aboutScrollPosition

    // Registration order is the released Settings, Incidents, Library sequence.
    private val settingsMotion = NativeMainPanelMotion(navigation.showSettings, scope, dispatcher,
        lifecycleOwner, ::motionChanged)
    private val incidentsOwner: NativeIncidentsScreenOwner = NativeIncidentsScreenOwner(context, scope, navigation.showIncidents,
        onBackRequested = { navigate { closeIncidents() } },
        onAlertChanged = { alert -> hasIncidentAlert = alert; requestRender() })
    private var hasIncidentAlert: Boolean = incidentsOwner.state.history.hasAlert
    private val source = NativeMainAccessibility(windowRoot, this, { drawOrder }, ::ownNodeHidden,
        ::sourceClip, minimumTouch = { view ->
            val sourceClick = view is NativeClickPhaseHost &&
                (view !is NativeMergedRowAccessibilityHost || view.mergesAccessibilityDescendants)
            if (sourceClick || view is NativeRangeEndpointView) dpFloat(48) else 0f
        })
    private val scrollEvents = windowText.openScrollEvents(scope,
        onScrollGeometryChanged = { invalidate() }, onBatchFinished = {})
    private val incidents = NativeIncidentsScreenView(context, environment, incidentsOwner, scope,
        lifecycleOwner, dispatcher, this, windowText, source,
        overlayVisibilityChanged = { visible ->
            navigation.bindIncidentsOverlayVisibility(visible)
            requestRender()
        }, scrollEvents = scrollEvents, restoredPosition = restored?.incidentsPosition,
        admitChildren = source::retainPresentSemanticChildren)
    private val libraryMotion = NativeMainPanelMotion(navigation.showLibrary, scope, dispatcher,
        lifecycleOwner, ::motionChanged)
    private val home = ReverbHomeScaffoldView(context, environment, homeNavigation(), hasIncidentAlert,
        homeInsets(), this)
    private val feedback = NativeAppFeedbackHostView(context, environment, windowRoot, lifecycleOwner,
        scope, accessibilityManager, windowText, source)
    private val vertical = NativeHomeVerticalDrag(
        touchSlop = { ViewConfiguration.get(context).scaledTouchSlop.toFloat() },
        onStart = { point ->
            when (navigation.beginVerticalReveal(point.y, home.height.toFloat())) {
                NativeMainPanel.SETTINGS -> settingsMotion.beginForwardMotion()
                NativeMainPanel.LIBRARY -> libraryMotion.beginForwardMotion()
                null -> Unit
            }
            requestRender()
        },
        onDelta = { delta -> navigation.verticalRevealDelta(delta, home.height.toFloat()); requestRender() },
        onEnd = { navigation.endVerticalReveal(); requestRender() },
        onCancel = { navigation.cancelVerticalReveal(); requestRender() },
    )
    private val homePointer = NativeHomePointerBinding(home, pointer.coordinates,
        { environment.textDensity.density }, vertical, { navigation.homeGesturesEnabled }) { view ->
        when {
            view === permissionContent -> directHit(view, emptyList(),
                { permissionContent?.hitLayouts.orEmpty() })
            else -> captureContent?.pointerLayout(view)
        }
    }
    private val incidentsPointer = NativeIncidentsPointerBinding(incidents, pointer.coordinates,
        frames, scope) { false }
    private val homeText = home.topBar.bindAccessibility(windowText.textWindow, source.geometry, source,
        source::retainPresentSemanticChildren)

    private val noNestedScroll = object : NativeVerticalNestedHost {
        override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
        override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
        override suspend fun preFling(availableY: Float) = 0f
        override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
    }

    init {
        pointer.owner = this
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isChildrenDrawingOrderEnabled = true
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(home)
        addView(feedback)
        addView(incidents)
    }

    /** Invoke only after the actual Activity root and result adapters are installed. */
    fun start() {
        if (closed || started) return
        started = true
        requestRender()
    }

    fun bind(next: NativeMainScreenInput, nextEnvironment: ReverbViewEnvironment) {
        if (closed) return
        val changedEnvironment = environment !== nextEnvironment
        input = next
        environment = nextEnvironment
        if (changedEnvironment) {
            home.setEnvironment(environment)
            permissionContent?.bindEnvironment(environment)
            incidents.bindPalette(environment.palette)
        }
        requestRender()
    }

    /** Activity already owns the sole insets listener; no independent raw-system/IME reads. */
    fun setWindowInsets(insets: WindowInsetsCompat) {
        if (closed || windowInsets == insets) return
        windowInsets = insets
        visualInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        statusInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
        navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        incidents.setStatusBarInsets(statusInsets)
        requestRender()
        requestLayout()
    }

    fun saveState() = NativeMainScreenSavedState(
        navigation.save(), captureOwner?.savedSelection()?.storageCode?.toInt()
            ?: restored?.selectedBufferCode?.takeIf { captureRestorationAvailable },
        settings?.owner?.saveDraft() ?: restored?.settingsDraft,
        settings?.owner?.saveDirectoryPicker() ?: restored?.directoryPicker,
        settings?.owner?.saveTileEditor() ?: restored?.tileEditor,
        settings?.savePosition() ?: restored?.settingsPosition,
        library?.saveScrollPosition() ?: restored?.libraryPosition,
        incidents.savePosition(),
        about?.scrollPosition() ?: pendingAboutScrollPosition,
    )

    private fun navigate(action: NativeMainNavigationState.() -> Unit) {
        if (closed) return
        navigation.action()
        requestRender()
    }

    private fun requestRender() {
        if (!started || closed) return
        if (rendering) { renderAgain = true; return }
        rendering = true
        try {
            do {
                renderAgain = false
                settingsMotion.bind(navigation.showSettings,
                    navigation.dragTarget == NativeMainPanel.SETTINGS, navigation.settingsDragProgress,
                    navigation.settingsBackEnabled) { navigate { closeSettings() } }
                incidentsOwner.bindNavigation(navigation.showIncidents, navigation.incidentsBackEnabled)
                libraryMotion.bind(navigation.showLibrary,
                    navigation.dragTarget == NativeMainPanel.LIBRARY, navigation.libraryDragProgress,
                    navigation.libraryBackEnabled) { navigate { closeLibrary() } }
                ensurePanels()
                settings?.bind(settingsInput(), environment)
                library?.bind(libraryInput(), environment)
                home.bindShell(homeNavigation(), hasIncidentAlert, homeInsets())
                bindCaptureBranch()
                feedback.bind(navigation.feedbackPlacement(libraryMotion.panelProgress).deliveryAllowed, environment)
                bindAbout()
                applyMotion(bindToolbar = true)
            } while (renderAgain && !closed)
        } finally { rendering = false }
    }

    /** Motion changes placement only; never rebind every Library row or recapture semantics per frame. */
    private fun motionChanged() {
        if (!started || closed) return
        if (rendering) return
        ensurePanels()
        applyMotion(bindToolbar = false)
        val visible = saveFeedbackVisible()
        if (lastSaveFeedbackVisible != visible) {
            lastSaveFeedbackVisible = visible
            captureOwner?.refreshPresentationVisibility()
        }
    }

    private fun ensurePanels() {
        if (navigation.retainSettings(settingsMotion.panelProgress) && settings == null) createSettings()
        if (navigation.retainLibrary(libraryMotion.panelProgress) && library == null) createLibrary()
    }

    private fun createSettings() {
        val owner = NativeSettingsOwner(context, windowRoot, lifecycleOwner.lifecycle, scope,
            navigation.showSettings, restored?.settingsDraft.orEmpty(), restored?.directoryPicker,
            restored?.tileEditor, { navigation.settingsPanelActive }, onThemeChanged,
            releaseInputFocus, { directoryRegistration?.launch?.invoke(it) },
            { navigate { closeSettings() } })
        // Registration may deliver a restored result synchronously. The exact owner exists first;
        // hydration starts only after the input/accessibility/result routes are all installed.
        directoryRegistration = registerDirectoryResult(owner::receiveDirectoryResult)
        val content = NativeSettingsScreenContent(context, owner, settingsInput(), environment,
            lifecycleOwner, dispatcher, this, frames, scope, noNestedScroll, { false },
            restored?.settingsPosition, ::settingsNodesChanged)
        settings = content
        addView(content)
        settingsPointer = NativeSettingsPointerBinding(content, pointer.coordinates, frames, scope, parentInterested = { false })
        settingsText = NativeSettingsAccessibilityBinding(content, windowText, source, scrollEvents,
            source::retainPresentSemanticChildren)
        content.start()
        settingsNodesChanged()
    }

    private fun settingsNodesChanged() {
        if (closed) return
        settingsPointer?.refresh()
        settingsText?.refresh()
        invalidate()
    }

    private fun settingsInput() = NativeSettingsScreenInput(navigation.showSettings,
        BufferSlot.fromStorageCode(navigation.settingsBufferTargetCode),
        NativeSettingsInsets(visualInsets.left, visualInsets.top, visualInsets.right, visualInsets.bottom))

    private fun createLibrary() {
        val content = ReverbLibraryScreenContent(context, environment, libraryInput(),
            lifecycleOwner, dispatcher, scope, frames,
            { scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f }, accessibilityManager,
            this, noNestedScroll, ::libraryNodesChanged, {}, restoredScrollPosition = restored?.libraryPosition)
        library = content
        libraryLayer = NativeMainLibraryLayer(context, environment, content).also(::addView)
        libraryPointer = NativeLibraryPointerBinding(content, pointer.coordinates, frames, scope) { false }
        libraryText = NativeLibraryAccessibilityBinding(content, windowText, source, scrollEvents,
            source::retainPresentSemanticChildren)
        libraryNodesChanged()
    }

    private fun libraryNodesChanged() {
        if (closed) return
        libraryPointer?.refresh()
        libraryText?.refresh()
        invalidate()
    }

    private fun libraryInput() = NativeLibraryScreenInput(
        NativeLibraryInput(navigation.libraryActive, librarySnapshot,
            NativeLibraryCallbacks(
                onSelectionActiveChange = { if (navigation.librarySelectionActive != it) {
                    navigation.librarySelectionActive = it; requestRender()
                } },
                onExpandedRecordingActiveChange = { if (navigation.libraryExpandedRecordingActive != it) {
                    navigation.libraryExpandedRecordingActive = it; requestRender()
                } },
                onVisibleRecordingsChanged = { visible -> if (librarySnapshot != visible) {
                    ++libraryRefreshGeneration; librarySnapshot = visible
                } },
                onParentRefreshRequested = ::refreshLibrarySnapshot,
                onUndoVisibleChange = { if (navigation.libraryUndoVisible != it) {
                    navigation.libraryUndoVisible = it; requestRender()
                } },
            )),
        hasIncidentAlert, false, LibraryScaffoldInsets(visualInsets.left, visualInsets.right, visualInsets.bottom),
        { navigate { openAbout() } }, { navigate { openIncidentsFromLibrary() } },
        { navigate { openSettingsFromLibrary() } }, { navigate { closeLibrary() } })

    private fun refreshLibrarySnapshot() {
        val generation = ++libraryRefreshGeneration
        scope.launch {
            try {
                val known = RecordingRepository.listKnown(context.applicationContext)
                if (generation != libraryRefreshGeneration) return@launch
                librarySnapshot = known
                requestRender()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Unit }
            try {
                val refreshed = RecordingRepository.refresh(context.applicationContext)
                if (generation != libraryRefreshGeneration) return@launch
                librarySnapshot = refreshed
                requestRender()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Unit }
        }
    }

    private fun bindCaptureBranch() {
        if (input.permissionsGranted) {
            permissionContent?.let { home.setBody(null); it.close() }; permissionContent = null
            if (captureOwner == null) {
                val selected = if (captureRestorationAvailable)
                    restored?.selectedBufferCode?.let { BufferSlot.fromStorageCode(it) } ?: BufferSlot.ONE_SHOT
                    else BufferSlot.ONE_SHOT
                captureRestorationAvailable = false
                val owner = NativeCaptureUiOwner(context, lifecycleOwner, windowRoot, scope, selected,
                    navigation.homeVisualizerVisible, accessibilityManager, ::saveFeedbackVisible,
                    ::refreshLibrarySnapshot, { navigate { openLibrary() } },
                    { slot -> navigate { openSettings(slot.storageCode.toInt()) } })
                captureOwner = owner
                val content = NativeCaptureContentView(context, environment, owner, lifecycleOwner,
                    dispatcher, scope, windowRoot, this, pointer.coordinates, windowText, source.geometry,
                    source, ::materialButtons, ::homeNodesChanged)
                captureContent = content
                home.setBody(content)
                captureCollection = scope.launch { owner.state.collect { state ->
                    if (captureOwner === owner) content.bind(state, environment)
                } }
                owner.attach()
            }
            captureOwner?.setVisualizerVisible(navigation.homeVisualizerVisible)
            captureContent?.let { it.bind(captureOwner!!.state.value, environment) }
        } else {
            disposeCapture()
            val permission = permissionContent ?: NativePermissionRequiredView(context, materialButtons(),
                onReviewPermission, { navigate { openLibrary() } }).also {
                permissionContent = it; home.setBody(it)
            }
            permission.bindEnvironment(environment)
            permission.bind(false, input.showPermissionDenied)
        }
    }

    private fun disposeCapture() {
        val owner = captureOwner ?: return
        captureOwner = null
        captureCollection?.cancel(); captureCollection = null
        val content = captureContent; captureContent = null
        home.setBody(null)
        var failure: Throwable? = null
        try { content?.close() } catch (error: Throwable) { failure = error }
        try { owner.close() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    private fun homeNodesChanged() {
        if (closed) return
        homePointer.refresh()
        invalidate()
    }

    private fun saveFeedbackVisible() = navigation.saveFeedbackVisible(
        settingsMotion.settledProgress, libraryMotion.settledProgress)

    private fun bindAbout() {
        if (navigation.showAboutDialog) {
            val appearance = AboutContentAppearance(environment.palette,
                ReverbParagraphStyles[ReverbTextRole.HEADLINE_SMALL], ReverbParagraphStyles[ReverbTextRole.TITLE_SMALL],
                ReverbParagraphStyles[ReverbTextRole.BODY_MEDIUM], ReverbParagraphStyles[ReverbTextRole.BODY_SMALL],
                environment.textDensity, environment.sharedFontResolver)
            val dismiss = { navigate { closeAbout() } }
            val current = about
            if (current == null) {
                val created = ReverbAboutDialog(windowRoot, appearance, pendingAboutScrollPosition ?: 0, dismiss)
                pendingAboutScrollPosition = null
                about = created.also { it.show() }
            } else current.updateAppearance(appearance, dismiss)
        } else { about?.dismiss(); about = null }
    }

    private fun applyMotion(bindToolbar: Boolean) {
        home.translationY = settingsMotion.panelProgress * height
        home.topBar.setIncidentsSuppressed(navigation.showLibrary || libraryMotion.panelProgress > 0f)
        settings?.alpha = if (settingsMotion.panelProgress > 0f || navigation.showSettings) 1f else .01f
        libraryLayer?.bind(environment, statusInsets.top, libraryMotion.panelProgress, navigation.showLibrary)
        val toolbarVisible = !navigation.showSettings && libraryMotion.panelProgress > 0f && !navigation.librarySelectionActive
        if (toolbarVisible) {
            val created = libraryToolbar == null
            val toolbar = libraryToolbar ?: ReverbAppTopBarView(context, environment, this).also {
                libraryToolbar = it; addView(it)
            }
            // All toolbar inputs change through requestRender; motion only changes its membership.
            if (created || bindToolbar) toolbar.bind(environment, hasIncidentAlert, libraryNavigation(), statusInsets.top)
            if (libraryToolbarText == null) libraryToolbarText = toolbar.bindAccessibility(
                windowText.textWindow, source.geometry, source, source::retainPresentSemanticChildren)
        } else if (libraryToolbar != null) {
            val old = libraryToolbar!!
            libraryToolbar = null
            libraryToolbarText?.close(); libraryToolbarText = null
            removeView(old)
            retireShellLayouts(old)
        }
        val placement = navigation.feedbackPlacement(libraryMotion.panelProgress)
        updateDrawOrder(placement.z)
        val bottom = dp(placement.bottomPaddingDp) + if (placement.addNavigationBarBottom) navigationBottom else 0
        if (bottom != measuredFeedbackBottom) { measuredFeedbackBottom = bottom; requestLayout() }
        invalidate()
    }

    private fun updateDrawOrder(feedbackZ: Float) {
        val libraryAboveHome = navigation.showLibrary || libraryMotion.panelProgress > 0f
        val feedbackZBits = feedbackZ.toRawBits()
        // Home, feedback and incidents are fixed identities; only these child/order keys can change.
        if (drawOrder.isNotEmpty() && orderedSettings === settings && orderedLibrary === libraryLayer &&
            orderedLibraryToolbar === libraryToolbar && orderedLibraryAboveHome == libraryAboveHome &&
            orderedFeedbackZBits == feedbackZBits) return
        val entries = ArrayList<Pair<Float, View>>(6)
        settings?.let { entries += 0f to it }
        entries += 1f to home
        entries += feedbackZ to feedback
        libraryLayer?.let { entries += (if (libraryAboveHome) 3f else -2f) to it }
        libraryToolbar?.let { entries += 4f to it }
        entries += 6f to incidents
        drawOrder = entries.sortedBy { it.first }.map { it.second }
        orderedSettings = settings
        orderedLibrary = libraryLayer
        orderedLibraryToolbar = libraryToolbar
        orderedLibraryAboveHome = libraryAboveHome
        orderedFeedbackZBits = feedbackZBits
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int =
        drawOrder.getOrNull(drawingPosition)?.let(::indexOfChild)?.takeIf { it >= 0 } ?: drawingPosition

    internal fun hitChildren(): List<NativeViewPointerHitLayout> = drawOrder.asReversed().mapNotNull { view ->
        when (view) {
            home -> homePointer.layout
            settings -> settingsPointer?.layout
            libraryLayer -> libraryPointer?.layout
            incidents -> incidentsPointer.layout
            feedback -> feedback.parentPointerLayout(pointer.coordinates.windowPositionInScreen)
            libraryToolbar -> shellLayout(view)
            else -> null
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val focused = findFocus()
        val settingsAtDispatch = settings
        val settingsViewport = settingsAtDispatch?.listViewport
        val settingsKeys = settingsPointer
        if (settingsAtDispatch?.inputAllowed == true && settingsViewport != null && focused != null &&
            (event.keyCode == KeyEvent.KEYCODE_PAGE_UP || event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN) &&
            belongsTo(focused, settingsViewport)) {
            // Source Scrollable bubbles after the focused child. Bypass RecyclerView's own
            // Page-key smooth scrolling, including when the viewport itself owns focus.
            if (focused !== settingsViewport && focused.dispatchKeyEvent(event)) return true
            return settingsKeys?.pageKey(focused, event) == true
        }
        if (incidentsPointer.pageKey(focused, event) ||
            settingsPointer?.pageKey(focused, event) == true ||
            libraryPointer?.pageKey(focused, event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun focusableViewAvailable(view: View) {
        // Source Compose click nodes never announce Android focus availability on an enabled change.
        // Explicit requestFocus/traversal remains intact; existing AndroidView widgets keep theirs.
        if (view !is NativeClickPhaseHost) super.focusableViewAvailable(view)
    }

    override fun addChildrenForAccessibility(children: ArrayList<View>) {
        val first = children.size
        super.addChildrenForAccessibility(children)
        if (first < children.size) source.retainPresentLayerChildren(children, first)
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w); val height = MeasureSpec.getSize(h)
        val fullW = exact(width); val fullH = exact(height)
        for (view in listOfNotNull(settings, home, libraryLayer, incidents)) view.measure(fullW, fullH)
        libraryToolbar?.measure(fullW, atMost(height))
        val placement = navigation.feedbackPlacement(libraryMotion.panelProgress)
        val bottom = dp(placement.bottomPaddingDp) + if (placement.addNavigationBarBottom) navigationBottom else 0
        feedback.measure(atMost((width - dp(placement.horizontalPaddingDp) * 2).coerceAtLeast(0)),
            atMost((height - bottom).coerceAtLeast(0)))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (view in listOfNotNull(settings, home, libraryLayer, incidents)) view.layout(0, 0, width, height)
        libraryToolbar?.let { it.layout(0, 0, width, it.measuredHeight) }
        val placement = navigation.feedbackPlacement(libraryMotion.panelProgress)
        val bottom = height - dp(placement.bottomPaddingDp) -
            if (placement.addNavigationBarBottom) navigationBottom else 0
        val x = ((width - feedback.measuredWidth) * .5f).roundToInt()
        feedback.layout(x, bottom - feedback.measuredHeight, x + feedback.measuredWidth, bottom)
        home.translationY = settingsMotion.panelProgress * height
    }

    private fun homeInsets() = ReverbHomeInsets(statusInsets.top, visualInsets.left, visualInsets.right, visualInsets.bottom)
    private fun homeNavigation() = ReverbAppTopBarActions(
        { navigate { openAbout() } }, { navigate { openIncidents() } }, { navigate { openSettings() } })
    private fun libraryNavigation() = ReverbAppTopBarActions(
        { navigate { openAbout() } }, { navigate { openIncidentsFromLibrary() } },
        { navigate { openSettingsFromLibrary() } }, { navigate { closeLibrary() } })

    private fun ownNodeHidden(view: View) = when (view) {
        home -> !navigation.homeGesturesEnabled
        settings -> !navigation.showSettings
        libraryLayer -> !navigation.showLibrary
        else -> false
    }

    private fun sourceClip(view: View): NativeTextSemanticsClip {
        home.topBar.sourceClip(view)?.let { return it }
        libraryToolbar?.sourceClip(view)?.let { return it }
        if (clipLeases.containsKey(view) ||
            (view.parent is ReverbGroupLayout && (view.parent as ReverbGroupLayout).pointerContent === view) ||
            view is ReverbHomeControlSurface) return rectangle(view)
        settings?.let { if (belongsTo(view, it)) return it.sourceClip(view) }
        library?.let { if (belongsTo(view, it)) return it.sourceClip(view) }
        return NativeTextSemanticsClip.Unclipped
    }

    private fun materialButtons() = NativeMaterialButtonHost(environment, this, { false },
        windowText.textWindow, source.geometry, { view, _ -> source.node(view) },
        registerTextClip = { view ->
            val token = Any(); clipLeases[view] = token
            AutoCloseable { if (clipLeases[view] === token) clipLeases.remove(view) }
        }, createHit = ::directHit, admitChildren = source::retainPresentSemanticChildren)

    private fun directHit(view: View, nodes: List<NativeViewPhasePointerNode>,
        children: () -> List<NativeViewPointerHitLayout> = { emptyList() },
        clip: (Offset) -> Boolean = { true }): NativeViewPointerHitLayout = object : NativeViewPointerHitLayout {
        override val placed get() = view.isAttachedToWindow && view.visibility == VISIBLE
        override val size get() = Size(view.width.toFloat(), view.height.toFloat())
        override val minimumTouchTarget get() = Size(dpFloat(48), dpFloat(48))
        override val nodes = nodes
        override val childrenInHitOrder get() = children()
        override fun screenToLocal(screen: Offset) = pointer.coordinates.coordinates(view).local(screen)
        override fun withinLayerBounds(local: Offset) = clip(local)
    }

    private fun shellLayout(view: View): NativeViewPointerHitLayout = shellLayouts.getOrPut(view) { ShellLayout(view) }
    private inner class ShellLayout(val view: View) : NativeViewPointerHitLayout {
        private val click = (view as? NativeClickPhaseHost)?.let { NativeClickPhaseNode(view, it.nativeClickEndpoint) { false } }
        override val placed get() = view.isAttachedToWindow && view.visibility == VISIBLE
        override val size get() = Size(view.width.toFloat(), view.height.toFloat())
        override val minimumTouchTarget get() = Size(dpFloat(48), dpFloat(48))
        override val nodes get() = listOfNotNull(click)
        override val childrenInHitOrder get() = if (view is ViewGroup)
            (view.childCount - 1 downTo 0).map { shellLayout(view.getChildAt(it)) } else emptyList()
        override fun screenToLocal(screen: Offset) = pointer.coordinates.coordinates(view).local(screen)
        override fun withinLayerBounds(local: Offset) =
            (view as? NativePointerLayerShape)?.containsPointerLayer(local.x, local.y) ?: true
        fun close() { click?.cancel() }
    }

    private fun retireShellLayouts(root: View) {
        shellLayouts.keys.filter { belongsTo(it, root) }.forEach { shellLayouts.remove(it)?.close() }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(action: () -> Unit) { try { action() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        } }
        release(::cancelPointerInput)
        release(settingsMotion::close); release(libraryMotion::close)
        release { about?.dismiss() }; about = null
        release(homePointer::close); release(incidentsPointer::close)
        release { settingsPointer?.close() }; release { libraryPointer?.close() }
        shellLayouts.values.forEach { release(it::close) }; shellLayouts.clear()
        release(homeText::close); release { libraryToolbarText?.close() }
        release { settingsText?.close() }; release { libraryText?.close() }
        release(::disposeCapture)
        release { permissionContent?.close() }; permissionContent = null
        release { settings?.close() }; release { library?.close() }
        release { directoryRegistration?.close() }; directoryRegistration = null
        release(incidents::close); release(incidentsOwner::close)
        release(feedback::close); release(scrollEvents::close)
        scope.cancel()
        clipLeases.clear()
        removeAllViews()
        failure?.let { throw it }
    }

    private fun belongsTo(view: View, ancestor: View): Boolean {
        var cursor: View? = view
        while (cursor != null) { if (cursor === ancestor) return true; cursor = cursor.parent as? View }
        return false
    }
    private fun rectangle(view: View) = NativeTextSemanticsClip.Rectangle(Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
    private fun dpFloat(value: Int) = value * environment.textDensity.density
    private fun dp(value: Int) = dpFloat(value).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
    private fun atMost(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.AT_MOST)

    companion object {
        fun create(context: Context, environment: ReverbViewEnvironment, input: NativeMainScreenInput,
            lifecycleOwner: LifecycleOwner, dispatcher: OnBackPressedDispatcher, parentScope: CoroutineScope,
            accessibilityManager: AccessibilityManager?, windowRoot: View,
            windowText: NativeWindowTextAccessibilityOwner, restored: NativeMainScreenSavedState?,
            onReviewPermission: () -> Unit, onThemeChanged: (AppThemeMode?) -> Unit,
            registerDirectoryResult: ((Uri?) -> Unit) -> NativeDirectoryPickerRegistration,
            releaseInputFocus: () -> Unit): NativeMainScreenView =
            NativeMainScreenView(context, environment, input, lifecycleOwner, dispatcher, parentScope,
                accessibilityManager, windowRoot, windowText, restored, onReviewPermission, onThemeChanged,
                registerDirectoryResult, releaseInputFocus, MainPointerGeometry())
    }
}

private class MainPointerGeometry {
    lateinit var owner: NativeMainScreenView
    val coordinates = NativeWindowPointerCoordinates { owner }
    val layout = object : NativeViewPointerHitLayout {
        override val placed get() = ::owner.isInitialized && owner.isAttachedToWindow
        override val size get() = if (::owner.isInitialized) Size(owner.width.toFloat(), owner.height.toFloat()) else Size.Zero
        override val minimumTouchTarget get() = Size.Zero
        override val nodes = emptyList<NativeViewPhasePointerNode>()
        override val childrenInHitOrder get() = owner.hitChildren()
        override fun screenToLocal(screen: Offset) = coordinates.coordinates(owner).local(screen)
        override fun withinLayerBounds(local: Offset) = true
    }
}
