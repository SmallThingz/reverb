package app.smallthingz.reverb

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToLong

private val BYTES_IN_MEGABYTE = 1024L * 1024L
private val retentionSizeFormatter =
    DecimalFormat(FORMAT_RETENTION_SIZE_MIB, DecimalFormatSymbols(Locale.US))
data class SettingsSnapshot(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val retentionMode: RetentionMode = RetentionMode.TIME,
    val oneShotRetentionTime: Int = 0,
    val oneShotRetentionSizeBytes: Long = 0L,
    val loopingRetentionTime: Int = 0,
    val loopingRetentionSizeBytes: Long = 0L,
    val format: ExportFormat? = null,
    val codec: ExportCodec? = null,
    val sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
    val source: AudioSourceMode? = null,
    val channelMode: ChannelMode? = null,
    val route: InputRouteMode? = null,
    val sampleRate: Int = 0,
    val exportDirectoryUri: String? = null,
    val wakeLockEnabled: Boolean = false,
    val flacCompression: Int = DEFAULT_FLAC_COMPRESSION,
    val quickSaveEnabled: Boolean = true,
    val quickSaveSeconds: Int = DEFAULT_QUICK_SAVE_SECONDS,
    val silenceGate: String = encodeSilenceGate(SilenceGateConfig()),
    val silenceHistogram: String? = null,
)

private val SETTINGS_ALWAYS_WRITTEN_PREFERENCE_KEYS = listOf(
    PrefKey.RETENTION_MODE,
    PrefKey.ONE_SHOT_RETENTION_SECONDS,
    PrefKey.ONE_SHOT_AUDIO_MEMORY_SIZE,
    PrefKey.RETENTION_SECONDS,
    PrefKey.AUDIO_MEMORY_SIZE,
    PrefKey.RETENTION_CONFIG_DIGEST,
    PrefKey.OUTPUT_FORMAT,
    PrefKey.OUTPUT_CODEC,
    PrefKey.FLAC_COMPRESSION_LEVEL,
    PrefKey.QUICK_SAVE_ENABLED,
    PrefKey.QUICK_SAVE_SECONDS,
    PrefKey.SILENCE_GATE,
    PrefKey.SILENCE_CALIBRATION_HISTOGRAM,
    PrefKey.PCM_SAMPLE_FORMAT,
    PrefKey.AUDIO_SOURCE,
    PrefKey.CHANNEL_MODE,
    PrefKey.INPUT_ROUTE,
    PrefKey.SAMPLE_RATE,
    PrefKey.WAKE_LOCK_ENABLED,
    PrefKey.THEME_MODE,
    PrefKey.EXPORT_DIRECTORY_URI,
)

internal fun settingsPreferenceRollbackKeys(
    invalidateCachedOneShotFull: Boolean,
): List<PrefKey> = if (invalidateCachedOneShotFull) {
    SETTINGS_ALWAYS_WRITTEN_PREFERENCE_KEYS + PrefKey.QUICK_TILE_ONE_SHOT_FULL
} else {
    SETTINGS_ALWAYS_WRITTEN_PREFERENCE_KEYS
}

internal fun settingsPreferenceRollbackSnapshot(
    rawPreferences: Map<String, *>,
    invalidateCachedOneShotFull: Boolean,
    coordinatedOneShotFullSnapshot: DurablePreferenceValueSnapshot? = null,
): Map<PrefKey, DurablePreferenceValueSnapshot> =
    settingsPreferenceRollbackKeys(invalidateCachedOneShotFull).associateWith { key ->
        if (key == PrefKey.QUICK_TILE_ONE_SHOT_FULL && coordinatedOneShotFullSnapshot != null) {
            coordinatedOneShotFullSnapshot
        } else {
            durablePreferenceValueSnapshot(rawPreferences, key)
        }
    }

internal fun shouldInvalidateCachedOneShotFull(
    previousMode: RetentionMode,
    newMode: RetentionMode,
    previousTimeSeconds: Int,
    newTimeSeconds: Int,
    previousSizeBytes: Long,
    newSizeBytes: Long,
    previousSampleRate: Int,
    newSampleRate: Int,
    previousChannelMode: ChannelMode?,
    newChannelMode: ChannelMode,
    previousSampleFormat: PcmSampleFormat,
    newSampleFormat: PcmSampleFormat,
): Boolean {
    if (previousMode != newMode) return true
    if (
        previousSampleRate != newSampleRate ||
        previousChannelMode != newChannelMode ||
        previousSampleFormat != newSampleFormat
    ) return true
    return when (newMode) {
        RetentionMode.TIME -> newTimeSeconds > previousTimeSeconds
        RetentionMode.SIZE -> newSizeBytes > previousSizeBytes
    }
}

internal fun settingsServiceBindingCallbackIsCurrent(
    callbackGeneration: Long,
    currentGeneration: Long,
    bindingOwned: Boolean,
): Boolean = bindingOwned && callbackGeneration == currentGeneration

internal fun settingsShouldOwnServiceBinding(active: Boolean, persisting: Boolean): Boolean =
    active || persisting

internal fun settingsEditedDuringPersistence(submittedRevision: Long, currentRevision: Long): Boolean =
    submittedRevision != currentRevision

internal data class SettingsRetentionModeChange(
    val mode: RetentionMode,
    val preserveInputDrafts: Boolean,
)

internal fun settingsRetentionModeChange(
    currentMode: RetentionMode,
    requestedMode: RetentionMode,
): SettingsRetentionModeChange? =
    if (currentMode == requestedMode) null
    else SettingsRetentionModeChange(requestedMode, preserveInputDrafts = true)

internal fun settingsSnapshotHasUnsavedChanges(
    durableSnapshot: SettingsSnapshot,
    currentSnapshot: SettingsSnapshot,
    invalidRetentionInput: Boolean,
): Boolean = durableSnapshot != currentSnapshot || invalidRetentionInput

internal fun settingsSaveMayContinueAfterCommit(hasUnsavedChanges: Boolean): Boolean =
    !hasUnsavedChanges

internal fun settingsShouldRehydrate(
    active: Boolean,
    persisting: Boolean,
    hasUnsavedChanges: Boolean,
): Boolean = active && !persisting && !hasUnsavedChanges

internal fun settingsHydrationMayApply(
    expectedEditRevision: Long,
    currentEditRevision: Long,
): Boolean = expectedEditRevision == currentEditRevision

internal fun settingsSupersededHydrationMayReleaseInteraction(
    active: Boolean,
    persisting: Boolean,
    hasUnsavedChanges: Boolean,
): Boolean = active && !persisting && hasUnsavedChanges

internal fun settingsMoveAvailabilityResultIsCurrent(
    active: Boolean,
    requestGeneration: Int,
    currentGeneration: Int,
): Boolean = active && requestGeneration == currentGeneration

internal suspend fun <T> runCommittedSettingsMove(
    persistSettings: suspend () -> Boolean,
    move: suspend () -> T,
    onTerminal: (Result<T>) -> Unit,
): Boolean = withContext(NonCancellable) {
    if (!persistSettings()) return@withContext false
    val result = try {
        Result.success(move())
    } catch (error: Exception) {
        Result.failure(error)
    }
    onTerminal(result)
    true
}


internal fun parseRetentionTimeSeconds(value: String): Int? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    if (':' in trimmed) return parseDurationInput(trimmed)
    val minutes = trimmed.replace(',', '.').toDoubleOrNull() ?: return null
    if (!minutes.isFinite() || minutes < 0.0) return null
    val seconds = minutes * 60.0
    if (seconds > Int.MAX_VALUE.toDouble()) return null
    return seconds.roundToLong().toInt()
}

internal fun formatRetentionTimeInput(seconds: Long): String {
    val total = seconds.coerceAtLeast(0L)
    val hours = total / 3600L
    val minutes = total % 3600L / 60L
    val secs = total % 60L
    return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
}

internal fun formatRetentionMinutesEstimate(seconds: Long): String {
    if (seconds <= 0L) return "0"
    return if (seconds % 60L == 0L) {
        (seconds / 60L).toString()
    } else {
        String.format(Locale.US, "%.1f", seconds / 60.0)
    }
}


internal fun bytesToMegabytes(bytes: Long): Double =
    bytes.coerceAtLeast(0L) / BYTES_IN_MEGABYTE.toDouble()

internal fun rawMegabytesToBytes(memoryInMegabytes: Double): Long {
    if (memoryInMegabytes <= 0.0) return 0L
    if (memoryInMegabytes >= Long.MAX_VALUE / BYTES_IN_MEGABYTE.toDouble()) return Long.MAX_VALUE
    return (memoryInMegabytes * BYTES_IN_MEGABYTE.toDouble()).roundToLong()
}

internal fun parseRetentionSizeMib(value: String): Double? =
    value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

internal fun formatRetentionSizeBytes(bytes: Long): String =
    retentionSizeFormatter.format(bytesToMegabytes(bytes))
