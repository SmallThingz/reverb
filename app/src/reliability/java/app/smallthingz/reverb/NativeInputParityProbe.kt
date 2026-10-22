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
import android.view.ViewGroup
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
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
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
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject

/**
 * Input reaches only the focused isolated fixture's own View.dispatch* methods.
 * Keyboard-mode entry uses only the host-coordinated, guarded physical-display-0 TAB handshake.
 * No recorder, preferences, grants, system settings or production app data.
 * This compares actual pinned foundation modifiers, not a copied pointer recognizer.
 */
internal fun verifyNativeInputParity(
    instrumentation: Instrumentation,
    report: StringBuilder,
    keyboardSessionNonce: String?,
) {
    val context = instrumentation.targetContext
    check(context.packageName == "app.smallthingz.reverb.nativeparity.reliability")
    check(context.applicationContext.javaClass == Application::class.java)
    val directory = File(context.filesDir, "native-input-parity-" + System.currentTimeMillis())
    check(directory.mkdir())
    val cases = JSONArray()
    val keyboardRequests = JSONArray()
    val focusPreconditions = JSONArray()
    val result = JSONObject().put("cases", cases)
        .put("keyboard_requests", keyboardRequests).put("focus_preconditions", focusPreconditions)
        .put("reference", "Actual pinned foundation 1.11.2 clickable/combinedClickable; explicit InteractionSource")
        .put("physical_input_injected", false)
        .put("physical_input_scope", "Only nonce-bound host keyboard TAB on explicitly physical display0; no app-side injection")
        .put("limits", "Direct synthetic own-View dispatch; no ripple raster, indirect input, or complete parent gesture arbitration")
    var activity: NativeParityActivity? = null
    var failure: Throwable? = null
    val frames = InputFrameTrace()
    try {
        val host = launchNativeParityActivity(instrumentation)
        activity = host
        awaitNativeParityDraw(instrumentation, host.fixtureRoot)
        nativeParityOnMain(instrumentation) { frames.start() }
        result.put("public_pointer_subset", verifyPublicPointerSubset(instrumentation, host, frames))
        for (case in InputScenario.entries) {
            val traces = mutableListOf<InputTrace>()
            for (compose in listOf(true, false)) {
                requireNativeParityHost(instrumentation, host)
                lateinit var target: InputFixture
                lateinit var parent: InputDelayFrame
                nativeParityOnMain(instrumentation) {
                    val trace = InputTrace(frames)
                    target = if (compose) ComposeInputFixture(host, trace, case) else NativeInputFixture(host, trace)
                    parent = InputDelayFrame(host, case == InputScenario.QUICK_DELAYED_TAP)
                    val density = host.resources.displayMetrics.density
                    val width = (160f * density + .5f).toInt().let {
                        if (case == InputScenario.KEYBOARD_AFTER_POINTER_ODD) it or 1 else it
                    }
                    val height = (64f * density + .5f).toInt()
                    check(width <= host.fixtureRoot.width && height <= host.fixtureRoot.height)
                    parent.addView(target.view, FrameLayout.LayoutParams(-1, -1))
                    host.fixtureRoot.removeAllViews()
                    host.fixtureRoot.addView(parent, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
                    parent.isFocusableInTouchMode = true
                    check(parent.requestFocus())
                    target.update("old", case == InputScenario.KEYBOARD_LONG_PRESS ||
                        case == InputScenario.KEYBOARD_AFTER_POINTER_ODD)
                }
                awaitNativeParityDraw(instrumentation, target.view)
                if (case.requiresKeyboardFocus) {
                    val renderer = if (compose) "compose" else "native"
                    focusPreconditions.put(target.trace.focusEvidence.put("name", case.name).put("renderer", renderer))
                    prepareInputKeyboardMode(instrumentation, host, target, case, directory,
                        keyboardSessionNonce, renderer) { request ->
                        keyboardRequests.put(request)
                        if (!result.optBoolean("physical_input_injected", false)) {
                            // A pending host command can have an unknown outcome if the lane stops.
                            result.put("physical_input_injected", JSONObject.NULL)
                        }
                        File(directory, "result.json").writeText(result.toString(2))
                    }
                    result.put("physical_input_injected", true)
                    // TAB may focus the fixture. Park focus on the original parent, then let
                    // any setup Focus/Unfocus notifications drain before resetting the trace.
                    guardedInput(instrumentation, host, target) { check(parent.requestFocus()) }
                }
                awaitInputFrames(instrumentation, 2)
                guardedInput(instrumentation, host, target) {
                    if (case.requiresKeyboardFocus) {
                        val mode = target.view.isInTouchMode
                        val parked = parent.isFocused
                        val focused = target.isInputFocused()
                        target.trace.focusEvidence.put("in_touch_mode_before_reset", mode)
                            .put("parent_focused_before_reset", parked)
                            .put("target_focused_before_reset", focused)
                        check(!mode && parked && !focused) { "Keyboard setup must park parent focus before comparison" }
                    }
                    target.trace.reset()
                }
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

/** Public MotionEvent roundtrip only; dispatch remains inside the already admitted QA window. */
private fun verifyPublicPointerSubset(
    instrumentation: Instrumentation,
    host: NativeParityActivity,
    frames: InputFrameTrace,
): JSONObject {
    val receipt = JSONObject()
    lateinit var target: NativeInputFixture
    nativeParityOnMain(instrumentation) {
        target = NativeInputFixture(host, InputTrace(frames))
        host.fixtureRoot.removeAllViews()
        host.fixtureRoot.addView(target.view, FrameLayout.LayoutParams(120, 120, Gravity.CENTER))
    }
    awaitNativeParityDraw(instrumentation, target.view)
    try {
        guardedInput(instrumentation, host, target) {
            val ids = intArrayOf(3, 11, 23)
            val properties = Array(3) { i -> MotionEvent.PointerProperties().apply {
                id = ids[i]
                toolType = if (i == 2) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER
            } }
            fun coords(sample: Int) = Array(3) { i -> MotionEvent.PointerCoords().apply {
                x = 10.25f + i + sample
                y = 20.5f + i * 2 + sample
                pressure = .25f + i * .125f
                size = .5f
                touchMajor = 7f + i
                toolMinor = 3f + sample
                orientation = .125f
                setAxisValue(MotionEvent.AXIS_TILT, .375f)
                setAxisValue(MotionEvent.AXIS_GENERIC_1, 7.25f + sample)
            } }
            fun verifyAdditionalSubset(event: MotionEvent, mask: Int, indices: IntArray, action: Int) {
                val copy = event.nativePointerSubset(mask)
                try {
                    check(copy !== event && copy.pointerCount == indices.size && copy.action == action)
                    check(copy.downTime == event.downTime && copy.eventTime == event.eventTime)
                    check(copy.historySize == event.historySize && copy.metaState == event.metaState)
                    check(copy.buttonState == event.buttonState && copy.flags == event.flags)
                    check(copy.deviceId == event.deviceId && copy.source == event.source)
                    check(copy.edgeFlags == event.edgeFlags)
                    check(copy.xPrecision == event.xPrecision && copy.yPrecision == event.yPrecision)
                    for ((output, input) in indices.withIndex()) {
                        check(copy.getPointerId(output) == event.getPointerId(input))
                        check(copy.getToolType(output) == event.getToolType(input))
                        for (axis in 0..63) {
                            check(copy.getAxisValue(axis, output) == event.getAxisValue(axis, input))
                            for (history in 0 until event.historySize) {
                                check(copy.getHistoricalEventTime(history) == event.getHistoricalEventTime(history))
                                check(copy.getHistoricalAxisValue(axis, output, history) ==
                                    event.getHistoricalAxisValue(axis, input, history))
                            }
                        }
                    }
                } finally { copy.recycle() }
            }
            fun verifyRejectedMask(event: MotionEvent, mask: Int) {
                var unexpected: MotionEvent? = null
                try {
                    unexpected = event.nativePointerSubset(mask)
                } catch (_: IllegalArgumentException) {
                    return
                } finally { unexpected?.recycle() }
                error("Pointer mask $mask was unexpectedly accepted")
            }
            val original = MotionEvent.obtain(100, 110, MotionEvent.ACTION_MOVE, 3,
                properties, coords(0), KeyEvent.META_SHIFT_ON, MotionEvent.BUTTON_STYLUS_PRIMARY,
                .25f, .5f, 7, MotionEvent.EDGE_TOP, InputDevice.SOURCE_STYLUS, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
            try {
                original.addBatch(120, coords(1), original.metaState)
                original.addBatch(130, coords(2), original.metaState)
                original.action = MotionEvent.ACTION_POINTER_UP or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                val subset = original.nativePointerSubset((1 shl 3) or (1 shl 23))
                try {
                    check(subset.pointerCount == 2 && subset.getPointerId(0) == 3 && subset.getPointerId(1) == 23)
                    check(subset.action == (MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)))
                    check(subset.downTime == original.downTime && subset.eventTime == original.eventTime)
                    check(subset.historySize == 2 && subset.metaState == original.metaState)
                    check(subset.buttonState == original.buttonState && subset.flags == original.flags)
                    check(subset.deviceId == original.deviceId && subset.source == original.source)
                    check(subset.edgeFlags == original.edgeFlags)
                    check(subset.xPrecision == original.xPrecision && subset.yPrecision == original.yPrecision)
                    for ((output, input) in intArrayOf(0, 2).withIndex()) {
                        check(subset.getToolType(output) == original.getToolType(input))
                        for (axis in 0..63) {
                            check(subset.getAxisValue(axis, output) == original.getAxisValue(axis, input))
                            for (history in 0 until 2) {
                                check(subset.getHistoricalEventTime(history) == original.getHistoricalEventTime(history))
                                check(subset.getHistoricalAxisValue(axis, output, history) ==
                                    original.getHistoricalAxisValue(axis, input, history))
                            }
                        }
                    }
                    receipt.put("ids_action_properties_axes_history_metadata", "passed")
                } finally { subset.recycle() }
                val fullMask = (1 shl 3) or (1 shl 11) or (1 shl 23)
                verifyAdditionalSubset(original, fullMask, intArrayOf(0, 1, 2), original.action)
                verifyAdditionalSubset(original, (1 shl 3) or (1 shl 11), intArrayOf(0, 1), MotionEvent.ACTION_MOVE)
                verifyAdditionalSubset(original, 1 shl 23, intArrayOf(2), MotionEvent.ACTION_UP)
                verifyRejectedMask(original, 0)
                verifyRejectedMask(original, 1 shl 31)
                verifyRejectedMask(original, fullMask or (1 shl 31))
                receipt.put("full_mask_copy_removed_action_pointer_empty_unknown_masks", "passed")
            } finally { original.recycle() }

            val signProperties = arrayOf(MotionEvent.PointerProperties().apply {
                id = 31
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }, properties[0])
            fun signCoords(sample: Int) = coords(sample).let { arrayOf(it[2], it[0]) }
            val signEvent = MotionEvent.obtain(200, 210, MotionEvent.ACTION_MOVE, 2,
                signProperties, signCoords(0), KeyEvent.META_SHIFT_ON, MotionEvent.BUTTON_STYLUS_PRIMARY,
                .25f, .5f, 7, MotionEvent.EDGE_TOP, InputDevice.SOURCE_STYLUS, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
            try {
                signEvent.addBatch(220, signCoords(1), signEvent.metaState)
                signEvent.addBatch(230, signCoords(2), signEvent.metaState)
                signEvent.action = MotionEvent.ACTION_POINTER_UP
                verifyAdditionalSubset(signEvent, (1 shl 31) or (1 shl 3), intArrayOf(0, 1), signEvent.action)
                verifyAdditionalSubset(signEvent, 1 shl 31, intArrayOf(0), MotionEvent.ACTION_UP)
                verifyAdditionalSubset(signEvent, 1 shl 3, intArrayOf(1), MotionEvent.ACTION_MOVE)
                verifyRejectedMask(signEvent, 0)
                verifyRejectedMask(signEvent, (1 shl 31) or (1 shl 2))
                receipt.put("id31_full_mask_subset_history_order", "passed")
            } finally { signEvent.recycle() }
            val singleEvent = MotionEvent.obtain(300, 310, MotionEvent.ACTION_DOWN, 1,
                arrayOf(signProperties[0]), arrayOf(signCoords(0)[0]), 0, 0, 1f, 1f, 7, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                verifyAdditionalSubset(singleEvent, 1 shl 31, intArrayOf(0), MotionEvent.ACTION_DOWN)
                receipt.put("single_id31_full_mask_copy", "passed")
            } finally { singleEvent.recycle() }

            if (android.os.Build.VERSION.SDK_INT >= 34) {
                // Public API34 creation can give ORIGINAL deep-press classification. The subset
                // intentionally cannot rely on a setter; the node must forward original metadata.
                var longClicks = 0
                target.view.controller.bind(NativeClickBinding("subset-classification", true,
                    onClick = {}, onLongClick = { longClicks++ }))
                val endpoint = ReverbClickPhaseEndpoint(target.view.controller)
                val node = NativeClickPhaseNode(target.view, endpoint) { false }
                val point = app.smallthingz.nativeui.geometry.Offset(12.25f, 24.5f)
                val transform = NativePointerCoordinates(android.graphics.Matrix(), android.graphics.Matrix(),
                    app.smallthingz.nativeui.geometry.Offset.Zero)
                fun deliver(action: Int, before: Boolean) {
                    val originalClassified = checkNotNull(MotionEvent.obtain(
                        100, 110, action, 3, properties, coords(0), 0, 0, 1f, 1f, 7, 0,
                        InputDevice.SOURCE_STYLUS, host.display!!.displayId, 0, MotionEvent.CLASSIFICATION_DEEP_PRESS))
                    try {
                        val change = NativeDragPointerChange(1, 110, point, point, true, before,
                            app.smallthingz.nativeui.input.pointer.PointerType.Stylus, point)
                        val event = NativeDragPointerEvent(listOf(change), app.smallthingz.nativeui.geometry.Offset.Zero)
                        node.bindMotion(originalClassified, transform, 1 shl 23)
                        node.dispatch(event, NativePointerPass.INITIAL)
                        node.dispatch(event, NativePointerPass.MAIN)
                        node.dispatch(event, NativePointerPass.FINAL)
                    } finally {
                        node.bindMotion(null, null, 0)
                        originalClassified.recycle()
                    }
                }
                try {
                    deliver(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), false)
                    check(longClicks == 0)
                    deliver(MotionEvent.ACTION_MOVE, true)
                    check(longClicks == 1) { "Original classification did not reach the native controller" }
                    receipt.put("original_classification_through_node_to_controller", "passed")
                } finally { node.cancel() }
            } else {
                receipt.put("original_classification_through_node_to_controller",
                    "unverified: public classified synthetic MotionEvent creation requires API34")
            }
        }
    } finally {
        nativeParityOnMain(instrumentation) {
            target.dispose()
            host.fixtureRoot.removeAllViews()
        }
    }
    return receipt
}

private enum class InputScenario {
    SAME_OWNER_CALLBACK, FOCUSED_LONG_CAPABILITY, QUICK_DELAYED_TAP, MULTI_POINTER_CANCEL, KEYBOARD_LONG_PRESS,
    KEYBOARD_AFTER_POINTER_ODD;

    val requiresKeyboardFocus: Boolean get() = this == FOCUSED_LONG_CAPABILITY ||
        this == KEYBOARD_LONG_PRESS || this == KEYBOARD_AFTER_POINTER_ODD
}

private interface InputFixture {
    val view: View
    val trace: InputTrace
    fun update(action: String, longClick: Boolean)
    fun requestInputFocus(): Boolean
    fun isInputFocused(): Boolean
    fun focusTargetSnapshot(): JSONObject
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
    override fun requestInputFocus(): Boolean = focusRequester.requestFocus()
    private fun focusNode() = inputFixtureDescendants(view).filterIsInstance<ViewRootForTest>().single()
        .semanticsOwner.getAllSemanticsNodes(true).single {
            it.config.getOrNull(SemanticsActions.OnClick) != null
        }
    override fun isInputFocused(): Boolean = focusNode().config.getOrNull(SemanticsProperties.Focused) == true
    override fun focusTargetSnapshot(): JSONObject {
        val node = focusNode()
        val bounds = node.boundsInWindow
        return JSONObject().put("target_identity", "semantics:${node.id}")
            .put("target_bounds", JSONArray(listOf(bounds.left.roundToInt(), bounds.top.roundToInt(),
                bounds.right.roundToInt(), bounds.bottom.roundToInt())))
            .put("enabled", view.isEnabled && node.config.getOrNull(SemanticsProperties.Disabled) == null)
            .put("target_focused", node.config.getOrNull(SemanticsProperties.Focused) == true)
    }
    override fun dispose() { view.disposeComposition() }
}

private class NativeInputFixture(context: Context, override val trace: InputTrace) : InputFixture {
    override val view = NativeInputView(context, trace)
    override fun update(action: String, longClick: Boolean) {
        view.controller.bind(NativeClickBinding("fixture-owner", true,
            onClick = { trace.action("click:" + action) },
            onLongClick = if (longClick) ({ trace.action("long:" + action) }) else null))
    }
    override fun requestInputFocus(): Boolean = view.requestFocus()
    override fun isInputFocused(): Boolean = view.isFocused
    override fun focusTargetSnapshot(): JSONObject {
        val xy = IntArray(2).also(view::getLocationInWindow)
        return JSONObject().put("target_identity", "view:${System.identityHashCode(view)}")
            .put("target_bounds", JSONArray(listOf(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)))
            .put("enabled", view.isEnabled).put("target_focused", view.isFocused)
    }
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

private fun inputFixtureDescendants(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(inputFixtureDescendants(view.getChildAt(index)))
}

private fun prepareInputKeyboardMode(
    instrumentation: Instrumentation,
    host: NativeParityActivity,
    target: InputFixture,
    case: InputScenario,
    directory: File,
    sessionNonce: String?,
    renderer: String,
    onRequest: (JSONObject) -> Unit,
) {
    check(case.requiresKeyboardFocus)
    awaitNativeParityKeyboardCommand(instrumentation, directory, sessionNonce, renderer,
        stage = "input-keyboard-mode", keyCodeName = "KEYCODE_TAB", guardedSnapshot = {
            var snapshot: JSONObject? = null
            guardedInput(instrumentation, host, target) {
                val decor = host.window.decorView
                val observed = target.focusTargetSnapshot()
                check(observed.getBoolean("enabled")) { "Input focus target is not enabled" }
                val bounds = observed.getJSONArray("target_bounds")
                check(bounds.getInt(0) < bounds.getInt(2) && bounds.getInt(1) < bounds.getInt(3))
                snapshot = JSONObject().put("display_id", checkNotNull(decor.display).displayId)
                    .put("window_title", host.window.attributes.title?.toString().orEmpty())
                    .put("decor_identity", System.identityHashCode(decor))
                    .put("target_bounds", bounds).put("target_identity", observed.getString("target_identity"))
                    .put("scene_label", "InputFocus:${case.name}")
                    .put("observed_target", JSONObject().put("enabled", true).put("focus_scenario", case.name)
                        .put("target_focused", observed.getBoolean("target_focused")))
                    .put("in_touch_mode", decor.isInTouchMode)
            }
            checkNotNull(snapshot)
        }, onRequest = { request ->
            target.trace.focusEvidence.put("keyboard_request_id", request.getString("request_id"))
            onRequest(request)
        }, validateObservedAfter = { observed ->
            val mode = observed.get("in_touch_mode")
            target.trace.focusEvidence.put("in_touch_mode_after_tab", mode)
            check(mode is Boolean && !mode) {
                "Host TAB completed but own input window remains in Touch mode; focus assertion is not bypassed"
            }
        })
}

private fun requestInputFocusForComparison(target: InputFixture) {
    val mode = target.view.isInTouchMode
    target.trace.focusEvidence.put("in_touch_mode_at_request", mode)
    check(!mode) { "Input comparison requires actual keyboard mode; no mode/focusability override" }
    val accepted = target.requestInputFocus()
    val focused = target.isInputFocused()
    target.trace.focusEvidence.put("request_focus_return", accepted).put("target_focused_after_request", focused)
    check(accepted && focused) { "Input target focus request must succeed and actually focus the target" }
}

private fun requireInputKeyboardTarget(target: InputFixture, phase: String) {
    val mode = target.view.isInTouchMode
    val focused = target.isInputFocused()
    target.trace.focusEvidence.put(phase, JSONObject().put("in_touch_mode", mode).put("target_focused", focused))
    check(!mode && focused) { "Keyboard dispatch requires the admitted input target to remain focused" }
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
                requestInputFocusForComparison(target)
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
        InputScenario.KEYBOARD_LONG_PRESS, InputScenario.KEYBOARD_AFTER_POINTER_ODD -> {
            if (case == InputScenario.KEYBOARD_AFTER_POINTER_ODD) {
                // A real pointer pass primes AbstractClickable's cached INTEGER bounds center.
                // Keep both events in one UI turn so no long-press timeout can win the seed tap.
                guardedInput(instrumentation, host, target) {
                    check(target.view.width % 2 == 1)
                    val time = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
                    val up = MotionEvent.obtain(time, time, MotionEvent.ACTION_UP, x, y, 0)
                    down.source = InputDevice.SOURCE_TOUCHSCREEN
                    up.source = InputDevice.SOURCE_TOUCHSCREEN
                    try {
                        target.trace.mark("seed-down:begin")
                        target.view.dispatchTouchEvent(down)
                        target.trace.mark("seed-down:end")
                        target.trace.mark("seed-up:begin")
                        target.view.dispatchTouchEvent(up)
                        target.trace.mark("seed-up:end")
                    } finally { down.recycle(); up.recycle() }
                }
                awaitInputFrames(instrumentation, 2)
            }
            guardedInput(instrumentation, host, target) {
                target.trace.mark("request-focus")
                requestInputFocusForComparison(target)
            }
            awaitInputFrames(instrumentation, 2)
            val keyDownTime = SystemClock.uptimeMillis()
            guardedInput(instrumentation, host, target) {
                requireInputKeyboardTarget(target, "before_key_down")
                target.trace.mark("key-down:begin")
                target.view.dispatchKeyEvent(KeyEvent(keyDownTime, keyDownTime, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_ENTER, 0))
                target.trace.mark("key-down:end")
            }
            awaitInputDelay(instrumentation, ViewConfiguration.getLongPressTimeout().toLong() + 60)
            guardedInput(instrumentation, host, target) {
                requireInputKeyboardTarget(target, "before_key_up")
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
    val focusEvidence = JSONObject()
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
            InputScenario.KEYBOARD_AFTER_POINTER_ODD -> listOf("click:old", "long:old")
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
            val expectedPresses = if (case == InputScenario.KEYBOARD_AFTER_POINTER_ODD) 2 else 1
            if (events.count { it == "press" } != expectedPresses || events.count { it == terminal } != expectedPresses) {
                errors += "Expected $expectedPresses press/$terminal pairs"
            }
        }
        val presses = compared().filter { it.kind == "press" }
        if (case == InputScenario.KEYBOARD_LONG_PRESS && presses.singleOrNull()?.let {
                it.x != 0f || it.y != 0f
            } == true) errors += "Keyboard-first origin must remain the untouched node's (0,0)"
        if (case == InputScenario.KEYBOARD_AFTER_POINTER_ODD && presses.size == 2) {
            val pointer = presses[0]
            val keyboard = presses[1]
            if (pointer.x == null || pointer.y == null || pointer.x % 1f != .5f ||
                keyboard.x != pointer.x.toInt().toFloat() || keyboard.y != pointer.y.toInt().toFloat()) {
                errors += "Keyboard origin must use the last pointer's integer bounds center at odd width"
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
