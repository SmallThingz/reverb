package app.smallthingz.reverb

import android.app.Instrumentation
import android.graphics.Rect as AndroidRect
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import app.smallthingz.reverb.nativeui.ReverbParagraphView
import app.smallthingz.reverb.nativeui.NativeTextSemanticsTransformPolicy
import app.smallthingz.reverb.nativeui.nativeTextSemanticsMatrix
import app.smallthingz.reverb.nativeui.NativeMergedRowAccessibilityHost
import org.json.JSONArray
import org.json.JSONObject

/** Main-thread geometry read only; never inserts modifiers into the generated source oracle. */
internal fun onboardingGeometryEvidence(content: View, reference: Boolean): JSONObject {
    val semanticTransforms = (content as? NativeOnboardingScreenView)?.sourceTextTransforms
    val text = mutableListOf<JSONObject>()
    val raw = JSONArray()
    if (reference) {
        val root = onboardingViewDescendants(content).filterIsInstance<ViewRootForTest>().single()
        check(!root.hasPendingMeasureOrLayout)
        root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).forEach { node ->
            val strings = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
            val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            val position = node.positionInRoot
            val bounds = node.boundsInRoot
            val attached = node.layoutInfo.isAttached
            val placed = node.layoutInfo.isPlaced
            val rawNode = JSONObject().put("source_semantics_id", node.id)
                .put("texts", JSONArray(strings)).put("descriptions", JSONArray(descriptions))
                .put("attached", attached).put("placed", placed)
                .put("deactivated", node.layoutInfo.isDeactivated)
                .put("position", JSONArray(listOf(position.x.toDouble(), position.y.toDouble())))
                .put("size", JSONArray(listOf(node.size.width, node.size.height)))
                .put("clipped_bounds", JSONArray(listOf(bounds.left.toDouble(), bounds.top.toDouble(),
                    bounds.right.toDouble(), bounds.bottom.toDouble())))
            if (!attached || !placed) rawNode.put("text_geometry_excluded_reason",
                "Source layout is not attached and placed; retained prefetch geometry is not rendered")
            raw.put(rawNode)
            // getAllSemanticsNodes includes attached precomposed/premeasured pager pages.
            // LayoutInfo.isPlaced includes all parents; the actual source accessibility map
            // likewise excludes unplaced nodes (SemanticsOwner1.11.2:254–260). Keep every raw
            // node above, and do not exclude placed alpha-zero, hidden, clipped or offscreen text.
            if (strings.size == 1 && attached && placed) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                val layout = layouts.singleOrNull()
                text += textGeometry(
                    strings.single(), position, node.size.width, node.size.height,
                    layout?.firstBaseline, layout?.lastBaseline, layout?.lineCount,
                )
            }
        }
    } else {
        onboardingViewDescendants(content).forEach { view ->
            val location = pointInFixture(view, content, Offset.Zero)
            val goneAncestor = if (view is ReverbParagraphView) goneInFixture(view, content) else null
            val rawNode = JSONObject().put("class", view.javaClass.name)
                .put("position", JSONArray(listOf(location.x.toDouble(), location.y.toDouble())))
                .put("size", JSONArray(listOf(view.width, view.height)))
                .put("visibility", view.visibility)
                .put("important_for_accessibility", view.importantForAccessibility)
                .put("padding", JSONArray(listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)))
                .put("scroll", JSONArray(listOf(view.scrollX, view.scrollY)))
            if (goneAncestor != null) rawNode
                .put("text_geometry_excluded_reason", "GONE in captured ancestor chain")
                .put("gone_ancestor_class", goneAncestor.javaClass.name)
            raw.put(rawNode)
            if (view is ReverbParagraphView && goneAncestor == null) {
                val paragraph = view.createOriginalSemanticsParagraph()
                text += textGeometry(
                    view.getText(),
                    pointInFixture(view, content, Offset(
                        view.paddingLeft.toFloat() - view.scrollX,
                        view.paddingTop.toFloat() - view.scrollY,
                    ), semanticTransforms),
                    (view.width - view.paddingLeft - view.paddingRight).coerceAtLeast(0),
                    (view.height - view.paddingTop - view.paddingBottom).coerceAtLeast(0),
                    paragraph?.firstBaseline, paragraph?.lastBaseline, paragraph?.lineCount,
                )
            }
        }
    }
    check(text.isNotEmpty()) { "No text geometry; an empty fixture is not parity evidence" }
    return JSONObject().put("reference", reference).put("raw", raw)
        .put("raw_coordinate_basis", if (reference) "compose_semantics" else "android_view_matrix")
        .put("text_coordinate_basis", if (reference) "compose_semantics"
            else if (semanticTransforms != null) "native_source_semantics_for_known_layers" else "android_view_matrix")
        .put("text_layouts", JSONArray(text.sortedBy(::geometrySortKey)))
}

/** A GONE wrapper has no released Text counterpart; retain INVISIBLE/alpha-zero/clipped labels. */
private fun goneInFixture(view: View, fixture: View): View? {
    var current = view
    while (true) {
        if (current.visibility == View.GONE) return current
        if (current === fixture) return null
        current = current.parent as? View ?: error("Native text left its captured fixture")
    }
}

private fun textGeometry(
    text: String, position: Offset, width: Int, height: Int,
    firstBaseline: Float?, lastBaseline: Float?, lines: Int?,
): JSONObject = JSONObject().put("text", text)
    .put("x", position.x.toDouble()).put("y", position.y.toDouble())
    .put("width", width).put("height", height)
    .put("first_baseline", firstBaseline?.toDouble() ?: JSONObject.NULL)
    .put("last_baseline", lastBaseline?.toDouble() ?: JSONObject.NULL)
    .put("line_count", lines ?: JSONObject.NULL)

private fun geometrySortKey(node: JSONObject): String =
    node.getString("text") + "\u0000" + node.getDouble("y") + "\u0000" + node.getDouble("x")

/** Raw diagnostics keep backend matrices; text inventory may use the production semantic policy. */
private fun pointInFixture(view: View, fixture: View, point: Offset,
                           transformPolicy: NativeTextSemanticsTransformPolicy? = null): Offset {
    var current = view
    var position = point
    while (current !== fixture) {
        val parent = current.parent as? View ?: error("Native text left its captured fixture")
        val mapped = nativeTextSemanticsMatrix(current, transformPolicy).map(position.nativeParityOffset())
        position = Offset(mapped.x, mapped.y) +
            Offset(current.left.toFloat() - parent.scrollX, current.top.toFloat() - parent.scrollY)
        current = parent
    }
    return position
}

/**
 * Actual native/virtual AccessibilityNodeInfo tree from the focused QA window. Text and content
 * descriptions are retained separately. No accessibility service/setting is enabled by this code.
 * Empty structural wrappers may differ between native and Compose; all payload/action-bearing
 * visible nodes are compared, and the complete raw tree is retained for review.
 */
internal fun onboardingNativeNodeEvidence(
    instrumentation: Instrumentation, activity: NativeParityActivity, content: View,
    automation: android.app.UiAutomation = nativeParityUiAutomation(instrumentation),
    preserveFailure: (JSONObject) -> Unit,
): JSONObject {
    requireNativeParityHost(instrumentation, activity)
    automation.waitForIdle(80, 2_000)
    requireNativeParityHost(instrumentation, activity)
    val origin = IntArray(2)
    nativeParityOnMain(instrumentation) { content.getLocationOnScreen(origin) }
    val viewport = AndroidRect(origin[0], origin[1], origin[0] + content.width, origin[1] + content.height)
    val root = checkNotNull(automation.rootInActiveWindow) { "No current QA node root" }
    val raw = JSONArray()
    val canonical = mutableListOf<JSONObject>()
    var visited = 0
    var currentPath: List<Int> = emptyList()
    var missingChild: JSONObject? = null
    var staleChild: JSONObject? = null
    @Suppress("DEPRECATION")
    fun visit(node: AccessibilityNodeInfo, depth: Int, path: List<Int>) {
        currentPath = path
        check(depth < 64 && ++visited < 1_024) { "Unexpected accessibility tree; stop without truncation" }
        val cachedBounds = AndroidRect().also(node::getBoundsInScreen)
        val cached = JSONObject().put("path", JSONArray(path))
            .put("class", node.className?.toString() ?: JSONObject.NULL)
            .put("text", node.text?.toString() ?: JSONObject.NULL)
            .put("description", node.contentDescription?.toString() ?: JSONObject.NULL)
            .put("resource_id", node.viewIdResourceName ?: JSONObject.NULL)
            .put("window_id", node.windowId).put("children", node.childCount)
            .put("visible", node.isVisibleToUser).put("enabled", node.isEnabled)
            .put("focused", node.isFocused)
            .put("screen_bounds", JSONArray(listOf(cachedBounds.left, cachedBounds.top, cachedBounds.right, cachedBounds.bottom)))
        if (!node.refresh()) {
            staleChild = cached
            try {
                cached.put("current_native_candidates", nativeParityOnMain(instrumentation) {
                    val candidates = JSONArray()
                    for (view in onboardingViewDescendants(content)) {
                        val location = IntArray(2)
                        view.getLocationOnScreen(location)
                        val physical = AndroidRect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                        val info = view.createAccessibilityNodeInfo()
                        try {
                            val currentBounds = info?.let { AndroidRect().also(it::getBoundsInScreen) }
                            val semanticMatch = currentBounds == cachedBounds &&
                                info?.className?.toString() == cached.optString("class") &&
                                info?.text?.toString() == cached.optString("text", null) &&
                                info?.contentDescription?.toString() == cached.optString("description", null)
                            if (semanticMatch || physical == cachedBounds) {
                                candidates.put(JSONObject().put("native_class", view.javaClass.name)
                                    .put("match", if (semanticMatch) "current node payload and bounds" else "physical bounds only; identity unproven")
                                    .put("attached", view.isAttachedToWindow).put("shown", view.isShown)
                                    .put("visibility", view.visibility).put("laid_out", view.isLaidOut)
                                    .put("current_node_present", info != null)
                                    .put("current_node_class", info?.className?.toString() ?: JSONObject.NULL)
                                    .put("current_node_visible", info?.isVisibleToUser ?: JSONObject.NULL)
                                    .put("merged_source_admitted", (view as? NativeMergedRowAccessibilityHost)
                                        ?.mergedRowAccessibility?.isNodePresent() ?: JSONObject.NULL))
                            }
                        } finally { info?.recycle() }
                    }
                    candidates
                })
            } catch (diagnostic: Throwable) {
                cached.put("native_diagnostic_error", android.util.Log.getStackTraceString(diagnostic))
            }
            error("Node became stale during a supposedly stable capture")
        }
        val rect = AndroidRect()
        node.getBoundsInScreen(rect)
        val actions = node.actionList.sortedBy { it.id }.map {
            JSONObject().put("id", it.id).put("label", it.label?.toString() ?: JSONObject.NULL)
        }
        val roleDescription = AccessibilityNodeInfoCompat.wrap(node).roleDescription?.toString()
        val stateDescription = if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString() else null
        val item = JSONObject()
            .put("class", node.className?.toString() ?: JSONObject.NULL)
            .put("text", node.text?.toString() ?: JSONObject.NULL)
            .put("description", node.contentDescription?.toString() ?: JSONObject.NULL)
            .put("state_description", stateDescription ?: JSONObject.NULL)
            .put("role_description", roleDescription ?: JSONObject.NULL)
            .put("screen_reader_focusable", node.isScreenReaderFocusable)
            .put("enabled", node.isEnabled).put("clickable", node.isClickable)
            .put("long_clickable", node.isLongClickable).put("checkable", node.isCheckable)
            .put("checked", node.isChecked).put("selected", node.isSelected)
            .put("editable", node.isEditable).put("scrollable", node.isScrollable)
            .put("focusable", node.isFocusable).put("focused", node.isFocused)
            .put("accessibility_focused", node.isAccessibilityFocused)
            .put("visible", node.isVisibleToUser)
            .put("movement_granularities", node.movementGranularities)
            .put("actions", JSONArray(actions))
            .put("bounds", JSONArray(listOf(rect.left - origin[0], rect.top - origin[1],
                rect.right - origin[0], rect.bottom - origin[1])))
        raw.put(JSONObject(item.toString()).put("depth", depth)
            .put("package", node.packageName?.toString() ?: JSONObject.NULL)
            .put("children", node.childCount))
        // Compose creates role-only virtual children for merging Button/Switch rows. They are
        // semantic payload, not empty wrappers: keep their role/class even without text or clicks.
        val hasPayload = !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty() ||
            !stateDescription.isNullOrEmpty() || !roleDescription.isNullOrEmpty() ||
            node.className?.toString() in onboardingSemanticRoleClasses ||
            node.isClickable || node.isCheckable || node.isScrollable
        if (node.isVisibleToUser && hasPayload && AndroidRect.intersects(rect, viewport)) {
            check(node.packageName?.toString() == activity.packageName) { "Foreign accessible content over fixture" }
            canonical += item
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index)
            if (child == null) {
                missingChild = JSONObject().put("parent_path", JSONArray(path))
                    .put("parent", JSONObject(item.toString()).put("depth", depth)
                        .put("child_count", node.childCount))
                    .put("missing_child_index", index)
                error("Missing child in stable accessibility tree: parent path=$path, " +
                    "class=${node.className}, bounds=$rect, depth=$depth, child=$index/${node.childCount}")
            }
            try { visit(child, depth + 1, path + index) } finally { child.recycle() }
        }
    }
    try {
        check(root.packageName?.toString() == activity.packageName) { "Focused root is not the QA package" }
        visit(root, 0, emptyList())
    } catch (error: Throwable) {
        // A missing native child remains a hard failure. Retain the exact parent/path and all
        // already-read nodes instead of dropping the failing capture or filtering null children.
        val partial = JSONObject().put("complete", false).put("raw", raw)
            .put("canonical_partial", JSONArray(canonical))
            .put("last_path", JSONArray(currentPath))
            .put("missing_child", missingChild ?: JSONObject.NULL)
            .put("stale_child_before_refresh", staleChild ?: JSONObject.NULL)
            .put("error", android.util.Log.getStackTraceString(error))
        try { preserveFailure(partial) }
        catch (evidenceError: Throwable) { error.addSuppressed(evidenceError) }
        throw error
    } finally {
        @Suppress("DEPRECATION")
        root.recycle()
    }
    check(canonical.any { !it.isNull("text") }) { "No accessible text; do not credit an empty node tree" }
    requireNativeParityHost(instrumentation, activity)
    return JSONObject().put("raw", raw).put("canonical", JSONArray(canonical.sortedBy { item ->
        item.optString("text") + "\u0000" + item.optString("description") + "\u0000" +
            item.getJSONArray("bounds").toString() + "\u0000" + item.optString("class")
    }))
}

internal fun onboardingTextGeometrySignature(evidence: JSONObject): String =
    evidence.getJSONObject("geometry").getJSONArray("text_layouts").toString()

internal fun onboardingNodeSignature(evidence: JSONObject): String =
    evidence.getJSONObject("nodes").getJSONArray("canonical").toString()

internal fun onboardingStableSignature(evidence: JSONObject): String =
    onboardingTextGeometrySignature(evidence) + "\n" + onboardingNodeSignature(evidence) + "\n" +
        evidence.getJSONObject("geometry").getInt("actual_scroll") + ":" +
        evidence.getJSONObject("geometry").getInt("max_scroll")

// Android legacy classes used for explicit roles; generic View/ViewGroup wrappers remain excluded.
private val onboardingSemanticRoleClasses = setOf(
    "android.widget.Button", "android.widget.CheckBox", "android.widget.RadioButton",
    "android.widget.Spinner", "android.widget.ImageView", "android.widget.Switch",
    "android.widget.SeekBar", "android.widget.ProgressBar",
)
