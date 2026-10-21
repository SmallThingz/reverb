package app.smallthingz.reverb.nativeui

/** Released RecordingGroupEdges: a singleton is first=true and last=true. */
internal data class ReverbRecordingGroupEdges(val first: Boolean, val last: Boolean)

/** CornerBasedShape fill radii and RecordingCard's independently constructed raw-dp outline. */
internal data class ReverbRecordingGroupGeometry(
    val topRadius: Float,
    val bottomRadius: Float,
    val strokeWidth: Float,
    val strokeInset: Float,
    val strokeRadius: Float,
    val cornersFit: Boolean,
)

internal fun reverbRecordingGroupGeometry(
    width: Float,
    height: Float,
    density: Float,
    edges: ReverbRecordingGroupEdges,
): ReverbRecordingGroupGeometry {
    val requestedTop = if (edges.first) 16f * density else 0f
    val requestedBottom = if (edges.last) 16f * density else 0f
    val total = requestedTop + requestedBottom
    val minimum = minOf(width, height).coerceAtLeast(0f)
    val scale = if (total > minimum && total > 0f) minimum / total else 1f
    val top = requestedTop * scale
    val bottom = requestedBottom * scale
    val inset = density / 2f
    // Preserve the released Float subtraction order, including constrained dimensions.
    val strokeRadius = (16f * density - inset)
        .coerceAtMost(((width - inset) - inset) / 2f)
        .coerceAtMost(((height - inset) - inset) / 2f)
        .coerceAtLeast(0f)
    return ReverbRecordingGroupGeometry(top, bottom, density, inset, strokeRadius,
        top + top <= width && bottom + bottom <= width && top + bottom <= height)
}
