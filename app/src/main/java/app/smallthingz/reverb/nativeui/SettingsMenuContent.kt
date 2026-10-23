/*
 * Source-derived from Material3 1.4.0 DropdownMenuContent/DropdownMenuItemContent and released
 * SettingsScreen.kt:2397-2486. Copyright 2022 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.FontFamily
import smallthingz.ui.unit.Density
import androidx.core.view.ViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Concrete DropdownMenuItem inputs; callbacks remain owned by the screen's current row. */
internal data class ReverbMenuAction(
    val label: String,
    val icon: ReverbIcon,
    val textColor: Int,
    val iconColor: Int,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** A source Surface: shadow outside its rounded content clip; no tonal overlay at 0dp. */
internal class SettingsMenuContent(
    context: Context,
    private val density: Density,
    labelLarge: TextStyle,
    resolver: FontFamily.Resolver,
    private var palette: ReverbPalette,
    state: NativeVerticalScrollState,
    frames: MonotonicFrameClock,
    scope: CoroutineScope,
    /** Current source gesture-connection interest, supplied by the popup phase owner. */
    private val interestedParent: (MotionEvent) -> Boolean,
) : ViewGroup(context), AutoCloseable {
    private val radius = 18f * density.density
    private val pad = (8f * density.density).roundToInt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = SettingsMenuClip(context, radius)
    val column = SettingsMenuColumn(context, labelLarge, density, resolver, palette, interestedParent)
    val viewport = NativeVerticalViewport(
        context, state, density, frames, scope,
        object : NativeVerticalNestedHost {
            override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
            override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
            override suspend fun preFling(availableY: Float) = 0f
            override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
        },
    )

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        elevation = 3f * density.density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, out: Outline) {
                out.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = false
        if (Build.VERSION.SDK_INT >= 28) {
            outlineAmbientShadowColor = android.graphics.Color.BLACK
            outlineSpotShadowColor = android.graphics.Color.BLACK
        }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        fill.color = palette.raised
        viewport.setContent(column)
        clip.addView(viewport)
        addView(clip)
    }

    fun <T> bind(content: SettingsDropdownContent<T>, palette: ReverbPalette, select: (T) -> Unit) {
        this.palette = palette
        fill.color = palette.raised
        column.bind(content, palette, select)
        requestLayout()
        invalidate()
    }

    /** Released Incidents/Library use default12dp horizontal/zero vertical item padding. */
    fun bindActions(actions: List<ReverbMenuAction>, palette: ReverbPalette, background: Int = palette.surfaceContainerHigh) {
        this.palette = palette
        fill.color = background
        column.bindActions(actions, palette)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED)
            Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec)
        val width = column.maxIntrinsicWidth().coerceAtMost(availableWidth)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        viewport.measure(exact(width), atMost((availableHeight - 2 * pad).coerceAtLeast(0)))
        // padding(vertical=8.dp) is OUTSIDE verticalScroll; it does not scroll with the items.
        val height = resolveSize(viewport.measuredHeight + 2 * pad, heightMeasureSpec)
        clip.measure(exact(width), exact(height))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        clip.layout(0, 0, width, height)
        viewport.layout(0, pad, viewport.measuredWidth, pad + viewport.measuredHeight)
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, fill)
    }
    // Surface's inert pointerInput shields its rectangular allocated area.
    override fun onTouchEvent(event: MotionEvent) = true
    override fun close() = column.close()
}

/** Clipping belongs after background/shadow, with Android Outline on the hardware path. */
private class SettingsMenuClip(context: Context, private val radius: Float) : ViewGroup(context) {
    private val path = Path()
    init {
        clipChildren = false
        clipToPadding = false
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, out: Outline) {
                out.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        path.rewind()
        path.addRoundRect(0f,0f,w.toFloat(),h.toFloat(),radius,radius,Path.Direction.CW)
        invalidateOutline()
    }
    override fun dispatchDraw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) super.dispatchDraw(canvas)
        else {
            val save = canvas.save()
            try { canvas.clipPath(path); super.dispatchDraw(canvas) }
            finally { canvas.restoreToCount(save) }
        }
    }
}

internal class SettingsMenuColumn(
    context: Context,
    private val style: TextStyle,
    private val density: Density,
    private val resolver: FontFamily.Resolver,
    private var palette: ReverbPalette,
    private val interestedParent: (MotionEvent) -> Boolean,
) : ViewGroup(context), AutoCloseable {
    val rows = ArrayList<SettingsMenuItemView>()
    init { clipChildren = false; clipToPadding = false }

    fun <T> bind(content: SettingsDropdownContent<T>, palette: ReverbPalette, select: (T) -> Unit) {
        this.palette = palette
        while (rows.size > content.options.size) {
            val row = rows.removeAt(rows.lastIndex)
            row.close()
            removeView(row)
        }
        content.options.forEachIndexed { index, option ->
            val row = rows.getOrNull(index) ?: SettingsMenuItemView(
                context, style, density, resolver, palette, interestedParent,
            ).also { rows += it; addView(it) }
            // The released forEach has no key; node identity belongs to position, not option value.
            row.bind(content.optionLabel(option), option == content.selected, palette) { select(option) }
        }
        requestLayout()
    }

    fun bindActions(actions: List<ReverbMenuAction>, palette: ReverbPalette) {
        this.palette = palette
        while (rows.size > actions.size) {
            val row = rows.removeAt(rows.lastIndex)
            row.close()
            removeView(row)
        }
        actions.forEachIndexed { index, action ->
            val row = rows.getOrNull(index) ?: SettingsMenuItemView(
                context, style, density, resolver, palette, interestedParent,
            ).also { rows += it; addView(it) }
            row.bindAction(action, palette)
        }
        requestLayout()
    }

    fun maxIntrinsicWidth(): Int = rows.maxOfOrNull { it.maxIntrinsicWidth() } ?: 0

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        var height = 0
        for (row in rows) {
            row.measure(exact(width), unspecified())
            height += row.measuredHeight
        }
        setMeasuredDimension(width, resolveSize(height, h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = 0
        for (row in rows) {
            row.layout(0, y, width, y + row.measuredHeight)
            y += row.measuredHeight
        }
    }
    override fun close() { rows.forEach { it.close() } }
}

internal class SettingsMenuItemView(
    context: Context,
    style: TextStyle,
    private val density: Density,
    resolver: FontFamily.Resolver,
    palette: ReverbPalette,
    private val interestedParent: (MotionEvent) -> Boolean,
) : ViewGroup(context), AutoCloseable {
    // DropdownMenuItem's ProvideTextStyle(labelLarge) merges the inherited default body style.
    private val text = ReverbParagraphView(context, reverbDefaultTextStyle().merge(style), density, resolver, palette.muted)
    private val check = OnboardingIconView(context, ReverbIcon.Check, palette.primary)
    private val horizontal = dp(16f)
    private val vertical = dp(8f)
    private val radius = 12f * density.density
    private val clip = Path()
    private val indication = ReverbRowIndication.install(this, palette.ink, ValueAnimator::getDurationScale)
    private val sink = ReverbRowIndicationSink(indication) { x, y, point -> point.set(x, y) }
    var pointerRoot: View? = null
    val input = ReverbClickController(this, object : NativeClickEnvironment {
        override val nativeInputRoot get() = pointerRoot
        override val minimumTouchWidthPx get() = 48f * density.density
        override val minimumTouchHeightPx get() = minimumTouchWidthPx
        override fun admitDown(event: MotionEvent) =
            reverbGroupGeometry(width.toFloat(),height.toFloat(),density.density,12f)
                .acceptsPointer(event.x,event.y,event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER,minimumTouchWidthPx)
        override fun interestedParent(event: MotionEvent) = this@SettingsMenuItemView.interestedParent(event)
        override fun longPressOwnershipChanged(owns: Boolean) = Unit
    }, sink)
    private var selected = false
    private var leading = false
    private var action: (() -> Unit)? = null
    private var closed = false

    init {
        clipChildren = false
        clipToPadding = false
        clipToOutline = true
        isClickable = true
        isFocusable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setScreenReaderFocusable(this, true)
        ViewCompat.setScreenReaderFocusable(text, false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, out: Outline) =
                out.setRoundRect(0,0,view.width,view.height,radius)
        }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(text)
        addView(check)
        input.bind(NativeClickBinding(this, true, { performClick() }))
    }
    private fun dp(value: Float) = (value * density.density).roundToInt()

    fun bind(label: String, selected: Boolean, palette: ReverbPalette, action: () -> Unit) {
        this.action = action
        this.selected = selected
        leading = false
        isEnabled = true
        input.bind(NativeClickBinding(this, true, { performClick() }))
        text.setText(label)
        text.setTextColor(if (selected) palette.ink else palette.muted)
        check.bind(ReverbIcon.Check, palette.primary)
        check.visibility = if (selected) VISIBLE else GONE
        indication.updateColor(palette.ink)
        requestLayout()
    }

    fun bindAction(value: ReverbMenuAction, palette: ReverbPalette) {
        action = value.onClick
        selected = false
        leading = true
        isEnabled = value.enabled
        text.setText(value.label)
        text.setTextColor(value.textColor)
        check.bind(value.icon, value.iconColor)
        check.visibility = VISIBLE
        indication.updateColor(palette.ink)
        input.bind(NativeClickBinding(this, value.enabled, { performClick() }))
        requestLayout()
    }

    fun maxIntrinsicWidth(): Int =
        (text.maxIntrinsicTextWidth() + 2 * (if (leading) dp(12f) else horizontal) +
            if (leading || selected) dp(24f) + dp(12f) else 0)
            .coerceIn(dp(112f), dp(280f))

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val inset = if (leading) dp(12f) else horizontal
        val inner = (width - 2 * inset).coerceAtLeast(0)
        val hasIcon = leading || selected
        val iconWidth = if (hasIcon) min(dp(24f), inner) else 0
        val gap = if (hasIcon) min(dp(12f), (inner - iconWidth).coerceAtLeast(0)) else 0
        if (hasIcon) check.measure(exact(iconWidth), exact(dp(24f)))
        text.measure(atMost((inner-iconWidth-gap).coerceAtLeast(0)), unspecified())
        val height = max(dp(48f), max(text.measuredHeight, if (hasIcon) check.measuredHeight else 0) +
            if (leading) 0 else 2 * vertical)
        setMeasuredDimension(width, resolveSize(height, h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val inset = if (leading) dp(12f) else horizontal
        val textStart = inset + if (leading) check.measuredWidth + dp(12f) else 0
        val textX = if (rtl) width - textStart - text.measuredWidth else textStart
        val textY = ((height-text.measuredHeight)/2f).roundToInt()
        text.layout(textX,textY,textX+text.measuredWidth,textY+text.measuredHeight)
        if (leading || selected) {
            val logicalStart = if (leading) inset else width-inset-check.measuredWidth
            val x = if (rtl) width-logicalStart-check.measuredWidth else logicalStart
            val y = ((height-check.measuredHeight)/2f).roundToInt()
            check.layout(x,y,x+check.measuredWidth,y+check.measuredHeight)
        }
        indication.onMeasured(width,height,density.density)
    }

    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        clip.rewind()
        clip.addRoundRect(0f,0f,w.toFloat(),h.toFloat(),radius,radius,Path.Direction.CW)
        invalidateOutline()
    }
    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) super.draw(canvas)
        else {
            val save=canvas.save()
            try { canvas.clipPath(clip); super.draw(canvas) }
            finally { canvas.restoreToCount(save) }
        }
    }
    override fun performClick(): Boolean {
        if (closed || !isEnabled) return false
        val currentAction = action ?: return false
        super.performClick()
        currentAction()
        return true
    }
    override fun onTouchEvent(event: MotionEvent) = input.touch(event)
    override fun onKeyDown(code: Int,event: KeyEvent): Boolean =
        if (isActivationKey(code)) input.key(event) else super.onKeyDown(code,event)
    override fun onKeyUp(code: Int,event: KeyEvent): Boolean =
        if (isActivationKey(code)) input.key(event) else super.onKeyUp(code,event)
    private fun isActivationKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> true
        else -> false
    }
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val handled = input.hover(event)
        val accessibilityHandled = super.onHoverEvent(event)
        return handled || accessibilityHandled
    }
    override fun onFocusChanged(gain: Boolean,direction: Int,previously: Rect?) {
        super.onFocusChanged(gain,direction,previously)
        input.focusChanged(gain)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        input.attached()
        if (!closed) input.bind(NativeClickBinding(this, isEnabled, { performClick() }))
    }
    override fun onDetachedFromWindow() { input.detached(); super.onDetachedFromWindow() }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = View::class.java.name
        // DropdownMenuItem has no selected semantics; only the source trailing check marks selection.
    }
    override fun close() { closed=true; action=null; input.bind(null); input.cancel() }
}
