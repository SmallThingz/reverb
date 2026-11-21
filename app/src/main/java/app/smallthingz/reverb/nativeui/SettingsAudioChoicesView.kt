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

import smallthingz.ui.components.NativeDropdown
import smallthingz.ui.components.NativeDropdownColors
import smallthingz.ui.components.NativeDropdownContent
import smallthingz.ui.components.NativeDropdownIcon
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.text.view.ParagraphView
import android.content.Context
import android.view.View
import android.view.ViewGroup
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

internal typealias SettingsDropdownContent<T> = NativeDropdownContent<T>

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
    initialEnvironment: ReverbViewEnvironment,
    initial: SettingsAudioChoicesState,
    private val sampleRateLabel: (Int) -> String,
    private val popup: SettingsAudioPopupHost,
    private val selections: SettingsAudioSelections,
    private val inputRoot: () -> View? = { null },
) : AutoCloseable {
    private val groupRecipe = initialEnvironment.appearance.groupSurfaces.settingsChoices
    val view = groupRecipe.create(context, ::invalidateSettingsTextContrast)
    private val initialDropdownColors = initialEnvironment.palette.dropdownColors()
    private fun <T> choice(kind: SettingsAudioChoice, choose: (T) -> Unit,
        environment: ReverbViewEnvironment): NativeDropdown<T> {
        val arrow = OnboardingIconView(context, ReverbIcon.ArrowDropDown, initialDropdownColors.muted).apply {
            contentDescription = context.getString(R.string.open_options)
        }
        val typography = environment.appearance.typography
        return NativeDropdown(context,
            ParagraphView(context, typography[ReverbTextRole.BODY_SMALL],
                environment.textDensity, environment.sharedFontResolver, initialDropdownColors.muted),
            ParagraphView(context, typography[ReverbTextRole.BODY_LARGE],
                environment.textDensity, environment.sharedFontResolver, initialDropdownColors.ink),
            NativeDropdownIcon(arrow, arrow::setTintImmediately), initialDropdownColors, inputRoot,
            onBinding = { anchor, content -> popup.update(kind, anchor, content, choose) },
            open = { anchor, content -> popup.open(kind, anchor, content, choose) },
            foregroundColor = ::transitionInk)
    }

    private val channel = choice(SettingsAudioChoice.CHANNELS, selections.channel, initialEnvironment)
    private val sampleFormat = choice(SettingsAudioChoice.SAMPLE_FORMAT, selections.sampleFormat, initialEnvironment)
    private val sampleRate = choice(SettingsAudioChoice.SAMPLE_RATE, selections.sampleRate, initialEnvironment)
    private val source = choice(SettingsAudioChoice.SOURCE, selections.source, initialEnvironment)
    private val route = choice(SettingsAudioChoice.ROUTE, selections.route, initialEnvironment)
    private val dropdowns = listOf(channel, sampleFormat, sampleRate, source, route)
    private val samplePair = SettingsAudioChoicePair(context, sampleFormat, sampleRate)
    private val inputPair = SettingsAudioChoicePair(context, source, route)

    init {
        view.addRow(channel, NativeGroupLayout.LayoutParams(-1, -2))
        view.addRow(samplePair, NativeGroupLayout.LayoutParams(-1, -2))
        view.addRow(inputPair, NativeGroupLayout.LayoutParams(-1, -2))
        bind(initial, initialEnvironment)
    }

    fun bind(state: SettingsAudioChoicesState, environment: ReverbViewEnvironment) {
        val palette = environment.palette
        val typography = environment.appearance.typography
        val description = context.getString(R.string.open_options)
        for (dropdown in dropdowns) {
            dropdown.bindTypography(typography[ReverbTextRole.BODY_SMALL],
                typography[ReverbTextRole.BODY_LARGE], environment.textDensity, environment.sharedFontResolver)
            dropdown.bindDescription(description)
        }
        val dropdownColors = palette.dropdownColors()
        channel.bind(
            SettingsDropdownContent(context.getString(R.string.channel_mode_label), state.channel, state.channels) {
                context.getString(it.labelRes)
            }, SettingsAudioChoice.CHANNELS in state.expanded, dropdownColors,
        )
        sampleFormat.bind(
            SettingsDropdownContent(context.getString(R.string.sample_format_label), state.sampleFormat, state.sampleFormats) {
                context.getString(it.labelRes)
            }, SettingsAudioChoice.SAMPLE_FORMAT in state.expanded, dropdownColors,
        )
        sampleRate.bind(
            SettingsDropdownContent(context.getString(R.string.sample_rate_label), state.sampleRate, state.sampleRates,
                sampleRateLabel), SettingsAudioChoice.SAMPLE_RATE in state.expanded, dropdownColors,
        )
        source.bind(
            SettingsDropdownContent(context.getString(R.string.audio_source_label), state.source, state.sources) {
                context.getString(it.labelRes)
            }, SettingsAudioChoice.SOURCE in state.expanded, dropdownColors,
        )
        route.bind(
            SettingsDropdownContent(context.getString(R.string.input_route_label), state.route, state.routes) {
                context.getString(it.labelRes)
            }, SettingsAudioChoice.ROUTE in state.expanded, dropdownColors,
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
        groupRecipe.bind(view, palette)
    }

    private fun topDivider(row: View, enabled: Boolean) {
        val params = row.layoutParams as NativeGroupLayout.LayoutParams
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

/** Source Row.weight + IntrinsicSize.Min. Each child text remains wrap-content inside its weight. */
private class SettingsAudioChoicePair(
    context: Context,
    private val first: NativeDropdown<*>,
    private val second: NativeDropdown<*>,
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

private fun ReverbPalette.dropdownColors() = NativeDropdownColors(field, raised, muted, ink, border)
