package app.smallthingz.reverb

/** A closed incident panel must be outside the viewport before its composition is removed. */
internal fun incidentsPanelTranslationX(viewportWidth: Float, openProgress: Float): Float =
    viewportWidth.coerceAtLeast(0f) * (1f - openProgress.coerceIn(0f, 1f))

