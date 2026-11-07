package app.smallthingz.reverb.nativeui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRowContrastTest {
    @Test fun enabledMetadataStaysReadableWhenAnotherRecordingIsSelected() {
        for (dark in listOf(false, true)) {
            val palette = ReverbPalette.fallback(dark)
            val row = LibraryRecordingRow("recording", "Recording", "1:20 - 8.4 MB", "10:30",
                selected = false, selectionActive = true, interactionsEnabled = true, edges = null)
            val frame = LibraryRecordingRowFrame.target(row, palette)
            val background = frame.background
            fun channel(color: Int, shift: Int) = ((color ushr shift) and 255).toDouble() / 255
            fun luminance(color: Int, alpha: Float = 1f): Double {
                fun linear(shift: Int): Double {
                    val value = channel(color, shift) * alpha + channel(background, shift) * (1 - alpha)
                    return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
                }
                return .2126 * linear(16) + .7152 * linear(8) + .0722 * linear(0)
            }
            val ink = luminance(palette.muted, frame.contentAlpha)
            val surface = luminance(background)
            val contrast = (max(ink, surface) + .05) / (min(ink, surface) + .05)
            assertTrue("Library metadata dark=$dark contrast=$contrast", contrast >= 4.5)
        }
    }
}
