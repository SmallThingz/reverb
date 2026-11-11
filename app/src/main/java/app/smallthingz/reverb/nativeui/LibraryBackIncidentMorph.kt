package app.smallthingz.reverb.nativeui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect

/** One retained geometry owner; sampling/alignment happens once, never in a gesture frame. */
internal class LibraryBackIncidentMorph(density: Float) {
    private val targetPath = ReverbIconPaths.copyHomeIncidentPath()
    private val targetContours = sampleContours(targetPath)
    private val target = targetContours.first()
    private val source = alignedSource(ReverbIconPaths.copyLibraryBackPath(), target, mirrored = false)
    private val mirroredSource = alignedSource(ReverbIconPaths.copyLibraryBackPath(), target, mirrored = true)
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val endpoint = ReverbIconDrawable(ReverbIcon.Incidents, density, 0)

    fun draw(canvas: Canvas, bounds: Rect, progress: Float, tint: Int, rtl: Boolean) {
        if (progress >= 1f) {
            endpoint.bounds = bounds
            endpoint.setTint(tint)
            endpoint.draw(canvas)
            return
        }
        val from = if (rtl) mirroredSource else source
        path.rewind()
        appendContour(from, target, progress)
        // The warning's two negative contours open from zero area inside the arrow shaft.
        for (index in 1 until targetContours.size) appendContour(null, targetContours[index], progress)
        paint.color = tint
        val checkpoint = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(checkpoint)
    }

    private fun appendContour(from: FloatArray?, to: FloatArray, progress: Float) {
        for (index in to.indices step 2) {
            val x = from?.get(index) ?: 12f
            val y = from?.get(index + 1) ?: 12f
            val px = x + (to[index] - x) * progress
            val py = y + (to[index + 1] - y) * progress
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
    }

    private companion object {
        const val POINTS = 128
        fun sampleContours(path: Path): List<FloatArray> {
            val measure = PathMeasure(path, true)
            val point = FloatArray(2)
            val result = ArrayList<FloatArray>(3)
            do {
                val contour = FloatArray(POINTS * 2)
                for (index in 0 until POINTS) {
                    measure.getPosTan(measure.length * index / POINTS, point, null)
                    contour[index * 2] = point[0]
                    contour[index * 2 + 1] = point[1]
                }
                result += contour
            } while (measure.nextContour())
            return result
        }
        fun alignedSource(path: Path, target: FloatArray, mirrored: Boolean): FloatArray {
            val original = sampleContours(path).first()
            if (mirrored) for (index in original.indices step 2) original[index] = 24f - original[index]
            // Minimize travel in the target's winding direction, including RTL reflection.
            val direction = if (area(original) * area(target) < 0f) -1 else 1
            var bestOffset = 0
            var bestCost = Float.POSITIVE_INFINITY
            for (offset in 0 until POINTS) {
                var cost = 0f
                for (index in 0 until POINTS) {
                    val sourceIndex = ((offset + direction * index + POINTS) % POINTS) * 2
                    val dx = original[sourceIndex] - target[index * 2]
                    val dy = original[sourceIndex + 1] - target[index * 2 + 1]
                    cost += dx * dx + dy * dy
                }
                if (cost < bestCost) { bestCost = cost; bestOffset = offset }
            }
            return FloatArray(original.size) { coordinate ->
                val index = (bestOffset + direction * (coordinate / 2) + POINTS) % POINTS
                original[index * 2 + coordinate % 2]
            }
        }
        fun area(points: FloatArray): Float {
            var result = 0f
            for (index in 0 until POINTS) {
                val next = (index + 1) % POINTS
                result += points[index * 2] * points[next * 2 + 1] - points[next * 2] * points[index * 2 + 1]
            }
            return result
        }
    }
}
