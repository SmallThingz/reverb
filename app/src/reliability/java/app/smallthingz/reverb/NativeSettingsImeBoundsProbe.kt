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
            MainSettingsParityRender(host, false, AppThemeMode.LIGHT, false, null)
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
            // A resized root with a nonzero *unconsumed* inset is diagnostic, not certified safe.
            val poses = listOf(
                Triple("hidden", fullHeight, 0),
                Triple("full_root_ime", fullHeight, ime),
                Triple("hidden_restored", fullHeight, 0),
                Triple("resized_consumed", fullHeight - ime, 0),
                Triple("resized_unconsumed_diagnostic", fullHeight - ime, ime),
            )
            for ((label, height, deliveredIme) in poses) {
                val parentConsumedBottom = label.startsWith("resized_")
                main {
                    current.board.layoutParams = current.board.layoutParams.apply { this.height = height }
                    current.injectWindowInsetsForProbe(injected(deliveredIme, parentConsumedBottom))
                }
                settle(current.board)
                val expectedBottom = if (parentConsumedBottom) 0
                    else maxOf(visual.bottom, deliveredIme)
                main {
                    val entry = JSONObject().put("case", label).put("board_bounds", bounds(current.board).json())
                        .put("root_bounds", bounds(root).json()).put("settings_bounds", bounds(screen).json())
                        .put("list_bounds", bounds(list).json()).put("delivered_ime_bottom", deliveredIme)
                        .put("remaining_local_occlusion", expectedBottom)
                    cases.put(entry)
                    val actualBottom = bounds(root).bottom - bounds(list).bottom
                    entry.put("actual_bottom_gap", actualBottom)
                        .put("bottom_matches", actualBottom == expectedBottom)
                        .put("required", label != "resized_unconsumed_diagnostic")
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
        } finally {
            main {
                current.board.layoutParams = current.board.layoutParams.apply { height = originalHeight }
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
