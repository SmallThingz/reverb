package app.smallthingz.reverb

import smallthingz.ui.components.NativeModalWindowRuntime

import smallthingz.ui.scroll.NativeVerticalViewport

import smallthingz.ui.text.view.ParagraphView
import android.app.Application
import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.ui.unit.Density
import app.smallthingz.reverb.nativeui.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Requested current page-three controls. Synthetic calibration; no recorder, preferences or oracle edits. */
internal fun verifyNativeOnboardingControlsCurrent(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val original = getRecorderPreferences(context).all.toMap()
    val directory = File(context.filesDir, "native-onboarding-controls-current-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("passed", false).put("cases", cases)
        .put("scope", "Actual current onboarding page and shared Settings controls; synthetic calibration completion")
        .put("unqualified", JSONArray(listOf("real microphone calibration", "TalkBack delivery", "physical keyboard", "system permission dialogs", "released pixel parity")))
    fun publish() = writeQaEvidenceAtomically(File(directory, "result.json")) { stream ->
        stream.write(result.toString(2).toByteArray(Charsets.UTF_8))
    }
    publish()
    var failure: Throwable? = null
    try {
        result.put("bundle_cases", JSONArray(NativeOnboardingDraftAssertions.runChecks()))
        for (dark in listOf(false, true)) {
            val host = launchNativeParityActivity(instrumentation)
            awaitNativeParityDraw(instrumentation, host.fixtureRoot)
            val runtime = NativeModalWindowRuntime(host).apply { setStarted(true) }
            var view: NativeOnboardingScreenView? = null
            var caseFailure: Throwable? = null
            try {
                lateinit var state: OnboardingPageContentState
                var levels = 0
                var calibrationRequests = 0
                var calibrationCancels = 0
                var curveEdits = 0
                var forbidden = 0
                var finishCalibration: () -> Unit = { error("Calibration fixture not initialized") }
                var cancelCalibration: () -> Unit = { error("Calibration fixture not initialized") }
                fun main(action: () -> Unit) = nativeParityOnMain(instrumentation, action)
                main {
                    val config = Configuration(host.resources.configuration).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        fontScale = if (dark) 1.3f else 1f
                    }
                    val themed = host.createConfigurationContext(config)
                    val palette = ReverbPalette.resolve(themed, dark)
                    host.fixtureRoot.setBackgroundColor(palette.background)
                    state = OnboardingPageContentState(
                        OnboardingPermissionStatus(false, false, true, false, true, true, true, true, true),
                        true, true, true, true, true, true, true, true,
                        compressionLevel = 5,
                        silenceState = SettingsSilenceState(uncalibratedSilenceCurve.copy(enabled = true)),
                    )
                    fun update(next: OnboardingPageContentState) { state = next; checkNotNull(view).bind(state) }
                    val silence = object : SettingsSilenceActions {
                        override fun configChanged(config: SilenceGateConfig) {
                            curveEdits++
                            update(state.copy(skipSilence = config.enabled,
                                silenceState = checkNotNull(state.silenceState).copy(config = config)))
                        }
                        override fun calibrate() {
                            calibrationRequests++
                            update(state.copy(silenceState = checkNotNull(state.silenceState).copy(
                                calibrating = true, calibrationProgress = .5f)))
                        }
                        override fun beginHeld(): Boolean { forbidden++; return false }
                        override fun completeTap() { forbidden++ }
                        override fun finishHeld() { forbidden++ }
                        override fun cancel() { calibrationCancels++; cancelCalibration() }
                    }
                    cancelCalibration = {
                        update(state.copy(silenceState = checkNotNull(state.silenceState).copy(
                            calibrating = false, holdingCalibration = false, calibrationProgress = null)))
                    }
                    finishCalibration = {
                        val calibrated = uncalibratedSilenceCurve.copy(enabled = true,
                            points = uncalibratedSilenceCurve.points.map { it.copy(db = -42) })
                        update(state.copy(silenceState = SettingsSilenceState(calibrated,
                            sampledPeaks = List(SILENCE_GRAPH_BINS) { -60f })))
                    }
                    view = NativeOnboardingScreenView.create(themed,
                        nativeParityEnvironment(themed, Density(themed.resources.displayMetrics.density, config.fontScale), palette),
                        state, host, OnboardingPageActions(
                            requestPermission = { forbidden++ }, oneShotChanged = { forbidden++ }, loopingChanged = { forbidden++ },
                            compressionChanged = { update(state.copy(outputCompression = it)) },
                            skipSilenceChanged = { update(state.copy(skipSilence = it)) },
                            compressionLevelChanged = { levels++; update(state.copy(compressionLevel = it)) },
                            silenceActions = silence,
                        ), { forbidden++ }, runtime.scope, host.onBackPressedDispatcher,
                        NativeOnboardingViewportSavedState(page = 2))
                    val width = minOf(host.fixtureRoot.width,
                        ((if (dark) 320 else 480) * themed.resources.displayMetrics.density).toInt())
                    check(width > 0)
                    host.fixtureRoot.addView(view, FrameLayout.LayoutParams(width, -1))
                }
                val screen = checkNotNull(view)
                awaitNativeParityDraw(instrumentation, screen)
                fun all() = currentOnboardingViews(screen)
                fun visible(target: View) {
                    val viewport = nativeParityOnMain(instrumentation) {
                        generateSequence(target.parent as? View) { it.parent as? View }
                            .filterIsInstance<NativeVerticalViewport>().first()
                    }
                    val completed = CountDownLatch(1)
                    var failure: Throwable? = null
                    val request = nativeParityOnMain(instrumentation) {
                        viewport.inputScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                viewport.bringDescendantIntoView(target) {
                                    smallthingz.ui.geometry.Rect(0f, 0f, target.width.toFloat(), target.height.toFloat())
                                }
                            } catch (error: Throwable) { failure = error }
                            finally { completed.countDown() }
                        }
                    }
                    try {
                        check(completed.await(5, TimeUnit.SECONDS)) { "Bring-into-view request did not complete" }
                        failure?.let { throw it }
                    } finally {
                        if (!request.isCompleted) main { request.cancel() }
                    }
                    // A request can complete as soon as its bounds fit while the same spring
                    // drains. Await its actual scroll owner before any direct geometry fixture.
                    awaitNativeParityDraw(instrumentation, screen)
                    val deadline = SystemClock.uptimeMillis() + 4_000L
                    var lastFailedPredicate: JSONObject? = null
                    while (!nativeParityOnMain(instrumentation) {
                        val idle = !viewport.state.isScrollInProgress
                        val viewportReady = if (idle) !viewport.isLayoutRequested else null
                        val targetReady = if (viewportReady == true) !target.isLayoutRequested else null
                        val visible = if (targetReady == true) target.getGlobalVisibleRect(Rect()) else null
                        val accepted = visible == true
                        if (!accepted) lastFailedPredicate = JSONObject()
                            .put("uptime_ms", SystemClock.uptimeMillis()).put("scroll_idle", idle)
                            .put("viewport_layout_clear", viewportReady ?: JSONObject.NULL)
                            .put("target_layout_clear", targetReady ?: JSONObject.NULL)
                            .put("target_visible", visible ?: JSONObject.NULL)
                        accepted
                    }) {
                        try { check(SystemClock.uptimeMillis() < deadline) }
                        catch (primary: Throwable) {
                            try {
                                result.put("visibility_timeout", nativeParityOnMain(instrumentation) {
                                    val clip = Rect(); val targetRect = Rect()
                                    JSONObject().put("dark", dark).put("target_class", target.javaClass.name)
                                        .put("last_failed_predicate", checkNotNull(lastFailedPredicate))
                                        .put("predicate_null_means", "not evaluated due to original short-circuit order")
                                        .put("target", currentOnboardingFocusFacts(target))
                                        .put("target_layout_requested", target.isLayoutRequested)
                                        .put("viewport_layout_requested", viewport.isLayoutRequested)
                                        .put("scroll_in_progress", viewport.state.isScrollInProgress)
                                        .put("scroll_value", viewport.state.value).put("scroll_max", viewport.state.maxValue)
                                        .put("target_visible", target.getGlobalVisibleRect(targetRect))
                                        .put("target_rect", targetRect.toShortString())
                                        .put("viewport_visible", viewport.getGlobalVisibleRect(clip))
                                        .put("viewport_rect", clip.toShortString())
                                        .put("focus_class", screen.rootView.findFocus()?.javaClass?.name ?: JSONObject.NULL)
                                        .put("target_owns_focus", screen.rootView.findFocus() === target)
                                        .put("uptime_ms", SystemClock.uptimeMillis())
                                })
                                publish()
                                val bitmap = captureNativeParityContent(instrumentation, host) { screen }
                                try { saveNativeParityBitmap(directory, "${if (dark) "dark-small" else "light"}-visibility-timeout.png", bitmap) }
                                finally { bitmap.recycle() }
                            } catch (evidence: Throwable) { if (evidence !== primary) primary.addSuppressed(evidence) }
                            throw primary
                        }
                        awaitNativeParityDraw(instrumentation, screen)
                    }
                    awaitNativeParityDraw(instrumentation, screen)
                }
                fun capture(label: String) {
                    val bitmap = captureNativeParityContent(instrumentation, host) { screen }
                    try { saveNativeParityBitmap(directory, "${if (dark) "dark-small" else "light"}-$label.png", bitmap) }
                    finally { bitmap.recycle() }
                }
                val compression = nativeParityOnMain(instrumentation) { all().filterIsInstance<SettingsOutputCompressionView>().single() }
                val slider = nativeParityOnMain(instrumentation) { checkNotNull(compression.currentSlider).pointerView }
                visible(slider)
                main {
                    for (level in listOf(0, 8, 3)) {
                        val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, level.toFloat()) }
                        check(slider.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args))
                        check(state.compressionLevel == level)
                    }
                    check(levels == 3)
                }
                val pointer = nativeParityOnMain(instrumentation) {
                    val before = levels
                    val event = currentOnboardingSliderGesture(screen, slider)
                    check(levels > before && state.compressionLevel in 0..8) { "Scrolled slider never received pointer edits" }
                    event.put("callbacks_before", before).put("callbacks_after", levels)
                        .put("level_after", state.compressionLevel)
                }
                capture("shared-compression-slider")
                val curve = nativeParityOnMain(instrumentation) { all().filterIsInstance<SilenceCurveView>().single() }
                visible(curve)
                main {
                    val points = currentOnboardingCurvePoints(curve)
                    check(points.size == 11 && points.all { it.db == 0 })
                    check(curve.isEnabled)
                }
                capture("uncalibrated-eleven-zero-knots")
                val manualCurve = nativeParityOnMain(instrumentation) {
                    check(checkNotNull(state.silenceState).sampledPeaks == null)
                    val before = curveEdits
                    val originalPoints = currentOnboardingCurvePoints(curve)
                    check(curve.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
                    val edited = currentOnboardingCurvePoints(curve)
                    check(curveEdits == before + 1 && edited != originalPoints && edited.size == 11)
                    check(edited == checkNotNull(state.silenceState).config.points)
                    JSONObject().put("without_histogram", true).put("action_handled", true)
                        .put("before", JSONArray(originalPoints.map { it.toString() }))
                        .put("after", JSONArray(edited.map { it.toString() }))
                }
                val silence = nativeParityOnMain(instrumentation) { all().filterIsInstance<SettingsSilenceView>().single() }
                val calibrationContainer = nativeParityOnMain(instrumentation) { checkNotNull(silence.calibrationPointerNode).view }
                // Offscreen source nodes are intentionally absent. Scroll the real control first,
                // then require its ordinary exposed accessibility action and label.
                visible(calibrationContainer)
                val calibrationAction = nativeParityOnMain(instrumentation) {
                    val label = host.getString(R.string.silence_calibrate)
                    currentOnboardingViews(calibrationContainer).first { target ->
                        val node = target.createAccessibilityNodeInfo() ?: return@first false
                        try { node.isClickable && node.isEnabled && currentOnboardingViews(target).any { child ->
                            val info = child.createAccessibilityNodeInfo() ?: return@any false
                            try { info.text?.toString() == label || info.contentDescription?.toString() == label }
                            finally { @Suppress("DEPRECATION") info.recycle() }
                        } } finally { @Suppress("DEPRECATION") node.recycle() }
                    }
                }
                // This route models an explicitly keyboard-focused native action, not TalkBack
                // or a bare semantic click while Android has no focused View.
                val calibrationFocus = nativeParityOnMain(instrumentation) {
                    val before = currentOnboardingFocusFacts(calibrationAction)
                    val accepted = calibrationAction.requestFocusFromTouch()
                    check(accepted && screen.findFocus() === calibrationAction)
                    JSONObject().put("before", before).put("request_focus_return", accepted)
                        .put("after", currentOnboardingFocusFacts(calibrationAction))
                }
                result.put("calibration_focus_pending", calibrationFocus.put("dark", dark))
                visible(calibrationAction)
                val keyActivations = JSONArray()
                fun activateCalibration() {
                    check(screen.findFocus() === calibrationAction && calibrationAction.isEnabled)
                    val now = SystemClock.uptimeMillis()
                    check(calibrationAction.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)))
                    check(calibrationAction.dispatchKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)))
                    check(screen.findFocus() === calibrationAction)
                    keyActivations.put(JSONObject().put("down_handled", true).put("up_handled", true)
                        .put("focus_retained", true).put("key", "ENTER"))
                }
                val calibration = JSONObject().put("dark", dark).put("focus_setup", calibrationFocus)
                    .put("activation", "synthetic focused own-view KeyEvent down/up; not physical keyboard or TalkBack")
                    .put("key_activations", keyActivations)
                result.put("calibration_pending", calibration)
                val initialGeometry = nativeParityOnMain(instrumentation) {
                    currentOnboardingCalibrationGeometry(calibrationContainer, calibrationAction, silence)
                }
                fun calibrationEvidence(stage: String, label: String, progress: Float?) {
                    try { main {
                    val geometry = currentOnboardingCalibrationGeometry(calibrationContainer, calibrationAction, silence)
                    val node = checkNotNull(calibrationAction.createAccessibilityNodeInfo())
                    try {
                        val stateDescription = androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(node).stateDescription
                        val range = node.rangeInfo
                        val bounds = Rect(); node.getBoundsInScreen(bounds)
                        val viewport = generateSequence(calibrationAction.parent as? View) { it.parent as? View }
                            .filterIsInstance<NativeVerticalViewport>().first()
                        val clip = Rect(); check(viewport.getGlobalVisibleRect(clip))
                        val hasLabel = currentOnboardingViews(calibrationAction).any { child ->
                            val info = child.createAccessibilityNodeInfo() ?: return@any false
                            try { info.text?.toString() == label || info.contentDescription?.toString() == label }
                            finally { @Suppress("DEPRECATION") info.recycle() }
                        }
                        calibration.put(stage, JSONObject().put("geometry", JSONArray(geometry))
                            .put("initial_geometry", JSONArray(initialGeometry)).put("label", label).put("label_found", hasLabel)
                            .put("state_description", stateDescription?.toString() ?: JSONObject.NULL)
                            .put("range_current", range?.current?.toDouble() ?: JSONObject.NULL)
                            .put("enabled", node.isEnabled).put("clickable", node.isClickable).put("visible", node.isVisibleToUser)
                            .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                            .put("viewport_clip", JSONArray(listOf(clip.left, clip.top, clip.right, clip.bottom)))
                            .put("focus", currentOnboardingFocusFacts(calibrationAction))
                            .put("focus_retained", screen.findFocus() === calibrationAction)
                            .put("scroll_value", viewport.state.value).put("scroll_in_progress", viewport.state.isScrollInProgress))
                        check(screen.findFocus() === calibrationAction) { "Calibration action lost input focus at $stage" }
                        check(!viewport.state.isScrollInProgress) { "Calibration viewport is still scrolling at $stage" }
                        check(geometry == initialGeometry) { "Calibration changed its settled geometry at $stage" }
                        check(hasLabel && node.isEnabled && node.isClickable && node.isVisibleToUser)
                        check(!bounds.isEmpty && clip.contains(bounds)) { "Calibration semantics escaped the actual viewport clip" }
                        if (progress == null) check(stateDescription.isNullOrEmpty() && range == null)
                        else {
                            check(stateDescription?.toString() == "50%")
                            check(range != null && range.type == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT &&
                                range.min == 0f && range.max == 1f && range.current == progress)
                            check(!curve.isEnabled)
                        }
                    } finally { @Suppress("DEPRECATION") node.recycle() }
                    } } catch (primary: Throwable) {
                        try { publish(); capture("calibration-$stage-failure") }
                        catch (evidence: Throwable) { if (evidence !== primary) primary.addSuppressed(evidence) }
                        throw primary
                    }
                }
                calibrationEvidence("idle", host.getString(R.string.silence_calibrate), null)
                main {
                    activateCalibration()
                    check(calibrationRequests == 1)
                }
                awaitNativeParityDraw(instrumentation, screen)
                calibrationEvidence("running", host.getString(R.string.cancel), .5f)
                capture("calibration-running-filled-button")
                main {
                    activateCalibration()
                    check(calibrationCancels == 1 && !checkNotNull(state.silenceState).calibrating)
                }
                awaitNativeParityDraw(instrumentation, screen)
                calibrationEvidence("cancelled", host.getString(R.string.silence_calibrate), null)
                main {
                    activateCalibration()
                    check(calibrationRequests == 2 && checkNotNull(state.silenceState).calibrating)
                }
                awaitNativeParityDraw(instrumentation, screen)
                calibrationEvidence("restarted", host.getString(R.string.cancel), .5f)
                main { finishCalibration() }
                awaitNativeParityDraw(instrumentation, screen)
                calibrationEvidence("completed", host.getString(R.string.silence_calibrate), null)
                visible(curve)
                val calibratedPoints = nativeParityOnMain(instrumentation) {
                    val expected = checkNotNull(state.silenceState).config.points
                    val observed = currentOnboardingCurvePoints(curve)
                    check(observed.size == 11 && curve.isEnabled && observed == expected)
                    val evidence = JSONObject().put("expected", JSONArray(expected.map { it.toString() }))
                        .put("observed", JSONArray(observed.map { it.toString() }))
                    val before = curveEdits
                    check(curve.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
                    check(curveEdits == before + 1)
                    evidence
                }
                capture("synthetic-calibrated-editable")
                visible(calibrationAction)
                main {
                    activateCalibration()
                    check(calibrationRequests == 3 && checkNotNull(state.silenceState).calibrating)
                }
                visible(slider)
                val focusSetup = JSONObject().put("request_attempted", false)
                    .put("request_focus_return", JSONObject.NULL)
                result.put("retained_focus_pending", focusSetup)
                main {
                    focusSetup.put("before", currentOnboardingFocusFacts(slider))
                    try {
                        check(slider.isAttachedToWindow && slider.isShown && slider.isEnabled && slider.isFocusable &&
                            slider.hasWindowFocus() && slider.display?.displayId == 0 &&
                            slider.getGlobalVisibleRect(Rect())) { "Slider focus setup requires the visible owned physical window" }
                        focusSetup.put("request_attempted", true)
                        val accepted = slider.requestFocusFromTouch()
                        focusSetup.put("request_focus_return", accepted)
                        check(accepted && slider.hasFocus()) { "Slider focus setup did not focus the actual control" }
                    } finally {
                        focusSetup.put("after", currentOnboardingFocusFacts(slider))
                    }
                    host.onBackPressedDispatcher.onBackPressed()
                }
                val pageDeadline = SystemClock.uptimeMillis() + 5_000L
                while (!nativeParityOnMain(instrumentation) {
                    screen.saveViewportState().let { it.page == 1 && it.offsetFraction == 0f }
                }) {
                    check(SystemClock.uptimeMillis() < pageDeadline) { "Back never settled on the buffer page" }
                    awaitNativeParityDraw(instrumentation, screen)
                }
                val offscreen = nativeParityOnMain(instrumentation) {
                    val oldLevel = state.compressionLevel; val oldLevels = levels
                    val oldCurve = curveEdits; val oldCalibration = calibrationRequests
                    val oldCancels = calibrationCancels; val oldSilence = state.silenceState
                    check(slider.isAttachedToWindow) { "Retained-control regression requires the actual attached offscreen slider" }
                    val focused = slider.hasFocus()
                    screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                    screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
                    slider.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 1f) })
                    curve.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
                    check(calibrationAction.createAccessibilityNodeInfo() == null) { "Clipped calibration action remained exposed" }
                    check(!calibrationAction.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                    check(state.compressionLevel == oldLevel && levels == oldLevels)
                    check(curveEdits == oldCurve && calibrationRequests == oldCalibration)
                    check(calibrationCancels == oldCancels && state.silenceState == oldSilence)
                    JSONObject().put("attached", true).put("focused_after_back", focused)
                        .put("draft_and_callbacks_unchanged", true).put("dispatch", "own-root key and retained semantic actions, not hardware keyboard")
                }
                capture("buffer-page-offscreen-controls-retired")
                // Model the real owner cancellation publication while the audio page is hidden.
                main { cancelCalibration() }
                awaitNativeParityDraw(instrumentation, screen)
                main {
                    val footer = all().filterIsInstance<OnboardingFooterView>().single()
                    val label = host.getString(R.string.onboarding_continue)
                    val next = currentOnboardingViews(footer).first { target ->
                        val node = target.createAccessibilityNodeInfo() ?: return@first false
                        try { node.isClickable && node.isEnabled && currentOnboardingViews(target).any { child ->
                            val info = child.createAccessibilityNodeInfo() ?: return@any false
                            try { info.text?.toString() == label || info.contentDescription?.toString() == label }
                            finally { @Suppress("DEPRECATION") info.recycle() }
                        } } finally { @Suppress("DEPRECATION") node.recycle() }
                    }
                    check(next.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                }
                val reentryDeadline = SystemClock.uptimeMillis() + 5_000L
                while (!nativeParityOnMain(instrumentation) {
                    screen.saveViewportState().let { it.page == 2 && it.offsetFraction == 0f }
                }) {
                    check(SystemClock.uptimeMillis() < reentryDeadline)
                    awaitNativeParityDraw(instrumentation, screen)
                }
                val restoredCalibrationContainer = nativeParityOnMain(instrumentation) {
                    checkNotNull(all().filterIsInstance<SettingsSilenceView>().single().calibrationPointerNode).view
                }
                visible(restoredCalibrationContainer)
                val restoredLabel = nativeParityOnMain(instrumentation) {
                    currentOnboardingViews(restoredCalibrationContainer).filterIsInstance<ParagraphView>().first { target ->
                        val info = checkNotNull(target.createAccessibilityNodeInfo())
                        try { info.text?.toString() == host.getString(R.string.silence_calibrate) }
                        finally { @Suppress("DEPRECATION") info.recycle() }
                    }
                }
                visible(restoredLabel)
                val restoredTextActions = nativeParityOnMain(instrumentation) {
                    val info = checkNotNull(restoredLabel.createAccessibilityNodeInfo())
                    try {
                        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
                        check(info.availableExtraData.contains(key))
                        check(info.movementGranularities and AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER != 0)
                        check(restoredLabel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
                            Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER) }))
                        restoredLabel.addExtraDataToAccessibilityNodeInfo(info, key, Bundle().apply {
                            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
                            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 1)
                        })
                        @Suppress("DEPRECATION") val boxes = info.extras.getParcelableArray(key)
                        val box = checkNotNull(boxes?.singleOrNull() as? android.graphics.RectF)
                        check(box.width() > 0f && box.height() > 0f)
                        JSONObject().put("movement_action_handled", true).put("character_box",
                            JSONArray(listOf(box.left.toDouble(), box.top.toDouble(), box.right.toDouble(), box.bottom.toDouble())))
                    } finally { @Suppress("DEPRECATION") info.recycle() }
                }
                capture("audio-reentry-text-handlers-restored")
                check(forbidden == 0 && getRecorderPreferences(context).all == original)
                cases.put(JSONObject().put("dark", dark).put("font_scale", if (dark) 1.3 else 1.0)
                    .put("compression_levels", JSONArray(listOf(0, 8, 3))).put("compression_callbacks", levels).put("slider_pointer", pointer)
                    .put("uncalibrated_knots", 11).put("synthetic_calibration_callbacks", calibrationRequests)
                    .put("curve_edit_callbacks", curveEdits).put("manual_curve", manualCurve)
                    .put("calibration_cancel_callbacks", calibrationCancels).put("calibrated_points", calibratedPoints).put("calibration_button", calibration).put("focus_setup", focusSetup).put("offscreen_actions", offscreen).put("reentry_text_actions", restoredTextActions).put("preferences_unchanged", true))
                publish()
            } catch (error: Throwable) { caseFailure = error; throw error }
            finally {
                try { nativeParityOnMain(instrumentation) {
                    try { view?.close() } finally { try { runtime.close() } finally { host.finish() } }
                } } catch (cleanup: Throwable) {
                    val primary = caseFailure
                    if (primary == null) throw cleanup
                    if (cleanup !== primary) primary.addSuppressed(cleanup)
                }
            }
        }
        check(cases.length() == 2)
        result.put("passed", true)
    } catch (error: Throwable) { failure = error; result.put("failure", error.stackTraceToString()) }
    finally {
        try { publish() } catch (evidence: Throwable) {
            val primary = failure
            if (primary == null) failure = evidence
            else if (evidence !== primary) primary.addSuppressed(evidence)
        } finally {
            report.appendLine("ONBOARDING_CONTROLS_EVIDENCE ${directory.absolutePath}")
        }
    }
    failure?.let { throw it }
    report.appendLine("PASS native_onboarding_controls_current shared compression, eleven-knot states and Android Bundle draft restoration")
}

private fun currentOnboardingViews(root: View): Sequence<View> = sequence {
    yield(root)
    if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(currentOnboardingViews(root.getChildAt(index)))
}

private fun currentOnboardingCurvePoints(curve: SilenceCurveView): List<SilencePoint> {
    val field = SilenceCurveView::class.java.getDeclaredField("gesture").apply { isAccessible = true }
    return (field.get(curve) as SilenceCurveGesture).points.toList()
}

private fun currentOnboardingFocusFacts(target: View): JSONObject {
    val bounds = Rect()
    val visible = target.getGlobalVisibleRect(bounds)
    return JSONObject().put("attached", target.isAttachedToWindow).put("shown", target.isShown)
        .put("enabled", target.isEnabled).put("focusable", target.isFocusable)
        .put("focusable_in_touch_mode", target.isFocusableInTouchMode).put("in_touch_mode", target.isInTouchMode)
        .put("focused", target.isFocused).put("has_focus", target.hasFocus())
        .put("window_focused", target.hasWindowFocus()).put("display", target.display?.displayId ?: JSONObject.NULL)
        .put("globally_visible", visible)
        .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
}

private fun currentOnboardingCalibrationGeometry(container: View, action: View, silence: View): List<Int> {
    check(!container.isLayoutRequested && !action.isLayoutRequested && !silence.isLayoutRequested) {
        "Calibration geometry requires completed layout"
    }
    fun bounds(view: View): List<Int> {
        val position = IntArray(2); view.getLocationOnScreen(position)
        return listOf(position[0], position[1], position[0] + view.width, position[1] + view.height)
    }
    return bounds(container) + bounds(action) + silence.height
}

private fun currentOnboardingSliderGesture(root: NativeOnboardingScreenView, slider: View): JSONObject {
    check(root.hasWindowFocus() && root.display?.displayId == 0 && slider.isEnabled)
    val state = root.saveViewportState()
    check(state.page == 2 && state.offsetFraction == 0f)
    val visible = Rect(); val rootVisible = Rect()
    check(slider.getGlobalVisibleRect(visible) && root.getGlobalVisibleRect(rootVisible) && visible.intersect(rootVisible))
    check(visible.width() > 20 && visible.height() > 0)
    val location = IntArray(2); root.getLocationOnScreen(location)
    val startX = visible.left + visible.width() * .3f - location[0]
    val endX = visible.left + visible.width() * .75f - location[0]
    val y = visible.exactCenterY() - location[1]
    val down = SystemClock.uptimeMillis()
    val actions = JSONArray()
    var downAccepted = false
    fun send(action: Int, x: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            val accepted = root.dispatchTouchEvent(event)
            actions.put(JSONObject().put("action", action).put("x", x).put("y", y).put("accepted", accepted))
            if (action == MotionEvent.ACTION_DOWN) downAccepted = accepted
            check(accepted) { "Own-root scrolled slider pointer rejected" }
        } finally { event.recycle() }
    }
    try { send(MotionEvent.ACTION_DOWN, startX); send(MotionEvent.ACTION_MOVE, endX); send(MotionEvent.ACTION_UP, endX) }
    catch (error: Throwable) {
        if (downAccepted) try { send(MotionEvent.ACTION_CANCEL, endX) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
        throw error
    }
    return JSONObject().put("dispatch", "actual root.dispatchTouchEvent, not physical input")
        .put("visible_screen_bounds", JSONArray(listOf(visible.left, visible.top, visible.right, visible.bottom)))
        .put("actions", actions)
}
