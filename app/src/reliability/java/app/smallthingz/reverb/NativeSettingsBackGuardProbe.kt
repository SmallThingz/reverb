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
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Current requested behavior, deliberately not compared with the unchanged released oracle. */
internal fun verifyNativeSettingsBackGuard(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val preferences = getRecorderPreferences(context)
    val original = preferences.all.toMap()
    val directory = File(context.filesDir, "native-settings-back-guard-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val evidence = JSONObject().put("passed", false).put("cases", cases)
        .put("scope", "Current native Settings owner and attached Views; no released-parity claim")
        .put("unqualified", JSONArray(listOf("OS predictive gesture", "physical keyboard", "TalkBack delivery", "production capture")))
    fun publish() = writeQaEvidenceAtomically(File(directory, "result.json")) { stream ->
        stream.write(evidence.toString(2).toByteArray(Charsets.UTF_8))
    }
    publish()
    var failure: Throwable? = null
    try {
        for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
            val host = launchNativeParityActivity(instrumentation)
            var render: MainSettingsParityRender? = null
            var pendingHint: Bitmap? = null
            var passFailure: Throwable? = null
            try {
                val current = nativeParityOnMain(instrumentation) {
                    MainSettingsParityRender(host, false, mode, mode == AppThemeMode.DARK, null)
                }.also { render = it }
                fun main(action: () -> Unit) = nativeParityOnMain(instrumentation, action)
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
            } catch (error: Throwable) { passFailure = error; throw error }
            finally {
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
        report.appendLine("SETTINGS_BACK_GUARD_EVIDENCE ${directory.absolutePath}")
    }
    failure?.let { throw it }
    report.appendLine("PASS native_settings_back_guard current dirty Back confirmation; synthetic dispatcher, actual native owner and frames")
}

private fun backGuardViews(root: View): Sequence<View> = sequence {
    yield(root)
    if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(backGuardViews(root.getChildAt(index)))
}
