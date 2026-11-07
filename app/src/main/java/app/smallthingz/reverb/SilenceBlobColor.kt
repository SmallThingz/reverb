package app.smallthingz.reverb

import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.exp

/** A finite transition that settles even when the microphone supplies digital zero. */
internal fun advanceSilenceBlob(current: Float, target: Float, dtSeconds: Float): Float {
    return advanceBlobTransition(current, target, dtSeconds, .32f, .10f)
}

/** Capture presence retains its existing timing independently of the softer silence feedback. */
internal fun advanceBlobPresence(current: Float, target: Float, dtSeconds: Float): Float =
    advanceBlobTransition(current, target, dtSeconds, .18f, .12f)

private fun advanceBlobTransition(current: Float, target: Float, dtSeconds: Float,
    riseSeconds: Float, fallSeconds: Float): Float {
    if (current == target) return target
    val duration = if (target > current) riseSeconds else fallSeconds
    val next = current + (target - current) * (1f - exp(-dtSeconds.coerceIn(0f, .1f) / duration))
    return if (abs(next - target) <= .002f) target else next
}

/** Drain the rim first, leaving a small, subdued core of the original color and light. */
internal fun silenceBlobDrainWeight(radiusFraction: Float): Float {
    val t = (radiusFraction / .7f).coerceIn(0f, 1f)
    return .35f + .65f * t * t * (3f - 2f * t)
}

internal fun silenceBlobDrainMaskColor(radiusFraction: Float): Int =
    ((silenceBlobDrainWeight(radiusFraction) * 255f).roundToInt() shl 24) or 0x00FFFFFF

/** A colored, visible quiet rim; matches the shader without reducing material opacity. */
internal fun silenceBlobMutedColor(color:Int):Int {
    val red=color ushr 16 and 255
    val green=color ushr 8 and 255
    val blue=color and 255
    val luminance=.2126*red+.7152*green+.0722*blue
    fun channel(value:Int)=((value+(luminance-value)*.42)*.72).roundToInt().coerceIn(0,255)
    return (color and -0x1000000) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}
