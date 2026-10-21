/*
 * Fixed-mode line-height span adapted from AndroidX ui-text 1.11.2.
 * Copyright 2019 The Android Open Source Project.
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
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.LocaleList
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LineHeightSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView

/**
 * Native candidate for plain text labels. Construction requires an explicit [ReverbTextScale].
 * It deliberately has no theme TextAppearance or guessed baseline correction.
 *
 * This is not yet a qualified replacement for Compose Paragraph. TextView does not expose the
 * latter's fallback-glyph padding backport, trailing-empty-line correction, letter-spacing
 * ellipsis corrections, or its intrinsic-width calculation. Those cases need direct paragraph
 * comparisons before production integration; a low-level paragraph-backed View remains an option.
 * Annotated text/EmojiCompat processing also require a separate explicit adapter.
 */
internal class ReverbTextView(
    context: Context,
    initialStyle: ReverbTextStyle,
    textScale: ReverbTextScale,
    textColor: Int,
) : TextView(context, null, 0) {
    constructor(context: Context, role: ReverbTextRole, scale: ReverbTextScale, textColor: Int) :
        this(context, role.style, scale, textColor)

    private var appliedStyle: ReverbTextStyle? = null
    private var appliedMetrics: ReverbTextMetrics? = null
    private var appliedWeight: Int? = null
    private var appliedLocales: LocaleList? = null

    init {
        // AndroidParagraphIntrinsics builds an AA-only Paint and applies TextMotion.Static.
        paintFlags = Paint.ANTI_ALIAS_FLAG
        paint.hinting = Paint.HINTING_ON
        paint.isSubpixelText = false
        paint.isLinearText = false
        paint.isElegantTextHeight = false
        includeFontPadding = false
        setFallbackLineSpacing(true)
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        justificationMode = Layout.JUSTIFICATION_MODE_NONE
        if (Build.VERSION.SDK_INT >= 33) {
            lineBreakStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_STYLE_NONE
            lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE
        }
        if (Build.VERSION.SDK_INT >= 35) {
            // targetSdk 35+ TextView defaults differ from ui-text's StaticLayoutFactory.
            setUseBoundsForWidth(false)
            setShiftDrawingOffsetForStartOverhang(false)
        }
        setAutoSizeTextTypeWithDefaults(AUTO_SIZE_TEXT_TYPE_NONE)
        setLineSpacing(0f, 1f)
        setPadding(0, 0, 0, 0)
        minimumWidth = 0
        minimumHeight = 0
        gravity = Gravity.TOP or Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_GRAVITY
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        setHorizontallyScrolling(false)
        transformationMethod = null
        fontFeatureSettings = ""
        textScaleX = 1f
        paint.textSkewX = 0f
        paint.isFakeBoldText = false
        textLocales = LocaleList.getDefault()
        setTextColor(textColor)
        applyTextStyle(initialStyle, textScale)
    }

    fun applyTextRole(role: ReverbTextRole, scale: ReverbTextScale) = applyTextStyle(role.style, scale)

    fun applyTextStyle(style: ReverbTextStyle, scale: ReverbTextScale) {
        val metrics = style.resolveMetrics(scale)
        val fontWeight = adjustedReverbFontWeight(
            style.fontWeight,
            if (Build.VERSION.SDK_INT >= 31) resources.configuration.fontWeightAdjustment else 0,
        )
        val locales = LocaleList.getDefault()
        if (style == appliedStyle && metrics == appliedMetrics && fontWeight == appliedWeight && locales == appliedLocales) return
        appliedStyle = style
        appliedMetrics = metrics
        appliedWeight = fontWeight
        appliedLocales = locales
        val family = when (style.fontFamily) {
            ReverbFontFamily.DEFAULT -> Typeface.DEFAULT
            ReverbFontFamily.SANS_SERIF -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
        typeface = if (style.fontFamily == ReverbFontFamily.DEFAULT && fontWeight == 400 && !style.italic) {
            Typeface.DEFAULT
        } else {
            Typeface.create(family, fontWeight, style.italic)
        }
        // Pass PX: converting through TextView's SP path would replace the supplied scale adapter.
        setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.fontSizePx)
        letterSpacing = metrics.letterSpacingEm
        setLineSpacing(0f, 1f)
        textLocales = locales
        setText(text, BufferType.NORMAL)
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        // TextView's constructor calls this before this subclass has initialized its fields.
        val style = appliedStyle
        val metrics = appliedMetrics
        if (style == null || metrics == null) {
            super.setText(text, type)
            return
        }
        val styled = SpannableString(text ?: "")
        styled.getSpans(0, styled.length, ReverbLineHeightSpan::class.java).forEach(styled::removeSpan)
        styled.setSpan(
            ReverbLineHeightSpan(
                style = style,
                heightPx = metrics.lineHeightPx,
                // Matches SpannableExtensions.setLineHeight; empty-final-line adjustment belongs
                // to Compose TextLayout and is explicitly not claimed to be reproduced here.
                endIndex = if (styled.isEmpty() || styled.last() == '\n') styled.length + 1 else styled.length,
            ),
            0,
            styled.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        super.setText(styled, type)
    }
}

/** Fixed-mode line-height behavior derived from ui-text 1.11.2 LineHeightStyleSpan. */
private class ReverbLineHeightSpan(
    private val style: ReverbTextStyle,
    private val heightPx: Float,
    private val endIndex: Int,
) : LineHeightSpan {
    private var targetMetrics: ReverbLineHeightMetrics? = null

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        v: Int,
        fm: Paint.FontMetricsInt,
    ) {
        if (fm.descent - fm.ascent <= 0) return
        val first = start == 0
        val last = end == endIndex
        if (first && last && style.trimLineHeightEdges) return
        val metrics = targetMetrics ?: reverbLineHeightMetrics(
            ascent = fm.ascent,
            descent = fm.descent,
            lineHeightPx = heightPx,
            alignment = style.lineHeightAlignment,
            trimEdges = style.trimLineHeightEdges,
        ).also { targetMetrics = it }
        fm.ascent = if (first) metrics.firstAscent else metrics.ascent
        fm.descent = if (last) metrics.lastDescent else metrics.descent
    }
}
