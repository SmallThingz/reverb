package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import app.smallthingz.reverb.nativeui.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Actual app IconButton adapter; own-View event dispatch, not physical pointer or released pixel parity. */
internal fun verifyNativeClickRippleCurrent(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-click-ripple-current-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val result = JSONObject().put("selector", "native_click_ripple_current").put("passed", false)
        .put("expected_cases", 4).put("completed_cases", 0)
        .put("scope", "Actual ReverbStandardIconButtonView, moved click controller and ripple; own-View dispatch with physical-display hardware captures")
        .put("not_qualified", JSONArray(listOf("OS pointer routing", "elevation raster", "all parent gesture arbitration", "released ripple frame equality")))
    val cases = JSONArray().also { result.put("cases", it) }
    val preferences = getRecorderPreferences(context).all.toMap()
    var host: NativeParityActivity? = null
    var button: ReverbStandardIconButtonView? = null
    var failure: Throwable? = null
    var baseline: Bitmap? = null
    fun persist() { File(directory, "result.json").writeText(result.toString(2)) }
    try {
        persist()
        val activity = launchCurrentUiSmokeActivity(instrumentation).also { host = it }
        val environment = nativeParityEnvironment(activity,
            androidx.compose.ui.unit.Density(activity.resources.displayMetrics.density, activity.resources.configuration.fontScale),
            ReverbPalette.resolve(activity, false))
        var clicks = 0
        val view = nativeParityOnMain(instrumentation) {
            ReverbStandardIconButtonView(activity, environment, { activity.fixtureRoot }).also {
                button = it
                activity.fixtureRoot.setBackgroundColor(environment.palette.surface)
                activity.fixtureRoot.addView(it, FrameLayout.LayoutParams(-2, -2).apply {
                    gravity = android.view.Gravity.CENTER
                })
            }
        }
        fun bind(enabled: Boolean) = nativeParityOnMain(instrumentation) {
            view.bind(environment, ReverbIcon.Back, "Click QA", enabled,
                environment.palette.onSurface, environment.palette.onSurface) { clicks++ }
        }
        fun draw(name: String): Bitmap {
            requireNativeParityHost(instrumentation, activity)
            repeat(2) { awaitNativeParityDraw(instrumentation, activity.fixtureRoot) }
            val bitmap = captureNativeParityContent(instrumentation, activity) { view.visual }
            try {
                saveNativeParityBitmap(directory, "$name.png", bitmap)
                requireNativeParityHost(instrumentation, activity)
                return bitmap
            } catch (error: Throwable) { bitmap.recycle(); throw error }
        }
        var downTime = 0L
        fun pointer(action: Int) {
            requireNativeParityHost(instrumentation, activity)
            nativeParityOnMain(instrumentation) {
                val now = SystemClock.uptimeMillis()
                if (action == MotionEvent.ACTION_DOWN) downTime = now
                val event = MotionEvent.obtain(downTime, now, action,
                    view.visual.width * .5f, view.visual.height * .5f, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { view.visual.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        fun scenario(name: String, action: (JSONObject) -> Unit) {
            val entry = JSONObject().put("name", name).put("passed", false)
            cases.put(entry); persist()
            try { action(entry); entry.put("passed", true); result.put("completed_cases", cases.length()) }
            catch (error: Throwable) { entry.put("error", error.stackTraceToString()); throw error }
            finally { persist() }
        }
        bind(true)
        baseline = draw("idle")
        val idleControl = draw("idle-control")
        try {
            val difference = compareNativeParityPixels(checkNotNull(baseline), idleControl)
            result.put("idle_control_difference", difference)
            check(difference.getLong("different_pixels") == 0L) { "Button idle raster did not settle" }
        } finally { idleControl.recycle() }
        result.put("capture_region", "Exact action visual bounds from getLocationInWindow; excludes unrelated fixture content")
        scenario("press-release") { entry ->
            pointer(MotionEvent.ACTION_DOWN)
            check(clicks == 0)
            val pressed = draw("pressed")
            try {
                val difference = compareNativeParityPixels(checkNotNull(baseline), pressed)
                entry.put("pressed_vs_idle", difference)
                check(difference.getLong("different_pixels") > 0) { "Pressed hardware render did not show an indication" }
            } finally { pressed.recycle() }
            pointer(MotionEvent.ACTION_UP)
            check(clicks == 1)
            draw("released").recycle()
            entry.put("clicks", clicks)
        }
        scenario("cancel") { entry ->
            pointer(MotionEvent.ACTION_DOWN)
            pointer(MotionEvent.ACTION_CANCEL)
            check(clicks == 1)
            draw("cancelled").recycle()
            entry.put("clicks", clicks)
        }
        scenario("disabled-during-press") { entry ->
            pointer(MotionEvent.ACTION_DOWN)
            bind(false)
            pointer(MotionEvent.ACTION_UP)
            check(clicks == 1)
            draw("disabled").recycle()
            entry.put("clicks", clicks)
        }
        scenario("detach-retires-press") { entry ->
            bind(true)
            pointer(MotionEvent.ACTION_DOWN)
            nativeParityOnMain(instrumentation) { activity.fixtureRoot.removeView(view) }
            pointer(MotionEvent.ACTION_UP)
            check(clicks == 1)
            nativeParityOnMain(instrumentation) { activity.fixtureRoot.addView(view) }
            bind(true)
            draw("reattached").recycle()
            pointer(MotionEvent.ACTION_DOWN); pointer(MotionEvent.ACTION_UP)
            check(clicks == 2)
            entry.put("clicks", clicks)
        }
    } catch (error: Throwable) { failure = error }
    finally {
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        cleanup { baseline?.recycle() }
        cleanup { nativeParityOnMain(instrumentation) { button?.cancelInput() } }
        cleanup { nativeParityOnMain(instrumentation) { host?.fixtureRoot?.removeAllViews() } }
        cleanup { nativeParityOnMain(instrumentation) { host?.finish() } }
        cleanup { check(getRecorderPreferences(context).all == preferences) }
        result.put("passed", failure == null && result.getInt("completed_cases") == 4)
        failure?.let { result.put("terminal_error", it.stackTraceToString()) }
        cleanup(::persist)
        report.appendLine("PARITY_EVIDENCE ${directory.name}/result.json")
    }
    failure?.let { throw it }
    check(result.getBoolean("passed"))
    report.appendLine("PASS native_click_ripple_current cases=4")
}
