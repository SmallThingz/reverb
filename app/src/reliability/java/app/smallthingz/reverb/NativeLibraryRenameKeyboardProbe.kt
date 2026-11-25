package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import app.smallthingz.reverb.nativeui.ReverbLibraryListView
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.reverb.ReverbInlineRecordingRename
import smallthingz.ui.reverb.ReverbLibraryTextAction
import smallthingz.ui.text.view.ParagraphView
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal const val LIBRARY_RENAME_KEYBOARD_SELECTOR = "native_library_rename_keyboard"

/**
 * Opt-in current-UI probe; A4 remains unchanged. Run first and warm in separate lease turns:
 * -e test native_library_rename_keyboard -e keyboard_session_nonce <32 lowercase hex>
 * -e rename_keyboard_attempt first|warm
 *
 * Uses protocol4's existing private-file/challenge/window guards, NOT its hardware-key driver.
 * The sole operator must admit the three literal SOFT_KEY_* stages below, view the timestamped
 * full-display capture, acquire/recheck its own fresh device lease, and tap the actual visible
 * soft key. No input text/keyevent/SetText/commitText fallback is permitted. Old stage-table
 * coordinators must reject these new stages. Input provenance is an explicit operator receipt,
 * not something an EditText callback or an IME inset can independently establish.
 *
 * A request's scene includes transport, expected text/selection, capture hash and geometry.
 * In addition to normal protocol4 ack fields the operator supplies input_route, lease_id,
 * fresh_lease_verified, keyboard_grid_visible, reviewed_capture_sha256, operator_capture_sha256,
 * operator_capture_uptime_ms, keyboard_bounds and
 * key_bounds (screen-pixel [l,t,r,b]). No IME-window enumeration is required for this guarded
 * visual handoff; positive_ime stays false when enumeration is unavailable.
 *
 * One measured attempt per invocation. Warm performs one open/Cancel before its measurement;
 * first means first observed, never proven cold. Active work has a 65s total deadline (not a
 * latency budget); protocol4 additionally bounds every rendezvous to 30s. Parent must admit a
 * 120s turn with cleanup reserve and enforce its external deadline for stalled platform/IO calls.
 */
internal fun verifyNativeLibraryRenameKeyboard(
    i: Instrumentation, report: StringBuilder, sessionNonce: String?, attempt: String?,
) {
    val context = i.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java && Build.VERSION.SDK_INT >= 31)
    check(sessionNonce?.matches(Regex("[0-9a-f]{32}")) == true)
    check(attempt == "first" || attempt == "warm") { "Select exactly one first|warm attempt per lease turn" }
    // Existing protocol4 admits this namespace. The selector/schema distinguish this from the
    // older decorated-editor probe; never reuse an evidence directory or its requests.
    val directory = File(context.filesDir, "native-rename-editor-current-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val started = SystemClock.uptimeMillis()
    val deadline = started + 65_000L
    val result = JSONObject().put("schema", "inline-rename-soft-key-readiness-v1")
        .put("selector", LIBRARY_RENAME_KEYBOARD_SELECTOR).put("passed", false)
        .put("attempt", attempt).put("classification", if (attempt == "first") "first_observed_coldness_unproven" else "warm_after_unmeasured_open_cancel")
        .put("started_uptime_ms", started).put("active_deadline_uptime_ms", deadline)
        .put("ack_protocol", NATIVE_KEYBOARD_ACK_PROTOCOL)
        .put("scope", "QA inline Library/inset adapter; actual soft keys supplied by sole external operator")
        .put("limits", JSONArray(listOf("not MainActivity inset integration", "not a latency benchmark",
            "stable geometry is not IME animation-end or full keyboard paint proof",
            "keyboard pixels/input provenance require explicit operator review; outline verdict remains separate")))
    val requests = JSONArray().also { result.put("keyboard_requests", it) }
    val captures = JSONArray().also { result.put("captures", it) }
    val preferences = getRecorderPreferences(context).all.toMap()
    val feedback = AppFeedbackCenter.events.value.toList()
    check(recordingMutations.activeIds.value.isEmpty())
    var host: NativeParityActivity? = null
    var board: TransactionBoard? = null
    var ledger: TransactionFixtures? = null
    var windows: NativeParityInteractiveWindows? = null
    var removeAnimationObserver: (() -> Unit)? = null
    var failure: Throwable? = null
    var functionalComplete = false
    fun <T> main(action: () -> T) = nativeParityOnMain(i, action)
    fun persist() = writeQaEvidenceAtomically(File(directory, "result.json")) {
        it.write(result.toString(2).toByteArray(Charsets.UTF_8))
    }
    fun budget() { check(SystemClock.uptimeMillis() < deadline) { "Rename readiness active deadline expired; no input retry" } }
    fun await(label: String, predicate: () -> Boolean) {
        val end = minOf(deadline, SystemClock.uptimeMillis() + 8_000L)
        while (true) {
            budget()
            if (main(predicate)) return
            check(SystemClock.uptimeMillis() < end) { "Rename readiness timeout: $label" }
            SystemClock.sleep(16)
        }
    }
    try {
        persist()
        recoverEmptyLibraryFixtureJournal(context, result, ::persist)
        budget()
        val scope = NativeParityInteractiveWindows.open(i, result).also { windows = it }
        val automation = nativeParityUiAutomation(i)
        val activity = launchCurrentUiSmokeActivity(i).also { host = it }
        awaitNativeParityDraw(i, activity.fixtureRoot)
        scope.recordHost(i, activity)
        val fixtures = TransactionFixtures(context, directory, "native", currentUi = true,
            namePrefix = "Rename soft QA ${UUID.randomUUID()}").also { ledger = it }
        fixtures.admit()
        budget()
        val records = fixtures.create(2)
        budget()
        val current = main { TransactionBoard(activity, false, records, JSONArray(),
            fitCurrentWindowInsets = true, inlineCurrent = true) { board = it } }
        current.awaitReady(i, records.map { it.id }.toSet())
        val screen = main { current.inlineScreen() }
        val list = main { renameViews(screen).filterIsInstance<ReverbLibraryListView>().single() }
        var imeAnimationSeen = false
        var imeAnimationEnd: Long? = null
        val imeAnimations = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<WindowInsetsAnimationCompat, Boolean>())
        main {
            // A new QA-only zero-size child observes dispatch without replacing any production
            // callback or modifying insets. Some window/IME paths deliver none; report that honestly.
            val observer = View(activity)
            activity.fixtureRoot.addView(observer, FrameLayout.LayoutParams(0, 0))
            removeAnimationObserver = {
                ViewCompat.setWindowInsetsAnimationCallback(observer, null)
                activity.fixtureRoot.removeView(observer)
            }
            ViewCompat.setWindowInsetsAnimationCallback(observer, object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        imeAnimationSeen = true; imeAnimationEnd = null; imeAnimations.add(animation)
                    }
                }
                override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat = insets
                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (imeAnimations.remove(animation) && imeAnimations.isEmpty()) imeAnimationEnd = SystemClock.uptimeMillis()
                }
            })
        }
        val record = records.first()
        result.put("recording_id", record.id)
        fun focusedRoot(): View = main {
            current.checkAsync()
            WindowInspector.getGlobalWindowViews().filter {
                it.isAttachedToWindow && it.isShown && it.hasWindowFocus() && it.display?.displayId == 0
            }.single().also { check(it.context.packageName == context.packageName) { "Focus left QA; no reclaim" } }
        }
        fun viewport(): Rect = renameBounds(list.scrollView).apply {
            left += list.scrollView.paddingLeft; right -= list.scrollView.paddingRight
            top += list.scrollView.paddingTop; bottom -= list.scrollView.paddingBottom
        }
        fun paragraph(root: View, label: String) = renameViews(root).filterIsInstance<ParagraphView>()
            .single { it.isShown && it.getText().toString() == label }
        fun gesture(bounds: Rect, held: Boolean = false) {
            budget(); focusedRoot()
            val x = bounds.left + bounds.width() * .8f
            val y = bounds.exactCenterY()
            val down = SystemClock.uptimeMillis()
            fun send(action: Int) {
                focusedRoot()
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { i.sendPointerSync(event) } finally { event.recycle() }
            }
            var pending = true
            try {
                send(MotionEvent.ACTION_DOWN)
                if (held) await("long-press menu") { screen.currentState()?.menuRecording?.id == record.id }
                else SystemClock.sleep(24)
                budget()
                send(MotionEvent.ACTION_UP)
                pending = false
            } finally {
                // Never send a stale tail into another application's focused window.
                if (pending) runCatching { send(MotionEvent.ACTION_CANCEL) }.exceptionOrNull()?.let {
                    result.put("pointer_cancel_error", it.stackTraceToString())
                }
            }
        }
        fun tapLabel(label: String) {
            val root = focusedRoot()
            val bounds = main {
                val view = paragraph(root, label)
                val full = renameBounds(view)
                check(renameVisibleBounds(view) == full)
                if (root === activity.window.decorView) check(viewport().contains(full))
                full
            }
            gesture(bounds)
        }
        fun openRename() {
            await("quiet baseline") { activity.hasWindowFocus() && screen.currentState()?.renameRecording == null &&
                ViewCompat.getRootWindowInsets(screen)?.isVisible(WindowInsetsCompat.Type.ime()) == false && imeAnimations.isEmpty() }
            main { imeAnimationSeen = false; imeAnimationEnd = null }
            // Explicit fixture positioning, especially after warmup revealed bottom actions.
            main { list.scrollView.scrollToPosition(0) }
            await("fully visible fixture title") {
                val row = list.attachedRecordingAnchor(record.id) ?: return@await false
                val view = paragraph(row, record.displayName)
                renameVisibleBounds(view) == renameBounds(view) && viewport().contains(renameBounds(view))
            }
            val title = main {
                val row = checkNotNull(list.attachedRecordingAnchor(record.id))
                paragraph(row, record.displayName).let { view -> renameBounds(view).also {
                    check(renameVisibleBounds(view) == it && viewport().contains(it))
                } }
            }
            gesture(title, held = true)
            val popup = focusedRoot()
            await("painted Rename menu settled") {
                val layer = renameField(popup, "animationLayer") as? View
                layer != null && layer.alpha == 1f && layer.scaleX == 1f && layer.scaleY == 1f
            }
            awaitNativeParityDraw(i, popup)
            tapLabel(context.getString(R.string.rename_recording))
        }
        fun renameView() = renameViews(screen).filterIsInstance<ReverbInlineRecordingRename>().single { it.isShown }
        fun action(header: View, label: String): View {
            val button = renameViews(header).filterIsInstance<NativeMaterialButtonView>().single {
                it.label.getText().toString() == label
            }
            // Retain the old wrapper's expanded target when present; merged actions expose the
            // actual material button directly. Neither lookup assumes a particular group layout.
            return (button.parent as? ReverbLibraryTextAction) ?: button
        }
        fun geometry(): JSONObject {
            check(activity.hasWindowFocus() && screen.currentState()?.renameRecording?.id == record.id)
            val header = renameView()
            val editor = header.editor
            check(editor.isAttachedToWindow && editor.isFocused && editor.hasWindowFocus())
            val field = header.outlinedField
            val cancel = action(header, context.getString(R.string.cancel))
            val done = action(header, context.getString(R.string.done))
            val inset = checkNotNull(ViewCompat.getRootWindowInsets(screen))
            val ime = inset.getInsets(WindowInsetsCompat.Type.ime())
            val view = viewport()
            fun measured(v: View) = JSONObject().put("bounds", renameBounds(v).flattenToString())
                .put("visible_bounds", renameVisibleBounds(v)?.flattenToString() ?: JSONObject.NULL)
                .put("inside_viewport", view.contains(renameBounds(v)))
                .put("fully_visible", view.contains(renameBounds(v)) && renameVisibleBounds(v) == renameBounds(v))
            val combined = Rect(renameBounds(field)).apply { union(renameBounds(cancel)); union(renameBounds(done)) }
            val caret = editor.layout?.let { layout ->
                val offset = editor.selectionEnd.coerceIn(0, editor.text.length)
                val line = layout.getLineForOffset(offset)
                val origin = renameBounds(editor)
                val x = origin.left + editor.totalPaddingLeft - editor.scrollX + layout.getPrimaryHorizontal(offset).toInt()
                val y = origin.top + editor.totalPaddingTop - editor.scrollY
                Rect(x, y + layout.getLineTop(line), x + 1, y + layout.getLineBottom(line))
            }
            return JSONObject().put("editor_identity", System.identityHashCode(editor))
                .put("header", measured(header)).put("outer_field", measured(field)).put("editor", measured(editor))
                .put("cancel", measured(cancel)).put("done", measured(done)).put("viewport", view.flattenToString())
                .put("field_fits_viewport", field.height <= view.height())
                .put("caret_bounds", caret?.flattenToString() ?: JSONObject.NULL)
                .put("caret_inside_viewport", caret != null && view.contains(caret))
                .put("combined_height", combined.height()).put("combined_fits_viewport", combined.height() <= view.height())
                .put("ime_visible", inset.isVisible(WindowInsetsCompat.Type.ime()))
                .put("ime_insets", JSONArray(listOf(ime.left, ime.top, ime.right, ime.bottom)))
                .put("ime_nonzero", ime.left + ime.top + ime.right + ime.bottom > 0)
                .put("ime_animation_observed", imeAnimationSeen).put("ime_animations_active", imeAnimations.size)
                .put("ime_animation_end_callback_uptime_ms", imeAnimationEnd ?: JSONObject.NULL)
                .put("layout_pending", listOf(header, field, editor, cancel, done, list.scrollView).any { it.isLayoutRequested })
        }
        // Count actual pre-draw callbacks, not polling iterations. No production listener is replaced.
        fun settled(label: String): JSONObject {
            var previous: String? = null
            var consecutive = 0
            var delivered = 0
            var latest: JSONObject? = null
            var sampleFailure: Throwable? = null
            val observer = ViewTreeObserver.OnPreDrawListener {
                try {
                    val value = geometry()
                    val signature = value.toString()
                    consecutive = if (value.getBoolean("ime_visible") && value.getBoolean("ime_nonzero") &&
                        !value.getBoolean("layout_pending") && value.getInt("ime_animations_active") == 0 &&
                        signature == previous) consecutive + 1 else 0
                    previous = signature; latest = value; delivered++
                    if (consecutive < 4) screen.postInvalidateOnAnimation()
                } catch (error: Throwable) { sampleFailure = error }
                true
            }
            main { screen.viewTreeObserver.addOnPreDrawListener(observer); screen.postInvalidateOnAnimation() }
            try {
                await(label) { sampleFailure?.let { throw it }; consecutive >= 4 }
                return main { checkNotNull(latest).put("delivered_pre_draws", delivered)
                    .put("stable_frame_count", consecutive).put("settled_observed_uptime_ms", SystemClock.uptimeMillis())
                    .put("ime_animation_end_observed", imeAnimationEnd != null) }
            } finally { main { if (screen.viewTreeObserver.isAlive) screen.viewTreeObserver.removeOnPreDrawListener(observer) } }
        }
        fun capture(name: String): JSONObject {
            budget(); focusedRoot()
            val before = main { geometry() }
            val begin = SystemClock.uptimeMillis()
            val bitmap = checkNotNull(automation.takeScreenshot())
            val end = SystemClock.uptimeMillis()
            val file = File(directory, "$name.png")
            val entry = JSONObject().put("file", file.name).put("begin_uptime_ms", begin).put("end_uptime_ms", end)
                .put("display_id", 0).put("width", bitmap.width).put("height", bitmap.height)
                .put("capture_scope", "UiAutomation full default display, uncropped")
                .put("geometry_before", before)
            try { saveNativeParityBitmap(directory, file.name, bitmap) } finally { bitmap.recycle() }
            focusedRoot()
            entry.put("geometry_after", main { geometry() })
                .put("sha256", file.inputStream().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                })
            captures.put(entry); persist()
            return entry
        }
        fun cancelRename(phase: String) {
            val before = main { geometry() }
            if (!before.getJSONObject("cancel").getBoolean("fully_visible") ||
                !before.getJSONObject("done").getBoolean("fully_visible")) {
                // QA-only ordinary scroll request, explicitly recorded; never claim simultaneous fit.
                check(!before.getBoolean("combined_fits_viewport")) { "Fitting field/actions are clipped" }
                main {
                    val actions = action(renameView(), context.getString(R.string.cancel)).parent as View
                    actions.requestRectangleOnScreen(Rect(0, 0, actions.width, actions.height), false)
                }
                result.put("$phase-actions_reveal_route", "QA requestRectangleOnScreen; not physical scroll qualification")
            }
            val after = settled("actions reachable after actual viewport reveal")
            check(after.getJSONObject("cancel").getBoolean("fully_visible") &&
                after.getJSONObject("done").getBoolean("fully_visible")) { "Cancel/Done unreachable" }
            result.put("$phase-actions_reachability", JSONObject().put("before", before).put("after", after))
            capture("$phase-actions-reachable")
            tapLabel(context.getString(R.string.cancel))
            await("Cancel retires editor and hides IME") { screen.currentState()?.renameRecording == null &&
                renameViews(screen).none { it is ReverbInlineRecordingRename && it.isShown } &&
                ViewCompat.getRootWindowInsets(screen)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            fixtures.requireUnchanged()
        }
        if (attempt == "warm") {
            openRename()
            await("warmup editor focus/insets") { activity.hasWindowFocus() &&
                renameViews(screen).filterIsInstance<ReverbInlineRecordingRename>().singleOrNull()?.editor?.isFocused == true &&
                ViewCompat.getRootWindowInsets(screen)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            settled("warmup geometry")
            cancelRename("warmup")
            result.put("warmup_open_cancel_completed", true)
        }
        openRename()
        await("same editor focus and first visible insets") { activity.hasWindowFocus() &&
            renameViews(screen).filterIsInstance<ReverbInlineRecordingRename>().singleOrNull()?.editor?.let {
                it.isFocused && it.hasWindowFocus()
            } == true && ViewCompat.getRootWindowInsets(screen)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        result.put("first_visible_insets_observed_uptime_ms", SystemClock.uptimeMillis())
        val initial = settled("stable IME and field/editor/actions geometry")
        result.put("settled_geometry", initial)
        check(initial.getBoolean("caret_inside_viewport")) { "Caret outside viewport" }
        if (initial.getBoolean("field_fits_viewport")) {
            check(initial.getJSONObject("outer_field").getBoolean("fully_visible")) { "Fitting outer field clipped" }
        }
        if (initial.getBoolean("combined_fits_viewport")) {
            check(initial.getJSONObject("cancel").getBoolean("fully_visible") &&
                initial.getJSONObject("done").getBoolean("fully_visible")) { "Fitting actions clipped" }
        }
        val identity = main { renameView().editor }
        val original = main { identity.text.toString().also {
            check(current.inlineRenameOwner().state.name == it)
            check(identity.selectionStart == it.length && identity.selectionEnd == it.length)
        } }
        result.put("original_text", original)
        var expected = original
        for ((index, key) in listOf("SOFT_KEY_Q", "SOFT_KEY_Z", "SOFT_KEY_BACKSPACE").withIndex()) {
            budget()
            val beforeText = expected
            val next = when (key) { "SOFT_KEY_Q" -> expected + "q"; "SOFT_KEY_Z" -> expected + "z"; else -> expected.dropLast(1) }
            settled("stable geometry before soft-key handoff $index")
            val shot = capture("soft-key-$index-ready")
            var request: JSONObject? = null
            awaitNativeParityKeyboardCommand(i, directory, sessionNonce, "native",
                "inline-rename-soft-$attempt-$index", key,
                guardedSnapshot = {
                    budget()
                    current.renameKeyboardSnapshot(i, scope).also { snapshot ->
                        val measured = main {
                            check(renameView().editor === identity)
                            val ownerRaw = current.inlineRenameOwner().state.name
                            check(identity.text.toString() == ownerRaw)
                            geometry().put("production_owner_raw", ownerRaw)
                        }
                        val target = snapshot.getJSONObject("observed_target")
                        target.put("production_owner_raw", measured.getString("production_owner_raw"))
                        val raw = target.getString("raw_text")
                        if (request == null) check(raw == beforeText) { "Text changed before request publication" }
                        check(raw == beforeText || raw == next) { "Unexpected text; no repair/retry" }
                        check(target.getInt("selection_start") == raw.length && target.getInt("selection_end") == raw.length)
                        check(measured.getBoolean("ime_visible") && measured.getBoolean("ime_nonzero"))
                        snapshot.put("geometry", measured).put("transport", "physical_screen_soft_key")
                            .put("expected_before", beforeText).put("expected_after", next)
                            .put("expected_selection_after", JSONArray(listOf(next.length, next.length)))
                            .put("capture", shot).put("global_deadline_uptime_ms", deadline)
                            .put("requires_fresh_operator_lease", true)
                            .put("forbidden_input", "hardware/direct key events, setText, commitText, paste, accessibility SetText")
                    }
                },
                onRequest = { request = it; requests.put(it); persist() },
                validateObservedAfter = { after ->
                    val target = after.getJSONObject("observed_target")
                    check(target.getString("raw_text") == next && target.getString("production_owner_raw") == next)
                    check(target.getInt("selection_start") == next.length && target.getInt("selection_end") == next.length)
                    val ack = checkNotNull(request).getJSONObject("host_acknowledgement")
                    check(ack.getString("input_route") == "physical_screen_soft_key")
                    check(ack.getBoolean("fresh_lease_verified") && ack.getString("lease_id").isNotBlank())
                    check(ack.getBoolean("keyboard_grid_visible")) { "Live full keyboard grid not visually qualified" }
                    check(ack.getString("reviewed_capture_sha256") == shot.getString("sha256"))
                    // The operator retains this *fresh* full-display image before tapping, which
                    // can be later than our ready capture when the compositor is still animating.
                    check(ack.getString("operator_capture_sha256").matches(Regex("[0-9a-f]{64}")))
                    check(ack.getLong("operator_capture_uptime_ms") in
                        checkNotNull(request).getLong("uptime_ms")..checkNotNull(request).getLong("ack_received_uptime_ms"))
                    val keyboard = renameJsonRect(ack.getJSONArray("keyboard_bounds"))
                    val keyBounds = renameJsonRect(ack.getJSONArray("key_bounds"))
                    check(Rect(0, 0, shot.getInt("width"), shot.getInt("height")).contains(keyboard))
                    check(!keyBounds.isEmpty && keyboard.contains(keyBounds))
                    checkNotNull(request).put("exact_edit_ack_uptime_ms", SystemClock.uptimeMillis())
                        .put("same_editor_verified", true)
                })
            expected = next
            persist()
        }
        result.put("final_text", expected).put("exact_soft_key_edits_acknowledged", 3)
        settled("post-edit stable geometry")
        capture("edited-keyboard")
        cancelRename("measured")
        result.put("cancelled_without_commit", true)
        functionalComplete = true
    } catch (error: Throwable) { failure = error }
    finally {
        fun cleanup(action: () -> Unit) { failure = captureNativeParityCleanup(failure, action) }
        cleanup { main { removeAnimationObserver?.invoke() } }
        cleanup { main { board?.close() } }
        cleanup { board?.awaitClosed() }
        cleanup { ledger?.close() }
        cleanup { main { host?.finish() } }
        cleanup { windows?.close() }
        cleanup { check(getRecorderPreferences(context).all == preferences) }
        cleanup { check(AppFeedbackCenter.events.value == feedback) }
        cleanup { check(recordingMutations.activeIds.value.isEmpty()) }
        result.put("passed", failure == null && functionalComplete)
            .put("finished_uptime_ms", SystemClock.uptimeMillis())
            .put("outline_visual_verdict", "separate_root_review_required")
        failure?.let { result.put("terminal_error", it.stackTraceToString()) }
        cleanup(::persist)
        report.appendLine("PARITY_EVIDENCE ${directory.name}/result.json")
    }
    failure?.let { throw it }
    check(result.getBoolean("passed"))
    report.appendLine("PASS $LIBRARY_RENAME_KEYBOARD_SELECTOR attempt=$attempt soft_key_edits=3; outline review separate")
}

private fun renameViews(root: View): List<View> = buildList {
    fun visit(view: View) { add(view); if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) } }
    visit(root)
}
private fun renameBounds(view: View): Rect = IntArray(2).also(view::getLocationOnScreen).let {
    Rect(it[0], it[1], it[0] + view.width, it[1] + view.height)
}
private fun renameVisibleBounds(view: View): Rect? {
    val rect = Rect()
    if (!view.getGlobalVisibleRect(rect)) return null
    val root = view.rootView
    check(root.matrix.isIdentity && root.scrollX == 0 && root.scrollY == 0)
    val origin = IntArray(2).also(root::getLocationOnScreen)
    rect.offset(origin[0], origin[1])
    return rect
}
private fun renameJsonRect(value: JSONArray): Rect {
    check(value.length() == 4)
    return Rect(value.getInt(0), value.getInt(1), value.getInt(2), value.getInt(3)).also { check(!it.isEmpty) }
}
private fun renameField(owner: Any, name: String): Any? {
    var type: Class<*>? = owner.javaClass
    while (type != null) {
        val field = type.declaredFields.firstOrNull { it.name == name }
        if (field != null) return field.apply { isAccessible = true }.get(owner)
        type = type.superclass
    }
    return null
}
