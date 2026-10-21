package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Four finite own-QA-window keyboard flows. The ordinary accessibility Click/SetSelection actions
 * target a freshly observed unique editable node. Hardware keys come only from the sole external
 * coordinator's literal stage table, while the real IME is independently visible.
 *
 * No InputConnection is created here, no keyboard/focus mode is forced, no user IME/accessibility
 * setting is changed, and no production settings, capture, permissions, or files are accessed.
 * Focused captures are evidence, not a cursor-blink pixel oracle. Existing six static cases remain
 * a separate selector. This does not qualify soft-key composition or other IME implementations.
 */
internal fun verifyNativeRetentionKeyboardParity(
    instrumentation: Instrumentation,
    report: StringBuilder,
    keyboardSessionNonce: String?,
) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    checkNotNull(keyboardSessionNonce) { "The sole targeted keyboard coordinator is required" }
    check(Build.VERSION.SDK_INT >= 30) { "Physical display/IME window evidence requires API30+" }
    val directory = File(context.filesDir, "native-retention-keyboard-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val flows = JSONArray()
    val result = JSONObject().put("schema", 1).put("flows", flows).put("passed", false)
        .put("scope", "Actual own-QA focused editing with visible real IME and stage-bound hardware keys")
        .put("not_qualified", JSONArray(listOf("soft-key composition", "drag selection",
            "other IMEs", "production persistence", "cursor animation pixel/frame equality")))
    var host: NativeParityActivity? = null
    var mounted: AutoCloseableRetentionKeyboardField? = null
    var interactiveWindows: NativeParityInteractiveWindows? = null
    var failure: Throwable? = null
    var completed = 0
    try {
        writeRetentionKeyboardEvidence(directory, result, null)
        result.put("initial_discovery_regressions", JSONArray(verifyRetentionInitialDiscoveryChecks()))
        result.put("target_resolution_regressions", JSONArray(verifyRetentionEditableLabelChecks()))
        val windowScope = NativeParityInteractiveWindows.open(instrumentation, result).also { interactiveWindows = it }
        val activity = launchNativeParityActivity(instrumentation).also { host = it }
        awaitNativeParityDraw(instrumentation, activity.fixtureRoot)
        windowScope.recordHost(instrumentation, activity)
        val typography = Class.forName("app.smallthingz.reverb.ReverbThemeKt")
            .getDeclaredField("ReverbTypography").apply { isAccessible = true }.get(null) as Typography
        val referenceCallbacks = mutableMapOf<RetentionMode, List<String>>()
        for (mode in listOf(RetentionMode.TIME, RetentionMode.SIZE)) {
            for (reference in listOf(true, false)) {
                val renderer = if (reference) "compose" else "native"
                val modeName = if (mode == RetentionMode.TIME) "time" else "size"
                val id = "$modeName-$renderer"
                val label = "Retention QA $modeName"
                val original = if (mode == RetentionMode.TIME) "30" else "53.1"
                val edited = if (mode == RetentionMode.TIME) "42" else "54.2"
                val afterDelete = if (mode == RetentionMode.TIME) "2" else "5.2"
                val deleteStart = if (mode == RetentionMode.TIME) 0 else 1
                val rawOwner = KeyboardRawOwner(original)
                val entry = JSONObject().put("id", id).put("initial_raw", original)
                    .put("events", JSONArray()).put("keyboard_requests", JSONArray()).put("passed", false)
                flows.put(entry)
                requireNativeParityHost(instrumentation, activity)
                val field = nativeParityOnMain(instrumentation) {
                    showRetentionKeyboardField(activity, typography, mode, reference, label, rawOwner)
                }.also { mounted = it }
                awaitNativeParityDraw(instrumentation, field.content)
                awaitNativeParityDraw(instrumentation, field.content)
                val guard = RetentionKeyboardGuard(instrumentation, activity, label, rawOwner, entry,
                    referenceContent = field.content.takeIf { reference })
                val initial = guard.initialSnapshot()
                check(initial.target().getString("raw_text") == original)
                check(!initial.target().getBoolean("editable_focused")) { "Fixture must begin unfocused" }
                entry.put("initial", initial)

                guard.click()
                guard.await("focus-and-ime") { it.target().positiveIme() && it.target().getBoolean("editable_focused") }
                captureRetentionKeyboardField(instrumentation, activity, field.content, directory, "$id-focused")
                guard.select(0, original.length)
                var raw = original
                var selection = 0 to original.length
                for ((index, character) in edited.withIndex()) {
                    val next = edited.take(index + 1)
                    guard.key(directory, keyboardSessionNonce, renderer,
                        "retention-keyboard-$id-type-$index", keyName(character),
                        raw, selection, next, next.length to next.length, hide = false)
                    raw = next
                    selection = next.length to next.length
                }
                guard.select(deleteStart, deleteStart + 1)
                guard.key(directory, keyboardSessionNonce, renderer, "retention-keyboard-$id-delete",
                    "KEYCODE_DEL", raw, deleteStart to deleteStart + 1,
                    afterDelete, deleteStart to deleteStart, hide = false)
                captureRetentionKeyboardField(instrumentation, activity, field.content, directory, "$id-edited")
                guard.key(directory, keyboardSessionNonce, renderer, "retention-keyboard-$id-hide",
                    "KEYCODE_BACK", afterDelete, deleteStart to deleteStart,
                    afterDelete, deleteStart to deleteStart, hide = true)
                captureRetentionKeyboardField(instrumentation, activity, field.content, directory, "$id-hidden")

                guard.click()
                guard.await("reopened-ime") { it.target().positiveIme() && it.target().getBoolean("editable_focused") }
                guard.select(0, afterDelete.length)
                raw = afterDelete
                selection = 0 to afterDelete.length
                for ((index, character) in original.withIndex()) {
                    val next = original.take(index + 1)
                    guard.key(directory, keyboardSessionNonce, renderer,
                        "retention-keyboard-$id-restore-$index", keyName(character),
                        raw, selection, next, next.length to next.length, hide = false)
                    raw = next
                    selection = next.length to next.length
                }
                guard.key(directory, keyboardSessionNonce, renderer, "retention-keyboard-$id-final-hide",
                    "KEYCODE_BACK", original, original.length to original.length,
                    original, original.length to original.length, hide = true)
                val terminal = guard.snapshot()
                val callbacks = nativeParityOnMain(instrumentation) {
                    check(rawOwner.raw == original)
                    rawOwner.callbacks.toList()
                }
                val expectedCallbacks = edited.indices.map { edited.take(it + 1) } +
                    afterDelete + original.indices.map { original.take(it + 1) }
                check(callbacks == expectedCallbacks) {
                    "Unexpected raw owner callbacks for $id: $callbacks versus $expectedCallbacks"
                }
                if (reference) referenceCallbacks[mode] = callbacks
                else check(callbacks == referenceCallbacks.getValue(mode))
                entry.put("final", terminal).put("callbacks", JSONArray(callbacks))
                    .put("restored_original", true)
                nativeParityOnMain(instrumentation) { field.close() }
                mounted = null
                entry.put("passed", true)
                completed++
                writeRetentionKeyboardEvidence(directory, result, null)
            }
        }
        check(completed == 4)
    } catch (error: Throwable) {
        failure = error
        result.put("terminal_error", android.util.Log.getStackTraceString(error))
    } finally {
        fun cleanup(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                val earlier = failure
                if (earlier == null) failure = error else if (earlier !== error) earlier.addSuppressed(error)
                result.put("cleanup_error", android.util.Log.getStackTraceString(error))
            }
        }
        cleanup { mounted?.let { field -> nativeParityOnMain(instrumentation) { field.close() } } }
        mounted = null
        cleanup { host?.let { activity -> nativeParityOnMain(instrumentation) { activity.finish() } } }
        cleanup { interactiveWindows?.close() }
        result.put("completed_flows", completed).put("passed", failure == null && completed == 4)
        var evidenceWritten = false
        try {
            writeRetentionKeyboardEvidence(directory, result, null)
            evidenceWritten = true
        } catch (error: Throwable) {
            val earlier = failure
            if (earlier == null) failure = error else if (earlier !== error) earlier.addSuppressed(error)
            result.put("passed", false).put("terminal_error", checkNotNull(failure).stackTraceToString())
            // One bounded failure publication; an unsuccessful atomic write retains the earlier false receipt.
            try { writeRetentionKeyboardEvidence(directory, result, null); evidenceWritten = true }
            catch (writeFailure: Throwable) {
                if (writeFailure !== failure) checkNotNull(failure).addSuppressed(writeFailure)
            }
        }
        report.append(if (evidenceWritten) "PARITY_EVIDENCE " else "PARITY_EVIDENCE_WRITE_FAILED ")
            .append(directory.name).append("/result.json\n")
    }
    failure?.let { throw it }
    report.append("PASS native_retention_keyboard flows=4\n")
}

private class KeyboardRawOwner(initial: String) : RetentionKeyboardRawOwner {
    override var raw by mutableStateOf(initial)
        private set
    val callbacks = mutableListOf<String>()
    override fun changed(value: String) {
        callbacks += value
        raw = value
    }
}

/** Fresh current-window nodes only. Bounds describe the real editable node, never guessed taps. */
private class RetentionKeyboardGuard(
    private val instrumentation: Instrumentation,
    private val host: NativeParityActivity,
    private val label: String,
    private val rawOwner: KeyboardRawOwner,
    private val evidence: JSONObject,
    private val referenceContent: View?,
) {
    private var expectedWindowId: Int? = null
    private var expectedTargetIdentity: String? = null
    private var expectedSemanticsId: Int? = null

    private var initialDiscoveryAttempted = false

    fun initialSnapshot(): JSONObject {
        check(!initialDiscoveryAttempted) { "Initial editable discovery may only run once" }
        initialDiscoveryAttempted = true
        val discovery = RetentionInitialDiscovery(SystemClock.uptimeMillis())
        val attempts = JSONArray().also { evidence.put("initial_discovery", it) }
        while (true) {
            try { return snapshot(discovery) }
            catch (pending: RetentionInitialTargetNotPublished) {
                attempts.put(JSONObject().put("uptime_ms", SystemClock.uptimeMillis())
                    .put("window_id", discovery.windowId).put("matching_targets", 0))
                Thread.sleep(40)
            }
        }
    }

    fun snapshot(): JSONObject = snapshot(null)

    private fun snapshot(discovery: RetentionInitialDiscovery?): JSONObject = withTarget(discovery) { node, appWindow, imeWindow ->
        val ownerRaw = nativeParityOnMain(instrumentation) { rawOwner.raw }
        val bounds = Rect().also(node::getBoundsInScreen)
        check(!bounds.isEmpty && node.isEnabled && node.isVisibleToUser && node.isEditable)
        val identity = "window:${node.windowId}:label:$label:class:${node.className}:" +
            if (Build.VERSION.SDK_INT >= 33) node.uniqueId.orEmpty() else ""
        expectedWindowId?.let { check(node.windowId == it) { "Editable window changed" } }
        expectedTargetIdentity?.let { check(identity == it) { "Editable identity changed" } }
        expectedWindowId = node.windowId
        expectedTargetIdentity = identity
        val result = nativeParityOnMain(instrumentation) {
            val decor = host.window.decorView
            check(decor.isAttachedToWindow && decor.hasWindowFocus() && host.display?.displayId == 0)
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor)) { "No actual window insets" }
            val input = host.getSystemService(InputMethodManager::class.java)
            val selection = actualSelection(node)
            JSONObject().put("display_id", 0).put("window_title", host.window.attributes.title.toString())
                .put("decor_identity", System.identityHashCode(decor)).put("target_identity", identity)
                .put("scene_label", label).put("target_bounds", bounds.array())
                .put("observed_target", JSONObject().put("field_description", label)
                    .put("raw_text", node.text?.toString().orEmpty()).put("owner_raw", ownerRaw)
                    .put("selection_start", selection.getInt("start")).put("selection_end", selection.getInt("end"))
                    .put("selection_observation", selection)
                    .put("accessibility_selection_start", node.textSelectionStart)
                    .put("accessibility_selection_end", node.textSelectionEnd)
                    .put("editable_focused", node.isFocused)
                    .put("ime_visible", insets.isVisible(WindowInsetsCompat.Type.ime()))
                    .put("ime_accepting_text", input.isAcceptingText)
                    .put("ime_window", imeWindow ?: JSONObject.NULL)
                    .put("application_window_id", appWindow.id))
        }
        result
    }

    /** ContentDescription makes Compose export traversal-cursor indices, not editor selection. */
    private fun actualSelection(node: AccessibilityNodeInfo): JSONObject {
        val content = referenceContent ?: return JSONObject().put("source", "accessibility_editable_node")
            .put("start", node.textSelectionStart).put("end", node.textSelectionEnd)
            .put("raw_text", node.text?.toString().orEmpty()).put("focused", node.isFocused)
        check(content.isAttachedToWindow && content.isShown && content.rootView === host.window.decorView)
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
        }
        val roots = descendants(content).filterIsInstance<ViewRootForTest>().toList()
        check(roots.size == 1) { "Released editor does not have one current semantics owner" }
        val id = node.extras.getInt(COMPOSE_SEMANTICS_ID)
        check(expectedSemanticsId == id) { "Released editable semantics identity changed" }
        val semantics = roots.single().semanticsOwner.getAllSemanticsNodes(false).single { it.id == id }
        val config = semantics.config
        val raw = checkNotNull(config.getOrNull(SemanticsProperties.EditableText)).text
        check(config.getOrNull(SemanticsProperties.ContentDescription) == listOf(label))
        val focused = config.getOrNull(SemanticsProperties.Focused) == true
        val range = checkNotNull(config.getOrNull(SemanticsProperties.TextSelectionRange))
        check(range.start in 0..raw.length && range.end in 0..raw.length)
        return JSONObject().put("source", "compose_semantics_exact_accessibility_id")
            .put("semantics_id", id).put("semantics_owner_identity", System.identityHashCode(roots.single()))
            .put("raw_text", raw).put("focused", focused)
            .put("start", range.start).put("end", range.end)
    }

    fun click() {
        val before = snapshot()
        check(before.target().coherentSelectionObservation()) { "Editable observation changed before click" }
        withTarget { node, _, _ ->
            check(node.text?.toString().orEmpty() == before.target().getString("raw_text")) {
                "Editable text changed before its click"
            }
            check(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Editable click was rejected" }
        }
        record("click", before)
    }

    fun select(start: Int, end: Int) {
        val before = snapshot()
        check(before.target().coherentSelectionObservation()) { "Editable observation changed before selection" }
        check(before.target().positiveIme() && before.target().getBoolean("editable_focused"))
        check(start in 0..before.target().getString("raw_text").length &&
            end in 0..before.target().getString("raw_text").length)
        withTarget { node, _, _ ->
            check(node.text?.toString().orEmpty() == before.target().getString("raw_text")) {
                "Editable text changed before selection"
            }
            check(node.isFocused) { "Editable focus changed before selection" }
            check(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION })
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }
            check(node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)) {
                "Editable selection was rejected"
            }
        }
        val after = await("selection") { value ->
            value.target().getInt("selection_start") == start && value.target().getInt("selection_end") == end
        }
        record("select", JSONObject().put("before", before).put("after", after))
    }

    fun key(
        directory: File, nonce: String?, renderer: String, stage: String, key: String,
        beforeRaw: String, beforeSelection: Pair<Int, Int>,
        afterRaw: String, afterSelection: Pair<Int, Int>, hide: Boolean,
    ) {
        val before = snapshot()
        requireText(before, beforeRaw, beforeSelection)
        check(before.target().positiveIme() && before.target().getBoolean("editable_focused")) {
            "No positively observed real IME for $stage"
        }
        var requestPublished = false
        awaitNativeParityKeyboardCommand(
            instrumentation, directory, nonce, renderer, stage, key,
            guardedSnapshot = { snapshot().also { current ->
                if (!requestPublished) {
                    requireText(current, beforeRaw, beforeSelection)
                    check(current.target().positiveIme() && current.target().getBoolean("editable_focused"))
                }
            } },
            onRequest = { requestPublished = true; evidence.getJSONArray("keyboard_requests").put(it) },
            validateObservedAfter = {
                val after = await(stage) { value ->
                    val target = value.target()
                    target.getString("raw_text") == afterRaw &&
                        target.getString("owner_raw") == afterRaw &&
                        target.getInt("selection_start") == afterSelection.first &&
                        target.getInt("selection_end") == afterSelection.second &&
                        if (hide) !target.getBoolean("ime_visible") && target.optJSONObject("ime_window") == null
                        else target.positiveIme() && target.getBoolean("editable_focused")
                }
                requireText(after, afterRaw, afterSelection)
                record(stage, JSONObject().put("before", before).put("after", after))
            },
        )
    }

    fun await(reason: String, predicate: (JSONObject) -> Boolean): JSONObject {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        var last: JSONObject
        do {
            last = snapshot()
            if (last.target().coherentSelectionObservation() && predicate(last)) return last
            Thread.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        evidence.put("failed_observation", JSONObject().put("reason", reason).put("last", last))
        error("Timed out observing $reason; no repeated input or focus recovery")
    }

    private fun record(name: String, value: JSONObject) {
        evidence.getJSONArray("events").put(JSONObject().put("name", name)
            .put("uptime_ms", SystemClock.uptimeMillis()).put("observed", value))
    }

    private fun <T> withTarget(
        initialDiscovery: RetentionInitialDiscovery? = null,
        block: (AccessibilityNodeInfo, AccessibilityWindowInfo, JSONObject?) -> T,
    ): T {
        requireNativeParityHost(instrumentation, host)
        check(host.applicationInfo.uid == android.os.Process.myUid()) { "QA host UID changed" }
        val windows = instrumentation.uiAutomation.windows
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val labelPaths = mutableListOf<List<AccessibilityNodeInfo>>()
        val windowInventory = JSONArray()
        evidence.put("last_window_discovery", JSONObject()
            .put("uptime_ms", SystemClock.uptimeMillis()).put("uid", host.applicationInfo.uid)
            .put("package", host.packageName).put("expected_window_id", expectedWindowId ?: JSONObject.NULL)
            .put("windows", windowInventory))
        try {
            val observed = windows.map { window ->
                val root = window.root?.also(roots::add)
                windowInventory.put(JSONObject().put("window_id", window.id)
                    .put("type", window.type).put("display_id", window.displayId)
                    .put("focused", window.isFocused).put("active", window.isActive)
                    .put("layer", window.layer).put("title", window.title?.toString() ?: JSONObject.NULL)
                    .put("bounds", Rect().also(window::getBoundsInScreen).array())
                    .put("root_window_id", root?.windowId ?: JSONObject.NULL)
                    .put("root_package", root?.packageName?.toString() ?: JSONObject.NULL)
                    .put("root_class", root?.className?.toString() ?: JSONObject.NULL)
                    .put("own_package", root?.packageName?.toString() == host.packageName))
                window to root
            }
            check(windows.isNotEmpty()) { "No actual accessible windows after explicit test-service setup" }
            // Selection handles/toolbars may add nonfocused application windows. The editor's
            // pinned, uniquely focused physical host owns input; total application-window count
            // is not an ownership or focus invariant.
            val focused = observed.filter { (window, _) ->
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.displayId == 0 && window.isFocused
            }
            check(focused.size == 1) { "Physical application window is not uniquely focused" }
            val (window, nullableRoot) = focused.single()
            val root = checkNotNull(nullableRoot) { "Focused application window has no current root" }
            check(root.packageName?.toString() == host.packageName && root.windowId == window.id) {
                "Focused physical application window does not belong to the QA host"
            }
            expectedWindowId?.let { check(window.id == it) { "Editable application window changed" } }
            val inventory = JSONArray()
            val observation = JSONObject().put("uptime_ms", SystemClock.uptimeMillis())
                .put("window_id", window.id).put("window_focused", window.isFocused)
                .put("display_id", window.displayId).put("uid", host.applicationInfo.uid)
                .put("package", host.packageName).put("nodes", inventory)
            evidence.put("last_discovery", observation)
            fun visit(node: AccessibilityNodeInfo, ancestors: List<AccessibilityNodeInfo>) {
                val path = ancestors + node
                inventory.put(JSONObject().put("window_id", node.windowId)
                    .put("package", node.packageName?.toString() ?: JSONObject.NULL)
                    .put("class", node.className?.toString() ?: JSONObject.NULL)
                    .put("description", node.contentDescription?.toString() ?: JSONObject.NULL)
                    .put("text", node.text?.toString() ?: JSONObject.NULL)
                    .put("editable", node.isEditable).put("enabled", node.isEnabled)
                    .put("visible", node.isVisibleToUser).put("focused", node.isFocused)
                    .put("bounds", Rect().also(node::getBoundsInScreen).array())
                    .put("child_count", node.childCount))
                if (node.contentDescription?.toString() == label) labelPaths += path
                for (index in 0 until node.childCount) {
                    val child = checkNotNull(node.getChild(index)) { "Editable tree changed while reading child $index" }
                    roots += child
                    visit(child, path)
                }
            }
            visit(root, emptyList())
            observation.put("matching_labels", labelPaths.size)
            val resolved = retentionEditableLabelTarget(labelPaths) { it.isEditable }
            val matchingTargets = if (resolved == null) 0 else 1
            observation.put("matching_targets", matchingTargets)
            if (initialDiscovery != null &&
                !initialDiscovery.ready(window.id, matchingTargets, SystemClock.uptimeMillis())) {
                throw RetentionInitialTargetNotPublished()
            }
            val (node, labelNode) = checkNotNull(resolved) { "Expected one current editable '$label', observed 0" }
            check(node.refresh()) { "Editable node became stale before action/observation" }
            if (referenceContent != null) {
                check(COMPOSE_SEMANTICS_ID in node.availableExtraData) { "Released editor does not advertise its semantics identity" }
                check(node.refreshWithExtraData(COMPOSE_SEMANTICS_ID, Bundle())) { "Released editor identity became unavailable" }
                check(node.extras.containsKey(COMPOSE_SEMANTICS_ID)) { "Released editor omitted its semantics identity" }
                val semanticsId = node.extras.getInt(COMPOSE_SEMANTICS_ID)
                observation.put("editable_semantics_id", semanticsId)
                expectedSemanticsId?.let { check(semanticsId == it) { "Released editable semantics identity changed" } }
                expectedSemanticsId = semanticsId
            }
            if (labelNode != node) check(labelNode.refresh()) { "Editable label became stale before action/observation" }
            val labelBounds = Rect().also(labelNode::getBoundsInScreen)
            val bounds = Rect().also(node::getBoundsInScreen)
            observation.put("refreshed_label", JSONObject().put("window_id", labelNode.windowId)
                .put("package", labelNode.packageName?.toString() ?: JSONObject.NULL)
                .put("description", labelNode.contentDescription?.toString() ?: JSONObject.NULL)
                .put("enabled", labelNode.isEnabled).put("visible", labelNode.isVisibleToUser)
                .put("bounds", labelBounds.array()))
                .put("refreshed_editor", JSONObject().put("window_id", node.windowId)
                    .put("package", node.packageName?.toString() ?: JSONObject.NULL)
                    .put("editable", node.isEditable).put("enabled", node.isEnabled)
                    .put("visible", node.isVisibleToUser).put("focused", node.isFocused)
                    .put("bounds", bounds.array()))
            check(labelNode.contentDescription?.toString() == label && labelNode.isEnabled && labelNode.isVisibleToUser)
            check(node.windowId == window.id && node.packageName?.toString() == host.packageName)
            check(node.isEditable && node.isEnabled && node.isVisibleToUser && !bounds.isEmpty)
            // Synthetic Compose description nodes use layout bounds; the editor uses adjusted
            // touch bounds. Their fresh parent relation, not rectangle containment, binds the label.
            // Compose exports the exact label as a child of the editable semantics node. Native
            // EditText exports it on the editor itself. Revalidate only that observed ancestry;
            // never choose an unrelated editor by text, class, or screen position.
            var current = labelNode
            var reachedEditor = false
            var parentSteps = 0
            for (expected in labelPaths.single().asReversed()) {
                check(current == expected && current.windowId == window.id &&
                    current.packageName?.toString() == host.packageName) { "Editable label ancestry changed" }
                if (current.isEditable) {
                    check(current == node) { "Nearest editable label ancestor changed" }
                    reachedEditor = true
                    break
                }
                current = checkNotNull(current.parent) { "Editable label lost its editor ancestor" }.also(roots::add)
                parentSteps++
            }
            check(reachedEditor) { "Editable label no longer belongs to its editor" }
            observation.put("label_parent_steps", parentSteps).put("label_bounds", labelBounds.array())
                .put("editable_bounds", bounds.array())
            expectedWindowId?.let { check(node.windowId == it) { "Editable window changed before action" } }
            val identity = "window:${node.windowId}:label:$label:class:${node.className}:" +
                if (Build.VERSION.SDK_INT >= 33) node.uniqueId.orEmpty() else ""
            expectedTargetIdentity?.let { check(identity == it) { "Editable identity changed before action" } }
            val ime = windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.displayId == 0 }
            check(ime.size <= 1) { "Ambiguous current IME window" }
            val imePayload = ime.singleOrNull()?.let {
                val bounds = Rect().also(it::getBoundsInScreen)
                JSONObject().put("window_id", it.id).put("type", it.type).put("display_id", it.displayId)
                    .put("bounds", bounds.array())
            }
            return block(node, window, imePayload)
        } finally {
            roots.forEach { it.recycle() }
            windows.forEach { it.recycle() }
        }
    }
}

private const val COMPOSE_SEMANTICS_ID = "androidx.compose.ui.semantics.id"

private fun JSONObject.target(): JSONObject = getJSONObject("observed_target")
private fun JSONObject.coherentSelectionObservation(): Boolean {
    val selection = getJSONObject("selection_observation")
    return selection.getString("raw_text") == getString("raw_text") &&
        selection.getBoolean("focused") == getBoolean("editable_focused")
}
private fun JSONObject.positiveIme(): Boolean =
    getBoolean("ime_visible") && getBoolean("ime_accepting_text") &&
        optJSONObject("ime_window")?.let { it.getInt("type") == 2 && it.getInt("display_id") == 0 } == true
private fun requireText(value: JSONObject, raw: String, selection: Pair<Int, Int>) {
    val target = value.target()
    check(target.coherentSelectionObservation()) { "Editor selection and text snapshots have not converged" }
    check(target.getString("raw_text") == raw && target.getString("owner_raw") == raw)
    check(target.getInt("selection_start") == selection.first && target.getInt("selection_end") == selection.second)
}
private fun keyName(character: Char): String = when (character) {
    in '0'..'5' -> "KEYCODE_$character"
    '.' -> "KEYCODE_PERIOD"
    else -> error("Character outside finite retention key table")
}
private fun Rect.array(): JSONArray = JSONArray(listOf(left, top, right, bottom))
private fun captureRetentionKeyboardField(
    instrumentation: Instrumentation, activity: NativeParityActivity, content: View, directory: File, name: String,
) {
    val bitmap = captureNativeParityContent(instrumentation, activity) { content }
    try { saveNativeParityBitmap(directory, "$name.png", bitmap) } finally { bitmap.recycle() }
}
private fun writeRetentionKeyboardEvidence(directory: File, result: JSONObject, primary: Throwable?) {
    try {
        val atomic = AtomicFile(File(directory, "result.json"))
        val stream = atomic.startWrite()
        try {
            stream.write(result.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            try { atomic.failWrite(stream) } catch (cleanup: Throwable) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            throw error
        }
    } catch (error: Throwable) {
        if (primary == null) throw error else if (primary !== error) primary.addSuppressed(error)
    }
}
