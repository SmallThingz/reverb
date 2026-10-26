package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.smallthingz.reverb.nativeui.*
import java.io.File
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

internal data class ScreenParityCase(val dark: Boolean, val rtl: Boolean, val scale: Float, val ready: Boolean) {
    val name get() = "${if (dark) "dark" else "light"}-${if (rtl) "rtl" else "ltr"}-$scale-ready$ready"
}
internal class ScreenParityRender(
    val view: View,
    val reference: Boolean,
    val save: () -> Any,
    private val dispose: () -> Unit,
) : AutoCloseable {
    private var closed = false
    override fun close() {
        if (closed) return
        closed = true
        dispose()
    }
}

/**
 * Full released screen, not a rearranged page oracle. All actions stay inside the isolated QA
 * window and call its own semantic click callbacks. No permission requests or recorder work.
 * Settled pixels/actions/restoration are tested; touch dispatch/frame-phase/TalkBack remain separate.
 */
internal fun verifyNativeOnboardingScreenParity(instrumentation: Instrumentation, report: StringBuilder) {
    check(instrumentation.targetContext.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    val directory = File(instrumentation.targetContext.filesDir, "native-onboarding-screen-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val entries = JSONArray()
    val nativeFocusChecks = JSONArray()
    val result = JSONObject().put("baseline", "3e16f8ddb78dca15df4b436d34d330801ce83ffd")
        .put("oracle_sha256", "8be65c8c589678c3a5393d4cb7a73489af942e3a04cf0958845bbad5fc4c72a9")
        .put("dispatch", "own-window semantic click callbacks; no synthetic physical input")
        .put("comparison", "exact unmasked ARGB and existing complete payload signatures; raw trees retained")
        .put("unqualified", JSONArray(listOf("gesture/frame-phase parity", "TalkBack delivery", "system permission UI")))
        .put("cases", entries).put("native_focus_checks", nativeFocusChecks).put("passed", false)
    var host: NativeParityActivity? = null
    var active: ScreenParityRender? = null
    var failure: Throwable? = null
    var mismatches = 0
    try {
        val activity = launchNativeParityActivity(instrumentation)
        host = activity
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        val queueCases = nativeParityOnMain(instrumentation) { verifyNativeWindowScrollEventCases(activity.fixtureRoot) }
        result.put("window_scroll_queue_cases", JSONArray(queueCases))
        val typography = Class.forName("app.smallthingz.reverb.ReverbThemeKt")
            .getDeclaredField("ReverbTypography").apply { isAccessible = true }.get(null) as Typography
        val cases = listOf(ScreenParityCase(false, false, 1f, false), ScreenParityCase(true, false, 1f, true),
            ScreenParityCase(false, true, 2f, true), ScreenParityCase(true, true, 2f, false))
        for (case in cases) {
            val all = ArrayList<List<Pair<File, JSONObject>>>()
            try {
                for (pass in 0..3) {
                    val reference = pass % 2 == 0
                    val steps = ArrayList<Pair<File, JSONObject>>()
                    all += steps
                    var requests = 0
                    var finishes = 0
                    fun replace(saved: Any? = null) {
                        nativeParityOnMain(instrumentation) {
                            active?.close()
                            active = null
                            activity.fixtureRoot.removeAllViews()
                            active = showOnboardingScreen(activity, typography, case, reference, saved,
                                { requests++ }, { finishes++ })
                        }
                        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
                    }
                    fun capture(label: String, page: Int) {
                        val render = checkNotNull(active)
                        awaitOnboardingScreenSettled(instrumentation, activity, render, page)
                        val bitmap = captureNativeParityContent(instrumentation, activity) { render.view }
                        try {
                        val geometry = nativeParityOnMain(instrumentation) {
                            onboardingGeometryEvidence(render.view, reference)
                        }
                        val stem = "${case.name}-$label-${listOf("compose", "native", "compose-repeat", "native-repeat")[pass]}"
                        val nodes = onboardingNativeNodeEvidence(instrumentation, activity, render.view) { partial ->
                            File(directory, "$stem-failed-nodes.json").writeText(partial.toString(2))
                            saveNativeParityBitmap(directory, "$stem-failed.png", bitmap)
                        }
                        val evidence = JSONObject().put("geometry", geometry).put("nodes", nodes)
                            .put("page", page).put("requests", requests).put("finishes", finishes)
                        saveNativeParityBitmap(directory, "$stem.png", bitmap)
                        File(directory, "$stem.json").writeText(evidence.toString(2))
                        steps += File(directory, "${stem}.png") to evidence
                        } finally { bitmap.recycle() }
                    }
                    fun click(resource: Int) {
                        requireNativeParityHost(instrumentation, activity)
                        nativeParityOnMain(instrumentation) {
                            invokeOnboardingSemanticClick(checkNotNull(active), activity.getString(resource))
                        }
                    }
                    replace()
                    capture("initial", 0)
                    click(R.string.onboarding_continue)
                    capture("forward1", 1)
                    click(R.string.onboarding_continue)
                    capture("forward2", 2)
                    val saved = nativeParityOnMain(instrumentation) { checkNotNull(active).save() }
                    replace(saved)
                    capture("restored2", 2)
                    click(R.string.onboarding_back)
                    capture("back1", 1)
                    click(R.string.onboarding_back)
                    capture("back0", 0)
                    check(requests == 0 && finishes == 0) { "Navigation accidentally requested permission or Finish" }
                    if (!reference) nativeFocusChecks.put(nativeParityOnMain(instrumentation) {
                        verifyOnboardingExplicitFooterFocus(checkNotNull(active)).put("case", case.name).put("pass", pass)
                    })
                    nativeParityOnMain(instrumentation) { active?.close(); active = null }
                }
                for (step in all[0].indices) {
                    val compare = compareOnboardingSavedPixels(all[0][step].first, all[1][step].first)
                    val referenceStable = compareOnboardingSavedPixels(all[0][step].first, all[2][step].first)
                    val nativeStable = compareOnboardingSavedPixels(all[1][step].first, all[3][step].first)
                    fun signature(pass: Int) = onboardingTextGeometrySignature(all[pass][step].second) + "\n" +
                        onboardingNodeSignature(all[pass][step].second)
                    val sameGeometryAndNodes = signature(0) == signature(1)
                    val stableEvidence = signature(0) == signature(2) && signature(1) == signature(3)
                    val sameTextGeometry = onboardingTextGeometrySignature(all[0][step].second) ==
                        onboardingTextGeometrySignature(all[1][step].second)
                    val sameNodes = onboardingNodeSignature(all[0][step].second) ==
                        onboardingNodeSignature(all[1][step].second)
                    val passed = listOf(compare, referenceStable, nativeStable).all { it.getLong("different_pixels") == 0L } &&
                        sameGeometryAndNodes && stableEvidence
                    entries.put(JSONObject().put("case", case.name).put("step", step).put("pixels", compare)
                        .put("reference_stability", referenceStable).put("native_stability", nativeStable)
                        .put("text_geometry_equal", sameTextGeometry).put("canonical_nodes_equal", sameNodes)
                        .put("text_and_nodes_equal", sameGeometryAndNodes).put("evidence_stable", stableEvidence)
                        .put("passed", passed))
                    if (!passed) mismatches++
                }
            } finally {
                // Pixels were persisted and recycled immediately; comparisons decode only two.
            }
        }
        check(mismatches == 0) { "Full onboarding screen differs in $mismatches captured steps; retain raw evidence" }
        result.put("passed", true)
        report.append("PASS native_onboarding_screen_parity exact settled pages/footer, forward/back and restoration; native gesture phase unqualified\n")
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            nativeParityOnMain(instrumentation) {
                val owned = active
                active = null
                var cleanup = captureNativeParityCleanup(null) { owned?.close() }
                cleanup = captureNativeParityCleanup(cleanup) { host?.fixtureRoot?.removeAllViews() }
                cleanup = captureNativeParityCleanup(cleanup) { host?.finish() }
                cleanup?.let { throw it }
            }
        } catch (cleanup: Throwable) {
            failure = appendNativeParityFailure(failure, cleanup)
        }
        val cleanupFailedAfterPass = failure != null && result.optBoolean("passed")
        if (failure != null) result.put("passed", false)
        result.put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
        val writeFailure = captureNativeParityCleanup(failure) {
            File(directory, "result.json").writeText(result.toString(2))
        }
        if (failure == null) writeFailure?.let { throw it }
        report.append("Full onboarding evidence: ").append(directory.absolutePath).append('\n')
        if (cleanupFailedAfterPass) throw checkNotNull(failure)
    }
}

internal fun showOnboardingScreen(
    host: NativeParityActivity, typography: Typography, case: ScreenParityCase, reference: Boolean,
    saved: Any?, request: () -> Unit, finish: () -> Unit,
    mutableQaDraft: Boolean = false,
): ScreenParityRender {
    check(host.hasWindowFocus()) {
        val root = host.fixtureRoot
        val decor = host.window.decorView
        "Onboarding QA host lost window focus; stop without reclaiming focus: " + JSONObject()
            .put("uptime_millis", android.os.SystemClock.uptimeMillis())
            .put("elapsed_realtime_millis", android.os.SystemClock.elapsedRealtime())
            .put("case", case.name).put("reference", reference)
            .put("activity_finishing", host.isFinishing).put("activity_destroyed", host.isDestroyed)
            .put("lifecycle", host.lifecycle.currentState.name)
            .put("own_window_focus", host.hasWindowFocus())
            .put("root_attached", root.isAttachedToWindow).put("root_shown", root.isShown)
            .put("root_visibility", root.visibility).put("root_window_visibility", root.windowVisibility)
            .put("root_window_focus", root.hasWindowFocus())
            .put("root_display", root.display?.displayId ?: JSONObject.NULL)
            .put("decor_attached", decor.isAttachedToWindow).put("decor_shown", decor.isShown)
            .put("decor_window_focus", decor.hasWindowFocus())
            .put("focused_view_class", host.currentFocus?.javaClass?.name ?: JSONObject.NULL)
            .put("window_token_present", decor.windowToken != null)
    }
    val config = Configuration(host.resources.configuration).apply {
        fontScale = case.scale
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (case.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    val context = host.createConfigurationContext(config)
    val density = Density(context.resources.displayMetrics.density, case.scale)
    val palette = ReverbPalette.resolve(context, case.dark)
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && case.dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        case.dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    host.fixtureRoot.setBackgroundColor(palette.background)
    val permissions = OnboardingPermissionStatus(case.ready, true, case.ready, true, case.ready,
        true, case.ready, case.ready, true)
    val initial = OnboardingPageContentState(permissions, true, true, false, false, true, true, true, true)
    val direction = if (case.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    val rendered = if (reference) {
        @Suppress("UNCHECKED_CAST")
        val registry = SaveableStateRegistry(saved as? Map<String, List<Any?>>, canBeSaved = { true })
        val view = ComposeView(context).apply {
            layoutDirection = direction
            setContent {
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                    LocalDensity provides density, LocalSaveableStateRegistry provides registry,
                    LocalLayoutDirection provides if (case.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    MaterialTheme(colorScheme = colors, typography = typography) {
                        ReleasedOnboardingScreen(ReleasedOnboardingStatus(
                            permissions.microphoneAllowed, permissions.storageAllowed, permissions.storageRequired,
                            permissions.recoveryAllowed, permissions.recoveryRequired, permissions.notificationsAllowed,
                            permissions.notificationPermissionRequired, permissions.batteryOptimizationAllowed),
                            true, true, OnboardingAudioSelection(false, false), true, true, false,
                            request, request, request, request, request, { _, _, _, _ -> finish() })
                    }
                }
            }
        }
        ScreenParityRender(view, true, registry::performSave, view::disposeComposition)
    } else {
        val runtime = NativeAboutWindowRuntime().apply { setStarted(true) }
        try {
            var draft = initial
            lateinit var view: NativeOnboardingScreenView
            fun changed(next: OnboardingPageContentState) {
                if (mutableQaDraft) { draft = next; view.bind(next) }
            }
            view = NativeOnboardingScreenView.create(context,
                nativeParityEnvironment(context, density, palette),
                initial, host, OnboardingPageActions({ request() },
                    { changed(draft.copy(oneShotEnabled = it)) },
                    { changed(draft.copy(loopingEnabled = it)) },
                    { changed(draft.copy(outputCompression = it)) },
                    { changed(draft.copy(skipSilence = it)) }), finish,
                runtime.scope, host.onBackPressedDispatcher,
                saved as? NativeOnboardingViewportSavedState ?: NativeOnboardingViewportSavedState())
            view.layoutDirection = direction
            ScreenParityRender(view, false, view::saveViewportState) {
                var failure = captureNativeParityCleanup(null) { view.close() }
                failure = captureNativeParityCleanup(failure) { runtime.close() }
                failure?.let { throw it }
            }
        } catch (error: Throwable) {
            captureNativeParityCleanup(error) { runtime.close() }
            throw error
        }
    }
    try {
        host.fixtureRoot.addView(rendered.view, FrameLayout.LayoutParams(-1, -1))
        return rendered
    } catch (error: Throwable) {
        captureNativeParityCleanup(error) { rendered.close() }
        captureNativeParityCleanup(error) { host.fixtureRoot.removeView(rendered.view) }
        throw error
    }
}

/** After the unmodified raster/tree sequence, ensure implicit-focus suppression did not disable
 * actual keyboard focus. Touch-mode admission is preserved, not changed to manufacture a pass. */
private fun verifyOnboardingExplicitFooterFocus(render: ScreenParityRender): JSONObject {
    val label = render.view.context.getString(R.string.onboarding_continue)
    val button = onboardingViewDescendants(render.view).single { view ->
        view is NativeMergedRowAccessibilityHost && onboardingViewDescendants(view)
            .filterIsInstance<ReverbParagraphView>().any { it.getText() == label }
    }
    check(button.isEnabled && button.isFocusable)
    check(!button.defaultFocusHighlightEnabled) { "Source indication must be the sole focus highlight" }
    check(!button.isFocused) { "Semantic navigation must not implicitly focus a footer button" }
    val touchMode = button.isInTouchMode
    val requested = if (!touchMode) button.requestFocus() else null
    if (!touchMode) check(requested == true && button.isFocused) { "Explicit keyboard focus was suppressed" }
    return JSONObject().put("implicit_focus_absent", true).put("default_focus_highlight", false)
        .put("touch_mode", touchMode).put("explicit_focus_accepted", requested ?: JSONObject.NULL)
        .put("explicit_focus_qualification", if (touchMode) "not attempted in touch mode" else "accepted")
}

internal fun invokeOnboardingSemanticClick(render: ScreenParityRender, label: String) {
    if (render.reference) {
        val root = onboardingViewDescendants(render.view).filterIsInstance<ViewRootForTest>().single()
        val matches = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).filter {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true &&
                it.config.getOrNull(SemanticsActions.OnClick)?.action != null
        }
        check(matches.size == 1) { "Expected one source button for $label, got ${matches.size}" }
        check(matches.single().config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
    } else {
        val matches = onboardingViewDescendants(render.view).filter { view ->
            view is NativeMergedRowAccessibilityHost && onboardingViewDescendants(view)
                .filterIsInstance<ReverbParagraphView>().any { it.getText() == label }
        }
        check(matches.size == 1) { "Expected one native button for $label, got ${matches.size}" }
        check(matches.single().performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
    }
}

internal fun awaitOnboardingScreenSettled(
    instrumentation: Instrumentation, host: NativeParityActivity, render: ScreenParityRender, expected: Int,
) {
    var consecutive = 0
    repeat(100) {
        requireNativeParityHost(instrumentation, host)
        awaitNativeParityDraw(instrumentation, render.view)
        val settled = nativeParityOnMain(instrumentation) {
            if (render.reference) {
                val root = onboardingViewDescendants(render.view).filterIsInstance<ViewRootForTest>().single()
                val pager = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).single {
                    it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
                }
                val range = checkNotNull(pager.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange))
                abs(range.value() - expected * pager.size.width.toFloat()) < .01f
            } else {
                val state = (render.view as NativeOnboardingScreenView).saveViewportState()
                state.page == expected && state.offsetFraction == 0f
            }
        }
        consecutive = if (settled) consecutive + 1 else 0
        if (consecutive >= 3) return
    }
    error("Screen did not reach page $expected in 100 observed frame boundaries")
}

private fun compareOnboardingSavedPixels(first: File, second: File): JSONObject {
    val expected = checkNotNull(BitmapFactory.decodeFile(first.absolutePath))
    try {
        val actual = checkNotNull(BitmapFactory.decodeFile(second.absolutePath))
        try { return compareNativeParityPixels(expected, actual) }
        finally { actual.recycle() }
    } finally { expected.recycle() }
}
