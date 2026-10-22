/*
 * Bidirectional boundary positions adapted from AndroidX LayoutHelper (ui-text 1.11.2).
 * Copyright 2022 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.nativeui.text

import android.text.Layout
import android.text.TextUtils
import java.text.Bidi

/** Android exposes downstream carets; character rectangles also need upstream line-edge carets. */
internal class NativeTextPositions(private val layout: Layout) {
    private val ends = buildList {
        var end = 0
        do {
            val next = layout.text.indexOf('\n', end)
            end = if (next < 0) layout.text.length else next + 1
            add(end)
        } while (end < layout.text.length)
    }
    private val analyzed = BooleanArray(ends.size)
    private val paragraphs = arrayOfNulls<Bidi>(ends.size)

    private fun paragraphStart(index: Int) = if (index == 0) 0 else ends[index - 1]
    private fun paragraphRtl(index: Int) =
        layout.getParagraphDirection(layout.getLineForOffset(paragraphStart(index))) == Layout.DIR_RIGHT_TO_LEFT

    private fun bidi(index: Int): Bidi? {
        if (!analyzed[index]) {
            val start = paragraphStart(index)
            val chars = CharArray(ends[index] - start)
            TextUtils.getChars(layout.text, start, ends[index], chars, 0)
            if (Bidi.requiresBidi(chars, 0, chars.size)) {
                val value = Bidi(chars, 0, null, 0, chars.size,
                    if (paragraphRtl(index)) Bidi.DIRECTION_RIGHT_TO_LEFT else Bidi.DIRECTION_LEFT_TO_RIGHT)
                if (value.runCount > 1) paragraphs[index] = value
            }
            analyzed[index] = true
        }
        return paragraphs[index]
    }

    private fun downstream(offset: Int, primary: Boolean): Float {
        // Android getHorizontal can crash after an ellipsized newline unless clamped to its line.
        val target = offset.coerceAtMost(layout.getLineEnd(layout.getLineForOffset(offset)))
        return if (primary) layout.getPrimaryHorizontal(target) else layout.getSecondaryHorizontal(target)
    }

    fun horizontal(offset: Int, primary: Boolean, upstream: Boolean): Float {
        if (!upstream || offset == 0 || offset == layout.text.length) return downstream(offset, primary)
        var line = layout.getLineForOffset(offset)
        if (line > 0 && layout.getLineStart(line) == offset) line--
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line)
        if (offset != start && offset != end) return downstream(offset, primary)
        var paragraph = ends.binarySearch(offset).let { if (it < 0) -(it + 1) else it + 1 }
        if (paragraph > 0 && offset == ends[paragraph - 1]) paragraph--
        val rtl = paragraphRtl(paragraph)
        val visibleEnd = trimEnd(end, start)
        val paragraphStart = paragraphStart(paragraph)
        val lineBidi = bidi(paragraph)?.createLineBidi(start - paragraphStart, visibleEnd - paragraphStart)
        if (lineBidi == null || lineBidi.runCount == 1) {
            val startLeft = if (primary || rtl == layout.isRtlCharAt(start)) !rtl else rtl
            val left = if (offset == start) startLeft else !startLeft
            return if (left) layout.getLineLeft(line) else layout.getLineRight(line)
        }
        val runs = Array(lineBidi.runCount) {
            Run(start + lineBidi.getRunStart(it), start + lineBidi.getRunLimit(it),
                lineBidi.getRunLevel(it) % 2 == 1)
        }
        Bidi.reorderVisually(ByteArray(runs.size) { lineBidi.getRunLevel(it).toByte() },
            0, runs, 0, runs.size)
        val atStart = offset == start
        val adjusted = if (offset > visibleEnd) trimEnd(offset, start) else offset
        val index = if (atStart) runs.indexOfFirst { it.start == offset }
            else runs.indexOfFirst { it.end == adjusted }
        val run = runs[index]
        val left = if (atStart) {
            if (primary || rtl == run.rtl) !rtl else rtl
        } else {
            if (primary || rtl == run.rtl) rtl else !rtl
        }
        return when {
            index == 0 && left -> layout.getLineLeft(line)
            index == runs.lastIndex && !left -> layout.getLineRight(line)
            left -> layout.getPrimaryHorizontal(if (atStart) runs[index - 1].start else runs[index - 1].end)
            else -> layout.getPrimaryHorizontal(if (atStart) runs[index + 1].start else runs[index + 1].end)
        }
    }

    fun visibleEnd(line: Int) = trimEnd(layout.getLineEnd(line), layout.getLineStart(line))
    private fun trimEnd(end: Int, start: Int): Int {
        var result = end
        while (result > start && isEndSpace(layout.text[result - 1])) result--
        return result
    }
    private fun isEndSpace(c: Char) = c == ' ' || c == '\n' || c == '\u1680' ||
        (c in '\u2000'..'\u200A' && c != '\u2007') || c == '\u205F' || c == '\u3000'
    private data class Run(val start: Int, val end: Int, val rtl: Boolean)
}
