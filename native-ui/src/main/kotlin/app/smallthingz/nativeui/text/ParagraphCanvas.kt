/*
 * Clip traversal rule follows AndroidX TextAndroidCanvas (ui-text 1.11.2).
 * Copyright 2023 The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package app.smallthingz.nativeui.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.fonts.Font
import android.graphics.text.MeasuredText
import android.text.Layout

/**
 * Native Layout skips clipped lines before drawing. The caller already clips the paragraph;
 * keep Layout's traversal independent of that viewport so shadows/fallback glyph edges survive.
 * Only Android Layout and EmojiCompat text drawing enter this adapter, not arbitrary app graphics.
 */
internal class ParagraphCanvas : Canvas() {
    companion object {
        private val threadCanvas = ThreadLocal<ParagraphCanvas>()
        fun draw(layout: Layout, value: Canvas) {
            val adapter = threadCanvas.get() ?: ParagraphCanvas().also(threadCanvas::set)
            val previous = adapter.target
            adapter.target = value
            try { layout.draw(adapter) } finally { adapter.target = previous }
        }
    }
    private var target: Canvas? = null
    private val canvas: Canvas get() = checkNotNull(target)
    override fun getClipBounds(bounds: Rect): Boolean {
        val visible = canvas.getClipBounds(bounds)
        if (visible) bounds.set(0, 0, bounds.width(), Int.MAX_VALUE)
        return visible
    }
    override fun getWidth() = canvas.width
    override fun getHeight() = canvas.height
    override fun isOpaque() = canvas.isOpaque
    override fun getDensity() = canvas.density
    override fun save() = canvas.save()
    override fun restore() = canvas.restore()
    override fun restoreToCount(saveCount: Int) = canvas.restoreToCount(saveCount)
    override fun getSaveCount() = canvas.saveCount
    override fun translate(dx: Float, dy: Float) = canvas.translate(dx, dy)
    override fun scale(sx: Float, sy: Float) = canvas.scale(sx, sy)
    override fun rotate(degrees: Float) = canvas.rotate(degrees)
    override fun concat(matrix: Matrix?) = canvas.concat(matrix)
    override fun clipRect(rect: Rect) = canvas.clipRect(rect)
    override fun clipRect(rect: RectF) = canvas.clipRect(rect)
    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float) =
        canvas.clipRect(left, top, right, bottom)
    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) =
        canvas.drawRect(left, top, right, bottom, paint)
    override fun drawRect(rect: Rect, paint: Paint) = canvas.drawRect(rect, paint)
    override fun drawRect(rect: RectF, paint: Paint) = canvas.drawRect(rect, paint)
    override fun drawPath(path: Path, paint: Paint) = canvas.drawPath(path, paint)
    override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) =
        canvas.drawLine(startX, startY, stopX, stopY, paint)
    override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) =
        canvas.drawBitmap(bitmap, left, top, paint)
    override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) =
        canvas.drawBitmap(bitmap, src, dst, paint)
    override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) =
        canvas.drawBitmap(bitmap, src, dst, paint)
    override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) =
        canvas.drawText(text, index, count, x, y, paint)
    override fun drawText(text: String, x: Float, y: Float, paint: Paint) =
        canvas.drawText(text, x, y, paint)
    override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) =
        canvas.drawText(text, start, end, x, y, paint)
    override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) =
        canvas.drawText(text, start, end, x, y, paint)
    override fun drawTextRun(text: CharArray, index: Int, count: Int, contextIndex: Int,
                             contextCount: Int, x: Float, y: Float, isRtl: Boolean, paint: Paint) =
        canvas.drawTextRun(text, index, count, contextIndex, contextCount, x, y, isRtl, paint)
    override fun drawTextRun(text: CharSequence, start: Int, end: Int, contextStart: Int,
                             contextEnd: Int, x: Float, y: Float, isRtl: Boolean, paint: Paint) =
        canvas.drawTextRun(text, start, end, contextStart, contextEnd, x, y, isRtl, paint)
    override fun drawTextRun(text: MeasuredText, start: Int, end: Int, contextStart: Int,
                             contextEnd: Int, x: Float, y: Float, isRtl: Boolean, paint: Paint) =
        canvas.drawTextRun(text, start, end, contextStart, contextEnd, x, y, isRtl, paint)
    override fun drawGlyphs(glyphIds: IntArray, glyphIdOffset: Int, positions: FloatArray,
                            positionOffset: Int, glyphCount: Int, font: Font, paint: Paint) =
        canvas.drawGlyphs(glyphIds, glyphIdOffset, positions, positionOffset, glyphCount, font, paint)
}
