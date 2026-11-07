package app.smallthingz.reverb

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import smallthingz.ui.runtime.MonotonicFrameClock
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
            if (branch == "ready") {
                val dynamic = JSONArray()
                entry.put("dynamic_rows", dynamic)
                fun awaitPlaced(label: String, top: Boolean) {
                    val deadline = android.os.SystemClock.uptimeMillis() + 5000L
                    while (!nativeParityOnMain(instrumentation) { current.rowsBoundAndPlaced(top) }) {
                        if (android.os.SystemClock.uptimeMillis() >= deadline) {
                            entry.put("placement_failure", nativeParityOnMain(instrumentation) {
                                current.rowPlacementDiagnostics().put("case", label).put("expect_top", top)
                            })
                            error("Rows not accessible/placed at $label")
                        }
                        awaitNativeParityDraw(instrumentation, current.root)
                    }
                }
                fun placed(label: String, top: Boolean) {
                    awaitPlaced(label, top)
                    val advertisement = JSONObject().put("case", label)
                        .put("automation_flags", checkNotNull(automation.serviceInfo).flags)
                    entry.put("latest_direct_advertisement", advertisement)
                    nativeParityOnMain(instrumentation) { current.verifyColumnAdvertisement(advertisement) }
                    // The temporary unrelated-child assertion requests layout on add/remove.
                    // Resolve that traversal before collecting platform or title evidence.
                    awaitNativeParityDraw(instrumentation, current.root)
                    awaitPlaced(label + " after direct advertisement", top)
                    val titles = nativeParityOnMain(instrumentation) { current.visibleRowTitles() }
                    check(titles.isNotEmpty())
                    val evidence = onboardingNativeNodeEvidence(instrumentation, activity, current.root,
                        automation) { partial ->
                            entry.put("platform_failure_case", label).put("platform_partial", partial)
                            entry.put("placement_at_platform_failure", nativeParityOnMain(instrumentation) {
                                current.rowPlacementDiagnostics()
                            })
                            preserveFailure(result)
                        }
                    val raw = evidence.getJSONArray("raw")
                    for (title in titles) check((0 until raw.length()).any { index ->
                        val node = raw.getJSONObject(index)
                        node.getBoolean("visible") &&
                            (node.optString("text") == title || node.optString("description") == title)
                    }) { "Visible row missing from platform accessibility at $label: $title" }
                    dynamic.put(JSONObject().put("case", label).put("visible_titles", JSONArray(titles))
                        .put("direct_advertisement", advertisement)
                        .put("placement", nativeParityOnMain(instrumentation) { current.rowPlacementDiagnostics() })
                        .put("platform", evidence).put("passed", true))
                }
                nativeParityOnMain(instrumentation) { current.submitInitialRows() }
                placed("initial_async_rows_without_reopen", true)
                nativeParityOnMain(instrumentation) { current.prependRow("first") }
                placed("async_new_header_preserves_absolute_top", true)
                nativeParityOnMain(instrumentation) { current.scrollToNewRows() }
                placed("newly_created_scrolled_rows", false)
                val anchorBefore = nativeParityOnMain(instrumentation) { current.visibleAnchor() }
                val anchorEvidence = JSONObject().put("passed", false).put("before", anchorBefore.json())
                entry.put("anchor_preservation", anchorEvidence)
                nativeParityOnMain(instrumentation) { current.prependRow("second") }
                placed("async_insert_does_not_reset_scrolled_reader", false)
                val anchorAfter = nativeParityOnMain(instrumentation) { current.visibleAnchor() }
                anchorEvidence.put("after", anchorAfter.json())
                check(anchorAfter == anchorBefore) { "Async insertion moved the reader: $anchorBefore -> $anchorAfter" }
                anchorEvidence.put("passed", true)
                nativeParityOnMain(instrumentation) { current.openAtTop() }
                placed("explicit_reopen_returns_header_to_top", true)
            }
            val poses = JSONArray()
            entry.put("poses", poses)
            for (offscreen in listOf(false, true)) {
                val pose = JSONObject().put("offscreen", offscreen).put("passed", false)
                poses.put(pose)
                requireNativeParityHost(instrumentation, activity)
                val direct = JSONObject()
                pose.put("direct", direct)
                nativeParityOnMain(instrumentation) { current.pose(offscreen, direct) }
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

private data class LibraryAdmissionAnchor(val stableKey: String, val offsetPixels: Int) {
    fun json(): JSONObject = JSONObject().put("stable_key", stableKey).put("offset_pixels", offsetPixels)
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
    private var lease: NativePageTextAccessibilityOwner? = null
    private var fixtureRows = emptyList<LibraryListItem>()
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
                LibraryRecordingRowActions({ error("Synthetic row must not open") }, null, null), object : LibraryInlineExpansionHost {
                    override fun create(context: Context, recordingId: String): LibraryInlineExpansionLease =
                        error("Admission fixture must not create playback or recording owners")
                }, onRetryFailedSaves = { error("Admission fixture must not retry durable saves") },
                onRowNodesChanged = { if (!closed) lease?.refresh() })
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

    private fun recording(label: String) = LibraryListItem.Recording(RecordingEntity(
        id = "/synthetic-library/$label.wav", displayName = "QA Library row $label", mimeType = "audio/wav",
        startedAtMillis = 1_700_000_000_000L, durationMillis = 1000L, sizeBytes = 32044L,
        codecSummary = "PCM", storageType = RecordingStorageType.FILE, directoryId = "/synthetic-library"))

    private fun submitRows() = (content as ReverbLibraryListView).submit(LibraryNativeListState(
        fixtureRows, emptySet(), false, emptySet(), emptySet(), null, null, true))

    fun submitInitialRows() {
        fixtureRows = listOf(LibraryListItem.Header("QA initial date")) + (0 until 30).map { recording("initial-$it") }
        submitRows()
    }

    fun prependRow(label: String) {
        fixtureRows = listOf(LibraryListItem.Header("QA new date $label"), recording(label)) + fixtureRows
        submitRows()
    }

    fun scrollToNewRows() = (content as ReverbLibraryListView).restorePosition(NativeListSavedPosition(20, 17))
    fun openAtTop() = (content as ReverbLibraryListView).openAtTop()

    fun verifyColumnAdvertisement(evidence: JSONObject) {
        val columns = libraryAdmissionViews(content).filterIsInstance<ViewGroup>()
            .filter { it.javaClass.simpleName == "RecordingCardColumn" }.toList()
        check(columns.isNotEmpty())
        val records = JSONArray()
        evidence.put("columns", records).put("passed", false)
        for (column in columns) {
            val location = IntArray(2).also(column::getLocationOnScreen)
            val record = JSONObject().put("native_class", column.javaClass.name)
                .put("bounds", JSONArray(listOf(location[0],location[1],location[0]+column.width,location[1]+column.height)))
                .put("passed", false)
            records.put(record)
            val unrelated = View(activity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
            column.addView(unrelated, ViewGroup.LayoutParams(1,1))
            try {
                val advertised = arrayListOf(prefix)
                column.addChildrenForAccessibility(advertised)
                val children = JSONArray()
                record.put("advertised", children)
                for (child in advertised.drop(1)) {
                    val merged = child as? NativeMergedRowAccessibilityHost
                    val sourcePresent = if (merged != null) source.node(child).present else null
                    children.put(JSONObject().put("native_class", child.javaClass.name)
                        .put("source_present", sourcePresent ?: JSONObject.NULL)
                        .put("node_present", libraryAdmissionNodePresent(child)))
                    check(merged == null || sourcePresent == true) { "Column advertised absent source child" }
                    check(libraryAdmissionNodePresent(child)) { "Column advertised an unresolved child" }
                }
                check(advertised.firstOrNull() === prefix && unrelated in advertised) {
                    "Column discarded caller prefix or unrelated child"
                }
                val expected = (0 until column.childCount).map(column::getChildAt)
                    .filter { it is NativeMergedRowAccessibilityHost && independentlyVisibleInViewport(it) }
                check(expected.all { it in advertised }) { "Column omitted a visible merged child" }
                record.put("expected_visible_merged", expected.size).put("passed", true)
            } finally { column.removeView(unrelated) }
        }
        evidence.put("passed", true)
    }

    fun visibleAnchor(): LibraryAdmissionAnchor {
        val list = (content as ReverbLibraryListView).scrollView
        check(!list.isLayoutRequested && !list.isComputingLayout)
        val layout = list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
        val position = layout.findFirstVisibleItemPosition()
        check(position in fixtureRows.indices) { "Visible anchor is not a fixture item: $position" }
        val item = checkNotNull(layout.findViewByPosition(position))
        return LibraryAdmissionAnchor(libraryListItemKey(fixtureRows[position]),
            list.paddingTop - layout.getDecoratedTop(item))
    }

    fun rowPlacementDiagnostics(): JSONObject {
        val list = (content as ReverbLibraryListView).scrollView
        val layout = list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
        fun rect(view: View): JSONObject {
            val bounds = android.graphics.Rect()
            val global = view.getGlobalVisibleRect(bounds)
            return JSONObject().put("global_visible", global)
                .put("global_bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                .put("local_bounds", JSONArray(listOf(view.left, view.top, view.right, view.bottom)))
                .put("attached", view.isAttachedToWindow).put("laid_out", view.isLaidOut)
        }
        val rows = JSONArray()
        libraryAdmissionViews(list).filter { it is NativeLibrarySummaryInput }.forEach { view ->
            val node = source.node(view)
            rows.put(rect(view).put("class", view.javaClass.name)
                .put("click_present", (view as NativeLibrarySummaryInput).sourceClickPresent)
                .put("expected_viewport_visible", independentlyVisibleInViewport(view))
                .put("source_present", node.present).put("source_hidden", node.hidden)
                .put("platform_node_present", libraryAdmissionNodePresent(view))
                .put("titles", JSONArray(libraryAdmissionViews(view).filterIsInstance<ReverbParagraphView>()
                    .map { it.getText().toString() }.toList())))
        }
        return JSONObject().put("layout_requested", list.isLayoutRequested)
            .put("computing_layout", list.isComputingLayout)
            .put("first_visible", layout.findFirstVisibleItemPosition())
            .put("last_visible", layout.findLastVisibleItemPosition())
            .put("item_count", list.adapter?.itemCount ?: -1)
            .put("saved_position", (content as ReverbLibraryListView).savePosition().toString())
            .put("root", rect(root)).put("viewport", rect(list)).put("control", rect(control)).put("rows", rows)
    }

    /** Independent geometry oracle: Android global visibility ignores our unclipped fixture edges. */
    private fun independentlyVisibleInViewport(view: View): Boolean {
        val bounds = android.graphics.Rect()
        if (!view.getGlobalVisibleRect(bounds)) return false
        val visible = android.graphics.Region(bounds)
        for (boundary in listOf(root, (content as ReverbLibraryListView).scrollView)) {
            if (!boundary.getGlobalVisibleRect(bounds) || !visible.op(bounds, android.graphics.Region.Op.INTERSECT)) return false
        }
        // This fixed paragraph is drawn above the Library layer and owns its covered pixels.
        if (control.getGlobalVisibleRect(bounds)) visible.op(bounds, android.graphics.Region.Op.DIFFERENCE)
        return !visible.isEmpty
    }

    fun rowsBoundAndPlaced(top: Boolean): Boolean {
        val list = (content as ReverbLibraryListView).scrollView
        if (list.isLayoutRequested || list.isComputingLayout) return false
        val layout = list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
        val first = layout.findFirstVisibleItemPosition()
        if (first == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return false
        if (top && (first != 0 || layout.getDecoratedTop(checkNotNull(layout.findViewByPosition(first))) != list.paddingTop)) return false
        if (!top && first < 10) return false
        val visible = libraryAdmissionViews(list).filter {
            it is NativeLibrarySummaryInput && independentlyVisibleInViewport(it)
        }.toList()
        return visible.isNotEmpty() && visible.all {
            (it as NativeLibrarySummaryInput).sourceClickPresent && libraryAdmissionNodeVisible(it)
        }
    }

    fun visibleRowTitles(): List<String> = libraryAdmissionViews(content).filterIsInstance<ReverbParagraphView>()
        .filter { it.getText().startsWith("QA Library row ") && independentlyVisibleInViewport(it) }
        .map { it.getText().toString() }.toList()

    fun pose(offscreen: Boolean, evidence: JSONObject): JSONObject {
        content.translationY = if (offscreen) root.height * 2f else 0f
        check(source.node(layer).present) { "Fixture must keep the enclosing layer present" }
        check(source.node(control).present) { "Fixture's fixed control must remain present" }
        check(semantics.all { source.node(it).present != offscreen }) { "$branch source geometry did not establish the requested pose" }
        // Only advertised edges are reachable by an accessibility client. Walking every physical
        // descendant would query children of the deliberately excluded offscreen RecyclerView.
        val parents = java.util.ArrayDeque<ViewGroup>()
        val visited = java.util.IdentityHashMap<ViewGroup, Boolean>()
        val traversal = JSONArray()
        evidence.put("reachable_parents", traversal).put("passed", false)
        parents.add(content)
        while (parents.isNotEmpty()) {
            val parent = parents.removeFirst()
            if (visited.put(parent, true) != null) continue
            check(visited.size <= 1024) { "Library advertisement traversal exceeded fixture bounds" }
            val location = IntArray(2).also(parent::getLocationOnScreen)
            val record = JSONObject().put("native_class", parent.javaClass.name)
                .put("bounds", JSONArray(listOf(location[0],location[1],location[0]+parent.width,location[1]+parent.height)))
                .put("source_present", source.node(parent).present).put("passed", false)
            traversal.put(record)
            val children = arrayListOf(prefix)
            parent.addChildrenForAccessibility(children)
            val advertised = JSONArray()
            record.put("advertised_children", advertised)
            check(children.firstOrNull() === prefix) { "Library parent changed its caller prefix: ${parent.javaClass.name}" }
            for (child in children.drop(1)) {
                val present = libraryAdmissionNodePresent(child)
                advertised.put(JSONObject().put("native_class", child.javaClass.name)
                    .put("source_present", source.node(child).present).put("node_present", present))
                check(present) { "Reachable Library parent ${parent.javaClass.name} advertised missing ${child.javaClass.name}" }
                if (child is ViewGroup) parents.add(child)
            }
            record.put("passed", true)
        }
        evidence.put("physical_parent_count", libraryAdmissionViews(content).filterIsInstance<ViewGroup>().count())
            .put("reachable_parent_count", visited.size)
        val children = arrayListOf(prefix)
        content.addChildrenForAccessibility(children)
        check(unknown in children) { "Library parent discarded an unknown child" }
        return evidence.put("passed", true).put("source_layer_present", true).put("semantic_nodes", semantics.size)
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

private fun libraryAdmissionNodeVisible(view: View): Boolean {
    val node = view.createAccessibilityNodeInfo() ?: return false
    return try { node.isVisibleToUser } finally {
        @Suppress("DEPRECATION")
        node.recycle()
    }
}

private fun libraryAdmissionNodePresent(view: View): Boolean {
    val node = view.createAccessibilityNodeInfo() ?: return false
    @Suppress("DEPRECATION")
    node.recycle()
    return true
}

private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
