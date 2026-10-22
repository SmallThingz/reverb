package app.smallthingz.reverb

import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Read-only readiness for one explicit own UI transition; never a substitute for an input guard. */
internal class NativeLibraryWindowTransition(
    private val host: NativeParityActivity,
    private val origin: View,
    private val evidence: JSONObject,
    private val observeInfoAuxiliaryOverlap: Boolean = false,
) {
    private val main = host.window.decorView
    private val initial = visibleRoots()
    private var popup: View? = null
    private var auxiliary: View? = null
    private var reads = 0

    init {
        check(initial.all { it === main || it === origin }) { "Unrelated own window before UI transition" }
        check(initial.filter(View::hasWindowFocus).single() === origin) { "UI transition origin lost focus" }
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
        if (added.size > 1) {
            val pinned = popup
            val extra = added.singleOrNull { it !== pinned }
            // The retained Info transition briefly contains a new, nonfocused own PopupLayout.
            // Its purpose is unknown: observe it, but never accept input/capture until it retires.
            check(observeInfoAuxiliaryOverlap && pinned != null && added.size == 2 &&
                pinned in added && focused.singleOrNull() === pinned && origin !in roots &&
                pinned.javaClass.name == "com.android.internal.policy.DecorView" &&
                extra != null && extra.javaClass.name == "androidx.compose.ui.window.PopupLayout" &&
                !extra.hasWindowFocus()) { "Ambiguous new windows during owned UI transition" }
            val knownExtra = checkNotNull(extra)
            check(auxiliary == null || auxiliary === knownExtra) {
                "Owned nonfocused Info auxiliary window was replaced before readiness"
            }
            auxiliary = knownExtra
            val detail = auxiliaryObservation(knownExtra)
            if (!evidence.has("first_auxiliary_observation")) {
                evidence.put("first_auxiliary_observation", detail)
            }
            evidence.put("last_auxiliary_observation", detail)
                .put("auxiliary_overlap_reads", evidence.optInt("auxiliary_overlap_reads") + 1)
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
                // Pinned Compose PopupLayout keeps the LocalView anchor in this existing field.
                val anchor = root.javaClass.getDeclaredField("composeView").apply {
                    isAccessible = true
                }.get(root) as View
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
