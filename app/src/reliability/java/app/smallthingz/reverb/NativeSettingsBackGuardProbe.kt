package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.BackEventCompat
import app.smallthingz.reverb.nativeui.NativeSettingsScreenContent
import app.smallthingz.reverb.nativeui.ReverbIncidentLoadingView
import app.smallthingz.reverb.nativeui.ReverbHomeScaffoldView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

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
                fun captureHydration(label: String): JSONObject {
                    await("Settings fully revealed for $label") {
                        val home = backGuardViews(current.board).filterIsInstance<ReverbHomeScaffoldView>().single()
                        val parentHeight = (home.parent as View).height
                        parentHeight > 0 && home.translationY >= parentHeight.toFloat()
                    }
                    capture(label)
                    val name = "${mode.name}-$label.png"
                    val digest = File(directory, name).inputStream().use { sha256(it) }
                    check(digest.byteCount > 0L)
                    return JSONObject().put("png", name).put("bytes", digest.byteCount)
                        .put("sha256", digest.sha256.joinToString("") { "%02x".format(it.toInt() and 0xff) })
                }
                await("Settings action") { current.actionCount(host.getString(R.string.open_settings)) == 1 }
                main { current.click(host.getString(R.string.open_settings)) }
                lateinit var settings: NativeSettingsScreenContent
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
