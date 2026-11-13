package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import app.smallthingz.reverb.nativeui.NativeSettingsScreenContent
import app.smallthingz.reverb.nativeui.SettingsSectionKey
import app.smallthingz.reverb.nativeui.SettingsRetentionInputView
import app.smallthingz.reverb.nativeui.SettingsStorageActionView
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Synthetic delivered-inset/constraint test on the existing mounted Settings root; no picker tap. */
internal fun verifyNativeSettingsImeBounds(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val preferences = getRecorderPreferences(context).all.toMap()
    val directory = File(context.filesDir, "native-settings-ime-bounds-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("selector", "native_settings_ime_bounds").put("passed", false)
        .put("cases", cases).put("real_keyboard_delivery_qualified", false)
        .put("host_focus_policy", "NativeActivityContentView NativeSourceFocusPolicy and full-window placement")
    val host = launchCurrentUiSmokeActivity(instrumentation)
    var render: MainSettingsParityRender? = null
    var failure: Throwable? = null
    fun <T> main(block: () -> T) = nativeParityOnMain(instrumentation, block)
    fun settle(board: View) {
        repeat(3) { awaitNativeParityDraw(instrumentation, board); instrumentation.waitForIdleSync() }
    }
    fun bounds(view: View): Rect {
        val xy = IntArray(2).also(view::getLocationInWindow)
        return Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
    }
    fun Rect.json() = JSONArray(listOf(left, top, right, bottom))
    fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
    try {
        val current = main {
            WindowCompat.setDecorFitsSystemWindows(host.window, false)
            MainSettingsParityRender(host, false, AppThemeMode.LIGHT, false, null, productionWindowFocus = true)
        }.also { render = it }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (!main { current.actionCount(host.getString(R.string.open_settings)) == 1 }) {
            check(SystemClock.uptimeMillis() < deadline); settle(current.board)
        }
        main { current.click(host.getString(R.string.open_settings)) }
        var settings: NativeSettingsScreenContent? = null
        val hydratedDeadline = SystemClock.uptimeMillis() + 5000
        while (settings == null) {
            settings = main { views(current.board).filterIsInstance<NativeSettingsScreenContent>().firstOrNull { it.inputAllowed } }
            check(SystemClock.uptimeMillis() < hydratedDeadline); settle(current.board)
        }
        val screen = checkNotNull(settings)
        val root = main { views(current.board).filterIsInstance<NativeMainScreenView>().single() }
        val list = main { checkNotNull(screen.listViewport) }
        val originalHeight = main { current.board.layoutParams.height }
        val originalTranslationY = main { current.board.translationY }
        val fullHeight = main { current.board.height }
        val originalInsets = main { checkNotNull(ViewCompat.getRootWindowInsets(current.board)) }
        val visual = originalInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = (fullHeight / 3).coerceAtLeast(visual.bottom + 1)
        result.put("initial_root_bounds", main { bounds(root).json() })
            .put("initial_list_bounds", main { bounds(list).json() })
            .put("initial_raw_ime", originalInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            .put("initial_visual_bottom", visual.bottom).put("injected_ime_bottom", ime)
        fun injected(bottom: Int, parentConsumedBottom: Boolean): WindowInsetsCompat {
            val builder = WindowInsetsCompat.Builder(originalInsets)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0)
            if (parentConsumedBottom) {
                for (type in listOf(WindowInsetsCompat.Type.navigationBars(), WindowInsetsCompat.Type.displayCutout())) {
                    val inset = originalInsets.getInsets(type)
                    builder.setInsets(type, Insets.of(inset.left, inset.top, inset.right, 0))
                }
            }
            return builder.build()
        }
        try {
            // All cases exercise production root measurement; API30+ also normalizes pan/resize.
            val pan = minOf(85, ime / 2)
            val poses = listOf(
                Triple("hidden", fullHeight, 0),
                Triple("full_root_ime", fullHeight, ime),
                Triple("full_root_ime_panned", fullHeight, ime),
                Triple("hidden_restored", fullHeight, 0),
                Triple("resized_consumed", fullHeight - ime, 0),
                Triple("resized_unconsumed", fullHeight - ime, ime),
            )
            for ((label, height, deliveredIme) in poses) {
                val parentConsumedBottom = label.startsWith("resized_")
                val panned = label == "full_root_ime_panned"
                main {
                    current.board.layoutParams = current.board.layoutParams.apply { this.height = height }
                    current.board.translationY = originalTranslationY - if (panned) pan.toFloat() else 0f
                    current.injectWindowInsetsForProbe(injected(deliveredIme, parentConsumedBottom))
                }
                settle(current.board)
                val expectedBottom = if (parentConsumedBottom) 0
                    else (maxOf(visual.bottom, deliveredIme) - if (panned) pan else 0).coerceAtLeast(0)
                main {
                    val entry = JSONObject().put("case", label).put("board_bounds", bounds(current.board).json())
                        .put("root_bounds", bounds(root).json()).put("settings_bounds", bounds(screen).json())
                        .put("list_bounds", bounds(list).json()).put("delivered_ime_bottom", deliveredIme)
                        .put("pan_pixels", if (panned) pan else 0)
                        .put("remaining_local_occlusion", expectedBottom)
                    cases.put(entry)
                    val actualBottom = bounds(root).bottom - bounds(list).bottom
                    entry.put("actual_bottom_gap", actualBottom)
                        .put("bottom_matches", actualBottom == expectedBottom)
                        .put("required", android.os.Build.VERSION.SDK_INT >= 30 || (!panned && label != "resized_unconsumed"))
                    val adapter = checkNotNull(list.adapter)
                    val storage = (0 until adapter.itemCount).single {
                        adapter.getItemViewType(it) == SettingsSectionKey.STORAGE.ordinal
                    }
                    (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(storage, 0)
                }
                settle(current.board)
                val choose = main {
                    views(screen).filterIsInstance<SettingsStorageActionView>().single { action ->
                        views(action.visual).any { it.contentDescription == host.getString(R.string.choose_folder) }
                    }.visual.also { check(it.isClickable && it.isEnabled && it.isAttachedToWindow) }
                }
                main { choose.requestRectangleOnScreen(Rect(0, 0, choose.width, choose.height), true) }
                settle(current.board)
                main {
                    val entry = cases.getJSONObject(cases.length() - 1)
                    val target = bounds(choose)
                    val viewport = bounds(list)
                    val visible = Rect()
                    val shown = choose.getGlobalVisibleRect(visible)
                    entry.put("choose_bounds", target.json()).put("list_bounds_after_reveal", viewport.json())
                        .put("choose_visible_rect", visible.json()).put("choose_shown", shown)
                        .put("choose_inside_list", viewport.contains(target))
                        .put("can_scroll_down", list.canScrollVertically(1))
                        .put("choose_inside_unoccluded_root", target.bottom <= bounds(root).bottom - expectedBottom)
                    check(current.forbiddenCalls == 0 && !screen.owner.state.hasUnsavedChanges)
                }
            }
            main {
                current.board.layoutParams = current.board.layoutParams.apply { height = originalHeight }
                current.board.translationY = originalTranslationY
            }
            verifyNativeSettingsImeFocusResize(instrumentation, current, screen, root, originalInsets,
                JSONArray().also { result.put("focused_resize_cases", it) })
        } finally {
            main {
                current.board.layoutParams = current.board.layoutParams.apply { height = originalHeight }
                current.board.translationY = originalTranslationY
                current.restoreWindowInsetsAfterProbe()
            }
        }
        check((0 until cases.length()).all { index ->
            val item = cases.getJSONObject(index)
            !item.getBoolean("required") || (item.getBoolean("bottom_matches") && item.getBoolean("choose_inside_list") && item.getBoolean("choose_inside_unoccluded_root"))
        }) { "Settings IME viewport/bounds mismatch; see retained per-case evidence" }
        val realIme = JSONObject()
        result.put("real_ime", realIme)
        verifyNativeSettingsRealImeBounds(instrumentation, host, current, screen, root, realIme)
        result.put("real_keyboard_delivery_qualified", true)
        result.put("passed", true)
    } catch (error: Throwable) { failure = error }
    finally {
        failure = captureNativeParityCleanup(failure) { main { render?.close() } }
        failure = captureNativeParityCleanup(failure) { main { host.finish() } }
        failure = captureNativeParityCleanup(failure) { check(getRecorderPreferences(context).all == preferences) }
        if (failure != null) result.put("passed", false).put("failure", failure!!.stackTraceToString())
        failure = captureNativeParityCleanup(failure) {
            writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray()) }
        }
        report.appendLine("SETTINGS_IME_BOUNDS_EVIDENCE ${directory.absolutePath}")
    }
    failure?.let { throw it }
    report.appendLine("PASS native_settings_ime_bounds actual mounted root/list/Storage bounds; synthetic and real IME results separated")
}

/** Actual retained editors/RV layout, with synthetic inset frames and the source scroll-cancel signal. */
private fun verifyNativeSettingsImeFocusResize(
    instrumentation: Instrumentation,
    render: MainSettingsParityRender,
    screen: NativeSettingsScreenContent,
    root: NativeMainScreenView,
    originalInsets: WindowInsetsCompat,
    cases: JSONArray,
) {
    fun <T> main(block: () -> T) = nativeParityOnMain(instrumentation, block)
    fun settle() { repeat(3) { awaitNativeParityDraw(instrumentation, render.board); instrumentation.waitForIdleSync() } }
    fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
    fun bounds(view: View): Rect {
        val xy = IntArray(2).also(view::getLocationOnScreen)
        return Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
    }
    fun Rect.json() = JSONArray(listOf(left, top, right, bottom))
    fun inset(bottom: Int) {
        main { render.injectWindowInsetsForProbe(WindowInsetsCompat.Builder(originalInsets)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
            .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0).build()) }
        settle()
    }
    val list = main { checkNotNull(screen.listViewport) }
    val manager = main { list.layoutManager as LinearLayoutManager }
    val retention = main {
        val adapter = checkNotNull(list.adapter)
        (0 until adapter.itemCount).single { adapter.getItemViewType(it) == SettingsSectionKey.RETENTION.ordinal }
    }
    fun placeRetention(bottom: Int) {
        inset(bottom)
        main { manager.scrollToPositionWithOffset(retention, 0) }
        settle()
    }
    placeRetention(0)
    val editors = main { views(screen).filterIsInstance<SettingsRetentionInputView>().filter { it.isEnabled }.toList() }
    check(editors.size >= 2)
    val a = editors[0]
    val b = editors[1]
    val raw = main { editors.map { it.text.toString() } }
    fun focus(editor: View) { main { check(editor.requestFocusFromTouch() && root.findFocus() === editor) }; settle() }
    fun shrink(label: String, cancelled: Boolean = false) {
        val before = main { bounds(a) }
        val selection = main { a.selectionStart to a.selectionEnd }
        val viewport = main { bounds(list) }
        check(viewport.contains(before)) { "$label requires a fully visible editor before shrinking" }
        val protectedBottom = before.bottom - 16
        check(protectedBottom - viewport.top >= before.height())
        val bottom = main { bounds(root).bottom - protectedBottom }
        if (cancelled) main { checkNotNull(list.sourceOnUserScroll).invoke() }
        inset(bottom)
        main {
            val after = bounds(a)
            val afterViewport = bounds(list)
            val retained = a.isAttachedToWindow && root.findFocus() === a
            cases.put(JSONObject().put("case", label).put("cancelled_by_source_user_scroll", cancelled)
                .put("before_editor_bounds", before.json()).put("after_editor_bounds", after.json())
                .put("viewport_bounds", afterViewport.json()).put("retained_focused_editor", retained)
                .put("editor_identity", System.identityHashCode(a)).put("editor_inside", afterViewport.contains(after))
                .put("text_unchanged", editors.map { it.text.toString() } == raw)
                .put("selection_unchanged", (a.selectionStart to a.selectionEnd) == selection))
            check(retained && editors.map { it.text.toString() } == raw && (a.selectionStart to a.selectionEnd) == selection)
            if (cancelled) check(after == before && !afterViewport.contains(after))
            else check(afterViewport.contains(after))
        }
    }
    var failure: Throwable? = null
    try {
        focus(a)
        shrink("first_shrink")
        shrink("successive_shrink")
        shrink("user_scroll_cancels", cancelled = true)
        // Grow without hiding IME: only the A -> B -> A focus change can reset cancellation.
        placeRetention(1)
        focus(b); focus(a)
        main { manager.scrollToPositionWithOffset(retention, 0) }; settle()
        shrink("focus_a_b_a_resets")
        main { checkNotNull(list.sourceOnUserScroll).invoke() }
        // Hide/show with the same focus starts a new reveal epoch, without rebinding the editor.
        placeRetention(0)
        main { check(root.findFocus() === a) }
        shrink("hidden_shown_same_focus_resets")
    } catch (error: Throwable) { failure = error }
    finally {
        failure = captureNativeParityCleanup(failure) { inset(0) }
        failure = captureNativeParityCleanup(failure) { main { a.clearFocus() } }
    }
    failure?.let { throw it }
}
