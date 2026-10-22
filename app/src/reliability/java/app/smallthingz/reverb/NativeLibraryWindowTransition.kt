package app.smallthingz.reverb

import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import app.smallthingz.reverb.nativeui.ReverbParagraphView
import org.json.JSONArray
import org.json.JSONObject

internal enum class NativeLibrarySheetScene { INFO, RENAME }

internal data class NativeLibrarySheetTooltip(
    val compose: Boolean,
    val label: String,
    val scene: NativeLibrarySheetScene,
    val requiredLabels: List<String>,
    val recordingTitles: List<String>,
    val dismissLabel: String,
    val confirmLabel: String? = null,
)

/** Read-only readiness for one explicit own UI transition; never a substitute for an input guard. */
internal class NativeLibraryWindowTransition(
    private val host: NativeParityActivity,
    private val origin: View,
    private val evidence: JSONObject,
    private val observeSheetAuxiliaryOverlap: Boolean = false,
    private val sheetTooltip: NativeLibrarySheetTooltip? = null,
    private val allowInitialTooltipReadiness: Boolean = false,
) {
    private val main = host.window.decorView
    private val initial = visibleRoots()
    private var popup: View? = null
    private var auxiliary: View? = null
    private var reads = 0
    private val initialAuxiliary: View?

    init {
        check(!allowInitialTooltipReadiness || sheetTooltip?.scene == NativeLibrarySheetScene.RENAME)
        check(initial.filter(View::hasWindowFocus).single() === origin) { "UI transition origin lost focus" }
        val extra = initial.filter { it !== main && it !== origin }
        check(extra.size <= 1) { "Unrelated own windows before UI transition" }
        initialAuxiliary = extra.singleOrNull()?.also {
            val detail = auxiliaryObservation(it)
            requireSheetTooltip(it, origin, detail)
            evidence.put("initial_sheet_tooltip", detail)
        }
        evidence.put("origin_identity", System.identityHashCode(origin))
            .put("main_identity", System.identityHashCode(main)).put("accepted", false)
    }

    /** Called on main, only while the existing post-action readiness deadline is running. */
    fun focusedRoot(): View? {
        val roots = visibleRoots()
        val observation = JSONObject().put("uptime_ms", SystemClock.uptimeMillis())
            .put("windows", JSONArray(roots.map { root ->
                JSONObject().put("identity", System.identityHashCode(root)).put("class", root.javaClass.name)
                    .put("package", root.context.packageName).put("uid", root.context.applicationInfo.uid)
                    .put("display_id", root.display?.displayId).put("focused", root.hasWindowFocus())
            }))
        if (!evidence.has("first_observation")) evidence.put("first_observation", observation)
        evidence.put("last_observation", observation).put("reads", ++reads)
        val added = roots.filter { it !in initial }
        val focused = roots.filter(View::hasWindowFocus)
        check(focused.size <= 1) { "Ambiguous focus during owned UI transition" }
        if (initialAuxiliary != null) {
            check(added.isEmpty()) { "Unexpected new window during the classified sheet action" }
        }
        if (added.size > 1) {
            // A sheet and its keyboard tooltip can appear before the very first readiness read.
            // Pin only the unique focused incoming Decor after validating the complete pair below.
            val pinned = popup ?: added.singleOrNull {
                it.hasWindowFocus() && it.javaClass.name == "com.android.internal.policy.DecorView"
            }
            val extra = added.singleOrNull { it !== pinned }
            // Material3 retains its drag-handle tooltip while the handle has keyboard focus.
            // Recognize only that anchored, noninteractive tooltip on the complete focused sheet.
            check(observeSheetAuxiliaryOverlap && pinned != null && added.size == 2 &&
                pinned in added && focused.singleOrNull() === pinned && origin !in roots &&
                pinned.javaClass.name == "com.android.internal.policy.DecorView" &&
                extra != null && extra.javaClass.name == expectedTooltipClass() &&
                !extra.hasWindowFocus()) { "Ambiguous new windows during owned UI transition" }
            val knownExtra = checkNotNull(extra)
            check(auxiliary == null || auxiliary === knownExtra) {
                "Owned nonfocused sheet tooltip was replaced before readiness"
            }
            auxiliary = knownExtra
            val detail = auxiliaryObservation(knownExtra)
            if (!evidence.has("first_auxiliary_observation")) {
                evidence.put("first_auxiliary_observation", detail)
            }
            evidence.put("last_auxiliary_observation", detail)
                .put("auxiliary_overlap_reads", evidence.optInt("auxiliary_overlap_reads") + 1)
            requireSheetTooltip(knownExtra, checkNotNull(pinned), detail)
            if (popup == null) {
                popup = pinned
                evidence.put("popup_identity", System.identityHashCode(pinned))
                    .put("popup_pinned_with_classified_tooltip", true)
            }
            return pinned
        }
        if (initialAuxiliary != null && initialAuxiliary in roots) {
            if (allowInitialTooltipReadiness && focused.singleOrNull() === origin) {
                // Invalid Rename keeps its sheet and tooltip. Observe the same complete scene;
                // the caller's exact error/editor predicate still decides readiness, not this helper.
                val detail = auxiliaryObservation(initialAuxiliary)
                requireSheetTooltip(initialAuxiliary, origin, detail)
                evidence.put("retained_sheet_tooltip", detail)
                return origin
            }
            // Close/Cancel must retire the known tooltip; never accept the main scene beneath it.
            evidence.put("outgoing_sheet_tooltip_reads", evidence.optInt("outgoing_sheet_tooltip_reads") + 1)
            return null
        }
        popup?.let { pinned ->
            check(added.singleOrNull() === pinned) { "Owned popup was replaced or disappeared before readiness" }
        }
        if (auxiliary != null && auxiliary !in roots) {
            evidence.put("auxiliary_retired_observed_uptime_ms", SystemClock.uptimeMillis())
        }
        if (popup == null && added.isNotEmpty()) {
            popup = added.single()
            evidence.put("popup_identity", System.identityHashCode(popup))
        }
        if (focused.isEmpty()) {
            evidence.put("zero_focus_reads", evidence.optInt("zero_focus_reads") + 1)
            return null
        }
        return focused.single()
    }

    private fun requireSheetTooltip(root: View, info: View, detail: JSONObject) {
        val expected = checkNotNull(sheetTooltip) { "Unclassified auxiliary window" }
        check(info.javaClass.name == "com.android.internal.policy.DecorView" && info.hasWindowFocus() &&
            root.javaClass.name == expectedTooltipClass() && !root.hasWindowFocus()) {
            "Auxiliary is not attached to the focused sheet scene"
        }
        val parameters = checkNotNull(root.layoutParams as? WindowManager.LayoutParams)
        check(parameters.type == WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL &&
            parameters.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
            "Sheet auxiliary has unexpected window type or focusability"
        }
        val anchor = tooltipAnchor(root)
        check(anchor.isAttachedToWindow && anchor.rootView === info) { "Sheet tooltip has a different anchor" }
        if (!expected.compose) {
            check(anchor.javaClass.name == "app.smallthingz.reverb.nativeui.NativeSheetDragHandle") {
                "Native Sheet tooltip anchor is not the sheet drag handle"
            }
        }
        check(!detail.has("content_unavailable") && !detail.getBoolean("descendant_walk_capped") &&
            detail.getJSONArray("native_text").length() == 0) { "Unreadable Sheet tooltip content" }
        fun nodes(view: View, merged: Boolean = false) = ownedViews(view).filterIsInstance<ViewRootForTest>().flatMap {
            it.semanticsOwner.getAllSemanticsNodes(merged)
        }
        val labels: List<String>
        val actionCount: (String, Boolean) -> Int
        val editableCount: Int
        if (expected.compose) {
            check(detail.getJSONArray("semantics_labels").let {
                it.length() == 1 && it.getString(0) == expected.label
            } && detail.getJSONArray("paragraph_labels").length() == 0) {
                "Sheet auxiliary is not the localized Compose drag-handle tooltip"
            }
            check(nodes(root).none {
                it.config.getOrNull(SemanticsActions.OnClick) != null ||
                    it.config.getOrNull(SemanticsActions.OnLongClick) != null ||
                    it.config.getOrNull(SemanticsActions.SetText) != null
            }) { "Sheet tooltip unexpectedly contains an action" }
            labels = nodes(info).flatMap { it.config.getOrNull(SemanticsProperties.Text)?.map { text -> text.text }.orEmpty() }
            val merged = nodes(info, merged = true)
            actionCount = { label, enabledOnly ->
                merged.count {
                    label in it.config.getOrNull(SemanticsProperties.Text)?.map { text -> text.text }.orEmpty() &&
                        (!enabledOnly || it.config.getOrNull(SemanticsProperties.Disabled) == null) &&
                        it.config.getOrNull(SemanticsActions.OnClick)?.action != null
                }
            }
            editableCount = merged.count {
                it.config.getOrNull(SemanticsActions.SetText)?.action != null &&
                    it.config.getOrNull(SemanticsProperties.EditableText) != null &&
                    it.config.getOrNull(SemanticsProperties.Disabled) == null
            }
        } else {
            check(detail.getJSONArray("paragraph_labels").let {
                it.length() == 1 && it.getString(0) == expected.label
            } && detail.getJSONArray("semantics_labels").length() == 0 &&
                ownedViews(root).none { it.isClickable || it.isLongClickable }) {
                "Sheet auxiliary is not the noninteractive native drag-handle tooltip"
            }
            val views = ownedViews(info)
            val paragraphs = views.filterIsInstance<ReverbParagraphView>().filter(View::isShown)
            labels = paragraphs.map { it.getText().toString() }
            actionCount = { label, enabledOnly ->
                paragraphs.filter { it.getText().toString() == label }.mapNotNull { leaf ->
                    var action: View? = leaf
                    while (action != null && !action.isClickable) action = action.parent as? View
                    action
                }.distinct().count { it.isShown && (!enabledOnly || it.isEnabled) }
            }
            editableCount = views.count { view ->
                view.isShown && view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES &&
                    view.createAccessibilityNodeInfo()?.let { it.isEditable && it.isEnabled } == true
            }
        }
        check(expected.requiredLabels.all(labels::contains)) { "Tooltip anchor has the wrong sheet labels" }
        when (expected.scene) {
            NativeLibrarySheetScene.INFO -> check(expected.recordingTitles.count(labels::contains) == 1 &&
                editableCount == 0 && actionCount(expected.dismissLabel, true) == 1) {
                "Tooltip anchor is not the complete expected Info scene"
            }
            NativeLibrarySheetScene.RENAME -> check(editableCount == 1 &&
                actionCount(expected.dismissLabel, false) == 1 &&
                actionCount(checkNotNull(expected.confirmLabel), false) == 1) {
                "Tooltip anchor is not the unique Rename editor and actions"
            }
        }
        // Rename can temporarily disable its actions while committing. This observer never invokes
        // them: the existing input path independently requires the unique enabled action each time.
        detail.put("classification", (if (expected.compose) "material3-" else "native-") +
            expected.scene.name.lowercase() + "-drag-handle-tooltip")
            .put("classification_anchor_identity", System.identityHashCode(info))
            .put("classified_editable_count", editableCount)
    }

    private fun expectedTooltipClass(): String = if (checkNotNull(sheetTooltip).compose)
        "androidx.compose.ui.window.PopupLayout" else "app.smallthingz.reverb.nativeui.NativePlainTooltipWindow"

    private fun tooltipAnchor(root: View): View {
        val field = when (root.javaClass.name) {
            "androidx.compose.ui.window.PopupLayout" -> "composeView"
            "app.smallthingz.reverb.nativeui.NativePlainTooltipWindow" -> "anchor"
            else -> error("Unrecognized Sheet tooltip class")
        }
        return root.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(root) as View
    }

    private fun ownedViews(root: View): List<View> = ArrayList<View>().also { views ->
        fun visit(view: View) {
            views += view
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
    }

    private fun auxiliaryObservation(root: View): JSONObject = JSONObject()
        .put("uptime_ms", SystemClock.uptimeMillis())
        .put("identity", System.identityHashCode(root))
        .put("class", root.javaClass.name)
        .put("focused", root.hasWindowFocus())
        .put("width", root.width).put("height", root.height)
        .apply {
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            put("screen_x", location[0]).put("screen_y", location[1])
            (root.layoutParams as? WindowManager.LayoutParams)?.let {
                put("window_type", it.type).put("window_flags", it.flags)
                    .put("window_title", it.title?.toString())
            }
            runCatching {
                // Read the existing anchor of the exact source or native tooltip class.
                val anchor = tooltipAnchor(root)
                put("anchor_identity", System.identityHashCode(anchor))
                    .put("anchor_root_identity", System.identityHashCode(anchor.rootView))
            }.onFailure { put("anchor_unavailable", it.toString()) }
            runCatching {
                val views = ArrayList<View>()
                fun visit(view: View) {
                    if (views.size >= 128) return
                    views += view
                    if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
                }
                visit(root)
                put("descendant_classes", JSONArray(views.map { it.javaClass.name }.distinct()))
                put("native_text", JSONArray(views.filterIsInstance<TextView>().map { it.text.toString() }))
                put("paragraph_labels", JSONArray(views.filterIsInstance<ReverbParagraphView>().map { it.getText().toString() }))
                val labels = views.filterIsInstance<ViewRootForTest>().flatMap {
                    it.semanticsOwner.getAllSemanticsNodes(false)
                }.flatMap { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
                        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }
                put("semantics_labels", JSONArray(labels))
                    .put("descendant_walk_limit", 128).put("descendant_walk_capped", views.size >= 128)
            }.onFailure { put("content_unavailable", it.toString()) }
        }

    fun accepted(root: View) {
        evidence.put("accepted", true).put("accepted_identity", System.identityHashCode(root))
            .put("accepted_uptime_ms", SystemClock.uptimeMillis())
    }

    private fun visibleRoots(): List<View> {
        check(!host.isFinishing && host.fixtureRoot.isAttachedToWindow && host.fixtureRoot.isShown &&
            host.fixtureRoot.display?.displayId == 0 && host.applicationInfo.uid == Process.myUid())
        return WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.isShown }.also { roots ->
            check(roots.isNotEmpty() && roots.all {
                it.context.packageName == host.packageName && it.context.applicationInfo.uid == Process.myUid() &&
                    it.display?.displayId == 0
            }) { "Foreign, missing, or wrong-display windows during owned UI transition" }
        }
    }
}
