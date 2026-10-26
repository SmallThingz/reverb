package app.smallthingz.reverb.nativeui

import kotlin.math.pow

/** Byte channels share one lazily initialized table; its mutable storage never escapes. */
private object SrgbChannelTable {
    val values = DoubleArray(256) { channel ->
        val value = channel / 255.0
        if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
}

internal fun srgbLinearChannel(channel: Int): Double = SrgbChannelTable.values[channel]

/** Relative luminance of RGB bytes; callers composite alpha before evaluating contrast. */
internal fun srgbLuminance(color: Int): Double =
    0.2126 * srgbLinearChannel(color ushr 16 and 255) +
        0.7152 * srgbLinearChannel(color ushr 8 and 255) +
        0.0722 * srgbLinearChannel(color and 255)
