package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import androidx.lifecycle.LifecycleOwner
import androidx.core.graphics.Insets
import app.smallthingz.reverb.IncidentHistoryUiState
import app.smallthingz.reverb.NativeIncidentsRenderer
import app.smallthingz.reverb.NativeIncidentsScreenOwner
import app.smallthingz.reverb.incidentsPanelTranslationX
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
    private val sourceNode: ((View) -> NativeSourceAccessibilityNode)? = null,
    private val overlayVisibilityChanged: (Boolean) -> Unit = {},
) : ViewGroup(context), NativeIncidentsRenderer, AutoCloseable {
    private var closed = false
    private var state = owner.state
    private var progress = owner.openProgress
    private var overlay = false
    private var content: ReverbIncidentsContentView? = null
    private var menus: NativeIncidentMenuHost? = null
    private var textLease: NativePageTextAccessibilityOwner? = null
    private var panelScope: CoroutineScope? = null
    private var backLease: AutoCloseable? = null
    private var renderLease: AutoCloseable? = null
    private var statusBarInsets = Insets.NONE

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        visibility = GONE
        backLease = owner.registerBack(dispatcher, lifecycleOwner)
        try { renderLease = owner.connect(this) }
        catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun appearance() = IncidentsContentAppearance(
        IncidentCardAppearance(environment.palette,
            ReverbParagraphStyles[ReverbTextRole.BODY_LARGE],
            ReverbParagraphStyles[ReverbTextRole.BODY_MEDIUM],
            ReverbParagraphStyles[ReverbTextRole.BODY_SMALL],
            environment.textDensity, environment.sharedFontResolver),
        ReverbParagraphStyles[ReverbTextRole.TITLE_LARGE],
        ReverbParagraphStyles[ReverbTextRole.TITLE_MEDIUM])

    override fun historyChanged(state: IncidentHistoryUiState) {
        if (closed) return
        this.state = state
        content?.bind(state)
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
        applyMotion()
    }

    private fun mount() {
        val scope = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))
        panelScope = scope
        val frames = requireNotNull(scope.coroutineContext[MonotonicFrameClock])
        try {
            val menuOwner = NativeIncidentMenuHost(environment, frames, scope)
            menus = menuOwner
            val shown = ReverbIncidentsContentView(context, appearance(), menuOwner,
                incidentContentActions(context, owner.history), owner::requestBack, frames, scope,
                { scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f }, inputRoot)
            content = shown
            shown.setStatusBarInsets(statusBarInsets)
            addView(shown)
            textLease = windowText.bindContent(shown, NativeTextSemanticsClipPolicy(shown::semanticsClip), sourceNode) {
                !closed && content === shown && (sourceNode?.invoke(it)?.present ?: true)
            }
            shown.observeAccessibilityMembership { textLease?.refresh() }
            shown.bind(state)
        } catch (failure: Throwable) {
            try { unmount() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Delivered by the one main-window NativeWindowInsetsBinding, before child consumption. */
    fun setStatusBarInsets(insets: Insets) {
        statusBarInsets = insets
        content?.setStatusBarInsets(insets)
    }

    /** Palette changes retain list/scroll/row identity; window density/resolver remain host-owned. */
    fun bindPalette(palette: ReverbPalette) {
        if (closed) return
        environment = ReverbViewEnvironment(environment.textDensity, environment.sharedFontResolver, palette)
        menus?.bindPalette(palette)
        content?.setAppearance(appearance(), owner::requestBack, incidentContentActions(context, owner.history))
    }

    private fun applyMotion() {
        // Source graphicsLayer belongs to the full Surface, including header/noise/list.
        content?.let {
            it.translationX = incidentsPanelTranslationX(width.toFloat(), progress)
            it.alpha = .82f + progress * .18f
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        content?.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        content?.layout(0, 0, width, height)
        applyMotion()
    }

    private fun unmount() {
        val current = content
        content = null
        val menuOwner = menus
        menus = null
        val scope = panelScope
        panelScope = null
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
        }
        current?.observeAccessibilityMembership {}
        cleanup { textLease?.close() }
        textLease = null
        cleanup { current?.dispose() }
        cleanup { menuOwner?.close() }
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
