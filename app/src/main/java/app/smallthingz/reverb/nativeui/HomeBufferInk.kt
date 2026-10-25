package app.smallthingz.reverb.nativeui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Home's indication is a foreground: it washes both the text and the surface beneath it. */
internal class HomeBufferInk {
    private var field = 0
    private var indication = 0
    private var endpointFloor = 4.5
    private var targetFloor = 4.5
    private var background = 0
    private var backgroundLow = 0.0
    private var backgroundHigh = 0.0

    fun target(field: Int, indication: Int, fill: Int, foreground: Int) {
        this.field = field
        this.indication = indication
        prepareBackground(fill)
        targetFloor = min(4.5, contrastBound(foreground))
        // A reversal retains the intentional dimming of either unfinished endpoint.
        endpointFloor = min(endpointFloor, targetFloor)
    }

    fun foreground(fill: Int, animated: Int, running: Boolean): Int {
        if (!running) {
            endpointFloor = targetFloor
            return animated
        }
        prepareBackground(fill)
        if (contrastBound(animated) >= endpointFloor) return animated
        val dark = animated and -0x1000000
        val light = dark or 0xFFFFFF
        val darkContrast = contrastBound(dark)
        val lightContrast = contrastBound(light)
        // Fixed foreground overlays can make 4.5 unattainable mid-transition. The guaranteed
        // floor is min(endpointFloor, max(darkContrast, lightContrast)), not an impossible 4.5.
        return if (darkContrast >= lightContrast) dark else light
    }

    private fun prepareBackground(fill: Int) {
        background = composite(fill, field, fill ushr 24)
        val overlaid = overlay(background)
        backgroundLow = luminance(channelMinimum(background, overlaid))
        backgroundHigh = luminance(channelMaximum(background, overlaid))
    }

    private fun contrastBound(foreground: Int): Double {
        val ink = composite(foreground, background, foreground ushr 24)
        val overlaid = overlay(ink)
        val inkLow = luminance(channelMinimum(ink, overlaid))
        val inkHigh = luminance(channelMaximum(ink, overlaid))
        return when {
            inkLow >= backgroundHigh -> (inkLow + .05) / (backgroundHigh + .05)
            backgroundLow >= inkHigh -> (backgroundLow + .05) / (inkHigh + .05)
            else -> 1.0
        }
    }

    // Hover/focus and press are the only Home state layers, each bounded by 26/255.
    // Every channel stays between its unoverlaid value and this maximum combined overlay.
    // Taking separate channel extrema also bounds tinted palettes whose channels move oppositely.
    private fun overlay(color: Int) = composite(indication, composite(indication, color, 26), 26)

    private fun channelMinimum(a: Int, b: Int): Int =
        (min(a ushr 16 and 255, b ushr 16 and 255) shl 16) or
            (min(a ushr 8 and 255, b ushr 8 and 255) shl 8) or min(a and 255, b and 255)

    private fun channelMaximum(a: Int, b: Int): Int =
        (max(a ushr 16 and 255, b ushr 16 and 255) shl 16) or
            (max(a ushr 8 and 255, b ushr 8 and 255) shl 8) or max(a and 255, b and 255)

    private fun composite(front: Int, back: Int, alpha: Int): Int {
        fun channel(shift: Int): Int =
            (((front ushr shift and 255) * alpha + (back ushr shift and 255) * (255 - alpha) + 127) / 255) shl shift
        return -0x1000000 or channel(16) or channel(8) or channel(0)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = (color ushr shift and 255) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
}
