/*
 * Cursor stroke policy adapted from AndroidX foundation 1.11.2 TextFieldCursor.kt.
 * Copyright 2021 The Android Open Source Project. Licensed under Apache-2.0.
 */
package app.smallthingz.reverb.nativeui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.floor

/** Source integer stroke; native Editor still owns cursor placement and blink timing. */
internal class NativeEditableCursorDrawable(thicknessPx: Float, color: Int) : Drawable() {
    private var stroke = floor(thicknessPx).coerceAtLeast(1f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        this.color = color
        strokeWidth = stroke
    }

    override fun getIntrinsicWidth() = stroke.toInt()
    override fun getIntrinsicHeight() = 1

    fun bind(thicknessPx: Float, color: Int) {
        val nextStroke = floor(thicknessPx).coerceAtLeast(1f)
        if (stroke == nextStroke && paint.color == color) return
        stroke = nextStroke
        paint.strokeWidth = nextStroke
        paint.color = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        // Integer bounds put an odd-width stroke at n+0.5 and an even-width stroke at n.
        val x = bounds.left + stroke * .5f
        canvas.drawLine(x, bounds.top.toFloat(), x, bounds.bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
