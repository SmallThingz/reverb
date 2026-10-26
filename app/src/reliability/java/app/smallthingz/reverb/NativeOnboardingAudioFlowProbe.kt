package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
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
 * Restored two-switch onboarding flow. All state belongs to this isolated fixture. Synthetic
 * permission and Finish callbacks exercise real renderers/actions, never a microphone, production
 * owner, permission launcher, or preference write. Captures are not released pixel comparisons.
 */
internal fun verifyNativeOnboardingAudioFlow(instrumentation: Instrumentation, report: StringBuilder) {
    check(instrumentation.targetContext.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    val directory = File(instrumentation.targetContext.filesDir, "native-onboarding-audio-flow-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val pointerAttempts = JSONArray()
    val scrollAttempts = JSONArray()
    val expected = listOf("permission-missing", "permission-granted", "buffers-default", "audio-configured-off",
        "toggles-on", "toggles-off", "direct-finish", "audio-configured-on", "retained-off", "retirement")
    val result = JSONObject().put("selector", "native_onboarding_audio_flow")
        .put("qualification", "restored two-switch native onboarding fixture; no released pixel comparison")
        .put("callbacks", "SYNTHETIC permission and Finish callbacks; no production owner or audio capture")
        .put("dispatch", "own-root MotionEvents and actual View semantic actions")
        .put("accessibility_connection", "public FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES")
        .put("expected_cases_per_palette", JSONArray(expected)).put("expected_case_count", expected.size * 2)
        .put("cases", cases).put("passed", false)
        .put("pointer_attempts", pointerAttempts).put("scroll_attempts", scrollAttempts)
        .put("unqualified", JSONArray(listOf("audio capture", "production owner Finish transaction",
            "system permission prompts/automatic sequence", "TalkBack delivery", "physical keyboard delivery",
            "released pixel parity", "switch animation timing", "production preference persistence")))
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
            // The intended destination is owned by this scenario, not sampled mid-transition.
            var intendedPage = 0
            fun current() = checkNotNull(fixture)
            fun main(action: () -> Unit) {
                requireNativeParityHost(instrumentation, activity)
                nativeParityOnMain(instrumentation, action)
            }
            fun settle() {
                awaitOnboardingScreenSettled(instrumentation, activity, current().render,
                    intendedPage)
                // Observe actual lifetime/geometry/scroll completion, not a refresh-rate-dependent
                // number of frames. No private animation clock or duration scale is changed.
                var consecutive = 0
                repeat(180) {
                    requireNativeParityHost(instrumentation, activity)
                    awaitNativeParityDraw(instrumentation, current().view)
                    val ready = nativeParityOnMain(instrumentation) {
                        val f = current()
                        val viewport = f.view.saveViewportState()
                        val pageReady = viewport.page == intendedPage && viewport.offsetFraction == 0f &&
                            f.settledPages.lastOrNull() == intendedPage
                        val descendants = onboardingViewDescendants(f.view)
                        val scrollIdle = descendants.filterIsInstance<NativeVerticalViewport>()
                            .all { !it.state.isScrollInProgress }
                        val audioReady = intendedPage != 2 || (
                            descendants.filterIsInstance<OnboardingAudioRow>().size == 2 &&
                            f.compression().isEnabled && f.silence().isEnabled)
                        pageReady && !f.view.isLayoutRequested && scrollIdle && audioReady
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
                        val viewport = f.view.saveViewportState()
                        check(viewport.page == intendedPage && viewport.offsetFraction == 0f)
                        JSONObject().put("palette", paletteName).put("case", name).put("passed", true)
                            .put("intended_page", intendedPage).put("observed_page", viewport.page)
                            .put("state", f.evidence()).put("geometry", onboardingGeometryEvidence(f.view, false))
                            .put("nodes", nodes)
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
                        val geometry = onboardingAudioQaVisibleGeometry(current().view, v)
                        scrollAttempts.put(geometry.evidence().put("palette", paletteName)
                            .put("intended_page", intendedPage).put("target_class", v.javaClass.name))
                        geometry.usable
                    }
                    if (visible) return
                    main {
                        val v = target()
                        // The native viewport's existing responder maps this precise descendant
                        // rectangle. Page-sized scroll actions can overshoot an individual control.
                        check(v.requestRectangleOnScreen(Rect(0, 0, v.width, v.height), false)) {
                            "Target's existing bring-into-view path rejected the rectangle"
                        }
                    }
                    settle()
                }
                // Credit the eighth admitted request if it completed; do not issue a ninth.
                if (nativeParityOnMain(instrumentation) {
                    onboardingAudioQaVisibleGeometry(current().view, target()).usable
                }) return
                error("Target did not reach its usable page clip in eight actual bring-into-view requests")
            }
            fun click(view: () -> View) {
                scrollTo(view)
                main {
                    val target = view()
                    check(target.isAttachedToWindow && target.isShown && target.isEnabled)
                    check(target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                }
            }
            fun continueTo(page: Int) {
                check(page == intendedPage + 1 && page in 1..2)
                main {
                    val viewport = current().view.saveViewportState()
                    check(viewport.page == intendedPage && viewport.offsetFraction == 0f)
                }
                click { footer(R.string.onboarding_continue) }
                intendedPage = page
                settle()
            }

            main {
                fixture = OnboardingAudioQaFixture(activity, dark, configuredOn = false, page = intendedPage)
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
                onboardingAudioQaTap(current().view, permission, x, y, pointerAttempts)
            }
            settle()
            check(nativeParityOnMain(instrumentation) { current().permissionRequests == 1 && current().state.permissions.microphoneAllowed })
            capture("permission-granted")
            continueTo(1)
            main { check(current().state.oneShotEnabled && current().state.loopingEnabled) }
            capture("buffers-default")
            continueTo(2)
            main {
                check(!current().state.outputCompression && !current().state.skipSilence)
                check(onboardingViewDescendants(current().view).filterIsInstance<OnboardingAudioRow>().size == 2)
                check(footer(R.string.onboarding_get_started).isEnabled)
                check(current().settledPages.last() == 2 && current().finishRequests == 0)
            }
            capture("audio-configured-off")
            click { current().compression() }
            click { current().silence() }
            main { check(current().state.outputCompression && current().state.skipSilence) }
            capture("toggles-on")
            click { current().compression() }
            click { current().silence() }
            main { check(!current().state.outputCompression && !current().state.skipSilence) }
            capture("toggles-off")
            click { current().compression() }
            click { current().silence() }
            settle()
            main {
                check(current().state.outputCompression && current().state.skipSilence)
                check(current().compressionEdits == 3 && current().silenceEdits == 3)
                check(current().finishRequests == 0)
            }
            click { footer(R.string.onboarding_get_started) }
            main { check(current().finishRequests == 1) }
            capture("direct-finish")
            val retired = current()
            val oldCompression = nativeParityOnMain(instrumentation) { retired.compression() }
            val oldSilence = nativeParityOnMain(instrumentation) { retired.silence() }
            val oldFinish = nativeParityOnMain(instrumentation) { retired.footer(activity.getString(R.string.onboarding_get_started)) }
            main {
                retired.close()
                check(retired.settledPages.last() == -1) { "Closed screen did not retire settled-page publication" }
                activity.fixtureRoot.removeAllViews()
                fixture = OnboardingAudioQaFixture(activity, dark, configuredOn = true, page = intendedPage)
                activity.fixtureRoot.addView(current().view, FrameLayout.LayoutParams(-1, -1))
            }
            main { check(current().state.outputCompression && current().state.skipSilence) }
            capture("audio-configured-on")
            main {
                current().close()
                activity.fixtureRoot.removeAllViews()
                fixture = OnboardingAudioQaFixture(activity, dark, configuredOn = true,
                    restoreOff = true, page = intendedPage)
                activity.fixtureRoot.addView(current().view, FrameLayout.LayoutParams(-1, -1))
            }
            settle()
            main {
                check(!current().state.outputCompression && !current().state.skipSilence)
                check(footer(R.string.onboarding_get_started).isEnabled)
                check(current().finishRequests == 0)
            }
            capture("retained-off")
            main {
                val before = retired.evidence().toString()
                oldCompression.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                oldSilence.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                oldFinish.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
                check(retired.evidence().toString() == before) { "Retired control delivered a stale callback" }
                check(!oldCompression.isAttachedToWindow && !oldSilence.isAttachedToWindow && !oldFinish.isAttachedToWindow)
            }
            check(seen.add("retirement"))
            cases.put(JSONObject().put("palette", paletteName).put("case", "retirement").put("passed", true)
                .put("scope", "actual disposed switch and footer semantic callbacks; no model callback guard"))
            check(seen == expected.toSet()) { "Missing native onboarding case: ${expected.toSet() - seen}" }
            main { current().close(); activity.fixtureRoot.removeAllViews(); fixture = null }
        }
        check(cases.length() == expected.size * 2)
        check((0 until cases.length()).all { cases.getJSONObject(it).getBoolean("passed") })
        result.put("passed", true)
        report.appendLine("PASS native_onboarding_audio_flow cases=${cases.length()}; permission/Finish callbacks are synthetic, no production or pixel-parity claim")
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            nativeParityOnMain(instrumentation) {
                val owned = fixture
                fixture = null
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
        report.appendLine("Onboarding audio flow evidence: ${directory.absolutePath}")
        if (cleanupFailedAfterPass) throw checkNotNull(failure)
    }
}

private class OnboardingAudioQaFixture(
    host: NativeParityActivity,
    dark: Boolean,
    private val configuredOn: Boolean,
    private val restoreOff: Boolean = false,
    page: Int = 0,
) : AutoCloseable {
    private val runtime = NativeAboutWindowRuntime().apply { setStarted(true) }
    val view: NativeOnboardingScreenView
    val render: ScreenParityRender
    var permissionRequests = 0
    var compressionEdits = 0
    var silenceEdits = 0
    var finishRequests = 0
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
        val audio = OnboardingAudioSelection(configuredOn, configuredOn, 5, SilenceGateConfig(enabled = configuredOn))
        val configuration = OnboardingConfiguration(ConfiguredBufferAvailability(true, true), audio, true, true)
        val saved = if (restoreOff) NativeOnboardingDraft.restore(configuration.copy(audio = audio.copy(
            outputCompressionEnabled = false, skipSilenceEnabled = false,
            silenceConfig = SilenceGateConfig(enabled = false))), null).save() else null
        val draft = NativeOnboardingDraft.restore(configuration, saved)
        state = OnboardingPageContentState(
            OnboardingPermissionStatus(false, false, true, false, true, true, true, true, true),
            draft.oneShotEnabled, draft.loopingEnabled, draft.outputCompression, draft.skipSilence, true, true, true, true)
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
                    compressionChanged = {
                        compressionEdits++; events += "compression:$it"; update(state.copy(outputCompression = it))
                    },
                    skipSilenceChanged = {
                        silenceEdits++; events += "silence:$it"; update(state.copy(skipSilence = it))
                    },
                    pageChanged = { settledPages += it; events += "page:$it" },
                ), {
                    finishRequests++; events += "synthetic-finish"
                }, runtime.scope, host.onBackPressedDispatcher, NativeOnboardingViewportSavedState(page = page))
            render = ScreenParityRender(view, false, view::saveViewportState, ::close)
        } catch (error: Throwable) {
            captureNativeParityCleanup(error) { runtime.close() }
            throw error
        }
    }
    private fun update(next: OnboardingPageContentState) { state = next; view.bind(next) }
    fun compression() = onboardingAudioQaRow(view, view.context.getString(R.string.output_compression)) as OnboardingAudioRow
    fun silence() = onboardingAudioQaRow(view, view.context.getString(R.string.silence_title)) as OnboardingAudioRow
    fun footer(label: String) = onboardingAudioQaRow(
        onboardingViewDescendants(view).filterIsInstance<OnboardingFooterView>().single(), label)
    fun evidence(): JSONObject = JSONObject().put("output_compression", state.outputCompression)
        .put("skip_silence", state.skipSilence).put("configured_on", configuredOn).put("restored_off", restoreOff)
        .put("initial_selection_source", "actual NativeOnboardingDraft.restore; synthetic configuration and saved draft")
        .put("permission_requests", permissionRequests).put("finish_requests", finishRequests)
        .put("compression_edits", compressionEdits).put("silence_edits", silenceEdits).put("events", JSONArray(events))
        .put("settled_pages", JSONArray(settledPages))
    private var closed = false
    override fun close() {
        if (closed) return
        closed = true
        var failure = captureNativeParityCleanup(null) { view.close() }
        failure = captureNativeParityCleanup(failure) { runtime.close() }
        failure?.let { throw it }
    }
}

private fun onboardingAudioQaRow(root: View, label: String): View =
    onboardingViewDescendants(root).filter { candidate ->
        candidate is NativeMergedRowAccessibilityHost && onboardingViewDescendants(candidate)
            .filterIsInstance<ReverbParagraphView>().any { it.getText() == label }
    }.single()

private data class OnboardingAudioQaVisibleGeometry(
    val target: Rect,
    val clip: Rect,
    val visible: Rect,
    val viewportCount: Int,
    val usable: Boolean,
) {
    fun evidence(): JSONObject = JSONObject()
        .put("target_bounds", JSONArray(listOf(target.left, target.top, target.right, target.bottom)))
        .put("page_clip", JSONArray(listOf(clip.left, clip.top, clip.right, clip.bottom)))
        .put("visible_bounds", JSONArray(listOf(visible.left, visible.top, visible.right, visible.bottom)))
        .put("viewport_count", viewportCount).put("usable", usable)
        .put("oversized", target.width() > clip.width() || target.height() > clip.height())
}

/** getGlobalVisibleRect does not account for clipToOutline on a clipChildren=false viewport. */
private fun onboardingAudioQaVisibleGeometry(root: View, target: View): OnboardingAudioQaVisibleGeometry {
    check(root.isAttachedToWindow && target.isAttachedToWindow && root.isShown && target.isShown)
    val location = IntArray(2)
    target.getLocationOnScreen(location)
    val bounds = Rect(location[0], location[1], location[0] + target.width, location[1] + target.height)
    val clip = Rect()
    check(root.getGlobalVisibleRect(clip))
    var ancestor: View? = target
    var viewportCount = 0
    while (ancestor !== root) {
        val current = checkNotNull(ancestor) { "Target does not belong to the current fixture" }
        if (current is NativeVerticalViewport) {
            current.getLocationOnScreen(location)
            val viewport = Rect(location[0], location[1], location[0] + current.width, location[1] + current.height)
            if (!clip.intersect(viewport)) clip.setEmpty()
            viewportCount++
        }
        ancestor = current.parent as? View
    }
    val visible = Rect()
    if (!target.getGlobalVisibleRect(visible) || !visible.intersect(clip)) visible.setEmpty()
    // Fitting controls must be wholly visible. A control larger than the current viewport must
    // span the available clipped axis; partial visibility is recorded, never called full visibility.
    val usable = bounds.width() > 0 && bounds.height() > 0 && !clip.isEmpty && !visible.isEmpty &&
        visible.width() >= minOf(bounds.width(), clip.width()) &&
        visible.height() >= minOf(bounds.height(), clip.height())
    return OnboardingAudioQaVisibleGeometry(bounds, clip, visible, viewportCount, usable)
}

/** Fresh own-target/page clip admission; same screen/raw-to-local contract as Settings fixtures. */
private fun onboardingAudioQaTap(root: View, target: View, screenX: Float, screenY: Float, attempts: JSONArray) {
    check(root.isAttachedToWindow && root.hasWindowFocus() && root.display?.displayId == 0)
    check(target.isEnabled)
    val geometry = onboardingAudioQaVisibleGeometry(root, target)
    val origin = IntArray(2); root.getLocationOnScreen(origin)
    val x = screenX - origin[0]; val y = screenY - origin[1]
    val entry = geometry.evidence().put("target_class", target.javaClass.name)
        .put("screen_point", JSONArray(listOf(screenX.toDouble(), screenY.toDouble())))
        .put("root_origin", JSONArray(listOf(origin[0], origin[1])))
        .put("local_point", JSONArray(listOf(x.toDouble(), y.toDouble())))
        .put("events", JSONArray())
    attempts.put(entry)
    check(geometry.usable && geometry.visible.contains(screenX.toInt(), screenY.toInt()) &&
        geometry.clip.contains(screenX.toInt(), screenY.toInt())) {
        "Touch target/point is outside its actual current page clip: $entry"
    }
    check(x >= 0 && x < root.width && y >= 0 && y < root.height)
    val down = SystemClock.uptimeMillis()
    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, screenX, screenY, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
        try {
            val accepted = root.dispatchTouchEvent(event)
            entry.getJSONArray("events").put(JSONObject().put("action", action).put("accepted", accepted)
                .put("raw", JSONArray(listOf(event.rawX.toDouble(), event.rawY.toDouble())))
                .put("local", JSONArray(listOf(event.x.toDouble(), event.y.toDouble()))))
            check(accepted) { "Own-root pointer action $action was rejected: $entry" }
        }
        finally { event.recycle() }
    }
}
