package app.smallthingz.reverb

/** A queued display frame cannot cross Stop/restart, destination handoff, or Service teardown. */
internal fun visualizationFrameMayDeliver(
    frameCommandGeneration: Long,
    frameContinuityGeneration: Long,
    currentCommandGeneration: Long,
    currentContinuityGeneration: Long,
    serviceDestroying: Boolean,
): Boolean = !serviceDestroying && frameCommandGeneration == currentCommandGeneration &&
    frameContinuityGeneration == currentContinuityGeneration
