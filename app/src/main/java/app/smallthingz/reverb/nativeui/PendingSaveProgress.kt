package app.smallthingz.reverb.nativeui

/** Pending work never claims terminal completion, including stale or malformed progress samples. */
internal fun pendingSaveProgressTarget(previous: Float, sample: Float?, failed: Boolean): Float =
    if (failed) 0f else maxOf(previous, sample?.takeIf { it.isFinite() }?.coerceIn(0f, .99f) ?: 0f)
