package app.smallthingz.reverb.nativeui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import smallthingz.ui.text.TextStyle
import smallthingz.ui.text.font.createFontFamilyResolver
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.unit.Density
import smallthingz.ui.unit.sp

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29, 35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParagraphDrawForwardingTest {
    @Test fun measuredMultilineParagraphPaintsIntoTheClippedDestination() {
        val context = RuntimeEnvironment.getApplication()
        val width = 192
        val height = 96
        val clipRight = 65
        val paragraph = ParagraphView(context, TextStyle(fontSize = 24.sp), Density(1f),
            createFontFamilyResolver(context), Color.RED).apply {
            setText("First line\nSecond line")
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            layout(0, 0, width, height)
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.clipRect(0, 0, clipRight, height)
            paragraph.draw(canvas)

            val paintedRows = (0 until height).filter { y ->
                (0 until clipRight).any { x -> Color.alpha(bitmap.getPixel(x, y)) > 0 }
            }
            assertTrue("Expected both lines of the paragraph in the destination", paintedRows.size > 24)
            assertTrue("Expected the paragraph's red ink", paintedRows.any { y ->
                (0 until clipRight).any { x -> bitmap.getPixel(x, y) == Color.RED }
            })
            assertEquals(0, (0 until height).count { y ->
                (clipRight until width).any { x -> Color.alpha(bitmap.getPixel(x, y)) > 0 }
            })
        } finally {
            bitmap.recycle()
        }
    }
}
