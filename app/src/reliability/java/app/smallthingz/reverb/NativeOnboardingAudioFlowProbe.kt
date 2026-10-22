package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.ui.unit.Density
import app.smallthingz.reverb.nativeui.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * New authorized onboarding UI, not an altered released oracle. All state belongs to this isolated
 * fixture. Synthetic calibration publications exercise real renderers/actions, never a microphone,
 * production owner, permission launcher, preference write, or invented successful measurement.
 */
internal fun verifyNativeOnboardingAudioFlow(instrumentation: Instrumentation, report: StringBuilder) {
    check(instrumentation.targetContext.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    val directory = File(instrumentation.targetContext.filesDir, "native-onboarding-audio-flow-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val expected = listOf("permission-missing", "permission-granted", "buffers-default", "audio-default-on-top",
        "audio-default-on-graph", "slider-input", "toggles-off", "toggles-on",
        "calibration-running", "calibration-error", "calibration-cancelled",
        "calibration-success", "retained-false", "retirement")
    val result = JSONObject().put("selector", "native_onboarding_audio_flow")
        .put("qualification", "native UI of authorized onboarding change; no released pixel comparison")
        .put("calibration", "SYNTHETIC callback and publication fixture; no audio capture")
        .put("dispatch", "own-root MotionEvents, actual View key handlers and semantic actions")
        .put("accessibility_connection", "public FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES")
        .put("expected_cases_per_palette", JSONArray(expected)).put("expected_case_count", expected.size * 2)
        .put("cases", cases).put("passed", false)
        .put("unqualified", JSONArray(listOf("actual microphone calibration", "production owner timed-calibration invocation",
            "system permission prompts/automatic sequence", "TalkBack delivery", "physical keyboard delivery",
            "released pixel parity of intentionally changed onboarding", "production preference persistence")))
    var host: NativeParityActivity? = null
    var fixture: OnboardingAudioQaFixture? = null
    var failure: Throwable? = null
    try {
        val activity = launchNativeParityActivity(instrumentation)
        host = activity
        val automation = nativeParityUiAutomation(instrumentation)
        for (dark in listOf(false, true)) {
            val paletteName = if (dark) "dark" else "light"
            val seen = linkedSetOf<String>()
            fun current() = checkNotNull(fixture)
            fun main(action: () -> Unit) {
                requireNativeParityHost(instrumentation, activity)
                nativeParityOnMain(instrumentation, action)
            }
            fun settle() {
                awaitOnboardingScreenSettled(instrumentation, activity, current().render,
                    nativeParityOnMain(instrumentation) { current().view.saveViewportState().page })
                // Observe actual lifetime/geometry/scroll completion, not a refresh-rate-dependent
                // number of frames. No private animation clock or duration scale is changed.
                var consecutive = 0
                repeat(180) {
                    requireNativeParityHost(instrumentation, activity)
                    awaitNativeParityDraw(instrumentation, current().view)
                    val ready = nativeParityOnMain(instrumentation) {
                        val f = current()
                        val descendants = onboardingViewDescendants(f.view)
                        val scrollIdle = descendants.filterIsInstance<NativeVerticalViewport>()
                            .all { !it.state.isScrollInProgress }
                        val audioReady = f.view.saveViewportState().page != 2 || (
                            (f.compression().currentSlider != null) == f.state.outputCompression &&
                            (f.silence().curveView != null) == f.state.skipSilence &&
                            descendants.filterIsInstance<SettingsExpansionView>().all { expansion ->
                                val content = expansion.content?.view
                                content == null || (content.alpha == 1f && expansion.width == content.width &&
                                    expansion.height == content.height && !expansion.isLayoutRequested)
                            })
                        !f.view.isLayoutRequested && scrollIdle && audioReady
                    }
                    consecutive = if (ready) consecutive + 1 else 0
                    if (consecutive >= 3) return
                }
                error("Native controls did not settle in 180 observed frame boundaries")
            }
            fun capture(name: String) {
                check(seen.add(name) && name in expected)
                settle()
                val f = current()
                val bitmap = captureNativeParityContent(instrumentation, activity) { f.view }
                try {
                    val stem = "$paletteName-$name"
                    saveNativeParityBitmap(directory, "$stem.png", bitmap)
                    val nodes = onboardingNativeNodeEvidence(instrumentation, activity, f.view, automation) { partial ->
                        File(directory, "$stem-failed-nodes.json").writeText(partial.toString(2))
                    }
                    val entry = nativeParityOnMain(instrumentation) {
                        JSONObject().put("palette", paletteName).put("case", name).put("passed", true)
                            .put("state", f.evidence()).put("geometry", onboardingGeometryEvidence(f.view, false))
                            .put("nodes", nodes)
                            .also {
                                if (name == "calibration-running")
                                    it.put("progress_node", onboardingAudioQaProgress(f.view, nodes))
                            }
                    }
                    File(directory, "$stem.json").writeText(entry.toString(2))
                    cases.put(entry)
                } finally { bitmap.recycle() }
            }
            fun footer(resource: Int) = current().footer(activity.getString(resource))
            fun row(resource: Int) = onboardingAudioQaRow(current().view, activity.getString(resource))
            fun scrollTo(target: () -> View) {
                repeat(8) {
                    val visible = nativeParityOnMain(instrumentation) {
                        val v = target()
                        val rect = Rect()
                        v.getGlobalVisibleRect(rect) && rect.height() >= v.height && rect.width() >= v.width
                    }
                    if (visible) return
                    main {
                        val v = target()
                        var parent = v.parent as? View
                        while (parent != null && parent !is NativeVerticalViewport) parent = parent.parent as? View
                        val viewport = parent as? NativeVerticalViewport ?: error("Target has no actual page viewport")
                        val rect = Rect(); val bounds = Rect()
                        v.getGlobalVisibleRect(rect); viewport.getGlobalVisibleRect(bounds)
                        val position = IntArray(2); v.getLocationOnScreen(position)
                        val direction = if (position[1] < bounds.top) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                            else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        check(viewport.performAccessibilityAction(direction, null)) { "Target cannot be scrolled into view" }
                    }
                    settle()
                }
                error("Target did not become fully visible in eight actual viewport actions")
            }
            fun click(view: () -> View) {
                scrollTo(view)
                main {
                    val target = view()
                    check(target.isAttachedToWindow && target.isShown && target.isEnabled)
                    check(target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                }
            }

            main {
                fixture = OnboardingAudioQaFixture(activity, dark, storedFalse = false)
                activity.fixtureRoot.addView(current().view, FrameLayout.LayoutParams(-1, -1))
            }
            capture("permission-missing")
            main { check(current().settledPages == listOf(0)) { "Initial settled page was not delivered exactly once" } }
            val permission = nativeParityOnMain(instrumentation) { row(R.string.onboarding_microphone_title) }
            main {
                val label = onboardingViewDescendants(permission).filterIsInstance<ReverbParagraphView>()
                    .single { it.getText() == activity.getString(R.string.onboarding_microphone_title) }
                val target = Rect(); val text = Rect()
                check(permission.getGlobalVisibleRect(target) && label.getGlobalVisibleRect(text))
                val x = target.left + 8f * permission.resources.displayMetrics.density
                val y = target.exactCenterY()
                check(!text.contains(x.toInt(), y.toInt())) { "Whole-card witness fell inside label" }
                onboardingAudioQaTap(current().view, x, y)
            }
            settle()
            check(nativeParityOnMain(instrumentation) { current().permissionRequests == 1 && current().state.permissions.microphoneAllowed })
            capture("permission-granted")
            click { footer(R.string.onboarding_continue) }
            settle()
            main { check(current().state.oneShotEnabled && current().state.loopingEnabled) }
            capture("buffers-default")
            click { footer(R.string.onboarding_continue) }
            settle()
            main {
                check(current().state.outputCompression && current().state.skipSilence)
                check(current().compression().currentSlider != null && current().silence().curveView != null)
                check(footer(R.string.silence_calibrate).isEnabled)
                check(current().settledPages.last() == 2)
            }
            capture("audio-default-on-top")
            scrollTo { checkNotNull(current().silence().curveView) }
            capture("audio-default-on-graph")
            scrollTo { checkNotNull(current().compression().currentSlider).pointerView }
            main {
                val slider = checkNotNull(current().compression().currentSlider).pointerView
                check(slider.isEnabled && slider.isFocusable && !slider.defaultFocusHighlightEnabled)
                val touchMode = slider.isInTouchMode
                if (!touchMode) check(slider.requestFocus() && slider.isFocused)
                current().focus = JSONObject().put("touch_mode", touchMode)
                    .put("explicit_focus", if (touchMode) "not attempted in touch mode" else "accepted")
                val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 2f) }
                check(slider.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args))
                check(slider.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
                check(slider.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)))
                check(current().state.compressionLevel == 3)
            }
            settle()
            val beforePointer = nativeParityOnMain(instrumentation) { current().compressionEdits }
            main {
                val slider = checkNotNull(current().compression().currentSlider).pointerView
                val bounds = Rect(); check(slider.getGlobalVisibleRect(bounds))
                onboardingAudioQaTap(current().view, bounds.left + bounds.width() * .8f, bounds.exactCenterY())
            }
            settle()
            check(nativeParityOnMain(instrumentation) { current().compressionEdits > beforePointer }) {
                "Real root slider pointer produced no callback"
            }
            capture("slider-input")
            val collapsedSlider = nativeParityOnMain(instrumentation) { checkNotNull(current().compression().currentSlider).pointerView }
            val collapsedCurve = nativeParityOnMain(instrumentation) { checkNotNull(current().silence().curveView) }
            click { row(R.string.output_compression) }
            click { row(R.string.silence_title) }
            settle()
            main {
                check(!current().state.outputCompression && !current().state.skipSilence)
                check(current().compression().currentSlider == null && current().silence().curveView == null)
                check(!collapsedSlider.isAttachedToWindow && !collapsedCurve.isAttachedToWindow)
                val before = current().compressionEdits
                collapsedSlider.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                check(current().compressionEdits == before) { "Retired expansion slider delivered a stale callback" }
            }
            capture("toggles-off")
            click { row(R.string.output_compression) }
            click { row(R.string.silence_title) }
            settle()
            main {
                val slider = checkNotNull(current().compression().currentSlider).pointerView
                check(slider !== collapsedSlider && current().silence().curveView !== collapsedCurve)
                check(slider.isAttachedToWindow && checkNotNull(current().silence().curveView).isAttachedToWindow)
                val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                    if (current().state.compressionLevel == 0) 1f else 0f) }
                val before = current().compressionEdits
                check(slider.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args))
                check(current().compressionEdits == before + 1) { "Replacement slider callback was not rebound" }
            }
            capture("toggles-on")
            click { footer(R.string.silence_calibrate) }
            main {
                check(current().calibrationRequests == 1 && current().state.silenceState.calibrating)
                val listening = footer(R.string.silence_sampling)
                check(!listening.isEnabled)
                val before = current().calibrationRequests
                listening.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                check(current().calibrationRequests == before)
            }
            scrollTo { onboardingViewDescendants(current().silence()).filterIsInstance<SettingsCalibrationProgressView>().single() }
            capture("calibration-running")
            main { current().publishError() }
            scrollTo { onboardingViewDescendants(current().silence()).filterIsInstance<ReverbParagraphView>()
                .single { it.getText() == ONBOARDING_QA_ERROR } }
            main {
                check(current().state.calibrationRequired && current().state.silenceState.calibrationError == ONBOARDING_QA_ERROR)
                check(onboardingViewDescendants(current().view).filterIsInstance<ReverbParagraphView>()
                    .any { it.getText() == ONBOARDING_QA_ERROR && it.visibility == View.VISIBLE })
            }
            capture("calibration-error")
            click { footer(R.string.silence_calibrate) }
            settle()
            scrollTo { onboardingAudioQaRow(current().silence(), activity.getString(R.string.cancel)) }
            click { onboardingAudioQaRow(current().silence(), activity.getString(R.string.cancel)) }
            main {
                check(current().cancelRequests == 1 && !current().state.silenceState.calibrating)
                check(current().state.calibrationRequired && current().state.silenceState.calibrationError == null)
            }
            capture("calibration-cancelled")
            click { footer(R.string.silence_calibrate) }
            main {
                check(current().calibrationRequests == 3)
                current().publishSuccess()
                check(!current().state.calibrationRequired && current().state.silenceState.sampledPeaks == ONBOARDING_QA_PEAKS)
                check(footer(R.string.onboarding_get_started).isEnabled)
            }
            capture("calibration-success")
            click { footer(R.string.onboarding_get_started) }
            main { check(current().finishRequests == 1) }
            val retired = current()
            val oldSlider = nativeParityOnMain(instrumentation) { checkNotNull(retired.compression().currentSlider).pointerView }
            val oldFinish = nativeParityOnMain(instrumentation) { retired.footer(activity.getString(R.string.onboarding_get_started)) }
            main {
                retired.close()
                check(retired.settledPages.last() == -1) { "Closed screen did not retire settled-page publication" }
                activity.fixtureRoot.removeAllViews()
                fixture = OnboardingAudioQaFixture(activity, dark, storedFalse = true, page = 2)
                activity.fixtureRoot.addView(current().view, FrameLayout.LayoutParams(-1, -1))
            }
            settle()
            main {
                check(!current().state.outputCompression && !current().state.skipSilence)
                check(current().compression().currentSlider == null && current().silence().curveView == null)
                check(footer(R.string.onboarding_get_started).isEnabled)
            }
            capture("retained-false")
            main {
                val before = retired.evidence().toString()
                oldSlider.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                oldFinish.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                check(retired.evidence().toString() == before) { "Retired control delivered a stale callback" }
                check(!oldSlider.isAttachedToWindow && !oldFinish.isAttachedToWindow)
            }
            check(seen.add("retirement"))
            cases.put(JSONObject().put("palette", paletteName).put("case", "retirement").put("passed", true)
                .put("scope", "actual disposed slider key and footer semantic callbacks; no model callback guard"))
            check(seen == expected.toSet()) { "Missing native onboarding case: ${expected.toSet() - seen}" }
            main { current().close(); activity.fixtureRoot.removeAllViews(); fixture = null }
        }
        check(cases.length() == expected.size * 2)
        check((0 until cases.length()).all { cases.getJSONObject(it).getBoolean("passed") })
        result.put("passed", true)
        report.appendLine("PASS native_onboarding_audio_flow cases=${cases.length()}; calibration UI is synthetic, no sampling claim")
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            nativeParityOnMain(instrumentation) {
                try { fixture?.close() } finally { host?.fixtureRoot?.removeAllViews(); host?.finish() }
            }
        } catch (cleanup: Throwable) {
            val primary = failure
            if (primary != null) primary.addSuppressed(cleanup) else failure = cleanup
        }
        val cleanupFailedAfterPass = failure != null && result.optBoolean("passed")
        if (failure != null) result.put("passed", false)
        result.put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
        File(directory, "result.json").writeText(result.toString(2))
        report.appendLine("Onboarding audio flow evidence: ${directory.absolutePath}")
        if (cleanupFailedAfterPass) throw checkNotNull(failure)
    }
}

private const val ONBOARDING_QA_ERROR = "QA synthetic calibration error"
private val ONBOARDING_QA_PEAKS = List(64) { index -> -78f + (index % 7) * 2f }

private class OnboardingAudioQaFixture(host: NativeParityActivity, dark: Boolean, storedFalse: Boolean, page: Int = 0) : AutoCloseable {
    private val runtime = NativeAboutWindowRuntime().apply { setStarted(true) }
    val view: NativeOnboardingScreenView
    val render: ScreenParityRender
    var permissionRequests = 0
    var compressionEdits = 0
    var calibrationRequests = 0
    var cancelRequests = 0
    var finishRequests = 0
    var focus: JSONObject? = null
    val settledPages = mutableListOf<Int>()
    private val events = mutableListOf<String>()
    var state: OnboardingPageContentState
        private set
    init {
        val config = Configuration(host.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val context = host.createConfigurationContext(config)
        val palette = ReverbPalette.resolve(context, dark)
        host.fixtureRoot.setBackgroundColor(palette.background)
        val original = OnboardingAudioSelection(false, false, 5, SilenceGateConfig())
        val selection = initialOnboardingAudioSelection(original,
            outputPreferencePresent = storedFalse, silencePreferencePresent = storedFalse, compressionAvailable = true)
        val configuration = OnboardingConfiguration(ConfiguredBufferAvailability(true, true),
            original, true, true, initialAudio = selection)
        val draft = NativeOnboardingDraft.restore(configuration, null)
        if (storedFalse) {
            val newDefaults = configuration.copy(initialAudio = initialOnboardingAudioSelection(original,
                outputPreferencePresent = false, silencePreferencePresent = false, compressionAvailable = true))
            val restoredFalse = NativeOnboardingDraft.restore(newDefaults, draft.save())
            check(!restoredFalse.outputCompression && !restoredFalse.skipSilence)
        }
        state = OnboardingPageContentState(
            OnboardingPermissionStatus(false, false, true, false, true, true, true, true, true),
            draft.oneShotEnabled, draft.loopingEnabled, draft.outputCompression, draft.skipSilence, true, true, true, true,
            compressionLevel = draft.compressionLevel, silenceState = SettingsSilenceState(draft.currentSilence),
            calibrationRequired = draft.skipSilence)
        val silence = object : SettingsSilenceActions {
            override fun configChanged(config: SilenceGateConfig) {
                events += "silence:${config.enabled}"
                update(state.copy(skipSilence = config.enabled, silenceState = state.silenceState.copy(config = config),
                    calibrationRequired = config.enabled && state.silenceState.sampledPeaks == null))
            }
            override fun calibrate() = beginSynthetic()
            override fun beginHeld(): Boolean = error("Held calibration is outside this no-microphone fixture")
            override fun completeTap() = error("Held calibration is outside this no-microphone fixture")
            override fun finishHeld() = error("Held calibration is outside this no-microphone fixture")
            override fun cancel() {
                cancelRequests++; events += "synthetic-cancel"
                update(state.copy(silenceState = state.silenceState.copy(calibrating = false,
                    calibrationProgress = null, calibrationError = null)))
            }
        }
        try {
            view = NativeOnboardingScreenView.create(context,
                nativeParityEnvironment(context, Density(context.resources.displayMetrics.density, config.fontScale), palette),
                state, host, OnboardingPageActions(
                    requestPermission = {
                        check(it == OnboardingPermission.MICROPHONE)
                        permissionRequests++; events += "synthetic-permission"
                        update(state.copy(permissions = state.permissions.copy(microphoneAllowed = true)))
                    },
                    oneShotChanged = { update(state.copy(oneShotEnabled = it)) },
                    loopingChanged = { update(state.copy(loopingEnabled = it)) },
                    compressionChanged = { events += "compression:$it"; update(state.copy(outputCompression = it)) },
                    skipSilenceChanged = { silence.configChanged(state.silenceState.config.copy(enabled = it)) },
                    compressionLevelChanged = {
                        compressionEdits++; events += "level:$it"; update(state.copy(compressionLevel = it))
                    },
                    silenceActions = silence,
                    pageChanged = { settledPages += it; events += "page:$it" },
                ), {
                    if (state.calibrationRequired) beginSynthetic() else { finishRequests++; events += "synthetic-finish" }
                }, runtime.scope, host.onBackPressedDispatcher, NativeOnboardingViewportSavedState(page = page))
            render = ScreenParityRender(view, false, view::saveViewportState, ::close)
        } catch (error: Throwable) { runtime.close(); throw error }
    }
    private fun update(next: OnboardingPageContentState) { state = next; view.bind(next) }
    private fun beginSynthetic() {
        calibrationRequests++; events += "synthetic-timed-request"
        update(state.copy(silenceState = state.silenceState.copy(calibrating = true,
            calibrationProgress = .35f, calibrationError = null)))
    }
    fun publishError() {
        events += "synthetic-error-publication"
        update(state.copy(silenceState = state.silenceState.copy(calibrating = false,
            calibrationProgress = null, calibrationError = ONBOARDING_QA_ERROR)))
    }
    fun publishSuccess() {
        events += "synthetic-success-publication"
        update(state.copy(calibrationRequired = false, silenceState = state.silenceState.copy(
            calibrating = false, calibrationProgress = null, calibrationError = null, sampledPeaks = ONBOARDING_QA_PEAKS)))
    }
    fun compression() = onboardingViewDescendants(view).filterIsInstance<SettingsOutputCompressionView>().single()
    fun silence() = onboardingViewDescendants(view).filterIsInstance<SettingsSilenceView>().single()
    fun footer(label: String) = onboardingAudioQaRow(
        onboardingViewDescendants(view).filterIsInstance<OnboardingFooterView>().single(), label)
    fun evidence(): JSONObject = JSONObject().put("output_compression", state.outputCompression)
        .put("skip_silence", state.skipSilence).put("compression_level", state.compressionLevel)
        .put("calibration_required", state.calibrationRequired).put("calibrating", state.silenceState.calibrating)
        .put("calibration_error", state.silenceState.calibrationError ?: JSONObject.NULL)
        .put("synthetic_peaks", state.silenceState.sampledPeaks?.let { JSONArray(it) } ?: JSONObject.NULL)
        .put("initial_selection_source", "actual initialOnboardingAudioSelection and NativeOnboardingDraft.restore; synthetic configuration")
        .put("permission_requests", permissionRequests).put("calibration_requests", calibrationRequests)
        .put("cancel_requests", cancelRequests).put("finish_requests", finishRequests)
        .put("compression_edits", compressionEdits).put("events", JSONArray(events))
        .put("settled_pages", JSONArray(settledPages))
        .put("focus", focus ?: JSONObject.NULL)
    override fun close() { try { view.close() } finally { runtime.close() } }
}

private fun onboardingAudioQaRow(root: View, label: String): View =
    onboardingViewDescendants(root).filter { candidate ->
        candidate is NativeMergedRowAccessibilityHost && onboardingViewDescendants(candidate)
            .filterIsInstance<ReverbParagraphView>().any { it.getText() == label }
    }.single()

/** Compare actual direct and platform nodes, including clipped bounds, without enabling a service. */
private fun onboardingAudioQaProgress(root: View, tree: JSONObject): JSONObject {
    val progress = onboardingViewDescendants(root).filterIsInstance<SettingsCalibrationProgressView>().single()
    val node = checkNotNull(progress.createAccessibilityNodeInfo())
    try {
        check(node.isVisibleToUser && node.className == "android.widget.ProgressBar")
        check(node.rangeInfo?.current == .35f && node.rangeInfo?.min == 0f && node.rangeInfo?.max == 1f)
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        var parent = progress.parent as? View
        while (parent != null && parent !is NativeVerticalViewport) parent = parent.parent as? View
        val viewport = checkNotNull(parent)
        val clip = Rect(); check(viewport.getGlobalVisibleRect(clip) && clip.contains(bounds))
        val origin = IntArray(2); root.getLocationOnScreen(origin)
        val relative = JSONArray(listOf(bounds.left - origin[0], bounds.top - origin[1],
            bounds.right - origin[0], bounds.bottom - origin[1]))
        val raw = tree.getJSONArray("raw")
        val platform = (0 until raw.length()).map(raw::getJSONObject)
            .single { it.optString("class") == "android.widget.ProgressBar" && it.getBoolean("visible") }
        check(platform.getJSONArray("bounds").toString() == relative.toString())
        check(platform.optString("state_description") == androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(node).stateDescription?.toString())
        return JSONObject().put("current", .35).put("bounds", relative).put("platform_equal", true)
            .put("viewport_clipped", true).put("state_description", platform.getString("state_description"))
    } finally { node.recycle() }
}

/** Coordinates are freshly read own-view screen bounds, transformed to its current input root. */
private fun onboardingAudioQaTap(root: View, screenX: Float, screenY: Float) {
    check(root.isAttachedToWindow && root.hasWindowFocus() && root.display?.displayId == 0)
    val origin = IntArray(2); root.getLocationOnScreen(origin)
    val x = screenX - origin[0]; val y = screenY - origin[1]
    check(x >= 0 && x < root.width && y >= 0 && y < root.height)
    val down = SystemClock.uptimeMillis()
    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { check(root.dispatchTouchEvent(event)) { "Own-root pointer action $action was rejected" } }
        finally { event.recycle() }
    }
}
