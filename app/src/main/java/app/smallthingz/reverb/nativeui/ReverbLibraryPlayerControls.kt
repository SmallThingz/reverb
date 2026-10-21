package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.ViewCompat
import app.smallthingz.reverb.NativeClickPhaseEndpoint
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
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
        duration.setTypography(ReverbParagraphStyles[ReverbTextRole.BODY_SMALL].copy(fontFamily = FontFamily.Monospace),
            value.textDensity, value.sharedFontResolver)
        duration.setTextColor(value.palette.muted)
    }
    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val hs = heightMeasureSpec.toAtMost()
        val size = min(width, dp(17f))
        icon.measure(exact(size), if (View.MeasureSpec.getMode(hs) == MeasureSpec.UNSPECIFIED) exact(dp(17f)) else exact(min(dp(17f), MeasureSpec.getSize(hs))))
        title.measure(atMost(max(0, width - icon.measuredWidth - dp(7f))), hs)
        duration.measure(atMost(max(0, width - icon.measuredWidth - dp(7f) - title.measuredWidth)), hs)
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

internal class PlayerTrimButtons(context: Context, private var environment: ReverbViewEnvironment, inputRoot: View) : ViewGroup(context) {
    private val cancel = PlayerTrimAction(context, environment, inputRoot, false)
    private val save = PlayerTrimAction(context, environment, inputRoot, true)
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
        cancel.measure(atMost(width), heightMeasureSpec.toAtMost())
        save.measure(atMost(max(0, width - cancel.measuredWidth - dp(8f))), heightMeasureSpec.toAtMost())
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
}

/** Surface(onClick), not Material Button: no implicit disabled text/tint or role is added. */
private class PlayerTrimAction(context: Context, private var environment: ReverbViewEnvironment,
                               inputRoot: View, private val savingAction: Boolean) : ViewGroup(context) {
    private val surface = ReverbGroupLayout(context, 14f)
    private val row = PlayerTrimActionRow(context, environment, inputRoot, savingAction)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        surface.addRow(row, ReverbGroupLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(surface)
    }
    fun bind(value: ReverbViewEnvironment, owner: String, enabled: Boolean, saving: Boolean, action: () -> Unit) {
        val densityChanged = environment.textDensity.density != value.textDensity.density
        environment = value
        if (densityChanged) requestLayout()
        surface.setPalette(if (savingAction) value.palette.secondaryContainer else value.palette.surfaceContainerHigh,
            android.graphics.Color.TRANSPARENT)
        row.bind(value, owner, enabled, saving, action)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        surface.measure(widthMeasureSpec, heightMeasureSpec)
        val minimum = (48f * environment.textDensity.density).roundToInt()
        setMeasuredDimension(resolveSize(max(minimum, surface.measuredWidth), widthMeasureSpec),
            resolveSize(max(minimum, surface.measuredHeight), heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = ((width - surface.measuredWidth) / 2f).roundToInt()
        val y = ((height - surface.measuredHeight) / 2f).roundToInt()
        surface.layout(x, y, x + surface.measuredWidth, y + surface.measuredHeight)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(-surface.left.toFloat() - row.left, -surface.top.toFloat() - row.top)
        return try { row.dispatchTouchEvent(copy) } finally { copy.recycle() }
    }
}

private class PlayerTrimActionRow(context: Context, private var environment: ReverbViewEnvironment,
                                  inputRoot: View, private val save: Boolean) : ViewGroup(context), NativeClickPhaseHost {
    private val label = environment.paragraph(context, "", ReverbTextRole.LABEL_LARGE)
    private var glyph: PlayerGlyph? = null
    private var spinner: ReverbIncidentLoadingView? = null
    private var owner: String? = null
    private var action: (() -> Unit)? = null
    private val indication = ReverbRowIndication.install(this, environment.palette.onSurface, ValueAnimator::getDurationScale)
    private val input = ReverbClickController(this, object : NativeClickEnvironment {
        override val nativeInputRoot: View = inputRoot
        override val minimumTouchWidthPx get() = 48f * environment.textDensity.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent) = reverbGroupGeometry(width.toFloat(), height.toFloat(),
            environment.textDensity.density, 14f).acceptsPointer(event.x, event.y,
            event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER, minimumTouchWidthPx)
        override fun interestedParent(event: MotionEvent) = true
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, ReverbRowIndicationSink(indication) { x, y, point -> point.set(x, y) })
    override val nativeClickEndpoint: NativeClickPhaseEndpoint = ReverbClickPhaseEndpoint(input)
    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this, true)
        ViewCompat.setScreenReaderFocusable(label, false)
        addView(label)
        setOnClickListener { if (isEnabled) action?.invoke() }
    }
    fun bind(value: ReverbViewEnvironment, owner: String, enabled: Boolean, saving: Boolean, callback: () -> Unit) {
        val densityChanged = environment.textDensity.density != value.textDensity.density
        environment = value
        if (densityChanged) requestLayout()
        this.owner = owner
        action = callback
        isEnabled = enabled
        isFocusable = enabled
        val color = if (save) value.palette.onSecondaryContainer else value.palette.onSurface
        val showSpinner = save && saving
        if (showSpinner) {
            glyph?.let(::removeView); glyph = null
            if (spinner == null) spinner = ReverbIncidentLoadingView(context, color).also { addView(it, 0) }
            spinner!!.setColor(color)
        } else {
            spinner?.let(::removeView); spinner = null
            if (glyph == null) glyph = PlayerGlyph(context, value, if (save) ReverbIcon.Save else ReverbIcon.Close).also { addView(it, 0) }
            glyph!!.bind(value, color)
        }
        label.setText(context.getString(if (!save) R.string.cancel else if (saving) R.string.trim_saving else R.string.trim_save_copy))
        value.bindParagraphStyle(label, ReverbTextRole.LABEL_LARGE, color)
        indication.updateColor(color)
        bindInput()
    }
    private fun bindInput() {
        input.bind(owner?.let { NativeClickBinding(it, isEnabled, onClick = { performClick() }) })
        input.focusChanged(isFocused)
    }
    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val horizontal = dp(if (save) 14f else 12f)
        val vertical = dp(9f)
        val width = max(0, MeasureSpec.getSize(widthMeasureSpec) - horizontal - horizontal)
        val bounded = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
        val height = max(0, MeasureSpec.getSize(heightMeasureSpec) - vertical - vertical)
        val leading: View = spinner ?: glyph ?: label
        val iconSize = dp(18f)
        leading.measure(exact(min(width, iconSize)), exact(if (bounded) min(height, iconSize) else iconSize))
        label.measure(atMost(max(0, width - leading.measuredWidth - dp(if (save) 7f else 6f))),
            if (bounded) atMost(height) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(resolveSize(horizontal + horizontal + leading.measuredWidth + dp(if (save) 7f else 6f) + label.measuredWidth, widthMeasureSpec),
            resolveSize(vertical + vertical + max(leading.measuredHeight, label.measuredHeight), heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val leading: View = spinner ?: glyph ?: return
        fun place(child: View, logical: Int) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - logical - child.measuredWidth else logical
            val y = ((height - child.measuredHeight) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
        val start = dp(if (save) 14f else 12f)
        place(leading, start); place(label, start + leading.measuredWidth + dp(if (save) 7f else 6f))
        indication.onMeasured(width, height, environment.textDensity.density)
    }
    override fun onTouchEvent(event: MotionEvent) = input.touch(event)
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean = if (activationKey(code)) input.key(event) else super.onKeyDown(code, event)
    override fun onKeyUp(code: Int, event: KeyEvent): Boolean = if (activationKey(code)) input.key(event) else super.onKeyUp(code, event)
    override fun onHoverEvent(event: MotionEvent): Boolean { val handled = super.onHoverEvent(event); return input.hover(event) || handled }
    override fun onFocusChanged(gain: Boolean, direction: Int, rect: Rect?) { super.onFocusChanged(gain, direction, rect); input.focusChanged(gain) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); input.attached(); bindInput() }
    override fun onDetachedFromWindow() { input.detached(); super.onDetachedFromWindow() }
    override fun getAccessibilityClassName(): CharSequence = "android.view.View"
}

private class PlayerGlyph(context: Context, private var environment: ReverbViewEnvironment,
                           private val glyph: ReverbIcon) : View(context) {
    private var drawable = ReverbIconDrawable(glyph, environment.textDensity.density, environment.palette.ink)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false }
    fun bind(value: ReverbViewEnvironment, color: Int) {
        if (value.textDensity.density != environment.textDensity.density) drawable = ReverbIconDrawable(glyph, value.textDensity.density, color)
        environment = value
        drawable.setTint(color)
        drawable.layoutDirection = layoutDirection
        drawable.setBounds(0, 0, width, height)
        invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { drawable.setBounds(0, 0, w, h) }
    override fun onRtlPropertiesChanged(layoutDirection: Int) { super.onRtlPropertiesChanged(layoutDirection); drawable.layoutDirection = layoutDirection }
    override fun onDraw(canvas: Canvas) { drawable.draw(canvas) }
}

private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
private fun atMost(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.AT_MOST)
private fun Int.toAtMost(): Int = if (View.MeasureSpec.getMode(this) == View.MeasureSpec.UNSPECIFIED) this else atMost(View.MeasureSpec.getSize(this))
private fun activationKey(code: Int): Boolean = when (code) {
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> true
    else -> false
}
