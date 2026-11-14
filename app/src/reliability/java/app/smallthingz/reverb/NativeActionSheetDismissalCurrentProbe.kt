package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment
import app.smallthingz.reverb.nativeui.createReverbActionSheet
import java.io.File
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.components.NativeActionSheet
import smallthingz.ui.components.NativeFloatingDialog
import smallthingz.ui.components.NativeFloatingDialogContent
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.unit.Density

/** Instance-scoped AndroidX Back completion and dialog touch; no process-global input. */
internal fun verifyNativeActionSheetDismissalCurrent(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val beforePreferences = getRecorderPreferences(context).all.toMap()
    val directory = File(context.filesDir, "native-action-sheet-dismissal-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("selector", "native_action_sheet_dismissal_current")
        .put("passed", false).put("cases", cases)
        .put("scope", "NativeActionSheet AndroidX Back-dispatcher completion/scrim busy veto, plus NativeFloatingDialog dispatcher completion and content/window retirement")
        .put("input_route", "Instance ComponentDialog.onBackPressedDispatcher.onBackPressed(); scrim Dialog.dispatchTouchEvent; no global input injection")
        .put("unqualified", JSONArray(listOf("physical/system Back delivery", "platform OnBackInvoked registration/delivery",
            "predictive system Back gesture", "IME", "production recorder")))
    val host = launchCurrentUiSmokeActivity(instrumentation)
    var currentSheet: NativeActionSheet? = null
    var currentFloating: NativeFloatingDialog? = null
    var failure: Throwable? = null
    fun <T> main(block: () -> T) = nativeParityOnMain(instrumentation, block)
    try {
        val anchor = main {
            TextView(host).also {
                it.text = "Action sheet dismissal fixture"
                host.fixtureRoot.removeAllViews()
                host.fixtureRoot.addView(it, ViewGroup.LayoutParams(-1, -1))
            }
        }
        awaitNativeParityDraw(instrumentation, anchor)
        for (route in listOf("androidx-back-dispatcher", "scrim")) {
            requireNativeParityHost(instrumentation, host, "before-$route-sheet")
            var busy = true
            var ownerDismissals = 0
            var windowDismissals = 0
            lateinit var dialog: NativeActionSheet
            main {
                val environment = ReverbViewEnvironment(Density(host), createFontFamilyResolver(host), ReverbPalette.fallback(true))
                dialog = createReverbActionSheet(anchor, environment, "Dismissal admission") {
                    ownerDismissals++
                    dialog.dismiss()
                }
                currentSheet = dialog
                val body = TextView(host).apply { text = "Busy operations must keep this window open." }
                val actions = View(host)
                dialog.setContent(body, actions, emptyList())
                dialog.setDismissalAdmission { !busy }
                dialog.setOnDismissListener { windowDismissals++ }
                dialog.show()
            }
            val decor = main { checkNotNull(dialog.window).decorView }
            val deadline = SystemClock.uptimeMillis() + 8000
            while (!main { dialog.hasCurrentWindowFocus && actionSheetFixtureExpanded(dialog) }) {
                check(SystemClock.uptimeMillis() < deadline) { "$route sheet did not settle in its own window" }
                awaitNativeParityDraw(instrumentation, decor)
            }
            fun guard() {
                check(host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !host.isFinishing)
                check(dialog.isShowing && dialog.hasCurrentWindowFocus)
                check(decor.isAttachedToWindow && decor.isShown && decor.isHardwareAccelerated)
                check(decor.display?.displayId == Display.DEFAULT_DISPLAY)
            }
            val expandedBounds = main {
                guard()
                Rect().also { check(dialog.contentView.getGlobalVisibleRect(it) && !it.isEmpty) }
            }
            fun dispatch() = main {
                guard()
                if (route == "androidx-back-dispatcher") {
                    // Direct key dispatch skips the modern framework Back input stage.
                    check(dialog.onBackPressedDispatcher.hasEnabledCallbacks())
                    dialog.onBackPressedDispatcher.onBackPressed()
                } else {
                    val now = SystemClock.uptimeMillis()
                    val origin = IntArray(2).also(decor::getLocationOnScreen)
                    val bounds = Rect().also { check(dialog.contentView.getGlobalVisibleRect(it)) }
                    val gap = bounds.top - origin[1]
                    check(gap > 48 * host.resources.displayMetrics.density) { "No safe measured scrim area" }
                    val x = decor.width / 2f
                    val y = gap / 2f
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now, action, x, y, 0).apply {
                            source = InputDevice.SOURCE_TOUCHSCREEN
                        }
                        try { check(dialog.dispatchTouchEvent(event)) { "$route event was not consumed" } }
                        finally { event.recycle() }
                    }
                }
            }
            dispatch()
            repeat(2) { awaitNativeParityDraw(instrumentation, decor) }
            main {
                guard()
                check(ownerDismissals == 0 && windowDismissals == 0) { "$route bypassed busy dismissal veto" }
                check(actionSheetFixtureExpanded(dialog)) { "$route moved a vetoed sheet toward hidden" }
                val retained = Rect().also { check(dialog.contentView.getGlobalVisibleRect(it)) }
                check(retained == expandedBounds) { "$route changed vetoed sheet bounds: $expandedBounds -> $retained" }
                busy = false
                dialog.refreshDismissalAdmission()
            }
            dispatch()
            val dismissDeadline = SystemClock.uptimeMillis() + 8000
            while (!main { !dialog.isShowing && ownerDismissals == 1 && windowDismissals == 1 }) {
                check(SystemClock.uptimeMillis() < dismissDeadline) {
                    "$route did not finish exactly once after admission changed"
                }
                awaitNativeParityDraw(instrumentation, anchor)
            }
            main { dialog.dismiss(); currentSheet = null }
            repeat(2) { awaitNativeParityDraw(instrumentation, anchor) }
            main {
                check(ownerDismissals == 1 && windowDismissals == 1) { "$route duplicated terminal delivery" }
                check(!decor.isAttachedToWindow) { "$route left its dialog window attached" }
            }
            requireNativeParityHost(instrumentation, host, "after-$route-sheet")
            cases.put(JSONObject().put("route", route).put("busy_veto_retained_window", true)
                .put("veto_retained_bounds", expandedBounds.toShortString())
                .put("owner_dismissals", ownerDismissals).put("window_dismissals", windowDismissals)
                .put("admitted_dismiss_removed_window", true).put("duplicate_terminal", false))
        }
        requireNativeParityHost(instrumentation, host, "before-floating-dialog")
        var floatingOwnerDismissals = 0
        var floatingWindowDismissals = 0
        var floatingDisposals = 0
        lateinit var floating: NativeFloatingDialog
        lateinit var floatingBody: TextView
        main {
            floating = NativeFloatingDialog(anchor, host.resources.displayMetrics.density,
                onDismiss = { floatingOwnerDismissals++; floating.dismiss() },
                createContent = { themed, _, _ ->
                    floatingBody = TextView(themed).apply { text = "Floating dialog lifecycle fixture" }
                    NativeFloatingDialogContent(floatingBody, bindWindowAccessibility = {}, dispose = { floatingDisposals++ })
                })
            currentFloating = floating
            floating.setOnDismissListener { floatingWindowDismissals++ }
            floating.show()
        }
        val floatingDecor = main { checkNotNull(floating.window).decorView }
        val floatingDeadline = SystemClock.uptimeMillis() + 8000
        var floatingAlphaSamples = 0
        var floatingTransientAlphaObserved = false
        while (!main {
            val motion = floating.javaClass.getDeclaredField("visibilityMotion").apply { isAccessible = true }.get(floating)
            val expectedAlpha = motion.javaClass.getDeclaredMethod("getAlpha").apply { isAccessible = true }.invoke(motion) as Float
            check(abs(floatingBody.alpha - expectedAlpha) < .0001f) {
                "Same-view predictive layer replaced entry alpha: ${floatingBody.alpha} != $expectedAlpha"
            }
            floatingAlphaSamples++
            if (expectedAlpha > 0f && expectedAlpha < .99f) floatingTransientAlphaObserved = true
            floatingDecor.hasWindowFocus() && floatingBody.alpha >= .99f
        }) {
            check(SystemClock.uptimeMillis() < floatingDeadline) { "Floating dialog did not enter its focused window" }
            awaitNativeParityDraw(instrumentation, floatingDecor)
        }
        main {
            check(host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !host.isFinishing)
            check(floating.isShowing && floatingDecor.hasWindowFocus())
            check(floatingDecor.isAttachedToWindow && floatingDecor.isShown && floatingDecor.isHardwareAccelerated)
            check(floatingDecor.display?.displayId == Display.DEFAULT_DISPLAY)
            check(floating.onBackPressedDispatcher.hasEnabledCallbacks())
            floating.onBackPressedDispatcher.onBackPressed()
        }
        val floatingDismissDeadline = SystemClock.uptimeMillis() + 8000
        while (!main { !floating.isShowing && floatingOwnerDismissals == 1 && floatingWindowDismissals == 1 && floatingDisposals == 1 }) {
            check(SystemClock.uptimeMillis() < floatingDismissDeadline) { "Floating dialog dispatcher completion did not retire each owner exactly once" }
            awaitNativeParityDraw(instrumentation, anchor)
        }
        main { floating.dismiss(); floating.dismiss(); currentFloating = null }
        repeat(2) { awaitNativeParityDraw(instrumentation, anchor) }
        main {
            check(floatingOwnerDismissals == 1 && floatingWindowDismissals == 1 && floatingDisposals == 1)
            check(!floatingDecor.isAttachedToWindow)
        }
        requireNativeParityHost(instrumentation, host, "after-floating-dialog")
        cases.put(JSONObject().put("route", "floating-dialog-androidx-back-dispatcher")
            .put("owner_dismissals", floatingOwnerDismissals).put("window_dismissals", floatingWindowDismissals)
            .put("same_view_visibility_alpha_samples", floatingAlphaSamples)
            .put("transient_entry_alpha_observed", floatingTransientAlphaObserved)
            .put("content_disposals", floatingDisposals).put("duplicate_terminal", false))
        result.put("passed", true)
    } catch (error: Throwable) { failure = error }
    finally {
        failure = captureNativeParityCleanup(failure) { main { currentSheet?.dismiss() } }
        failure = captureNativeParityCleanup(failure) { main { currentFloating?.dismiss() } }
        failure = captureNativeParityCleanup(failure) { main { host.finish() } }
        failure = captureNativeParityCleanup(failure) { check(getRecorderPreferences(context).all == beforePreferences) }
        if (failure != null) result.put("passed", false).put("failure", failure!!.stackTraceToString())
        failure = captureNativeParityCleanup(failure) {
            writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray()) }
        }
        report.appendLine("ACTION_SHEET_DISMISSAL_EVIDENCE ${directory.absolutePath}")
    }
    failure?.let { throw it }
    report.appendLine("PASS native_action_sheet_dismissal_current AndroidX dispatcher completion/scrim busy veto and floating dialog retirement")
}

/** Observe readiness only; transitions use the instance dispatcher or dialog touch dispatch. */
private fun actionSheetFixtureExpanded(dialog: NativeActionSheet): Boolean {
    val state = dialog.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(dialog)
    fun property(name: String): Any? = state.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(state)
    return property("getCurrentValue").toString() == "EXPANDED" &&
        property("getTargetValue").toString() == "EXPANDED" && property("getAnimationRunning") == false
}
