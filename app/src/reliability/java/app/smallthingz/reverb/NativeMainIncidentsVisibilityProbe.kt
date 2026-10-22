package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.res.Configuration
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.BackEventCompat
import androidx.core.view.WindowCompat
import app.smallthingz.reverb.nativeui.NativeMainLibraryLayer
import app.smallthingz.reverb.nativeui.ReverbHomeScaffoldView
import app.smallthingz.reverb.nativeui.ReverbLibraryScreenContent
import app.smallthingz.reverb.nativeui.ReverbLibraryToolbarView
import app.smallthingz.reverb.nativeui.ReverbTopBarIconAction
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Assembled native Main regression for retained Home Incidents under Library/selection.
 * Own focused QA actions and dispatcher callbacks only, not physical gestures or TalkBack.
 * Uses identity-bound synthetic recordings; never opens a capture owner or changes settings.
 */
internal fun verifyNativeMainIncidentsVisibility(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-main-incidents-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val steps = JSONArray()
    val result = JSONObject().put("selector", "native_main_incidents_visibility").put("passed", false)
        .put("scope", "Actual assembled Main Home/Library/selection, predictive callbacks cancel/commit, retained Home underlay")
        .put("unqualified", JSONArray(listOf("physical input/gesture dispatch", "frame/pixel parity",
            "TalkBack delivery", "capture/production data")))
        .put("steps", steps)
    fun persist() = File(directory, "result.json").writeText(result.toString(2))
    val fixtures = LibraryOwnedFixtures(context, directory)
    val initialTheme = getConfiguredThemeMode(context)
    var activity: NativeParityActivity? = null
    var render: MainSettingsParityRender? = null
    var failure: Throwable? = null
    var fixturesCreated = false
    try {
        fixtures.requireEmptyBaseline()
        val recordings = fixtures.create()
        fixturesCreated = true
        val host = launchNativeParityActivity(instrumentation)
        activity = host
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        requireNativeParityHost(instrumentation, host)
        val current = nativeParityOnMain(instrumentation) {
            WindowCompat.setDecorFitsSystemWindows(host.window, false)
            @Suppress("DEPRECATION")
            host.window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            host.window.navigationBarColor = android.graphics.Color.TRANSPARENT
            MainSettingsParityRender(host, false, initialTheme,
                host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES, commonGrain = null)
        }
        render = current
        val automation = nativeParityUiAutomation(instrumentation)
        val incidents = host.getString(R.string.open_incidents)
        val back = host.getString(R.string.back)
        val clear = host.getString(R.string.clear_selection)
        fun guard() = requireNativeParityHost(instrumentation, host)
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8_000L
            while (true) {
                guard()
                if (nativeParityOnMain(instrumentation, condition)) return
                check(SystemClock.uptimeMillis() < deadline) { "Main Incidents fixture timed out: $label" }
                awaitNativeParityDraw(instrumentation, current.board)
            }
        }
        await("Home") { current.actionCount(incidents) == 1 }
        val main = nativeParityOnMain(instrumentation) {
            incidentProbeViews(current.board).filterIsInstance<NativeMainScreenView>().single()
        }
        val anchors = nativeParityOnMain(instrumentation) {
            val bar = incidentProbeViews(main).filterIsInstance<ReverbHomeScaffoldView>().single().topBar
            val brand = (0 until bar.childCount).map(bar::getChildAt).single {
                incidentProbeHasDescription(it, host.getString(R.string.app_name))
            }
            val settings = incidentProbeViews(bar).filterIsInstance<ReverbTopBarIconAction>().single {
                incidentProbeHasDescription(it.accessibilityAction, host.getString(R.string.open_settings))
            }
            listOf(brand, settings).map { it to incidentProbeBounds(it) }
        }
        fun direct(step: String, expected: Int): JSONObject {
            check(host.hasWindowFocus() && current.board.isShown)
            val evidence = current.evidence()
            val nodes = evidence.getJSONArray("native_view_nodes")
            val count = (0 until nodes.length()).count {
                nodes.getJSONObject(it).optString("description") == incidents
            }
            val entry = JSONObject().put("step", step).put("expected_incidents", expected)
                .put("direct_incidents", count).put("direct", evidence).put("passed", false)
            steps.put(entry)
            check(count == expected && current.actionCount(incidents) == expected) {
                "$step: expected $expected Incidents action(s); direct=$count"
            }
            val geometry = JSONArray()
            anchors.forEach { (view, baseline) ->
                val bounds = incidentProbeBounds(view)
                geometry.put(JSONObject().put("class", view.javaClass.name)
                    .put("baseline", JSONArray(baseline)).put("current", JSONArray(bounds)))
                check(bounds == baseline) { "$step: Home brand/Settings geometry changed" }
            }
            entry.put("home_anchors", geometry).put("passed", true)
            check(getConfiguredThemeMode(host) == initialTheme && current.forbiddenCalls == 0)
            return entry
        }
        fun capture(step: String, expected: Int) {
            guard()
            val bitmap = captureNativeParityContent(instrumentation, host) { current.board }
            try { saveNativeParityBitmap(directory, "$step.png", bitmap) } finally { bitmap.recycle() }
            val entry = nativeParityOnMain(instrumentation) { direct(step, expected) }
            entry.put("passed", false)
            val platform = onboardingNativeNodeEvidence(instrumentation, host, current.board, automation) { partial ->
                entry.put("platform_partial", partial); persist()
            }
            entry.put("platform", platform)
            val nodes = platform.getJSONArray("canonical")
            val count = (0 until nodes.length()).count {
                nodes.getJSONObject(it).optString("description") == incidents
            }
            entry.put("platform_incidents", count)
            check(count == expected) { "$step: expected $expected platform Incidents node(s), found $count" }
            entry.put("passed", true)
            persist()
        }
        fun dispatch(action: () -> Unit) {
            guard()
            nativeParityOnMain(instrumentation) {
                check(host.hasWindowFocus() && current.board.isShown)
                action()
            }
        }
        fun predictive(progress: Float) {
            dispatch {
                host.onBackPressedDispatcher.dispatchOnBackStarted(
                    BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
                host.onBackPressedDispatcher.dispatchOnBackProgressed(
                    BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT))
            }
            awaitNativeParityDraw(instrumentation, current.board)
            awaitNativeParityDraw(instrumentation, current.board)
        }

        capture("home-before", 1)
        dispatch {
            current.click(host.getString(R.string.files_tab))
            check(main.saveState().navigation.showLibrary)
            direct("library-opening-before-frame", 0)
        }
        await("Library normal") { current.actionCount(back) == 1 }
        val library = nativeParityOnMain(instrumentation) {
            incidentProbeViews(main).filterIsInstance<ReverbLibraryScreenContent>().single()
        }
        await("Library settled and owned fixtures loaded") {
            (library.parent as View).translationY == 0f && library.currentState()?.let {
                it.hasLoaded && !it.isRefreshing && !it.loadFailed &&
                    it.recordings.map(RecordingEntity::id).toSet() == recordings.map(RecordingEntity::id).toSet()
            } == true
        }
        capture("library-normal", 0)
        dispatch { library.owner.multiSelect(recordings.first()) }
        await("Library selection") { current.actionCount(clear) == 1 && current.actionCount(back) == 0 }
        capture("library-selection", 0)

        predictive(.5f)
        result.put("selection_back_partial", nativeParityOnMain(instrumentation) {
            val toolbar = incidentProbeViews(library).filterIsInstance<ReverbLibraryToolbarView>().single()
            val moving = (0 until toolbar.childCount).map(toolbar::getChildAt).single {
                kotlin.math.abs(it.translationX) > 0f && kotlin.math.abs(it.translationX) < it.width
            }
            check(library.currentState()?.selectedCount == 1)
            JSONObject().put("translation_x", moving.translationX.toDouble()).put("width", moving.width)
        })
        capture("selection-back-half", 0)
        dispatch { host.onBackPressedDispatcher.dispatchOnBackCancelled() }
        await("Selection Back cancelled") {
            current.actionCount(clear) == 1 && main.saveState().navigation.showLibrary &&
                library.currentState()?.selectedCount == 1 &&
                incidentProbeViews(library).filterIsInstance<ReverbLibraryToolbarView>().single().let { toolbar ->
                    (0 until toolbar.childCount).all { toolbar.getChildAt(it).translationX == 0f }
                }
        }
        capture("selection-back-cancelled", 0)
        predictive(.6f)
        dispatch { host.onBackPressedDispatcher.onBackPressed() }
        await("Selection Back committed") { current.actionCount(clear) == 0 && current.actionCount(back) == 1 }
        check(nativeParityOnMain(instrumentation) { main.saveState().navigation.showLibrary })
        capture("selection-back-committed", 0)

        predictive(.5f)
        val transition = nativeParityOnMain(instrumentation) {
            val layer = incidentProbeViews(main).filterIsInstance<NativeMainLibraryLayer>().single()
            // This is the actual translated parent installed by Main, not a synthetic source pose.
            val translated = library.parent as View
            check(translated.parent === layer && translated.translationY > 0f &&
                translated.translationY < layer.height.toFloat()) { "Library did not enter a partial Back pose" }
            JSONObject().put("translation_y", translated.translationY.toDouble()).put("height", layer.height)
        }
        result.put("main_back_partial", transition)
        capture("library-back-half-retained-home", 0)
        dispatch { host.onBackPressedDispatcher.onBackPressed() }
        await("Home restored") { !main.saveState().navigation.showLibrary && current.actionCount(incidents) == 1 }
        capture("home-restored", 1)
        result.put("passed", true)
    } catch (error: Throwable) {
        failure = error
    } finally {
        fun retain(error: Throwable) {
            val primary = failure
            if (primary == null) failure = error else if (primary !== error) primary.addSuppressed(error)
        }
        // Close input, Back owners, async refresh and any transient draft before fixture deletion.
        try {
            nativeParityOnMain(instrumentation) {
                var cleanup: Throwable? = null
                fun release(action: () -> Unit) { try { action() } catch (error: Throwable) {
                    if (cleanup == null) cleanup = error else if (cleanup !== error) cleanup!!.addSuppressed(error)
                } }
                release { render?.close() }
                release { activity?.fixtureRoot?.removeAllViews() }
                release { activity?.finish() }
                cleanup?.let { throw it }
            }
        } catch (cleanup: Throwable) { retain(cleanup) }
        if (fixturesCreated) try { fixtures.requireUnchanged() } catch (check: Throwable) { retain(check) }
        try { fixtures.close() } catch (cleanup: Throwable) { retain(cleanup) }
        try { check(getConfiguredThemeMode(context) == initialTheme) } catch (check: Throwable) { retain(check) }
        result.put("passed", result.getBoolean("passed") && failure == null)
            .put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
        try { persist() } catch (write: Throwable) { retain(write) }
        report.appendLine("Main Incidents evidence=" + directory.absolutePath)
    }
    failure?.let { throw it }
    report.appendLine("PASS native_main_incidents_visibility direct/platform; programmatic predictive cancel/commit; Home anchors unchanged")
}

private fun incidentProbeBounds(view: View) = listOf(view.left, view.top, view.right, view.bottom)
private fun incidentProbeHasDescription(view: View, label: String) = incidentProbeViews(view).any {
    val node = it.createAccessibilityNodeInfo()
    try { node?.isVisibleToUser == true && node.contentDescription?.toString() == label }
    finally { @Suppress("DEPRECATION") node?.recycle() }
}
private fun incidentProbeViews(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(incidentProbeViews(view.getChildAt(index)))
}
