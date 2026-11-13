package app.smallthingz.reverb

import android.app.Instrumentation
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import app.smallthingz.reverb.nativeui.NativeSettingsScreenContent
import app.smallthingz.reverb.nativeui.SettingsRetentionInputView
import app.smallthingz.reverb.nativeui.SettingsSectionKey
import app.smallthingz.reverb.nativeui.SettingsStorageActionView
import org.json.JSONArray
import org.json.JSONObject

/** One real docked-IME show/hide on the already-mounted Settings editor; no typing or picker. */
internal fun verifyNativeSettingsRealImeBounds(
    instrumentation: Instrumentation,
    host: NativeParityActivity,
    render: MainSettingsParityRender,
    screen: NativeSettingsScreenContent,
    root: NativeMainScreenView,
    result: JSONObject,
) {
    check(Build.VERSION.SDK_INT >= 30) { "Actual window-metrics IME qualification requires API30+" }
    fun <T> main(block: () -> T) = nativeParityOnMain(instrumentation, block)
    fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
    fun bounds(view: View): Rect {
        val xy = IntArray(2).also(view::getLocationOnScreen)
        return Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
    }
    fun Rect.json() = JSONArray(listOf(left, top, right, bottom))
    fun settle() { awaitNativeParityDraw(instrumentation, render.board); instrumentation.waitForIdleSync() }
    fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var previous: List<Any?>? = null
        var stable = 0
        while (stable < 3) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out: $label" }
            val geometry = main {
                val list = screen.listViewport
                listOf(root.width, root.height, list?.width, list?.height, list?.top, list?.bottom,
                    render.lastRealWindowInsetsForProbe?.getInsets(WindowInsetsCompat.Type.ime())?.bottom,
                    render.realWindowInsetsRevisionForProbe)
            }
            stable = if (main(condition) && geometry == previous) stable + 1 else 0
            previous = geometry
            if (stable >= 3) break
            settle()
        }
    }
    val snapshots = JSONArray()
    result.put("passed", false).put("snapshots", snapshots)
        .put("scope", "Existing visible numeric editor; ordinary focus/show/hide; no text mutation")
    val list = main { checkNotNull(screen.listViewport) }
    val baseline = main { screen.owner.snapshot }
    main {
        check(host.hasWindowFocus() && screen.inputAllowed)
        val adapter = checkNotNull(list.adapter)
        val retention = (0 until adapter.itemCount).single {
            adapter.getItemViewType(it) == SettingsSectionKey.RETENTION.ordinal
        }
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(retention, 0)
    }
    await("attached retention editor") { views(screen).filterIsInstance<SettingsRetentionInputView>().any { it.isShown && it.isEnabled } }
    val editor = main { views(screen).filterIsInstance<SettingsRetentionInputView>().first { it.isShown && it.isEnabled } }
    main { editor.requestRectangleOnScreen(Rect(0, 0, editor.width, editor.height), true) }
    await("visible retention editor") { editor.getGlobalVisibleRect(Rect()) && !list.isLayoutRequested }
    val raw = main { editor.text.toString() }
    val input = host.getSystemService(InputMethodManager::class.java)
    fun snapshot(label: String): JSONObject = main {
        check(host.hasWindowFocus() && root.isAttachedToWindow)
        val delivered = checkNotNull(render.lastRealWindowInsetsForProbe)
        val metrics = host.windowManager.currentWindowMetrics
        val protectedInsets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        val rootBounds = bounds(root)
        val listBounds = bounds(list)
        val visibleWindowFrame = Rect().also(root::getWindowVisibleDisplayFrame)
        val expectedListBottom = minOf(rootBounds.bottom, metrics.bounds.bottom - protectedInsets.bottom)
        val retained = views(screen).filterIsInstance<SettingsRetentionInputView>().any { it === editor }
        val choose = views(screen).filterIsInstance<SettingsStorageActionView>().firstOrNull { action ->
            views(action.visual).any { it.contentDescription == host.getString(R.string.choose_folder) }
        }?.visual
        JSONObject().put("label", label).put("real_insets_revision", render.realWindowInsetsRevisionForProbe)
            .put("delivered_ime_bottom", delivered.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            .put("delivered_ime_visible", delivered.isVisible(WindowInsetsCompat.Type.ime()))
            .put("window_bounds", metrics.bounds.json()).put("window_protected_bottom", protectedInsets.bottom)
            .put("window_visible_frame", visibleWindowFrame.json())
            .put("decor_bounds", bounds(host.window.decorView).json()).put("root_bounds", rootBounds.json())
            .put("list_bounds", listBounds.json()).put("expected_list_bottom", expectedListBottom)
            .put("list_bottom_matches_remaining_occlusion", listBounds.bottom == expectedListBottom)
            .put("editor_identity", System.identityHashCode(editor)).put("editor_retained", retained)
            .put("editor_bounds", bounds(editor).json()).put("editor_focused", editor.hasFocus())
            .put("editor_inside_list", listBounds.contains(bounds(editor)))
            .put("editor_visible", editor.getGlobalVisibleRect(Rect())).put("raw_text", editor.text.toString())
            .put("selection_start", editor.selectionStart).put("selection_end", editor.selectionEnd)
            .put("current_focus_identity", root.findFocus()?.let(System::identityHashCode) ?: JSONObject.NULL)
            .put("choose_bounds_if_attached", choose?.let { bounds(it).json() } ?: JSONObject.NULL)
            .put("choose_attached", choose?.isAttachedToWindow == true).also { snapshots.put(it) }
    }
    await("real hidden baseline") { render.lastRealWindowInsetsForProbe?.isVisible(WindowInsetsCompat.Type.ime()) == false }
    snapshot("before_focus")
    var failure: Throwable? = null
    var focusedSelection: Pair<Int, Int>? = null
    val beforeShowRevision = main { render.realWindowInsetsRevisionForProbe }
    try {
        main {
            check(host.hasWindowFocus() && editor.isEnabled && editor.isFocusable)
            check(editor.requestFocusFromTouch() && root.findFocus() === editor)
            focusedSelection = editor.selectionStart to editor.selectionEnd
            result.put("focused_selection_before_show", JSONArray(listOf(editor.selectionStart, editor.selectionEnd)))
            result.put("show_requested", input.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT))
        }
        await("focused editor and visible docked IME") {
            val delivered = render.lastRealWindowInsetsForProbe
            render.realWindowInsetsRevisionForProbe > beforeShowRevision && editor.hasFocus() &&
                delivered?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                delivered.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0 && !root.isLayoutRequested && !list.isLayoutRequested
        }
        val shown = snapshot("shown")
        check(shown.getBoolean("editor_retained") && shown.getBoolean("editor_visible") && shown.getBoolean("editor_inside_list"))
        main { check(editor.text.toString() == raw && (editor.selectionStart to editor.selectionEnd) == focusedSelection) }
        check(shown.getBoolean("list_bottom_matches_remaining_occlusion")) { "Actual IME root/list geometry double-deducts or overlaps; see evidence" }
    } catch (error: Throwable) { failure = error }
    finally {
        failure = captureNativeParityCleanup(failure) {
            val hideBoundary = main {
                render.realWindowInsetsRevisionForProbe to
                    (render.lastRealWindowInsetsForProbe?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            }
            main { input.hideSoftInputFromWindow(editor.windowToken ?: host.window.decorView.windowToken, 0) }
            await("own IME hidden") {
                render.lastRealWindowInsetsForProbe?.isVisible(WindowInsetsCompat.Type.ime()) == false &&
                    (!hideBoundary.second || render.realWindowInsetsRevisionForProbe > hideBoundary.first) &&
                    !root.isLayoutRequested && !list.isLayoutRequested
            }
            val hidden = snapshot("hidden")
            check(hidden.getBoolean("list_bottom_matches_remaining_occlusion"))
            if (focusedSelection != null) check(hidden.getBoolean("editor_retained") && hidden.getBoolean("editor_focused") && hidden.getBoolean("editor_visible") && hidden.getBoolean("editor_inside_list"))
            main {
                check(editor.text.toString() == raw && screen.owner.snapshot == baseline && !screen.owner.state.hasUnsavedChanges)
                if (focusedSelection != null) check((editor.selectionStart to editor.selectionEnd) == focusedSelection)
            }
        }
    }
    failure?.let { throw it }
    result.put("passed", true)
}
