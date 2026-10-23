package app.smallthingz.reverb

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.Lifecycle
import app.smallthingz.reverb.nativeui.NativeSourceAccessibilityNode
import app.smallthingz.reverb.nativeui.PlainTextAccessibility
import app.smallthingz.reverb.nativeui.PlainTextAccessibilitySnapshot
import app.smallthingz.reverb.nativeui.PlainTextAccessibilityWindowScope

/** Existing source endpoint Box, not a stock SeekBar or another pointer recognizer. */
internal class NativeRangeEndpointView(context: Context) : View(context) {
    var sourceAccessibilityNode: (() -> NativeSourceAccessibilityNode)? = null
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (sourceAccessibilityNode?.invoke()?.present == false) null else super.createAccessibilityNodeInfo()
}

/**
 * RangeTimelineBar's original discrete endpoint action/key lease plus pinned Android delegate
 * progress/custom-action mapping. Pointer scrubs remain with NativeRangeTimelineInput.
 * One window text cursor is supplied; description traversal does not fabricate text layout boxes.
 */
internal class NativeRangeEndpointActions(
    private val view: NativeRangeEndpointView,
    private val sourceWindow: View,
    private val state: RangeExportEditorState,
    private val target: RangeEditTarget,
    private val lifecycle: Lifecycle,
    private val interactionEnabled: () -> Boolean,
    window: PlainTextAccessibilityWindowScope,
    private val sourceNode: () -> NativeSourceAccessibilityNode,
) : AutoCloseable {
    private var alive = true
    private val keys = RangeEndpointKeyOwner()
    private val label = view.context.getString(if (target == RangeEditTarget.START)
        R.string.range_start_accessibility else R.string.range_end_accessibility)
    private val earlier = view.context.getString(R.string.range_endpoint_earlier)
    private val later = view.context.getString(R.string.range_endpoint_later)
    private val earlierId = smallthingz.ui.R.id.accessibility_custom_action_0
    private val laterId = smallthingz.ui.R.id.accessibility_custom_action_1
    private val textAccessibility = PlainTextAccessibility(view, window,
        snapshot = { PlainTextAccessibilitySnapshot(label, { null }, { null }) },
        exposed = { alive && sourceNode().present }, descriptionOnly = true)
    private val focusListener = View.OnFocusChangeListener { _, focused ->
        if (focused) selectOrAdjust(null) else keys.clear()
    }
    private val keyListener = View.OnKeyListener { _, code, event -> key(code, event) }
    private val delegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            textAccessibility.populate(info)
            val limits = rangeEndpointLimits(state.durationSeconds, target)
            val enabled = actionEnabled()
            info.className = if (limits != null) "android.widget.SeekBar" else "android.view.View"
            info.text = null
            info.contentDescription = label
            AccessibilityNodeInfoCompat.wrap(info).stateDescription =
                formatRangeTimeInput(state.targetSeconds(target).toDouble())
            info.isSelected = state.lastTarget == target
            info.isEnabled = enabled
            info.isCheckable = false
            info.isClickable = true // OnClick semantics exists even when disabled; action does not.
            info.isLongClickable = false
            info.isFocusable = enabled
            info.isFocused = view.isFocused
            info.isScreenReaderFocusable = true
            info.setBoundsInParent(android.graphics.Rect())
            sourceNode().populate(info)
            if (enabled) {
                info.addAction(if (view.isFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_FOCUS
                    else AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(earlierId, earlier))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(laterId, later))
            }
            if (limits != null) {
                val current = state.targetSeconds(target).coerceIn(limits)
                info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, limits.start, limits.endInclusive, current)
                if (enabled && current < maxOf(limits.start, limits.endInclusive))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                if (enabled && current > minOf(limits.start, limits.endInclusive))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            } else info.rangeInfo = null
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            if (!alive || !view.isAttachedToWindow || !sourceNode().present) return false
            textAccessibility.performAction(action, args)?.let { return it }
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS ||
                action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
                return super.performAccessibilityAction(host, action, args)
            if (!actionEnabled()) return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    val result = selectOrAdjust(null)
                    sendClickedEvent()
                    result
                }
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> {
                    if (args?.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) != true) false
                    else selectOrAdjust(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE).toDouble())
                }
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                    val limits = rangeEndpointLimits(state.durationSeconds, target) ?: return false
                    // Pinned Android delegate slider increment is range/20, unlike the explicit
                    // earlier/later custom actions'1 second. Do not silently unify these controls.
                    val current = state.targetSeconds(target).coerceIn(limits)
                    val increment = (maxOf(limits.start, limits.endInclusive) -
                        minOf(limits.start, limits.endInclusive)) / 20
                    val requested = current + if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        -increment else increment
                    selectOrAdjust(requested.toDouble())
                }
                earlierId -> selectOrAdjust(state.targetSeconds(target).toDouble() - 1.0)
                laterId -> selectOrAdjust(state.targetSeconds(target).toDouble() + 1.0)
                AccessibilityNodeInfo.ACTION_FOCUS, AccessibilityNodeInfo.ACTION_CLEAR_FOCUS ->
                    super.performAccessibilityAction(host, action, args)
                else -> false
            }
        }
    }

    init {
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        view.isClickable = false // Source pointer modifier, not Android's default press/key machine.
        view.sourceAccessibilityNode = sourceNode
        view.onFocusChangeListener = focusListener
        view.setOnKeyListener(keyListener)
        view.accessibilityDelegate = delegate
        refresh()
    }

    fun refresh() {
        view.isFocusable = actionEnabled()
        view.isEnabled = actionEnabled()
    }

    private fun actionEnabled() = interactionEnabled() && state.durationActionAvailable &&
        rangeEndpointLimits(state.durationSeconds, target) != null
    private fun actionReady() = alive && interactionEnabled() && sourceWindow.isAttachedToWindow &&
        sourceWindow.isShown && sourceWindow.hasWindowFocus() &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && state.durationActionAvailable
    private fun gesture() = state.inputGesture(interactionAllowed = ::actionReady, onCancel = {})
    private fun selectOrAdjust(requested: Double?, owner: RangeInputGesture = gesture()) =
        state.editEndpointFromAction(target, requested, owner)

    private fun key(code: Int, event: KeyEvent): Boolean {
        val requested = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN -> state.targetSeconds(target).toDouble() - 1.0
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> state.targetSeconds(target).toDouble() + 1.0
            KeyEvent.KEYCODE_MOVE_HOME -> rangeEndpointLimits(state.durationSeconds, target)?.start?.toDouble() ?: return false
            KeyEvent.KEYCODE_MOVE_END -> rangeEndpointLimits(state.durationSeconds, target)?.endInclusive?.toDouble() ?: return false
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> null
            else -> return false
        }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                keys.keyDown(code, event.repeatCount, ::gesture)?.let { selectOrAdjust(requested, it) }
                true
            }
            KeyEvent.ACTION_UP -> { keys.keyUp(code); true }
            else -> false
        }
    }

    @Suppress("DEPRECATION")
    private fun sendClickedEvent() {
        val manager = view.context.getSystemService(AccessibilityManager::class.java)
        if (manager?.isEnabled != true || !view.isAttachedToWindow) return
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            isEnabled = true
            className = "android.view.View"
            packageName = view.context.packageName
            setSource(view)
        }
        view.parent?.requestSendAccessibilityEvent(view, event)
    }

    override fun close() {
        if (!alive) return
        alive = false
        keys.clear()
        if (view.onFocusChangeListener === focusListener) view.onFocusChangeListener = null
        view.setOnKeyListener(null)
        view.accessibilityDelegate = null
        view.sourceAccessibilityNode = null
    }
}
