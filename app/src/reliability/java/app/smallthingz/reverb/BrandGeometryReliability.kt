package app.smallthingz.reverb

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import kotlin.math.abs

/** Native drawable rendering, isolated from the installed app and its recordings. */
internal fun verifyBrandGeometry(context: Context) {
    check(context.packageName.endsWith(".reliability"))
    val row = IntArray(512)
    val circularMaskRadius = 512.0 / 3
    var sharedAnchor: BrandOpaqueAnchor? = null
    for ((theme, night) in listOf("light" to Configuration.UI_MODE_NIGHT_NO, "dark" to Configuration.UI_MODE_NIGHT_YES)) {
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        }
        val themed = context.createConfigurationContext(configuration)
        for ((name, resource) in listOf("foreground" to R.drawable.ic_launcher_foreground,
            "monochrome" to R.drawable.ic_launcher_monochrome, "splash-start" to R.drawable.reverb_splash_vector)) {
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            try {
                val drawable = requireNotNull(themed.getDrawable(resource))
                drawable.setBounds(0, 0, 512, 512)
                drawable.draw(Canvas(bitmap))
                var left = 512; var right = 0; var top = 512; var bottom = 0
                var count = 0; var xSum = 0.0; var ySum = 0.0
                var mass = 0L; var weightedX = 0.0
                var radiusSquared = 0.0
                for (y in 0 until 512) {
                    bitmap.getPixels(row, 0, 512, 0, y, 512, 1)
                    for (x in 0 until 512) {
                        val alpha = Color.alpha(row[x])
                        mass += alpha
                        weightedX += alpha * (x + 0.5)
                        if (alpha >= 8) {
                            val dx = x + 0.5 - 256; val dy = y + 0.5 - 256
                            radiusSquared = maxOf(radiusSquared, dx * dx + dy * dy)
                        }
                        // Opaque pixels identify the anchor shared by the primary R in all three drawables.
                        if (alpha >= 240) {
                            left = minOf(left, x); right = maxOf(right, x)
                            top = minOf(top, y); bottom = maxOf(bottom, y)
                            count++; xSum += x + 0.5; ySum += y + 0.5
                        }
                    }
                }
                check(count > 10_000) { "$theme/$name: primary glyph missing" }
                val anchor = BrandOpaqueAnchor((left + right + 1) * 0.5, (top + bottom + 1) * 0.5,
                    xSum / count, ySum / count)
                check(abs(anchor.boundsY - 256) <= 2 && abs(anchor.centroidY - 256) <= 4) {
                    "$theme/$name: primary glyph vertical alignment changed"
                }
                val expected = sharedAnchor
                if (expected == null) {
                    sharedAnchor = anchor
                } else {
                    check(abs(expected.boundsX - anchor.boundsX) <= 1 && abs(expected.boundsY - anchor.boundsY) <= 1 &&
                        abs(expected.centroidX - anchor.centroidX) <= 1 && abs(expected.centroidY - anchor.centroidY) <= 1) {
                        "$theme/$name: primary glyph anchor differs from the launcher foreground"
                    }
                }
                check(radiusSquared < circularMaskRadius * circularMaskRadius) {
                    "$theme/$name: a stroke extends beyond the circular adaptive mask"
                }
                if (name != "splash-start") {
                    check(weightedX / mass in 246.0..253.0) { "$theme/$name: full-mark alpha balance changed" }
                }
                File(context.filesDir, "brand-$theme-$name.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}

private data class BrandOpaqueAnchor(
    val boundsX: Double,
    val boundsY: Double,
    val centroidX: Double,
    val centroidY: Double,
)
