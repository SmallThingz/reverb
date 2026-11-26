package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.widget.FrameLayout
import app.smallthingz.reverb.nativeui.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        .put("expected_cases", 5).put("completed_cases", 0)
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
        nativeParityOnMain(instrumentation) { activity.fixtureRoot.removeView(view) }
        scenario("dropdown-option-release") { entry ->
            verifyDropdownReleaseRipple(instrumentation, activity, environment, entry)
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
        result.put("passed", failure == null && result.getInt("completed_cases") == 5)
        failure?.let { result.put("terminal_error", it.stackTraceToString()) }
        cleanup(::persist)
        report.appendLine("PARITY_EVIDENCE ${directory.name}/result.json")
    }
    failure?.let { throw it }
    check(result.getBoolean("passed"))
    report.appendLine("PASS native_click_ripple_current cases=5")
}

private fun verifyDropdownReleaseRipple(
    instrumentation: Instrumentation,
    activity: NativeParityActivity,
    environment: ReverbViewEnvironment,
    entry: JSONObject,
) {
    val root = activity.fixtureRoot
    val dropdown = smallthingz.ui.components.NativeAnchoredDropdown(activity, root, environment.theme.controlTheme)
    val picks = mutableListOf<Int>()
    val content = smallthingz.ui.common.DropdownContent("Ripple fixture", 0, listOf(0, 1, 2)) { "Choice $it" }
    val anchor = nativeParityOnMain(instrumentation) {
        dropdown.createField(content) { picks += it }.also {
            root.addView(it, FrameLayout.LayoutParams(root.width / 2, (72 * activity.resources.displayMetrics.density).toInt(),
                android.view.Gravity.CENTER))
        }
    }
    var downTime = 0L
    fun row() = onboardingViewDescendants(root)
        .filterIsInstance<smallthingz.ui.components.NativeDropdown<*>>()
        .singleOrNull { it !== anchor && it.createAccessibilityNodeInfo()?.contentDescription == "Choice 1" }
    fun send(target: android.view.View, action: Int, leave: Boolean = false, corner: Boolean = false) {
        val wheel = target.parent as android.view.View
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val x = when {
            leave -> wheel.width + 1f
            corner -> 1f
            else -> target.width * .7f
        }
        val y = target.translationY + if (corner) 2f else target.height * .5f
        val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { check(wheel.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
    try {
        awaitNativeParityDraw(instrumentation, root)
        nativeParityOnMain(instrumentation) { dropdown.open(anchor, content) { picks += it } }
        val deadline = SystemClock.uptimeMillis() + 3_000L
        var visible: android.view.View? = null
        while (visible == null) {
            check(SystemClock.uptimeMillis() < deadline) { "Dropdown choice never became fully visible" }
            awaitNativeParityDraw(instrumentation, root)
            visible = nativeParityOnMain(instrumentation) { row()?.takeIf { it.isFocusable && it.width > 0 } }
        }
        val target = checkNotNull(visible)
        val idle = captureNativeParityContent(instrumentation, activity) { target }
        try {
            nativeParityOnMain(instrumentation) {
                val wheel = target.parent as android.view.ViewGroup
                val top = (0 until wheel.childCount).map(wheel::getChildAt).minBy { it.translationY }
                check(top.isFocusable) { "Rounded corner fixture requires the complete reveal" }
                send(top, MotionEvent.ACTION_DOWN, corner = true)
                send(top, MotionEvent.ACTION_UP, corner = true)
                send(target, MotionEvent.ACTION_DOWN)
                send(target, MotionEvent.ACTION_UP, leave = true)
                send(target, MotionEvent.ACTION_DOWN)
                send(target, MotionEvent.ACTION_CANCEL)
                check(picks.isEmpty() && target.isAttachedToWindow)
            }
            val releasedOutside = captureNativeParityContent(instrumentation, activity) { target }
            try {
                check(compareNativeParityPixels(idle, releasedOutside).getLong("different_pixels") == 0L)
            } finally { releasedOutside.recycle() }
            nativeParityOnMain(instrumentation) { send(target, MotionEvent.ACTION_DOWN) }
            val held = captureNativeParityContent(instrumentation, activity) { target }
            try { check(compareNativeParityPixels(idle, held).getLong("different_pixels") == 0L) }
            finally { held.recycle() }
            val sample = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            var copyStatus = -1
            var positionAtCommit = Float.NaN
            val before = nativeParityOnMain(instrumentation) {
                val before = target.translationY
                root.viewTreeObserver.registerFrameCommitCallback {
                    positionAtCommit = target.translationY
                    val xy = IntArray(2).also(target::getLocationInWindow)
                    val x = xy[0] + (target.width * .7f).toInt()
                    val y = xy[1] + target.height / 2
                    PixelCopy.request(activity.window, Rect(x - 2, y - 2, x + 3, y + 3), sample, { status ->
                        copyStatus = status
                        copied.countDown()
                    }, Handler(Looper.getMainLooper()))
                }
                send(target, MotionEvent.ACTION_UP)
                check(picks == listOf(1) && target.isAttachedToWindow && target.translationY == before)
                // Another finger stream during the queued collapse may begin inside, but an
                // outside release cannot reverse it or commit another value.
                send(target, MotionEvent.ACTION_DOWN)
                send(target, MotionEvent.ACTION_UP, leave = true)
                before
            }
            check(copied.await(3, TimeUnit.SECONDS)) { "No frame committed after dropdown release" }
            try {
                check(copyStatus == PixelCopy.SUCCESS && before == positionAtCommit)
                val x = (idle.width * .7f).toInt() - 2
                val y = idle.height / 2 - 2
                val changed = (0 until 25).count { sample.getPixel(it % 5, it / 5) != idle.getPixel(x + it % 5, y + it / 5) }
                entry.put("ripple_pixels_before_motion", changed)
                check(changed > 0) { "The first committed release frame has no ripple at the hotspot" }
            } finally { sample.recycle() }
            val closeBy = SystemClock.uptimeMillis() + 3_000L
            while (nativeParityOnMain(instrumentation) { target.isAttachedToWindow }) {
                check(SystemClock.uptimeMillis() < closeBy) { "Outside lift reversed an accepted collapse" }
                awaitNativeParityDraw(instrumentation, root)
            }
            check(nativeParityOnMain(instrumentation) { picks == listOf(1) })
            entry.put("selected_once_on_release", true).put("outside_and_cancel_unchanged", true)
                .put("rounded_corner_rejected", true).put("outside_release_did_not_reverse_collapse", true)
        } finally { idle.recycle() }
    } finally {
        nativeParityOnMain(instrumentation) {
            dropdown.close()
            anchor.close()
            root.removeView(anchor)
        }
    }
}
