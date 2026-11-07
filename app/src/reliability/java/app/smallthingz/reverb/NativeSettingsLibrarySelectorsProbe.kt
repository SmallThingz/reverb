package app.smallthingz.reverb

import android.app.ActivityOptions
import android.content.Intent
import android.view.Display
import java.security.MessageDigest
import android.app.Application
import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.pow
import app.smallthingz.reverb.nativeui.NativeSettingsScreenContent
import app.smallthingz.reverb.nativeui.SettingsSegmentedControlView
import app.smallthingz.reverb.nativeui.SettingsSegment
import smallthingz.ui.components.NativeSegmentedChoice
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Isolated actual Settings root; synthetic input, no recorder/capture mounted. */
internal fun verifyNativeSettingsLibrarySelectors(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    val currentUiSmoke = context.packageName == "app.smallthingz.reverb.uitheme.reliability"
    check(currentUiSmoke || context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val original = getRecorderPreferences(context).all.toMap()
    val directory = File(context.filesDir, "native-settings-library-selectors-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val motions = JSONArray()
    val result = JSONObject().put("selector", "native_settings_library_selectors").put("passed", false)
        .put("cases", cases).put("motion_coordinates", motions)
    result.put("package", context.packageName)
        .put("mode", if (currentUiSmoke) "current-ui-smoke" else "isolated-current-settings")
        .put("historical_parity_claim", false)
    // Correlate this installed artifact with root's frozen source/build receipt, not a historical oracle.
    val digest = MessageDigest.getInstance("SHA-256")
    File(context.applicationInfo.sourceDir).inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    result.put("installed_apk_sha256", digest.digest().joinToString("") { "%02x".format(it) })
        .put("source_identity_contract", "Installed APK digest maps to the frozen current-source build receipt")
    val host = if (currentUiSmoke) launchCurrentUiSmokeActivity(instrumentation) else launchNativeParityActivity(instrumentation)
    var render: MainSettingsParityRender? = null
    var failure: Throwable? = null
    fun main(action: () -> Unit) = nativeParityOnMain(instrumentation, action)
    try {
        val current = nativeParityOnMain(instrumentation) {
            MainSettingsParityRender(host, false, AppThemeMode.LIGHT, false, null)
        }.also { render = it }
        fun settle() { awaitNativeParityDraw(instrumentation, current.board); instrumentation.waitForIdleSync() }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (!nativeParityOnMain(instrumentation, condition)) {
                check(SystemClock.uptimeMillis() < deadline) { "Timed out: $label" }; settle()
            }
        }
        await("Settings action") { current.actionCount(host.getString(R.string.open_settings)) == 1 }
        main { current.click(host.getString(R.string.open_settings)) }
        lateinit var settings: NativeSettingsScreenContent
        await("Settings hydrated") {
            selectorViews(current.board).filterIsInstance<NativeSettingsScreenContent>()
                .firstOrNull { it.inputAllowed }?.also { settings = it } != null
        }
        main {
            val tiny = SettingsSegmentedControlView(context,
                listOf(SettingsSegment("time", 0, "Time"), SettingsSegment("size", 1, "Size")),
                0, settings.pointerEnvironment, { current.board }) { error("Geometry fixture must not activate") }
            try {
                val inset = (4 * context.resources.displayMetrics.density).roundToInt()
                for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                    tiny.layoutDirection = direction
                    for (width in listOf(0, 1, 2 * inset - 1, 2 * inset, 2 * inset + 1, 200)) {
                        tiny.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        tiny.layout(0, 0, width, tiny.measuredHeight)
                        val choices = selectorViews(tiny).filterIsInstance<NativeSegmentedChoice>().single()
                        check(choices.width == choices.measuredWidth && choices.width == (width - 2 * inset).coerceAtLeast(0))
                        for (child in selectorViews(choices)) check(child.right >= child.left && child.bottom >= child.top)
                    }
                }
            } finally { tiny.close() }
            cases.put(JSONObject().put("case", "adapter zero/narrow width LTR/RTL geometry").put("passed", true))
        }
        fun bounds(view: View): Rect {
            val point = IntArray(2); view.getLocationOnScreen(point)
            return Rect(point[0], point[1], point[0] + view.width, point[1] + view.height)
        }
        fun motion(action: Int, down: Long, x: Float, y: Float) {
            check(host.hasWindowFocus() && settings.inputAllowed)
            val origin = IntArray(2); current.board.getLocationOnScreen(origin)
            val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = MotionEvent.PointerCoords().apply {
                this.x = x; this.y = y
                pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f; size = 1f
            }
            // Construct raw screen coordinates, then offset only local coordinates for dispatch.
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1,
                arrayOf(pointer), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
                val rawX = event.rawX; val rawY = event.rawY
                val localX = event.x; val localY = event.y
                val expectedLocalX = x - origin[0]; val expectedLocalY = y - origin[1]
                val tool = event.getToolType(0)
                fun coordinate(value: Float): Any = if (value.isFinite()) value.toDouble() else value.toString()
                val observation = JSONObject().put("action", action)
                    .put("expected_raw_x", coordinate(x)).put("expected_raw_y", coordinate(y))
                    .put("expected_local_x", coordinate(expectedLocalX)).put("expected_local_y", coordinate(expectedLocalY))
                    .put("actual_raw_x", coordinate(rawX)).put("actual_raw_y", coordinate(rawY))
                    .put("actual_local_x", coordinate(localX)).put("actual_local_y", coordinate(localY))
                    .put("tool_type", tool).put("tolerance_px", 0.01)
                motions.put(observation)
                // Offset/raw reconstruction uses Float math; allow only a hundredth of one pixel.
                fun near(actual: Float, expected: Float) = actual.isFinite() && expected.isFinite() &&
                    kotlin.math.abs(actual - expected) <= .01f
                check(tool == MotionEvent.TOOL_TYPE_FINGER && near(rawX, x) && near(rawY, y) &&
                    near(localX, expectedLocalX) && near(localY, expectedLocalY)) {
                    "Synthetic finger coordinate mismatch: $observation"
                }
                val handled = current.board.dispatchTouchEvent(event)
                observation.put("handled", handled)
                check(handled) { "Synthetic finger dispatch not handled: $observation" }
            } finally { event.recycle() }
        }
        fun reveal(view: View) {
            main {
                val viewport = checkNotNull(settings.listViewport)
                viewport.scrollBy(0, bounds(view).centerY() - bounds(viewport).centerY())
            }; settle()
        }
        fun snapshot(name: String) {
            // Observe attached color-transition frames before saving actual hardware-rendered pixels.
            repeat(45) { settle() }
            val bitmap = captureNativeParityContent(instrumentation, host) { current.board }
            try { saveNativeParityBitmap(directory, "$name.png", bitmap) } finally { bitmap.recycle() }
            cases.put(JSONObject().put("screenshot", "$name.png").put("dark", current.effectiveDark))
        }
        fun checkRestingContrast(group: NativeSegmentedChoice) {
            val selected = checkNotNull(group.choiceView(checkNotNull(group.selectedId)))
            fun luminance(color: Int): Double {
                fun channel(shift: Int): Double {
                    val value = (color ushr shift and 255) / 255.0
                    return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
                }
                return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
            }
            val palette = settings.pointerEnvironment.palette
            fun contrast(a: Int, b: Int): Double {
                val first = luminance(a); val second = luminance(b)
                return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
            }
            check(selected.currentTextColor == palette.settingsOnSelection)
            check(!(group.choiceStyle.selectedOutlined ?: group.choiceStyle.outlined)) {
                "Selected size/theme choice must not request an outline"
            }
            // Draw the actual settled background, independently observing its solid fill and edge.
            val bitmap = android.graphics.Bitmap.createBitmap(selected.width, selected.height,
                android.graphics.Bitmap.Config.ARGB_8888)
            val priorState = selected.background.state
            try {
                selected.background.state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_selected)
                selected.background.draw(android.graphics.Canvas(bitmap))
                val fill = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                val edge = bitmap.getPixel(bitmap.width / 2,
                    maxOf(1, (context.resources.displayMetrics.density / 2).toInt()))
                check(fill == palette.primary && fill == palette.settingsSelection && fill ushr 24 == 255) {
                    "Selected fill must be the opaque primary role"
                }
                check(edge == fill) { "Selected edge differs from its solid fill" }
                check(contrast(selected.currentTextColor, fill) >= 4.5)
                cases.put(JSONObject().put("case", "actual selected solid paint without outline")
                    .put("dark", current.effectiveDark).put("foreground", selected.currentTextColor)
                    .put("fill", fill).put("edge", edge).put("selected_outlined", false).put("passed", true))
            } finally { selected.background.state = priorState; bitmap.recycle() }
            val density = context.resources.displayMetrics.density
            check(group.height == (34 * density).roundToInt())
        }
        for ((label, firstId, secondId) in listOf(Triple("theme", "LIGHT", "DARK"), Triple("retention", "time", "size"))) {
            lateinit var group: NativeSegmentedChoice
            await("$label group") {
                selectorViews(settings).filterIsInstance<NativeSegmentedChoice>()
                    .firstOrNull { it.choiceView(firstId) != null }?.also { group = it } != null
            }
            reveal(group)
            snapshot("$label-before")
            main { checkRestingContrast(group) }
            val before = nativeParityOnMain(instrumentation) { group.selectedId }
            val targetId = if (before == secondId) firstId else secondId
            val target = nativeParityOnMain(instrumentation) { checkNotNull(group.choiceView(targetId)) }
            main {
                val density = context.resources.displayMetrics.density
                check(group.height == (34 * density).roundToInt())
                val adapter = generateSequence(group.parent as? View) { it.parent as? View }
                    .filterIsInstance<SettingsSegmentedControlView<*>>().first()
                check(adapter.height == (42 * density).roundToInt())
                check(target.getGlobalVisibleRect(Rect()))
                val node = target.createAccessibilityNodeInfo()
                check(node.className == "android.widget.RadioButton" && node.isCheckable)
                check(node.isChecked == target.isChecked)
                check(node.collectionItemInfo != null)
                val collection = group.createAccessibilityNodeInfo()
                check(collection.collectionInfo?.selectionMode == AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE)
                @Suppress("DEPRECATION") collection.recycle()
                @Suppress("DEPRECATION") node.recycle()
            }
            val down = SystemClock.uptimeMillis()
            val box = nativeParityOnMain(instrumentation) { bounds(target) }
            main { motion(MotionEvent.ACTION_DOWN, down, box.exactCenterX(), box.exactCenterY()) }
            settle()
            SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 30)
            main { check(group.selectedId == before); motion(MotionEvent.ACTION_UP, down, box.exactCenterX(), box.exactCenterY()) }
            await("$label held release") { group.selectedId == targetId }
            snapshot("$label-after-selection")
            main { checkRestingContrast(group) }
            cases.put(JSONObject().put("case", "$label held release, radio semantics, 34/42dp geometry").put("passed", true))
            val other = nativeParityOnMain(instrumentation) { checkNotNull(group.choiceView(if (targetId == secondId) firstId else secondId)) }
            val otherBox = nativeParityOnMain(instrumentation) { bounds(other) }
            val dragDown = SystemClock.uptimeMillis()
            main {
                motion(MotionEvent.ACTION_DOWN, dragDown, otherBox.exactCenterX(), otherBox.exactCenterY())
                motion(MotionEvent.ACTION_MOVE, dragDown, otherBox.exactCenterX(), otherBox.exactCenterY() - 100)
                motion(MotionEvent.ACTION_UP, dragDown, otherBox.exactCenterX(), otherBox.exactCenterY() - 100)
            }; settle()
            main { check(group.selectedId == targetId) }
            cases.put(JSONObject().put("case", "$label scroll cancellation").put("passed", true))
            reveal(group)
            main { group.layoutDirection = View.LAYOUT_DIRECTION_RTL }; settle()
            val keyboardCandidates = nativeParityOnMain(instrumentation) {
                (0 until group.childCount).map { group.getChildAt(it) to group.getChildAt(it).isFocusableInTouchMode }
            }
            val keyboardEvidence = JSONObject().put("case", "$label synthetic own-root RTL keyboard")
                .put("hardware_keyboard_claim", false)
            try {
                main {
                    check(checkNotNull(group.choiceView(firstId)).left > checkNotNull(group.choiceView(secondId)).left)
                    keyboardEvidence.put("touch_mode_before", group.isInTouchMode)
                    // Direct dispatch bypasses ViewRoot's physical-key touch-mode transition.
                    keyboardCandidates.forEach { (view, _) -> view.isFocusableInTouchMode = true }
                    check(other.requestFocus())
                    check(current.board.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
                    check(current.board.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
                }
                await("$label keyboard") { group.selectedId != targetId }
                main {
                    val beforeNavigation = group.selectedId
                    val focused = group.findFocus()
                    keyboardEvidence.put("focus_before_direction", focused?.id ?: -1)
                    check(current.board.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)))
                    check(current.board.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT)))
                    check(group.findFocus() !== focused && group.selectedId == beforeNavigation)
                    keyboardEvidence.put("focus_after_direction", group.findFocus()?.id ?: -1)
                        .put("touch_mode_after", group.isInTouchMode).put("passed", true)
                }
            } finally {
                main {
                    keyboardCandidates.forEach { (view, previous) -> view.isFocusableInTouchMode = previous }
                    group.layoutDirection = View.LAYOUT_DIRECTION_LTR
                }
                cases.put(keyboardEvidence)
            }
            settle()
            val expandedBox = nativeParityOnMain(instrumentation) { bounds(target) }
            val expandedDown = SystemClock.uptimeMillis()
            val outsideY = expandedBox.top - (48 * context.resources.displayMetrics.density - group.height) / 2 + .5f
            main {
                motion(MotionEvent.ACTION_DOWN, expandedDown, expandedBox.exactCenterX(), outsideY)
                motion(MotionEvent.ACTION_UP, expandedDown, expandedBox.exactCenterX(), outsideY)
            }
            await("$label expanded minimum touch target") { group.selectedId == targetId }
            cases.put(JSONObject().put("case", "$label compact expanded touch").put("passed", true))
            val lowerBox = nativeParityOnMain(instrumentation) { bounds(other) }
            val lowerDown = SystemClock.uptimeMillis()
            val lowerY = lowerBox.bottom + (48 * context.resources.displayMetrics.density - group.height) / 2 - .5f
            main {
                motion(MotionEvent.ACTION_DOWN, lowerDown, lowerBox.exactCenterX(), lowerY)
                motion(MotionEvent.ACTION_UP, lowerDown, lowerBox.exactCenterX(), lowerY)
            }
            await("$label lower expanded gap") { group.selectedId != targetId }
            val outsideDown = SystemClock.uptimeMillis()
            main {
                motion(MotionEvent.ACTION_DOWN, outsideDown, expandedBox.exactCenterX(), expandedBox.exactCenterY())
                motion(MotionEvent.ACTION_UP, outsideDown, expandedBox.exactCenterX(), expandedBox.bottom + 80 * context.resources.displayMetrics.density)
            }
            settle()
            main { check(group.selectedId != targetId) }
            cases.put(JSONObject().put("case", "$label lower inset gap and outside release").put("passed", true))
            main {
                group.isEnabled = false
                check(!target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                check(group.selectedId != targetId)
                group.isEnabled = true
                check(target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            }
            await("$label accessibility activation") { group.selectedId == targetId }
            snapshot("$label-final")
            main { checkRestingContrast(group) }
        }
        // Explicit modes, not Auto's effective mode, provide comparable light/dark evidence.
        for (mode in listOf("LIGHT", "DARK")) {
            val theme = nativeParityOnMain(instrumentation) {
                selectorViews(settings).filterIsInstance<NativeSegmentedChoice>()
                    .first { it.choiceView(mode) != null }
            }
            reveal(theme)
            main { check(checkNotNull(theme.choiceView(mode)).performAccessibilityAction(
                AccessibilityNodeInfo.ACTION_CLICK, null)) }
            await("explicit $mode") { theme.selectedId == mode }
            snapshot("material-${mode.lowercase()}-theme")
            main { checkRestingContrast(theme) }
            val retention = nativeParityOnMain(instrumentation) {
                selectorViews(settings).filterIsInstance<NativeSegmentedChoice>()
                    .first { it.choiceView("time") != null }
            }
            reveal(retention)
            snapshot("material-${mode.lowercase()}-retention")
            main { checkRestingContrast(retention) }
        }
        val retired = nativeParityOnMain(instrumentation) { selectorViews(settings).filterIsInstance<NativeSegmentedChoice>().first().getChildAt(0) }
        main { current.close(); check(!retired.performClick());
            check(!retired.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)); check(current.forbiddenCalls == 0) }
        check(getRecorderPreferences(context).all == original)
        result.put("passed", true)
    } catch (error: Throwable) { failure = error }
    finally {
        failure = captureNativeParityCleanup(failure) { nativeParityOnMain(instrumentation) { render?.close() } }
        failure = captureNativeParityCleanup(failure) { nativeParityOnMain(instrumentation) { host.finish() } }
        failure = captureNativeParityCleanup(failure) { check(getRecorderPreferences(context).all == original) }
        if (failure != null) result.put("passed", false).put("failure", failure!!.stackTraceToString())
        writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray()) }
        report.appendLine("SETTINGS_LIBRARY_SELECTORS_EVIDENCE ${directory.absolutePath}")
    }
    failure?.let { throw it }
    report.appendLine("PASS native_settings_library_selectors isolated actual Settings root bridge")
}

private fun selectorViews(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(selectorViews(view.getChildAt(index)))
}

/** Exact current-UI route; the historical parity launch helper remains unchanged. */
internal fun launchCurrentUiSmokeActivity(instrumentation: Instrumentation): NativeParityActivity {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    return instrumentation.startActivitySync(
        Intent(context, NativeParityActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
    ) as NativeParityActivity
}
