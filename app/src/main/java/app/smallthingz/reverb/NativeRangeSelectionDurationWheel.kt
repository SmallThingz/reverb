package app.smallthingz.reverb

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import app.smallthingz.reverb.nativeui.ReverbViewEnvironment

/**
 * RangeSelectionDurationWheel's released AndroidView update/onRelease contract. The renderer is
 * the original RangeDurationWheelView. Model changes and interaction generation stay in the
 * shared RangeExportEditorState; this adapter does not create a second draft or duration policy.
 */
internal class NativeRangeSelectionDurationWheel(
    context: Context,
    private val state: RangeExportEditorState,
    private val lifecycle: Lifecycle,
) : AutoCloseable {
    val view = RangeDurationWheelView(context)
    private var alive = true
    private var currentEnabled = false
    private var exportLimit = 0.0

    init {
        view.setAccessibilityLabel(context.getString(R.string.range_export_duration))
        view.onInteractionStart = state::beginSelectionDurationEdit
        view.onDurationChanged = { state.setSelectionDuration(it.toFloat()) }
        view.onInteractionEnd = state::endSelectionDurationEdit
        view.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false else when (code) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT -> step(true)
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT -> step(false)
                else -> false
            }
        }
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.SeekBar::class.java.name
                val maximum = maximum().coerceAtLeast(0.0).toFloat()
                val minimum = minOf(.05f, maximum)
                info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                    minimum, maximum, state.selectionDurationExactSeconds.toFloat().coerceIn(minimum, maximum),
                )
                if (actionReady()) {
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                }
            }
            override fun performAccessibilityAction(host: View, action: Int, arguments: Bundle?): Boolean =
                when (action) {
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> commit(
                        (arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, Float.NaN)
                            ?: Float.NaN).toDouble(),
                    )
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> step(true)
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> step(false)
                    else -> super.performAccessibilityAction(host, action, arguments)
                }
        }
    }

    fun bind(maxExportDurationSeconds: Double, enabled: Boolean, inputVisible: Boolean,
        environment: ReverbViewEnvironment) {
        exportLimit = maxExportDurationSeconds
        currentEnabled = enabled && inputVisible
        view.isEnabled = enabled
        with(environment.palette) {
            view.setPalette(ink = onSurface, muted = onSurfaceVariant, border = outlineVariant, error = error)
        }
        view.setMaximumDurationSeconds(maximum())
        view.setDurationSeconds(state.selectionDurationExactSeconds)
    }

    private fun actionReady(): Boolean = alive && currentEnabled && view.isEnabled &&
        view.isAttachedToWindow && view.isShown && view.hasWindowFocus() &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && state.durationActionAvailable

    private fun maximum() = state.selectionDurationWheelLimitExactSeconds(exportLimit)

    private fun commit(seconds: Double): Boolean = runRangeDurationAction(
        requestedSeconds = seconds,
        maximumSeconds = maximum(),
        ready = actionReady(),
        begin = state::beginSelectionDurationEdit,
        commit = {
            state.setSelectionDurationFromAction(it, maximum())
            view.setDurationSeconds(state.selectionDurationExactSeconds)
        },
        end = state::endSelectionDurationEdit,
    )

    private fun step(forward: Boolean) =
        commit(state.selectionDurationExactSeconds + if (forward) 1.0 else -1.0)

    override fun close() {
        if (!alive) return
        alive = false
        view.setOnKeyListener(null)
        view.accessibilityDelegate = null
        view.clearFocus()
        view.onInteractionStart = null
        view.onDurationChanged = null
        view.onInteractionEnd = null
        state.endSelectionDurationEdit()
    }
}
