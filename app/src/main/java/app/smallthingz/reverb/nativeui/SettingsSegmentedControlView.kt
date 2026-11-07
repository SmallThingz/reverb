package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.smallthingz.reverb.NativeAndroidInteropHost
import app.smallthingz.reverb.NativeAndroidInteropRequests
import smallthingz.ui.components.NativeChoice
import smallthingz.ui.components.NativeChoiceStyle
import smallthingz.ui.components.NativeInputGate
import smallthingz.ui.components.NativeSegmentedChoice
import smallthingz.ui.components.NativeTextRole
import smallthingz.ui.components.NativeTextStyle
import smallthingz.ui.controls.ControlPalette
import kotlin.math.roundToInt

internal data class SettingsSegment<T>(val id: String, val value: T, val label: String, val icon: ReverbIcon? = null)

/** Reverb owns typed actions, palette and lifetime; the library owns the actual radio controls. */
internal class SettingsSegmentedControlView<T>(
    context: Context,
    private val items: List<SettingsSegment<T>>,
    selected: T,
    private var environment: ReverbViewEnvironment,
    private val inputRoot: () -> View?,
    private var onSelected: ((T) -> Unit)?,
) : ViewGroup(context), NativeInputGate, AutoCloseable {
    private var closed = false
    private val byId = items.associateBy { it.id }
    private val surface = ReverbGroupLayout(context, 30f)
    private val row = SegmentHost(context)
    private val choices = NativeSegmentedChoice(context, palette(environment),
        NativeChoiceStyle(radiusDp = 22f, minHeightDp = 34f, horizontalPaddingDp = 0f,
            verticalPaddingDp = 0f, outlined = false, selectedOutlined = false, iconSizeDp = 16f, iconGapDp = 6f,
            normalTextRole = NativeTextRole.CONTENT, selectedTextRole = NativeTextRole.ON_ACCENT,
            normalIconRole = NativeTextRole.CONTENT, selectedIconRole = NativeTextRole.ON_ACCENT,
            textStyle = NativeTextStyle(sizeSp = 14f, lineHeightSp = 20f, weight = 400),
            animateSelection = true), gapDp = 0f, activateOnFocusNavigation = false, singleRow = true)
    private var options = options(environment)

    override val acceptsNativeInput: Boolean
        get() {
            if (closed || !isEnabled || !isAttachedToWindow || !isShown || !hasWindowFocus() || inputRoot() == null) return false
            var ancestor: View? = parent as? View
            while (ancestor != null) {
                if (ancestor is NativeSettingsScreenContent) return ancestor.inputAllowed
                ancestor = ancestor.parent as? View
            }
            return false
        }

    init {
        require(items.isNotEmpty() && byId.size == items.size && items.all { it.id.isNotEmpty() })
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        row.addView(choices)
        surface.addRow(row, ReverbGroupLayout.LayoutParams(-1, -2))
        addView(surface)
        surface.setPalette(environment.palette.field, environment.palette.border)
        choices.onSelectionRequest = { id ->
            val item = byId[id]
            val action = onSelected
            if (!acceptsNativeInput || item == null || action == null) false
            else { action(item.value); true }
        }
        choices.bind(options, items.single { it.value == selected }.id)
    }

    fun bind(selected: T, next: ReverbViewEnvironment) {
        if (closed) return
        if (environment.textDensity.density != next.textDensity.density) options = options(next)
        environment = next
        surface.setPalette(next.palette.field, next.palette.border)
        choices.applyPalette(palette(next))
        choices.bind(options, items.single { it.value == selected }.id)
        requestLayout()
    }

    private fun options(next: ReverbViewEnvironment) = items.map { item -> NativeChoice(item.id, item.label,
        item.icon?.let { ReverbIconDrawable(it, next.textDensity.density, next.palette.muted) }) }

    override fun onMeasure(w: Int, h: Int) { surface.measure(w, h); setMeasuredDimension(surface.measuredWidth, surface.measuredHeight) }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { surface.layout(0, 0, width, height) }
    override fun close() { if (!closed) { closed = true; onSelected = null; choices.close() } }

    private inner class SegmentHost(context: Context) : ViewGroup(context), NativeAndroidInteropHost {
        override val nativeInteropRequests = NativeAndroidInteropRequests()
        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            nativeInteropRequests.request(disallowIntercept)
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
        override fun onMeasure(w: Int, h: Int) {
            val inset = dp(4)
            val width = MeasureSpec.getSize(w)
            choices.measure(exact((width - 2 * inset).coerceAtLeast(0)), exact(dp(34)))
            setMeasuredDimension(resolveSize(width, w), resolveSize(dp(42), h))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            choices.layout(dp(4), dp(4), dp(4) + choices.measuredWidth, dp(4) + choices.measuredHeight)
        }
    }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
    private fun palette(value: ReverbViewEnvironment): ControlPalette = value.palette.let {
        ControlPalette(it.field, it.field, it.ink, it.muted, it.primary, it.settingsOnSelection, it.settingsSelection, it.border)
    }
}
