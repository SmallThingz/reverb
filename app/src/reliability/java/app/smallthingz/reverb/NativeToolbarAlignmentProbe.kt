package app.smallthingz.reverb

import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.Insets
import app.smallthingz.reverb.nativeui.LibraryToolbarActions
import app.smallthingz.reverb.nativeui.LibraryToolbarState
import app.smallthingz.reverb.nativeui.ReverbAppTopBarActions
import app.smallthingz.reverb.nativeui.ReverbAppTopBarView
import app.smallthingz.reverb.nativeui.ReverbLibraryToolbarView
import app.smallthingz.reverb.nativeui.ReverbSettingsTopBarView
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import app.smallthingz.reverb.nativeui.SettingsTopBarBinding
import app.smallthingz.reverb.nativeui.incidentTexts
import smallthingz.ui.components.NativeModalWindowRuntime
import smallthingz.ui.components.NativeSelectionTopBarLayout
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.components.NativeTopBarAction
import smallthingz.ui.components.NativeTopBarInsets
import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbIncidentActions
import smallthingz.ui.reverb.ReverbIncidentsContentView
import smallthingz.ui.reverb.ReverbSelectionToolbar
import smallthingz.ui.reverb.ReverbToolbarActionView
import smallthingz.ui.text.view.NativeActionDescriptionView
import smallthingz.ui.text.view.ParagraphView
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt

/** Extends native_home_hydration_current with attached production Views and local input only. */
internal fun checkReverbToolbarAlignment(
    instrumentation: Instrumentation,
    host: NativeParityActivity,
    original: ReverbViewEnvironment,
    runtime: NativeModalWindowRuntime,
    cases: JSONArray,
) {
    val densities = listOf(160, 421, host.resources.configuration.densityDpi).distinct()
    for (dpi in densities) {
        val density = dpi / 160f
        val baseWidth = (400 * density).roundToInt().let { it - it % 2 }
        for (width in listOf(baseWidth, baseWidth + 1)) {
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                for (safe in listOf(Insets.NONE, Insets.of(17, 25, 9, 0))) {
                    val entry = JSONObject().put("dpi", dpi).put("density", density.toDouble()).put("width", width)
                        .put("rtl", direction == View.LAYOUT_DIRECTION_RTL)
                        .put("safe_insets", JSONArray(listOf(safe.left, safe.top, safe.right))).put("passed", false)
                    cases.put(entry)
                    try {
                        requireNativeParityHost(instrumentation, host)
                        nativeParityOnMain(instrumentation) {
                            val configuration = Configuration(host.resources.configuration).apply { densityDpi = dpi }
                            val context = ContextThemeWrapper(host, 0).apply { applyOverrideConfiguration(configuration) }
                            val environment = ReverbViewEnvironment(context, false).withPalette(original.palette)
                            checkToolbarCase(host, context, environment, runtime, width, direction, safe, dpi, entry)
                        }
                    } catch (failure: Throwable) {
                        entry.put("failure", failure.stackTraceToString())
                        throw failure
                    }
                }
            }
        }
    }
}

private fun checkToolbarCase(
    host: NativeParityActivity,
    context: ContextThemeWrapper,
    environment: ReverbViewEnvironment,
    runtime: NativeModalWindowRuntime,
    width: Int,
    direction: Int,
    safe: Insets,
    dpi: Int,
    result: JSONObject,
) {
    val started = SystemClock.uptimeMillis()
    val density = environment.textDensity.density
    val height = (58 * density).roundToInt()
    val root = FrameLayout(context).apply { layoutDirection = direction }
    val calls = mutableListOf<String>()
    val navigation = ReverbAppTopBarActions({ calls += "brand" }, { calls += "home-incidents" }, { calls += "settings" })
    fun compact(back: Boolean) = ReverbAppTopBarView(context, environment, root).apply {
        setActionInsets(safe.left, safe.right)
        bind(environment, false, if (back) navigation.copy(back = { calls += "library-back" }) else navigation, safe.top)
    }
    val home = compact(false)
    val library = compact(true)
    val settings = ReverbSettingsTopBarView(context, environment, runtime.scope) { root }
    val settingsBinding = SettingsTopBarBinding(false, false, false, false, false,
        { calls += "settings-back" }, { calls += "done" })
    settings.bind(environment, settingsBinding)
    settings.setRemainingWindowInsets(NativeTopBarInsets(safe.left, safe.top, safe.right))
    val librarySelection = ReverbLibraryToolbarView(context, environment, root).apply {
        setActionInsets(safe.left, safe.right)
        bind(LibraryToolbarState(2, false, true, false, showNormalTopBar = false),
            LibraryToolbarActions({}, {}, {}, {}, { calls += "library-clear" },
                { calls += "share" }, { calls += "delete" }))
    }
    val texts = incidentTexts(context)
    val rows = prepareIncidentHistory(listOf(
        RecordingIncident(1_700_000_000_000, pid = 71, description = "Toolbar fixture"),
        RecordingIncident(1_700_000_001_000, pid = 72, description = "Toolbar fixture"),
    ), Locale.US, ZoneOffset.UTC, context.resources)
    val incidents = ReverbIncidentsContentView(context, environment.theme, texts,
        ReverbIncidentActions({ error("Unexpected row acknowledgement") }, { error("Unexpected row deletion") },
            { targets -> check(targets.toSet() == rows.rows.toSet()); calls += "incident-delete" },
            { error("Unexpected clipboard action") }),
        { calls += "incident-back" }, runtime.frames, runtime.scope, { 0f }, root).apply {
        setStatusBarInsets(safe)
        bind(IncidentHistoryUiState(rows, IncidentHistoryLoadStatus.READY).nativeHistory)
    }
    // Retain the pre-fix generic selection layout and its 16dp recipe as the negative control.
    // Actual View placement below must expose the original mismatch against the compact bar.
    fun oldAction(label: String) = ReverbToolbarActionView(context, density, environment.palette,
        { root }, label, selectionWrapper = true).apply {
        bindToolbarAction(density, environment.palette, true, NativeToolbarActionTone.NEUTRAL) { calls += label }
    }
    val oldStart = oldAction("old-back")
    val oldDelete = oldAction("old-delete")
    val oldSelect = oldAction("old-select")
    val oldEnd = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(oldDelete, LinearLayout.LayoutParams(0, -1, 1f))
        addView(oldSelect, LinearLayout.LayoutParams(0, -1, 1f))
    }
    val oldStyle = ReverbDesign.appearance.toolbars.copy(selectionEdgeInsetDp = 16f)
    val old = NativeSelectionTopBarLayout(context, oldStyle, oldStart, View(context), oldEnd, 2).apply {
        bindStyle(density, 0, oldStyle)
        setActionInsets(safe.left, safe.right)
    }
    val views = listOf(home, library, settings, librarySelection, incidents, old)
    try {
        host.fixtureRoot.addView(root, FrameLayout.LayoutParams(width, (600 * density).roundToInt()))
        views.forEach { root.addView(it) }
        root.measure(exactToolbar(width), exactToolbar((600 * density).roundToInt()))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        fun place(view: View, top: Int = 0) {
            view.measure(exactToolbar(width), if (view === incidents) exactToolbar(root.height)
                else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, top, width, top + view.measuredHeight)
        }
        place(home)
        place(library)
        place(settings)
        place(librarySelection, safe.top)
        place(incidents)
        place(old, safe.top)

        fun action(parent: View, label: String) = onboardingViewDescendants(parent)
            .filterIsInstance<NativeTopBarAction>().single { glyph(it).contentDescription?.toString() == label }
        val homeStart = action(home, context.getString(R.string.open_incidents))
        val homeEnd = action(home, context.getString(R.string.open_settings))
        val incidentHeader = (incidents.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        val incidentBack = action(incidentHeader, texts.back)
        val incidentSelect = action(incidentHeader, texts.multiSelect)
        val incidentDelete = action(incidentHeader, texts.clearAll)
        val libraryBar = onboardingViewDescendants(librarySelection).filterIsInstance<ReverbSelectionToolbar>().single()
        val libraryClose = action(libraryBar, context.getString(R.string.clear_selection))
        val libraryShare = action(libraryBar, context.getString(R.string.share_recording))
        val libraryDelete = action(libraryBar, context.getString(R.string.delete_recording))
        val startBounds = toolbarBounds(root, homeStart.visual)
        val endBounds = toolbarBounds(root, homeEnd.visual)
        val oldBounds = toolbarBounds(root, oldStart.visual)
        val oldDx = oldBounds.left - startBounds.left
        val oldDy = oldBounds.top - startBounds.top
        result.put("compact_start", startBounds.flattenToString()).put("compact_end", endBounds.flattenToString())
            .put("settings_start", toolbarBounds(root, settings.navigation.visual).flattenToString())
            .put("settings_end", toolbarBounds(root, settings.done.visual).flattenToString())
            .put("incident_start", toolbarBounds(root, incidentBack.visual).flattenToString())
            .put("incident_end", toolbarBounds(root, incidentSelect.visual).flattenToString())
            .put("library_selection_start", toolbarBounds(root, libraryClose.visual).flattenToString())
            .put("compact_end_glyph", toolbarBounds(root, glyph(homeEnd)).flattenToString())
            .put("library_delete_glyph", toolbarBounds(root, glyph(libraryDelete)).flattenToString())
            .put("old_start", oldBounds.flattenToString()).put("old_dx", oldDx).put("old_dy", oldDy)
        check(startBounds == toolbarBounds(root, settings.navigation.visual))
        check(startBounds == toolbarBounds(root, action(library, context.getString(R.string.back)).visual))
        check(startBounds == toolbarBounds(root, incidentBack.visual))
        check(startBounds == toolbarBounds(root, libraryClose.visual))
        check(endBounds == toolbarBounds(root, settings.done.visual))
        check(endBounds == toolbarBounds(root, action(library, context.getString(R.string.open_settings)).visual))
        check(endBounds == toolbarBounds(root, incidentSelect.visual))
        check(toolbarBounds(root, glyph(homeEnd)) == toolbarBounds(root, glyph(libraryDelete)))
        check(home.height == height + safe.top && settings.height == height + safe.top && library.height == height + safe.top)
        check(incidentHeader.height == height && librarySelection.height == height && old.height == height)

        val expectedDx = if (direction == View.LAYOUT_DIRECTION_LTR) 3 else -3
        if (dpi == 160) check(oldDx == expectedDx && oldDy == 0)
        if (dpi == 421) check(oldDx == (if (direction == View.LAYOUT_DIRECTION_LTR) 8 else -7) && oldDy == 1)
        check(oldBounds != startBounds) { "Old selection geometry must fail the same actual-View comparison" }

        fun assertOrder(inner: NativeTopBarAction, outer: NativeTopBarAction) {
            val a = toolbarBounds(root, glyph(inner))
            val b = toolbarBounds(root, glyph(outer))
            check(if (direction == View.LAYOUT_DIRECTION_LTR) a.right < b.left else b.right < a.left)
            check(!Rect.intersects(toolbarBounds(root, inner.visual), toolbarBounds(root, outer.visual)))
        }
        assertOrder(incidentDelete, incidentSelect)
        assertOrder(libraryShare, libraryDelete)
        for (bar in listOf(incidentHeader, libraryBar)) {
            val title = onboardingViewDescendants(bar).filterIsInstance<ParagraphView>().single()
            val titleBounds = toolbarBounds(root, title)
            check(title.width > 0)
            onboardingViewDescendants(bar).filterIsInstance<NativeTopBarAction>().forEach {
                check(!Rect.intersects(titleBounds, toolbarBounds(root, it.visual)))
            }
        }

        fun click(parent: ViewGroup, target: NativeTopBarAction, expected: String?) {
            val bounds = toolbarBounds(parent, target.visual)
            val before = calls.size
            toolbarTap(parent, bounds.exactCenterX(), bounds.exactCenterY())
            check(calls.drop(before) == listOfNotNull(expected)) { "Wrong toolbar callback: ${calls.drop(before)}" }
        }
        click(home, homeStart, "home-incidents")
        click(library, action(library, context.getString(R.string.back)), "library-back")
        click(settings, settings.navigation, "settings-back")
        click(settings, settings.done, null)
        settings.bind(environment, settingsBinding.copy(hasUnsavedChanges = true))
        click(settings, settings.done, "done")
        click(libraryBar, libraryShare, "share")
        click(libraryBar, libraryDelete, "delete")
        click(libraryBar, libraryClose, "library-clear")
        click(incidentHeader, incidentBack, "incident-back")
        checkToolbarMinimumTarget(incidentHeader, incidentBack, density, calls, "incident-back")
        checkToolbarMinimumTarget(home, homeStart, density, calls, "home-incidents")
        checkToolbarMinimumTarget(libraryBar, libraryClose, density, calls, "library-clear")
        click(incidentHeader, incidentSelect, null)
        check(incidents.selectionActive)
        place(incidents)
        check(startBounds == toolbarBounds(root, incidentBack.visual) && endBounds == toolbarBounds(root, incidentSelect.visual))
        click(incidentHeader, incidentSelect, null)
        place(incidents)
        check(onboardingViewDescendants(incidentHeader).filterIsInstance<ParagraphView>().single().getText() == texts.selectionCount(2))
        click(incidentHeader, incidentDelete, "incident-delete")
        check(!incidents.selectionActive)
        click(incidentHeader, incidentSelect, null)
        check(incidents.selectionActive)
        click(incidentHeader, incidentBack, null)
        check(!incidents.selectionActive)
        click(incidentHeader, incidentBack, "incident-back")

    } finally {
        result.put("callbacks", JSONArray(calls)).put("elapsed_ms", SystemClock.uptimeMillis() - started)
        try { incidents.dispose() } finally {
            try { settings.close() } finally { host.fixtureRoot.removeView(root) }
        }
    }
    result.put("passed", true)
}

private fun glyph(action: NativeTopBarAction): NativeActionDescriptionView =
    (action.visual as ViewGroup).getChildAt(0) as NativeActionDescriptionView

private fun toolbarBounds(root: ViewGroup, view: View): Rect =
    Rect(0, 0, view.width, view.height).also { root.offsetDescendantRectToMyCoords(view, it) }

private fun exactToolbar(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

private fun checkToolbarMinimumTarget(parent: ViewGroup, action: NativeTopBarAction,
                                      density: Float, calls: List<String>, expected: String) {
    val visible = toolbarBounds(parent, action.visual)
    val reach = 48f * density / 2f - .25f
    val x = visible.exactCenterX()
    val y = visible.exactCenterY()
    val points = listOf(x - reach to y, x + reach to y, x to y - reach, x to y + reach)
    for ((px, py) in points) {
        check(px < visible.left || px >= visible.right || py < visible.top || py >= visible.bottom)
        val before = calls.size
        toolbarTap(parent, px, py)
        check(calls.drop(before) == listOf(expected)) {
            "48dp parent-routed target missed $expected at ($px, $py), visual bounds $visible"
        }
    }
}

private fun toolbarTap(view: View, x: Float, y: Float) {
    check(view.isAttachedToWindow && view.isShown)
    val down = SystemClock.uptimeMillis()
    val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
    val point = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(pointer), arrayOf(point),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
