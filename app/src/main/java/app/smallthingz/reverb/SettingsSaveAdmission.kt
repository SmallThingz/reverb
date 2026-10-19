package app.smallthingz.reverb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SettingsSaveAdmission(val panelOwner: Any, val editRevision: Long)

internal fun settingsSaveAdmission(
    active: Boolean,
    ownerActive: Boolean,
    restorationConflict: Boolean,
    panelOwner: Any,
    editRevision: Long,
): SettingsSaveAdmission? = if (active && ownerActive && !restorationConflict) {
    SettingsSaveAdmission(panelOwner, editRevision)
} else null

internal enum class SettingsRetentionPreflightResult { READY, UNSAFE, SUPERSEDED }

/** The caller owns UI admission; the worker owns only the durable read and its persistence lock. */
internal suspend fun readSettingsRetentionPreflight(
    admission: SettingsSaveAdmission,
    read: () -> Boolean,
    currentAdmission: () -> SettingsSaveAdmission?,
): SettingsRetentionPreflightResult {
    val safe = withContext(Dispatchers.IO) { read() }
    // Back on the caller dispatcher, before any draft is consumed or durable work admitted.
    if (currentAdmission() != admission) return SettingsRetentionPreflightResult.SUPERSEDED
    return if (safe) SettingsRetentionPreflightResult.READY else SettingsRetentionPreflightResult.UNSAFE
}
