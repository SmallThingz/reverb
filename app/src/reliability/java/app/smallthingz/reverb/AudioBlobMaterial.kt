package app.smallthingz.reverb

import kotlin.math.pow
import kotlin.math.roundToInt

/** One readable material, including silence: no separately colored badge behind the readout. */
internal const val AUDIO_BLOB_READOUT_INK: Int = -1
internal const val AUDIO_BLOB_READOUT_SUPPORT: Int = -0x929293 // #6d6d6d, > 5:1 against white.
internal const val AUDIO_BLOB_TRANSITION_SUPPORT: Int = -0x8a8a8b // #757575, > 4.5:1 against black and white.
// The native radial blend can rasterize one code below its nominal support color.
private const val AUDIO_BLOB_DARK_INK_SUPPORT: Int = -0x888889 // #777777, with room above the black-ink floor.
internal fun audioBlobReadoutSupport(darkInk: Boolean, transitioning: Boolean): Int = when {
    darkInk -> AUDIO_BLOB_DARK_INK_SUPPORT
    transitioning -> AUDIO_BLOB_TRANSITION_SUPPORT
    else -> AUDIO_BLOB_READOUT_SUPPORT
}

private val transitionSupportLuminance = audioBlobMaterialLuminance(AUDIO_BLOB_TRANSITION_SUPPORT)
private val rasterDarkInkLuminance = audioBlobMaterialLuminance(0xff010101.toInt())
private val rasterLightInkLuminance = audioBlobMaterialLuminance(0xfffefefe.toInt())

private fun blobBlend(a: Int, b: Int, fraction: Float): Int {
    fun channel(shift: Int) = ((a ushr shift and 255) * (1f - fraction) + (b ushr shift and 255) * fraction).roundToInt()
    return -0x1000000 or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private fun blobMaterialSample(active: Int, paused: Int, life: Float, presence: Float,
    silence: Float, fallback: Boolean, radiusFraction: Float): Int {
    val mix = audioBlobColorLife(life, presence)
    val body = blobBlend(paused, active, mix)
    val drain = silence.coerceIn(0f, 1f) * presence * silenceBlobDrainWeight(radiusFraction) * (if (fallback) 1f else mix)
    return blobBlend(body, silenceBlobMutedColor(if (fallback) active else body), drain)
}

/** Conservative channel bounds avoid applying a neutral blend to material that already carries its ink. */
internal fun audioBlobInkNeedsSupport(primary: Int, tertiary: Int, paused: Int, life: Float, presence: Float,
    silence: Float, fallback: Boolean, darkInk: Boolean, radiusFraction: Float,
    covered: Boolean, background: Int,
    gradientMin: Float = 0f, gradientMax: Float = 1f): Boolean {
    // During fallback startup the muted active palette can be brighter than the partially mixed
    // body. Bound both radial endpoints for either ink polarity instead of assuming drain darkens.
    val first = blobBlend(primary, tertiary, gradientMin)
    val last = blobBlend(primary, tertiary, gradientMax)
    val a = blobMaterialSample(first, paused, life, presence, silence, fallback, 0f)
    val b = blobMaterialSample(last, paused, life, presence, silence, fallback, 0f)
    val c = blobMaterialSample(first, paused, life, presence, silence, fallback, radiusFraction)
    val d = blobMaterialSample(last, paused, life, presence, silence, fallback, radiusFraction)
    fun channel(shift: Int): Int {
        if (!darkInk) return maxOf(maxOf(a ushr shift and 255, b ushr shift and 255),
            maxOf(c ushr shift and 255, d ushr shift and 255))
        val material = minOf(minOf(a ushr shift and 255, b ushr shift and 255),
            minOf(c ushr shift and 255, d ushr shift and 255))
        return if (covered) material else minOf(material, background ushr shift and 255)
    }
    val bound = -0x1000000 or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    fun safe(color: Int): Boolean {
        // Paint alpha truncation and intermediate channel rounding can move the raster two codes
        // beyond the scalar sample; fully covered glyph samples can be one code off their ink.
        fun rasterChannel(shift: Int) = ((color ushr shift and 255) + if (darkInk) -2 else 2).coerceIn(0, 255)
        val raster = -0x1000000 or (rasterChannel(16) shl 16) or (rasterChannel(8) shl 8) or rasterChannel(0)
        val luminance = audioBlobMaterialLuminance(raster)
        return if (darkInk) (luminance + .05) / (rasterDarkInkLuminance + .05) >= 4.5
            else (rasterLightInkLuminance + .05) / (luminance + .05) >= 4.5
    }
    return !safe(bound) || (!covered && !safe(background))
}

internal fun audioBlobColorLife(life: Float, presence: Float): Float {
    val t = ((life - .36f) / .5f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t) * presence
}

/** Sample the readout region, including the actual silence drain, before choosing ink polarity. */
internal fun audioBlobUsesDarkInk(
    primary: Int, tertiary: Int, paused: Int, life: Float, presence: Float,
    silence: Float = 0f, fallback: Boolean = false,
): Boolean {
    // Most ink spans the inner half-radius. Match each renderer's existing compositing equation;
    // the measured support still protects local gradient variation at individual glyphs.
    val sampled = blobMaterialSample(blobBlend(primary, tertiary, .5f), paused, life, presence, silence, fallback, .5f)
    return audioBlobMaterialLuminance(sampled) >= transitionSupportLuminance
}

internal fun audioBlobMaterialLuminance(color: Int): Double {
    fun linear(channel: Int): Double {
        val value = channel / 255.0
        return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
    }
    return .2126 * linear(color ushr 16 and 255) + .7152 * linear(color ushr 8 and 255) +
        .0722 * linear(color and 255)
}

/** Keep the theme's full color; measured readout support, not a body-wide cap, owns legibility. */
internal fun audioBlobMaterialColor(color: Int): Int = color or -0x1000000
