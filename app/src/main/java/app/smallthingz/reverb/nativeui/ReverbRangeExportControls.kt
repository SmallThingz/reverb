package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.components.NativeButtonInputPolicy
import smallthingz.ui.components.NativeMaterialButtonStyle
import smallthingz.ui.components.NativeMaterialButtonView
import android.content.Context
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.MotionDurationScale
import smallthingz.ui.text.font.FontWeight
import app.smallthingz.reverb.RangeExportEditorState
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
    private val surface = NativeGroupLayout(context, 28f, onPaintedSurfaceChanged = ::invalidateSettingsTextContrast)
    private val row = ControlsRow(context)
    val close = ReverbStandardIconButtonView(context, initialEnvironment, inputRoot, actionSizeDp = 50f)
    private val pillSurface = NativeGroupLayout(context, 16f, onPaintedSurfaceChanged = ::invalidateSettingsTextContrast)
    private val pill = BufferPill(context, initialEnvironment)
    private val exportSurface = NativeGroupLayout(context, 20f, onPaintedSurfaceChanged = ::invalidateSettingsTextContrast)
    val exportAction = ExportContent(context, initialEnvironment, inputRoot, scope)
    private var onCancel: () -> Unit = {}
    private var onExport: () -> Unit = {}
    private var state: RangeExportEditorState? = null
    private data class VisualKey(val environment: ReverbViewEnvironment, val loading: Boolean,
        val enabled: Boolean, val buffer: ReverbService.BufferSlot, val canClose: Boolean)
    private var visualKey: VisualKey? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false; clipToPadding = false
        addView(surface)
        surface.addRow(row, NativeGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        row.addView(close); row.addView(pillSurface); row.addView(exportSurface)
        pillSurface.addRow(pill, NativeGroupLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        exportSurface.addRow(exportAction,
            NativeGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(
        value: ReverbViewEnvironment,
        state: RangeExportEditorState,
        loading: Boolean,
        canExport: Boolean,
        selectedBuffer: ReverbService.BufferSlot,
        onCancel: () -> Unit,
        onExport: () -> Unit,
        canClose: Boolean = true,
    ) {
        environment = value; this.state = state; this.onCancel = onCancel; this.onExport = onExport
        val palette = value.palette
        val enabled = !loading && state.snapshotReady && !state.selectionDurationEditing && canExport
        val previous = visualKey
        if (previous != null && previous.environment === value && previous.loading == loading &&
            previous.enabled == enabled && previous.buffer == selectedBuffer && previous.canClose == canClose) return
        // Waveform frames keep live callbacks above without allocating or rebinding visual state.
        visualKey = VisualKey(value, loading, enabled, selectedBuffer, canClose)
        surface.setPalette(palette.field, palette.border)
        close.bind(value, ReverbIcon.Close, context.getString(R.string.close), canClose,
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
        val color = value.palette.onPrimaryContainer
        glyph.bind(if (oneShot) ReverbIcon.OneShot else ReverbIcon.Looping, color)
        label.setText(context.getString(if (oneShot) R.string.buffer_one_shot else R.string.buffer_loop))
        label.setTypography(rangeBufferLabelStyle,
            value.textDensity, value.sharedFontResolver)
        label.animateTextColor(color); requestLayout()
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
        for (index in 0..1) {
            val child = if (index == 0) glyph else label
            val px = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - x - child.measuredWidth else x
            val y = dp(5) + ((innerH - child.measuredHeight) * .5f).roundToInt()
            child.layout(px, y, px + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + min(dp(5), max(0, width - dp(10) - x - child.measuredWidth))
        }
    }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}

internal class ExportContent(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    private val scope: CoroutineScope,
) : NativeMaterialButtonView(context, false, ReverbParagraphStyles[ReverbTextRole.LABEL_LARGE],
    initialEnvironment.textDensity, initialEnvironment.sharedFontResolver,
    NativeMaterialButtonStyle(15, verticalPaddingDp = 0, minimumWidthDp = 48,
        minimumHeightDp = 48, iconSizeDp = 20, radiusDp = 20f, alignContentStart = true),
    NativeButtonInputPolicy(inputRoot), {}) {
    private var loadingView: ReverbIncidentLoadingView? = null
    private var glyphDensity = Float.NaN

    fun bind(value: ReverbViewEnvironment, loading: Boolean, enabled: Boolean, color: Int, action: () -> Unit) {
        bindTypography(ReverbParagraphStyles[ReverbTextRole.LABEL_LARGE], value.textDensity, value.sharedFontResolver)
        if (loading) {
            setLeadingIcon(null)
            glyphDensity = Float.NaN
            val indicator = loadingView ?: ReverbIncidentLoadingView(context, value.palette.primary,
                requireNotNull(scope.coroutineContext[MonotonicFrameClock]), scope,
                { scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f }).also {
                it.contentDescription = context.getString(R.string.preparing_audio_selection)
                loadingView = it
                setLeadingView(it, exposeToAccessibility = true)
            }
            indicator.setColor(value.palette.primary)
        } else {
            loadingView = null
            setLeadingView(null)
            val density = value.textDensity.density
            if (glyphDensity != density) {
                glyphDensity = density
                setLeadingIcon(ReverbIconDrawable(ReverbIcon.Save, density, color))
            }
        }
        bind(context.getString(R.string.export), enabled, android.graphics.Color.TRANSPARENT,
            color, action, if (enabled) value.palette.onPrimary else value.palette.onSurface)
    }
}

private fun rangeControlExact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
private fun rangeControlAtMost(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST)

private val rangeBufferLabelStyle = ReverbParagraphStyles[ReverbTextRole.LABEL_SMALL].copy(fontWeight = FontWeight.SemiBold)
