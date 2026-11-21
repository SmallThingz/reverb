package app.smallthingz.reverb

import kotlin.math.sqrt

/** Render-only energy. Gate suppression calms the material without changing capture analysis. */
internal fun audioBlobMotionEnergy(activity: Float, low: Float, high: Float, silence: Float): Float =
    sqrt(maxOf(activity, low * .9f + high * .7f).coerceIn(0f, 1f)) *
        (1f - .92f * silence.coerceIn(0f, 1f))

/** Broad liquid folds and a smaller treble ripple, bounded inside the existing render footprint. */
internal fun audioBlobFold(h3: Float, h7: Float, energy: Float, low: Float, high: Float): Float =
    energy * (h3 * (.035f + .035f * low) + h7 * (.008f + .018f * high))

