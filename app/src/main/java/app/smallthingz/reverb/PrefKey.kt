package app.smallthingz.reverb

import android.content.SharedPreferences

enum class PrefKey {
    AUDIO_MEMORY_ENABLED,
    CAPTURE_BUFFER_SLOT,
    QUICK_TILE_ONE_SHOT_FULL,
    QUICK_TILE_ONE_SHOT_DURATION_MILLIS,
    QUICK_TILE_LOOPING_DURATION_MILLIS,
    RANGE_EXPORT_ONE_SHOT_SELECTION_MILLIS,
    RANGE_EXPORT_ONE_SHOT_END_OFFSET_MILLIS,
    RANGE_EXPORT_LOOPING_SELECTION_MILLIS,
    RANGE_EXPORT_LOOPING_END_OFFSET_MILLIS,
    ONBOARDING_SHOWN,
    AUDIO_MEMORY_SIZE,
    WAKE_LOCK_ENABLED,
    RETENTION_MODE,
    RETENTION_CONFIG_DIGEST,
    RETENTION_SECONDS,
    ONE_SHOT_RETENTION_SECONDS,
    ONE_SHOT_AUDIO_MEMORY_SIZE,
    EXPORT_DIRECTORY_URI,
    OUTPUT_FORMAT,
    OUTPUT_CODEC,
    PCM_SAMPLE_FORMAT,
    AUDIO_SOURCE,
    CHANNEL_MODE,
    INPUT_ROUTE,
    SAMPLE_RATE,
    THEME_MODE,
    PENDING_RECORDING_DELETIONS,
    PENDING_OUTPUT_CLEANUP,
    VERIFIED_EXPORT_STAGING,
}

fun SharedPreferences.getString(key: PrefKey, default: String?): String? = getString(key.name, default)
fun SharedPreferences.getInt(key: PrefKey, default: Int): Int = getInt(key.name, default)
fun SharedPreferences.getLong(key: PrefKey, default: Long): Long = getLong(key.name, default)
fun SharedPreferences.getBoolean(key: PrefKey, default: Boolean): Boolean = getBoolean(key.name, default)
fun SharedPreferences.getStringSet(key: PrefKey, default: Set<String>?): Set<String>? = getStringSet(key.name, default)
fun SharedPreferences.contains(key: PrefKey): Boolean = contains(key.name)

internal inline fun <T> safePreferenceRead(default: T, read: () -> T): T =
    try {
        read()
    } catch (_: ClassCastException) {
        default
    }

internal inline fun <T> requireDurablePreference(
    present: Boolean,
    absent: T,
    label: String,
    read: () -> T,
): T {
    if (!present) return absent
    return try {
        read()
    } catch (error: ClassCastException) {
        throw IllegalStateException("Unreadable durable preference $label", error)
    }
}

internal fun SharedPreferences.safeString(key: PrefKey, default: String? = null): String? =
    safePreferenceRead(default) { getString(key, default) }

internal fun SharedPreferences.safeInt(key: PrefKey, default: Int): Int =
    safePreferenceRead(default) { getInt(key, default) }

internal fun SharedPreferences.safeLong(key: PrefKey, default: Long): Long =
    safePreferenceRead(default) { getLong(key, default) }

internal fun SharedPreferences.safeBoolean(key: PrefKey, default: Boolean): Boolean =
    safePreferenceRead(default) { getBoolean(key, default) }

internal fun SharedPreferences.requireDurableStringSet(key: PrefKey): Set<String> =
    requireDurablePreference(
        present = contains(key),
        absent = emptySet(),
        label = key.name,
    ) {
        getStringSet(key, emptySet())?.toSet() ?: emptySet()
    }
fun SharedPreferences.Editor.putString(key: PrefKey, value: String): SharedPreferences.Editor =
    putString(key.name, value)
fun SharedPreferences.Editor.putInt(key: PrefKey, value: Int): SharedPreferences.Editor = putInt(key.name, value)
fun SharedPreferences.Editor.putLong(key: PrefKey, value: Long): SharedPreferences.Editor = putLong(key.name, value)
fun SharedPreferences.Editor.putBoolean(key: PrefKey, value: Boolean): SharedPreferences.Editor =
    putBoolean(key.name, value)
fun SharedPreferences.Editor.putStringSet(key: PrefKey, value: Set<String>): SharedPreferences.Editor =
    putStringSet(key.name, value)
fun SharedPreferences.Editor.remove(key: PrefKey): SharedPreferences.Editor = remove(key.name)
