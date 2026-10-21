/*
 * Row weight/intrinsic arithmetic adapted from AndroidX foundation-layout 1.11.2.
 * Copyright 2019, 2022 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import app.smallthingz.reverb.AudioSourceMode
import app.smallthingz.reverb.ChannelMode
import app.smallthingz.reverb.InputRouteMode
import app.smallthingz.reverb.PcmSampleFormat
import app.smallthingz.reverb.R
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class SettingsAudioChoice { CHANNELS, SAMPLE_FORMAT, SAMPLE_RATE, SOURCE, ROUTE }

internal data class SettingsAudioChoicesState(
    val channel: ChannelMode,
    val channels: List<ChannelMode>,
    val sampleFormat: PcmSampleFormat,
    val sampleFormats: List<PcmSampleFormat>,
    val sampleRate: Int,
    val sampleRates: List<Int>,
    val source: AudioSourceMode,
    val sources: List<AudioSourceMode>,
    val route: InputRouteMode,
    val routes: List<InputRouteMode>,
    val expanded: Set<SettingsAudioChoice>,
)

internal data class SettingsAudioSelections(
    val channel: (ChannelMode) -> Unit,
    val sampleFormat: (PcmSampleFormat) -> Unit,
    val sampleRate: (Int) -> Unit,
    val source: (AudioSourceMode) -> Unit,
    val route: (InputRouteMode) -> Unit,
)

/** Typed data only. The separate popup owner retains released open/dismiss/menu behavior. */
internal data class SettingsDropdownContent<T>(
    val label: String,
    val selected: T,
    val options: List<T>,
    val optionLabel: (T) -> String,
)

internal interface SettingsAudioPopupHost {
    fun <T> update(
        kind: SettingsAudioChoice,
        anchor: View,
        content: SettingsDropdownContent<T>,
        onSelected: (T) -> Unit,
    )
    fun <T> open(
        kind: SettingsAudioChoice,
        anchor: View,
        content: SettingsDropdownContent<T>,
        onSelected: (T) -> Unit,
    )
}

/**
 * Released five choices in three physical rows: Channels; Sample format + Sample rate;
 * Source + Route. Caller supplies the existing external 16dp inset. Feature rows can be appended
 * directly to [view], so all audio settings retain the same continuous Surface and outer border.
 *
 * Popup state and whole-panel input admission stay screen-owned. Released SettingsDropdown has
 * no disabled Surface state; do not dim a retained inactive/loading panel's individual choices.
 */
internal class SettingsAudioChoicesView(
    private val context: Context,
    private val bodySmall: TextStyle,
    private val bodyLarge: TextStyle,
    private val density: Density,
    private val resolver: FontFamily.Resolver,
    initial: SettingsAudioChoicesState,
    palette: ReverbPalette,
    private val sampleRateLabel: (Int) -> String,
    private val popup: SettingsAudioPopupHost,
    private val selections: SettingsAudioSelections,
) : AutoCloseable {
    val view = ReverbGroupLayout(context, cornerRadiusDp = 16f)
    private var currentPalette = palette
    private val interactions = OnboardingNativeInteractionsFactory()
    private fun <T> choice(kind: SettingsAudioChoice, choose: (T) -> Unit) =
        SettingsAudioDropdownView<T>(context, bodySmall, bodyLarge, density, resolver, currentPalette, interactions,
            onBinding = { anchor, content -> popup.update(kind, anchor, content, choose) },
        ) {
            anchor, content -> popup.open(kind, anchor, content, choose)
        }

    private val channel = choice(SettingsAudioChoice.CHANNELS, selections.channel)
    private val sampleFormat = choice(SettingsAudioChoice.SAMPLE_FORMAT, selections.sampleFormat)
    private val sampleRate = choice(SettingsAudioChoice.SAMPLE_RATE, selections.sampleRate)
    private val source = choice(SettingsAudioChoice.SOURCE, selections.source)
    private val route = choice(SettingsAudioChoice.ROUTE, selections.route)
    private val samplePair = SettingsAudioChoicePair(context, sampleFormat, sampleRate)
    private val inputPair = SettingsAudioChoicePair(context, source, route)

    init {
        view.addRow(channel, ReverbGroupLayout.LayoutParams(-1, -2))
        view.addRow(samplePair, ReverbGroupLayout.LayoutParams(-1, -2))
        view.addRow(inputPair, ReverbGroupLayout.LayoutParams(-1, -2))
        bind(initial, palette)
    }

    fun bind(state: SettingsAudioChoicesState, palette: ReverbPalette) {
        currentPalette = palette
        channel.bind(
            SettingsDropdownContent(context.getString(R.string.channel_mode_label), state.channel, state.channels) {
                context.getString(it.labelRes)
            }, SettingsAudioChoice.CHANNELS in state.expanded, palette,
        )
        sampleFormat.bind(
            SettingsDropdownContent(context.getString(R.string.sample_format_label), state.sampleFormat, state.sampleFormats) {
                context.getString(it.labelRes)
            }, SettingsAudioChoice.SAMPLE_FORMAT in state.expanded, palette,
        )
        sampleRate.bind(
            SettingsDropdownContent(context.getString(R.string.sample_rate_label), state.sampleRate, state.sampleRates,
                sampleRateLabel), SettingsAudioChoice.SAMPLE_RATE in state.expanded, palette,
        )
        source.bind(
            SettingsDropdownContent(context.getString(R.string.audio_source_label), state.source, state.sources) {
                context.getString(it.labelRes)
            }, SettingsAudioChoice.SOURCE in state.expanded, palette,
        )
        route.bind(
            SettingsDropdownContent(context.getString(R.string.input_route_label), state.route, state.routes) {
                context.getString(it.labelRes)
            }, SettingsAudioChoice.ROUTE in state.expanded, palette,
        )
        val showChannels = channel.visibility != View.GONE
        val showSample = sampleFormat.visibility != View.GONE || sampleRate.visibility != View.GONE
        val showInput = source.visibility != View.GONE || route.visibility != View.GONE
        samplePair.visibility = if (showSample) View.VISIBLE else View.GONE
        inputPair.visibility = if (showInput) View.VISIBLE else View.GONE
        sampleRate.dividerAtStart = sampleFormat.visibility != View.GONE
        route.dividerAtStart = source.visibility != View.GONE
        topDivider(samplePair, showChannels)
        topDivider(inputPair, showChannels || showSample)
        view.setPalette(palette.field, palette.border)
    }

    private fun topDivider(row: View, enabled: Boolean) {
        val params = row.layoutParams as ReverbGroupLayout.LayoutParams
        if (params.dividerTop == enabled) return
        params.dividerTop = enabled
        row.requestLayout()
    }

    override fun close() {
        channel.close()
        sampleFormat.close()
        sampleRate.close()
        source.close()
        route.close()
    }
}

private class SettingsAudioDropdownView<T>(
    context: Context,
    bodySmall: TextStyle,
    bodyLarge: TextStyle,
    density: Density,
    resolver: FontFamily.Resolver,
    initialPalette: ReverbPalette,
    interactions: OnboardingNativeInteractionsFactory,
    private val onBinding: (View, SettingsDropdownContent<T>) -> Unit,
    private val open: (View, SettingsDropdownContent<T>) -> Unit,
) : ViewGroup(context), AutoCloseable {
    private val title = ReverbParagraphView(context, bodySmall, density, resolver, initialPalette.muted)
    private val selected = ReverbParagraphView(context, bodyLarge, density, resolver, initialPalette.ink)
    private val arrow = OnboardingIconView(context, ReverbIcon.ArrowDropDown, initialPalette.muted)
    private val input = interactions.row(this)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        strokeWidth = 1f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val backgroundSpring = ReleasedSpringValue.color(Color(initialPalette.field))
    private val backgroundDriver = ViewSpringDriver(this, backgroundSpring, { fill.color = it.toArgb() })
    private var content: SettingsDropdownContent<T>? = null
    private var textHeight = 0
    private var textGap = 0
    private var textWidth = 0
    private var iconGap = 0
    private var present = false
    private var closed = false
    var dividerAtStart: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        ViewCompat.setScreenReaderFocusable(this, true)
        // A source merging clickable keeps separate unmerged Text/Icon nodes. No joined label.
        ViewCompat.setScreenReaderFocusable(title, false)
        ViewCompat.setScreenReaderFocusable(selected, false)
        arrow.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        arrow.contentDescription = context.getString(R.string.open_options)
        selected.setTextLayout(maxLines = 1)
        fill.color = initialPalette.field
        addView(title)
        addView(selected)
        addView(arrow)
        setOnClickListener { content?.takeIf { !closed && present }?.let { open(this, it) } }
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    fun bind(next: SettingsDropdownContent<T>, expanded: Boolean, palette: ReverbPalette) {
        content = next
        val nextPresent = next.options.size > 1
        val target = Color(if (expanded) palette.raised else palette.field)
        if (!present || !nextPresent) {
            backgroundSpring.snapTo(target)
            fill.color = target.toArgb()
        } else backgroundDriver.retarget(target)
        present = nextPresent
        visibility = if (present) VISIBLE else GONE
        title.setText(next.label)
        title.setTextColor(palette.muted)
        selected.setText(next.optionLabel(next.selected))
        selected.setTextColor(palette.ink)
        arrow.bind(ReverbIcon.ArrowDropDown, palette.muted)
        divider.color = palette.border
        input.bind(present, palette.ink)
        onBinding(this, next)
        invalidate()
    }

    fun intrinsicHeight(width: Int): Int {
        val textWidth = availableTextWidth(width)
        val stack = title.intrinsicTextHeight(textWidth) + dp(2) + selected.intrinsicTextHeight(textWidth)
        return max(dp(72), dp(10) * 2 + max(stack, dp(24)))
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val innerWidth = (width - dp(14) * 2).coerceAtLeast(0)
        val unboundedHeight = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
        val innerMaximumHeight = if (unboundedHeight) {
            Int.MAX_VALUE
        } else (MeasureSpec.getSize(heightSpec) - dp(10) * 2).coerceAtLeast(0)
        arrow.measure(exact(minOf(dp(24), innerWidth)), exact(minOf(dp(24), innerMaximumHeight)))
        iconGap = minOf(dp(10), (innerWidth - arrow.measuredWidth).coerceAtLeast(0))
        textWidth = (innerWidth - arrow.measuredWidth - iconGap).coerceAtLeast(0)
        fun heightLimit(limit: Int) = if (unboundedHeight) unspecified() else atMost(limit)
        title.measure(atMost(textWidth), heightLimit(innerMaximumHeight))
        val afterTitle = (innerMaximumHeight - title.measuredHeight).coerceAtLeast(0)
        textGap = minOf(dp(2), afterTitle)
        selected.measure(atMost(textWidth), heightLimit((afterTitle - textGap).coerceAtLeast(0)))
        textHeight = title.measuredHeight + textGap + selected.measuredHeight
        val height = max(dp(72), dp(10) * 2 + max(textHeight, arrow.measuredHeight))
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rowHeight = (height - dp(10) * 2).coerceAtLeast(0)
        val textY = dp(10) + ((rowHeight - textHeight) / 2f).roundToInt()
        placeStart(title, dp(14), textY)
        placeStart(selected, dp(14), textY + title.measuredHeight + textGap)
        placeStart(arrow, dp(14) + textWidth + iconGap,
            dp(10) + ((rowHeight - arrow.measuredHeight) / 2f).roundToInt())
        input.measured(width, height)
    }

    private fun placeStart(child: View, start: Int, top: Int) {
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
        child.layout(x, top, x + child.measuredWidth, top + child.measuredHeight)
    }
    private fun availableTextWidth(width: Int): Int =
        (width - dp(14) * 2 - dp(10) - dp(24)).coerceAtLeast(0)

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        super.onDraw(canvas)
    }
    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (dividerAtStart) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - 0.5f else 0.5f
            canvas.drawLine(x, 0f, x, height.toFloat(), divider)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = input.touch(event)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = input.key(event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = input.key(event)
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val handled = super.onHoverEvent(event)
        return input.hover(event) || handled
    }
    override fun onFocusChanged(gain: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gain, direction, previous)
        input.focusChanged(gain)
    }
    override fun onCancelPendingInputEvents() { super.onCancelPendingInputEvents(); input.cancel() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); input.attached() }
    override fun onDetachedFromWindow() { input.detached(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean = present && !closed && isAttachedToWindow && super.performClick()
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // The released clickable Surface supplies no Role.Button and no parent Text value.
        info.className = View::class.java.name
        info.text = null
        info.contentDescription = null
    }
    override fun close() {
        if (closed) return
        closed = true
        input.cancel()
        backgroundDriver.close()
        content = null
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

/** Source Row.weight + IntrinsicSize.Min. Each child text remains wrap-content inside its weight. */
private class SettingsAudioChoicePair(
    context: Context,
    private val first: SettingsAudioDropdownView<*>,
    private val second: SettingsAudioDropdownView<*>,
) : ViewGroup(context) {
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        addView(first)
        addView(second)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val firstVisible = first.visibility != GONE
        val secondVisible = second.visibility != GONE
        val count = (if (firstVisible) 1 else 0) + (if (secondVisible) 1 else 0)
        if (count == 0) {
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(0, heightSpec))
            return
        }
        val unit = (width.toFloat() / count).roundToInt()
        val targetHeight = max(if (firstVisible) first.intrinsicHeight(unit) else 0,
            if (secondVisible) second.intrinsicHeight(unit) else 0)
        val height = resolveSize(targetHeight, heightSpec)
        var remainder = width - unit * count
        repeat(2) { index ->
            val child = if (index == 0) first else second
            if (child.visibility == GONE) return@repeat
            val correction = remainder.coerceIn(-1, 1)
            remainder -= correction
            child.measure(exact(unit + correction), exact(height))
        }
        setMeasuredDimension(resolveSize(width, widthSpec), height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var start = 0
        repeat(2) { index ->
            val child = if (index == 0) first else second
            if (child.visibility == GONE) return@repeat
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
            child.layout(x, 0, x + child.measuredWidth, height)
            start += child.measuredWidth
        }
    }
}
