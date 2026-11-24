package app.smallthingz.reverb

import smallthingz.ui.text.view.NativeMergedRowAccessibilityHost
import smallthingz.ui.text.view.NativeTextSemanticsClip
import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import app.smallthingz.reverb.nativeui.NativeSettingsScreenContent
import app.smallthingz.reverb.nativeui.ReverbHomeScaffoldView
import app.smallthingz.reverb.nativeui.ReverbTopBarIconAction
import app.smallthingz.reverb.nativeui.ReverbLibraryScreenContent
import androidx.recyclerview.widget.LinearLayoutManager
import smallthingz.ui.components.NativeChip
import smallthingz.ui.components.NativeSegmentedChoice
import smallthingz.ui.reverb.settings.ReverbRetentionSection
import smallthingz.ui.reverb.settings.ReverbSettingsSectionKey
import smallthingz.ui.common.NativeAxisDrag
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import smallthingz.ui.input.phase.PhasePointerRoot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Attached-View regression for source admission, independent of screen-image comparisons.
 * All temporary poses run in one main-thread dispatch and restore before a frame can be drawn.
 * This does not qualify gesture animation, platform cache delivery, or TalkBack.
 */
internal fun verifyNativeMainLayerAdmission(
    instrumentation: Instrumentation,
    activity: NativeParityActivity,
    content: View,
    preserveFailure: (JSONObject) -> Unit = {},
): JSONObject {
    requireNativeParityHost(instrumentation, activity)
    val result = JSONObject().put("passed", false)
        .put("scope", "Current native Main child advertisement and attached source-layer geometry")
        .put("unqualified", JSONArray(listOf("gesture/frame parity", "platform cache delivery", "TalkBack")))
    val actual = JSONObject().put("passed", false)
    val fixture = JSONObject().put("passed", false)
    result.put("actual_main", actual).put("source_fixture", fixture)
    try {
        nativeParityOnMain(instrumentation) {
            verifyActualMainLayerAdmission(activity, content, actual)
            verifyAttachedLayerFilter(activity, fixture)
        }
        requireNativeParityHost(instrumentation, activity)
        return result.put("passed", true)
    } catch (error: Throwable) {
        result.put("error", error.stackTraceToString())
        try { preserveFailure(result) } catch (preservation: Throwable) {
            if (preservation !== error) error.addSuppressed(preservation)
        }
        throw error
    }
}

private fun verifyActualMainLayerAdmission(
    activity: NativeParityActivity,
    content: View,
    evidence: JSONObject,
) {
    val main = admissionViews(content).filterIsInstance<NativeMainScreenView>().single()
    val home = admissionViews(main).filterIsInstance<ReverbHomeScaffoldView>().single()
    val settings = admissionViews(main).filterIsInstance<NativeSettingsScreenContent>().single()
    check(home.parent === main && settings.parent === main && main.isAttachedToWindow && main.isLaidOut)
    check(!home.isFocusable && !settings.isFocusable) { "Surface roots must not be standalone focus targets" }
    val bar = home.topBar
    val actions = (0 until bar.childCount).map { index ->
        when (val child = bar.getChildAt(index)) {
            is ReverbTopBarIconAction -> child.accessibilityAction
            is NativeMergedRowAccessibilityHost -> child
            else -> error("Unexpected actual Home top-bar child: ${child.javaClass.name}")
        }
    }
    check(actions.size == 3 && actions.distinct().size == 3) {
        "The QA Home must expose the actual brand, Incidents and Settings actions"
    }
    check(main.height > bar.height * 2 && main.height == home.height) {
        "Current fixture cannot place the complete top bar within the half-visible Home pose"
    }
    val savedTranslation = home.translationY
    val prefix = View(activity)
    val cases = JSONArray()
    evidence.put("cases", cases).put("saved_translation_y", savedTranslation.toDouble())
    try {
        val poses = listOf(
            Triple("offscreen", main.height.toFloat(), 0),
            Triple("inactive_home_visible_while_settings_open", 0f, 0),
            Triple("inactive_home_half_visible", main.height / 2f, 0),
            Triple("late_partial", main.height - 1f, 0),
        )
        for ((name, translation, expectedActions) in poses) {
            val record = JSONObject().put("name", name).put("passed", false)
                .put("translation_y", translation.toDouble())
            cases.put(record)
            home.translationY = translation
            val presentActions = actions.filter(::admissionNodePresent)
            record.put("source_action_nodes", presentActions.size)
            check(presentActions.size == expectedActions) {
                "$name: expected $expectedActions actual source action nodes, found ${presentActions.size}"
            }

            val topBarCandidates = arrayListOf(prefix)
            bar.addChildrenForAccessibility(topBarCandidates)
            check(topBarCandidates.firstOrNull() === prefix) { "$name: top-bar traversal altered its caller prefix" }
            val advertisedActions = topBarCandidates.drop(1).map { child ->
                when (child) {
                    is ReverbTopBarIconAction -> child.accessibilityAction
                    is NativeMergedRowAccessibilityHost -> child
                    else -> error("$name: unrecognized advertised top-bar child ${child.javaClass.name}")
                }
            }
            record.put("top_bar_candidates", JSONArray(topBarCandidates.drop(1).map { it.javaClass.name }))
                .put("advertised_action_nodes", advertisedActions.size)
            check(advertisedActions.size == presentActions.size &&
                advertisedActions.toSet() == presentActions.toSet()) {
                "$name: the actual top bar advertised absent actions or dropped present actions"
            }
            check(advertisedActions.all(::admissionNodePresent)) {
                "$name: an advertised top-bar action has no current node"
            }

            val mainCandidates = arrayListOf(prefix)
            main.addChildrenForAccessibility(mainCandidates)
            check(mainCandidates.firstOrNull() === prefix) { "$name: Main traversal altered its caller prefix" }
            val current = mainCandidates.drop(1)
            val homeCount = current.count { admissionWithin(it, home) }
            val settingsCount = current.count { admissionWithin(it, settings) }
            record.put("main_candidates", JSONArray(current.map { candidate ->
                JSONObject().put("class", candidate.javaClass.name)
                    .put("in_home", admissionWithin(candidate, home))
                    .put("in_settings", admissionWithin(candidate, settings))
                    .put("node_present", admissionNodePresent(candidate))
            })).put("home_candidates", homeCount).put("settings_candidates", settingsCount)
            check(current.all(::admissionNodePresent)) {
                "$name: Main advertised a candidate whose current native node is absent"
            }
            if (expectedActions == 0) {
                check(homeCount == 0 && settingsCount > 0) {
                    "Inactive Home must be absent while the actual Settings subtree remains advertised"
                }
            } else if (expectedActions > 0) {
                check(homeCount > 0) { "$name: a present Home layer lost all its advertised descendants" }
            }
            // Exercise held View references, not just fresh child enumeration. These actual Home
            // top-bar actions are inactive while Settings owns navigation, even in a visible pose.
            val focusedBefore = main.findFocus()
            for (action in actions) {
                val view = action as View
                check(!view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)) {
                    "$name: a held inactive Home action still accepted an accessibility click"
                }
                check(!view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_FOCUS, null)) {
                    "$name: a held inactive Home action still accepted accessibility input focus"
                }
                val touchFocusable = view.isFocusableInTouchMode
                val focusable = view.isFocusable
                try {
                    // Remove touch-mode eligibility as a confounder: the surface boundary itself
                    // must reject a direct leaf request, including a stale retained View reference.
                    view.isFocusableInTouchMode = true
                    check(!view.requestFocus(View.FOCUS_FORWARD)) {
                        "$name: a direct leaf request entered the inactive Home subtree"
                    }
                } finally {
                    view.isFocusableInTouchMode = touchFocusable
                    view.isFocusable = focusable
                }
            }
            check(main.findFocus() === focusedBefore) { "$name: blocked requests changed the active focus" }
            record.put("stale_action_and_direct_focus_rejected", true).put("passed", true)
        }
        evidence.put("passed", true)
    } finally {
        home.translationY = savedTranslation
        check(home.translationY.toRawBits() == savedTranslation.toRawBits()) {
            "Main admission regression did not restore the original Home translation"
        }
        evidence.put("translation_restored", true)
    }
}

/** Exercise both direct-layer and flattened candidates against the same attached source map. */
private fun verifyAttachedLayerFilter(activity: NativeParityActivity, evidence: JSONObject) {
    val root = FrameLayout(activity)
    val settings = FrameLayout(activity)
    val home = FrameLayout(activity)
    val unknown = FrameLayout(activity)
    fun action(label: String) = View(activity).apply {
        contentDescription = label
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isScreenReaderFocusable = true
    }
    val settingsAction = action("QA visible Settings")
    val homeAction = action("QA Home")
    val unknownChild = action("QA unmapped wrapper")
    val prefix = View(activity)
    settings.addView(settingsAction)
    home.addView(homeAction)
    unknown.addView(unknownChild)
    root.addView(settings)
    root.addView(home)
    root.addView(unknown)
    val layers = listOf(settings, home)
    var hiddenHome = false
    var inactiveHome = false
    val source = NativeMainAccessibility(activity.fixtureRoot, root, { layers },
        ownNodeHidden = { hiddenHome && it === home },
        clip = { NativeTextSemanticsClip.Unclipped }, minimumTouch = { 0f },
        inactiveLayer = { inactiveHome && it === home })
    val cases = JSONArray()
    evidence.put("cases", cases)
    try {
        activity.fixtureRoot.addView(root, FrameLayout.LayoutParams(100, 100))
        val exact = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        root.measure(exact, exact)
        root.layout(0, 0, 100, 100)
        settings.layout(0, 0, 50, 100)
        home.layout(50, 0, 100, 100)
        unknown.layout(0, 0, 50, 100)
        settingsAction.layout(0, 0, 50, 20)
        homeAction.layout(0, 0, 50, 20)
        unknownChild.layout(0, 0, 50, 20)
        check(root.isAttachedToWindow && root.isLaidOut)

        fun checkCandidates(name: String, expected: List<View>) {
            val candidates = arrayListOf(prefix, unknown, home, settings, homeAction, settingsAction, unknownChild)
            val record = JSONObject().put("name", name).put("passed", false)
                .put("home_present", source.node(home).present)
                .put("home_hidden", source.node(home).hidden)
                .put("settings_present", source.node(settings).present)
            cases.put(record)
            source.retainPresentLayerChildren(candidates, 1)
            record.put("candidate_count", candidates.size)
            check(candidates.size == expected.size && candidates.indices.all { candidates[it] === expected[it] }) {
                "$name: layer admission changed prefix, unknown candidates, order, or the source-presence contract"
            }
            record.put("passed", true)
        }
        home.translationY = 100f
        check(!source.node(home).present && source.node(settings).present) {
            "Attached fixture did not establish offscreen Home and visible Settings"
        }
        checkCandidates("direct_and_flattened_absent",
            listOf(prefix, unknown, settings, settingsAction, unknownChild))

        home.translationY = 50f
        hiddenHome = true
        check(source.node(home).present && source.node(home).hidden && source.node(homeAction).present) {
            "Attached fixture did not establish a partially visible, own-node-hidden Home with a present action"
        }
        checkCandidates("hidden_but_present_retained",
            listOf(prefix, unknown, home, settings, homeAction, settingsAction, unknownChild))

        inactiveHome = true
        check(!source.node(home).present && !source.node(homeAction).present && source.node(homeAction).hidden)
        checkCandidates("inactive_subtree_excludes_direct_and_flattened_children",
            listOf(prefix, unknown, settings, settingsAction, unknownChild))
        val semanticChildren = arrayListOf(prefix, homeAction, settingsAction, unknownChild)
        source.retainPresentSemanticChildren(semanticChildren, 1) { it }
        check(semanticChildren == listOf(prefix, settingsAction)) {
            "Semantic child admission retained an inactive descendant or changed its prefix"
        }
        inactiveHome = false
        check(source.node(homeAction).present) { "Reactivating the surface did not restore its child node" }
        checkCandidates("reactivated_subtree_restores_same_children",
            listOf(prefix, unknown, home, settings, homeAction, settingsAction, unknownChild))
        evidence.put("passed", true)
    } finally {
        if (root.parent === activity.fixtureRoot) activity.fixtureRoot.removeView(root)
        check(!root.isAttachedToWindow) { "Temporary source-admission fixture remained attached" }
        evidence.put("fixture_removed", true)
    }
}

private fun admissionViews(root: View): Sequence<View> = sequence {
    yield(root)
    if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(admissionViews(root.getChildAt(index)))
}

private fun admissionWithin(view: View, ancestor: View): Boolean {
    var current: View? = view
    while (current != null) {
        if (current === ancestor) return true
        current = current.parent as? View
    }
    return false
}

private fun admissionNodePresent(view: View): Boolean {
    val node = view.createAccessibilityNodeInfo() ?: return false
    @Suppress("DEPRECATION")
    node.recycle()
    return true
}

/** Current-source regression, not timing evidence: real Main pointer dispatch, no private method
 * invocation or production counters. Reflection only reads bind witnesses and admission revision. */
internal fun verifyNativeMainMotionAdmission(
    instrumentation: Instrumentation,
    activity: NativeParityActivity,
    render: MainSettingsParityRender,
    preserve: (JSONObject) -> Unit,
): JSONObject {
    val result = JSONObject().put("passed", false)
        .put("scope", "Actual Main local finger streams; retained Settings selectors; drag bind/admission witnesses")
        .put("expected_cases", 6).put("completed_cases", 0)
        .put("bind_witnesses", JSONArray(listOf("Settings.input", "Library.input", "Home.navigation")))
        .put("not_qualified", JSONArray(listOf("physical input transport", "frame latency or RAM",
            "IME composition", "all themes/configurations", "inline recording card interactions")))
    val cases = JSONArray().also { result.put("cases", it) }
    fun <T> ui(block: () -> T): T {
        requireNativeParityHost(instrumentation, activity)
        return nativeParityOnMain(instrumentation, block)
    }
    val main = ui { admissionViews(render.board).filterIsInstance<NativeMainScreenView>().single() }
    fun field(name: String) = mainAdmissionField(main, name)
    val navigation = ui { field("navigation") as NativeMainNavigationState }
    fun settings() = field("settings") as NativeSettingsScreenContent
    fun library() = field("library") as? ReverbLibraryScreenContent
    fun motion(panel: NativeMainPanel) = field(if (panel == NativeMainPanel.SETTINGS)
        "settingsMotion" else "libraryMotion") as NativeMainPanelMotion
    fun admission() = field("admissionGeneration") as Long
    fun bindings(): List<Any?> = listOf(
        (field("settings") as? NativeSettingsScreenContent)?.let { mainAdmissionField(it, "input") },
        library()?.let { mainAdmissionField(it, "input") },
        mainAdmissionField(field("home")!!, "navigation"),
    )
    fun sameBindings(a: List<Any?>, b: List<Any?>) = a.indices.all { a[it] === b[it] }
    fun ids(values: List<Any?>) = JSONArray(values.map { it?.let(System::identityHashCode) ?: JSONObject.NULL })
    fun requireFullBind(entry: JSONObject, phase: String, before: List<Any?>, requireLibrary: Boolean) {
        val after = bindings()
        entry.put("${phase}_bind_refs_before", ids(before)).put("${phase}_bind_refs_after", ids(after))
        check(before[0] != null && before[2] != null) { "$phase missing warmed Settings/Home witnesses" }
        before.indices.forEach { index ->
            if (before[index] != null) check(after[index] != null && before[index] !== after[index]) {
                "$phase did not replace bind witness $index"
            }
        }
        if (requireLibrary) check(after[1] != null) { "$phase did not create/bind Library" }
        entry.put("${phase}_full_bind", true)
    }
    fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        while (!ui(predicate)) {
            check(SystemClock.uptimeMillis() < deadline) { "Main motion admission timed out: $label" }
            awaitNativeParityDraw(instrumentation, render.board)
        }
    }
    fun home() = !navigation.showSettings && !navigation.showLibrary && navigation.dragTarget == null &&
        motion(NativeMainPanel.SETTINGS).panelProgress == 0f && motion(NativeMainPanel.LIBRARY).panelProgress == 0f
    // Read the real retained dispatch path, not a second hit test or a substituted input owner.
    // Only sampled at gesture admission; the measured delta loop remains unchanged.
    fun startSnapshot(): JSONObject {
        fun describe(view: View): JSONObject {
            val origin = IntArray(2).also(view::getLocationOnScreen)
            val visible = Rect()
            val hasVisibleBounds = view.getGlobalVisibleRect(visible)
            return JSONObject().put("class", view.javaClass.name).put("identity", System.identityHashCode(view))
                .put("attached", view.isAttachedToWindow).put("shown", view.isShown)
                .put("visibility", view.visibility).put("width", view.width).put("height", view.height)
                .put("screen_x", origin[0]).put("screen_y", origin[1])
                .put("translation_y", view.translationY.toDouble()).put("alpha", view.alpha.toDouble())
                .put("visible_bounds", if (hasVisibleBounds)
                    JSONArray(listOf(visible.left, visible.top, visible.right, visible.bottom)) else JSONObject.NULL)
                .put("description", view.contentDescription?.toString() ?: JSONObject.NULL)
        }
        val vertical = field("vertical")!!
        val recognizer = JSONObject()
        for (name in listOf("state", "pointerId", "accumulated", "threshold", "verifyFinal")) {
            val value = NativeAxisDrag::class.java.getDeclaredField(name).run {
                isAccessible = true; get(vertical)
            }
            recognizer.put(name, if (value is Enum<*>) value.name else value ?: JSONObject.NULL)
        }
        val tree = PhasePointerRoot::class.java.getDeclaredField("paths").run {
            isAccessible = true; get(main)
        }
        fun branch(value: Any): JSONObject {
            val node = mainAdmissionField(value, "node") as NativeViewPhasePointerNode
            val children = mainAdmissionField(value, "children") as List<*>
            return JSONObject().put("node_class", node.javaClass.name)
                .put("node_identity", System.identityHashCode(node)).put("node_attached", node.attached)
                .put("view", describe(node.view))
                .put("pointer_ids", JSONArray((mainAdmissionField(value, "pointers") as Set<*>).toList()))
                .put("children", JSONArray(children.map { branch(checkNotNull(it)) }))
        }
        val roots = mainAdmissionField(tree, "roots") as List<*>
        return JSONObject().put("home", describe(field("home") as View))
            .put("settings", (field("settings") as? View)?.let(::describe) ?: JSONObject.NULL)
            .put("home_gestures_enabled", navigation.homeGesturesEnabled)
            .put("home_input_admitted", main.admitsSurfaceInput(field("home") as View))
            .put("drag_target", navigation.dragTarget?.name ?: JSONObject.NULL)
            .put("recognizer", recognizer).put("retained_path", JSONArray(roots.map { branch(checkNotNull(it)) }))
    }
    fun bounds(view: View): Rect = Rect().also { check(view.getGlobalVisibleRect(it)) }
    val events = JSONArray().also { result.put("pointer_events", it) }
    fun finger(action: Int, down: Long, x: Float, y: Float) {
        check(activity.hasWindowFocus() && main.isShown && main.isAttachedToWindow)
        val origin = IntArray(2).also(main::getLocationOnScreen)
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coordinate = MotionEvent.PointerCoords().apply {
            this.x = x + origin[0]; this.y = y + origin[1]
            pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else 1f
            size = 1f
        }
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1,
            arrayOf(pointer), arrayOf(coordinate), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
            val sample = JSONObject().put("action", action).put("x", x.toDouble()).put("y", y.toDouble())
                .put("raw_x", event.rawX.toDouble()).put("raw_y", event.rawY.toDouble())
            events.put(sample)
            check(kotlin.math.abs(event.x - x) < .01f && kotlin.math.abs(event.y - y) < .01f)
            check(kotlin.math.abs(event.rawX - coordinate.x) < .01f &&
                kotlin.math.abs(event.rawY - coordinate.y) < .01f)
            sample.put("handled", main.dispatchTouchEvent(event))
            check(sample.getBoolean("handled")) { "Actual Main rejected finger event: $sample" }
        } finally { event.recycle() }
    }
    fun tap(view: View, beforeDown: () -> Unit = {}) = ui {
        val rectangle = bounds(view)
        val origin = IntArray(2).also(main::getLocationOnScreen)
        val x = rectangle.exactCenterX() - origin[0]
        val y = rectangle.exactCenterY() - origin[1]
        val down = SystemClock.uptimeMillis()
        beforeDown()
        finger(MotionEvent.ACTION_DOWN, down, x, y)
        finger(MotionEvent.ACTION_UP, down, x, y)
    }
    fun openSettings() {
        ui { render.click(activity.getString(R.string.open_settings)) }
        await("Settings ready") { settings().owner.state.hydrated && settings().inputAllowed &&
            motion(NativeMainPanel.SETTINGS).panelProgress == 1f }
        awaitNativeParityDraw(instrumentation, render.board)
    }
    fun closeToHome() {
        ui { activity.onBackPressedDispatcher.onBackPressed() }
        await("Home terminal", ::home)
    }
    fun case(name: String, body: (JSONObject) -> Unit) {
        val entry = JSONObject().put("name", name).put("passed", false)
        cases.put(entry); preserve(result)
        try { body(entry); entry.put("passed", true); result.put("completed_cases", cases.length()) }
        catch (failure: Throwable) { entry.put("error", failure.stackTraceToString()); throw failure }
        finally { preserve(result) }
    }
    try {
        await("initial Home", ::home)
        openSettings()
        result.put("existing_layer_admission", verifyNativeMainLayerAdmission(instrumentation, activity, render.board) { partial ->
            result.put("existing_layer_admission", partial)
            preserve(result)
        })
        closeToHome()

        var retiredToolbar: View? = null
        for (panel in NativeMainPanel.entries) for (commit in listOf(false, true)) {
            case("${panel.name.lowercase()}_reverse_${if (commit) "commit" else "cancel"}") { entry ->
                await("Home before drag", ::home)
                val height = ui { main.height.toFloat() }
                val sign = if (panel == NativeMainPanel.SETTINGS) 1f else -1f
                val x = ui { main.width * .5f }
                val startY = height * if (sign > 0) .20f else .80f
                val slop = ui { ViewConfiguration.get(main.context).scaledTouchSlop.toFloat() }
                val crossedY = startY + sign * (slop * 2f + 2f)
                val down = SystemClock.uptimeMillis()
                var lastY = crossedY
                var streamOpen = true
                try {
                    ui {
                        val before = bindings()
                        entry.put("before_down", startSnapshot())
                        finger(MotionEvent.ACTION_DOWN, down, x, startY)
                        entry.put("after_down", startSnapshot())
                        finger(MotionEvent.ACTION_MOVE, down, x, crossedY)
                        entry.put("after_first_move", startSnapshot())
                        check(navigation.dragTarget == panel) {
                            "Main did not admit $panel drag; see before_down/after_down/after_first_move"
                        }
                        requireFullBind(entry, "start", before, panel == NativeMainPanel.LIBRARY)
                    }
                    await("first intermediate pose") { motion(panel).panelProgress > 0f &&
                        (panel != NativeMainPanel.LIBRARY || field("libraryToolbar") != null) }
                    val previewToolbar = ui {
                        (field("libraryToolbar") as? View)?.takeIf { panel == NativeMainPanel.LIBRARY }?.also { toolbar ->
                            check(toolbar !== retiredToolbar) { "A removed toolbar was silently reused" }
                            check(!main.admitsSurfaceInput(toolbar))
                            val action = admissionViews(toolbar).filterIsInstance<ReverbTopBarIconAction>()
                                .first().accessibilityAction
                            check(!action.requestFocusFromTouch()) { "New preview toolbar admitted focus before navigation commit" }
                            entry.put("new_preview_toolbar_blocked", true)
                        }
                    }
                    val samples = JSONArray().also { entry.put("deltas", it) }
                    fun move(fraction: Float) = ui {
                        val before = bindings()
                        val revision = admission()
                        lastY = startY + sign * height * fraction
                        finger(MotionEvent.ACTION_MOVE, down, x, lastY)
                        val after = bindings()
                        val observation = JSONObject().put("bind_refs_before", ids(before))
                            .put("bind_refs_after", ids(after)).put("admission_before", revision)
                            .put("admission_after", admission())
                        samples.put(observation)
                        check(sameBindings(before, after)) { "Drag delta fully rebound a retained screen" }
                        check(admission() == revision) { "Unchanged drag delta repeated admission cleanup" }
                    }
                    repeat(12) { move(.06f + .26f * (it + 1) / 12f) }
                    fun target() = if (panel == NativeMainPanel.SETTINGS)
                        navigation.settingsDragProgress else navigation.libraryDragProgress
                    await("outward motion") { kotlin.math.abs(motion(panel).panelProgress - target()) < .005f }
                    val outward = ui { motion(panel).panelProgress }
                    check(outward > 0f && outward < 1f)
                    repeat(12) { move(.32f - .24f * (it + 1) / 12f) }
                    await("reversed motion") { kotlin.math.abs(motion(panel).panelProgress - target()) < .005f }
                    val reversed = ui { motion(panel).panelProgress }
                    check(reversed >= 0f && reversed < outward) { "Pointer reversal did not reverse the displayed panel" }
                    if (commit) repeat(6) { move(.08f + .16f * (it + 1) / 6f) }
                    ui {
                        val before = bindings()
                        finger(if (commit) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL, down, x, lastY)
                        streamOpen = false
                        check(navigation.dragTarget == null)
                        requireFullBind(entry, "terminal", before, panel == NativeMainPanel.LIBRARY)
                        check((if (panel == NativeMainPanel.SETTINGS) navigation.showSettings else navigation.showLibrary) == commit)
                    }
                    await("panel terminal") { motion(panel).panelProgress == if (commit) 1f else 0f }
                    entry.put("outward_progress", outward.toDouble()).put("reversed_progress", reversed.toDouble())
                        .put("terminal_progress", ui { motion(panel).panelProgress }.toDouble())
                        .put("verified_delta_count", samples.length())
                    if (commit && previewToolbar != null) ui {
                        check(field("libraryToolbar") === previewToolbar && main.admitsSurfaceInput(previewToolbar))
                        val action = admissionViews(previewToolbar).filterIsInstance<ReverbTopBarIconAction>()
                            .first().accessibilityAction
                        check(action.requestFocusFromTouch() && main.findFocus() === action)
                        entry.put("committed_toolbar_admitted", true)
                    }
                    if (commit) closeToHome()
                    if (previewToolbar != null) ui {
                        check(!previewToolbar.isAttachedToWindow && field("libraryToolbar") == null)
                        check(main.findFocus()?.let { admissionWithin(it, previewToolbar) } != true)
                        retiredToolbar = previewToolbar
                        entry.put("toolbar_retired_and_focus_cleared", true)
                    }
                } finally {
                    if (streamOpen) ui { finger(MotionEvent.ACTION_CANCEL, down, x, lastY) }
                }
            }
        }

        for (theme in listOf(true, false)) case(if (theme) "theme_held_view_roundtrip" else "retention_held_view_roundtrip") { entry ->
            openSettings()
            val screen = ui { settings() }
            val baseline = ui { screen.owner.snapshot }
            fun revealGroup(): NativeSegmentedChoice {
                ui {
                    val viewport = checkNotNull(screen.listViewport)
                    entry.put("reveal_scroll_state_before", viewport.scrollState)
                    viewport.stopScroll()
                    entry.put("reveal_scroll_state_after_stop", viewport.scrollState)
                    val adapter = checkNotNull(viewport.adapter)
                    val key = if (theme) ReverbSettingsSectionKey.THEME else ReverbSettingsSectionKey.RETENTION
                    val position = (0 until adapter.itemCount).single { adapter.getItemViewType(it) == key.ordinal }
                    (viewport.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
                }
                awaitNativeParityDraw(instrumentation, render.board)
                var found: NativeSegmentedChoice? = null
                await("selector attached") {
                    found = admissionViews(screen).filterIsInstance<NativeSegmentedChoice>().firstOrNull { group ->
                        if (theme) group.choiceView("LIGHT") != null else
                            generateSequence(group.parent as? View) { it.parent as? View }.any { it is ReverbRetentionSection }
                    }
                    found?.let { it.isShown && it.isLaidOut && it.height > 0 } == true
                }
                return checkNotNull(found)
            }
            fun choose(group: NativeSegmentedChoice): NativeChip = ui {
                if (theme) checkNotNull(group.choiceView(if (screen.owner.snapshot.themeMode == AppThemeMode.LIGHT) "DARK" else "LIGHT"))
                else (0 until group.childCount).map(group::getChildAt).filterIsInstance<NativeChip>().single { !it.isChecked }
            }
            val selections = JSONArray().also { entry.put("neutral_pointer_selections", it) }
            fun exercise(target: NativeChip) {
                // Observe ordinary focus without clearing it or granting the selector focus.
                // An unrelated Settings control may legitimately own window focus.
                val selection = JSONObject().also(selections::put)
                tap(target) {
                    val focused = render.board.findFocus()
                    selection.put("target_focused_before_down", target.hasFocus())
                        .put("settings_focused_before_down", screen.hasFocus())
                        .put("neutral_owner_before_down", focused == null || focused === render.board)
                    val group = generateSequence(target.parent as? View) { it.parent as? View }
                        .filterIsInstance<NativeSegmentedChoice>().first()
                    selection.put("focused_class", focused?.javaClass?.name ?: JSONObject.NULL)
                        .put("focused_identity", focused?.let(System::identityHashCode) ?: JSONObject.NULL)
                        .put("focused_description", focused?.contentDescription?.toString() ?: JSONObject.NULL)
                        .put("selector_focused_before_down", group.hasFocus())
                        .put("focus_ancestry", JSONArray(generateSequence(focused) { it.parent as? View }
                            .map { it.javaClass.name }.toList()))
                    check(!group.hasFocus() && !target.hasFocus()) {
                        "First tap requires an unfocused selector; see recorded ambient focus"
                    }
                    check(main.admitsSurfaceInput(target) && screen.inputAllowed)
                }
                await("selector changes actual owner") {
                    target.isChecked && (if (theme) screen.owner.snapshot.themeMode != baseline.themeMode
                        else screen.owner.snapshot.retentionMode != baseline.retentionMode)
                }
                selection.put("pointer_changed_owner", true)
                // Independent focus assertion only after the unfocused pointer action succeeded.
                ui {
                    check(main.admitsSurfaceInput(target) && screen.inputAllowed)
                    check(target.requestFocusFromTouch() && main.findFocus() === target)
                }
                selection.put("separate_focus_admitted", true)
                ui { render.click(activity.getString(R.string.undo)) }
                await("Undo restored unsaved state") { screen.owner.snapshot == baseline && !screen.owner.state.hasUnsavedChanges }
            }
            val firstGroup = revealGroup()
            val held = choose(firstGroup)
            exercise(held)
            ui { check(held.requestFocusFromTouch()) }
            closeToHome()
            ui {
                check(!main.admitsSurfaceInput(screen))
                check(main.findFocus() !== held)
                check(!held.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                val acceptedFocus = held.requestFocusFromTouch()
                check(!held.isAttachedToWindow || !acceptedFocus)
                check(main.findFocus() !== held && screen.owner.snapshot == baseline)
                entry.put("held_hidden_action_rejected", true).put("held_hidden_focus_rejected", true)
            }
            openSettings()
            check(ui { settings() === screen }) { "Round trip replaced the retained Settings screen" }
            val currentGroup = revealGroup()
            entry.put("same_group_after_roundtrip", currentGroup === firstGroup)
                .put("held_attached_after_roundtrip", ui { held.isAttachedToWindow })
            exercise(choose(currentGroup))
            entry.put("root_pointer_selection_before_and_after", true)
            closeToHome()
        }
        check(cases.length() == 6 && result.getInt("completed_cases") == 6)
        result.put("restored_home", ui(::home)).put("passed", true)
        preserve(result)
        return result
    } catch (failure: Throwable) {
        result.put("error", failure.stackTraceToString())
        try { preserve(result) } catch (write: Throwable) { if (write !== failure) failure.addSuppressed(write) }
        throw failure
    }
}

private fun mainAdmissionField(owner: Any, name: String): Any? =
    owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
