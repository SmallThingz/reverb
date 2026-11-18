package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeMaterialButtonStyle
import smallthingz.ui.components.NativeMaterialButtonView
import smallthingz.ui.util.closePreservingPrimaryFailure
import android.content.Context
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import app.smallthingz.reverb.R
import app.smallthingz.reverb.formatRangeTimeInput
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class PlayerTrimHeading(context: Context, private var environment: ReverbViewEnvironment) : ViewGroup(context) {
    private val icon = PlayerGlyph(context, environment, ReverbIcon.Trim)
    val title = environment.paragraph(context, context.getString(R.string.trim_recording), ReverbTextRole.LABEL_LARGE)
    val duration = environment.paragraph(context, "", ReverbTextRole.BODY_SMALL)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; addView(icon); addView(title); addView(duration) }
    fun bind(value: ReverbViewEnvironment, state: LibraryPlayerUiState) {
        val densityChanged = environment.textDensity.density != value.textDensity.density
        environment = value
        if (densityChanged) requestLayout()
        icon.bind(value, value.palette.primary)
        value.bindParagraphStyle(title, ReverbTextRole.LABEL_LARGE, value.palette.ink)
        duration.setText(formatRangeTimeInput((state.trimEndMillis - state.trimStartMillis).coerceAtLeast(0) / 1000.0))
        duration.setTypography(playerTimeTypography,
            value.textDensity, value.sharedFontResolver)
        duration.animateTextColor(value.palette.muted)
    }
    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val hs = heightMeasureSpec.toAtMost()
        val size = min(width, dp(17f))
        icon.measure(libraryPlayerControlsExact(size), if (View.MeasureSpec.getMode(hs) == MeasureSpec.UNSPECIFIED) libraryPlayerControlsExact(dp(17f)) else libraryPlayerControlsExact(min(dp(17f), MeasureSpec.getSize(hs))))
        title.measure(libraryPlayerControlsAtMost(max(0, width - icon.measuredWidth - dp(7f))), hs)
        duration.measure(libraryPlayerControlsAtMost(max(0, width - icon.measuredWidth - dp(7f) - title.measuredWidth)), hs)
        setMeasuredDimension(width, resolveSize(max(icon.measuredHeight, max(title.measuredHeight, duration.measuredHeight)), heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        fun place(child: View, logical: Int) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - logical - child.measuredWidth else logical
            val y = ((height - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
        place(icon, 0); place(title, icon.measuredWidth + dp(7f)); place(duration, width - duration.measuredWidth)
    }
}

internal class PlayerTrimButtons(context: Context, private var environment: ReverbViewEnvironment,
    inputRoot: View, frames: MonotonicFrameClock, scope: CoroutineScope, durationScale: () -> Float) : ViewGroup(context), AutoCloseable {
    private val cancel = PlayerTrimAction(context, environment, inputRoot, false, frames, scope, durationScale)
    private val save = PlayerTrimAction(context, environment, inputRoot, true, frames, scope, durationScale)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; clipChildren = false; addView(cancel); addView(save) }
    fun bind(value: ReverbViewEnvironment, state: LibraryPlayerUiState, actions: LibraryPlayerActions) {
        val densityChanged = environment.textDensity.density != value.textDensity.density
        environment = value
        if (densityChanged) requestLayout()
        cancel.bind(value, state.recordingRevisionKey, !state.trimSaving, false, actions.cancelTrim)
        save.bind(value, state.recordingRevisionKey, !state.trimSaving && state.trimEndMillis > state.trimStartMillis,
            state.trimSaving, actions.saveTrim)
    }
    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        cancel.measure(libraryPlayerControlsAtMost(width), heightMeasureSpec.toAtMost())
        save.measure(libraryPlayerControlsAtMost(max(0, width - cancel.measuredWidth - dp(8f))), heightMeasureSpec.toAtMost())
        setMeasuredDimension(width, resolveSize(max(cancel.measuredHeight, save.measuredHeight), heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val total = cancel.measuredWidth + dp(8f) + save.measuredWidth
        fun place(child: View, logical: Int) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - logical - child.measuredWidth else logical
            val y = ((height - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
        place(cancel, width - total); place(save, width - save.measuredWidth)
    }
    override fun close() {
        val failure = closePreservingPrimaryFailure(null, cancel::close)
        closePreservingPrimaryFailure(failure, save::close)?.let { throw it }
    }
}

private class PlayerTrimAction(
    context: Context,
    private var environment: ReverbViewEnvironment,
    inputRoot: View,
    private val save: Boolean,
    private val frames: MonotonicFrameClock,
    private val scope: CoroutineScope,
    private val durationScale: () -> Float,
) : ViewGroup(context), AutoCloseable {
    private var revision: String? = null
    private var spinner: ReverbIncidentLoadingView? = null
    private var glyphDensity = Float.NaN
    private val surface = NativeMaterialButtonView(context, false, ReverbParagraphStyles[ReverbTextRole.LABEL_LARGE],
        environment.textDensity, environment.sharedFontResolver,
        NativeMaterialButtonStyle(horizontalPaddingDp = if (save) 14 else 12,
            verticalPaddingDp = 9, minimumWidthDp = 0, minimumHeightDp = 0, iconSizeDp = 18,
            iconGapDp = if (save) 7 else 6, radiusDp = 14f, alignContentStart = true),
        OnboardingNativeInteractionsFactory { inputRoot }.roundedButton(14f, delayPressForParent = true) { revision }, {})

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        addView(surface)
    }

    fun bind(value: ReverbViewEnvironment, owner: String, enabled: Boolean, saving: Boolean, action: () -> Unit) {
        if (environment !== value) {
            environment = value
            surface.bindTypography(ReverbParagraphStyles[ReverbTextRole.LABEL_LARGE],
                value.textDensity, value.sharedFontResolver)
        }
        revision = owner
        val palette = value.palette
        val ink = if (save) palette.onSecondaryContainer else palette.onSurface
        val loading = save && saving
        val density = value.textDensity.density
        if (loading) {
            surface.setLeadingIcon(null)
            glyphDensity = Float.NaN
            val indicator = spinner ?: ReverbIncidentLoadingView(context, ink, frames, scope, durationScale).also {
                spinner = it
                surface.setLeadingView(it)
            }
            indicator.setColor(ink)
        } else {
            spinner = null
            surface.setLeadingView(null)
            if (glyphDensity != density) {
                glyphDensity = density
                surface.setLeadingIcon(ReverbIconDrawable(if (save) ReverbIcon.Save else ReverbIcon.Close, density, ink))
            }
        }
        surface.bind(context.getString(if (!save) R.string.cancel else if (saving) R.string.trim_saving else R.string.trim_save_copy),
            enabled, if (save) palette.secondaryContainer else palette.surfaceContainerHigh, ink, action)
    }

    override fun onMeasure(w: Int, h: Int) {
        surface.measure(w, h)
        val minimum = (48f * environment.textDensity.density).roundToInt()
        setMeasuredDimension(resolveSize(max(minimum, surface.measuredWidth), w),
            resolveSize(max(minimum, surface.measuredHeight), h))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = ((width - surface.measuredWidth) / 2f).roundToInt()
        val y = ((height - surface.measuredHeight) / 2f).roundToInt()
        surface.layout(x, y, x + surface.measuredWidth, y + surface.measuredHeight)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val translated = MotionEvent.obtain(event)
        translated.offsetLocation(-surface.left.toFloat(), -surface.top.toFloat())
        return try { surface.dispatchTouchEvent(translated) } finally { translated.recycle() }
    }
    override fun close() = surface.close()
}

private class PlayerGlyph(context: Context, private var environment: ReverbViewEnvironment,
                           private val glyph: ReverbIcon) : View(context) {
    private var drawable = ReverbIconDrawable(glyph, environment.textDensity.density, environment.palette.ink)
    private val colorMotion=colorTransition(environment.palette.ink) {drawable.setTint(it);invalidate()}
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false }
    fun bind(value: ReverbViewEnvironment, color: Int) {
        if (value.textDensity.density != environment.textDensity.density) drawable = ReverbIconDrawable(glyph, value.textDensity.density, color)
        environment = value
        colorMotion.retarget(color)
        drawable.setTint(colorMotion.color(0))
        drawable.layoutDirection = layoutDirection
        drawable.setBounds(0, 0, width, height)
        invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { drawable.setBounds(0, 0, w, h) }
    override fun onRtlPropertiesChanged(layoutDirection: Int) { super.onRtlPropertiesChanged(layoutDirection); drawable.layoutDirection = layoutDirection }
    override fun onDraw(canvas: Canvas) { drawable.draw(canvas) }
}

private fun libraryPlayerControlsExact(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
private fun libraryPlayerControlsAtMost(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.AT_MOST)
private fun Int.toAtMost(): Int = if (View.MeasureSpec.getMode(this) == View.MeasureSpec.UNSPECIFIED) this else libraryPlayerControlsAtMost(View.MeasureSpec.getSize(this))
