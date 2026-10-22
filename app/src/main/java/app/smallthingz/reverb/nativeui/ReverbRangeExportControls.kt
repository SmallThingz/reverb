package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import app.smallthingz.nativeui.runtime.MonotonicFrameClock
import app.smallthingz.nativeui.MotionDurationScale
import app.smallthingz.nativeui.text.font.FontWeight
import androidx.core.view.ViewCompat
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.RangeExportEditorState
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import app.smallthingz.reverb.ReverbService
import app.smallthingz.reverb.R
import kotlinx.coroutines.CoroutineScope
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * RangeExportControls's ordinary View content. Parent owns the exact70dp height, alpha and
 * source18dp bottom spacer. This adds no exterior inset and does not initiate an export itself.
 *
 * The shared window hit tree adopts close.visual and exportAction as their same retained phase
 * nodes. Its source geometry/accessibility owner binds the real paragraph/description descendants.
 */
internal class ReverbRangeExportControls(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    scope: CoroutineScope,
) : ViewGroup(context) {
    private var environment = initialEnvironment
    private val surface = ReverbGroupLayout(context, 28f)
    private val row = ControlsRow(context)
    val close = ReverbStandardIconButtonView(context, initialEnvironment, inputRoot, actionSizeDp = 50f)
    private val pillSurface = ReverbGroupLayout(context, 16f)
    private val pill = BufferPill(context, initialEnvironment)
    private val exportSurface = ReverbGroupLayout(context, 20f)
    val exportAction = ExportContent(context, initialEnvironment, inputRoot, scope)
    private var onCancel: () -> Unit = {}
    private var onExport: () -> Unit = {}
    private var state: RangeExportEditorState? = null
    private data class VisualKey(val environment: ReverbViewEnvironment, val loading: Boolean,
        val enabled: Boolean, val buffer: ReverbService.BufferSlot)
    private var visualKey: VisualKey? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false; clipToPadding = false
        addView(surface)
        surface.addRow(row, ReverbGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        row.addView(close); row.addView(pillSurface); row.addView(exportSurface)
        pillSurface.addRow(pill, ReverbGroupLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        exportSurface.addRow(exportAction,
            ReverbGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(
        value: ReverbViewEnvironment,
        state: RangeExportEditorState,
        loading: Boolean,
        canExport: Boolean,
        selectedBuffer: ReverbService.BufferSlot,
        onCancel: () -> Unit,
        onExport: () -> Unit,
    ) {
        environment = value; this.state = state; this.onCancel = onCancel; this.onExport = onExport
        val palette = value.palette
        val enabled = !loading && state.snapshotReady && !state.selectionDurationEditing && canExport
        val key = VisualKey(value, loading, enabled, selectedBuffer)
        if (visualKey == key) return // Waveform frames do not remeasure unchanged controls.
        visualKey = key
        surface.setPalette(palette.field, palette.border)
        close.bind(value, ReverbIcon.Close, context.getString(R.string.close), true,
            palette.ink, palette.onSurface, { this.onCancel() })
        pillSurface.setPalette(reverbColorWithAlpha(palette.primaryContainer, .32f), 0)
        pill.bind(value, selectedBuffer)
        exportSurface.setPalette(if (enabled) palette.primary else palette.raised, 0)
        exportAction.bind(value, loading, enabled,
            if (enabled) palette.onPrimary else palette.muted) export@ {
            // Released onClick deliberately reads the immediate model again: wheel ownership can
            // begin after the enabled UI frame. Backend/config admission remains with its owner.
            val current = this.state ?: return@export
            val duration = current.selectionDurationExactSeconds
            if (!current.selectionDurationEditing && duration.isFinite() && duration > 0.0) this.onExport()
        }
        requestLayout()
    }

    val labelViews: List<ReverbParagraphView> get() = listOf(pill.label, exportAction.label)
    val exportShape: ReverbGroupLayout get() = exportSurface
    fun cancelInput() { close.cancelInput(); exportAction.cancelInput() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = resolveSize(dp(70), heightMeasureSpec)
        surface.measure(rangeControlExact(width), rangeControlExact(height))
        setMeasuredDimension(width, height)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        surface.layout(0, 0, width, height)
    }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()

    private inner class ControlsRow(context: Context) : ViewGroup(context) {
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; clipChildren = false; clipToPadding = false }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
            val innerW = max(0, w - dp(8) - dp(8)); val innerH = max(0, h - dp(8) - dp(8))
            // Row measures non-weighted children first in source order; the pill's weight fills
            // only what remains. Each source spacing contribution is independently constrained.
            close.measure(rangeControlExact(min(dp(50), innerW)), rangeControlExact(min(dp(50), innerH)))
            val firstGap = min(dp(4), max(0, innerW - close.measuredWidth))
            val remaining = max(0, innerW - close.measuredWidth - firstGap)
            exportSurface.measure(rangeControlAtMost(remaining), rangeControlExact(min(dp(50), innerH)))
            val secondGap = min(dp(4), max(0, remaining - exportSurface.measuredWidth))
            val pillSpace = max(0, remaining - exportSurface.measuredWidth - secondGap)
            pillSurface.measure(rangeControlAtMost(pillSpace), rangeControlAtMost(innerH))
            setMeasuredDimension(w, h)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val pad = dp(8); val gap = dp(4); val innerH = max(0, height - pad - pad)
            val innerW = max(0, width - pad - pad)
            val firstGap = min(gap, max(0, innerW - close.measuredWidth))
            val afterClose = max(0, innerW - close.measuredWidth - firstGap)
            val secondGap = min(gap, max(0, afterClose - exportSurface.measuredWidth))
            val pillSpace = max(0, afterClose - exportSurface.measuredWidth - secondGap)
            place(close, pad, pad + ((innerH - close.measuredHeight) * .5f).roundToInt())
            place(pillSurface, pad + close.measuredWidth + firstGap +
                ((pillSpace - pillSurface.measuredWidth) * .5f).roundToInt(),
                pad + ((innerH - pillSurface.measuredHeight) * .5f).roundToInt())
            place(exportSurface, pad + close.measuredWidth + firstGap + pillSpace + secondGap,
                pad + ((innerH - exportSurface.measuredHeight) * .5f).roundToInt())
        }
        private fun place(child: View, logicalX: Int, y: Int) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - logicalX - child.measuredWidth else logicalX
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }
}

private class BufferPill(context: Context, private var environment: ReverbViewEnvironment) : ViewGroup(context) {
    private val glyph = OnboardingIconView(context, ReverbIcon.OneShot, environment.palette.onPrimaryContainer)
    val label = environment.paragraph(context, "", ReverbTextRole.LABEL_SMALL)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(glyph); addView(label); label.setTextLayout(maxLines = 1)
    }
    fun bind(value: ReverbViewEnvironment, buffer: ReverbService.BufferSlot) {
        environment = value
        val oneShot = buffer == ReverbService.BufferSlot.ONE_SHOT
        val color = reverbColorWithAlpha(value.palette.onPrimaryContainer, .58f)
        glyph.bind(if (oneShot) ReverbIcon.OneShot else ReverbIcon.Looping, color)
        label.setText(context.getString(if (oneShot) R.string.buffer_one_shot else R.string.buffer_loop))
        label.setTypography(ReverbParagraphStyles[ReverbTextRole.LABEL_SMALL].copy(fontWeight = FontWeight.SemiBold),
            value.textDensity, value.sharedFontResolver)
        label.setTextColor(color); requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec); val maxH = MeasureSpec.getSize(heightMeasureSpec)
        val innerW = max(0, maxW - dp(10) - dp(10)); val innerH = max(0, maxH - dp(5) - dp(5))
        glyph.measure(rangeControlExact(min(dp(16), innerW)), rangeControlExact(min(dp(16), innerH)))
        val gap = min(dp(5), max(0, innerW - glyph.measuredWidth))
        label.measure(rangeControlAtMost(max(0, innerW - glyph.measuredWidth - gap)), rangeControlAtMost(innerH))
        setMeasuredDimension(resolveSize(glyph.measuredWidth + gap + label.measuredWidth + dp(10) + dp(10), widthMeasureSpec),
            resolveSize(max(glyph.measuredHeight, label.measuredHeight) + dp(5) + dp(5), heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val innerH = max(0, height - dp(5) - dp(5))
        var x = dp(10)
        for (child in listOf(glyph, label)) {
            val px = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - x - child.measuredWidth else x
            val y = dp(5) + ((innerH - child.measuredHeight) * .5f).roundToInt()
            child.layout(px, y, px + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + min(dp(5), max(0, width - dp(10) - x - child.measuredWidth))
        }
    }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}

/** Source clickable Surface has NO Button role; preserve original Text/progress descendants. */
internal class ExportContent(
    context: Context,
    private var environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    private val scope: CoroutineScope,
) : ViewGroup(context), NativeClickPhaseHost, NativePointerLayerShape {
    private val identity = Any()
    private var action: () -> Unit = {}
    private val glyph = OnboardingIconView(context, ReverbIcon.Save, environment.palette.muted)
    private var loadingView: ReverbIncidentLoadingView? = null
    val label = environment.paragraph(context, context.getString(R.string.export), ReverbTextRole.LABEL_LARGE)
    private val indication = ReverbRowIndication.install(this, environment.palette.onSurface,
        ValueAnimator::getDurationScale)
    private val controller = ReverbClickController(this, object : NativeClickEnvironment {
        override val nativeInputRoot get() = inputRoot()
        override val minimumTouchWidthPx get() = 48f * environment.textDensity.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun interestedParent(event: MotionEvent) = false
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
        override fun admitDown(event: MotionEvent) = reverbGroupGeometry(width.toFloat(), height.toFloat(),
            environment.textDensity.density, 20f).acceptsPointer(event.x, event.y,
            event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
    }, ReverbRowIndicationSink(indication) { x, y, point -> point.set(x, y) })
    override val nativeClickEndpoint = ReverbClickPhaseEndpoint(controller)
    override fun containsPointerLayer(x: Float, y: Float) = reverbGroupGeometry(
        width.toFloat(), height.toFloat(), environment.textDensity.density, 20f).contains(x, y)

    init {
        clipChildren = false; clipToPadding = false
        isClickable = true; isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this, true)
        ViewCompat.setScreenReaderFocusable(label, false)
        addView(glyph); addView(label)
        setOnClickListener { if (isEnabled) action() }
    }
    fun bind(value: ReverbViewEnvironment, loading: Boolean, enabled: Boolean, color: Int, action: () -> Unit) {
        environment = value; this.action = action
        isEnabled = enabled; isFocusable = enabled
        value.bindParagraphStyle(label, ReverbTextRole.LABEL_LARGE, color)
        glyph.bind(ReverbIcon.Save, color)
        if (loading && loadingView == null) {
            loadingView = ReverbIncidentLoadingView(context, value.palette.primary,
                requireNotNull(scope.coroutineContext[MonotonicFrameClock]), scope,
                { scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f }).also {
                it.contentDescription = context.getString(R.string.preparing_audio_selection)
                addView(it, 0)
            }
        } else if (!loading && loadingView != null) {
            removeView(loadingView); loadingView = null
        }
        loadingView?.setColor(value.palette.primary)
        glyph.visibility = if (loading) GONE else VISIBLE
        // M3 Surface's inherited color is contentColorFor(container), independent of Text override.
        indication.updateColor(if (enabled) value.palette.onPrimary else value.palette.onSurface)
        controller.bind(NativeClickBinding(identity, enabled, { performClick() }))
        requestLayout()
    }
    fun cancelInput() = controller.cancel()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec); val maxH = MeasureSpec.getSize(heightMeasureSpec)
        val innerW = max(0, maxW - dp(15) - dp(15))
        val icon = loadingView ?: glyph
        icon.measure(rangeControlExact(min(dp(20), innerW)), rangeControlExact(min(dp(20), maxH)))
        val gap = min(dp(7), max(0, innerW - icon.measuredWidth))
        label.measure(rangeControlAtMost(max(0, innerW - icon.measuredWidth - gap)), rangeControlAtMost(maxH))
        setMeasuredDimension(resolveSize(max(dp(48), icon.measuredWidth + gap + label.measuredWidth + dp(15) + dp(15)),
            widthMeasureSpec), resolveSize(max(dp(48), max(icon.measuredHeight, label.measuredHeight)), heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var logicalX = dp(15)
        for (child in listOf(loadingView ?: glyph, label)) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - logicalX - child.measuredWidth else logicalX
            val y = ((height - child.measuredHeight) * .5f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            logicalX += child.measuredWidth + min(dp(7), max(0, width - dp(15) - logicalX - child.measuredWidth))
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) =
        indication.onMeasured(w, h, environment.textDensity.density)
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); controller.attached()
        controller.bind(NativeClickBinding(identity, isEnabled, { performClick() }))
        controller.focusChanged(isFocused)
    }
    override fun onDetachedFromWindow() { controller.detached(); super.onDetachedFromWindow() }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); controller.cancel() }
    override fun onTouchEvent(event: MotionEvent) = controller.touch(event)
    override fun onKeyDown(code: Int, event: KeyEvent) =
        if (activation(code)) controller.key(event) else super.onKeyDown(code, event)
    override fun onKeyUp(code: Int, event: KeyEvent) =
        if (activation(code)) controller.key(event) else super.onKeyUp(code, event)
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val platform = super.onHoverEvent(event); return controller.hover(event) || platform
    }
    override fun onFocusChanged(focus: Boolean, direction: Int, old: Rect?) {
        super.onFocusChanged(focus, direction, old); controller.focusChanged(focus)
    }
    override fun addChildrenForAccessibility(children: ArrayList<View>) {
        loadingView?.let(children::add); children.add(label)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = View::class.java.name
        info.text = null; info.contentDescription = null
        if (isEnabled) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        else info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
    }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
    private fun activation(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_SPACE
}

private fun rangeControlExact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
private fun rangeControlAtMost(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST)
