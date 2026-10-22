package app.smallthingz.reverb

import android.app.Instrumentation
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.smallthingz.reverb.nativeui.NativeMergedRowAccessibilityHost
import app.smallthingz.reverb.nativeui.NativeSettingsScreenContent
import app.smallthingz.reverb.nativeui.NativeTextSemanticsClip
import app.smallthingz.reverb.nativeui.ReverbHomeScaffoldView
import app.smallthingz.reverb.nativeui.ReverbTopBarIconAction
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
            Triple("home_visible_while_settings_open", 0f, 3),
            Triple("half_visible", main.height / 2f, 3),
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
            if (name == "offscreen") {
                check(homeCount == 0 && settingsCount > 0) {
                    "Offscreen Home must be absent while the actual Settings subtree remains advertised"
                }
            } else if (expectedActions > 0) {
                check(homeCount > 0) { "$name: a present Home layer lost all its advertised descendants" }
            }
            record.put("passed", true)
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
    val source = NativeMainAccessibility(activity.fixtureRoot, root, { layers },
        ownNodeHidden = { hiddenHome && it === home },
        clip = { NativeTextSemanticsClip.Unclipped }, minimumTouch = { 0f })
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
