package app.smallthingz.reverb

import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.inspector.WindowInspector
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import app.smallthingz.reverb.nativeui.ReverbParagraphView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Diagnostic only, on the existing guarded own popup and main thread. It does not admit input,
 * remeasure a View, change styles, or alter pixel comparison. GetTextLayoutResult is the source's
 * read-only semantics reconstruction, not proof that its MultiParagraph is the draw-path cache.
 */
internal fun incidentMenuTextGeometry(host: NativeParityActivity, expectedWindow: Int): JSONObject {
    val result = JSONObject().put("uptime_ms", SystemClock.uptimeMillis())
        .put("expected_window_id", expectedWindow).put("complete", false)
    try {
        val root = WindowInspector.getGlobalWindowViews().filter {
            it.isAttachedToWindow && it.isShown && it.hasWindowFocus()
        }.single()
        check(root.context.applicationInfo.uid == Process.myUid() &&
            root.context.packageName == host.packageName && root.display?.displayId == 0)
        val info = checkNotNull(root.createAccessibilityNodeInfo())
        try { check(info.windowId == expectedWindow) }
        finally { @Suppress("DEPRECATION") info.recycle() }
        result.put("root", incidentTextViewGeometry(root))
        val labels = listOf(host.getString(R.string.incident_copy), host.getString(R.string.delete_recording))
        val views = onboardingViewDescendants(root).toList()
        val sourceRoots = views.filterIsInstance<ViewRootForTest>()
        val texts = JSONArray().also { result.put("texts", it) }
        if (sourceRoots.isNotEmpty()) {
            val source = sourceRoots.single()
            val nodes = source.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
            for (label in labels) {
                val entry = JSONObject().put("text", label).also(texts::put)
                val candidates = nodes.filter { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == label
                }
                entry.put("matches", candidates.size)
                val node = candidates.single()
                entry.put("node_id", node.id).put("bounds_root", node.boundsInRoot.toString())
                    .put("bounds_window", node.boundsInWindow.toString())
                    .put("size", "${node.size.width}x${node.size.height}")
                    .put("placed", node.layoutInfo.isPlaced)
                val parents = JSONArray().also { entry.put("semantic_ancestors", it) }
                var parent = node.parent
                while (parent != null) {
                    parents.put(JSONObject().put("id", parent.id).put("bounds_root", parent.boundsInRoot.toString())
                        .put("bounds_window", parent.boundsInWindow.toString())
                        .put("size", "${parent.size.width}x${parent.size.height}"))
                    parent = parent.parent
                }
                val layouts = mutableListOf<TextLayoutResult>()
                check(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
                val text = layouts.single()
                entry.put("style", text.layoutInput.style.toString())
                    .put("density", text.layoutInput.density.density.toDouble())
                    .put("font_scale", text.layoutInput.density.fontScale.toDouble())
                    .put("direction", text.layoutInput.layoutDirection.toString())
                    .put("constraints", text.layoutInput.constraints.toString())
                    .put("layout_size", "${text.size.width}x${text.size.height}")
                    .put("paragraph", incidentParagraphGeometry(text.multiParagraph.width, text.multiParagraph.height,
                        text.multiParagraph.maxIntrinsicWidth, text.firstBaseline, text.lastBaseline,
                        text.lineCount, text::getLineLeft, text::getLineRight, text::getLineBaseline))
            }
            result.put("renderer", "released").put("paragraph_kind", "GetTextLayoutResult semantics reconstruction")
        } else {
            for (label in labels) {
                val entry = JSONObject().put("text", label).also(texts::put)
                val candidates = views.filterIsInstance<ReverbParagraphView>().filter { it.getText() == label }
                entry.put("matches", candidates.size)
                val text = candidates.single()
                val parents = JSONArray().also { entry.put("view_ancestors", it) }
                var view: View? = text
                while (view != null) {
                    parents.put(incidentTextViewGeometry(view))
                    view = view.parent as? View
                }
                // Exact current own UI fields only; do not introduce production diagnostic APIs.
                val style = ReverbParagraphView::class.java.getDeclaredField("textStyle")
                    .apply { isAccessible = true }.get(text) as TextStyle
                val density = ReverbParagraphView::class.java.getDeclaredField("density")
                    .apply { isAccessible = true }.get(text) as Density
                val paragraph = checkNotNull(text.currentParagraph)
                entry.put("style", style.toString()).put("density", density.density.toDouble())
                    .put("font_scale", density.fontScale.toDouble()).put("direction", text.layoutDirection)
                    .put("paragraph", incidentParagraphGeometry(paragraph.width, paragraph.height,
                        paragraph.maxIntrinsicWidth, paragraph.firstBaseline, paragraph.lastBaseline,
                        paragraph.lineCount, paragraph::getLineLeft, paragraph::getLineRight, paragraph::getLineBaseline))
                text.createOriginalSemanticsParagraph()?.let {
                    entry.put("semantics_paragraph", incidentParagraphGeometry(it.width, it.height,
                        it.maxIntrinsicWidth, it.firstBaseline, it.lastBaseline, it.lineCount,
                        it::getLineLeft, it::getLineRight, it::getLineBaseline))
                }
            }
            result.put("renderer", "native").put("paragraph_kind", "current draw cache plus separate semantics reconstruction")
        }
        result.put("complete", true)
    } catch (failure: Throwable) {
        // Missing diagnostics remain explicit; the caller still repeats its unchanged strict guard.
        result.put("failure", failure.stackTraceToString())
    }
    return result
}

private fun incidentTextViewGeometry(view: View): JSONObject {
    val screen = IntArray(2).also(view::getLocationOnScreen)
    val matrix = FloatArray(9).also(view.matrix::getValues)
    return JSONObject().put("class", view.javaClass.name).put("identity", System.identityHashCode(view))
        .put("left", view.left).put("top", view.top).put("width", view.width).put("height", view.height)
        .put("measured_width", view.measuredWidth).put("measured_height", view.measuredHeight)
        .put("screen_x", screen[0]).put("screen_y", screen[1]).put("layout_direction", view.layoutDirection)
        .put("translation_x", view.translationX.toDouble()).put("translation_y", view.translationY.toDouble())
        .put("scale_x", view.scaleX.toDouble()).put("scale_y", view.scaleY.toDouble())
        .put("pivot_x", view.pivotX.toDouble()).put("pivot_y", view.pivotY.toDouble())
        .put("scroll_x", view.scrollX).put("scroll_y", view.scrollY)
        .put("matrix", JSONArray(matrix.map(Float::toDouble)))
}

private fun incidentParagraphGeometry(width: Float, height: Float, intrinsicWidth: Float,
                                     firstBaseline: Float, lastBaseline: Float, count: Int,
                                     left: (Int) -> Float, right: (Int) -> Float,
                                     baseline: (Int) -> Float): JSONObject {
    val lines = JSONArray()
    repeat(count) { index -> lines.put(JSONObject().put("index", index)
        .put("left", left(index).toDouble()).put("right", right(index).toDouble())
        .put("baseline", baseline(index).toDouble())) }
    return JSONObject().put("width", width.toDouble()).put("height", height.toDouble())
        .put("max_intrinsic_width", intrinsicWidth.toDouble()).put("first_baseline", firstBaseline.toDouble())
        .put("last_baseline", lastBaseline.toDouble()).put("line_count", count).put("lines", lines)
}

