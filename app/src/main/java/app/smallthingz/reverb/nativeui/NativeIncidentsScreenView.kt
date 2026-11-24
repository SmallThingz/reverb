package app.smallthingz.reverb.nativeui

import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeTextSemanticsClipPolicy
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.MotionDurationScale
import androidx.lifecycle.LifecycleOwner
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import app.smallthingz.reverb.IncidentHistoryUiState
import app.smallthingz.reverb.NativeIncidentsRenderer
import app.smallthingz.reverb.NativeIncidentsScreenOwner
import smallthingz.ui.scroll.NativeWindowScrollAccessibilityEvents
import smallthingz.ui.reverb.NativeIncidentsSavedPosition
import smallthingz.ui.reverb.ReverbIncidentListPosition
import smallthingz.ui.reverb.ReverbIncidentsContentView
import smallthingz.ui.reverb.reverbIncidentSafeInsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * Retained native mount point for MainScreen's conditionally composed Incidents overlay. Its
 * history owner outlives this renderer. Actual content/list/menu state exists only while the
 * released overlay predicate is true; reopening after fully closing starts a fresh list.
 *
 * Parent owns saved navigation, overall z-order (source zIndex6), the shared window pointer/text
 * owners and lifecycle runtime. This view never creates another history observer or frame clock.
 */
internal class NativeIncidentsScreenView(
    context: Context,
    private var environment: ReverbViewEnvironment,
    private val owner: NativeIncidentsScreenOwner,
    private val parentScope: CoroutineScope,
    lifecycleOwner: LifecycleOwner,
    dispatcher: OnBackPressedDispatcher,
    private val inputRoot: View,
    private val windowText: NativeWindowTextAccessibilityOwner,
    private val sourceNode: (View) -> NativeSourceAccessibilityNode,
    private val overlayVisibilityChanged: (Boolean) -> Unit = {},
    private val scrollEvents: NativeWindowScrollAccessibilityEvents,
    restoredPosition: NativeListSavedPosition? = null,
    private val admitChildren: ((ArrayList<View>, Int, (View) -> View?) -> Unit)? = null,
) : FrameLayout(context), NativeIncidentsRenderer, AutoCloseable {
    private val savedPosition = NativeIncidentsSavedPosition(restoredPosition?.let {
        ReverbIncidentListPosition(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset)
    })
    private var closed = false
    private var state = owner.state
    private var progress = owner.openProgress
    private var overlay = false
    private var content: ReverbIncidentsContentView? = null
    private var textLease: NativePageTextAccessibilityOwner? = null
    private var headerAccessibilityLease: AutoCloseable? = null
    private var scrollList: ReverbLibraryRecyclerView? = null
    private var scrollLease: NativeLibraryRecyclerAccessibility? = null
    private var panelScope: CoroutineScope? = null
    private var backLease: AutoCloseable? = null
    private var renderLease: AutoCloseable? = null
    private var statusBarInsets = Insets.NONE
    private var pointerMembershipChanged: (() -> Unit)? = null

    val pointerEnvironment: ReverbViewEnvironment get() = environment
    val pointerContent: ReverbIncidentsContentView? get() = content

    /** Pending unconsumed restore survives initial loading; closed/empty consumed lists do not. */
    fun savePosition(): NativeListSavedPosition? = (content?.savePosition() ?: savedPosition.save())?.let {
        NativeListSavedPosition(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset)
    }

    /** One Main-window pointer binding; source mount/branch changes retire its input immediately. */
    fun observePointerMembership(changed: () -> Unit): AutoCloseable {
        check(pointerMembershipChanged == null) { "Incidents already has a pointer binding" }
        pointerMembershipChanged = changed
        return AutoCloseable { if (pointerMembershipChanged === changed) pointerMembershipChanged = null }
    }

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        visibility = GONE
        backLease = owner.registerBack(dispatcher, lifecycleOwner,
            selectionActive = { content?.selectionActive == true },
            consumeSelectionBack = { content?.consumeSelectionBack() == true })
        try { renderLease = owner.connect(this) }
        catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    override fun historyChanged(state: IncidentHistoryUiState) {
        if (closed) return
        this.state = state
        content?.bind(state.nativeHistory)
    }

    override fun panelChanged(visible: Boolean, openProgress: Float) {
        if (closed) return
        progress = openProgress.coerceIn(0f, 1f)
        if (visible != overlay) {
            overlay = visible
            if (visible) mount() else unmount()
            visibility = if (visible) VISIBLE else GONE
            overlayVisibilityChanged(visible)
        }
        content?.setPanelProgress(progress)
    }

    private fun mount() {
        val scope = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))
        panelScope = scope
        val frames = requireNotNull(scope.coroutineContext[MonotonicFrameClock])
        try {
            val shown = ReverbIncidentsContentView(context, environment.theme, incidentTexts(context),
                incidentContentActions(context, owner.history), owner::requestBack, frames, scope,
                { scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f }, inputRoot, savedPosition)
            content = shown
            shown.bindSourceAccessibility(sourceNode)
            shown.setStatusBarInsets(statusBarInsets)
            addView(shown, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            val clips = NativeTextSemanticsClipPolicy(shown::semanticsClip)
            textLease = windowText.bindContent(shown, clips, sourceNode) {
                !closed && content === shown && sourceNode(it).present
            }
            headerAccessibilityLease = shown.bindHeaderAccessibility(windowText.textWindow,
                windowText.contentGeometry(shown, clips), sourceNode)
            shown.observeAccessibilityMembership {
                textLease?.refresh()
                refreshScrollAccessibility()
                pointerMembershipChanged?.invoke()
            }
            shown.bind(state.nativeHistory)
        } catch (failure: Throwable) {
            try { unmount() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** The source creates LazyColumn only for nonempty history; loading/empty is a plain Box. */
    private fun refreshScrollAccessibility() {
        val next = content?.pointerList
        if (scrollList === next) return
        val previous = scrollLease
        scrollLease = null
        scrollList = null
        previous?.close()
        scrollList = next
        scrollLease = next?.let { NativeLibraryRecyclerAccessibility(it, sourceNode, scrollEvents, admitChildren) }
    }

    /** Delivered by the one main-window NativeWindowInsetsBinding, before child consumption. */
    fun setWindowInsets(insets: WindowInsetsCompat) {
        statusBarInsets = reverbIncidentSafeInsets(insets)
        content?.setStatusBarInsets(statusBarInsets)
    }

    /** Palette changes retain list/scroll/row identity; window density/resolver remain host-owned. */
    fun bindPalette(palette: ReverbPalette) {
        if (closed) return
        environment = environment.withPalette(palette)
        content?.bindTheme(environment.theme)
    }

    private fun unmount() {
        val current = content
        content = null
        val scope = panelScope
        panelScope = null
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
        }
        cleanup { pointerMembershipChanged?.invoke() }
        cleanup { refreshScrollAccessibility() }
        current?.observeAccessibilityMembership {}
        cleanup { textLease?.close() }
        textLease = null
        cleanup { headerAccessibilityLease?.close() }
        headerAccessibilityLease = null
        cleanup { current?.dispose() }
        cleanup { if (current != null) removeView(current) }
        cleanup { scope?.cancel() }
        failure?.let { throw it }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
        }
        cleanup { renderLease?.close() }
        renderLease = null
        cleanup { backLease?.close() }
        backLease = null
        cleanup { unmount() }
        visibility = GONE
        failure?.let { throw it }
    }
}
