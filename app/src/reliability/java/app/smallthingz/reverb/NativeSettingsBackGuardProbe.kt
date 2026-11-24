package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.activity.BackEventCompat
import app.smallthingz.reverb.nativeui.NativeSettingsScreenContent
import app.smallthingz.reverb.nativeui.ReverbIncidentLoadingView
import app.smallthingz.reverb.nativeui.ReverbHomeScaffoldView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.reverb.settings.ReverbSettingsBackgroundWarningView
import smallthingz.ui.scroll.NativeSourceRecyclerView
import smallthingz.ui.text.view.ParagraphView

/** Current requested behavior, deliberately not compared with the unchanged released oracle. */
internal fun verifyNativeSettingsBackGuard(
    instrumentation: Instrumentation,
    report: StringBuilder,
    hydrationOnly: Boolean = false,
) {
    val context = instrumentation.targetContext
    check(context.packageName == if (hydrationOnly) "app.smallthingz.reverb.uitheme.reliability"
        else "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val preferences = getRecorderPreferences(context)
    val original = preferences.all.toMap()
    val prefix = if (hydrationOnly) "native-settings-hydration-current" else "native-settings-back-guard"
    val directory = File(context.filesDir, "$prefix-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val evidence = JSONObject().put("passed", false).put("cases", cases)
        .put("scope", "Current native Settings owner and attached Views; no released-parity claim")
        .put("unqualified", JSONArray(listOf("OS predictive gesture", "physical keyboard", "TalkBack delivery", "production capture")))
    if (hydrationOnly) evidence.put("selector", "native_settings_hydration_current")
        .put("historical_parity_claim", false)
        .put("visual_review_required", "Original hardware toolbar/card pixels; functional PASS is not visual closure")
        .put("unqualified", JSONArray(listOf("physical touch", "TalkBack delivery", "OS picker launch", "settled-idle folder tap")))
    fun publish() = writeQaEvidenceAtomically(File(directory, "result.json")) { stream ->
        stream.write(evidence.toString(2).toByteArray(Charsets.UTF_8))
    }
    publish()
    var failure: Throwable? = null
    try {
        for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
            val hydrationEvidence = if (hydrationOnly) JSONObject().put("theme", mode.name)
                .put("case_passed", false).put("stage", "opening") else null
            hydrationEvidence?.let { cases.put(it); publish() }
            val host = if (hydrationOnly) launchCurrentUiSmokeActivity(instrumentation)
                else launchNativeParityActivity(instrumentation)
            var render: MainSettingsParityRender? = null
            var pendingHint: Bitmap? = null
            val releaseHydration = CompletableDeferred<Unit>()
            var hydrationBarrier: Job? = null
            var passFailure: Throwable? = null
            try {
                val current = nativeParityOnMain(instrumentation) {
                    MainSettingsParityRender(host, false, mode, mode == AppThemeMode.DARK, null)
                }.also { render = it }
                fun main(action: () -> Unit) {
                    if (hydrationOnly) requireNativeParityHost(instrumentation, host, phase = "settings-hydration-action")
                    nativeParityOnMain(instrumentation, action)
                }
                fun await(label: String, condition: () -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 5_000L
                    while (!nativeParityOnMain(instrumentation, condition)) {
                        check(SystemClock.uptimeMillis() < deadline) { "Timed out: $label" }
                        awaitNativeParityDraw(instrumentation, current.board)
                    }
                }
                fun capture(label: String) {
                    requireNativeParityHost(instrumentation, host)
                    val bitmap = captureNativeParityContent(instrumentation, host) { current.board }
                    try { saveNativeParityBitmap(directory, "${mode.name}-$label.png", bitmap) }
                    finally { bitmap.recycle() }
                }
                lateinit var settings: NativeSettingsScreenContent
                fun saveHydrationCapture(label: String, bitmap: Bitmap): JSONObject {
                    val name = "${mode.name}-$label.png"
                    try { saveNativeParityBitmap(directory, name, bitmap) }
                    finally { bitmap.recycle() }
                    val digest = File(directory, name).inputStream().use { sha256(it) }
                    check(digest.byteCount > 0L)
                    return JSONObject().put("png", name).put("bytes", digest.byteCount)
                        .put("sha256", digest.sha256.joinToString("") { "%02x".format(it.toInt() and 0xff) })
                }
                fun captureHydration(label: String): JSONObject {
                    await("Settings fully revealed for $label") {
                        val home = backGuardViews(current.board).filterIsInstance<ReverbHomeScaffoldView>().single()
                        val parentHeight = (home.parent as View).height
                        parentHeight > 0 && home.translationY >= parentHeight.toFloat()
                    }
                    val diagnostic = JSONObject().put("kind", "original_hardware_two_draw_boundaries")
                    checkNotNull(hydrationEvidence).put("${label}_capture", diagnostic)
                    publish()
                    val bitmap = captureHydrationHardware(instrumentation, host, current.board,
                        settings, diagnostic, later = false, publish = ::publish)
                    return saveHydrationCapture(label, bitmap)
                }
                fun captureRestoredSupplements() {
                    val hydration = checkNotNull(hydrationEvidence)
                    val later = JSONObject().put("kind", "later_hardware_observation_not_guaranteed_new_buffer")
                    hydration.put("restored_later_hardware", later)
                    publish()
                    val hardware = captureHydrationHardware(instrumentation, host, current.board,
                        settings, later, later = true, publish = ::publish)
                    later.put("screenshot", saveHydrationCapture("restored-later-hardware", hardware))
                    publish()
                    // Software can finish overscroll effects and alter caches: it must be LAST.
                    val software = JSONObject().put("kind", "supplemental_software_may_mutate_render_state")
                    hydration.put("restored_software", software)
                    requireNativeParityHost(instrumentation, host, phase = "hydration-before-software")
                    val bitmap = nativeParityOnMain(instrumentation) {
                        software.put("before_draw", hydrationGeometry(current.board, settings))
                        val image = Bitmap.createBitmap(current.board.width, current.board.height, Bitmap.Config.ARGB_8888)
                        try {
                            current.board.draw(Canvas(image))
                            software.put("after_draw", hydrationGeometry(current.board, settings))
                            image
                        } catch (error: Throwable) { image.recycle(); throw error }
                    }
                    software.put("screenshot", saveHydrationCapture("restored-software", bitmap))
                    publish()
                }
                await("Settings action") { current.actionCount(host.getString(R.string.open_settings)) == 1 }
                main { current.click(host.getString(R.string.open_settings)) }
                await("Settings hydration") {
                    val found = backGuardViews(current.board).filterIsInstance<NativeSettingsScreenContent>()
                        .firstOrNull { it.inputAllowed }
                    if (found != null) settings = found
                    found != null
                }
                val baseline = nativeParityOnMain(instrumentation) { settings.owner.snapshot }
                if (hydrationOnly) {
                    val hydration = checkNotNull(hydrationEvidence)
                    val retainedList = nativeParityOnMain(instrumentation) { checkNotNull(settings.sectionContent) }
                    val retainedPosition = nativeParityOnMain(instrumentation) { settings.savePosition() }
                    main { host.onBackPressedDispatcher.onBackPressed() }
                    await("clean Settings closes") { !settings.owner.state.active }
                    main {
                        hydrationBarrier = settings.owner.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            processSettingsCommitBarrier.runAcceptedSave { releaseHydration.await() }
                        }
                    }
                    await("Settings action after close") { current.actionCount(host.getString(R.string.open_settings)) == 1 }
                    main { current.click(host.getString(R.string.open_settings)) }
                    await("retained Settings hydration pending") {
                        settings.owner.state.active && settings.owner.state.hydrationPending &&
                            backGuardViews(settings).filterIsInstance<ReverbIncidentLoadingView>().any { it.isLaidOut }
                    }
                    fun recordHydrationState(stage: String) {
                        hydration.put("stage", stage)
                        nativeParityOnMain(instrumentation) {
                            val observed = JSONObject().put("active", settings.owner.state.active)
                                .put("hydrated", settings.owner.state.hydrated)
                                .put("hydration_pending", settings.owner.state.hydrationPending)
                                .put("input_allowed", settings.inputAllowed)
                                .put("retained_list", settings.sectionContent === retainedList)
                                .put("retained_scroll", settings.savePosition() == retainedPosition)
                                .put("snapshot_unchanged", settings.owner.snapshot == baseline)
                                .put("settings_alpha", settings.alpha)
                                .put("content_alpha", retainedList.alpha)
                                .put("top_bar_alpha", settings.topBar.alpha)
                                .put("toolbar_parent_is_settings", settings.topBar.parent === settings)
                                .put("list_parent_is_settings", retainedList.parent === settings)
                                .put("settings_importance", settings.importantForAccessibility)
                                .put("list_importance", retainedList.importantForAccessibility)
                                .put("toolbar_importance", settings.topBar.importantForAccessibility)
                            hydration.put(stage, observed)
                            fun nodeEvidence(view: View): JSONObject {
                                val entry = JSONObject().put("attached", view.isAttachedToWindow)
                                    .put("shown", view.isShown).put("width", view.width).put("height", view.height)
                                    .put("alpha", view.alpha).put("importance", view.importantForAccessibility)
                                val node = view.createAccessibilityNodeInfo()
                                try {
                                    entry.put("node_present", node != null)
                                    if (node != null) entry.put("class", node.className?.toString())
                                        .put("visible", node.isVisibleToUser).put("enabled", node.isEnabled)
                                        .put("screen_reader_focusable", node.isScreenReaderFocusable)
                                        .put("state_description", node.stateDescription?.toString())
                                } finally { @Suppress("DEPRECATION") node?.recycle() }
                                return entry
                            }
                            val actions = JSONArray()
                            observed.put("toolbar_actions", actions)
                            for (view in listOf(settings.topBar.navigation.visual, settings.topBar.done.visual)) {
                                actions.put(nodeEvidence(view))
                            }
                            val loaders = JSONArray()
                            observed.put("loaders", loaders)
                            backGuardViews(settings).filterIsInstance<ReverbIncidentLoadingView>()
                                .forEach { loaders.put(nodeEvidence(it)) }
                        }
                        publish()
                    }
                    recordHydrationState("pending")
                    hydration.put("pending_screenshot", captureHydration("pending"))
                    publish()
                    main {
                        check(!settings.inputAllowed && settings.owner.state.hydrated)
                        check(settings.sectionContent === retainedList && settings.savePosition() == retainedPosition)
                        // The stable toolbar stayed opaque when 7d6348f9 moved its binding
                        // before hydration. Only retained editable content receives the fade.
                        check(retainedList.alpha == .38f && settings.topBar.alpha == 1f && settings.alpha == 1f) {
                            "Unexpected pending opacity: ${hydration.getJSONObject("pending")}"
                        }
                        check(settings.topBar.parent === settings && retainedList.parent === settings)
                        check(settings.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO)
                        check(retainedList.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                        check(settings.topBar.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                        for (view in listOf(settings.topBar.navigation.visual, settings.topBar.done.visual)) {
                            val node = view.createAccessibilityNodeInfo()
                            try { check(node == null || !node.isVisibleToUser) }
                            finally { @Suppress("DEPRECATION") node?.recycle() }
                        }
                        val loader = backGuardViews(settings).filterIsInstance<ReverbIncidentLoadingView>().single()
                        check(loader.isAttachedToWindow && loader.isShown && loader.width > 0 && loader.height > 0)
                        val node = checkNotNull(loader.createAccessibilityNodeInfo())
                        try {
                            check(node.className == "android.widget.ProgressBar" && node.isVisibleToUser)
                            check(node.isScreenReaderFocusable && !node.stateDescription.isNullOrBlank())
                        } finally { @Suppress("DEPRECATION") node.recycle() }
                        hydration.put("retained_list", true).put("retained_scroll", true)
                            .put("suspension", "process_settings_commit_barrier").put("input_blocked", true)
                            .put("content_alpha", retainedList.alpha).put("top_bar_alpha", settings.topBar.alpha)
                            .put("loading_status_accessible", true).put("loader_attached", true)
                            .put("list_children_hidden", true).put("toolbar_actions_hidden", true)
                    }
                    main { check(settings.owner.state.hydrationPending && !settings.inputAllowed) }
                    // The loading affordance does not acquire Back ownership: clean pending Settings can leave.
                    hydration.put("stage", "back_while_pending")
                    publish()
                    main { host.onBackPressedDispatcher.onBackPressed() }
                    await("Back closes pending Settings") {
                        !settings.owner.state.active &&
                            backGuardViews(settings).none { it is ReverbIncidentLoadingView }
                    }
                    recordHydrationState("closed")
                    main { check(retainedList.alpha == 1f && settings.topBar.alpha == 1f) }
                    await("Settings action after pending close") { current.actionCount(host.getString(R.string.open_settings)) == 1 }
                    main { current.click(host.getString(R.string.open_settings)) }
                    await("second retained hydration pending") { settings.owner.state.hydrationPending }
                    main { releaseHydration.complete(Unit) }
                    await("released hydration restores input") { settings.inputAllowed && hydrationBarrier?.isCompleted == true }
                    recordHydrationState("restored")
                    hydration.put("restored_screenshot", captureHydration("restored"))
                    publish()
                    main {
                        check(settings.sectionContent === retainedList && settings.savePosition() == retainedPosition)
                        check(retainedList.alpha == 1f && settings.topBar.alpha == 1f)
                        check(backGuardViews(settings).none { it is ReverbIncidentLoadingView })
                        check(settings.owner.snapshot == baseline)
                        hydration.put("restored_content_alpha", retainedList.alpha)
                            .put("restored_top_bar_alpha", settings.topBar.alpha)
                    }
                    hydration.put("native_back_closed_pending", true).put("released_read_restored_input", true)
                        .put("restored_loader_absent", true).put("restored_snapshot_unchanged", true)
                    main { check(settings.inputAllowed && !settings.owner.state.hydrationPending) }
                    check(preferences.all == original) { "Hydration changed durable QA preferences" }
                    check(current.forbiddenCalls == 0)
                    captureRestoredSupplements()
                    hydration.put("case_passed", true).put("stage", "complete")
                        .put("durable_preferences_unchanged", true)
                    publish()
                    continue
                }
                main {
                    val owner = settings.owner
                    owner.outputCompressionChanged(owner.state.activation, true)
                    owner.compressionLevelChanged(owner.state.activation, (baseline.flacCompression + 1) % 9)
                    check(owner.state.hasUnsavedChanges)
                }
                val draft = nativeParityOnMain(instrumentation) { settings.owner.snapshot }
                val revision = nativeParityOnMain(instrumentation) { settings.owner.state.editRevision }
                capture("dirty-before-back")
                // Dispatch through the installed activity dispatcher, not directly into an owner method.
                main {
                    host.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
                    host.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, .5f, BackEventCompat.EDGE_LEFT))
                    host.onBackPressedDispatcher.dispatchOnBackCancelled()
                    check(settings.owner.snapshot == draft && settings.owner.state.editRevision == revision)
                    check(settings.owner.backHint == null)
                }
                val started = SystemClock.uptimeMillis()
                main { host.onBackPressedDispatcher.onBackPressed() }
                await("first Back hint painted") {
                    settings.owner.backHint != null &&
                        (settings.topBar.navigation.visual.background as ColorDrawable).alpha > 0 &&
                        (settings.topBar.done.visual.background as ColorDrawable).alpha > 0
                }
                val first = nativeParityOnMain(instrumentation) {
                    check(settings.inputAllowed && settings.owner.snapshot == draft)
                    check(settings.owner.state.editRevision == revision && settings.owner.state.hasUnsavedChanges)
                    JSONObject().put("theme", mode.name).put("hint_observed_after_ms", SystemClock.uptimeMillis() - started)
                        .put("navigation_alpha", (settings.topBar.navigation.visual.background as ColorDrawable).alpha)
                        .put("done_alpha", (settings.topBar.done.visual.background as ColorDrawable).alpha)
                        .put("edit_revision_unchanged", true)
                }
                cases.put(first.put("case_passed", false))
                val captureStarted = nativeParityOnMain(instrumentation) {
                    check((settings.topBar.navigation.visual.background as ColorDrawable).alpha > 0)
                    check((settings.topBar.done.visual.background as ColorDrawable).alpha > 0)
                    SystemClock.uptimeMillis()
                }
                pendingHint = captureNativeParityContent(instrumentation, host) { current.board }
                nativeParityOnMain(instrumentation) {
                    val navigation = (settings.topBar.navigation.visual.background as ColorDrawable).alpha
                    val done = (settings.topBar.done.visual.background as ColorDrawable).alpha
                    check(navigation > 0 && done > 0) { "Pulse expired before captured frame; no flash evidence claimed" }
                    first.put("capture_started_uptime_ms", captureStarted)
                        .put("capture_finished_uptime_ms", SystemClock.uptimeMillis())
                        .put("navigation_alpha_after_capture", navigation).put("done_alpha_after_capture", done)
                }
                // Each icon retains its accessible click target during the visual cue.
                main {
                    for (view in listOf(settings.topBar.navigation.visual, settings.topBar.done.visual)) {
                        val node = checkNotNull(view.createAccessibilityNodeInfo())
                        try { check(node.isEnabled && node.isClickable) }
                        finally { @Suppress("DEPRECATION") node.recycle() }
                    }
                }
                main {
                    check(SystemClock.uptimeMillis() - started < 2_000L) { "Missed the actual confirmation window; do not manufacture a double Back" }
                    first.put("second_back_elapsed_ms", SystemClock.uptimeMillis() - started)
                    host.onBackPressedDispatcher.onBackPressed()
                }
                await("second Back restores and closes") {
                    !settings.inputAllowed && !settings.owner.state.hasUnsavedChanges && settings.owner.snapshot == baseline
                }
                // PNG encoding is outside the actual two-Back deadline.
                saveNativeParityBitmap(directory, "${mode.name}-first-back-hint.png", checkNotNull(pendingHint))
                pendingHint?.recycle(); pendingHint = null
                capture("second-back-closed")
                check(preferences.all == original) { "Back/Undo wrote durable QA preferences" }
                check(current.forbiddenCalls == 0)
                first.put("case_passed", true).put("second_back_closed", true).put("durable_preferences_unchanged", true)
                publish()
            } catch (error: Throwable) {
                passFailure = error
                hydrationEvidence?.put("failure", error.stackTraceToString())
                throw error
            }
            finally {
                releaseHydration.complete(Unit)
                var cleanup = passFailure
                pendingHint?.let { bitmap ->
                    cleanup = captureNativeParityCleanup(cleanup) {
                        try { saveNativeParityBitmap(directory, "${mode.name}-failed-hint.png", bitmap) }
                        finally { bitmap.recycle() }
                    }
                }
                cleanup = captureNativeParityCleanup(cleanup) { nativeParityOnMain(instrumentation) { render?.close() } }
                cleanup = captureNativeParityCleanup(cleanup) { nativeParityOnMain(instrumentation) { host.finish() } }
                cleanup = captureNativeParityCleanup(cleanup) { check(preferences.all == original) { "QA preferences changed during failed/complete case" } }
                if (passFailure == null) cleanup?.let { throw it }
            }
        }
        check(cases.length() == 2 && preferences.all == original)
        evidence.put("passed", true)
    } catch (error: Throwable) {
        failure = error
        evidence.put("failure", error.stackTraceToString())
    } finally {
        publish()
        report.appendLine("${if (hydrationOnly) "SETTINGS_HYDRATION_CURRENT_EVIDENCE" else "SETTINGS_BACK_GUARD_EVIDENCE"} ${directory.absolutePath}")
    }
    failure?.let { throw it }
    report.appendLine(if (hydrationOnly)
        "PASS native_settings_hydration_current retained loading and Back; actual native owner, barrier and frames"
    else "PASS native_settings_back_guard current dirty Back confirmation; synthetic dispatcher, actual native owner and frames")
}

private fun backGuardViews(root: View): Sequence<View> = sequence {
    yield(root)
    if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(backGuardViews(root.getChildAt(index)))
}

/** Same guarded window crop/PixelCopy ownership as the shared helper; scoped diagnostics only. */
private fun captureHydrationHardware(
    instrumentation: Instrumentation,
    host: NativeParityActivity,
    board: View,
    settings: NativeSettingsScreenContent,
    evidence: JSONObject,
    later: Boolean,
    publish: () -> Unit,
): Bitmap {
    requireNativeParityHost(instrumentation, host, phase = "hydration-hardware-before", beginCapture = true)
    var drawCount = 0
    var lastDrawUptime = 0L
    val drawListener = ViewTreeObserver.OnDrawListener {
        drawCount++
        lastDrawUptime = SystemClock.uptimeMillis()
    }
    val rectangle = Rect()
    var bitmap: Bitmap? = null
    val copied = CountDownLatch(1)
    var status = Int.MIN_VALUE
    var submitted = false
    var recycleOnCompletion = false
    var captureFailure: Throwable? = null
    fun releaseDestination() {
        nativeParityOnMain(instrumentation) {
            if (!submitted || copied.count == 0L) bitmap?.recycle() else recycleOnCompletion = true
        }
    }
    fun sample(key: String) {
        evidence.put(key, hydrationGeometry(board, settings)
            .put("observed_draw_count", drawCount).put("last_draw_uptime_ms", lastDrawUptime))
    }
    nativeParityOnMain(instrumentation) {
        board.viewTreeObserver.addOnDrawListener(drawListener)
    }
    try {
        nativeParityOnMain(instrumentation) { sample("before_settle") }
        publish()
        if (later) {
            // Observe time passing without invalidate/requestLayout/scroll/software draw.
            // Two callbacks do NOT prove the window submitted a new buffer.
            val advanced = CountDownLatch(1)
            val callbacks = JSONArray()
            var active = true
            lateinit var callback: Choreographer.FrameCallback
            nativeParityOnMain(instrumentation) {
                callback = Choreographer.FrameCallback { frameTime ->
                    if (active) {
                        callbacks.put(JSONObject().put("frame_time_nanos", frameTime)
                            .put("uptime_ms", SystemClock.uptimeMillis()))
                        if (callbacks.length() == 2) advanced.countDown()
                        else Choreographer.getInstance().postFrameCallback(callback)
                    }
                }
                Choreographer.getInstance().postFrameCallback(callback)
            }
            var boundaryFailure: Throwable? = null
            try { check(advanced.await(5, TimeUnit.SECONDS)) { "Hydration later-hardware callbacks timed out" } }
            catch (error: Throwable) { boundaryFailure = error; throw error }
            finally {
                try {
                    nativeParityOnMain(instrumentation) {
                        active = false
                        Choreographer.getInstance().removeFrameCallback(callback)
                        evidence.put("choreographer_callbacks", callbacks)
                    }
                } catch (cleanup: Throwable) {
                    val primary = boundaryFailure
                    if (primary == null) throw cleanup
                    if (primary !== cleanup) primary.addSuppressed(cleanup)
                }
            }
        } else {
            instrumentation.waitForIdleSync()
            awaitNativeParityDraw(instrumentation, board)
            awaitNativeParityDraw(instrumentation, board)
        }
        requireNativeParityHost(instrumentation, host, phase = "hydration-hardware-after-boundaries")
        nativeParityOnMain(instrumentation) {
            check(board.isLaidOut && !board.isLayoutRequested && board.isShown && board.isHardwareAccelerated)
            val location = IntArray(2)
            board.getLocationInWindow(location)
            rectangle.set(location[0], location[1], location[0] + board.width, location[1] + board.height)
            check(rectangle.width() > 0 && rectangle.height() > 0)
        }
        val destination = Bitmap.createBitmap(rectangle.width(), rectangle.height(), Bitmap.Config.ARGB_8888)
        bitmap = destination
        nativeParityOnMain(instrumentation) {
            sample("before_pixelcopy_request")
            evidence.put("crop_in_window", hydrationRect(rectangle))
                .put("request_uptime_ms", SystemClock.uptimeMillis())
            PixelCopy.request(host.window, rectangle, destination, { result ->
                status = result
                evidence.put("pixelcopy_status", result).put("copy_callback_uptime_ms", SystemClock.uptimeMillis())
                copied.countDown()
                if (recycleOnCompletion) destination.recycle()
            }, Handler(Looper.getMainLooper()))
            submitted = true
        }
        check(copied.await(5, TimeUnit.SECONDS)) { "Hydration PixelCopy timed out" }
        check(status == PixelCopy.SUCCESS) { "Hydration PixelCopy failed: $status" }
        requireNativeParityHost(instrumentation, host, phase = "hydration-hardware-after-pixelcopy")
        nativeParityOnMain(instrumentation) { sample("after_pixelcopy") }
        publish()
        return destination
    } catch (error: Throwable) {
        captureFailure = error
        try { releaseDestination() }
        catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
        try {
            nativeParityOnMain(instrumentation) {
                evidence.put("capture_failure", error.stackTraceToString())
            }
        } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
        throw error
    } finally {
        try {
            nativeParityOnMain(instrumentation) {
                if (board.viewTreeObserver.isAlive) board.viewTreeObserver.removeOnDrawListener(drawListener)
            }
        } catch (cleanup: Throwable) {
            val primary = captureFailure
            if (primary != null) {
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            } else {
                // The successful return has not transferred the bitmap to its caller yet.
                try { releaseDestination() }
                catch (recycleFailure: Throwable) {
                    if (recycleFailure !== cleanup) cleanup.addSuppressed(recycleFailure)
                }
                throw cleanup
            }
        }
    }
}

private fun hydrationRect(rect: Rect): JSONArray = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))

/** Read-only View facts, not an occlusion oracle or a claim about private RenderNode state. */
private fun hydrationGeometry(board: View, settings: NativeSettingsScreenContent): JSONObject {
    val targets = linkedMapOf<View, MutableList<String>>()
    fun add(role: String, view: View?) {
        if (view == null) return
        targets.getOrPut(view) { mutableListOf() }.add(role)
        var ancestor = view.parent as? View
        while (ancestor != null) {
            targets.getOrPut(ancestor) { mutableListOf("ancestor") }
            ancestor = ancestor.parent as? View
        }
    }
    fun identity(view: View?) = view?.let { "${it.javaClass.name}@${System.identityHashCode(it).toString(16)}" }
    add("capture_board", board)
    add("settings", settings)
    add("toolbar", settings.topBar)
    add("toolbar_content", settings.topBar.contentClipView)
    add("toolbar_title", settings.topBar.title)
    add("navigation_wrapper", settings.topBar.navigation)
    add("done_wrapper", settings.topBar.done)
    add("navigation_visual", settings.topBar.navigation.visual)
    add("done_visual", settings.topBar.done.visual)
    add("list_container", settings.sectionContent)
    val viewport = settings.listViewport
    add("list_viewport", viewport)
    if (viewport != null) for (index in 0 until minOf(3, viewport.childCount)) {
        add("attached_section_$index", viewport.getChildAt(index))
    }
    backGuardViews(settings).filterIsInstance<ReverbSettingsBackgroundWarningView>().forEachIndexed { index, warning ->
        add("warning_section_$index", warning)
        for (child in 0 until warning.childCount) {
            val group = warning.getChildAt(child) as? NativeGroupLayout ?: continue
            add("warning_group_$index", group)
            val content = group.pointerContent
            add("warning_content_$index", content)
            for (rowIndex in 0 until content.childCount) {
                val row = content.getChildAt(rowIndex)
                add("warning_${index}_row_$rowIndex", row)
                if (row is ViewGroup) for (childIndex in 0 until row.childCount) {
                    add("warning_${index}_row_${rowIndex}_child_$childIndex", row.getChildAt(childIndex))
                }
                backGuardViews(row).filterIsInstance<ParagraphView>().forEachIndexed { labelIndex, label ->
                    add("warning_${index}_row_${rowIndex}_label_$labelIndex", label)
                }
            }
        }
    }
    val entries = JSONArray()
    val boardLocation = IntArray(2); board.getLocationInWindow(boardLocation)
    for ((view, roles) in targets) {
        val window = IntArray(2); view.getLocationInWindow(window)
        val screen = IntArray(2); view.getLocationOnScreen(screen)
        val visible = Rect()
        val globalVisible = view.getGlobalVisibleRect(visible)
        val matrix = FloatArray(9); view.matrix.getValues(matrix)
        val outline = Outline()
        view.outlineProvider?.getOutline(view, outline)
        val outlineRect = Rect()
        val rectOutline = outline.getRect(outlineRect)
        val outlineRadius = outline.radius
        val parent = view.parent as? ViewGroup
        val entry = JSONObject().put("roles", JSONArray(roles)).put("identity", identity(view))
            .put("parent", identity(parent)).put("index_in_parent", parent?.indexOfChild(view))
            .put("bounds_in_parent", JSONArray(listOf(view.left, view.top, view.right, view.bottom)))
            .put("measured_size", JSONArray(listOf(view.measuredWidth, view.measuredHeight)))
            .put("location_in_window", JSONArray(window.toList())).put("location_on_screen", JSONArray(screen.toList()))
            .put("location_in_capture", JSONArray(listOf(window[0] - boardLocation[0], window[1] - boardLocation[1])))
            .put("translation", JSONArray(listOf(view.translationX, view.translationY, view.translationZ)))
            .put("scale", JSONArray(listOf(view.scaleX, view.scaleY))).put("matrix", JSONArray(matrix.toList()))
            .put("pivot", JSONArray(listOf(view.pivotX, view.pivotY)))
            .put("rotation", JSONArray(listOf(view.rotation, view.rotationX, view.rotationY)))
            .put("scroll", JSONArray(listOf(view.scrollX, view.scrollY))).put("alpha", view.alpha)
            .put("z", view.z).put("elevation", view.elevation).put("visibility", view.visibility)
            .put("shown", view.isShown).put("attached", view.isAttachedToWindow)
            .put("laid_out", view.isLaidOut).put("hardware_accelerated", view.isHardwareAccelerated)
            .put("layout_requested", view.isLayoutRequested).put("layer_type", view.layerType)
            .put("has_overlapping_rendering", view.hasOverlappingRendering())
            .put("padding", JSONArray(listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)))
            .put("clip_bounds", view.clipBounds?.let(::hydrationRect) ?: JSONObject.NULL)
            .put("clip_to_outline", view.clipToOutline).put("outline_provider", view.outlineProvider?.javaClass?.name)
            .put("outline_empty", outline.isEmpty).put("outline_can_clip", outline.canClip())
            .put("outline_rect", if (rectOutline) hydrationRect(outlineRect) else JSONObject.NULL)
            .put("outline_radius", if (outlineRadius.isFinite()) outlineRadius else JSONObject.NULL)
            .put("outline_radius_defined", outlineRadius.isFinite()).put("outline_radius_raw", outlineRadius.toString())
            .put("outline_alpha", outline.alpha)
            .put("global_visible_rect_nonempty", globalVisible).put("global_visible_rect_not_occlusion", hydrationRect(visible))
        if (view is ViewGroup) {
            val children = JSONArray()
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                children.put(JSONObject().put("index", index).put("identity", identity(child))
                    .put("z", child.z).put("elevation", child.elevation)
                    .put("translation", JSONArray(listOf(child.translationX, child.translationY, child.translationZ)))
                    .put("bounds_in_parent", JSONArray(listOf(child.left, child.top, child.right, child.bottom)))
                    .put("alpha", child.alpha).put("visibility", child.visibility))
            }
            entry.put("clip_children", view.clipChildren).put("clip_to_padding", view.clipToPadding)
                .put("children_in_index_order", children)
            if (Build.VERSION.SDK_INT >= 29) entry.put("drawing_order_before_z", JSONArray(
                (0 until view.childCount).map { view.getChildDrawingOrder(it) }))
        }
        if (view is NativeSourceRecyclerView) {
            val items = JSONArray()
            for (index in 0 until minOf(3, view.childCount)) {
                val child = view.getChildAt(index)
                val decorated = Rect()
                view.layoutManager?.getDecoratedBoundsWithMargins(child, decorated)
                items.put(JSONObject().put("identity", identity(child)).put("child_index", index)
                    .put("adapter_position", view.getChildAdapterPosition(child)).put("item_id", view.getChildItemId(child))
                    .put("decorated_bounds", hydrationRect(decorated)))
            }
            entry.put("scroll_state", view.scrollState).put("computing_layout", view.isComputingLayout)
                .put("layout_suppressed", view.isLayoutSuppressed)
                .put("layout_manager", view.layoutManager?.javaClass?.name)
                .put("adapter", view.adapter?.javaClass?.name).put("adapter_count", view.adapter?.itemCount)
                .put("pending_adapter_updates", view.hasPendingAdapterUpdates()).put("first_attached_items", items)
                .put("item_animator", view.itemAnimator?.javaClass?.name ?: JSONObject.NULL)
                .put("item_animator_running", view.itemAnimator?.isRunning ?: false)
                .put("overscroll_present", view.sourceOverscroll != null)
                .put("overscroll_near_far_in_progress_only", view.sourceOverscroll?.isInProgress ?: false)
                .put("overscroll_backend_or_negation_idle_proven", false)
        }
        entries.put(entry)
    }
    val windowFrame = Rect(); board.getWindowVisibleDisplayFrame(windowFrame)
    return JSONObject().put("uptime_ms", SystemClock.uptimeMillis()).put("views", entries)
        .put("window_visible_display_frame", hydrationRect(windowFrame))
        .put("root_identity", identity(board.rootView)).put("display_id", board.display?.displayId)
        .put("active", settings.owner.state.active).put("hydration_pending", settings.owner.state.hydrationPending)
        .put("input_allowed", settings.inputAllowed).put("saved_position", settings.savePosition().toString())
}
