package app.smallthingz.reverb

/** Only a complete retained draft can bypass clean-panel durable hydration after a save. */
internal fun settingsRetainedDraftMayReleaseInteraction(
    active: Boolean,
    hydrated: Boolean,
    persisting: Boolean,
    hasUnsavedChanges: Boolean,
): Boolean = active && hydrated && !persisting && hasUnsavedChanges
