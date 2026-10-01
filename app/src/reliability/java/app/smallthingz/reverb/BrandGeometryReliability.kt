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
                for (y in 0 until 512) for (x in 0 until 512) {
                    // Echoes stay translucent; primary opaque strokes own optical alignment.
                    if (Color.alpha(bitmap.getPixel(x, y)) >= 240) {
                        left = minOf(left, x); right = maxOf(right, x)
                        top = minOf(top, y); bottom = maxOf(bottom, y)
                        count++; xSum += x + 0.5; ySum += y + 0.5
                    }
                }
                check(count > 10_000) { "$theme/$name: primary glyph missing" }
                check(abs((left + right + 1) * 0.5 - 256) <= 2 && abs((top + bottom + 1) * 0.5 - 256) <= 2) {
                    "$theme/$name: primary glyph bounds are off-center"
                }
                check(abs(xSum / count - 256) <= 4 && abs(ySum / count - 256) <= 4) {
                    "$theme/$name: primary glyph visual mass is off-center"
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
