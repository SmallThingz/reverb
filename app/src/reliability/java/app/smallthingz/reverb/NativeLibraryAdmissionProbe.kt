package app.smallthingz.reverb

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.smallthingz.nativeui.runtime.MonotonicFrameClock
import app.smallthingz.reverb.nativeui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject

/** Real Library containers and source geometry only; no catalog, recordings, or interaction. */
internal fun verifyNativeLibraryAdmission(
    instrumentation: Instrumentation,
    activity: NativeParityActivity,
    board: FrameLayout,
    automation: UiAutomation,
    environment: ReverbViewEnvironment,
    parentScope: CoroutineScope,
    text: NativeWindowTextAccessibilityOwner,
    preserveFailure: (JSONObject) -> Unit,
): JSONObject {
    requireNativeParityHost(instrumentation, activity)
    val result = JSONObject().put("passed", false)
        .put("scope", "Library Ready/Empty/Error child advertisement, present and offscreen")
        .put("unqualified", JSONArray(listOf("recordings/catalog", "motion", "TalkBack")))
    val branches = JSONArray()
    result.put("branches", branches)
    val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    var fixture: LibraryAdmissionFixture? = null
    var failure: Throwable? = null
    try {
        for (branch in listOf("ready", "empty", "error")) {
            val entry = JSONObject().put("branch", branch).put("passed", false)
            branches.put(entry)
            val current = nativeParityOnMain(instrumentation) {
                LibraryAdmissionFixture(activity, board, environment, scope, text, branch)
            }
            fixture = current
            val poses = JSONArray()
            entry.put("poses", poses)
            for (offscreen in listOf(false, true)) {
                val pose = JSONObject().put("offscreen", offscreen).put("passed", false)
                poses.put(pose)
                requireNativeParityHost(instrumentation, activity)
                pose.put("direct", nativeParityOnMain(instrumentation) { current.pose(offscreen) })
                val importance = JSONObject()
                pose.put("importance", importance)
                verifyNativeMainImportanceTraversal(automation, importance) { traversal ->
                    val evidence = onboardingNativeNodeEvidence(instrumentation, activity, current.root,
                        automation) { partial ->
                        traversal.put("platform_partial", partial)
                        preserveFailure(result)
                    }
                    traversal.put("platform", evidence)
                    val raw = evidence.getJSONArray("raw")
                    check((0 until raw.length()).any { index ->
                        val node = raw.getJSONObject(index)
                        node.optString("text") == current.controlText && node.getBoolean("visible")
                    }) { "Fixture's fixed visible control paragraph is missing" }
                    for (marker in current.markers) {
                        val present = (0 until raw.length()).any { index ->
                            val node = raw.getJSONObject(index)
                            node.optString("text") == marker || node.optString("description") == marker
                        }
                        check(present != offscreen) { "$branch: unexpected platform admission for $marker" }
                    }
                    evidence.put("raw_nodes", raw.length()).put("markers_present", !offscreen)
                        .put("strict_missing_child_check", true)
                }
                pose.put("passed", true)
            }
            entry.put("unbound_fallback", nativeParityOnMain(instrumentation) { current.verifyUnboundFallback() })
            nativeParityOnMain(instrumentation) { current.close() }
            fixture = null
            entry.put("passed", true).put("removed", true)
        }
        requireNativeParityHost(instrumentation, activity)
        return result.put("passed", true)
    } catch (error: Throwable) {
        failure = error
        result.put("error", error.stackTraceToString())
        try { preserveFailure(result) } catch (preservation: Throwable) {
            if (preservation !== error) error.addSuppressed(preservation)
        }
        throw error
    } finally {
        try { nativeParityOnMain(instrumentation) { fixture?.close() } }
        catch (cleanup: Throwable) {
            result.put("cleanup_error", cleanup.stackTraceToString())
            val primary = failure
            if (primary == null) throw cleanup
            if (primary !== cleanup) primary.addSuppressed(cleanup)
        }
        finally { scope.cancel() }
    }
}

private class LibraryAdmissionFixture(
    private val activity: NativeParityActivity,
    private val board: FrameLayout,
    environment: ReverbViewEnvironment,
    scope: CoroutineScope,
    text: NativeWindowTextAccessibilityOwner,
    private val branch: String,
) : AutoCloseable {
    val root = FrameLayout(activity)
    private val layer = FrameLayout(activity)
    val controlText = "QA Library admission control"
    private val control = environment.paragraph(activity, controlText,
        ReverbTextRole.BODY_MEDIUM, environment.palette.primary)
    private val content: ViewGroup
    private val unknown = View(activity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
    private val prefix = View(activity)
    private val source: NativeMainAccessibility
    private val semantics = ArrayList<View>()
    private val unboundAdmission = ArrayList<Boolean>()
    val markers = ArrayList<String>()
    private var lease: AutoCloseable? = null
    private var closed = false
    private val frames = checkNotNull(scope.coroutineContext[MonotonicFrameClock])
    private val nested = object : NativeVerticalNestedHost {
        override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
        override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
        override suspend fun preFling(availableY: Float) = 0f
        override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
    }

    init {
        content = if (branch == "ready") {
            ReverbLibraryListView(activity, environment, scope, board,
                LibraryRecordingRowActions(null, null, null), object : LibraryInlineExpansionHost {
                    override fun create(context: Context, recordingId: String): LibraryInlineExpansionLease =
                        error("Admission fixture must not create playback or recording owners")
                })
        } else ReverbLibraryStatusView(activity, environment, frames, scope, nested, board) { 1f }.apply {
            bind(if (branch == "empty") LibraryBodyMode.EMPTY else LibraryBodyMode.FAILED) {
                error("Admission fixture must not invoke Retry")
            }
        }
        source = NativeMainAccessibility(board, root, { listOf(layer, control) }, { false },
            clip = { NativeTextSemanticsClip.Unclipped }, minimumTouch = { view ->
                if (view is NativeClickPhaseHost) 48f * environment.textDensity.density else 0f
            })
        try {
            val width = minOf(board.width, 720)
            val height = minOf(board.height, 720)
            check(width > 0 && height > 0)
            root.clipChildren = false; root.clipToPadding = false
            layer.clipChildren = false; layer.clipToPadding = false
            root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            root.addView(layer, FrameLayout.LayoutParams(-1, -1))
            root.addView(control, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
            layer.addView(content, FrameLayout.LayoutParams(-1, -1))
            content.addView(unknown, ViewGroup.LayoutParams(1, 1))
            board.addView(root, FrameLayout.LayoutParams(width, height))
            root.measure(exact(width), exact(height)); root.layout(0, 0, width, height)
            when (val current = content) {
                is ReverbLibraryListView -> current.scrollView.let { viewport ->
                    val marker = "QA Library Ready viewport"
                    viewport.contentDescription = marker
                    recordSemantic(viewport)
                    viewport.sourceAccessibilityNode = { source.node(viewport) }
                    markers += marker
                }
                is ReverbLibraryStatusView -> if (branch == "empty") {
                    val viewport = checkNotNull(current.emptyViewport)
                    val marker = "QA Library Empty viewport"
                    viewport.contentDescription = marker
                    recordSemantic(viewport)
                    viewport.sourceAccessibilityNode = { source.node(viewport) }
                    markers += marker
                } else {
                    val paragraphs = libraryAdmissionViews(current).filterIsInstance<ReverbParagraphView>().toList()
                    check(paragraphs.size == 2)
                    paragraphs.forEachIndexed { index, paragraph ->
                        val marker = "QA Library Error $index"
                        paragraph.setText(marker); recordSemantic(paragraph); markers += marker
                    }
                    libraryAdmissionViews(current).filterIsInstance<NativeMergedRowAccessibilityHost>()
                        .forEach { recordSemantic(it as View) }
                }
            }
            lease = text.bindContent(root, NativeTextSemanticsClipPolicy { NativeTextSemanticsClip.Unclipped },
                source::node) { source.node(it).present }
            // Text binding may request layout. Fixed parent parameters preserve this geometry on subsequent traversals.
            root.measure(exact(width), exact(height)); root.layout(0, 0, width, height)
            check(semantics.isNotEmpty())
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    }

    fun pose(offscreen: Boolean): JSONObject {
        content.translationY = if (offscreen) root.height * 2f else 0f
        check(source.node(layer).present) { "Fixture must keep the enclosing layer present" }
        check(source.node(control).present) { "Fixture's fixed control must remain present" }
        check(semantics.all { source.node(it).present != offscreen }) { "$branch source geometry did not establish the requested pose" }
        val parents = libraryAdmissionViews(content).filterIsInstance<ViewGroup>().toList()
        for (parent in parents) {
            val children = arrayListOf(prefix)
            parent.addChildrenForAccessibility(children)
            check(children.firstOrNull() === prefix) { "Library parent changed its caller prefix" }
            check(children.drop(1).all(::libraryAdmissionNodePresent)) { "Library parent advertised a missing current node" }
        }
        val children = arrayListOf(prefix)
        content.addChildrenForAccessibility(children)
        check(unknown in children) { "Library parent discarded an unknown child" }
        return JSONObject().put("source_layer_present", true).put("semantic_nodes", semantics.size)
            .put("semantic_nodes_present", !offscreen).put("prefix_and_unknown_preserved", true)
    }

    fun verifyUnboundFallback(): Boolean {
        clearBindings()
        check(semantics.indices.all { libraryAdmissionNodePresent(semantics[it]) == unboundAdmission[it] }) {
            "Unbound Library nodes did not restore their original fallback"
        }
        val children = arrayListOf(prefix)
        content.addChildrenForAccessibility(children)
        check(children.firstOrNull() === prefix && unknown in children)
        return true
    }

    private fun recordSemantic(view: View) {
        semantics += view
        unboundAdmission += libraryAdmissionNodePresent(view)
    }

    private fun clearBindings() {
        val previous = lease
        lease = null
        try { previous?.close() } finally {
            (content as? ReverbLibraryListView)?.scrollView?.sourceAccessibilityNode = null
            (content as? ReverbLibraryStatusView)?.emptyViewport?.sourceAccessibilityNode = null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { clearBindings() } finally {
            try { (content as? ReverbLibraryListView)?.clearListComposition() }
            finally { if (root.parent === board) board.removeView(root) }
        }
        check(!root.isAttachedToWindow)
    }
}

private fun libraryAdmissionViews(root: View): Sequence<View> = sequence {
    yield(root)
    if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(libraryAdmissionViews(root.getChildAt(index)))
}

private fun libraryAdmissionNodePresent(view: View): Boolean {
    val node = view.createAccessibilityNodeInfo() ?: return false
    @Suppress("DEPRECATION")
    node.recycle()
    return true
}

private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
