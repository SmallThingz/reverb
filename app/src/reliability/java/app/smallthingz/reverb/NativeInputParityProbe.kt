package app.smallthingz.reverb

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import app.smallthingz.reverb.nativeui.NativeClickBinding
import app.smallthingz.reverb.nativeui.NativeClickEnvironment
import app.smallthingz.reverb.nativeui.NativeClickIndication
import app.smallthingz.reverb.nativeui.NativePressToken
import app.smallthingz.reverb.nativeui.NativeStateToken
import app.smallthingz.reverb.nativeui.ReverbClickController
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject

/**
 * Input reaches only the focused isolated fixture's own View.dispatch* methods.
 * No device-wide input injection, recorder, preferences, grants, system settings or app data.
 * This compares actual pinned foundation modifiers, not a copied pointer recognizer.
 */
internal fun verifyNativeInputParity(instrumentation: Instrumentation, report: StringBuilder) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-input-parity-" + System.currentTimeMillis())
    check(directory.mkdir())
    val cases = JSONArray()
    val result = JSONObject().put("cases", cases)
        .put("reference", "Actual pinned foundation 1.11.2 clickable/combinedClickable; explicit InteractionSource")
        .put("physical_input_injected", false)
        .put("limits", "Direct synthetic own-View dispatch; no ripple raster, indirect input, or complete parent gesture arbitration")
    var activity: NativeParityActivity? = null
    var failure: Throwable? = null
    val frames = InputFrameTrace()
    try {
        val host = launchNativeParityActivity(instrumentation)
        activity = host
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        nativeParityOnMain(instrumentation) { frames.start() }
        for (case in InputScenario.entries) {
            val traces = mutableListOf<InputTrace>()
            for (compose in listOf(true, false)) {
                requireNativeParityHost(instrumentation, host)
                lateinit var target: InputFixture
                nativeParityOnMain(instrumentation) {
                    val trace = InputTrace(frames)
                    target = if (compose) ComposeInputFixture(host, trace, case) else NativeInputFixture(host, trace)
                    val parent = InputDelayFrame(host, case == InputScenario.QUICK_DELAYED_TAP)
                    val density = host.resources.displayMetrics.density
                    val width = (160f * density + .5f).toInt()
                    val height = (64f * density + .5f).toInt()
                    check(width <= host.fixtureRoot.width && height <= host.fixtureRoot.height)
                    parent.addView(target.view, FrameLayout.LayoutParams(-1, -1))
                    host.fixtureRoot.removeAllViews()
                    host.fixtureRoot.addView(parent, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
                    parent.isFocusableInTouchMode = true
                    check(parent.requestFocus())
                    target.update("old", case == InputScenario.KEYBOARD_LONG_PRESS)
                }
                awaitNativeParityDraw(instrumentation, target.view)
                awaitInputFrames(instrumentation, 2)
                guardedInput(instrumentation, host, target) { target.trace.reset() }
                runInputScenario(instrumentation, host, target, case)
                awaitInputFrames(instrumentation, 3)
                guardedInput(instrumentation, host, target) {
                    target.trace.mark("terminal")
                    target.trace.finishComparison()
                    traces += target.trace
                    target.dispose()
                    host.fixtureRoot.removeAllViews()
                }
            }
            val expected = traces[0]
            val actual = traces[1]
            val semantic = expected.semanticSignature() == actual.semanticSignature()
            val ordering = expected.dispatchSignature() == actual.dispatchSignature()
            val frameOffsets = expected.frameSignature() == actual.frameSignature()
            val expectedErrors = expected.expectations(case)
            val actualErrors = actual.expectations(case)
            val entry = JSONObject().put("name", case.name)
                .put("compose", expected.json()).put("native", actual.json())
                .put("semantic_trace_equal", semantic).put("dispatch_order_equal", ordering)
                .put("observed_frame_offsets_equal", frameOffsets)
                .put("compose_expectation_errors", JSONArray(expectedErrors))
                .put("native_expectation_errors", JSONArray(actualErrors))
                .put("frame_timing_qualified", false)
                .put("passed", semantic && ordering && expectedErrors.isEmpty() && actualErrors.isEmpty())
            if (!frameOffsets) entry.put("timing_observation",
                "Frame offsets differ; raw event/frame times retained. Sequential runs do not isolate scheduling cause.")
            cases.put(entry)
        }
        val mismatches = (0 until cases.length()).count { !cases.getJSONObject(it).getBoolean("passed") }
        check(mismatches == 0) { "$mismatches native input action/interaction ordering case(s) differ; retain result.json" }
        report.append("PASS native_input_parity action/interaction ordering; raw frame timing retained, not qualified\n")
    } catch (error: Throwable) {
        failure = error
        result.put("terminal_error", android.util.Log.getStackTraceString(error))
        throw error
    } finally {
        var cleanupError: Throwable? = null
        try { nativeParityOnMain(instrumentation) { frames.stop(); activity?.finish() } }
        catch (error: Throwable) { cleanupError = error; failure?.addSuppressed(error) }
        result.put("passed", failure == null && cleanupError == null && cases.length() == InputScenario.entries.size)
        result.put("motion_parity_qualified", false)
        cleanupError?.let { result.put("cleanup_error", android.util.Log.getStackTraceString(it)) }
        report.append("PARITY_EVIDENCE ").append(directory.name).append("/result.json\n")
        try { File(directory, "result.json").writeText(result.toString(2)) }
        catch (error: Throwable) {
            val primary = failure ?: cleanupError
            if (primary == null) throw error else primary.addSuppressed(error)
        }
        if (failure == null) cleanupError?.let { throw it }
    }
}

private enum class InputScenario {
    SAME_OWNER_CALLBACK, FOCUSED_LONG_CAPABILITY, QUICK_DELAYED_TAP, MULTI_POINTER_CANCEL, KEYBOARD_LONG_PRESS,
}

private interface InputFixture {
    val view: View
    val trace: InputTrace
    fun update(action: String, longClick: Boolean)
    fun requestInputFocus()
    fun dispose()
}

@OptIn(ExperimentalFoundationApi::class)
private class ComposeInputFixture(context: Context, override val trace: InputTrace, case: InputScenario) : InputFixture {
    private data class Binding(val action: String, val longClick: Boolean)
    private var binding by mutableStateOf(Binding("old", false))
    private val interactionSource = MutableInteractionSource()
    private val focusRequester = FocusRequester()
    override val view = ComposeView(context).apply {
        setContent {
            val accepted = binding // Capture the accepted binding: update must reach the actual modifier node.
            LaunchedEffect(interactionSource) {
                interactionSource.interactions.collect { event ->
                    when (event) {
                        is PressInteraction.Press -> trace.interaction("press", event, event.pressPosition.x, event.pressPosition.y)
                        is PressInteraction.Release -> trace.interaction("release", event.press)
                        is PressInteraction.Cancel -> trace.interaction("cancel", event.press)
                        is FocusInteraction.Focus -> trace.interaction("focus", event)
                        is FocusInteraction.Unfocus -> trace.interaction("unfocus", event.focus)
                        is HoverInteraction.Enter -> trace.interaction("hover", event)
                        is HoverInteraction.Exit -> trace.interaction("unhover", event.enter)
                    }
                }
            }
            val base = Modifier.fillMaxSize().focusRequester(focusRequester)
            val clickable = if (case == InputScenario.QUICK_DELAYED_TAP) {
                base.clickable(interactionSource = interactionSource, indication = null) {
                    trace.action("click:" + accepted.action)
                }
            } else {
                base.combinedClickable(interactionSource = interactionSource, indication = null,
                    onLongClick = if (accepted.longClick) ({ trace.action("long:" + accepted.action) }) else null,
                    onClick = { trace.action("click:" + accepted.action) })
            }
            Box(clickable)
        }
    }
    override fun update(action: String, longClick: Boolean) { binding = Binding(action, longClick) }
    override fun requestInputFocus() { focusRequester.requestFocus() }
    override fun dispose() { view.disposeComposition() }
}

private class NativeInputFixture(context: Context, override val trace: InputTrace) : InputFixture {
    override val view = NativeInputView(context, trace)
    override fun update(action: String, longClick: Boolean) {
        view.controller.bind(NativeClickBinding("fixture-owner", true,
            onClick = { trace.action("click:" + action) },
            onLongClick = if (longClick) ({ trace.action("long:" + action) }) else null))
    }
    override fun requestInputFocus() { check(view.requestFocus()) }
    override fun dispose() { view.controller.bind(null) }
}

private class NativeInputView(context: Context, trace: InputTrace) : View(context) {
    val controller = ReverbClickController(this, object : NativeClickEnvironment {
        override val minimumTouchWidthPx = 48f * resources.displayMetrics.density
        override val minimumTouchHeightPx = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent) =
            event.x >= 0f && event.y >= 0f && event.x <= width && event.y <= height
        override fun interestedParent(event: MotionEvent) = false
        override fun longPressOwnershipChanged(owns: Boolean) { parent?.requestDisallowInterceptTouchEvent(owns) }
    }, object : NativeClickIndication {
        override fun press(token: NativePressToken) = trace.interaction("press", token, token.x, token.y)
        override fun release(token: NativePressToken) = trace.interaction("release", token)
        override fun cancel(token: NativePressToken) = trace.interaction("cancel", token)
        override fun stateEnter(token: NativeStateToken) = trace.interaction(
            if (token.kind == NativeStateToken.Kind.FOCUS) "focus" else "hover", token)
        override fun stateExit(token: NativeStateToken) = trace.interaction(
            if (token.kind == NativeStateToken.Kind.FOCUS) "unfocus" else "unhover", token)
    })
    init { isFocusable = true; isFocusableInTouchMode = true; isClickable = true }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); controller.attached() }
    override fun onDetachedFromWindow() { controller.detached(); super.onDetachedFromWindow() }
    override fun onTouchEvent(event: MotionEvent) = controller.touch(event)
    override fun onKeyDown(keyCode: Int, event: KeyEvent) = controller.key(event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent) = controller.key(event)
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, old: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, old)
        controller.focusChanged(gainFocus)
    }
}

private class InputDelayFrame(context: Context, private val delayPress: Boolean) : FrameLayout(context) {
    override fun shouldDelayChildPressedState() = delayPress
}

private fun runInputScenario(instrumentation: Instrumentation, host: NativeParityActivity,
    target: InputFixture, case: InputScenario) {
    val x = target.view.width / 2f
    val y = target.view.height / 2f
    var downTime = 0L
    fun touch(label: String, action: Int, points: List<Pair<Float, Float>> = listOf(x to y)) {
        guardedInput(instrumentation, host, target) {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val properties = Array(points.size) { index ->
                MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER }
            }
            val coordinates = Array(points.size) { index ->
                MotionEvent.PointerCoords().apply {
                    this.x = points[index].first; this.y = points[index].second; pressure = 1f; size = 1f
                }
            }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, points.size,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                target.trace.mark(label + ":begin")
                target.view.dispatchTouchEvent(event)
                target.trace.mark(label + ":end")
            } finally { event.recycle() }
        }
    }
    fun update(label: String, longClick: Boolean) {
        guardedInput(instrumentation, host, target) {
            target.trace.mark("update:" + label)
            target.update(label, longClick)
        }
        awaitInputFrames(instrumentation, 2)
    }
    when (case) {
        InputScenario.SAME_OWNER_CALLBACK -> {
            touch("down", MotionEvent.ACTION_DOWN)
            awaitInputFrames(instrumentation, 2)
            update("new", false)
            touch("up", MotionEvent.ACTION_UP)
        }
        InputScenario.FOCUSED_LONG_CAPABILITY -> {
            guardedInput(instrumentation, host, target) {
                target.trace.mark("request-focus")
                target.requestInputFocus()
            }
            awaitInputFrames(instrumentation, 2)
            update("with-long", true)
            update("without-long", false)
        }
        InputScenario.QUICK_DELAYED_TAP -> {
            // Both events must execute in one main-thread turn, before either platform timeout.
            guardedInput(instrumentation, host, target) {
                val time = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(time, time, MotionEvent.ACTION_UP, x, y, 0)
                down.source = InputDevice.SOURCE_TOUCHSCREEN
                up.source = InputDevice.SOURCE_TOUCHSCREEN
                try {
                    target.trace.mark("quick-down:begin")
                    target.view.dispatchTouchEvent(down)
                    target.trace.mark("quick-down:end")
                    target.trace.mark("quick-up:begin")
                    target.view.dispatchTouchEvent(up)
                    target.trace.mark("quick-up:end")
                } finally { down.recycle(); up.recycle() }
            }
            awaitInputDelay(instrumentation, ViewConfiguration.getTapTimeout().toLong() + 30)
        }
        InputScenario.MULTI_POINTER_CANCEL -> {
            touch("down", MotionEvent.ACTION_DOWN)
            awaitInputDelay(instrumentation, ViewConfiguration.getTapTimeout().toLong() + 30)
            touch("second-down", MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(x to y, (x + 4f) to y))
            touch("outside-move", MotionEvent.ACTION_MOVE, listOf(x to y, (target.view.width + 2f) to y))
            touch("second-up", MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(x to y, (target.view.width + 2f) to y))
            touch("up", MotionEvent.ACTION_UP)
        }
        InputScenario.KEYBOARD_LONG_PRESS -> {
            guardedInput(instrumentation, host, target) {
                target.trace.mark("request-focus")
                target.requestInputFocus()
            }
            awaitInputFrames(instrumentation, 2)
            val keyDownTime = SystemClock.uptimeMillis()
            guardedInput(instrumentation, host, target) {
                target.trace.mark("key-down:begin")
                target.view.dispatchKeyEvent(KeyEvent(keyDownTime, keyDownTime, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_ENTER, 0))
                target.trace.mark("key-down:end")
            }
            awaitInputDelay(instrumentation, ViewConfiguration.getLongPressTimeout().toLong() + 60)
            guardedInput(instrumentation, host, target) {
                target.trace.mark("key-up:begin")
                target.view.dispatchKeyEvent(KeyEvent(keyDownTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_ENTER, 0))
                target.trace.mark("key-up:end")
            }
        }
    }
}

private fun guardedInput(instrumentation: Instrumentation, host: NativeParityActivity,
    target: InputFixture, action: () -> Unit) {
    requireNativeParityHost(instrumentation, host)
    nativeParityOnMain(instrumentation) {
        check(host.hasWindowFocus() && target.view.isAttachedToWindow && target.view.isShown)
        check(target.view.rootView === host.fixtureRoot.rootView)
        action()
    }
}

private class InputTrace(private val frames: InputFrameTrace) {
    private data class Entry(val kind: String, val token: Int, val x: Float?, val y: Float?,
        val nanos: Long, val frame: Long, val frameNanos: Long)
    private val ids = IdentityHashMap<Any, Int>()
    private val entries = mutableListOf<Entry>()
    private var epoch = 0L
    private var initialFrame = 0L
    private var nextId = 0
    private var comparisonEnd: Int? = null
    fun reset() {
        ids.clear(); entries.clear(); nextId = 0; comparisonEnd = null
        epoch = System.nanoTime(); initialFrame = frames.number
    }
    fun finishComparison() { comparisonEnd = entries.size }
    fun mark(value: String) = record("step:" + value)
    fun action(value: String) = record(value)
    fun interaction(kind: String, token: Any, x: Float? = null, y: Float? = null) {
        record(kind, ids.getOrPut(token) { ++nextId }, x, y)
    }
    private fun record(kind: String, token: Int = 0, x: Float? = null, y: Float? = null) {
        entries += Entry(kind, token, x, y, System.nanoTime() - epoch,
            frames.number - initialFrame, frames.timestamp)
    }
    private fun Entry.signature() = listOf(kind, token, x, y).joinToString("|")
    private fun compared() = entries.take(comparisonEnd ?: entries.size)
    fun semanticSignature() = compared().filterNot { it.kind.startsWith("step:") }.map { it.signature() }
    fun dispatchSignature() = compared().map { it.signature() }
    fun frameSignature() = compared().map { it.kind to it.frame }
    fun expectations(case: InputScenario): List<String> {
        val events = compared().map { it.kind }
        val actions = events.filter { it.startsWith("click:") || it.startsWith("long:") }
        val expectedActions = when (case) {
            InputScenario.SAME_OWNER_CALLBACK -> listOf("click:new")
            InputScenario.QUICK_DELAYED_TAP -> listOf("click:old")
            InputScenario.KEYBOARD_LONG_PRESS -> listOf("long:old")
            else -> emptyList()
        }
        val errors = mutableListOf<String>()
        if (actions != expectedActions) errors += "actions=$actions expected=$expectedActions"
        if (case == InputScenario.FOCUSED_LONG_CAPABILITY) {
            if (events.count { it == "focus" } != 1 || events.any { it == "unfocus" }) {
                errors += "Focus must enter once and remain active through both capability changes"
            }
        } else {
            val terminal = if (case == InputScenario.MULTI_POINTER_CANCEL) "cancel" else "release"
            if (events.count { it == "press" } != 1 || events.count { it == terminal } != 1) {
                errors += "Expected one press and one $terminal"
            }
        }
        return errors
    }
    fun json(): JSONArray = JSONArray().also { array ->
        entries.forEachIndexed { index, entry ->
            array.put(JSONObject().put("event", entry.kind).put("token", entry.token)
                .put("within_comparison", index < (comparisonEnd ?: entries.size))
                .put("x", entry.x ?: JSONObject.NULL).put("y", entry.y ?: JSONObject.NULL)
                .put("relative_nanoseconds", entry.nanos).put("relative_frame", entry.frame)
                .put("actual_frame_nanoseconds", entry.frameNanos))
        }
    }
}

private class InputFrameTrace : Choreographer.FrameCallback {
    var number = 0L
        private set
    var timestamp = 0L
        private set
    private var active = false
    fun start() { active = true; Choreographer.getInstance().postFrameCallback(this) }
    fun stop() { active = false; Choreographer.getInstance().removeFrameCallback(this) }
    override fun doFrame(frameTimeNanos: Long) {
        if (!active) return
        number++
        timestamp = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }
}

private fun awaitInputFrames(instrumentation: Instrumentation, count: Int) {
    val complete = CountDownLatch(1)
    lateinit var callback: Choreographer.FrameCallback
    nativeParityOnMain(instrumentation) {
        var remaining = count
        callback = Choreographer.FrameCallback {
            if (--remaining == 0) complete.countDown() else Choreographer.getInstance().postFrameCallback(callback)
        }
        Choreographer.getInstance().postFrameCallback(callback)
    }
    try { check(complete.await(5, TimeUnit.SECONDS)) { "Input frame barrier timed out" } }
    finally { nativeParityOnMain(instrumentation) { Choreographer.getInstance().removeFrameCallback(callback) } }
}

private fun awaitInputDelay(instrumentation: Instrumentation, milliseconds: Long) {
    val complete = CountDownLatch(1)
    val handler = Handler(Looper.getMainLooper())
    val callback = Runnable { complete.countDown() }
    nativeParityOnMain(instrumentation) { handler.postDelayed(callback, milliseconds) }
    try { check(complete.await(milliseconds + 2000, TimeUnit.MILLISECONDS)) { "Input timeout barrier failed" } }
    finally { handler.removeCallbacks(callback) }
}
