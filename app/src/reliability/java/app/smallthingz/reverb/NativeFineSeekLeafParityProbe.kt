package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import smallthingz.ui.geometry.Offset
import smallthingz.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.smallthingz.reverb.nativeui.NativeAboutWindowRuntime
import app.smallthingz.reverb.nativeui.NativeVerticalNestedHost
import app.smallthingz.reverb.nativeui.NativeVerticalScrollSource
import app.smallthingz.reverb.nativeui.NativeVerticalScrollState
import app.smallthingz.reverb.nativeui.NativeVerticalViewport
import app.smallthingz.reverb.nativeui.ReverbPalette
import app.smallthingz.reverb.nativeui.ReverbSpringFineSeekView
import app.smallthingz.reverb.nativeui.SpringFineSeekBinding
import app.smallthingz.reverb.nativeui.SpringFineSeekEnvironment
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** Actual released leaf versus native leaf; callbacks own only in-memory QA values. */
internal fun verifyNativeFineSeekLeafParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-fine-seek-leaf-${System.currentTimeMillis()}")
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("passed", false).put("cases", cases)
        .put("reference", "Unchanged SpringFineSeekControl in ReleasedRangeEditorRenderers.kt; v0.1.2")
        .put("scope", "One Light/LTR320x240dp board;104dp Library leaf, ordinary tap, drag/UP, parent-owned scroll, explicit CANCEL")
        .put("physical_input_injected", false).put("playback_started", false)
        .put("not_qualified", "Playback, complete RecyclerView, accessibility, indirect input, motion frame phase and exact accumulated real-time seek deltas")
    var activity: NativeParityActivity? = null
    var active: FineLeafRender? = null
    var primary: Throwable? = null
    fun retain(error: Throwable) {
        val earlier = primary
        if (earlier == null) primary = error else if (earlier !== error) earlier.addSuppressed(error)
    }
    try {
        writeFineLeafEvidence(directory, result) // Keep an initial false receipt if terminal publication fails.
        val host = launchNativeParityActivity(instrumentation)
        activity = host
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        for (scenario in FineLeafScenario.entries) {
            val observations = mutableListOf<JSONObject>()
            val terminalImages = mutableListOf<Bitmap>()
            val heldImages = mutableListOf<Bitmap>()
            val entry = JSONObject().put("name", scenario.name).put("passed", false)
                .put("completed_observations", JSONArray())
            cases.put(entry)
            var caseFailure: Throwable? = null
            try {
                for (reference in listOf(true, false)) {
                    requireNativeParityHost(instrumentation, host)
                    val render = nativeParityOnMain(instrumentation) {
                        FineLeafRender(host, reference).also {
                            active = it
                            host.fixtureRoot.addView(it.root, FrameLayout.LayoutParams(it.widthPx, it.heightPx))
                        }
                    }
                    awaitNativeParityDraw(instrumentation, render.root)
                    awaitFineLeafTime(instrumentation, host, render, 40)
                    val initial = nativeParityOnMain(instrumentation) { render.snapshot("initial") }
                    val held = runFineLeafGesture(instrumentation, host, render, scenario)
                    held?.let {
                        heldImages += it
                        saveNativeParityBitmap(directory, "${scenario.name}-${render.label}-held.png", it)
                    }
                    awaitFineLeafTime(instrumentation, host, render, 280)
                    val image = captureNativeParityContent(instrumentation, host) { render.root }
                    terminalImages += image
                    saveNativeParityBitmap(directory, "${scenario.name}-${render.label}-terminal.png", image)
                    val repeat = captureNativeParityContent(instrumentation, host) { render.root }
                    val stability = try { compareNativeParityPixels(image, repeat) } finally { repeat.recycle() }
                    val observed = nativeParityOnMain(instrumentation) { render.snapshot("terminal") }
                    observed.put("initial", initial).put("terminal_stability", stability)
                    val errors = fineLeafExpectations(scenario, observed)
                    observed.put("expectation_errors", JSONArray(errors))
                    observations += observed
                    entry.getJSONArray("completed_observations").put(observed)
                    nativeParityOnMain(instrumentation) { active = null; render.close() }
                    File(directory, "${scenario.name}-${render.label}.json").writeText(observed.toString(2))
                }
                val source = observations[0]
                val native = observations[1]
                val callbacksEqual = source.getJSONArray("critical_callbacks").toString() ==
                    native.getJSONArray("critical_callbacks").toString()
                val scrollEqual = source.getInt("scroll") == native.getInt("scroll")
                val terminalPixels = compareNativeParityPixels(terminalImages[0], terminalImages[1])
                val heldPixels = if (heldImages.size == 2) compareNativeParityPixels(heldImages[0], heldImages[1]) else null
                val pass = callbacksEqual && scrollEqual && terminalPixels.getInt("different_pixels") == 0 &&
                    (heldPixels == null || heldPixels.getInt("different_pixels") == 0) && observations.all {
                        it.getJSONArray("expectation_errors").length() == 0 &&
                            it.getJSONObject("terminal_stability").getInt("different_pixels") == 0
                    }
                entry.put("compose", source).put("native", native)
                    .put("critical_callback_order_and_begin_rate_equal", callbacksEqual)
                    .put("terminal_parent_scroll_equal", scrollEqual).put("terminal_pixels", terminalPixels)
                    .put("held_pixels", heldPixels ?: JSONObject.NULL)
                    .put("real_time_callback_totals_compared", false).put("passed", pass)
            } catch (error: Throwable) {
                caseFailure = error
                try {
                    nativeParityOnMain(instrumentation) { active?.partialSnapshot() }?.let {
                        entry.put("incomplete_render", it)
                    }
                } catch (evidence: Throwable) { if (evidence !== error) error.addSuppressed(evidence) }
                entry.put("failure", android.util.Log.getStackTraceString(error))
                throw error
            } finally {
                terminalImages.forEach(Bitmap::recycle)
                heldImages.forEach(Bitmap::recycle)
                try { nativeParityOnMain(instrumentation) { val old = active; active = null; old?.close() } }
                catch (error: Throwable) { if (caseFailure == null) throw error else caseFailure.addSuppressed(error) }
            }
        }
        check((0 until cases.length()).all { cases.getJSONObject(it).getBoolean("passed") }) {
            "FineSeek leaf differs; retain exact pixels/callbacks/ownership and real frame observations"
        }
    } catch (error: Throwable) { retain(error) }
    finally {
        try { nativeParityOnMain(instrumentation) { val old = active; active = null; old?.close() } }
        catch (error: Throwable) { retain(error) }
        try { nativeParityOnMain(instrumentation) { activity?.finish() } }
        catch (error: Throwable) { retain(error) }
        result.put("passed", primary == null && cases.length() == FineLeafScenario.entries.size)
        primary?.let { result.put("failure", android.util.Log.getStackTraceString(it)) }
        var evidenceWritten = false
        try {
            writeFineLeafEvidence(directory, result)
            evidenceWritten = true
        } catch (error: Throwable) {
            retain(error)
            result.put("passed", false).put("failure", android.util.Log.getStackTraceString(checkNotNull(primary)))
            // One bounded failure publication; the initial canonical receipt cannot become a stale PASS.
            try { writeFineLeafEvidence(directory, result); evidenceWritten = true }
            catch (writeFailure: Throwable) { retain(writeFailure) }
        } finally {
            // The sole runner needs the identity-bound directory even when storage publication itself failed.
            report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
            if (!evidenceWritten) report.append("PARITY_EVIDENCE_WRITE_FAILED ")
                .append(directory.name).append("/result.json\n")
        }
    }
    primary?.let { throw it }
    report.append("PASS native_fine_seek_leaf bounded leaf pixels/callback/parent ownership; real frame timing remains unqualified\n")
}

private fun writeFineLeafEvidence(directory: File, result: JSONObject) {
    writeQaEvidenceAtomically(File(directory, "result.json")) { output ->
        output.write(result.toString(2).toByteArray(Charsets.UTF_8))
    }
}

private enum class FineLeafScenario { TAP, DRAG_UP, PARENT_SCROLL, DRAG_CANCEL }

private fun fineLeafExpectations(scenario: FineLeafScenario, observation: JSONObject): List<String> {
    val errors = mutableListOf<String>()
    val callbacks = observation.getJSONArray("critical_callbacks")
    val names = (0 until callbacks.length()).map { callbacks.getJSONObject(it).getString("name") }
    val expected = when (scenario) {
        FineLeafScenario.TAP -> listOf("toggle")
        FineLeafScenario.DRAG_UP -> listOf("interaction_start", "begin", "end")
        FineLeafScenario.PARENT_SCROLL -> emptyList()
        FineLeafScenario.DRAG_CANCEL -> listOf("interaction_start", "begin", "cancel")
    }
    if (names != expected) errors += "Expected critical callbacks $expected, observed $names"
    if (observation.getBoolean("is_playing") != (scenario == FineLeafScenario.TAP)) errors += "Unexpected toggle state"
    if (scenario == FineLeafScenario.PARENT_SCROLL) {
        if (observation.getInt("scroll") <= 0) errors += "Parent did not actually consume a scroll"
    } else if (observation.getInt("scroll") != 0) errors += "Parent stole an admitted FineSeek gesture"
    if (!observation.getBoolean("callback_numbers_finite")) errors += "Non-finite callback result"
    if (!observation.getBoolean("commit_resolution_exact")) errors += "Commit resolution changed"
    if ((scenario == FineLeafScenario.DRAG_UP || scenario == FineLeafScenario.DRAG_CANCEL) &&
        observation.getInt("shuttle_count") == 0) errors += "Held fine drag never produced actual frame callback"
    if (scenario == FineLeafScenario.DRAG_UP) {
        val all = observation.getJSONArray("callbacks")
        val commits = (0 until all.length()).map(all::getJSONObject).filter { it.getString("name") == "commit" }
        val deltas = commits.map { it.getJSONArray("values").getDouble(0) }
        if (deltas.isEmpty()) errors += "Normal fine drag never committed an adjustment"
        if (!deltas.any { it.isFinite() && it > 0.0 } || deltas.any { !it.isFinite() || it < 0.0 })
            errors += "Rightward fine drag did not commit a finite positive adjustment"
        val initial = observation.getJSONObject("initial").getDouble("in_memory_position_seconds")
        val position = observation.getDouble("in_memory_position_seconds")
        if (!position.isFinite() || position <= initial) errors += "Normal fine drag did not advance its in-memory position"
    }
    return errors
}

/** Every event is dispatched into the current owned fixture View only, never Instrumentation input. */
private fun runFineLeafGesture(instrumentation: Instrumentation, host: NativeParityActivity,
                              render: FineLeafRender, scenario: FineLeafScenario): Bitmap? {
    var downTime = 0L
    val density = render.density.density
    val center = nativeParityOnMain(instrumentation) {
        val bounds = render.leafBounds()
        check(bounds.width() == render.widthPx && bounds.height() == (104f * density).roundToInt())
        Offset(bounds.exactCenterX(), bounds.exactCenterY())
    }
    fun event(action: Int, point: Offset) {
        requireNativeParityHost(instrumentation, host)
        nativeParityOnMain(instrumentation) {
            render.checkCurrent()
            check(point.x >= 0 && point.y >= 0 && point.x < render.root.width && point.y < render.root.height)
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val screen = IntArray(2).also(render.root::getLocationOnScreen)
            val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                x = point.x + screen[0]; y = point.y + screen[1]; pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
                size = 1f
            })
            val motion = MotionEvent.obtain(downTime, now, action, 1, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            motion.offsetLocation(-screen[0].toFloat(), -screen[1].toFloat())
            try {
                val previous = render.inputs.optJSONObject(render.inputs.length() - 1)
                // AndroidComposeView rejects a single-pointer MOVE with unchanged raw coordinates.
                // This is the deliberate stationary sample of the already-admitted parent scroll,
                // not evidence that the owned scene or the pointer stream was lost.
                val stationaryReferenceMove = render.label == "compose" && scenario == FineLeafScenario.PARENT_SCROLL &&
                    action == MotionEvent.ACTION_MOVE && previous != null &&
                    previous.getInt("action") == MotionEvent.ACTION_MOVE && previous.getBoolean("handled") &&
                    previous.getJSONArray("point").getDouble(0) == point.x.toDouble() &&
                    previous.getJSONArray("point").getDouble(1) == point.y.toDouble() &&
                    previous.getJSONArray("raw_point").getDouble(0) == motion.rawX.toDouble() &&
                    previous.getJSONArray("raw_point").getDouble(1) == motion.rawY.toDouble() && render.scroll() > 0
                // Compose delivers ACTION_CANCEL through processCancel(), then returns a false
                // dispatch result. Accept that return only when this admitted drag synchronously
                // gains exactly its cancel callback; an absent or duplicate cancel still fails.
                val referenceCancel = render.label == "compose" && scenario == FineLeafScenario.DRAG_CANCEL &&
                    action == MotionEvent.ACTION_CANCEL && previous != null &&
                    previous.getInt("action") == MotionEvent.ACTION_MOVE && previous.getBoolean("handled") &&
                    render.criticalCallbackNames() == listOf("interaction_start", "begin")
                render.inputs.put(JSONObject().put("action", action).put("uptime_ms", now)
                    .put("point", JSONArray(listOf(point.x, point.y)))
                    .put("raw_point", JSONArray(listOf(motion.rawX, motion.rawY)))
                    .put("stationary_reference_move", stationaryReferenceMove).put("scroll_before", render.scroll()))
                val handled = render.root.dispatchTouchEvent(motion)
                val referenceCancelDelivered = referenceCancel &&
                    render.criticalCallbackNames() == listOf("interaction_start", "begin", "cancel")
                render.inputs.getJSONObject(render.inputs.length() - 1).put("handled", handled)
                    .put("reference_cancel_delivered", referenceCancelDelivered).put("scroll_after_dispatch", render.scroll())
                check(handled || stationaryReferenceMove || referenceCancelDelivered) {
                    "Current own fixture did not admit the expected pointer stream"
                }
            } finally { motion.recycle() }
        }
    }
    if (scenario == FineLeafScenario.PARENT_SCROLL) {
        val start = Offset(center.x, 190f * density)
        val end = Offset(center.x, 100f * density)
        event(MotionEvent.ACTION_DOWN, start)
        event(MotionEvent.ACTION_MOVE, Offset(center.x, 150f * density))
        awaitFineLeafTime(instrumentation, host, render, 20)
        event(MotionEvent.ACTION_MOVE, end)
        awaitFineLeafTime(instrumentation, host, render, 80)
        event(MotionEvent.ACTION_MOVE, end)
        // A real stationary interval prevents a fling from obscuring ownership/consumption.
        awaitFineLeafTime(instrumentation, host, render, 80)
        event(MotionEvent.ACTION_UP, end)
        return null
    }
    event(MotionEvent.ACTION_DOWN, center)
    if (scenario == FineLeafScenario.TAP) {
        event(MotionEvent.ACTION_UP, center)
        return null
    }
    val end = center + Offset(60f * density, 0f)
    event(MotionEvent.ACTION_MOVE, center + Offset(35f * density, 0f))
    event(MotionEvent.ACTION_MOVE, end)
    awaitFineLeafTime(instrumentation, host, render, 90)
    val held = captureNativeParityContent(instrumentation, host) { render.root }
    try {
        event(if (scenario == FineLeafScenario.DRAG_CANCEL) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, end)
        return held
    } catch (error: Throwable) { held.recycle(); throw error }
}

private fun awaitFineLeafTime(instrumentation: Instrumentation, host: NativeParityActivity,
                             render: FineLeafRender, minimumMillis: Long) {
    val start = SystemClock.uptimeMillis()
    do {
        requireNativeParityHost(instrumentation, host)
        nativeParityOnMain(instrumentation) { render.checkCurrent() }
        awaitNativeParityDraw(instrumentation, render.root)
        check(SystemClock.uptimeMillis() - start < 5_000) { "FineSeek draw observation exceeded its bound" }
    } while (SystemClock.uptimeMillis() - start < minimumMillis)
}

private class FineLeafTrace {
    val callbacks = JSONArray()
    val critical = JSONArray()
    var playing by mutableStateOf(false)
    var updatePlaying: () -> Unit = {}
    var position = 60f
    var shuttleCount = 0
    var finite = true
    var exactResolution = true
    fun event(name: String, vararg values: Float) {
        finite = finite && values.all(Float::isFinite)
        val entry = JSONObject().put("name", name).put("uptime_ms", SystemClock.uptimeMillis())
            .put("values", JSONArray(values.toList()))
        callbacks.put(entry)
        if (name in listOf("toggle", "interaction_start", "begin", "end", "cancel"))
            critical.put(JSONObject().put("name", name).put("values", JSONArray(values.toList())))
    }
    fun binding() = SpringFineSeekBinding(
        enabled = true, isPlaying = playing, durationSeconds = 120f, interactionKey = "owned-leaf",
        onTogglePlayback = { event("toggle"); playing = !playing; updatePlaying() },
        onInteractionStart = { event("interaction_start") },
        onBeginFineAdjust = { event("begin", it) },
        onFineAdjust = { delta, resolution -> event("commit", delta, resolution); position += delta; exactResolution = exactResolution && resolution == .04f },
        onUpdateFineAdjustShuttle = { rate, pending -> event("shuttle", rate, pending); shuttleCount++ },
        onEndFineAdjust = { event("end") }, onCancelFineAdjust = { event("cancel") },
    )
}

private class FineLeafRender(private val host: NativeParityActivity, private val reference: Boolean) : AutoCloseable {
    val label = if (reference) "compose" else "native"
    val density = Density(host.resources.displayMetrics.density, 1f)
    val widthPx = with(density) { 320.dp.roundToPx() }
    val heightPx = with(density) { 240.dp.roundToPx() }
    val inputs = JSONArray()
    private val trace = FineLeafTrace()
    private val palette = ReverbPalette.resolve(host, false)
    private var sourceBounds: Rect? = null
    private var sourceScroll: ScrollState? = null
    private var nativeScroll: NativeVerticalScrollState? = null
    private var compose: ComposeView? = null
    private var native: ReverbSpringFineSeekView? = null
    private var vertical: NativeVerticalPhaseBinding? = null
    private var runtime: NativeAboutWindowRuntime? = null
    private var lifecycle: LifecycleEventObserver? = null
    private var closed = false
    lateinit var root: View
        private set

    init {
        try {
        check(widthPx <= host.fixtureRoot.width && heightPx <= host.fixtureRoot.height)
        if (reference) {
            val scroll = ScrollState(0)
            sourceScroll = scroll
            val view = ComposeView(host)
            compose = view
            root = view
            view.setBackgroundColor(palette.surface)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides density, LocalLayoutDirection provides LayoutDirection.Ltr) {
                    MaterialTheme(colorScheme = if (Build.VERSION.SDK_INT >= 31) dynamicLightColorScheme(host) else lightColorScheme()) {
                        val value = trace.binding()
                        Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                            Spacer(Modifier.height(24.dp))
                            SpringFineSeekControl(value.enabled, value.isPlaying, value.durationSeconds, value.interactionKey,
                                value.onTogglePlayback, value.onInteractionStart, value.onBeginFineAdjust, value.onFineAdjust,
                                value.onUpdateFineAdjustShuttle, value.onEndFineAdjust, value.onCancelFineAdjust,
                                modifier = Modifier.fillMaxWidth().height(104.dp).onGloballyPositioned { coordinates ->
                                    val position = coordinates.positionInWindow()
                                    val origin = IntArray(2).also(view::getLocationInWindow)
                                    sourceBounds = Rect((position.x - origin[0]).roundToInt(), (position.y - origin[1]).roundToInt(),
                                        (position.x - origin[0]).roundToInt() + coordinates.size.width,
                                        (position.y - origin[1]).roundToInt() + coordinates.size.height)
                                })
                            Spacer(Modifier.height(480.dp))
                        }
                    }
                }
            }
        } else {
            val owner = NativeAboutWindowRuntime()
            runtime = owner
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) owner.setStarted(true)
                if (event == Lifecycle.Event.ON_STOP) owner.setStarted(false)
            }
            lifecycle = observer
            host.lifecycle.addObserver(observer)
            owner.setStarted(host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            val geometry = FineLeafPointerGeometry(density)
            val nativeRoot = NativePagerTouchRoot(host, geometry.coordinates::rootToScreen,
                geometry.coordinates::coordinates, geometry.layout, geometry.coordinates::begin)
            root = nativeRoot
            geometry.root = nativeRoot
            nativeRoot.clipChildren = false; nativeRoot.clipToPadding = false
            nativeRoot.setBackgroundColor(palette.surface)
            val state = NativeVerticalScrollState()
            nativeScroll = state
            val viewport = NativeVerticalViewport(host, state, density.nativeParityDensity(), owner.frames, owner.scope, FineLeafNested)
            geometry.viewport = viewport
            val configuration = ViewConfiguration.get(host)
            val binding = NativeVerticalPhaseBinding(viewport, { configuration.scaledTouchSlop.toFloat() },
                { configuration.scaledMaximumFlingVelocity.toFloat() }, { false })
            vertical = binding
            geometry.vertical = binding
            val fine = ReverbSpringFineSeekView(host, SpringFineSeekEnvironment(palette, density.nativeParityDensity(),
                configuration.scaledTouchSlop.toFloat(), owner.frames, owner.scope, nativeRoot, binding::interested))
            native = fine
            geometry.fine = fine
            fine.bind(trace.binding())
            trace.updatePlaying = { fine.bind(trace.binding()) }
            viewport.setContent(FineLeafColumn(host, fine, density))
            nativeRoot.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        }
        root.layoutDirection = View.LAYOUT_DIRECTION_LTR
        if (Build.VERSION.SDK_INT >= 29) root.isForceDarkAllowed = false
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    }

    fun checkCurrent() {
        check(!closed && root.parent === host.fixtureRoot && root.isAttachedToWindow && root.isShown && root.hasWindowFocus())
        check(root.display?.displayId == 0 && root.windowToken === host.fixtureRoot.windowToken)
        check(root.width == widthPx && root.height == heightPx && !root.isLayoutRequested)
    }
    fun leafBounds(): Rect = if (reference) Rect(checkNotNull(sourceBounds)) else {
        val fine = checkNotNull(native)
        val point = IntArray(2).also(fine::getLocationInWindow)
        val origin = IntArray(2).also(root::getLocationInWindow)
        Rect(point[0] - origin[0], point[1] - origin[1], point[0] - origin[0] + fine.width, point[1] - origin[1] + fine.height)
    }
    fun scroll() = sourceScroll?.value ?: checkNotNull(nativeScroll).value
    fun criticalCallbackNames(): List<String> =
        (0 until trace.critical.length()).map { trace.critical.getJSONObject(it).getString("name") }
    fun snapshot(phase: String): JSONObject {
        checkCurrent()
        val bounds = leafBounds()
        return partialSnapshot().put("phase", phase)
            .put("scroll", scroll()).put("leaf_bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
    }
    /** Already collected fixture data only; safe even after a guard stops further interaction. */
    fun partialSnapshot(): JSONObject = JSONObject().put("renderer", label).put("phase", "incomplete")
            .put("is_playing", trace.playing).put("in_memory_position_seconds", trace.position)
            .put("callbacks", JSONArray(trace.callbacks.toString())).put("critical_callbacks", JSONArray(trace.critical.toString()))
            .put("inputs", JSONArray(inputs.toString())).put("shuttle_count", trace.shuttleCount)
            .put("callback_numbers_finite", trace.finite).put("commit_resolution_exact", trace.exactResolution)

    override fun close() {
        if (closed) return
        closed = true
        var primary: Throwable? = null
        fun release(block: () -> Unit) { try { block() } catch (error: Throwable) {
            if (primary == null) primary = error else if (primary !== error) primary!!.addSuppressed(error)
        } }
        trace.updatePlaying = {}
        release { if (::root.isInitialized) (root as? NativePagerTouchRoot)?.cancelPointerInput() }
        release { if (::root.isInitialized) host.fixtureRoot.removeView(root) }
        release { compose?.disposeComposition() }
        release { native?.release() }
        release { vertical?.close() }
        release { lifecycle?.let(host.lifecycle::removeObserver) }
        release { runtime?.close() }
        primary?.let { throw it }
    }
}

private class FineLeafColumn(context: Context, private val fine: View, density: Density) : ViewGroup(context) {
    private val topPx = with(density) { 24.dp.roundToPx() }
    private val fineHeight = with(density) { 104.dp.roundToPx() }
    private val tailPx = with(density) { 480.dp.roundToPx() }
    init { clipChildren = false; clipToPadding = false; addView(fine) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fine.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(fineHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), topPx + fineHeight + tailPx)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { fine.layout(0, topPx, width, topPx + fineHeight) }
}

private class FineLeafPointerGeometry(private val density: Density) {
    lateinit var root: NativePagerTouchRoot
    lateinit var viewport: NativeVerticalViewport
    lateinit var vertical: NativeVerticalPhaseBinding
    lateinit var fine: ReverbSpringFineSeekView
    val coordinates = NativeWindowPointerCoordinates { root }
    private val layouts = IdentityHashMap<View, NativeViewPointerHitLayout>()
    val layout: NativeViewPointerHitLayout = Layout { root }
    private fun layoutFor(view: View) = layouts.getOrPut(view) { Layout { view } }
    private inner class Layout(private val value: () -> View) : NativeViewPointerHitLayout {
        private val view get() = value()
        override val placed get() = view.isAttachedToWindow && view.visibility == View.VISIBLE
        override val size get() = Size(view.width.toFloat(), view.height.toFloat())
        override val minimumTouchTarget get() = Size(48f * density.density, 48f * density.density)
        override val nodes: List<NativeViewPhasePointerNode> get() = when (view) {
            viewport -> vertical.nodes
            fine -> listOf(fine.fineSeekNode)
            fine.puckClickView -> listOf(fine.puckClickNode)
            else -> emptyList()
        }
        override val childrenInHitOrder: List<NativeViewPointerHitLayout> get() {
            val group = view as? ViewGroup ?: return emptyList()
            return (group.childCount - 1 downTo 0).map { layoutFor(group.getChildAt(it)) }
        }
        override fun screenToLocal(screen: Offset) = coordinates.coordinates(view).local(screen)
        override fun withinLayerBounds(local: Offset): Boolean {
            if (view !== viewport) return true
            val margin = with(density) { 30.dp.roundToPx() }
            return local.y >= 0 && local.y < view.height && local.x >= -margin && local.x < view.width + margin
        }
    }
}

private object FineLeafNested : NativeVerticalNestedHost {
    override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
    override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
    override suspend fun preFling(availableY: Float) = 0f
    override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
}
