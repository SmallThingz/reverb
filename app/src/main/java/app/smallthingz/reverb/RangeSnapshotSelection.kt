package app.smallthingz.reverb

/** Float is presentation geometry; its terminal coordinate identifies the exact snapshot end. */
private fun resolveSnapshotCoordinate(availableSeconds: Double, value: Float): Double =
    if (value == availableSeconds.toFloat()) availableSeconds
    else value.toDouble().coerceIn(0.0, availableSeconds)

private fun validSnapshotSelection(availableSeconds: Double, startSeconds: Float, endSeconds: Float): Boolean =
    availableSeconds.isFinite() && availableSeconds > 0.0 && startSeconds.isFinite() && endSeconds.isFinite()

internal fun rangeSnapshotStartSeconds(
    availableSeconds: Double,
    startSeconds: Float,
    endSeconds: Float,
): Double = if (validSnapshotSelection(availableSeconds, startSeconds, endSeconds)) {
    resolveSnapshotCoordinate(availableSeconds, startSeconds)
} else 0.0

internal fun rangeSnapshotEndSeconds(
    availableSeconds: Double,
    startSeconds: Float,
    endSeconds: Float,
): Double = if (validSnapshotSelection(availableSeconds, startSeconds, endSeconds)) {
    maxOf(resolveSnapshotCoordinate(availableSeconds, startSeconds), resolveSnapshotCoordinate(availableSeconds, endSeconds))
} else 0.0
