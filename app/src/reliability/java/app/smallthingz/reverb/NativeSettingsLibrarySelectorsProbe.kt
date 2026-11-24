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
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.pow
import app.smallthingz.reverb.nativeui.NativeSettingsScreenContent
import app.smallthingz.reverb.nativeui.QuickTileInlineView
import app.smallthingz.reverb.nativeui.SettingsCalibrationActionView
import app.smallthingz.reverb.nativeui.SettingsRetentionInputView
import app.smallthingz.reverb.nativeui.segmentedPalette
import smallthingz.ui.components.NativeChoice
import smallthingz.ui.components.NativeChip
import smallthingz.ui.components.NativeDurationWheelView
import smallthingz.ui.components.NativeModalWindowRuntime
import smallthingz.ui.components.NativeSegmentedChoice
import smallthingz.ui.components.NativeSegmentedControl
import smallthingz.ui.reverb.settings.ReverbRetentionSection
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
            val environment = settings.pointerEnvironment
            val palette = segmentedPalette(environment)
            val tiny = environment.appearance.settingsSelectors.createSegmentedControl(context, palette).apply {
                onSelectionRequest = { error("Geometry fixture must not activate") }
                bind(listOf(NativeChoice("time", "Time"), NativeChoice("size", "Size")), "time", palette)
            }
            try {
                val inset = (4 * context.resources.displayMetrics.density).roundToInt()
                for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                    tiny.layoutDirection = direction
                    for (width in listOf(0, 1, 2 * inset - 1, 2 * inset, 2 * inset + 1, 200)) {
                        tiny.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        tiny.layout(0, 0, width, tiny.measuredHeight)
                        val choices = selectorViews(tiny).filterIsInstance<NativeSegmentedChoice>().single()
                        val fittedInset = minOf(inset, (width - 1).coerceAtLeast(0) / 2)
                        check(choices.width == choices.measuredWidth && choices.width == width - 2 * fittedInset)
                        if (width > 0) {
                            check(choices.width > 0)
                            for (index in 0 until choices.childCount) check(choices.getChildAt(index).width > 0)
                        }
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
            // The retained group paints the selection. Sample the straight edge above the label.
            val bitmap = android.graphics.Bitmap.createBitmap(group.width, group.height,
                android.graphics.Bitmap.Config.ARGB_8888)
            val density = context.resources.displayMetrics.density
            try {
                group.draw(android.graphics.Canvas(bitmap))
                val x = selected.left + selected.width / 2
                val edgeY = selected.top + maxOf(1, (density / 2).toInt())
                val fillY = selected.top + (3f * density).roundToInt()
                val fill = bitmap.getPixel(x, fillY)
                val edge = bitmap.getPixel(x, edgeY)
                check(fill == palette.primary && fill == palette.settingsSelection && fill ushr 24 == 255) {
                    "Selected fill must be the opaque primary role"
                }
                check(edge == fill) { "Selected edge differs from its solid fill" }
                check(contrast(selected.currentTextColor, fill) >= 4.5)
                cases.put(JSONObject().put("case", "actual selected solid paint without outline")
                    .put("dark", current.effectiveDark).put("foreground", selected.currentTextColor)
                    .put("fill", fill).put("edge", edge).put("selected_outlined", false).put("passed", true))
            } finally { bitmap.recycle() }
            check(group.height == (34 * density).roundToInt())
        }
        for (label in listOf("theme", "retention")) {
            lateinit var group: NativeSegmentedChoice
            await("$label group") {
                selectorViews(settings).filterIsInstance<NativeSegmentedChoice>()
                    .firstOrNull { if (label == "theme") it.choiceView("LIGHT") != null else it.isRetentionSelector() }
                    ?.also { group = it } != null
            }
            reveal(group)
            snapshot("$label-before")
            main { checkRestingContrast(group) }
            val (first, second) = nativeParityOnMain(instrumentation) {
                if (label == "theme") checkNotNull(group.choiceView("LIGHT")) to checkNotNull(group.choiceView("DARK"))
                else (group.getChildAt(0) as NativeChip) to (group.getChildAt(1) as NativeChip)
            }
            val (target, other) = nativeParityOnMain(instrumentation) {
                if (second.isChecked) first to second else second to first
            }
            main {
                val density = context.resources.displayMetrics.density
                check(group.height == (34 * density).roundToInt())
                val adapter = generateSequence(group.parent as? View) { it.parent as? View }
                    .filterIsInstance<NativeSegmentedControl>().first()
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
            main { check(!target.isChecked); motion(MotionEvent.ACTION_UP, down, box.exactCenterX(), box.exactCenterY()) }
            await("$label held release") { target.isChecked }
            snapshot("$label-after-selection")
            main { checkRestingContrast(group) }
            cases.put(JSONObject().put("case", "$label held release, radio semantics, 34/42dp geometry").put("passed", true))
            val otherBox = nativeParityOnMain(instrumentation) { bounds(other) }
            val dragDown = SystemClock.uptimeMillis()
            main {
                motion(MotionEvent.ACTION_DOWN, dragDown, otherBox.exactCenterX(), otherBox.exactCenterY())
                motion(MotionEvent.ACTION_MOVE, dragDown, otherBox.exactCenterX(), otherBox.exactCenterY() - 100)
                motion(MotionEvent.ACTION_UP, dragDown, otherBox.exactCenterX(), otherBox.exactCenterY() - 100)
            }; settle()
            main { check(target.isChecked) }
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
                    check(first.left > second.left)
                    keyboardEvidence.put("touch_mode_before", group.isInTouchMode)
                    // Direct dispatch bypasses ViewRoot's physical-key touch-mode transition.
                    keyboardCandidates.forEach { (view, _) -> view.isFocusableInTouchMode = true }
                    check(other.requestFocus())
                    check(current.board.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
                    check(current.board.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
                }
                await("$label keyboard") { !target.isChecked }
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
            await("$label expanded minimum touch target") { target.isChecked }
            cases.put(JSONObject().put("case", "$label compact expanded touch").put("passed", true))
            val lowerBox = nativeParityOnMain(instrumentation) { bounds(other) }
            val lowerDown = SystemClock.uptimeMillis()
            val lowerY = lowerBox.bottom + (48 * context.resources.displayMetrics.density - group.height) / 2 - .5f
            main {
                motion(MotionEvent.ACTION_DOWN, lowerDown, lowerBox.exactCenterX(), lowerY)
                motion(MotionEvent.ACTION_UP, lowerDown, lowerBox.exactCenterX(), lowerY)
            }
            await("$label lower expanded gap") { !target.isChecked }
            val outsideDown = SystemClock.uptimeMillis()
            main {
                motion(MotionEvent.ACTION_DOWN, outsideDown, expandedBox.exactCenterX(), expandedBox.exactCenterY())
                motion(MotionEvent.ACTION_UP, outsideDown, expandedBox.exactCenterX(), expandedBox.bottom + 80 * context.resources.displayMetrics.density)
            }
            settle()
            main { check(!target.isChecked) }
            cases.put(JSONObject().put("case", "$label lower inset gap and outside release").put("passed", true))
            main {
                group.isEnabled = false
                check(!target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                check(!target.isChecked)
                group.isEnabled = true
                check(target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            }
            await("$label accessibility activation") { target.isChecked }
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
                    .first { it.isRetentionSelector() }
            }
            reveal(retention)
            snapshot("material-${mode.lowercase()}-retention")
            main { checkRestingContrast(retention) }
        }
        val retention = nativeParityOnMain(instrumentation) {
            selectorViews(settings).filterIsInstance<ReverbRetentionSection>().single()
        }
        reveal(retention)
        main {
            val modeChips = selectorViews(retention).filterIsInstance<NativeSegmentedChoice>().single()
            cases.put(verifyCurrentTileEditorGeometry(host, current.board, settings, modeChips))
            cases.put(verifyCurrentRetentionModeDrafts(host, settings, retention, modeChips))
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

/** Synthetic InputConnection commands against the actual Settings draft editor on the QA device. */
private fun verifyCurrentRetentionModeDrafts(
    host: NativeParityActivity,
    settings: NativeSettingsScreenContent,
    retention: ReverbRetentionSection,
    chips: NativeSegmentedChoice,
): JSONObject {
    val editor = selectorViews(retention).filterIsInstance<SettingsRetentionInputView>()
        .single { it.contentDescription == host.getString(R.string.buffer_one_shot) }
    fun select(mode: RetentionMode) {
        val chip = chips.getChildAt(if (mode == RetentionMode.TIME) 0 else 1) as NativeChip
        if (!chip.isChecked) check(chip.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        check(chip.isChecked && settings.owner.snapshot.retentionMode == mode)
    }
    fun replace(connection: InputConnection, text: String) {
        check(connection.setSelection(0, editor.length()))
        check(connection.commitText(text, 1))
    }

    select(RetentionMode.TIME)
    val time = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
    replace(time, "1:23")
    check(time.setSelection(1, 2) && time.setComposingRegion(0, 1))
    check(time.beginBatchEdit() && time.commitText("queued Time command", 1))

    select(RetentionMode.SIZE)
    check(!time.endBatchEdit() && !time.commitText("stale Time command", 1))
    val size = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
    replace(size, "384.50")
    check(size.setSelection(2, 5) && size.setComposingRegion(3, 4))

    select(RetentionMode.TIME)
    check(editor.text.toString() == "1:23" && editor.selectionStart == 1 && editor.selectionEnd == 2)
    check(BaseInputConnection.getComposingSpanStart(editor.editableText) == 0 &&
        BaseInputConnection.getComposingSpanEnd(editor.editableText) == 1)
    select(RetentionMode.SIZE)
    check(editor.text.toString() == "384.50" && editor.selectionStart == 2 && editor.selectionEnd == 5)
    check(BaseInputConnection.getComposingSpanStart(editor.editableText) == 3 &&
        BaseInputConnection.getComposingSpanEnd(editor.editableText) == 4)
    val drafts = settings.owner.state.displayed
    check(drafts.oneShotTimeText == "1:23" && drafts.oneShotSizeText == "384.50")
    return JSONObject().put("case", "actual_retention_drafts_preserve_selection_and_reject_stale_ime_batch")
        .put("time", drafts.oneShotTimeText).put("size", drafts.oneShotSizeText).put("passed", true)
}

/** The current inline editor with synthetic capacity, so geometry checks have no tile side effects. */
private fun verifyCurrentTileEditorGeometry(
    host: NativeParityActivity,
    board: View,
    settings: NativeSettingsScreenContent,
    retentionChips: NativeSegmentedChoice,
): JSONObject {
    val runtime = NativeModalWindowRuntime(host)
    val target = QuickSettingsTileEditorTarget()
    val editor = QuickSettingsTilesEditorState().apply { this.target = target }
    val owner = NativeQuickTileInlineOwner(host, host.lifecycle, runtime.scope, editor,
        target, NativeQuickTilesInputs(true, true, true, 1L), { 1L }, { 1L },
        { error("Tile geometry must not dispatch an application action") })
    val environment = settings.pointerEnvironment
    val tile = QuickTileInlineView(host, owner, host.lifecycle, environment, { board })
    try {
        val request = checkNotNull(owner.capacityLoad.begin(1L))
        check(owner.capacityLoad.finish(request, 1L, TileCapacities(3600, 120.0, 120.0)))
        tile.bind(environment)
        tile.measure(View.MeasureSpec.makeMeasureSpec(board.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        tile.layout(0, 0, tile.measuredWidth, tile.measuredHeight)
        val selector = selectorViews(tile).filterIsInstance<NativeSegmentedControl>().single()
        val tileChips = selectorViews(selector).filterIsInstance<NativeSegmentedChoice>().single()
        val wheel = selectorViews(tile).filterIsInstance<NativeDurationWheelView>().single()
        val actions = selectorViews(tile).filterIsInstance<SettingsCalibrationActionView>().toList()
        val cancel = actions.single { it.textView.getText() == host.getString(R.string.cancel) }
        val save = actions.single { it.textView.getText() == host.getString(R.string.tiles_ui_save) }
        val inset = (environment.appearance.quickTiles.editorInsetDp * environment.textDensity.density).roundToInt()
        check(tileChips.choiceStyle == retentionChips.choiceStyle)
        check(selector.top == inset && selector.bottom < wheel.top)
        check(wheel.bottom < minOf(cancel.top, save.top) && maxOf(cancel.bottom, save.bottom) == tile.height - inset)
        return JSONObject().put("case", "actual_tile_editor_shared_capsule_at_top_and_actions_at_bottom")
            .put("capsule_top_px", selector.top).put("wheel_top_px", wheel.top)
            .put("footer_bottom_px", maxOf(cancel.bottom, save.bottom)).put("passed", true)
    } finally {
        try { owner.close() } finally {
            try { tile.close() } finally { runtime.close() }
        }
    }
}

private fun NativeSegmentedChoice.isRetentionSelector(): Boolean =
    generateSequence(parent as? View) { it.parent as? View }.any { it is ReverbRetentionSection }

private fun selectorViews(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(selectorViews(view.getChildAt(index)))
}

/** Real Android measurement only: no window input, screenshot, or animation claim. */
internal fun verifyNativeSegmentedPadding(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-segmented-padding-current-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("selector", "native_segmented_padding_current").put("passed", false)
        .put("scope", "Unattached real Views; minimum horizontal content inset, text fit, reflow and retained selection")
        .put("cases", cases)
    try {
        nativeParityOnMain(instrumentation) {
            val palette = smallthingz.ui.controls.ControlPalette(-1, -1, 0xff202020.toInt(), 0xff606060.toInt(),
                0xff3458a0.toInt(), -1, 0xff3458a0.toInt(), 0xffaaaaaa.toInt())
            val default = smallthingz.ui.components.NativeSegmentedStyle.Default
            val styles = mapOf("default" to default, "undersized-overrides" to default.copy(
                choiceStyle = default.choiceStyle.copy(horizontalPaddingDp = 2f, startPaddingDp = 0f, endPaddingDp = 1f)),
                "horizontal-fallback" to default.copy(choiceStyle = default.choiceStyle.copy(horizontalPaddingDp = 2f)),
                "mixed-edges" to default.copy(choiceStyle = default.choiceStyle.copy(horizontalPaddingDp = 2f, endPaddingDp = 20f)),
                "larger-overrides" to default.copy(choiceStyle = default.choiceStyle.copy(
                    horizontalPaddingDp = 18f, startPaddingDp = 20f, endPaddingDp = 16f)))
            val unconstrained = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            for (dpi in listOf(160, 421)) for (scale in listOf(1f, 2f))
                for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                val configured = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                    densityDpi = dpi; fontScale = scale
                    setLocales(android.os.LocaleList(if (scale == 1f) java.util.Locale.ENGLISH else java.util.Locale.GERMAN))
                })
                val density = smallthingz.ui.unit.Density(configured)
                fun dp(value: Int) = (value * density.density).roundToInt()
                val resolver = smallthingz.ui.text.font.createFontFamilyResolver(configured)
                for ((name, style) in styles) {
                    val selector = smallthingz.ui.components.NativeSettingsSelectorStyle(segmented = style).createThemeSelector(
                        configured, palette, smallthingz.ui.components.NativeThemeMode.SYSTEM, density, resolver,
                    ) { error("Measurement must not request selection") }
                    try {
                        // Unattached Views have no ViewRoot to resolve inherited direction.
                        selectorViews(selector).forEach { it.layoutDirection = direction }
                        val group = selectorViews(selector).filterIsInstance<NativeSegmentedChoice>().single()
                        val children = (0 until group.childCount).map { group.getChildAt(it) as NativeChip }
                        for (widthDp in listOf(120, 360, 600)) {
                            val entry = JSONObject().put("dpi", dpi).put("font_scale", scale).put("direction", direction)
                                .put("style", name).put("width_dp", widthDp).put("passed", false)
                            cases.put(entry)
                            selector.measure(View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY), unconstrained)
                            selector.layout(0, 0, selector.measuredWidth, selector.measuredHeight)
                            val geometry = JSONArray()
                            entry.put("cells", geometry)
                            for (child in children) {
                                val text = checkNotNull(child.layout)
                                geometry.put(JSONObject().put("text", child.text).put("width", child.width).put("height", child.height)
                                    .put("left_padding", child.paddingLeft).put("right_padding", child.paddingRight)
                                    .put("text_width", text.width).put("lines", text.lineCount))
                                check(child.paddingLeft >= dp(12) && child.paddingRight >= dp(12))
                                if (name == "larger-overrides") check(child.paddingStart == dp(20) && child.paddingEnd == dp(16))
                                if (name == "mixed-edges") check(child.paddingStart == dp(12) && child.paddingEnd == dp(20))
                                check(child.paddingTop == 0 && child.paddingBottom == 0)
                                val available = child.width - child.compoundPaddingLeft - child.compoundPaddingRight
                                check(available > 0 && text.width <= available)
                                check((0 until text.lineCount).all { text.getLineWidth(it) <= available + 1 && text.getEllipsisCount(it) == 0 })
                                check(text.getLineEnd(text.lineCount - 1) == child.text.length)
                                check(text.height <= child.height - child.compoundPaddingTop - child.compoundPaddingBottom)
                            }
                            if (widthDp == 120) check(children.map { it.top }.distinct().size > 1)
                            if (widthDp == 600 && scale == 1f) {
                                check(children.map { it.top }.distinct().size == 1)
                                check(children.all { it.height == dp(34) })
                                check((selector.getChildAt(1) as NativeSegmentedControl).height == dp(42))
                            }
                            entry.put("passed", true)
                        }
                        selector.bind(smallthingz.ui.components.NativeThemeMode.DARK, palette, density, resolver)
                        check(children.indices.all { children[it] === group.getChildAt(it) })
                        check(children.last().isChecked)
                    } finally { selector.close() }
                }
                // Recreate the previous zero-padding cell without the capsule's new floor.
                val old = NativeChip(configured, palette, default.choiceStyle.copy(horizontalPaddingDp = 0f))
                try {
                    old.bind("WWWWWW", true)
                    old.measure(unconstrained, unconstrained)
                    check(old.paddingLeft < dp(12) && old.paddingRight < dp(12))
                    check(old.measuredWidth < checkNotNull(old.layout).getLineWidth(0) + 2 * dp(12))
                } finally { old.close() }
            }
        }
        result.put("passed", true).put("old_zero_padding_counterexamples_rejected", 8)
    } catch (error: Throwable) {
        result.put("failure", error.stackTraceToString()); throw error
    } finally {
        writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray()) }
    }
    report.appendLine("PASS native_segmented_padding_current ${cases.length()} geometry cases; evidence ${directory.absolutePath}")
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
