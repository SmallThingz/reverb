package app.smallthingz.reverb

/** Durability may outlive its panel; only that panel may finish its UI navigation. */
internal suspend fun runSettingsSaveContinuation(
    submittedOwner: Any,
    persistSettings: suspend () -> Boolean,
    currentVisibleOwner: () -> Any?,
    onSaved: () -> Unit,
): Boolean {
    if (!persistSettings()) return false
    if (currentVisibleOwner() !== submittedOwner) return false
    onSaved()
    return true
}
