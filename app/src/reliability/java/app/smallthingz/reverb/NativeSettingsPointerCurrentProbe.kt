package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.graphics.Rect
import android.graphics.Point
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.smallthingz.reverb.nativeui.NativeSettingsScreenContent
import app.smallthingz.reverb.nativeui.SettingsSectionKey
import smallthingz.ui.components.NativeChip
import smallthingz.ui.components.NativeInputGate
import smallthingz.ui.components.NativeSegmentedChoice
import smallthingz.ui.components.NativeSegmentedControl
import smallthingz.ui.reverb.settings.ReverbRetentionSection
import smallthingz.ui.reverb.settings.ReverbSettingsSectionListView
import smallthingz.ui.scroll.NativeAxisOverscroll
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Small independent Main-root touch repro. No semantic click or direct model selection. */
internal fun verifyNativeSettingsPointerCurrent(i: Instrumentation, report: StringBuilder) {
    val context = i.targetContext
    check(context.packageName == "app.smallthingz.reverb.uitheme.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val original = getRecorderPreferences(context).all.toMap()
    val directory = File(context.filesDir, "native-settings-pointer-current-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("passed", false).put("selector", "native_settings_pointer_current")
        .put("cases", cases).put("scope", "Current actual Main-root finger DOWN/UP; fresh and retained Settings; no Done/save")
        .put("not_qualified", "Installed old normal APK, physical input transport, scroll-to-tap interruption, all configurations")
    fun persist() = writeQaEvidenceAtomically(File(directory, "result.json")) { it.write(result.toString(2).toByteArray()) }
    fun <T> main(block: () -> T) = nativeParityOnMain(i, block)
    fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
    fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).run { isAccessible = true; get(owner) }
    fun bounds(view: View): Rect {
        val xy = IntArray(2).also(view::getLocationOnScreen)
        return Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
    }
    var host: NativeParityActivity? = null
    var render: MainSettingsParityRender? = null
    var failure: Throwable? = null
    var pendingTarget: View? = null
    var pendingEntry: JSONObject? = null
    fun geometry(view: View): JSONObject {
        val visible = Rect()
        val globalOffset = Point()
        val shown = view.getGlobalVisibleRect(visible, globalOffset)
        val screen = IntArray(2).also(view::getLocationOnScreen)
        val window = IntArray(2).also(view::getLocationInWindow)
        val rootScreen = IntArray(2).also(view.rootView::getLocationOnScreen)
        val localVisible = Rect()
        val localShown = view.getLocalVisibleRect(localVisible)
        return JSONObject().put("class", view.javaClass.name).put("identity", System.identityHashCode(view))
            .put("bounds_screen", bounds(view).flattenToString()).put("visible_global", visible.flattenToString())
            .put("global_offset", JSONArray(listOf(globalOffset.x, globalOffset.y)))
            .put("screen_location", JSONArray(screen.toList())).put("window_location", JSONArray(window.toList()))
            .put("root_screen_offset", JSONArray(rootScreen.toList()))
            .put("local_visible", localVisible.flattenToString()).put("local_visible_result", localShown)
            .put("global_visible_result", shown).put("layout_requested", view.isLayoutRequested)
            .put("laid_out", view.isLaidOut).put("attached", view.isAttachedToWindow)
            .put("shown", view.isShown).put("visibility", view.visibility).put("alpha", view.alpha)
            .put("translation_x", view.translationX).put("translation_y", view.translationY)
            .put("scroll_x", view.scrollX).put("scroll_y", view.scrollY)
            .put("window_focused", view.hasWindowFocus()).apply {
                if (view is ViewGroup) put("clip_children", view.clipChildren).put("clip_to_padding", view.clipToPadding)
            }
    }
    fun ancestors(view: View) = JSONArray(generateSequence(view) { it.parent as? View }.map(::geometry).toList())
    persist()
    try {
        val activity = launchCurrentUiSmokeActivity(i).also { host = it }
        val current = main { MainSettingsParityRender(activity, false, AppThemeMode.LIGHT, false, null,
            productionWindowFocus = true) }.also { render = it }
        fun frame() { requireNativeParityHost(i, activity); awaitNativeParityDraw(i, current.board) }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!main(condition)) { check(SystemClock.uptimeMillis() < deadline) { "Timed out: $label" }; frame() }
        }
        await("Main ready") { views(current.board).filterIsInstance<NativeMainScreenView>().any { it.isLaidOut } }
        val root = main { views(current.board).filterIsInstance<NativeMainScreenView>().single() }
        fun tap(box: Rect, entry: JSONObject, holdMillis: Long = 0L,
            prepareDown: (() -> Rect?)? = null, verifyHeld: (() -> Unit)? = null) {
            var down = 0L
            var admittedBox = box
            var active = false
            val motions = JSONArray().also { entry.put("motion", it) }
            fun send(action: Int) = main {
                check(activity.hasWindowFocus() && root.isShown)
                if (action == MotionEvent.ACTION_DOWN) {
                    if (prepareDown != null) admittedBox = prepareDown() ?: return@main false
                    down = SystemClock.uptimeMillis()
                } else if (action == MotionEvent.ACTION_UP) verifyHeld?.invoke()
                val xy = IntArray(2).also(root::getLocationOnScreen)
                val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val coords = MotionEvent.PointerCoords().apply { x = admittedBox.exactCenterX(); y = admittedBox.exactCenterY(); pressure = 1f; size = 1f }
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coords),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try {
                    event.offsetLocation(-xy[0].toFloat(), -xy[1].toFloat())
                    val observation = JSONObject().put("action", action).put("raw_x", event.rawX).put("raw_y", event.rawY)
                        .put("local_x", event.x).put("local_y", event.y).put("tool", event.getToolType(0))
                    motions.put(observation)
                    if (action == MotionEvent.ACTION_DOWN) active = true
                    observation.put("handled", root.dispatchTouchEvent(event))
                } finally { event.recycle() }
                true
            }
            try {
                val deadline = SystemClock.uptimeMillis() + 5_000L
                while (!send(MotionEvent.ACTION_DOWN)) {
                    check(SystemClock.uptimeMillis() < deadline) { "Timed out: fresh visible stable target before DOWN" }
                    frame()
                }
                do { frame() } while (SystemClock.uptimeMillis() - down < holdMillis)
                entry.put("down_to_up_ms", SystemClock.uptimeMillis() - down)
                send(MotionEvent.ACTION_UP); active = false
            }
            finally { if (active) send(MotionEvent.ACTION_CANCEL) }
        }
        fun tapLabel(id: Int) {
            val label = activity.getString(id)
            var box: Rect? = null
            await("visible $label") {
                val candidates = views(root).mapNotNull { view ->
                    val node = view.createAccessibilityNodeInfo()
                    try {
                        if (node?.isVisibleToUser == true && node.contentDescription?.toString() == label)
                            Rect().also(node::getBoundsInScreen).takeUnless { it.isEmpty } else null
                    } finally { @Suppress("DEPRECATION") node?.recycle() }
                }.distinct().toList()
                candidates.singleOrNull()?.also { box = it } != null
            }
            val entry = JSONObject().put("navigation", label); cases.put(entry)
            tap(checkNotNull(box), entry)
        }
        for (round in 0..1) {
            tapLabel(R.string.open_settings)
            lateinit var settings: NativeSettingsScreenContent
            await("Settings hydrated") { views(root).filterIsInstance<NativeSettingsScreenContent>()
                .firstOrNull { it.inputAllowed }?.also { settings = it } != null }
            await("Settings settled") { (field(root, "settingsMotion") as NativeMainPanelMotion).panelProgress == 1f }
            val baseline = main { settings.owner.snapshot }
            fun select(theme: Boolean, value: String) {
                val entry = JSONObject().put("round", round).put("kind", if (theme) "theme" else "retention")
                    .put("requested", value).put("passed", false); cases.put(entry)
                pendingEntry = entry
                pendingTarget = null
                fun pendingRestoration(): Any? {
                    val owner = views(settings).filterIsInstance<ReverbSettingsSectionListView>().single()
                    return field(checkNotNull(field(owner, "pendingPosition")), "pending")
                }
                fun listObservation(): JSONObject {
                    val viewport = checkNotNull(settings.listViewport)
                    val manager = viewport.layoutManager as LinearLayoutManager
                    val rows = JSONArray()
                    for (index in 0 until viewport.childCount) {
                        val row = viewport.getChildAt(index)
                        val position = viewport.getChildAdapterPosition(row)
                        rows.put(geometry(row).put("adapter_position", position)
                            .put("view_type", if (position == RecyclerView.NO_POSITION) JSONObject.NULL
                                else checkNotNull(viewport.adapter).getItemViewType(position)))
                    }
                    val candidates = JSONArray()
                    // Record BEFORE filtering: an absent row and a painted-but-unlaid-out
                    // choice are different failures, including for production hit admission.
                    views(settings).filterIsInstance<NativeSegmentedChoice>().forEach { choice ->
                        val matches = if (theme) choice.choiceView(value) != null else
                            generateSequence(choice.parent as? View) { it.parent as? View }
                                .any { it is ReverbRetentionSection }
                        val leaf = if (!matches) null else if (theme) choice.choiceView(value)
                            else choice.getChildAt(if (value == "TIME") 0 else 1) as? NativeChip
                        candidates.put(geometry(choice).put("matches_requested", matches)
                            .put("requested_chip", leaf?.let(::geometry) ?: JSONObject.NULL))
                    }
                    return JSONObject().put("viewport", geometry(viewport))
                        .put("first_visible", manager.findFirstVisibleItemPosition())
                        .put("last_visible", manager.findLastVisibleItemPosition())
                        .put("pending_restoration", pendingRestoration()?.toString() ?: JSONObject.NULL)
                        .put("scroll_state", viewport.scrollState)
                        .put("computing_layout", viewport.isComputingLayout)
                        .put("pending_adapter_updates", viewport.hasPendingAdapterUpdates())
                        .put("rows", rows).put("unfiltered_choices", candidates)
                }
                val restoration = JSONArray().also { entry.put("restoration_before_reveal", it) }
                // Hydration/panel motion can finish before RV places savedPosition. Its
                // onLayoutChildren would overwrite a premature direct manager scroll request.
                await("production Settings restoration placed before explicit reveal") {
                    val viewport = checkNotNull(settings.listViewport)
                    restoration.put(listObservation())
                    pendingRestoration() == null && viewport.isAttachedToWindow && viewport.isLaidOut &&
                        !viewport.isLayoutRequested && !viewport.isComputingLayout && !viewport.hasPendingAdapterUpdates() &&
                        (viewport.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() != RecyclerView.NO_POSITION
                }
                main {
                    val viewport = checkNotNull(settings.listViewport)
                    check(pendingRestoration() == null && !viewport.isLayoutRequested && !viewport.hasPendingAdapterUpdates()) {
                        "Restoration changed before explicit reveal; no scroll or input issued"
                    }
                    entry.put("before_explicit_reveal", listObservation())
                    viewport.stopScroll()
                    entry.put("after_stop_scroll_state", viewport.scrollState)
                    val adapter = checkNotNull(viewport.adapter)
                    val type = if (theme) SettingsSectionKey.THEME else SettingsSectionKey.RETENTION
                    val position = (0 until adapter.itemCount).single { adapter.getItemViewType(it) == type.ordinal }
                    entry.put("explicit_reveal_adapter_position", position).put("explicit_reveal_section", type.name)
                    (viewport.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
                }
                fun liveChip(): NativeChip? {
                    val choice = views(settings).filterIsInstance<NativeSegmentedChoice>().filter {
                        it.isAttachedToWindow && it.isShown && it.isLaidOut &&
                        if (theme) it.choiceView(value) != null else generateSequence(it.parent as? View) { v -> v.parent as? View }
                            .any { v -> v is ReverbRetentionSection }
                    }.singleOrNull() ?: return null
                    return (if (theme) choice.choiceView(value) else
                        choice.getChildAt(if (value == "TIME") 0 else 1) as? NativeChip)
                        ?.takeIf { it.isAttachedToWindow && it.isShown && it.isLaidOut }
                }
                lateinit var chip: NativeChip
                var candidate: NativeChip? = null
                var previous: Rect? = null; var stable = 0
                val readiness = JSONArray().also { entry.put("visibility_frames", it) }
                var capsule: NativeSegmentedControl? = null
                var callback: ((String) -> Boolean)? = null
                var requests = 0
                val forwarded: (String) -> Boolean = { id ->
                    requests++
                    checkNotNull(callback)(id).also { accepted -> entry.put("callback_id", id).put("callback_accepted", accepted) }
                }
                fun prepareDown(): Rect? {
                    val viewport = checkNotNull(settings.listViewport)
                    val live = liveChip()
                    val listState = listObservation().also { entry.put("latest_list_readiness", it) }
                    val layoutPending = !viewport.isLaidOut || viewport.isLayoutRequested ||
                        viewport.isComputingLayout || viewport.hasPendingAdapterUpdates()
                    if (live !== candidate) { candidate = live; previous = null; stable = 0 }
                    pendingTarget = live
                    if (live == null || layoutPending) {
                        stable = 0
                        readiness.put(JSONObject().put("uptime_ms", SystemClock.uptimeMillis())
                            .put("live_target", live?.let(::geometry) ?: JSONObject.NULL)
                            .put("viewport_layout_pending", layoutPending).put("list_state", listState))
                        return null
                    }
                    val next = bounds(live); val visible = Rect()
                    stable = if (next == previous && !live.isLayoutRequested) stable + 1 else 0
                    val firstObservation = previous == null
                    previous = next
                    val visibleResult = live.getGlobalVisibleRect(visible)
                    readiness.put(geometry(live).put("uptime_ms", SystemClock.uptimeMillis()).put("stable_frames", stable)
                        .put("full_visible_equal_screen", visible == next).put("ancestors", ancestors(live)).put("list_state", listState))
                    if (firstObservation) {
                        live.requestRectangleOnScreen(Rect(0, 0, live.width, live.height), true)
                        return null
                    }
                    if (stable < 2 || !visibleResult || visible != next || next.isEmpty) return null
                    chip = live
                    val targetCapsule = generateSequence(chip.parent as? View) { it.parent as? View }
                        .filterIsInstance<NativeSegmentedControl>().first()
                    capsule = targetCapsule
                    callback = checkNotNull(targetCapsule.onSelectionRequest)
                    entry.put("before_snapshot", settings.owner.snapshot.toString()).put("owner_accepts", settings.owner.acceptsInput())
                        .put("capsule_accepts", targetCapsule.acceptsNativeInput).put("attached", chip.isAttachedToWindow)
                        .put("shown", chip.isShown).put("window_focused", chip.hasWindowFocus())
                        .put("admitted_target_identity", System.identityHashCode(chip))
                        .put("target_focused_before_down", chip.isFocused)
                        .put("focus_before_down", root.findFocus()?.javaClass?.name ?: JSONObject.NULL)
                        .put("target_bounds", bounds(chip).flattenToString())
                        .put("ancestor_gates", JSONArray(generateSequence(chip as View?) { it.parent as? View }
                            .filterIsInstance<NativeInputGate>().map { it.acceptsNativeInput }.toList()))
                    val scroll = field(checkNotNull(field(root, "settingsPointer")), "scroll")
                    if (scroll != null) entry.put("scroll_mutating", field(scroll, "mutating"))
                        .put("overscroll_active", (field(scroll, "overscroll") as NativeAxisOverscroll).isInProgress)
                    targetCapsule.onSelectionRequest = forwarded
                    return next
                }
                try {
                    entry.put("requested_hold_ms", if (round == 0) 0 else 180)
                    // Resolve and admit in the same main-thread call that dispatches DOWN.
                    // Holder replacement before DOWN restarts stability, never a pointer stream.
                    tap(Rect(), entry, if (round == 0) 0L else 180L, ::prepareDown) {
                        val visible = Rect()
                        check(liveChip() === chip && chip.hasWindowFocus() &&
                            chip.getGlobalVisibleRect(visible) && visible == bounds(chip)) {
                            "Admitted selector lost identity/visibility while held; cancel without retargeting"
                        }
                        entry.put("release_target_identity", System.identityHashCode(chip))
                            .put("callback_count_before_up", requests)
                        check(requests == 0) { "Selector activated before finger release" }
                    }
                    await("root touch selects $value") {
                        entry.put("callback_count", requests).put("after_snapshot", settings.owner.snapshot.toString())
                        (if (theme) settings.owner.snapshot.themeMode.name else settings.owner.snapshot.retentionMode.name) == value && chip.isChecked
                    }
                    main { check(requests == 1 && entry.optBoolean("callback_accepted")); if (theme) check(current.effectiveDark == (value == "DARK")) }
                    entry.put("passed", true)
                    if (theme && value == "LIGHT") {
                        val bitmap = captureNativeParityContent(i, activity) { current.board }
                        try { saveNativeParityBitmap(directory, "light-round-$round.png", bitmap) } finally { bitmap.recycle() }
                    }
                } finally {
                    main { entry.put("callback_count", requests); capsule?.onSelectionRequest = callback }
                    persist()
                }
            }
            select(true, if (baseline.themeMode == AppThemeMode.DARK) "LIGHT" else "DARK")
            select(true, if (main { settings.owner.snapshot.themeMode } == AppThemeMode.LIGHT) "DARK" else "LIGHT")
            if (main { settings.owner.snapshot.themeMode } != AppThemeMode.LIGHT) select(true, "LIGHT")
            select(false, if (baseline.retentionMode == RetentionMode.TIME) "SIZE" else "TIME")
            if (main { settings.owner.state.hasUnsavedChanges }) tapLabel(R.string.undo)
            await("Undo restores draft") { settings.owner.snapshot == baseline && !settings.owner.state.hasUnsavedChanges }
            tapLabel(R.string.onboarding_back)
            await("Home restored") { !root.saveState().navigation.showSettings &&
                (field(root, "settingsMotion") as NativeMainPanelMotion).panelProgress == 0f }
        }
        main { check(current.forbiddenCalls == 0) }
        result.put("passed", true)
    } catch (error: Throwable) {
        failure = error
        // Capture before teardown, without requiring a settled layout: layout instability may BE the failure.
        try { main { pendingTarget?.let { pendingEntry?.put("failure_ancestors", ancestors(it)) } } }
        catch (diagnostic: Throwable) { error.addSuppressed(diagnostic) }
        try {
            host?.let { activity ->
                requireNativeParityHost(i, activity)
                val bitmap = checkNotNull(nativeParityUiAutomation(i).takeScreenshot())
                try { saveNativeParityBitmap(directory, "failure.png", bitmap); result.put("failure_screenshot", "failure.png") }
                finally { bitmap.recycle() }
            }
        } catch (diagnostic: Throwable) {
            result.put("failure_screenshot_error", diagnostic.toString()); error.addSuppressed(diagnostic)
        }
    }
    finally {
        failure = captureNativeParityCleanup(failure) { main { render?.close() } }
        failure = captureNativeParityCleanup(failure) { render?.awaitClosed() }
        failure = captureNativeParityCleanup(failure) { main { host?.finish() } }
        failure = captureNativeParityCleanup(failure) { check(getRecorderPreferences(context).all == original) }
        if (failure != null) result.put("passed", false).put("failure", failure!!.stackTraceToString())
        failure = captureNativeParityCleanup(failure, ::persist)
        report.appendLine("SETTINGS_POINTER_CURRENT_EVIDENCE ${directory.absolutePath}")
    }
    failure?.let { throw it }
    report.appendLine("PASS native_settings_pointer_current actual Main root, theme/retention, Light and retained roundtrip")
}
