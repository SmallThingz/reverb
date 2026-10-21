package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import app.smallthingz.reverb.R
import app.smallthingz.reverb.ReverbService.BufferSlot
import app.smallthingz.reverb.isBufferActivelyRecording
import kotlinx.coroutines.CoroutineScope
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Released BufferSelector: two unchanged Home segments, 6dp outer padding and 6dp spacing.
 * Unlike the fixed Home cluster, this standalone Row wraps its measured font-scaled child height.
 */
internal class ReverbBufferSelectorView(
    context: Context,
    private var environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    animationScope: CoroutineScope,
) : ViewGroup(context) {
    private val oneShot = HomeBufferSegment(context, environment, inputRoot, animationScope)
    private val looping = HomeBufferSegment(context, environment, inputRoot, animationScope)
    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = false
        addView(oneShot)
        addView(looping)
    }
    fun bind(
        selected: BufferSlot,
        active: BufferSlot?,
        listening: Boolean,
        oneShotEnabled: Boolean,
        oneShotFull: Boolean,
        loopingEnabled: Boolean,
        interactionEnabled: Boolean,
        onSelect: (BufferSlot) -> Unit,
    ) {
        oneShot.bind(context.getString(R.string.buffer_one_shot),
            if (oneShotFull) ReverbIcon.Check else ReverbIcon.OneShot,
            selected == BufferSlot.ONE_SHOT,
            isBufferActivelyRecording(BufferSlot.ONE_SHOT, listening, active),
            oneShotEnabled && !oneShotFull, oneShotFull, interactionEnabled) {
            onSelect(BufferSlot.ONE_SHOT)
        }
        looping.bind(context.getString(R.string.buffer_loop), ReverbIcon.Looping,
            selected == BufferSlot.LOOPING,
            isBufferActivelyRecording(BufferSlot.LOOPING, listening, active),
            loopingEnabled, false, interactionEnabled) { onSelect(BufferSlot.LOOPING) }
    }
    fun setEnvironment(next: ReverbViewEnvironment) {
        environment = next
        oneShot.setEnvironment(next)
        looping.setEnvironment(next)
        requestLayout()
    }
    override fun onMeasure(w: Int, h: Int) {
        val padding = dp(6)
        val width = resolveSize(dp(112) * 2 + padding * 3, w)
        val maxHeight = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED)
            0x3fffffff else (MeasureSpec.getSize(h) - padding * 2).coerceAtLeast(0)
        var remaining = (width - padding * 2).coerceAtLeast(0)
        for (child in listOf(oneShot, looping)) {
            child.measure(exact(min(dp(112), remaining)), atMost(maxHeight))
            remaining = (remaining - child.measuredWidth -
                min(padding, (remaining - child.measuredWidth).coerceAtLeast(0))).coerceAtLeast(0)
        }
        setMeasuredDimension(width, resolveSize(max(oneShot.measuredHeight,
            looping.measuredHeight) + padding * 2, h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val padding = dp(6)
        val innerHeight = (height - padding * 2).coerceAtLeast(0)
        var start = padding
        for (child in listOf(oneShot, looping)) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
            val y = padding + ((innerHeight - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            start += child.measuredWidth + padding
        }
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(1, 2, false,
            AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_NONE)
    }
    override fun onRtlPropertiesChanged(direction: Int) {
        super.onRtlPropertiesChanged(direction)
        requestLayout()
    }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}
