package app.smallthingz.reverb

/** Historical renderer compatibility only; live gestures use the stationary waveform coordinates. */
internal fun rangeTimelineDragDeltaPx(
    initialMarkerCenterPx: Float,
    currentMarkerCenterPx: Float,
    downLocalX: Float,
    currentLocalX: Float,
): Float = (currentMarkerCenterPx + currentLocalX) - (initialMarkerCenterPx + downLocalX)
