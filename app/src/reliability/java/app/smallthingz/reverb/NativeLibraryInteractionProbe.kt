package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.smallthingz.reverb.nativeui.ReverbLibraryListView
import app.smallthingz.reverb.nativeui.ReverbLibraryRecordingRow
import org.json.JSONArray
import org.json.JSONObject
import smallthingz.ui.reverb.ReverbInlineRecordingInfo
import smallthingz.ui.text.view.ParagraphView
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Current production Library in the existing isolated inline fixture. All injection is through
 * the system window, never performClick/performLongClick. Root owns device admission/execution. */
internal fun verifyNativeLibraryInteractionCurrent(i: Instrumentation, report: StringBuilder) {
    val context = i.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java && Build.VERSION.SDK_INT >= 31)
    val directory = File(context.filesDir, "native-library-interaction-${UUID.randomUUID()}")
    check(directory.mkdir())
    val result = JSONObject().put("selector", "native_library_interaction_current")
        .put("passed", false).put("expected_cases", 5).put("completed_cases", 0)
        .put("clock", "SystemClock.uptimeMillis; all event observations and intervals in ms")
        .put("scope", "Actual current inline Library fixture, QA-owned MediaStore only; system pointer/key delivery")
        .put("limits", JSONArray(listOf("not full MainActivity inset wiring", "no universal latency/IME attribution",
            "IME-visible insets do not prove keyboard draw or interactive readiness", "no recipient selected or message sent")))
    val cases = JSONArray().also { result.put("cases", it) }
    val preferences = getRecorderPreferences(context).all.toMap()
    val feedback = AppFeedbackCenter.events.value.toList()
    check(recordingMutations.activeIds.value.isEmpty())
    var host: NativeParityActivity? = null
    var board: TransactionBoard? = null
    var ledger: TransactionFixtures? = null
    var windowScope: NativeParityInteractiveWindows? = null
    var retireInjectedGesture: (() -> Unit)? = null
    var failure: Throwable? = null
    fun <T> main(action: () -> T): T = nativeParityOnMain(i, action)
    fun persist() = writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray()) }
    fun await(label: String, timeout: Long = 8000, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        do {
            if (main(predicate)) return
            check(SystemClock.uptimeMillis() < end) { "Interaction timeout: $label" }
            SystemClock.sleep(16)
        } while (true)
    }
    try {
        persist()
        recoverEmptyLibraryFixtureJournal(context, result, ::persist)
        val windows = NativeParityInteractiveWindows.open(i, result).also { windowScope = it }
        val automation = nativeParityUiAutomation(i)
        val activity = launchCurrentUiSmokeActivity(i).also { host = it }
        awaitNativeParityDraw(i, activity.fixtureRoot)
        windows.recordHost(i, activity)
        val fixtures = TransactionFixtures(context, directory, "native", currentUi = true,
            namePrefix = "Reverb QA interaction ${UUID.randomUUID()}").also { ledger = it }
        fixtures.admit()
        val records = fixtures.create(count = 12)
        val current = main { TransactionBoard(activity, false, records,
            JSONArray().also { result.put("fixture_window_transitions", it) },
            fitCurrentWindowInsets = true, inlineCurrent = true) { board = it } }
        current.awaitReady(i, records.map { it.id }.toSet())
        val screen = main { current.inlineScreen() }
        val inputRoot = main { current.inlineInputRoot() }
        val list = main { interactionViews(screen).filterIsInstance<ReverbLibraryListView>().single() }
        fun card(id: String) = checkNotNull(list.attachedRecordingAnchor(id)) as ReverbLibraryRecordingRow
        fun ownFocusedRoot(): View = main {
            WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.isShown &&
                it.hasWindowFocus() && it.display?.displayId == 0 }.single().also {
                check(it.context.packageName == context.packageName) { "Focus left QA; no reclaim/input" }
            }
        }
        fun windowSnapshot(): JSONObject {
            val observed = windows.windows()
            try {
                val items = JSONArray()
                observed.filter { it.displayId == 0 }.forEach { window ->
                    val root = window.root
                    try { items.put(JSONObject().put("id", window.id).put("type", window.type)
                        .put("focused", window.isFocused).put("package", root?.packageName?.toString() ?: JSONObject.NULL)
                        .put("bounds", Rect().also(window::getBoundsInScreen).flattenToString())) }
                    finally { root?.recycle() }
                }
                return JSONObject().put("observed_ms", SystemClock.uptimeMillis()).put("windows", items)
                    .put("ime_window_present", observed.any { it.displayId == 0 && it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD })
            } finally { observed.forEach { it.recycle() } }
        }
        fun focusedPackage(): String? {
            val observed = windows.windows()
            try {
                val root = observed.singleOrNull { it.displayId == 0 && it.isFocused &&
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
                return try { root?.packageName?.toString() } finally { root?.recycle() }
            } finally { observed.forEach { it.recycle() } }
        }
        fun state(): JSONObject = main {
            val state = checkNotNull(screen.currentState())
            JSONObject().put("observed_ms", SystemClock.uptimeMillis())
                .put("rename_id", state.renameRecording?.id ?: JSONObject.NULL)
                .put("info_id", state.infoRecording?.id ?: JSONObject.NULL)
                .put("menu_id", state.menuRecording?.id ?: JSONObject.NULL)
                .put("selected_count", state.selectedCount)
                .put("expanded_id", state.list.expandedOwner?.recordingId ?: JSONObject.NULL)
                .put("shown_editors", interactionViews(screen).filterIsInstance<EditText>().count { it.isShown })
                .put("ime_visible", ViewCompat.getRootWindowInsets(inputRoot)?.isVisible(WindowInsetsCompat.Type.ime()) ?: false)
        }
        fun quietOwner() = main {
            val state = checkNotNull(screen.currentState())
            state.renameRecording == null && state.infoRecording == null && state.menuRecording == null &&
                state.selectedCount == 0 && state.list.expandedOwner == null
        }
        var heldDown: Long? = null
        var heldX = 0f
        var heldY = 0f
        fun inject(down: Long, action: Int, x: Float, y: Float) {
            ownFocusedRoot()
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                i.sendPointerSync(event)
                heldX = x; heldY = y
                if (action == MotionEvent.ACTION_DOWN) heldDown = down
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) heldDown = null
            } finally { event.recycle() }
        }
        retireInjectedGesture = {
            heldDown?.let { down ->
                if (focusedPackage() == context.packageName) inject(down, MotionEvent.ACTION_CANCEL, heldX, heldY)
            }
        }
        fun back(expectedPackage: String = context.packageName) {
            check(focusedPackage() == expectedPackage) { "Unexpected foreground; no Back injection" }
            val down = SystemClock.uptimeMillis()
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(down, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0)
                check(automation.injectInputEvent(event, true))
            }
        }
        fun capture(name: String) {
            ownFocusedRoot()
            val bitmap = checkNotNull(automation.takeScreenshot())
            try { saveNativeParityBitmap(directory, "$name.png", bitmap) } finally { bitmap.recycle() }
        }
        fun label(root: View, text: String): ParagraphView = interactionViews(root).filterIsInstance<ParagraphView>()
            .single { it.isShown && it.getText().toString() == text }
        fun tapLabel(text: String, entry: JSONObject, timing: InteractionTiming? = null) {
            val root = ownFocusedRoot()
            await("stable painted action before pointer DOWN") {
                val layer = interactionField(root, "animationLayer") as? View
                layer == null || (layer.scaleX == 1f && layer.scaleY == 1f && layer.alpha == 1f)
            }
            awaitNativeParityDraw(i, root)
            val bounds = main {
                val leaf = label(root, text)
                val clipped = Rect()
                val visible = interactionVisibleScreenBounds(leaf, clipped)
                entry.put("painted_label", text).put("painted_label_bounds", clipped.flattenToString())
                    .put("full_label_bounds", interactionBounds(leaf).flattenToString())
                    .put("window_bounds", interactionBounds(root).flattenToString())
                    .put("window_identity", System.identityHashCode(root))
                check(visible && clipped == interactionBounds(leaf)) { "Painted label is clipped in screen coordinates" }
                var action: View = leaf
                while (!action.isClickable) action = action.parent as? View ?: error("No painted action ancestor")
                entry.put("painted_action_bounds", interactionBounds(action).flattenToString())
                clipped
            }
            val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
            val down = SystemClock.uptimeMillis()
            entry.put("last_touch", JSONObject().put("screen_x", x).put("screen_y", y).put("down_ms", down))
            timing?.mark("tap_down_send_ms")
            inject(down, MotionEvent.ACTION_DOWN, x, y)
            timing?.mark("tap_down_return_ms")
            SystemClock.sleep(24)
            // Recheck actual painted geometry immediately before UP; do not silently retarget.
            main { check(interactionBounds(label(root, text)) == bounds) { "Painted menu moved during tap" } }
            entry.getJSONObject("last_touch").put("up_send_ms", SystemClock.uptimeMillis())
            timing?.mark("tap_up_send_ms")
            inject(down, MotionEvent.ACTION_UP, x, y)
            timing?.mark("tap_up_return_ms")
            timing?.sampleNow()
        }
        fun longPressViewportBounds(): Rect = interactionBounds(list.scrollView).apply {
            left += list.scrollView.paddingLeft; right -= list.scrollView.paddingRight
            top += list.scrollView.paddingTop; bottom -= list.scrollView.paddingBottom
        }
        fun longPress(record: RecordingEntity, entry: JSONObject) {
            await("row visible for real long press") { list.attachedRecordingAnchor(record.id)?.isShown == true }
            val point = main {
                val row = card(record.id)
                val title = label(row, record.displayName)
                val visible = Rect()
                val reportedVisible = interactionVisibleScreenBounds(title, visible)
                val full = interactionBounds(title)
                val viewport = longPressViewportBounds()
                entry.put("recording_id", record.id)
                    .put("padded_viewport_bounds", viewport.flattenToString())
                    .put("full_title_bounds", full.flattenToString())
                    .put("title_bounds", visible.flattenToString())
                // RV uses an outline clip with clipChildren=false. Android's visible rect
                // alone can admit a scrolled-out title above the native pointer viewport.
                check(reportedVisible && visible.width() > 0 && visible.height() > 0 &&
                    visible == full && viewport.contains(full)) {
                    "Long-press title must be fully inside the actual Library viewport"
                }
                // Body/title avoids the separate leading-selection target; deliberately off-center.
                val x = visible.left + visible.width() * .8f
                val y = visible.exactCenterY()
                entry.put("down_screen_x", x).put("down_screen_y", y)
                    .put("row_bounds", interactionBounds(row).flattenToString())
                    .put("title_bounds", visible.flattenToString())
                x to y
            }
            val down = SystemClock.uptimeMillis()
            inject(down, MotionEvent.ACTION_DOWN, point.first, point.second)
            var released = false
            try {
                await("real long press publishes recording menu", ViewConfiguration.getLongPressTimeout().toLong() + 4000) {
                    screen.currentState()?.menuRecording?.id == record.id
                }
                entry.put("menu_state_observed_ms", SystemClock.uptimeMillis()).put("down_ms", down)
                inject(down, MotionEvent.ACTION_UP, point.first, point.second)
                released = true
            } finally {
                if (!released && focusedPackage() == context.packageName)
                    inject(down, MotionEvent.ACTION_CANCEL, point.first, point.second)
            }
            await("focused painted menu") {
                WindowInspector.getGlobalWindowViews().any { root -> root.hasWindowFocus() && root.isShown &&
                    interactionViews(root).filterIsInstance<ParagraphView>().any { it.getText().toString() == context.getString(R.string.share_recording) } }
            }
            val popup = ownFocusedRoot()
            await("popup entry motion complete") {
                val layer = interactionField(popup, "animationLayer") as? View
                layer != null && layer.scaleX == 1f && layer.scaleY == 1f && layer.alpha == 1f
            }
            awaitNativeParityDraw(i, popup)
            entry.put("popup_bounds", main { interactionBounds(popup).flattenToString() })
            main {
                check(screen.currentState()?.menuRecording?.id == record.id)
                check(screen.currentState()?.renameRecording == null && screen.currentState()?.infoRecording == null)
                check(screen.currentState()?.selectedCount == 0 && screen.currentState()?.list?.expandedOwner == null)
                val origin = checkNotNull(interactionField(screen, "pointerMenuOrigin")) { "Physical long press lost pointer origin" }
                run {
                    val row = interactionField(origin, "anchor") as View
                    val value = checkNotNull(interactionField(origin, "point"))
                    val x = interactionField(value, "x") as Float
                    val y = interactionField(value, "y") as Float
                    val a = interactionBounds(row)
                    entry.put("retained_pointer_origin", JSONObject().put("x", x).put("y", y))
                    check(abs(a.left + x - point.first) <= 1f && abs(a.top + y - point.second) <= 1f)
                    val placed = checkNotNull(interactionField(popup, "previousAnchor"))
                    val location = IntArray(2).also(row::getLocationInWindow)
                    val left = interactionField(placed, "left") as Int
                    val top = interactionField(placed, "top") as Int
                    check(interactionField(placed, "right") == left && interactionField(placed, "bottom") == top)
                    check(abs(left - location[0] - x) <= 1f && abs(top - location[1] - y) <= 1f)
                    val painted = interactionBounds(popup)
                    val margin = (48 * context.resources.displayMetrics.density).toInt() + 2
                    val permitted = Rect(painted).apply { inset(-margin, -margin) }
                    check(permitted.contains(point.first.toInt(), point.second.toInt())) { "Painted popup disconnected from pointer/clamp margin" }
                    entry.put("point_anchor_window_x", left).put("point_anchor_window_y", top)
                        .put("painted_point_or_clamp_margin", margin)
                }
            }
        }
        fun openInfo(record: RecordingEntity, entry: JSONObject) {
            longPress(record, JSONObject().also { entry.put("menu", it) })
            tapLabel(context.getString(R.string.recording_info), JSONObject().also { entry.put("info_tap", it) })
            await("Info owns same Activity") { activity.hasWindowFocus() && screen.currentState()?.infoRecording?.id == record.id }
        }
        fun outsidePoint(): Pair<Float, Float> = main {
            val bounds = interactionBounds(list.scrollView)
            val x = bounds.left + 1f
            val y = bounds.top + list.scrollView.paddingTop + (bounds.height() - list.scrollView.paddingTop - list.scrollView.paddingBottom) * .7f
            val active = checkNotNull(screen.currentState()?.infoRecording)
            check(!interactionBounds(card(active.id).summarySurface).contains(x.toInt(), y.toInt()))
            x to y
        }
        fun awaitSettledPlacement(entry: JSONObject, label: String) {
            var previous: String? = null
            var changedAt = SystemClock.uptimeMillis()
            var observations = 0
            await(label) {
                observations++
                val geometry = list.savePosition().toString() + interactionViews(list.scrollView)
                    .filterIsInstance<ReverbLibraryRecordingRow>().joinToString { "${it.recordingId}:${interactionBounds(it)}" }
                if (geometry != previous) { previous = geometry; changedAt = SystemClock.uptimeMillis() }
                entry.put("settled_placement_observations", observations)
                interactionViews(list.scrollView).none { it is ReverbInlineRecordingInfo } &&
                    !list.scrollView.isLayoutRequested && SystemClock.uptimeMillis() - changedAt >= 350
            }
        }
        fun scenario(name: String, action: (JSONObject) -> Unit) {
            val entry = JSONObject().put("name", name).put("passed", false)
            cases.put(entry); persist()
            try {
                action(entry); fixtures.requireUnchanged()
                entry.put("passed", true); result.put("completed_cases", cases.length())
            } catch (error: Throwable) {
                entry.put("error", error.stackTraceToString()).put("owner_state", state()).put("windows", windowSnapshot())
                throw error
            } finally { persist() }
        }
        result.put("environment", JSONObject().put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL)
            .put("ime_id", Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            .put("animator_scale", Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE))
            .put("transition_scale", Settings.Global.getString(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE))
            .put("window_scale", Settings.Global.getString(context.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE)))

        scenario("actual-longpress-point") { entry ->
            longPress(records.first(), entry)
            capture("longpress-menu")
            back()
            await("menu dismissed") { activity.hasWindowFocus() && screen.currentState()?.menuRecording == null }
            check(quietOwner())
        }
        scenario("dimmed-backdrop-drag-and-held-stream-retirement") { entry ->
            openInfo(records.first(), entry)
            val before = main { list.savePosition() }
            val p = outsidePoint()
            val distance = main { list.scrollView.height * .25f }
            val down = SystemClock.uptimeMillis()
            inject(down, MotionEvent.ACTION_DOWN, p.first, p.second)
            for (step in 1..6) {
                SystemClock.sleep(24)
                inject(down, MotionEvent.ACTION_MOVE, p.first, p.second - distance * step / 6)
            }
            val moved = main { list.savePosition() }
            entry.put("before", before.toString()).put("dragged", moved.toString())
            check(before != moved) { "Dimmed backdrop did not route drag to actual Library list" }
            main { check(screen.currentState()?.infoRecording?.id == records.first().id) }
            // Retire while held. Retain Android's pointer stream; old MOVE must not become DOWN.
            back()
            await("Back retires Info during held stream") { screen.currentState()?.infoRecording == null }
            awaitSettledPlacement(entry, "outgoing Info removed and collapse placement settled")
            val retiredPosition = main { list.savePosition() }
            val staleTravel = maxOf(48f, ViewConfiguration.get(context).scaledTouchSlop * 3f)
            val stalePoints = main {
                val viewport = list.scrollView
                val y = viewport.height * .55f
                check(y < viewport.height - viewport.paddingBottom && y - staleTravel > viewport.paddingTop)
                val points = floatArrayOf(viewport.width / 2f, y, viewport.width / 2f, y - staleTravel)
                android.graphics.Matrix().also(viewport::transformMatrixToGlobal).mapPoints(points)
                val rootPoints = points.copyOf()
                android.graphics.Matrix().also(inputRoot::transformMatrixToLocal).mapPoints(rootPoints)
                for (offset in intArrayOf(0, 2)) {
                    check(rootPoints[offset] > inputRoot.width * .25f && rootPoints[offset] < inputRoot.width * .75f &&
                        rootPoints[offset + 1] > 0f && rootPoints[offset + 1] < inputRoot.height) {
                        "Stale tail must reach normal Library center, outside either edge band"
                    }
                }
                check(abs(points[0] - points[2]) <= 1f &&
                    points[1] - points[3] > ViewConfiguration.get(context).scaledTouchSlop)
                entry.put("stale_tail_screen_points", JSONArray(points.map { it.toDouble() }))
                    .put("stale_tail_root_points", JSONArray(rootPoints.map { it.toDouble() }))
                points
            }
            inject(down, MotionEvent.ACTION_MOVE, stalePoints[0], stalePoints[1])
            SystemClock.sleep(24)
            inject(down, MotionEvent.ACTION_MOVE, stalePoints[2], stalePoints[3])
            inject(down, MotionEvent.ACTION_UP, stalePoints[2], stalePoints[3])
            awaitSettledPlacement(entry, "placement after old stream tail")
            main { check(list.savePosition() == retiredPosition) { "Retired drag scrolled the replacement owner" } }
            check(quietOwner())
            // A genuine next DOWN can establish a fresh owner after cancellation.
            val visibleRecord = main {
                val viewport = longPressViewportBounds()
                records.first { record ->
                    val row = list.attachedRecordingAnchor(record.id) ?: return@first false
                    val title = interactionViews(row).filterIsInstance<ParagraphView>().firstOrNull {
                        it.isShown && it.getText().toString() == record.displayName
                    } ?: return@first false
                    val clipped = Rect()
                    interactionVisibleScreenBounds(title, clipped) && clipped == interactionBounds(title) &&
                        viewport.contains(clipped)
                }
            }
            longPress(visibleRecord, JSONObject().also { entry.put("fresh_down", it) })
            back(); await("fresh menu closed") { activity.hasWindowFocus() && screen.currentState()?.menuRecording == null }
        }
        scenario("bottom-info-final-visible") { entry ->
            main { list.scrollView.scrollToPosition(checkNotNull(list.scrollView.adapter).itemCount - 1) }
            await("last row placed") { list.attachedRecordingAnchor(records.last().id)?.isShown == true }
            openInfo(records.last(), entry)
            val expectedLocation = describeRecordingLocation(context, records.last())
            var lastGeometry: String? = null
            var stable = 0
            await("complete final Info above padded viewport bottom") {
                val row = card(records.last().id)
                val info = interactionViews(row).filterIsInstance<ReverbInlineRecordingInfo>().singleOrNull()
                    ?: return@await false
                val last = interactionViews(info).filterIsInstance<ParagraphView>().last()
                if (last.getText().toString() != expectedLocation) return@await false
                val viewport = interactionBounds(list.scrollView).apply {
                    top += list.scrollView.paddingTop; bottom -= list.scrollView.paddingBottom
                }
                val full = interactionBounds(last)
                val wholeRow = interactionBounds(row)
                check(wholeRow.height() <= viewport.height()) { "Bounded fixture requires Info card to fit viewport" }
                val clipped = Rect()
                val geometry = "${interactionBounds(row)}:$full:$viewport"
                stable = if (geometry == lastGeometry) stable + 1 else 0
                lastGeometry = geometry
                val fits = interactionVisibleScreenBounds(last, clipped) && clipped == full && viewport.contains(full) && viewport.contains(wholeRow)
                entry.put("final_value_bounds", full.flattenToString()).put("padded_viewport", viewport.flattenToString())
                    .put("row_bounds", interactionBounds(row).flattenToString())
                    .put("resolved_location", expectedLocation).put("whole_card_fits", fits)
                fits && stable >= 4 && !row.isLayoutRequested && !list.scrollView.isLayoutRequested
            }
            capture("bottom-info-final")
            back(); await("Info closed") { screen.currentState()?.infoRecording == null }
        }
        scenario("painted-share-window-hit-no-rename-or-ime") { entry ->
            await("hidden IME baseline") { ViewCompat.getRootWindowInsets(inputRoot)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            longPress(records.last(), JSONObject().also { entry.put("menu", it) })
            entry.put("before", state()).put("windows_before", windowSnapshot())
            val launch = AtomicReference<Intent?>()
            val launchTime = AtomicReference<Long?>()
            val launchCount = AtomicInteger()
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action == Intent.ACTION_CHOOSER) {
                        launchCount.incrementAndGet()
                        if (launch.compareAndSet(null, Intent(intent))) launchTime.set(SystemClock.uptimeMillis())
                    }
                    return null // Observe only: allow actual chooser; never intercept/select a target.
                }
            }
            val popup = ownFocusedRoot()
            val observation = InteractionTiming(i, activity.window.decorView, popup, screen,
                { screen.currentState()?.renameRecording != null })
            i.addMonitor(monitor)
            var resolver: String? = null
            try {
                observation.start()
                tapLabel(context.getString(R.string.share_recording), entry, observation)
                await("observed Share chooser intent") {
                    check(screen.currentState()?.renameRecording == null) { "Painted Share activated Rename" }
                    check(screen.currentState()?.infoRecording == null) { "Painted Share activated Info" }
                    launch.get() != null
                }
                val chooser = checkNotNull(launch.get())
                check(launchCount.get() == 1) { "Duplicate chooser launch" }
                @Suppress("DEPRECATION") val send = checkNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
                check(send.action == Intent.ACTION_SEND)
                @Suppress("DEPRECATION") val uri = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                check(uri == buildRecordingUri(context, records.last())) { "Chooser contained a non-owned or wrong recording" }
                resolver = checkNotNull(chooser.resolveActivity(context.packageManager)).packageName
                entry.put("chooser", JSONObject().put("start_observed_ms", launchTime.get())
                    .put("inner_action", send.action).put("owned_uri", uri.toString()).put("resolver_package", resolver))
                await("actual chooser owns window") { focusedPackage() == resolver }
                entry.put("at_chooser", state()).put("windows_at_chooser", windowSnapshot())
                check(!entry.getJSONObject("windows_at_chooser").getBoolean("ime_window_present")) { "IME appeared on Share chooser path" }
                check(!observation.seen("rename_state_first_observed_ms") && !observation.seen("editor_first_observed_ms"))
                check(!observation.seen("ime_visible_first_observed_ms")) { "IME became visible on Share path" }
                observation.requireHealthy()
                back(checkNotNull(resolver))
                await("chooser cancellation returns to QA") { activity.hasWindowFocus() }
                entry.put("after_cancel", state()).put("windows_after_cancel", windowSnapshot())
                check(quietOwner())
                check(launchCount.get() == 1)
            } finally {
                observation.close(); entry.put("observations", observation.receipt())
                i.removeMonitor(monitor)
                if (resolver != null && focusedPackage() == resolver) back(checkNotNull(resolver))
            }
        }
        scenario("rename-first-observed-plus-three-warm") { entry ->
            val attempts = JSONArray().also { entry.put("attempts", it) }
            repeat(4) { index ->
                await("hidden IME and no previous Rename") {
                    activity.hasWindowFocus() && screen.currentState()?.renameRecording == null &&
                        ViewCompat.getRootWindowInsets(inputRoot)?.isVisible(WindowInsetsCompat.Type.ime()) == false
                }
                val attempt = JSONObject().put("index", index)
                    .put("classification", if (index == 0) "first_observed; IME coldness unproven" else "warm_repeat")
                attempts.put(attempt)
                longPress(records.last(), JSONObject().also { attempt.put("menu", it) })
                val popup = ownFocusedRoot()
                val timing = InteractionTiming(i, activity.window.decorView, popup, screen,
                    { screen.currentState()?.renameRecording != null })
                try {
                    timing.start()
                    tapLabel(context.getString(R.string.rename_recording), attempt, timing)
                    await("Rename same editor + app focus + real IME-visible") {
                        timing.sampleOnMain()
                        val editor = interactionViews(screen).filterIsInstance<EditText>().singleOrNull { it.isShown }
                        editor != null && editor.isFocused && editor.hasWindowFocus() && activity.hasWindowFocus() &&
                            ViewCompat.getRootWindowInsets(inputRoot)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                    attempt.put("positive_ime_observation", current.renameKeyboardSnapshot(i, windows))
                    timing.requireHealthy()
                    capture("rename-ime-$index")
                } finally { timing.close(); attempt.put("timing", timing.receipt()); persist() }
                tapLabel(context.getString(R.string.cancel), JSONObject().also { attempt.put("cancel", it) })
                await("Rename retired and keyboard hidden without recovery") {
                    screen.currentState()?.renameRecording == null &&
                        ViewCompat.getRootWindowInsets(inputRoot)?.isVisible(WindowInsetsCompat.Type.ime()) == false
                }
                fixtures.requireUnchanged()
            }
        }
    } catch (error: Throwable) { failure = error }
    finally {
        fun cleanup(action: () -> Unit) { failure = captureNativeParityCleanup(failure, action) }
        cleanup { retireInjectedGesture?.invoke() }
        cleanup { main { board?.close() } }
        cleanup { board?.awaitClosed() }
        cleanup { ledger?.close() }
        cleanup { main { host?.finish() } }
        cleanup { windowScope?.close() }
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
    report.appendLine("PASS native_library_interaction_current cases=5 rename_attempts=4")
}

/** QA-only listeners: no production hook, no claim that first observation equals request time. */
private class InteractionTiming(
    private val instrumentation: Instrumentation,
    private val decor: View,
    private val popup: View,
    private val content: View,
    private val renameActive: () -> Boolean,
) : AutoCloseable {
    private val times = JSONObject()
    private val focus = JSONArray()
    private var layouts = 0
    private var samples = 0
    private var editorIdentity: Int? = null
    private var observationError: String? = null
    private var popupMotion: Any? = null
    private var popupTarget: java.lang.reflect.Field? = null
    private var closed = false
    private val preDraw = ViewTreeObserver.OnPreDrawListener { sampleOnMain(); true }
    private val globalLayout = ViewTreeObserver.OnGlobalLayoutListener { layouts++; sampleOnMain() }
    private val localFocus = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> sampleOnMain() }
    private val windowFocus = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (focus.length() < 32) focus.put(JSONObject().put("ms", now()).put("app_window_focused", focused))
        if (focused) first("app_window_focus_gain_callback_ms")
        sampleOnMain()
    }
    private val popupAttach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { first("popup_detach_callback_ms"); sampleOnMain() }
    }
    private fun now() = SystemClock.uptimeMillis()
    private fun first(key: String) { if (!times.has(key)) times.put(key, now()) }
    fun mark(key: String) = nativeParityOnMain(instrumentation) { first(key) }
    fun start() = nativeParityOnMain(instrumentation) {
        popupMotion = checkNotNull(interactionField(checkNotNull(interactionField(popup, "animationLayer")), "motion"))
        popupTarget = checkNotNull(popupMotion).javaClass.getDeclaredField("targetExpanded").apply { isAccessible = true }
        check(checkNotNull(popupTarget).getBoolean(popupMotion)) { "Timing must start on expanded menu" }
        decor.viewTreeObserver.addOnPreDrawListener(preDraw)
        decor.viewTreeObserver.addOnGlobalLayoutListener(globalLayout)
        decor.viewTreeObserver.addOnGlobalFocusChangeListener(localFocus)
        decor.viewTreeObserver.addOnWindowFocusChangeListener(windowFocus)
        popup.viewTreeObserver.addOnPreDrawListener(preDraw)
        popup.addOnAttachStateChangeListener(popupAttach)
        sampleOnMain()
    }
    fun sampleNow() = nativeParityOnMain(instrumentation) { sampleOnMain() }
    fun sampleOnMain() {
        if (closed) return
        samples++
        if (popupTarget?.getBoolean(popupMotion) == false)
            first("popup_expanded_false_first_observed_ms")
        if (!popup.isAttachedToWindow) first("popup_detached_first_observed_ms")
        if (renameActive()) first("rename_state_first_observed_ms")
        val editor = interactionViews(content).filterIsInstance<EditText>().singleOrNull { it.isShown }
        if (editor != null) {
            first("editor_first_observed_ms")
            val identity = System.identityHashCode(editor)
            if (editorIdentity != null && editorIdentity != identity) observationError = "Editor replaced within timing attempt"
            editorIdentity = identity
            if (editor.isAttachedToWindow) first("editor_attached_first_observed_ms")
            if (editor.isLaidOut && !editor.isLayoutRequested) first("editor_laid_out_first_observed_ms")
            if (editor.isFocused) first("editor_local_focus_first_observed_ms")
            if (editor.hasWindowFocus()) first("editor_window_focus_first_observed_ms")
        }
        if (decor.hasWindowFocus()) first("app_window_focus_first_observed_ms")
        if (ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            first("ime_visible_first_observed_ms")
    }
    fun seen(key: String): Boolean = nativeParityOnMain(instrumentation) { times.has(key) }
    fun requireHealthy() = nativeParityOnMain(instrumentation) { check(observationError == null) { checkNotNull(observationError) } }
    fun receipt(): JSONObject = nativeParityOnMain(instrumentation) {
        val intervals = JSONObject()
        fun interval(name: String, from: String, to: String) {
            intervals.put(name, if (times.has(from) && times.has(to)) times.getLong(to) - times.getLong(from) else JSONObject.NULL)
        }
        interval("up_to_rename_state_observed_ms", "tap_up_send_ms", "rename_state_first_observed_ms")
        interval("up_to_editor_layout_observed_ms", "tap_up_send_ms", "editor_laid_out_first_observed_ms")
        interval("popup_exit_observed_to_detach_callback_ms", "popup_expanded_false_first_observed_ms", "popup_detach_callback_ms")
        interval("up_to_app_focus_gain_callback_ms", "tap_up_send_ms", "app_window_focus_gain_callback_ms")
        interval("app_focus_gain_to_ime_visible_observed_ms", "app_window_focus_gain_callback_ms", "ime_visible_first_observed_ms")
        interval("up_to_ime_visible_observed_ms", "tap_up_send_ms", "ime_visible_first_observed_ms")
        JSONObject().put("raw_uptime_ms", times).put("intervals", intervals).put("window_focus_events", focus)
            .put("observation_error", observationError ?: JSONObject.NULL)
            .put("editor_identity", editorIdentity ?: JSONObject.NULL).put("observed_global_layout_callbacks", layouts)
            .put("observation_callbacks", samples).put("controller_entry_exact_ms", JSONObject.NULL)
            .put("imm_show_request_exact_ms", JSONObject.NULL).put("imm_show_return_exact_ms", JSONObject.NULL)
            .put("show_request_to_ime_visible_ms", JSONObject.NULL)
            .put("unavailable_reason", "No shipping controller/IMM timing hook; first observed state is not request timestamp")
            .put("interpretation", "Popup exit/window admission is app-owned gating. Focus-to-insets includes app scheduling/framework/IME; no exclusive OS attribution or painted/readiness claim.")
    }
    override fun close() = nativeParityOnMain(instrumentation) {
        closed = true
        if (decor.viewTreeObserver.isAlive) {
            decor.viewTreeObserver.removeOnPreDrawListener(preDraw)
            decor.viewTreeObserver.removeOnGlobalLayoutListener(globalLayout)
            decor.viewTreeObserver.removeOnGlobalFocusChangeListener(localFocus)
            decor.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocus)
        }
        if (popup.viewTreeObserver.isAlive) popup.viewTreeObserver.removeOnPreDrawListener(preDraw)
        popup.removeOnAttachStateChangeListener(popupAttach)
    }
}

private fun interactionViews(root: View): List<View> = buildList {
    fun visit(view: View) { add(view); if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) } }
    visit(root)
}
private fun interactionBounds(view: View): Rect = IntArray(2).also(view::getLocationOnScreen).let {
    Rect(it[0], it[1], it[0] + view.width, it[1] + view.height)
}
private fun interactionVisibleScreenBounds(view: View, result: Rect): Boolean {
    if (!view.getGlobalVisibleRect(result)) return false
    // Android's "global" visible rect is root-relative, including for a separately
    // positioned popup window. System pointer injection and interactionBounds use screen space.
    val root = view.rootView
    check(root.matrix.isIdentity && root.scrollX == 0 && root.scrollY == 0)
    val origin = IntArray(2).also(root::getLocationOnScreen)
    result.offset(origin[0], origin[1])
    return true
}
private fun interactionField(owner: Any, name: String): Any? {
    var type: Class<*>? = owner.javaClass
    while (type != null) {
        val current = type
        val field = current.declaredFields.firstOrNull { it.name == name }
        if (field != null) return field.apply { isAccessible = true }.get(owner)
        type = current.superclass
    }
    return null
}
