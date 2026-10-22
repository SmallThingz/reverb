/*
 * Cursor stroke policy adapted from AndroidX foundation 1.11.2 TextFieldCursor.kt.
 * Copyright 2021 The Android Open Source Project. Licensed under Apache-2.0.
 */
package app.smallthingz.reverb.nativeui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.floor

/** Source integer stroke; native Editor still owns cursor placement and blink timing. */
internal class NativeEditableCursorDrawable(thicknessPx: Float, color: Int) : Drawable() {
    private var stroke = floor(thicknessPx).coerceAtLeast(1f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        this.color = color
        strokeWidth = stroke
    }
    private var deferUntilAfterText = false
    private var requestedInTextDraw = false
    private val requestedBounds = Rect()
    private val requestedClip = Rect()

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
        if (deferUntilAfterText) {
            // Editor alone decides whether the caret is visible in this pass (including blink).
            requestedInTextDraw = canvas.getClipBounds(requestedClip)
            requestedBounds.set(bounds)
            return
        }
        drawStroke(canvas, bounds)
    }

    fun beginTextDraw() {
        requestedInTextDraw = false
        deferUntilAfterText = true
    }

    fun drawAfterText(canvas: Canvas, compoundPaddingLeft: Int, extendedPaddingTop: Int) {
        if (!requestedInTextDraw) return
        val saved = canvas.save()
        try {
            // Both owners use TOP gravity, no marquee and no vertical scrolling. TextView adds
            // exactly this translation before Editor draws; incoming Canvas already has scrollX.
            // Keep the parent clip and intersect the actual cursor-local clip from that draw pass.
            canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())
            canvas.clipRect(requestedClip)
            drawStroke(canvas, requestedBounds)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    fun endTextDraw() {
        requestedInTextDraw = false
        deferUntilAfterText = false
    }

    private fun drawStroke(canvas: Canvas, rect: Rect) {
        // Integer bounds put an odd-width stroke at n+0.5 and an even-width stroke at n.
        val x = rect.left + stroke * .5f
        canvas.drawLine(x, rect.top.toFloat(), x, rect.bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
