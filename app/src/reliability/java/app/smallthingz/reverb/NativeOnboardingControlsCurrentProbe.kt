package app.smallthingz.reverb

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
            val runtime = NativeAboutWindowRuntime(host).apply { setStarted(true) }
            var view: NativeOnboardingScreenView? = null
            try {
                lateinit var state: OnboardingPageContentState
                var levels = 0
                var calibrationRequests = 0
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
                        override fun cancel() = cancelCalibration()
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
                    while (!nativeParityOnMain(instrumentation) {
                        !viewport.state.isScrollInProgress && !viewport.isLayoutRequested &&
                            !target.isLayoutRequested && target.getGlobalVisibleRect(Rect())
                    }) {
                        check(SystemClock.uptimeMillis() < deadline)
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
                    check(!curve.isEnabled)
                    val before = curveEdits
                    check(!curve.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
                    check(curveEdits == before)
                }
                capture("uncalibrated-eleven-zero-knots")
                val calibrationContainer = nativeParityOnMain(instrumentation) {
                    checkNotNull(all().filterIsInstance<SettingsSilenceView>().single().calibrationPointerNode).view
                }
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
                visible(calibrationAction)
                main {
                    check(calibrationAction.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                    check(calibrationRequests == 1)
                }
                awaitNativeParityDraw(instrumentation, screen)
                val progress = nativeParityOnMain(instrumentation) { all().filterIsInstance<SettingsCalibrationProgressView>().single() }
                visible(progress)
                lateinit var progressViewport: NativeVerticalViewport
                val placement = JSONObject().put("dark", dark)
                result.put("progress_edge_pending", placement)
                main {
                    progressViewport = generateSequence(progress.parent as? View) { it.parent as? View }
                        .filterIsInstance<NativeVerticalViewport>().first()
                    val clip = Rect(); check(progressViewport.getGlobalVisibleRect(clip))
                    val position = IntArray(2); progress.getLocationOnScreen(position)
                    val delta = position[1] - (clip.bottom + 5f * progress.resources.displayMetrics.density)
                    placement.put("before", currentOnboardingProgressGeometry(progress, progressViewport))
                    check(!progressViewport.state.isScrollInProgress && !progressViewport.isLayoutRequested &&
                        !progress.isLayoutRequested) { "Progress placement requires the completed scroll/layout owner" }
                    val consumed = progressViewport.state.scrollBy(delta)
                    placement.put("requested_delta", delta.toDouble()).put("consumed_delta", consumed.toDouble())
                        .put("immediate", currentOnboardingProgressGeometry(progress, progressViewport))
                    check(kotlin.math.abs(consumed - delta) < 1.5f) {
                        "Fixture cannot place the progress semantics-only edge inside the actual scroll range"
                    }
                }
                awaitNativeParityDraw(instrumentation, screen)
                val progressEdge = nativeParityOnMain(instrumentation) {
                    val clip = Rect(); check(progressViewport.getGlobalVisibleRect(clip))
                    val position = IntArray(2); progress.getLocationOnScreen(position)
                    val semantic = progress.semanticsBoundsInView()
                    placement.put("after_draw", currentOnboardingProgressGeometry(progress, progressViewport))
                    check(position[1] >= clip.bottom && position[1] + semantic.top < clip.bottom)
                    val node = checkNotNull(progress.createAccessibilityNodeInfo())
                    try {
                        val bounds = Rect(); node.getBoundsInScreen(bounds)
                        check(node.isVisibleToUser && bounds.height() > 0 && bounds.bottom <= clip.bottom)
                        JSONObject().put("visual_top", position[1]).put("viewport_bottom", clip.bottom)
                            .put("semantic_local_top", semantic.top).put("visible_to_user", node.isVisibleToUser)
                            .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                            .put("placement", "actual viewport scrollBy; only expanded semantic padding intersects viewport")
                    } finally { @Suppress("DEPRECATION") node.recycle() }
                }
                capture("calibration-progress-semantic-edge")
                main { finishCalibration() }
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
                    check(calibrationAction.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                    check(calibrationRequests == 2 && checkNotNull(state.silenceState).calibrating)
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
                    check(slider.isAttachedToWindow) { "Retained-control regression requires the actual attached offscreen slider" }
                    val focused = slider.hasFocus()
                    screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                    screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
                    slider.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 1f) })
                    curve.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
                    calibrationAction.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                    check(state.compressionLevel == oldLevel && levels == oldLevels)
                    check(curveEdits == oldCurve && calibrationRequests == oldCalibration)
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
                    currentOnboardingViews(restoredCalibrationContainer).filterIsInstance<ReverbParagraphView>().first { target ->
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
                    .put("curve_edit_callbacks", curveEdits).put("calibrated_points", calibratedPoints).put("progress_semantic_edge", progressEdge).put("focus_setup", focusSetup).put("offscreen_actions", offscreen).put("reentry_text_actions", restoredTextActions).put("preferences_unchanged", true))
                publish()
            } finally {
                nativeParityOnMain(instrumentation) {
                    try { view?.close() } finally { try { runtime.close() } finally { host.finish() } }
                }
            }
        }
        check(cases.length() == 2)
        result.put("passed", true)
    } catch (error: Throwable) { failure = error; result.put("failure", error.stackTraceToString()) }
    finally { publish(); report.appendLine("ONBOARDING_CONTROLS_EVIDENCE ${directory.absolutePath}") }
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

private fun currentOnboardingProgressGeometry(
    progress: SettingsCalibrationProgressView,
    viewport: NativeVerticalViewport,
): JSONObject {
    val clip = Rect()
    val hasClip = viewport.getGlobalVisibleRect(clip)
    val position = IntArray(2); progress.getLocationOnScreen(position)
    val semantic = progress.semanticsBoundsInView()
    return JSONObject().put("has_viewport_clip", hasClip)
        .put("visual_top", position[1]).put("visual_bottom", position[1] + progress.height)
        .put("viewport_top", clip.top).put("viewport_bottom", clip.bottom)
        .put("semantic_local_top", semantic.top.toDouble()).put("semantic_local_bottom", semantic.bottom.toDouble())
        .put("scroll", viewport.state.value).put("max_scroll", viewport.state.maxValue)
        .put("viewport_size", viewport.state.viewportSize).put("content_size", viewport.state.contentSize)
        .put("scroll_in_progress", viewport.state.isScrollInProgress)
        .put("viewport_layout_requested", viewport.isLayoutRequested)
        .put("progress_layout_requested", progress.isLayoutRequested)
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
