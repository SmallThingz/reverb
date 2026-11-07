package app.smallthingz.reverb

import android.app.Instrumentation
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.smallthingz.reverb.nativeui.*
import org.json.JSONObject

/** Production private boundary listener on its real paragraph, inside the actual RecyclerView parent.
 * Reflection constructs that small presentation owner only; no player, file, trim save, or fake key handler. */
internal fun verifyLibraryBoundaryKeyboard(instrumentation: Instrumentation, host: NativeParityActivity,
    environment: ReverbViewEnvironment, result: JSONObject) {
    var viewport: ReverbLibraryRecyclerView? = null
    var failure: Throwable? = null
    try {
        val list = nativeParityOnMain(instrumentation) {
            ReverbLibraryRecyclerView(host).also {
                viewport = it
                it.layoutManager = LinearLayoutManager(host)
                host.fixtureRoot.addView(it, FrameLayout.LayoutParams(-1, -1))
            }
        }
        for (target in listOf(InlineFineSeekTarget.TRIM_START, InlineFineSeekTarget.TRIM_END)) {
            var state = LibraryPlayerUiState("qa-keyboard", true, false, 10000, 0, true, false,
                1000, 9000, target, null, true, false, 0f, 0f)
            var edits = 0
            lateinit var label: ReverbParagraphView
            lateinit var bind: () -> Unit
            nativeParityOnMain(instrumentation) {
                label = environment.paragraph(host, "QA trim boundary", ReverbTextRole.BODY_MEDIUM,
                    environment.palette.ink)
                val type = Class.forName("app.smallthingz.reverb.nativeui.PlayerBoundary")
                val owner = type.getDeclaredConstructor(ReverbParagraphView::class.java,
                    InlineFineSeekTarget::class.java).apply { isAccessible = true }.newInstance(label, target)
                val method = type.getDeclaredMethod("bind", LibraryPlayerUiState::class.java,
                    LibraryPlayerActions::class.java).apply { isAccessible = true }
                val actions = LibraryPlayerActions({ _, _ -> }, { _, _ -> }, {}, {}, { _, requested ->
                    edits++
                    val value = requested.toInt().coerceIn(checkNotNull(inlineTrimBoundaryLimits(state.durationMillis,target)))
                    val previous = if (target == InlineFineSeekTarget.TRIM_START) state.trimStartMillis else state.trimEndMillis
                    state = if (target == InlineFineSeekTarget.TRIM_START) state.copy(trimStartMillis=value)
                        else state.copy(trimEndMillis=value)
                    bind()
                    previous != value
                }, {}, {})
                bind = { method.invoke(owner, state, actions); Unit }
                bind()
                list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = 30
                    override fun getItemViewType(position: Int) = if (position == 10) 1 else 0
                    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
                        val row = FrameLayout(host)
                        row.layoutParams = RecyclerView.LayoutParams(-1, 240)
                        if (type == 1) row.addView(label, FrameLayout.LayoutParams(-1,-1))
                        return object : RecyclerView.ViewHolder(row) {}
                    }
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
                }
                (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(10,0)
            }
            repeat(3) { awaitNativeParityDraw(instrumentation,host.fixtureRoot) }
            nativeParityOnMain(instrumentation) { check(label.requestFocusFromTouch()) }
            awaitNativeParityDraw(instrumentation,host.fixtureRoot)
            val layout = list.layoutManager as LinearLayoutManager
            fun anchor(): Pair<Int,Int> {
                val first = layout.findFirstVisibleItemPosition()
                return first to layout.getDecoratedTop(checkNotNull(layout.findViewByPosition(first)))
            }
            val before = nativeParityOnMain(instrumentation) { anchor() }
            check(before.first > 0)
            val limits = checkNotNull(inlineTrimBoundaryLimits(state.durationMillis,target))
            for ((key,expected) in listOf(KeyEvent.KEYCODE_MOVE_HOME to limits.first,
                KeyEvent.KEYCODE_MOVE_HOME to limits.first, KeyEvent.KEYCODE_MOVE_END to limits.last,
                KeyEvent.KEYCODE_MOVE_END to limits.last)) {
                nativeParityOnMain(instrumentation) {
                    check(label.hasFocus() && host.hasWindowFocus())
                    val previousEdits = edits
                    check(host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,key)))
                    check(edits == previousEdits + 1)
                    check((if (target == InlineFineSeekTarget.TRIM_START) state.trimStartMillis else state.trimEndMillis) == expected)
                    check(host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,key)))
                    check(edits == previousEdits + 1) { "Key-up edited trim twice" }
                    check(!layout.isSmoothScrolling) { "Boundary key started Library scrolling" }
                }
                repeat(2) { awaitNativeParityDraw(instrumentation,host.fixtureRoot) }
                nativeParityOnMain(instrumentation) {
                    check(anchor() == before && label.hasFocus() && label.getGlobalVisibleRect(Rect()))
                }
            }
            nativeParityOnMain(instrumentation) {
                state = state.copy(boundaryInputEnabled=false)
                bind()
                check(!label.isFocusable && !label.hasFocus())
                val previousEdits = edits
                for (action in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP))
                    host.dispatchKeyEvent(KeyEvent(action,KeyEvent.KEYCODE_MOVE_HOME))
                check(edits == previousEdits) { "Disabled boundary accepted a keyboard edit" }
                list.stopScroll()
                list.adapter = null
            }
        }
        result.put("trim_boundary_window_key_pairs", true)
    } catch (error: Throwable) { failure = error }
    finally {
        failure = captureNativeParityCleanup(failure) { nativeParityOnMain(instrumentation) {
            viewport?.let { it.stopScroll(); it.adapter=null; host.fixtureRoot.removeView(it) }
        } }
    }
    failure?.let { throw it }
}
