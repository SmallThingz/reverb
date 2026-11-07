package app.smallthingz.reverb

import kotlin.math.sin
import kotlin.math.sqrt

/** Render-only energy. Gate suppression calms the material without changing capture analysis. */
internal fun audioBlobMotionEnergy(activity: Float, low: Float, high: Float, silence: Float): Float =
    sqrt(maxOf(activity, low * .9f + high * .7f).coerceIn(0f, 1f)) *
        (1f - .92f * silence.coerceIn(0f, 1f))

/** Broad liquid folds and a smaller treble ripple, bounded inside the existing render footprint. */
internal fun audioBlobFold(angle: Float, time: Float, energy: Float, low: Float, high: Float): Float =
    energy * (sin(angle * 3f + time * .78f) * (.035f + .035f * low) +
        sin(angle * 7f - time * .39f + 1.8f) * (.008f + .018f * high))

