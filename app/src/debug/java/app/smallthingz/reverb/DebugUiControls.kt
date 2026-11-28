package app.smallthingz.reverb

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.nativeui.NativeIncidentsScreenView
import app.smallthingz.reverb.nativeui.ReverbAppTopBarView
import app.smallthingz.reverb.nativeui.ReverbLibraryScreenContent
import app.smallthingz.reverb.nativeui.ReverbLibraryToolbarView
import app.smallthingz.reverb.nativeui.ReverbSettingsTopBarView
import app.smallthingz.reverb.nativeui.ReverbHomeScaffoldView
import java.lang.ref.WeakReference
import java.util.UUID
import smallthingz.ui.common.NativeSurfaceInputAdmission
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.reverb.ReverbLibraryRevealLayer
import smallthingz.ui.reverb.ReverbToolbarActionView
import smallthingz.ui.reverb.home.HomeClickable
import smallthingz.ui.reverb.home.ReverbHomeControls
import smallthingz.ui.reverb.home.ReverbPermissionRequiredView
import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost

/** Protocol IDs, not displayed strings or child indices. Only these real UI actions are admitted. */
internal enum class DebugUiControl(val id: String, val labelResource: Int?,
    val action: Int = AccessibilityNodeInfo.ACTION_CLICK) {
    SETTINGS("navigation.settings", R.string.open_settings),
    INCIDENTS("navigation.incidents", R.string.open_incidents),
    LIBRARY("navigation.library", R.string.files_tab),
    BACK("navigation.back", R.string.back),
    LIBRARY_FORWARD("library.scroll.forward", null, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),
    LIBRARY_BACKWARD("library.scroll.backward", null, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD),
    INCIDENTS_SELECT("incidents.selection.enter", R.string.multi_select),
    INCIDENTS_SELECT_ALL("incidents.selection.all", R.string.incidents_select_all);

    companion object { fun fromId(id: String) = entries.firstOrNull { it.id == id } }
}

internal enum class DebugUiStatus {
    READY, DISPATCHED, REJECTED, INVALID_REQUEST, NO_ACTIVITY, STALE_OBSERVATION, NOT_RESUMED,
    DETACHED, HIDDEN, WINDOW_UNFOCUSED, NO_MAIN_ROOT, NOT_LAID_OUT, MOVING,
    NOT_PRESENT, AMBIGUOUS, SURFACE_BLOCKED, DISABLED, NO_ACCESSIBLE_ACTION, TREE_LIMIT
}
internal data class DebugUiControlState(val id: String, val status: DebugUiStatus)
internal data class DebugUiSnapshot(
    val generation: String?, val status: DebugUiStatus,
    val navigation: NativeMainSavedNavigation?, val controls: List<DebugUiControlState>,
    val incidentsSelecting: Boolean? = null, val libraryScrollOffset: Int? = null,
)
internal data class DebugUiResult(val status: DebugUiStatus, val snapshot: DebugUiSnapshot)

/** Instrumentation owns this registration; no Activity, View or action survives its session. */
internal class DebugUiActivities(
    private val accepts: (Activity) -> Boolean = { it is MainActivity },
) : Application.ActivityLifecycleCallbacks {
    private val session = UUID.randomUUID().toString()
    private var revision = 0L
    private var activity = WeakReference<Activity>(null)
    private var root = WeakReference<NativeMainScreenView>(null)
    private var navigation: NativeMainSavedNavigation? = null
    private var targets = emptyMap<DebugUiControl, WeakReference<View>>()
    var resumed = false
        private set
    val current get() = activity.get()
    val generation get() = if (current == null) null else "$session:$revision"
    fun observeRoot(value: NativeMainScreenView?) {
        if (root.get() !== value) { root = WeakReference(value); revision++ }
    }
    fun observeControls(value: NativeMainSavedNavigation?, leaves: Map<DebugUiControl, View>) {
        if (navigation != value || targets.keys != leaves.keys || leaves.any { (id, view) -> targets[id]?.get() !== view }) {
            navigation = value
            targets = leaves.mapValues { WeakReference(it.value) }
            revision++
        }
    }
    override fun onActivityResumed(value: Activity) {
        if (!accepts(value)) return
        activity = WeakReference(value); root.clear(); resumed = true; revision++
    }
    override fun onActivityPaused(value: Activity) {
        if (current === value) { resumed = false; revision++ }
    }
    override fun onActivityDestroyed(value: Activity) {
        if (current === value) { resumed = false; activity.clear(); root.clear(); revision++ }
    }
    override fun onActivityCreated(value: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(value: Activity) = Unit
    override fun onActivityStopped(value: Activity) = Unit
    override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
}

/** All discovery, generation checks, admission and invocation happen within ONE main-thread turn. */
internal class DebugUiControls(private val activities: DebugUiActivities) {
    private data class Candidate(val view: View, val status: DebugUiStatus)
    private data class Capture(val snapshot: DebugUiSnapshot, val targets: Map<DebugUiControl, View>)

    fun snapshot(): DebugUiSnapshot = capture().snapshot

    fun activate(control: DebugUiControl, generation: String): DebugUiResult {
        val before = capture()
        if (before.snapshot.generation != generation) return DebugUiResult(DebugUiStatus.STALE_OBSERVATION, before.snapshot)
        val state = before.snapshot.controls.first { it.id == control.id }.status
        if (state != DebugUiStatus.READY) return DebugUiResult(state, before.snapshot)
        val target = checkNotNull(before.targets[control])
        val dispatched = target.performAccessibilityAction(control.action, null)
        // Dispatch is not a promise that asynchronous hydration, navigation or persistence succeeded.
        return DebugUiResult(if (dispatched) DebugUiStatus.DISPATCHED else DebugUiStatus.REJECTED, capture().snapshot)
    }

    private fun capture(): Capture {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Debug UI commands require the main thread" }
        val activity = activities.current
        fun unavailable(status: DebugUiStatus, nav: NativeMainSavedNavigation? = null): Capture {
            activities.observeControls(nav, emptyMap())
            return Capture(DebugUiSnapshot(activities.generation, status, nav,
                DebugUiControl.entries.map { DebugUiControlState(it.id, status) }), emptyMap())
        }
        if (activity == null) return unavailable(DebugUiStatus.NO_ACTIVITY)
        val views = debugUiBoundedViews(activity.window.decorView) ?: return unavailable(DebugUiStatus.TREE_LIMIT)
        val roots = views.filterIsInstance<NativeMainScreenView>()
        if (roots.size != 1) { activities.observeRoot(null); return unavailable(DebugUiStatus.NO_MAIN_ROOT) }
        val main = roots.single()
        activities.observeRoot(main)
        val nav = main.saveState().navigation
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        if (!activities.resumed || lifecycle?.currentState != Lifecycle.State.RESUMED || activity.isFinishing || activity.isDestroyed)
            return unavailable(DebugUiStatus.NOT_RESUMED, nav)
        if (!main.isAttachedToWindow) return unavailable(DebugUiStatus.DETACHED, nav)
        if (!main.isShown) return unavailable(DebugUiStatus.HIDDEN, nav)
        if (!main.hasWindowFocus()) return unavailable(DebugUiStatus.WINDOW_UNFOCUSED, nav)
        if (!main.isLaidOut || main.width == 0 || main.height == 0 || main.isLayoutRequested)
            return unavailable(DebugUiStatus.NOT_LAID_OUT, nav)
        if (!panelsSettled(main, nav)) return unavailable(DebugUiStatus.MOVING, nav)
        val states = ArrayList<DebugUiControlState>()
        val targets = LinkedHashMap<DebugUiControl, View>()
        for (control in DebugUiControl.entries) {
            val candidates = when (control) {
                DebugUiControl.LIBRARY_FORWARD, DebugUiControl.LIBRARY_BACKWARD ->
                    views.filterIsInstance<ReverbLibraryScreenContent>().mapNotNull { it.listViewport }
                else -> clickCandidates(control, views, main,
                    activity.getString(checkNotNull(control.labelResource)))
            }
            val matches = candidates.map { Candidate(it, admission(it, main, control.action)) }
            val ready = matches.filter { it.status == DebugUiStatus.READY }
            val status = when {
                ready.size > 1 -> DebugUiStatus.AMBIGUOUS
                ready.size == 1 -> { targets[control] = ready.single().view; DebugUiStatus.READY }
                matches.isEmpty() -> DebugUiStatus.NOT_PRESENT
                else -> matches.first().status
            }
            states += DebugUiControlState(control.id, status)
        }
        activities.observeControls(nav, targets)
        return Capture(DebugUiSnapshot(activities.generation, DebugUiStatus.READY, nav, states,
            views.filterIsInstance<NativeIncidentsScreenView>().singleOrNull()?.pointerContent?.selectionActive,
            views.filterIsInstance<ReverbLibraryScreenContent>().singleOrNull()?.listViewport?.computeVerticalScrollOffset()), targets)
    }

    private fun clickCandidates(
        control: DebugUiControl,
        views: List<View>,
        main: NativeMainScreenView,
        label: String,
    ): List<View> =
        views.mapNotNull { owner ->
            val leaf = when (control) {
                DebugUiControl.SETTINGS, DebugUiControl.INCIDENTS ->
                    (owner as? ReverbToolbarActionView)?.takeIf {
                        (it.inside<ReverbAppTopBarView>() &&
                            (it.inside<ReverbHomeScaffoldView>() || it.inside<ReverbLibraryToolbarView>())) ||
                            (control == DebugUiControl.SETTINGS && it.inRootLibraryToolbar(main))
                    }?.accessibilityAction
                DebugUiControl.BACK -> (owner as? ReverbToolbarActionView)?.takeIf {
                    it.inRootLibraryToolbar(main) || it.inside<ReverbLibraryToolbarView>() ||
                        it.inside<ReverbSettingsTopBarView>() || it.inside<NativeIncidentsScreenView>()
                }?.accessibilityAction
                DebugUiControl.LIBRARY -> owner.takeIf {
                    it.inside<ReverbHomeScaffoldView>() &&
                        ((it is HomeClickable && it.inside<ReverbHomeControls<*>>()) ||
                            (it is NativeMaterialButtonView && it.inside<ReverbPermissionRequiredView>()))
                }
                DebugUiControl.INCIDENTS_SELECT, DebugUiControl.INCIDENTS_SELECT_ALL ->
                    (owner as? ReverbToolbarActionView)?.takeIf { it.inside<NativeIncidentsScreenView>() }
                        ?.accessibilityAction
                DebugUiControl.LIBRARY_FORWARD, DebugUiControl.LIBRARY_BACKWARD -> null
            }
            leaf?.takeIf { hasLabel(it, label) }
        }

    private fun ReverbToolbarActionView.inRootLibraryToolbar(main: NativeMainScreenView): Boolean =
        generateSequence(parent as? View) { it.parent as? View }
            .any { it is ReverbAppTopBarView && it.parent === main }

    private inline fun <reified T : View> View.inside(): Boolean =
        generateSequence(parent as? View) { it.parent as? View }.any { it is T }

    private fun hasLabel(view: View, label: String): Boolean {
        if (view.contentDescription?.toString() == label) return true
        // Full Home actions expose their glyph description on a direct child.
        if (view is HomeClickable && (0 until view.childCount).any {
                view.getChildAt(it).contentDescription?.toString() == label
            }) return true
        val host = view as? NativeMergedRowAccessibilityHost ?: return false
        return host.mergedRowAccessibility.sourceOrderedChildren().any { child ->
            if (child.contentDescription?.toString() == label) true else {
                val node = child.createAccessibilityNodeInfo()
                try { node?.text?.toString() == label || node?.contentDescription?.toString() == label }
                finally { @Suppress("DEPRECATION") node?.recycle() }
            }
        }
    }

    private fun admission(view: View, main: NativeMainScreenView, action: Int): DebugUiStatus {
        if (!view.isAttachedToWindow) return DebugUiStatus.DETACHED
        if (!view.isShown) return DebugUiStatus.HIDDEN
        if (!view.hasWindowFocus()) return DebugUiStatus.WINDOW_UNFOCUSED
        var ancestor: View? = view
        var belongs = false
        while (ancestor != null) {
            if (ancestor === main) belongs = true
            if (ancestor.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                return DebugUiStatus.SURFACE_BLOCKED
            if (ancestor is NativeSurfaceInputAdmission && !ancestor.admitsSurfaceInput(view)) return DebugUiStatus.SURFACE_BLOCKED
            ancestor = ancestor.parent as? View
        }
        if (!belongs) return DebugUiStatus.SURFACE_BLOCKED
        if (!view.isEnabled) return DebugUiStatus.DISABLED
        val node = view.createAccessibilityNodeInfo() ?: return DebugUiStatus.NOT_PRESENT
        return try {
            when {
                !node.isVisibleToUser -> DebugUiStatus.HIDDEN
                !node.isEnabled -> DebugUiStatus.DISABLED
                node.actionList.none { it.id == action } -> DebugUiStatus.NO_ACCESSIBLE_ACTION
                else -> DebugUiStatus.READY
            }
        } finally { @Suppress("DEPRECATION") node.recycle() }
    }

    /** These are the existing owners' rendered progress projections, never timers or forced flags. */
    private fun panelsSettled(main: NativeMainScreenView, nav: NativeMainSavedNavigation): Boolean {
        val direct = (0 until main.childCount).map(main::getChildAt)
        val home = direct.filterIsInstance<ReverbHomeScaffoldView>().singleOrNull() ?: return false
        if (home.translationY != if (nav.showSettings) main.height.toFloat() else 0f) return false
        direct.filterIsInstance<ReverbLibraryRevealLayer>().forEach { layer ->
            val translated = layer.content.parent as? View ?: return false
            if (translated.translationY != if (nav.showLibrary) 0f else layer.height.toFloat()) return false
        }
        direct.filterIsInstance<NativeIncidentsScreenView>().forEach { layer ->
            val content = layer.pointerContent
            if (nav.showIncidents) {
                if (content == null || content.translationX != 0f || content.alpha != 1f) return false
            } else if (content != null) return false
        }
        return true
    }

}

internal const val DEBUG_UI_VIEW_LIMIT = 4096

/** One shared budget covers visited entries and the pending frontier, including the root. */
internal fun debugUiBoundedViews(root: View): List<View>? {
    val queue = java.util.ArrayDeque<View>(); queue.add(root)
    val result = ArrayList<View>()
    while (queue.isNotEmpty()) {
        val view = queue.removeFirst(); result += view
        if (view is ViewGroup) {
            val childCount = view.childCount
            val remaining = DEBUG_UI_VIEW_LIMIT - result.size - queue.size
            if (childCount > remaining) return null
            repeat(childCount) { queue.addLast(view.getChildAt(it)) }
        }
    }
    return result
}
