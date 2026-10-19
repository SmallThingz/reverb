package app.smallthingz.reverb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/** The same alpha-only artwork feeds the preview, Android add prompt and installed tile. */
internal fun quickSaveTileBitmap(buffer: ReverbService.BufferSlot, amount: Int, unit: QuickSaveUnit,
                                 pixels: Int = 96): Bitmap {
    require(pixels in 24..512 && amount in 1..999)
    val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(pixels / 24f, pixels / 24f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 1.7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    val outline = Path().apply {
        if (buffer == ReverbService.BufferSlot.LOOPING) {
            moveTo(21f, 7f); cubicTo(18f, 0f, 8f, 0f, 3f, 6f)
            moveTo(3f, 6f); lineTo(3f, 2f); moveTo(3f, 6f); lineTo(7f, 6f)
            moveTo(3f, 17f); cubicTo(6f, 24f, 16f, 24f, 21f, 18f)
            moveTo(21f, 18f); lineTo(21f, 22f); moveTo(21f, 18f); lineTo(17f, 18f)
        } else {
            moveTo(3f, 3f); lineTo(3f, 21f); lineTo(21f, 21f)
            moveTo(8f, 4f); lineTo(21f, 4f); lineTo(18f, 1f)
            moveTo(21f, 4f); lineTo(18f, 7f)
        }
    }
    canvas.drawPath(outline, paint)
    paint.style = Paint.Style.FILL
    paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = 10f
    val digits = amount.toString()
    if (paint.measureText(digits) > 15f) paint.textSize *= 15f / paint.measureText(digits)
    canvas.drawText(digits, 12f, 15f, paint)
    paint.textSize = 5f
    canvas.drawText(unit.symbol, 12f, 20f, paint)
    return bitmap
}
