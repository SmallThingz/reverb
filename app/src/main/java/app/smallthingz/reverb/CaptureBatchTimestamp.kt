package app.smallthingz.reverb

/**
 * Approximate wall-clock start of a delivered batch, shared by gated and ungated history.
 * This is not AudioRecord hardware-clock proof; calibration keeps its separate monotonic mapping.
 */
internal fun captureBatchStartedAtMillis(
    deliveredAtMillis: Long,
    byteCount: Int,
    frameBytes: Int,
    sampleRate: Int,
): Long {
    require(byteCount >= 0 && frameBytes > 0 && sampleRate > 0)
    require(byteCount % frameBytes == 0)
    val durationMillis = (byteCount / frameBytes).toLong() * 1000L / sampleRate
    return (deliveredAtMillis.coerceAtLeast(0L) - durationMillis).coerceAtLeast(0L)
}
