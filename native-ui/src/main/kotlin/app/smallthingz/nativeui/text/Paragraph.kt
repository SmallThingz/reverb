/*
 * Native Android paragraph layout. Intrinsic rounding, line-height edge treatment,
 * fallback padding and empty-final-line rules follow the released AndroidX ui-text
 * 1.11.2 contract, implemented over Android Layout rather than a UI runtime.
 * Portions derived from AndroidX, Copyright 2019–2023 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.nativeui.text

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.LocaleList
import android.text.BoringLayout
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristic
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.MetricAffectingSpan
import android.graphics.text.LineBreakConfig
import app.smallthingz.nativeui.geometry.Offset
import app.smallthingz.nativeui.geometry.Rect
import app.smallthingz.nativeui.graphics.Color
import app.smallthingz.nativeui.graphics.toArgb
import app.smallthingz.nativeui.text.font.FontFamily
import app.smallthingz.nativeui.text.font.FontStyle
import app.smallthingz.nativeui.text.font.FontSynthesis
import app.smallthingz.nativeui.text.font.FontWeight
import app.smallthingz.nativeui.text.style.LineHeightStyle
import app.smallthingz.nativeui.text.style.ResolvedTextDirection
import app.smallthingz.nativeui.text.style.TextDecoration
import app.smallthingz.nativeui.text.style.TextOverflow
import app.smallthingz.nativeui.unit.Constraints
import app.smallthingz.nativeui.unit.Density
import app.smallthingz.nativeui.unit.IntSize
import app.smallthingz.nativeui.unit.LayoutDirection
import app.smallthingz.nativeui.unit.TextUnit
import java.text.BreakIterator
import java.util.PriorityQueue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

open class ParagraphIntrinsics(
    val text: String,
    style: TextStyle,
    annotations: List<Any> = emptyList(),
    placeholders: List<Any> = emptyList(),
    val density: Density,
    val fontFamilyResolver: FontFamily.Resolver,
) {
    init {
        require(annotations.isEmpty() && placeholders.isEmpty()) {
            "Native plain paragraphs do not accept inline content or style annotations"
        }
    }
    val style = resolveDefaults(style, style.layoutDirection ?:
        if (TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == android.view.View.LAYOUT_DIRECTION_RTL)
            LayoutDirection.Rtl else LayoutDirection.Ltr)
    private val emojiProcessed = NativeEmojiFonts.ready
    val hasStaleResolvedFonts: Boolean get() = !emojiProcessed && NativeEmojiFonts.ready
    fun observeFontChanges(onChanged: () -> Unit): AutoCloseable = NativeEmojiFonts.observe(onChanged)

    internal val direction: TextDirectionHeuristic =
        if (this.style.layoutDirection == LayoutDirection.Rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL
        else TextDirectionHeuristics.FIRSTSTRONG_LTR
    internal val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.density = this@ParagraphIntrinsics.density.density
        hinting = Paint.HINTING_ON
        isSubpixelText = false
        isLinearText = false
        isElegantTextHeight = false
        val s = this@ParagraphIntrinsics.style
        textSize = with(this@ParagraphIntrinsics.density) { s.fontSize.toPx() }
        typeface = fontFamilyResolver.resolve(s.fontFamily, s.fontWeight ?: FontWeight.Normal,
            s.fontStyle ?: FontStyle.Normal, s.fontSynthesis ?: FontSynthesis.All).value
        textLocales = LocaleList.getDefault()
        fontFeatureSettings = ""
        color = s.color.toArgb()
        letterSpacing = with(this@ParagraphIntrinsics.density) {
            if (s.letterSpacing.isEm) s.letterSpacing.value
            else if (textSize != 0f) s.letterSpacing.toPx() / textSize else 0f
        }
    }
    internal val lineHeight: Float? = with(density) {
        val height = this@ParagraphIntrinsics.style.lineHeight
        when {
            height == TextUnit.Unspecified -> null
            height.isEm -> height.value * paint.textSize
            fontScale > 1.05f -> height.value / paint.textSize.toSp().value * paint.textSize
            else -> height.toPx()
        }
    }

    internal fun styledText(): CharSequence {
        val processed = if (emojiProcessed) NativeEmojiFonts.process(text) else text
        val height = lineHeight ?: return processed
        return SpannableString(processed).apply {
            setSpan(NativeLineHeightSpan(height,
                if (isEmpty() || last() == '\n') length + 1 else length,
                style.lineHeightStyle ?: LineHeightStyle.Default),
                0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    internal val measuredText: CharSequence = styledText()
    internal val boringMetrics: BoringLayout.Metrics? by lazy(LazyThreadSafetyMode.NONE) {
        if (Build.VERSION.SDK_INT >= 33) BoringLayout.isBoring(measuredText, paint, direction, true, null)
        else if (!direction.isRtl(measuredText, 0, measuredText.length))
            BoringLayout.isBoring(measuredText, paint, null) else null
    }
    val maxIntrinsicWidth: Float by lazy(LazyThreadSafetyMode.NONE) {
        var value = boringMetrics?.width?.toFloat()
            ?: ceil(Layout.getDesiredWidth(measuredText, 0, measuredText.length, paint))
        if (value != 0f && paint.letterSpacing != 0f) value += 0.5f
        value
    }
    val minIntrinsicWidth: Float by lazy(LazyThreadSafetyMode.NONE) {
        val iterator = BreakIterator.getLineInstance(paint.textLocale).apply { setText(text) }
        val longest = PriorityQueue<IntRange>(10, compareBy { it.last - it.first })
        var start = 0
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            if (longest.size < 10) longest.add(start..end)
            else if (checkNotNull(longest.peek()).let { it.last - it.first } < end - start) {
                longest.poll(); longest.add(start..end)
            }
            start = end
            end = iterator.next()
        }
        longest.maxOfOrNull { Layout.getDesiredWidth(measuredText, it.first, it.last, paint) } ?: 0f
    }
}

/** One Android Layout, reused for draw, cursor geometry and accessibility. */
open class Paragraph(
    val paragraphIntrinsics: ParagraphIntrinsics,
    val constraints: Constraints,
    val maxLines: Int = Int.MAX_VALUE,
    val overflow: TextOverflow = TextOverflow.Clip,
) {
    constructor(text: String, style: TextStyle, spanStyles: List<Any> = emptyList(),
                maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip,
                density: Density, fontFamilyResolver: FontFamily.Resolver,
                constraints: Constraints = Constraints()) : this(
        ParagraphIntrinsics(text, style, spanStyles, emptyList(), density, fontFamilyResolver),
        constraints, maxLines, overflow)

    init {
        require(maxLines >= 1)
        require(constraints.minWidth == 0 && constraints.minHeight == 0)
    }

    private val paint = paragraphIntrinsics.paint
    private val includePadding = paragraphIntrinsics.style.platformStyle?.includeFontPadding ?: false
    private val text = paragraphIntrinsics.styledText()
    private val ellipsize = when (overflow) {
        TextOverflow.Ellipsis -> TextUtils.TruncateAt.END
        TextOverflow.StartEllipsis -> TextUtils.TruncateAt.START
        TextOverflow.MiddleEllipsis -> TextUtils.TruncateAt.MIDDLE
        else -> null
    }
    private var layout = createLayout(text, maxLines)
    private var effectiveMaxLines = maxLines

    init {
        if (Build.VERSION.SDK_INT < 35 && paint.letterSpacing != 0f &&
            (overflow == TextOverflow.StartEllipsis || overflow == TextOverflow.MiddleEllipsis) &&
            layout.getEllipsisCount(0) > 0) {
            val before = layout.getEllipsisStart(0)
            val after = before + layout.getEllipsisCount(0)
            layout = createLayout(TextUtils.concat(text.subSequence(0, before), "\u2026",
                text.subSequence(after, text.length)), maxLines)
        }
        if (overflow == TextOverflow.Ellipsis && maxLines > 1) {
            val count = min(layout.lineCount, maxLines)
            val span = findLineHeightSpan(layout)
            val padding = computeVerticalPadding(layout, count, span)
            val last = computeEmptyLastLineMetrics(layout, count, span)
            fun bottom(line: Int): Float = if (line == count - 1 && last != null)
                layout.getLineBottom(line - 1).toFloat() + last.bottom
            else layout.getLineBottom(line).toFloat() + padding.first +
                if (line == count - 1) padding.second else 0
            val truncated = count >= maxLines && (layout.getEllipsisCount(count - 1) > 0 ||
                layout.getLineEnd(count - 1) != layout.text.length)
            val extra = last?.let { it.bottom - (layout.getLineBottom(count - 1) + padding.second -
                layout.getLineTop(count - 1) + if (count == 1) padding.first else 0) } ?: 0
            val measuredHeight = (if (truncated) layout.getLineBottom(count - 1) else layout.height) +
                padding.first + padding.second + extra
            if (measuredHeight > constraints.maxHeight) {
                var fitting = 0
                while (fitting < count && bottom(fitting) <= constraints.maxHeight) fitting++
                effectiveMaxLines = fitting.coerceAtLeast(1)
                if (effectiveMaxLines != maxLines) layout = createLayout(text, effectiveMaxLines)
            }
        }
    }

    val lineCount: Int = min(layout.lineCount, effectiveMaxLines)
    val didExceedMaxLines: Boolean = lineCount >= effectiveMaxLines &&
        (layout.getEllipsisCount(lineCount - 1) > 0 ||
            layout.getLineEnd(lineCount - 1) != layout.text.length)
    private val lineHeightSpan = findLineHeightSpan(layout)
    private val verticalPadding: Pair<Int, Int> = computeVerticalPadding()
    private val topPadding = verticalPadding.first
    private val bottomPadding = verticalPadding.second
    private val lastLineMetrics: Paint.FontMetricsInt? = computeEmptyLastLineMetrics()
    private val lastLineExtra: Int = lastLineMetrics?.let {
        it.bottom - (layout.getLineBottom(lineCount - 1) + bottomPadding -
            layout.getLineTop(lineCount - 1) + if (lineCount == 1) topPadding else 0)
    } ?: 0
    private val drawClip = android.graphics.Rect()
    private var cachedPositions: NativeTextPositions? = null
    private val positions: NativeTextPositions
        get() = cachedPositions ?: NativeTextPositions(layout).also { cachedPositions = it }

    val width: Float get() = constraints.maxWidth.toFloat()
    val height: Float get() = ((if (didExceedMaxLines) layout.getLineBottom(lineCount - 1) else layout.height) +
        topPadding + bottomPadding + lastLineExtra).toFloat()
    val firstBaseline: Float get() = getLineBaseline(0)
    val lastBaseline: Float get() = getLineBaseline(lineCount - 1)
    val minIntrinsicWidth: Float get() = paragraphIntrinsics.minIntrinsicWidth
    val maxIntrinsicWidth: Float get() = paragraphIntrinsics.maxIntrinsicWidth
    val placeholderRects: List<Rect?> get() = emptyList()

    private fun createLayout(value: CharSequence, lines: Int): Layout {
        val width = constraints.maxWidth
        val metrics = paragraphIntrinsics.boringMetrics
        if (value === text && metrics != null && paragraphIntrinsics.maxIntrinsicWidth <= width) {
            return if (Build.VERSION.SDK_INT >= 33) BoringLayout(value, paint, width,
                Layout.Alignment.ALIGN_NORMAL, 1f, 0f, metrics, includePadding, ellipsize, width, true)
            else BoringLayout(value, paint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f,
                metrics, includePadding, ellipsize, width)
        }
        return staticLayout(value, width, lines, ellipsize)
    }

    private fun staticLayout(value: CharSequence, width: Int, lines: Int,
                             ellipsis: TextUtils.TruncateAt?): StaticLayout {
        val builder = StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
            .setTextDirection(paragraphIntrinsics.direction)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(includePadding)
            .setUseLineSpacingFromFallbacks(true)
            .setLineSpacing(0f, 1f)
            .setMaxLines(lines)
            .setEllipsize(ellipsis)
            .setEllipsizedWidth(width)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setJustificationMode(Layout.JUSTIFICATION_MODE_NONE)
        if (Build.VERSION.SDK_INT >= 33) builder.setLineBreakConfig(LineBreakConfig.Builder()
            .setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_NONE)
            .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE).build())
        if (Build.VERSION.SDK_INT >= 35) {
            builder.setUseBoundsForWidth(false)
            builder.setShiftDrawingOffsetForStartOverhang(false)
        }
        return builder.build()
    }

    private fun findLineHeightSpan(value: Layout): NativeLineHeightSpan? =
        (value.text as? Spanned)?.getSpans(0, value.text.length, NativeLineHeightSpan::class.java)?.firstOrNull()

    private fun computeVerticalPadding(layout: Layout = this.layout, count: Int = lineCount,
                                       span: NativeLineHeightSpan? = lineHeightSpan): Pair<Int, Int> {
        var top = 0
        var bottom = 0
        val fallbackApplied = layout is StaticLayout || Build.VERSION.SDK_INT >= 33
        if (!includePadding && !fallbackApplied) {
            val bounds = android.graphics.Rect()
            textBounds(layout.text, layout.getLineStart(0), layout.getLineEnd(0), bounds)
            top = if (bounds.top < layout.getLineAscent(0)) layout.getLineAscent(0) - bounds.top else layout.topPadding
            val last = count - 1
            if (last != 0) textBounds(layout.text, layout.getLineStart(last), layout.getLineEnd(last), bounds)
            bottom = if (bounds.bottom > layout.getLineDescent(last)) bounds.bottom - layout.getLineDescent(last)
                else layout.bottomPadding
        }
        if (span?.style?.mode == LineHeightStyle.Mode.Tight) {
            if (span.trimFirst) top = 0
            if (span.trimLast) bottom = 0
        }
        span?.let {
            val firstDifference = if (it.firstAscentDiff < 0) abs(it.firstAscentDiff) else 0
            if (firstDifference != 0) top = maxOf(top, firstDifference)
            if (it.lastDescentDiff < 0) bottom = maxOf(bottom, firstDifference, abs(it.lastDescentDiff))
        }
        return top to bottom
    }

    /** Fallback bounds preserve metric spans, including the EmojiCompat font metrics. */
    private fun textBounds(value: CharSequence, start: Int, end: Int, result: android.graphics.Rect) {
        fun TextPaint.fill(from: Int, to: Int, bounds: android.graphics.Rect) {
            if (Build.VERSION.SDK_INT >= 29) getTextBounds(value, from, to, bounds)
            else getTextBounds(value.toString(), from, to, bounds)
        }
        val kind = MetricAffectingSpan::class.java
        if (value !is Spanned || value.nextSpanTransition(start - 1, end, kind) == end) {
            paint.fill(start, end, result)
            return
        }
        result.setEmpty()
        val segmentBounds = android.graphics.Rect()
        val segmentPaint = TextPaint()
        var from = start
        while (from < end) {
            val to = value.nextSpanTransition(from, end, kind)
            segmentPaint.set(paint)
            for (span in value.getSpans(from, to, kind)) {
                if (value.getSpanStart(span) != value.getSpanEnd(span)) span.updateMeasureState(segmentPaint)
            }
            segmentPaint.fill(from, to, segmentBounds)
            result.right += segmentBounds.width()
            result.top = minOf(result.top, segmentBounds.top)
            result.bottom = maxOf(result.bottom, segmentBounds.bottom)
            from = to
        }
    }

    private fun computeEmptyLastLineMetrics(layout: Layout = this.layout, count: Int = lineCount,
                                            lineSpan: NativeLineHeightSpan? = lineHeightSpan): Paint.FontMetricsInt? {
        val last = count - 1
        val span = lineSpan ?: return null
        if (layout.getLineStart(last) != layout.getLineEnd(last)) return null
        val empty = SpannableString("\u200B").apply {
            setSpan(NativeLineHeightSpan(span.height, 1, span.style,
                trimFirst = if (last != 0 && span.trimLast) false else span.trimLast),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val temporary = staticLayout(empty, Int.MAX_VALUE, Int.MAX_VALUE, null)
        return Paint.FontMetricsInt().apply {
            ascent = temporary.getLineAscent(0)
            descent = temporary.getLineDescent(0)
            top = temporary.getLineTop(0)
            bottom = temporary.getLineBottom(0)
        }
    }

    fun getLineTop(lineIndex: Int): Float = layout.getLineTop(lineIndex).toFloat() +
        if (lineIndex == 0) 0 else topPadding
    fun getLineBottom(lineIndex: Int): Float =
        if (lineIndex == lineCount - 1 && lastLineMetrics != null)
            layout.getLineBottom(lineIndex - 1).toFloat() + lastLineMetrics.bottom
        else layout.getLineBottom(lineIndex).toFloat() + topPadding +
            if (lineIndex == lineCount - 1) bottomPadding else 0
    fun getLineBaseline(lineIndex: Int): Float = topPadding +
        if (lineIndex == lineCount - 1 && lastLineMetrics != null)
            getLineTop(lineIndex) - lastLineMetrics.ascent
        else layout.getLineBaseline(lineIndex).toFloat()
    fun getLineLeft(lineIndex: Int): Float = layout.getLineLeft(lineIndex)
    fun getLineRight(lineIndex: Int): Float = layout.getLineRight(lineIndex)
    fun getLineHeight(lineIndex: Int): Float = getLineBottom(lineIndex) - getLineTop(lineIndex)
    fun getLineStart(lineIndex: Int): Int = layout.getLineStart(lineIndex)
    fun getLineEnd(lineIndex: Int, visibleEnd: Boolean = false): Int =
        if (layout.getEllipsisCount(lineIndex) > 0 && ellipsize == TextUtils.TruncateAt.END) {
            if (visibleEnd) layout.getLineStart(lineIndex) + layout.getEllipsisStart(lineIndex) else layout.text.length
        } else if (visibleEnd) positions.visibleEnd(lineIndex) else layout.getLineEnd(lineIndex)
    fun getLineForOffset(offset: Int): Int = layout.getLineForOffset(offset)
    fun getLineForVerticalPosition(vertical: Float): Int =
        layout.getLineForVertical(vertical.toInt() - topPadding)
    fun getOffsetForPosition(position: Offset): Int =
        layout.getOffsetForHorizontal(getLineForVerticalPosition(position.y), position.x)
    fun getParagraphDirection(offset: Int): ResolvedTextDirection =
        if (layout.getParagraphDirection(getLineForOffset(offset)) == Layout.DIR_LEFT_TO_RIGHT)
            ResolvedTextDirection.Ltr else ResolvedTextDirection.Rtl
    fun getBidiRunDirection(offset: Int): ResolvedTextDirection =
        if (layout.isRtlCharAt(offset)) ResolvedTextDirection.Rtl else ResolvedTextDirection.Ltr
    fun getHorizontalPosition(offset: Int, usePrimaryDirection: Boolean): Float =
        positions.horizontal(offset, usePrimaryDirection, upstream = false)
    fun isLineEllipsized(lineIndex: Int): Boolean = layout.getEllipsisCount(lineIndex) > 0

    fun getBoundingBox(offset: Int): Rect {
        require(offset in paragraphIntrinsics.text.indices)
        val line = getLineForOffset(offset)
        val ltr = layout.getParagraphDirection(line) == Layout.DIR_LEFT_TO_RIGHT
        val rtlChar = layout.isRtlCharAt(offset)
        val primary = ltr != rtlChar
        val a = positions.horizontal(offset, primary, false)
        val b = positions.horizontal(offset + 1, primary, true)
        return if (rtlChar) Rect(b, getLineTop(line), a, getLineBottom(line))
            else Rect(a, getLineTop(line), b, getLineBottom(line))
    }

    fun getCursorRect(offset: Int): Rect {
        require(offset in 0..paragraphIntrinsics.text.length)
        val line = getLineForOffset(offset)
        val horizontal = getHorizontalPosition(offset, true)
        return Rect(horizontal, getLineTop(line), horizontal, getLineBottom(line))
    }

    fun paint(canvas: Canvas, color: Color = paragraphIntrinsics.style.color) {
        if (!canvas.getClipBounds(drawClip)) return
        paint.color = color.toArgb()
        val decoration = paragraphIntrinsics.style.textDecoration ?: TextDecoration.None
        paint.isUnderlineText = TextDecoration.Underline in decoration
        paint.isStrikeThruText = TextDecoration.LineThrough in decoration
        val saved = canvas.save()
        try {
            if (didExceedMaxLines) canvas.clipRect(0f, 0f, width, height)
            canvas.translate(0f, topPadding.toFloat())
            ParagraphCanvas.draw(layout, canvas)
        } finally { canvas.restoreToCount(saved) }
    }
}

class MultiParagraphIntrinsics(
    val annotatedString: AnnotatedString,
    style: TextStyle,
    placeholders: List<Any>,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
) : ParagraphIntrinsics(annotatedString.text, style, emptyList(), placeholders, density, fontFamilyResolver)

class MultiParagraph(
    val intrinsics: MultiParagraphIntrinsics,
    constraints: Constraints,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) : Paragraph(intrinsics, constraints, maxLines, overflow)

data class TextLayoutInput(
    val text: AnnotatedString,
    val style: TextStyle,
    val placeholders: List<Any>,
    val maxLines: Int,
    val softWrap: Boolean,
    val overflow: TextOverflow,
    val density: Density,
    val layoutDirection: LayoutDirection,
    val fontFamilyResolver: FontFamily.Resolver,
    val constraints: Constraints,
)

class TextLayoutResult(val layoutInput: TextLayoutInput, val multiParagraph: MultiParagraph, val size: IntSize) {
    val firstBaseline: Float get() = multiParagraph.firstBaseline
    val lastBaseline: Float get() = multiParagraph.lastBaseline
    val lineCount: Int get() = multiParagraph.lineCount
    val didOverflowWidth: Boolean get() = size.width < multiParagraph.width
    val didOverflowHeight: Boolean get() = multiParagraph.didExceedMaxLines || size.height < multiParagraph.height
    val hasVisualOverflow: Boolean get() = didOverflowWidth || didOverflowHeight
    fun getLineTop(index: Int) = multiParagraph.getLineTop(index)
    fun getLineBottom(index: Int) = multiParagraph.getLineBottom(index)
    fun getLineBaseline(index: Int) = multiParagraph.getLineBaseline(index)
    fun getLineLeft(index: Int) = multiParagraph.getLineLeft(index)
    fun getLineRight(index: Int) = multiParagraph.getLineRight(index)
    fun getLineStart(index: Int) = multiParagraph.getLineStart(index)
    fun getLineEnd(index: Int, visibleEnd: Boolean = false) = multiParagraph.getLineEnd(index, visibleEnd)
    fun getLineForOffset(offset: Int) = multiParagraph.getLineForOffset(offset)
    fun getBoundingBox(offset: Int) = multiParagraph.getBoundingBox(offset)
    fun getCursorRect(offset: Int) = multiParagraph.getCursorRect(offset)
    fun getParagraphDirection(offset: Int) = multiParagraph.getParagraphDirection(offset)
    fun getOffsetForPosition(position: Offset) = multiParagraph.getOffsetForPosition(position)
}
