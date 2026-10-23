package app.smallthingz.reverb

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import smallthingz.ui.graphics.Matrix
import smallthingz.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.nativeui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Equivalent of rememberPagerState/three rememberScrollState saver payloads. */
internal data class NativeOnboardingViewportSavedState(
    val page: Int = 0,
    val offsetFraction: Float = 0f,
    val verticalPositions: List<Int> = listOf(0, 0, 0),
) {
    init { require(page in 0..2 && offsetFraction in -.5f.. .5f && verticalPositions.size == 3) }
}

/**
 * The existing Activity/window supplies its actual safeDrawing insets and immutable environment.
 * Owner publishes draft/permission state; this renderer owns only the ONE pager/navigation state.
 * No new permission prompts, screen-state bus, Composition, DSL runtime or alternate render tree.
 *
 * The real host owns pager/vertical placement, one window accessibility queue, source occlusion,
 * predictive motion and insets. Source compilation is not native parity evidence; the isolated
 * full-screen fixture retains exact pixels/actions/restoration checks before production routing.
 */
internal class NativeOnboardingScreenView private constructor(
    context: Context,
    private var environment: ReverbViewEnvironment,
    initialState: OnboardingPageContentState,
    private val lifecycleOwner: LifecycleOwner,
    private val actions: OnboardingPageActions,
    private val onFinish: () -> Unit,
    private val pointer: OnboardingPointerGeometry,
    parentScope: CoroutineScope,
    private val dispatcher: OnBackPressedDispatcher,
    private val restored: NativeOnboardingViewportSavedState,
    externalWindowText: NativeWindowTextAccessibilityOwner?,
    observeWindowInsets: Boolean,
) : NativePagerTouchRoot(
    context, pointer.coordinates::rootToScreen, pointer.coordinates::coordinates,
    pointer.rootLayout, pointer.coordinates::begin,
), AutoCloseable {
    override fun focusableViewAvailable(view: View) {
        // Source clickables are virtual Compose focus nodes: enabling Back does not announce a new
        // Android child to ViewRoot and steal focus after a semantic click. Explicit requestFocus
        // and keyboard traversal still use the existing native focus path. Real AndroidView widgets
        // retain their platform availability behavior.
        if (view !is NativeClickPhaseHost) super.focusableViewAvailable(view)
    }

    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    // Caller supplies the ONE reviewed native window runtime: preserve its lifecycle-gated frame
    // clock and duration-scale context. Replacing either with raw AndroidUiDispatcher is incorrect.
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob)
    private val frames = requireNotNull(scope.coroutineContext[MonotonicFrameClock])
    private val density = environment.textDensity
    private val navigation = NativeOnboardingNavigationState()
    private var state = initialState
    private var closed = false
    private var reportedPage = -1
    private var contentLaidOut = false
    private var safeDrawing = Rect()
    private var pageHeight = 0
    private var innerLeft = 0
    private var innerTop = 0
    private var inputScope: CoroutineScope? = null
    private var inputJob: Job? = null
    private var backFrameJob: Job? = null
    private var backEdge = BackEventCompat.EDGE_NONE
    // Same released rememberPredictiveBackMotion helper as About; reuse the reviewed scalar engine.
    private val backMotion = ReverbAboutPredictiveMotion()
    private val pager = NativeOnboardingPager(scope, density, onChanged = ::pagerChanged)
    private val pageArea = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
    private val overscroll = NativeHorizontalOverscroll(context, density, 0xff666666.toInt()) { invalidate() }
    private val scrolling = NativeOnboardingPagerScrollOwner(pager, overscroll) {
        this::viewport.isInitialized && viewport.isAttachedToWindow
    }
    private val scrollStates = List(3) { NativeVerticalScrollState(restored.verticalPositions[it]) }
    private val interactions = OnboardingNativeInteractionsFactory { this }
    private val typography = OnboardingPageTypography(
        ReverbParagraphStyles[ReverbTextRole.HEADLINE_MEDIUM],
        ReverbParagraphStyles[ReverbTextRole.TITLE_MEDIUM],
        ReverbParagraphStyles[ReverbTextRole.BODY_LARGE],
        ReverbParagraphStyles[ReverbTextRole.BODY_MEDIUM],
        ReverbParagraphStyles[ReverbTextRole.BODY_SMALL],
    )
    private val pages: List<OnboardingPageContentView> = List(3) { page -> createPage(page, initialState) }
    private lateinit var viewport: NativeOnboardingPlacedPagerViewport
    private lateinit var horizontal: NativeOnboardingPagerPhaseBinding
    private val vertical = java.util.IdentityHashMap<NativeVerticalViewport, NativeVerticalPhaseBinding>()
    private val verticalAccessibility = java.util.IdentityHashMap<NativeVerticalViewport, NativeVerticalScrollAccessibilityBinding>()
    private val footer = OnboardingFooterView(context, ReverbParagraphStyles[ReverbTextRole.LABEL_LARGE],
        density, environment.sharedFontResolver, interactions)
    private val pageActions = mutableListOf<AutoCloseable>()
    private val pageText = mutableListOf<AutoCloseable>()
    private var preview: OnboardingPageContentView? = null
    private var previewText: AutoCloseable? = null
    private var previewActions: AutoCloseable? = null
    private var footerActions: AutoCloseable? = null
    private var footerText: AutoCloseable? = null
    val sourceTextTransforms = NativeTextSemanticsTransformPolicy(::populateSourceTextTransform)
    private val windowText = externalWindowText ?: NativeWindowTextAccessibilityOwner(
        this, NativeTextSemanticsClipPolicy(::sourceWindowTextClip),
        rootContentClip = { smallthingz.ui.geometry.Rect(0f, 0f, width.toFloat(), height.toFloat()) },
        transformPolicy = sourceTextTransforms)
    var externalAccessibilityOcclusion: () -> List<android.graphics.Rect> = { emptyList() }
    private val accessibility = NativeOnboardingAccessibility(this, { viewport }, { preview }, footer,
        externalOcclusion = { externalAccessibilityOcclusion() }, transformPolicy = sourceTextTransforms)
    private val scrollEvents = windowText.openScrollEvents(scope,
        onScrollGeometryChanged = { invalidate() },
        // Actual native Views own their live focus bounds; there is no copied semantics cache.
        onBatchFinished = {},
    )
    private lateinit var pagerAccessibility: NativeOnboardingPagerAccessibility
    private val insets = if (observeWindowInsets) NativeWindowInsetsBinding(this) { delivered ->
        val value = delivered.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
            androidx.core.view.WindowInsetsCompat.Type.ime() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
        setSafeDrawingInsets(Rect(value.left, value.top, value.right, value.bottom))
    } else null
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(event: BackEventCompat) {
            beginBack()
            backEdge = event.swipeEdge
            applyMotion()
        }
        override fun handleOnBackProgressed(event: BackEventCompat) {
            if (!backMotion.active) beginBack()
            backEdge = event.swipeEdge
            backMotion.progressed(event.progress)
            applyMotion()
        }
        override fun handleOnBackCancelled() = cancelBack()
        override fun handleOnBackPressed() {
            if (!backMotion.active || backMotion.hasContinuation) beginBack() // New ordinary Back replaces an earlier commit stream.
            if (backMotion.committed()) {
                applyMotion()
                finishBackCommit()
            } else scheduleBackFrames()
        }
    }

    init {
        pointer.root = this
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        viewport = NativeOnboardingPlacedPagerViewport(context, pager, density, pages, overscroll, frames, scope) {
            // Geometry/membership changes alone do not mutate the retained per-page scroll state.
            pointer.retireRemovedClicks()
        }
        horizontal = NativeOnboardingPagerPhaseBinding(viewport, pager, scrolling,
            { requireNotNull(inputScope) }, { ViewConfiguration.get(context).scaledTouchSlop.toFloat() },
            { ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat() },
            { !stateIsFinishing && !backMotion.active })
        for (page in pages) {
            addVertical(page)
            pageActions += page.connect(lifecycleOwner, actions)
            pageText += page.bindTextAccessibility(windowText, accessibility::node) { child ->
                !closed && accessibility.node(child).present
            }
        }
        pageArea.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        addView(pageArea, FrameLayout.LayoutParams(-1, -1))
        addView(footer, FrameLayout.LayoutParams(-1, -2))
        pagerAccessibility = NativeOnboardingPagerAccessibility(viewport, pager, scope,
            accessibility.geometry, { userScrollEnabled }, accessibility::pagerNode, scrollEvents)
        viewport.sourceAccessibility = pagerAccessibility
        viewport.accessibilityDelegate = pagerAccessibility
        footerActions = footer.connect(
            { navigate((pager.settledPage - 1).coerceAtLeast(0)) },
            ::continueOrFinish,
        )
        footerText = footer.bindTextAccessibility(windowText, accessibility::node) { child ->
            !closed && accessibility.node(child).present
        }
        dispatcher.addCallback(lifecycleOwner, backCallback)
        pager.requestScrollToPage(restored.page, restored.offsetFraction)
        publishState()
    }

    private val beyondBoundsFocus = NativePagerBeyondBoundsFocus(
        focused = ::findFocus,
        placed = { viewport.placedPages },
        existingPages = { viewport.focusPlacedPages() },
        userScrollEnabled = { userScrollEnabled },
        pageOf = { view ->
            var cursor: View? = view
            var found: Int? = null
            while (cursor != null && cursor !== this) {
                val index = pages.indexOf(cursor)
                if (index >= 0) { found = index; break }
                cursor = cursor.parent as? View
            }
            found
        },
        candidatesInPlacementOrder = { page ->
            buildList {
                fun visit(view: View) {
                    if (view.visibility != View.VISIBLE) return
                    if (view.isFocusable && (view is NativeClickPhaseHost || view is SilenceCurveView ||
                        (view.parent is CompressionSliderView && (view.parent as CompressionSliderView).pointerView === view))) add(view)
                    if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
                }
                visit(pages[page])
            }
        },
        rtl = { layoutDirection == View.LAYOUT_DIRECTION_RTL },
        pageBounds = { page -> nativeBringIntoViewLocalRect(this, pages[page].sourceVerticalViewport,
            smallthingz.ui.geometry.Rect(0f, 0f, pages[page].width.toFloat(), pages[page].height.toFloat())) },
        focusBounds = { view -> nativeBringIntoViewLocalRect(this, view,
            smallthingz.ui.geometry.Rect(0f, 0f, view.width.toFloat(), view.height.toFloat())) },
        withExtraPage = { page, block -> viewport.withFocusSearchPage(page, block) },
    )

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (super.dispatchKeyEvent(event)) return true // Descendant's activation/page key wins first.
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val forward = when (event.keyCode) {
            KeyEvent.KEYCODE_TAB -> !event.isShiftPressed
            KeyEvent.KEYCODE_NAVIGATE_NEXT -> true
            KeyEvent.KEYCODE_NAVIGATE_PREVIOUS -> false
            else -> {
                val direction = when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> smallthingz.ui.focus.FocusDirection.Left
                    KeyEvent.KEYCODE_DPAD_RIGHT -> smallthingz.ui.focus.FocusDirection.Right
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> smallthingz.ui.focus.FocusDirection.Up
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> smallthingz.ui.focus.FocusDirection.Down
                    else -> return false
                }
                return beyondBoundsFocus.moveDirectional(direction)
            }
        }
        return beyondBoundsFocus.move(forward)
    }

    private val stateIsFinishing get() = !state.controlsEnabled
    private val userScrollEnabled get() = !stateIsFinishing && !backMotion.active

    /** Replacement keeps draft state external; no per-page copy mutates an owner independently. */
    fun bind(value: OnboardingPageContentState) {
        state = value
        pages.forEach { it.bind(value, environment.palette) }
        preview?.bind(value.copy(interactionEnabled = false), environment.palette)
        if (stateIsFinishing && backMotion.active) cancelBack()
        publishState()
        pointer.retireRemovedClicks()
    }

    /** Activity palette preview keeps this same pager, drafts, gestures and semantic identities. */
    fun setPalette(palette: ReverbPalette) {
        if (environment.palette === palette) return
        environment = ReverbViewEnvironment(environment.textDensity, environment.sharedFontResolver, palette)
        bind(state)
    }

    /** Actual source Surface and pager clips outside each page subtree, including shared Activity ownership. */
    fun sourceWindowTextClip(view: View): NativeTextSemanticsClip =
        if (view === this || (this::viewport.isInitialized && view === viewport))
            NativeTextSemanticsClip.Rectangle(smallthingz.ui.geometry.Rect(
                0f, 0f, view.width.toFloat(), view.height.toFloat()))
        else NativeTextSemanticsClip.Unclipped

    /** Only this known port of the preview graphicsLayer overrides semantic transform math. */
    private fun populateSourceTextTransform(view: View, target: Matrix): Boolean {
        if (view !== preview) return false
        populateOnboardingPreviewSemanticTransform(target, view.width, view.height,
            view.translationX, view.translationY, view.scaleX, view.scaleY)
        return true
    }

    fun setSafeDrawingInsets(insets: Rect) {
        if (safeDrawing == insets) return
        safeDrawing = Rect(insets)
        requestLayout()
    }

    fun saveViewportState() = NativeOnboardingViewportSavedState(
        pager.currentPage, pager.currentPageOffsetFraction.coerceIn(-.5f, .5f),
        scrollStates.map { it.value },
    )

    private fun createPage(page: Int, value: OnboardingPageContentState): OnboardingPageContentView = OnboardingPageContentView(
        context, page, typography, density, environment.sharedFontResolver, value,
        environment.palette, scrollStates[page], frames, scope, scrolling.pageNestedHost, interactions,
        inputRoot = { this },
        parentInterested = { change ->
            (this::horizontal.isInitialized && horizontal.interested(change)) ||
                vertical[pages[page].sourceVerticalViewport]?.interested(change) == true
        },
        audioStructureChanged = pointer::retireRemovedClicks,
    )

    private fun addVertical(page: OnboardingPageContentView) {
        val source = page.sourceVerticalViewport
        source.sourceAccessibilityNode = { accessibility.node(source) }
        source.sourceAccessibilityChildren = { accessibility.children(source) }
        verticalAccessibility[source] = NativeVerticalScrollAccessibilityBinding(source, scrollEvents) {
            !closed && accessibility.node(source).present
        }
        vertical[source] = NativeVerticalPhaseBinding(source,
            { ViewConfiguration.get(context).scaledTouchSlop.toFloat() },
            { ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat() },
            { change -> page.parent === viewport && horizontal.interested(change) })
    }

    private fun pagerChanged() {
        if (!this::viewport.isInitialized) return
        viewport.onPagerChanged()
        publishState()
    }

    private fun publishState() {
        val target = navigation.predictiveBackTarget
        val page = target?.sourcePage ?: pager.settledPage
        val footerPage = target?.sourcePage ?: minOf(page, pager.targetPage)
        footer.bind(OnboardingFooterState(page, footerPage,
            footerPage != 0 || state.permissions.isReadyForHighlightedContinue(), stateIsFinishing,
            calibrationRequired = state.skipSilence && state.calibrationRequired,
            calibrating = state.silenceState.calibrating),
            environment.palette)
        if (this::horizontal.isInitialized) horizontal.updateEnabled()
        backCallback.isEnabled = (page > 0 || target != null) && !stateIsFinishing && !closed
        val visiblePage = if (!closed && inputScope != null && isAttachedToWindow && contentLaidOut && isShown &&
            windowVisibility == VISIBLE && !stateIsFinishing && !backMotion.active &&
            !pager.isScrollInProgress && pager.targetPage == pager.settledPage) pager.settledPage else -1
        if (reportedPage != visiblePage) {
            reportedPage = visiblePage // Set before callback: owner publication may synchronously bind this screen.
            actions.pageChanged(visiblePage)
        }
    }

    private fun navigate(target: Int) {
        if (!navigation.mayNavigate()) return
        val owner = navigation.begin() // Acquired before coroutine dispatch, like the released callback.
        val job = scope.launch { pager.animateScrollToPage(target) }
        job.invokeOnCompletion { navigation.complete(owner); if (!closed) publishState() }
        navigation.attachCancellation(owner) { job.cancel() }
    }

    private fun continueOrFinish() {
        val current = minOf(pager.settledPage, pager.targetPage)
        if (current < 2) navigate(current + 1)
        else if (!state.silenceState.calibrating &&
            navigation.mayFinish(pager.settledPage == 2, pager.isScrollInProgress, backMotion.active)) onFinish()
    }

    private fun beginBack() {
        backFrameJob?.cancel()
        navigation.predictiveBackTarget?.let(navigation::endPredictiveBack)
        // Pin before cancelling button animation; its cancellation may change settledPage.
        val target = navigation.beginPredictiveBack(pager.settledPage)
        pager.requestScrollToPage(target.sourcePage)
        backMotion.started()
        backEdge = BackEventCompat.EDGE_NONE
        replacePreview(target)
        applyMotion()
    }

    private fun replacePreview(target: OnboardingBackTarget) {
        clearPreview()
        if (target.sourcePage <= 0) return
        val shown = createPage(target.destinationPage, state.copy(interactionEnabled = false))
        preview = shown
        addVertical(shown)
        previewActions = shown.connect(lifecycleOwner, OnboardingPageActions({}, {}, {}, {}, {}))
        // The preview's source own semantics node is hidden, not all of its descendants.
        // Its children remain present where the translated pager does not cover their bounds.
        previewText = shown.bindTextAccessibility(windowText, accessibility::node) { child ->
            !closed && accessibility.node(child).present
        }
        pageArea.addView(shown, 0, FrameLayout.LayoutParams(-1, -1))
    }

    private fun clearPreview() {
        preview?.let { shown ->
            shown.close()
            pageArea.removeView(shown)
            vertical.remove(shown.sourceVerticalViewport)?.close()
            verticalAccessibility.remove(shown.sourceVerticalViewport)?.close()
        }
        previewText?.close()
        previewActions?.close()
        previewText = null
        previewActions = null
        preview = null
    }

    private fun cancelBack() {
        backFrameJob?.cancel()
        backFrameJob = null
        backMotion.cancelled()
        navigation.predictiveBackTarget?.let(navigation::endPredictiveBack)
        clearPreview()
        backEdge = BackEventCompat.EDGE_NONE
        applyMotion()
    }

    private fun scheduleBackFrames() {
        backFrameJob?.cancel()
        backFrameJob = scope.launch {
            while (isActive && backMotion.hasContinuation) {
                val completed = frames.withFrameNanos { time -> backMotion.frame(time, scope.coroutineContext[smallthingz.ui.MotionDurationScale]?.scaleFactor ?: 1f) }
                applyMotion()
                if (completed) finishBackCommit()
            }
        }
    }

    private fun finishBackCommit() {
        val target = navigation.predictiveBackTarget
        if (target != null && navigation.ownsPredictiveBack(target)) pager.requestScrollToPage(target.destinationPage)
        backMotion.finishCommitCallback()
        if (target != null) navigation.endPredictiveBack(target)
        clearPreview()
        backEdge = BackEventCompat.EDGE_NONE
        applyMotion()
    }

    private fun applyMotion() {
        val progress = if (backMotion.active) backMotion.progress.coerceIn(0f, 1f) else 0f
        val direction = predictiveBackHorizontalDirection(backEdge)
        viewport.translationX = direction * pageArea.width * progress
        viewport.alpha = 1f - .05f * progress
        preview?.let {
            it.translationX = -direction * pageArea.width * .08f * (1f - progress)
            it.scaleX = .97f + .03f * progress
            it.scaleY = it.scaleX
            it.alpha = (progress * 1.45f).coerceIn(0f, 1f)
        }
        publishState()
    }

    override fun onMeasure(w: Int, h: Int) {
        check(MeasureSpec.getMode(w) == MeasureSpec.EXACTLY && MeasureSpec.getMode(h) == MeasureSpec.EXACTLY)
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        innerLeft = safeDrawing.left + with(density) { 24.dp.roundToPx() }
        innerTop = safeDrawing.top + with(density) { 20.dp.roundToPx() }
        val contentWidth = (width - safeDrawing.left - safeDrawing.right -
            2 * with(density) { 24.dp.roundToPx() }).coerceAtLeast(0)
        val contentHeight = (height - safeDrawing.top - safeDrawing.bottom -
            2 * with(density) { 20.dp.roundToPx() }).coerceAtLeast(0)
        footer.measure(exact(contentWidth), MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.AT_MOST))
        pageHeight = (contentHeight - footer.measuredHeight).coerceAtLeast(0)
        pageArea.measure(exact(contentWidth), exact(pageHeight))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        pageArea.layout(innerLeft, innerTop, innerLeft + pageArea.measuredWidth, innerTop + pageHeight)
        footer.layout(innerLeft, innerTop + pageHeight, innerLeft + footer.measuredWidth,
            innerTop + pageHeight + footer.measuredHeight)
        // View sets its first isLaidOut flag after this callback returns; our children are already
        // laid out here. Publish readiness now, without waiting for an unrelated second layout.
        contentLaidOut = true
        applyMotion()
    }

    override fun onAttachedToWindow() {
        contentLaidOut = false
        // Parent is attached before children: the horizontal binding asks for this attached scope.
        inputJob = SupervisorJob(ownerJob)
        inputScope = CoroutineScope(scope.coroutineContext + requireNotNull(inputJob))
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        contentLaidOut = false
        inputJob?.cancel()
        inputJob = null
        inputScope = null
        cancelBack()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (this::viewport.isInitialized) publishState()
    }

    override fun close() {
        if (closed) return
        closed = true
        backCallback.remove()
        insets?.close()
        cancelPointerInput()
        pointer.close()
        cancelBack()
        horizontal.close()
        vertical.values.toList().forEach(AutoCloseable::close)
        vertical.clear()
        verticalAccessibility.values.toList().forEach(AutoCloseable::close)
        verticalAccessibility.clear()
        pagerAccessibility.close()
        scrollEvents.close()
        pages.forEach(AutoCloseable::close)
        pageActions.forEach(AutoCloseable::close)
        pageText.forEach(AutoCloseable::close)
        footerActions?.close()
        footerText?.close()
        scope.cancel()
    }

    internal fun pointerNodes(view: View): List<NativeViewPhasePointerNode> {
        if (view is SilenceCurveView) return listOf(pointer.interop(view))
        val audio = pages[2].pointerNodes(view)
        if (audio.isNotEmpty()) return audio
        return when {
        view === viewport -> horizontal.nodes
        view is NativeVerticalViewport -> vertical[view]?.let { listOf(it.observerNode, it.dragNode) } ?: emptyList()
        view is NativeClickPhaseHost -> listOf(pointer.click(view, view.nativeClickEndpoint) { change ->
            var parent = view.parent
            while (parent is View) {
                if (parent is NativeVerticalViewport) return@click vertical[parent]?.interested(change) == true
                parent = parent.parent
            }
            false
        })
        else -> emptyList()
        }
    }

    internal fun isPager(view: View) = view === viewport
    internal fun isFooterButton(view: View) = view is NativeClickPhaseHost && view.parent === footer
    private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    companion object {
        fun create(
            context: Context, environment: ReverbViewEnvironment, state: OnboardingPageContentState,
            lifecycleOwner: LifecycleOwner, actions: OnboardingPageActions, onFinish: () -> Unit,
            parentScope: CoroutineScope, dispatcher: OnBackPressedDispatcher,
            restored: NativeOnboardingViewportSavedState = NativeOnboardingViewportSavedState(),
            externalWindowText: NativeWindowTextAccessibilityOwner? = null,
            observeWindowInsets: Boolean = true,
        ): NativeOnboardingScreenView = NativeOnboardingScreenView(context, environment, state,
            lifecycleOwner, actions, onFinish, OnboardingPointerGeometry(environment),
            parentScope, dispatcher, restored, externalWindowText, observeWindowInsets)
    }
}

/** Input adapters for ACTUAL Views; no copied rendering/state hierarchy or per-frame registry. */
private class OnboardingPointerGeometry(private val environment: ReverbViewEnvironment) {
    lateinit var root: NativeOnboardingScreenView
    val coordinates = NativeWindowPointerCoordinates { root }
    private val clicks = java.util.IdentityHashMap<View, NativeClickPhaseNode>()
    private val interop = java.util.IdentityHashMap<View, NativeAndroidInteropNode>()
    val rootLayout: NativeViewPointerHitLayout = Layout { root }
    fun click(view: View, endpoint: NativeClickPhaseEndpoint, interested: (NativeDragPointerChange) -> Boolean) =
        clicks.getOrPut(view) { NativeClickPhaseNode(view, endpoint, interested) }
    fun interop(view: SilenceCurveView) = interop.getOrPut(view) {
        NativeAndroidInteropNode(view, (view.parent as NativeAndroidInteropHost).nativeInteropRequests)
    }

    fun retireRemovedClicks() {
        fun belongs(view: View): Boolean {
            var cursor: View? = view
            while (cursor != null) {
                if (cursor === root) return true
                cursor = cursor.parent as? View
            }
            return false
        }
        clicks.keys.filterNot(::belongs).forEach { clicks.remove(it)?.cancel() }
        interop.keys.filterNot(::belongs).forEach { interop.remove(it)?.close() }
    }

    fun close() {
        clicks.values.forEach { it.cancel() }
        clicks.clear()
        interop.values.forEach(AutoCloseable::close)
        interop.clear()
    }

    private inner class Layout(private val getView: () -> View) : NativeViewPointerHitLayout {
        private val view get() = getView()
        override val placed get() = view.isAttachedToWindow && view.visibility == View.VISIBLE
        override val size get() = Size(view.width.toFloat(), view.height.toFloat())
        override val minimumTouchTarget get() = Size(48f * environment.textDensity.density, 48f * environment.textDensity.density)
        override val nodes get() = root.pointerNodes(view)
        override val childrenInHitOrder: List<NativeViewPointerHitLayout> get() {
            val group = view as? ViewGroup ?: return emptyList()
            return (group.childCount - 1 downTo 0).map { index -> Layout { group.getChildAt(index) } }
        }
        override fun screenToLocal(screen: Offset) = coordinates.coordinates(view).local(screen)
        override fun withinLayerBounds(local: Offset): Boolean {
            val target = view
            val width = target.width.toFloat()
            val height = target.height.toFloat()
            val margin = with(environment.textDensity) { 30.dp.roundToPx() }.toFloat()
            return when {
                root.isPager(target) -> local.x >= 0f && local.x < width && local.y >= -margin && local.y < height + margin
                target is NativeVerticalViewport -> local.y >= 0f && local.y < height && local.x >= -margin && local.x < width + margin
                target is SettingsExpansionView -> local.x >= 0f && local.x < width && local.y >= 0f && local.y < height
                target is NativePointerLayerShape -> target.containsPointerLayer(local.x, local.y)
                target is ReverbGroupLayout -> target.containsVisualPoint(local.x, local.y)
                target is SettingsOutputCompressionView || target is SettingsSilenceView -> true // One outer group clips both consecutive controls.
                target.parent is ReverbGroupLayout -> reverbGroupGeometry(width, height, environment.textDensity.density, 20f)
                    .contains(local.x, local.y)
                root.isFooterButton(target) -> reverbGroupGeometry(width, height, environment.textDensity.density,
                    minOf(width, height) / (2f * environment.textDensity.density)).contains(local.x, local.y)
                else -> true // The remaining released Column/Box/page/body boundaries have no clip.
            }
        }
    }
}
