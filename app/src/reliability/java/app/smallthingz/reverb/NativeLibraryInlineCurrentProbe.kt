package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import app.smallthingz.reverb.nativeui.ReverbLibraryListView
import app.smallthingz.reverb.nativeui.ReverbLibraryRecordingRow
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.reverb.ReverbInlineRecordingInfo
import smallthingz.ui.reverb.ReverbRecordingSpotlight

/**
 * Current UI only. Real Library card/controller and QA-owned MediaStore transactions.
 * Local phase-root touch and accessibility editing, with a real visible IME observation.
 * No shell keys, global input, microphone, capture service, settings writes or focus recovery.
 */
internal fun verifyNativeLibraryInlineCurrent(i: Instrumentation, report: StringBuilder) {
    val context = i.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java && Build.VERSION.SDK_INT >= 31)
    val directory = File(context.filesDir, "native-library-inline-current-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("selector", "native_library_inline_current").put("passed", false)
        .put("expected_cases", 5).put("completed_cases", 0).put("cases", cases)
        .put("scope", "Production inline card, phase pointer binding, accessibility edit actions, positive IME and owned physical rename")
        .put("stale_input_scope", "Direct calls to production InputConnections retained before detach/retirement; owner state observed without a production test hook")
        .put("not_qualified", JSONArray(listOf("physical touch transport", "hardware keys", "soft-key composition",
            "TalkBack traversal", "other IMEs", "all configurations", "accepted rename across process death",
            "full MainActivity inset wiring", "platform scheduling of stale IME callbacks")))
    val preferences = getRecorderPreferences(context).all.toMap()
    val feedback = AppFeedbackCenter.events.value.toList()
    check(recordingMutations.activeIds.value.isEmpty())
    var host: NativeParityActivity? = null
    var board: TransactionBoard? = null
    var ledger: TransactionFixtures? = null
    var windows: NativeParityInteractiveWindows? = null
    var failure: Throwable? = null
    val retainedConnections = ArrayList<InputConnection>()
    fun persist() = writeQaEvidenceAtomically(File(directory, "result.json")) {
        it.write(result.toString(2).toByteArray())
    }
    fun <T> main(action: () -> T): T = nativeParityOnMain(i, action)
    try {
        persist()
        recoverEmptyLibraryFixtureJournal(context, result, ::persist)
        val windowScope = NativeParityInteractiveWindows.open(i, result).also { windows = it }
        val activity = launchCurrentUiSmokeActivity(i).also { host = it }
        awaitNativeParityDraw(i, activity.fixtureRoot)
        windowScope.recordHost(i, activity)
        val owned = TransactionFixtures(context, directory, "native", currentUi = true,
            namePrefix = "Reverb QA inline ${java.util.UUID.randomUUID()}").also { ledger = it }
        owned.admit()
        // Enough real, ledger-owned rows to place the final recording at the viewport bottom.
        val records = owned.create(count = 12)
        val transitions = JSONArray().also { result.put("window_transitions", it) }
        val current = main { TransactionBoard(activity, false, records, transitions,
            fitCurrentWindowInsets = true, inlineCurrent = true) { board = it } }
        current.awaitReady(i, records.map { it.id }.toSet())
        fun screen() = current.inlineScreen()
        fun list() = inlineViews(screen()).filterIsInstance<ReverbLibraryListView>().single()
        fun card(id: String): ReverbLibraryRecordingRow =
            checkNotNull(list().attachedRecordingAnchor(id)) as ReverbLibraryRecordingRow
        fun editor(): EditText = inlineViews(screen()).filterIsInstance<EditText>().single { it.isShown }
        fun capture(name: String) {
            val bitmap = captureNativeParityContent(i, activity) { current.inlineInputRoot() }
            try { saveNativeParityBitmap(directory, "$name.png", bitmap) } finally { bitmap.recycle() }
        }
        fun scenario(name: String, action: (JSONObject) -> Unit) {
            val entry = JSONObject().put("name", name).put("passed", false)
            cases.put(entry); persist()
            try {
                action(entry)
                entry.put("passed", true)
                result.put("completed_cases", cases.length())
            } catch (error: Throwable) {
                entry.put("error", error.stackTraceToString())
                entry.put("inventory", current.failureInventory(i))
                throw error
            } finally { persist() }
        }
        fun open(record: RecordingEntity, label: Int) {
            current.openMenu(i, record.displayName)
            current.click(i, context.getString(label))
            current.await(i, "inline action in original Activity window") {
                activity.hasWindowFocus() && screen().rootView === activity.window.decorView &&
                    (if (label == R.string.recording_info) screen().currentState()?.infoRecording?.id
                    else screen().currentState()?.renameRecording?.id) == record.id
            }
        }
        /** Coordinates are observed screen pixels; dispatch always enters the actual phase root. */
        fun tapAt(x: Float, y: Float) {
            val root = current.inlineInputRoot()
            val bounds = inlineBounds(root)
            check(bounds.contains(x.toInt(), y.toInt()))
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                    x - bounds.left, y - bounds.top, 0)
                try { check(root.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
        }
        fun renameAction(label: String): View {
            val leaf = inlineViews(screen()).filterIsInstance<ParagraphView>()
                .single { it.isShown && it.getText().toString() == label }
            var target: View = leaf
            while (!target.isClickable) target = target.parent as? View ?: error("No action for $label")
            check(target.isEnabled && target.isShown)
            return target
        }
        fun awaitRenameControls(entry: JSONObject, label: String, validation: Boolean = false) {
            val deadline = SystemClock.uptimeMillis() + 8_000L
            while (true) {
                val ime = current.renameKeyboardSnapshot(i, windowScope)
                val target = ime.getJSONObject("observed_target")
                val imeTop = target.getJSONObject("ime_window").getJSONArray("bounds").getInt(1)
                val ready = main {
                    val controls = linkedMapOf(
                        "cancel" to renameAction(context.getString(R.string.cancel)),
                        "done" to renameAction(context.getString(R.string.done)))
                    if (validation) controls["validation"] = inlineViews(screen()).filterIsInstance<ParagraphView>()
                        .single { it.isShown && it.getText().toString() == context.getString(R.string.rename_recording_illegal_chars) }
                    val observations = JSONObject().put("ime", ime)
                    val visible = controls.map { (name, view) ->
                        val full = inlineBounds(view)
                        val clipped = Rect()
                        val shown = view.getGlobalVisibleRect(clipped)
                        observations.put(name, JSONObject().put("bounds", full.flattenToString())
                            .put("visible", clipped.flattenToString()).put("shown", shown))
                        shown && full == clipped && full.height() > 0 && full.bottom <= imeTop &&
                            current.inlineAccessibilityNode(view).let { it.present && !it.hidden && it.visibleToUser }
                    }
                    entry.put(label, observations)
                    target.getBoolean("positive_ime") && visible.all { it }
                }
                if (ready) return
                check(SystemClock.uptimeMillis() < deadline) { "Rename $label remained clipped or below the actual IME" }
                current.waitFrame(i)
            }
        }
        fun outside(id: String, cancel: Boolean, evidence: JSONObject) = main {
            val root = current.inlineInputRoot()
            val target = card(id)
            val rowBounds = inlineBounds(target)
            val rootBounds = inlineBounds(root)
            // The list's horizontal margin is outside the card but inside the actual phase root.
            val x = rootBounds.left + 1f
            val y = (maxOf(rootBounds.top, rowBounds.top) + minOf(rootBounds.bottom, rowBounds.bottom)) / 2f
            check(!rowBounds.contains(x.toInt(), y.toInt()) && rootBounds.contains(x.toInt(), y.toInt()))
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN,
                if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    x - rootBounds.left, y - rootBounds.top, 0)
                try { check(root.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            evidence.put(if (cancel) "outside_cancel" else "outside_up",
                JSONObject().put("x", x).put("y", y).put("card", rowBounds.flattenToString()))
        }
        fun tapEditorThroughPhaseRoot(entry: JSONObject) {
            val field = main { editor().also { it.clearFocus() } }
            current.waitFrame(i)
            main {
                check(!field.isFocused) { "Field must begin unfocused so missing interop cannot pass" }
                val root = current.inlineInputRoot()
                val a = inlineBounds(field)
                val b = inlineBounds(root)
                val visible = Rect()
                check(field.getGlobalVisibleRect(visible))
                val x = visible.exactCenterX() - b.left
                val y = visible.exactCenterY() - b.top
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
                    try { check(root.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                entry.put("phase_editor_tap", JSONObject().put("field", a.flattenToString()).put("x", x).put("y", y))
            }
            current.await(i, "phase root gives actual Rename field focus") { editor() === field && field.isFocused }
        }
        fun staleInput(connection: InputConnection, field: EditText, owner: NativeRecordingRenameOwner,
                       evidence: JSONObject, label: String, reattached: Boolean = false) {
            // Reattachment must not revive this connection even when the SAME editor is eligible.
            if (reattached) check(field.isAttachedToWindow && field.isShown && field.isFocused && field.hasWindowFocus())
            else check(!field.isAttachedToWindow && !field.hasWindowFocus())
            val state = owner.state
            val raw = field.text.toString()
            val selection = field.selectionStart to field.selectionEnd
            val observed = JSONObject().put("editor_identity", System.identityHashCode(field))
                .put("connection_class", connection.javaClass.name)
                .put("reattached", reattached)
                .put("owner_before", state.toString()).put("raw_before", raw)
            evidence.put(label, observed)
            val textAccepted = connection.commitText("stale-connection", 1)
            observed.put("commit_text_returned", textAccepted)
            check(!textAccepted && field.text.toString() == raw && owner.state == state) { "Retired connection accepted text" }
            val doneAccepted = connection.performEditorAction(EditorInfo.IME_ACTION_DONE)
            observed.put("done_returned", doneAccepted)
            check(!doneAccepted && owner.state == state && !owner.state.isRenaming) { "Retired connection accepted Done" }
            check(field.text.toString() == raw && field.selectionStart == selection.first &&
                field.selectionEnd == selection.second)
            check(recordingMutations.activeIds.value.isEmpty())
            observed.put("owner_after", owner.state.toString()).put("raw_after", field.text.toString())
                .put("no_acceptance_or_draft_change", true)
        }
        val first = records.first()
        scenario("info-three-fields-outside-cancel-and-dismiss") { entry ->
            val original = main { card(first.id) }
            val beforeHeight = main { original.height }
            val location = describeRecordingLocation(context, first)
            val required = listOf(R.string.recording_details_codec, R.string.recording_details_mime,
                R.string.recording_details_location).map { context.getString(it).removeSuffix(":") }
            open(first, R.string.recording_info)
            current.await(i, "Info expanded in same card with resolved metadata") {
                card(first.id) === original && original.height > beforeHeight &&
                    inlineViews(original).filterIsInstance<ParagraphView>().let { leaves ->
                        leaves.any { it.getText().toString() == location } &&
                            leaves.filter { it.getText().toString().removeSuffix(":") in required }.let { labels ->
                                labels.size == 3 && labels.all { current.inlineAccessibilityNode(it).let { n -> n.present && !n.hidden } }
                            }
                    }
            }
            main {
                val labels = inlineViews(original).filter { it.isShown }.filterIsInstance<ParagraphView>()
                    .map { it.getText().toString().removeSuffix(":") }
                val excluded = listOf(R.string.recording_details_started, R.string.recording_details_duration,
                    R.string.recording_details_size, R.string.recording_details_storage, R.string.close)
                    .map { context.getString(it).removeSuffix(":") }
                check(required.all { label -> labels.count { it == label } == 1 })
                check(excluded.none { it in labels })
                check(first.codecSummary in labels && first.mimeType in labels &&
                    location in labels)
                check(labels.size == original.paragraphLeaves.count { it.isShown } + 6) {
                    "Info must add exactly three label/value pairs to the existing summary"
                }
                entry.put("labels", JSONArray(labels)).put("same_card_identity", System.identityHashCode(original))
                check(screen().currentState()?.selectedCount == 0 && screen().currentState()?.list?.expandedOwner == null)
                val background = inlineViews(screen()).filterIsInstance<ReverbLibraryRecordingRow>()
                    .filter { it !== original && it.isShown }
                check(background.isNotEmpty())
                check(background.all { it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS })
                val infoLeaves = inlineViews(original).filterIsInstance<ParagraphView>()
                    .filter { it.isShown && it.getText().toString().removeSuffix(":") in required }
                check(infoLeaves.all { current.inlineAccessibilityNode(it).let { node -> node.present && !node.hidden } }) {
                    "Full-screen scrim occluded the active Info card's source accessibility nodes"
                }
                check(background.flatMap { inlineViews(it) }.filterIsInstance<ParagraphView>()
                    .none { current.inlineAccessibilityNode(it).let { node -> node.present && !node.hidden } })
            }
            capture("info-expanded")
            outside(first.id, true, entry)
            current.waitFrame(i)
            main { check(screen().currentState()?.infoRecording?.id == first.id) }
            current.await(i, "fully revealed Info padding for rounded-corner probe") {
                val info = inlineViews(original).filterIsInstance<ReverbInlineRecordingInfo>().single()
                val visible = Rect()
                info.getGlobalVisibleRect(visible) && visible == inlineBounds(info)
            }
            val corner = main {
                val surface = original.summarySurface
                val surfaceBounds = inlineBounds(surface)
                val rowBounds = inlineBounds(original)
                val spotlight = inlineViews(screen()).filterIsInstance<ReverbRecordingSpotlight>().single()
                val spotlightBounds = inlineBounds(spotlight)
                check(surface.scaleX == 1f && surface.scaleY == 1f && surface.rotation == 0f)
                // Pick a three-pixel patch from actual fill membership, not an assumed radius.
                val limit = minOf(surface.width / 2, surface.height / 2, 64)
                val outside = (1 until limit).firstNotNullOfOrNull { y ->
                    (1 until limit).firstOrNull { x ->
                        (-1..1).all { dy -> (-1..1).all { dx ->
                            !surface.containsVisualPoint(x + dx + .5f, y + dy + .5f)
                        } }
                    }?.let { x -> surfaceBounds.left + x to surfaceBounds.top + y }
                } ?: error("Actual card has no visible rounded-corner patch")
                val info = inlineViews(original).filterIsInstance<ReverbInlineRecordingInfo>().single()
                val infoBounds = inlineBounds(info)
                val inside = infoBounds.centerX() to infoBounds.bottom - maxOf(2, info.paddingBottom / 2)
                check(rowBounds.contains(outside.first, outside.second))
                check(rowBounds.contains(inside.first, inside.second))
                check((-1..1).all { dy -> (-1..1).all { dx ->
                    surface.containsVisualPoint(inside.first - surfaceBounds.left + dx + .5f,
                        inside.second - surfaceBounds.top + dy + .5f)
                } })
                fun sample(point: Pair<Int, Int>, name: String, dimmed: Boolean): JSONObject {
                    val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(bitmap)
                        canvas.translate((spotlightBounds.left - point.first + 1).toFloat(),
                            (spotlightBounds.top - point.second + 1).toFloat())
                        spotlight.draw(canvas)
                        val alpha = (0..2).flatMap { y -> (0..2).map { x -> Color.alpha(bitmap.getPixel(x, y)) } }
                        check(alpha.all { if (dimmed) it > 0 else it == 0 }) { "$name actual Spotlight alpha=$alpha" }
                        check(spotlight.containsPointerLayer(point.first + .5f - spotlightBounds.left,
                            point.second + .5f - spotlightBounds.top) == dimmed)
                        saveNativeParityBitmap(directory, "$name-alpha.png", bitmap)
                        return JSONObject().put("screen_x", point.first + .5f).put("screen_y", point.second + .5f)
                            .put("alpha_3x3", JSONArray(alpha))
                    } finally { bitmap.recycle() }
                }
                val geometry = JSONObject().put("scope", "Actual Spotlight software draw; production fill membership and phase-root taps")
                    .put("surface", surfaceBounds.flattenToString()).put("row", rowBounds.flattenToString())
                    .put("corner", sample(outside, "info-rounded-corner", true))
                    .put("interior", sample(inside, "info-painted-interior", false))
                entry.put("rounded_shape", geometry)
                tapAt(inside.first + .5f, inside.second + .5f)
                outside
            }
            current.waitFrame(i)
            val dismissals = main {
                check(screen().currentState()?.infoRecording?.id == first.id) { "Painted Info padding dismissed" }
                current.inlineInfoDismissalTransitions.also {
                    tapAt(corner.first + .5f, corner.second + .5f)
                }
            }
            current.await(i, "rounded-corner tap dismisses without activating another row") {
                screen().currentState()?.let { it.infoRecording == null && it.selectedCount == 0 &&
                    it.list.expandedOwner == null && it.menuRecording == null } == true
            }
            current.waitFrame(i)
            main {
                check(current.inlineInfoDismissalTransitions == dismissals + 1)
                entry.getJSONObject("rounded_shape").put("committed_dismissal_transitions", 1)
                    .put("inside_paint_preserved", true).put("row_activation", false)
            }
            // Keep the original plain outside-margin UP check as a separate reopening.
            open(first, R.string.recording_info)
            outside(first.id, false, entry)
            current.await(i, "outside UP dismisses Info without another row action") {
                screen().currentState()?.let { it.infoRecording == null && it.selectedCount == 0 && it.list.expandedOwner == null } == true
            }
            owned.requireUnchanged()
        }
        val last = records.last()
        // Real list placement, not a fabricated IME inset or a direct bring-into-view call.
        main {
            val recycler = list().scrollView
            check(recycler.canScrollVertically(1)) { "Fixture did not establish an offscreen bottom recording" }
            recycler.scrollToPosition(checkNotNull(recycler.adapter).itemCount - 1)
        }
        current.await(i, "last recording at bottom") { list().attachedRecordingAnchor(last.id)?.isShown == true }
        val originalLast = main { card(last.id) }
        scenario("rename-header-ime-and-same-id-rebind") { entry ->
            entry.put("card_before", main { inlineBounds(originalLast).flattenToString() })
            open(last, R.string.rename_recording)
            current.awaitEditor(i)
            main {
                // startViewTransition may keep a removed summary isShown during its finite
                // draw-only fade. It must already be absent from the live input/semantic tree.
                val live = inlineViews(originalLast).toSet()
                val outgoing = JSONArray()
                originalLast.paragraphLeaves.forEach { leaf ->
                    val node = current.inlineAccessibilityNode(leaf)
                    check(leaf !in live && (!node.present || node.hidden)) {
                        "Outgoing summary retained live child or semantic authority"
                    }
                    if (leaf.isShown) {
                        val retired = generateSequence(leaf as View) { it.parent as? View }
                            .first { candidate -> live.any { it === candidate.parent } }
                        check(retired.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                        check(retired is ViewGroup && retired.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS)
                        check(!retired.hasFocus())
                    }
                    outgoing.put(JSONObject().put("shown", leaf.isShown).put("alpha", leaf.alpha)
                        .put("semantic_present", node.present).put("semantic_hidden", node.hidden))
                }
                entry.put("rename_header_transition_sample", outgoing)
                entry.put("rename_editor_focused_at_transition_sample", editor().isFocused)
            }
            current.await(i, "removed summary finite visual terminal", timeout = 2_000L) {
                originalLast.paragraphLeaves.none { it.isShown }
            }
            entry.put("rename_header_visual_terminal", true)
            tapEditorThroughPhaseRoot(entry)
            current.selectRenameDraft(i, 0, 0, entry)
            current.awaitRenameIme(i, windowScope, entry)
            val initial = main {
                check(card(last.id) === originalLast)
                check(originalLast.paragraphLeaves.none { it.isShown }) { "Ordinary header metadata survived Rename" }
                editor().also { check(it.isFocused && it.hasWindowFocus()) }
            }
            val draft = last.displayName.substringBeforeLast('.') + " edited"
            current.setRaw(i, draft)
            current.awaitRaw(i, draft)
            current.selectRenameDraft(i, draft.length - 6, draft.length, entry)
            main { current.inlineRebind() }
            current.waitFrame(i)
            main {
                check(editor() === initial && initial.text.toString() == draft)
                check(initial.selectionStart == draft.length - 6 && initial.selectionEnd == draft.length)
                check(initial.isFocused && originalLast.paragraphLeaves.none { it.isShown })
            }
            // Drive the production reconciliation entry with changed metadata for the SAME
            // pinned asset. This is a controlled read-result boundary, not a storage rename.
            val refreshed = last.copy(displayName = "Metadata enrichment.wav")
            check(sameRecordingActionTarget(last, refreshed))
            main { screen().owner.reconcileTransientRecordings(mapOf(last.id to last), mapOf(last.id to refreshed)) }
            current.await(i, "same-asset metadata reconciliation") {
                screen().currentState()?.renameRecording?.displayName == refreshed.displayName
            }
            main {
                check(editor() === initial && initial.text.toString() == draft) { "Metadata refresh erased the edited draft" }
                check(initial.selectionStart == draft.length - 6 && initial.selectionEnd == draft.length && initial.isFocused)
                screen().owner.reconcileTransientRecordings(mapOf(last.id to refreshed), mapOf(last.id to last))
            }
            current.await(i, "original metadata restored without replacing draft") {
                screen().currentState()?.renameRecording?.displayName == last.displayName
            }
            entry.put("same_asset_metadata_rebind_preserved_draft", true)
            val deadline = SystemClock.uptimeMillis() + 8_000L
            while (true) {
                val observation = current.renameKeyboardSnapshot(i, windowScope)
                val target = observation.getJSONObject("observed_target")
                val imeBounds = target.getJSONObject("ime_window").getJSONArray("bounds")
                val ready = main {
                    val field = editor()
                    val bounds = inlineBounds(field)
                    val visible = Rect()
                    entry.put("editor", bounds.flattenToString()).put("ime", observation)
                        .put("card_after", inlineBounds(originalLast).flattenToString())
                    current.inlineAccessibilityNode(field).let { node -> check(node.present && !node.hidden) }
                    target.getBoolean("positive_ime") && field.getGlobalVisibleRect(visible) && visible == bounds &&
                        bounds.bottom <= imeBounds.getInt(1)
                }
                if (ready) break
                check(SystemClock.uptimeMillis() < deadline) { "Bottom editor remained occluded by actual IME" }
                current.waitFrame(i)
            }
            awaitRenameControls(entry, "bottom_ime_actions")
            capture("rename-ime-bottom")
        }
        scenario("rename-detach-rebind-does-not-retarget-draft") { entry ->
            val before = main { editor() }
            val raw = current.raw(i)
            val owner = main { current.inlineRenameOwner() }
            val stale = main { checkNotNull(before.onCreateInputConnection(EditorInfo())).also(retainedConnections::add) }
            main {
                val content = screen()
                val parent = content.parent as ViewGroup
                val index = parent.indexOfChild(content)
                val params = content.layoutParams
                parent.removeView(content)
                try {
                    check(!before.isAttachedToWindow && !before.hasWindowFocus())
                    entry.put("detached_editor_identity", System.identityHashCode(before))
                    staleInput(stale, before, owner, entry, "detached_stale_input")
                } finally { parent.addView(content, index, params) }
                current.inlineRebind()
                // This test deliberately removes/reinserts the whole retained screen at the
                // same bounds. Explicitly schedule layout of the retained editor; unchanged
                // ancestor bounds need not invoke every descendant's onLayout after reattach.
                // Keep the helper's actual laid-out/visible gate and never recover focus here.
                val layout = JSONObject().put("editor_identity", System.identityHashCode(before))
                    .put("attached", before.isAttachedToWindow).put("shown", before.isShown)
                    .put("laid_out_before", before.isLaidOut).put("layout_requested_before", before.isLayoutRequested)
                    .put("bounds_before", inlineBounds(before).flattenToString())
                entry.put("artificial_reattach_layout_request", layout)
                before.requestLayout()
                layout.put("layout_requested_after", before.isLayoutRequested)
            }
            current.awaitEditor(i)
            main { check(editor() === before && before.text.toString() == raw) }
            current.selectRenameDraft(i, raw.length, raw.length, entry)
            current.awaitRenameIme(i, windowScope, entry)
            main {
                check(editor() === before && current.inlineRenameOwner() === owner)
                val fresh = checkNotNull(before.onCreateInputConnection(EditorInfo())).also(retainedConnections::add)
                check(fresh !== stale)
                staleInput(stale, before, owner, entry, "old_input_after_reattach_and_new_connection", reattached = true)
                // Positive control in this same Main turn: the new connection can edit and
                // deliver Done to production validation. No physical rename is intended here.
                val invalid = "$raw/"
                check(fresh.setSelection(0, before.text.length))
                check(fresh.commitText(invalid, 1))
                check(before.text.toString() == invalid && owner.state.name == invalid)
                check(fresh.performEditorAction(EditorInfo.IME_ACTION_DONE))
                check(owner.state.error == context.getString(R.string.rename_recording_illegal_chars) && !owner.state.isRenaming)
                entry.put("new_connection_edit_and_done_validation", true)
                check(fresh.setSelection(0, before.text.length) && fresh.commitText(raw, 1))
                check(before.text.toString() == raw && owner.state.name == raw && owner.state.error == null)
            }
            current.waitFrame(i)
            check(current.raw(i) == raw)
            owned.requireUnchanged()
        }
        scenario("invalid-rename-keeps-header-cancel-discards") { entry ->
            val invalid = "invalid/name"
            current.setRaw(i, invalid)
            awaitRenameControls(entry, "before_invalid_done")
            main {
                val bounds = inlineBounds(renameAction(context.getString(R.string.done)))
                tapAt(bounds.exactCenterX(), bounds.exactCenterY())
            }
            current.awaitText(i, context.getString(R.string.rename_recording_illegal_chars))
            awaitRenameControls(entry, "validation_and_actions_above_ime", validation = true)
            check(current.raw(i) == invalid)
            owned.requireUnchanged()
            val retired = main { editor() }
            val retiredOwner = main { current.inlineRenameOwner() }
            val stale = main { checkNotNull(retired.onCreateInputConnection(EditorInfo())).also(retainedConnections::add) }
            main {
                val bounds = inlineBounds(renameAction(context.getString(R.string.cancel)))
                tapAt(bounds.exactCenterX(), bounds.exactCenterY())
            }
            current.awaitActivity(i)
            main { check(screen().currentState()?.renameRecording == null) }
            open(last, R.string.rename_recording)
            current.awaitEditor(i)
            check(current.raw(i) == last.displayName.substringBeforeLast('.'))
            main {
                val replacement = editor()
                val replacementOwner = current.inlineRenameOwner()
                check(replacement !== retired && replacementOwner !== retiredOwner)
                val state = replacementOwner.state
                val text = replacement.text.toString()
                staleInput(stale, retired, retiredOwner, entry, "retired_stale_input_after_reopen")
                check(replacementOwner.state == state && !state.isRenaming && replacement.text.toString() == text)
            }
            current.waitFrame(i)
            check(current.raw(i) == last.displayName.substringBeforeLast('.'))
            owned.requireUnchanged()
            entry.put("cancel_discarded_draft", true)
        }
        scenario("accepted-rename-exact-owned-object") { entry ->
            val expectedStem = last.displayName.substringBeforeLast('.') + " accepted"
            current.selectRenameDraft(i, 0, last.displayName.substringBeforeLast('.').length, entry)
            current.awaitRenameIme(i, windowScope, entry)
            main {
                val field = editor()
                val connection = checkNotNull(field.onCreateInputConnection(EditorInfo())).also(retainedConnections::add)
                check(connection.setSelection(0, field.text.length) && connection.commitText(expectedStem, 1))
                check(field.text.toString() == expectedStem && current.inlineRenameOwner().state.name == expectedStem)
                check(connection.performEditorAction(EditorInfo.IME_ACTION_DONE))
                entry.put("submitted_through_current_input_connection", true)
            }
            current.awaitActivity(i)
            val expected = "$expectedStem.wav"
            current.awaitRenamed(i, last.id, expected)
            val renamed = owned.awaitAndAcceptRename(last, expected)
            main {
                val state = checkNotNull(screen().currentState())
                check(state.renameRecording == null && state.selectedCount == 0)
                check(state.recordings.count { it.id == renamed.id && it.displayName == expected } == 1)
                check(inlineViews(screen()).filterIsInstance<EditText>().none { it.isShown })
            }
            entry.put("renamed_id", renamed.id).put("pcm_and_provider_identity_preserved", true)
            capture("rename-accepted")
        }
    } catch (error: Throwable) { failure = error }
    finally {
        fun cleanup(action: () -> Unit) { failure = captureNativeParityCleanup(failure, action) }
        cleanup { main { board?.close() } }
        retainedConnections.forEach { connection -> cleanup { main { connection.closeConnection() } } }
        cleanup { board?.awaitClosed() }
        cleanup { ledger?.close() }
        cleanup { main { host?.finish() } }
        cleanup { windows?.close() }
        cleanup { check(getRecorderPreferences(context).all == preferences) }
        cleanup { check(AppFeedbackCenter.events.value == feedback) }
        cleanup { check(recordingMutations.activeIds.value.isEmpty()) }
        result.put("passed", failure == null && result.getInt("completed_cases") == 5)
        failure?.let { result.put("terminal_error", it.stackTraceToString()) }
        cleanup(::persist)
        report.appendLine("PARITY_EVIDENCE ${directory.name}/result.json")
    }
    failure?.let { throw it }
    check(result.getBoolean("passed"))
    report.appendLine("PASS native_library_inline_current cases=5")
}

private fun inlineViews(root: View): List<View> = buildList {
    fun visit(view: View) { add(view); if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) } }
    visit(root)
}

private fun inlineBounds(view: View): Rect = IntArray(2).also(view::getLocationOnScreen).let {
    Rect(it[0], it[1], it[0] + view.width, it[1] + view.height)
}
