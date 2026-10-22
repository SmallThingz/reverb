/*
 * Line-height and empty-line metric rules derived from AndroidX ui-text 1.11.2.
 * Copyright 2019 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.nativeui.text

import android.graphics.Paint
import android.text.style.LineHeightSpan
import app.smallthingz.nativeui.text.style.LineHeightStyle
import kotlin.math.abs
import kotlin.math.ceil

internal class NativeLineHeightSpan(
    val height: Float,
    private val endIndex: Int,
    val style: LineHeightStyle,
    val trimFirst: Boolean = style.trim == LineHeightStyle.Trim.Both || style.trim == LineHeightStyle.Trim.FirstLineTop,
    val trimLast: Boolean = style.trim == LineHeightStyle.Trim.Both || style.trim == LineHeightStyle.Trim.LastLineBottom,
) : LineHeightSpan {
    private var initialized = false
    private var firstAscent = 0
    private var ascent = 0
    private var descent = 0
    private var lastDescent = 0
    var firstAscentDiff = 0
        private set
    var lastDescentDiff = 0
        private set

    override fun chooseHeight(text: CharSequence, start: Int, end: Int,
                              spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
        if (fm.descent - fm.ascent <= 0) return
        val first = start == 0
        val last = end == endIndex
        if (first && last && trimFirst && trimLast && style.mode != LineHeightStyle.Mode.Tight) return
        if (!initialized) {
            initialized = true
            val current = fm.descent - fm.ascent
            val requested = ceil(height).toInt()
            val difference = requested - current
            if (style.mode == LineHeightStyle.Mode.Minimum && difference <= 0) {
                ascent = fm.ascent; descent = fm.descent
                firstAscent = ascent; lastDescent = descent
            } else {
                val ratio = if (style.alignment.topRatio == -1f) abs(fm.ascent.toFloat()) / current
                    else style.alignment.topRatio
                val below = ceil(difference * if (difference <= 0) ratio else 1f - ratio).toInt()
                descent = fm.descent + below
                ascent = descent - requested
                if (style.mode == LineHeightStyle.Mode.Fixed || difference >= 0) {
                    firstAscent = if (trimFirst) fm.ascent else ascent
                    lastDescent = if (trimLast) fm.descent else descent
                    firstAscentDiff = fm.ascent - firstAscent
                    lastDescentDiff = lastDescent - fm.descent
                } else {
                    firstAscent = if (trimFirst) maxOf(fm.ascent, ascent) else minOf(fm.ascent, ascent)
                    lastDescent = if (trimLast) minOf(fm.descent, descent) else maxOf(fm.descent, descent)
                }
            }
        }
        fm.ascent = if (first) firstAscent else ascent
        fm.descent = if (last) lastDescent else descent
    }
}
