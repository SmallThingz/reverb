package app.smallthingz.reverb

import android.content.SharedPreferences

enum class PrefKey {
    AUDIO_MEMORY_ENABLED,
    AUDIO_MEMORY_SIZE,
    BATTERY_OPTIMIZATION_PROMPT_SHOWN,
    WAKE_LOCK_ENABLED,
    RETENTION_MODE,
    RETENTION_SECONDS,
    ONE_SHOT_RETENTION_SECONDS,
    ONE_SHOT_AUDIO_MEMORY_SIZE,
    EXPORT_DIRECTORY_URI,
    PCM_SAMPLE_FORMAT,
    AUDIO_SOURCE,
    CHANNEL_MODE,
    INPUT_ROUTE,
    SAMPLE_RATE,
    THEME_MODE,
}

fun SharedPreferences.getString(key: PrefKey, default: String?): String? = getString(key.name, default)
fun SharedPreferences.getInt(key: PrefKey, default: Int): Int = getInt(key.name, default)
fun SharedPreferences.getLong(key: PrefKey, default: Long): Long = getLong(key.name, default)
fun SharedPreferences.getBoolean(key: PrefKey, default: Boolean): Boolean = getBoolean(key.name, default)
fun SharedPreferences.contains(key: PrefKey): Boolean = contains(key.name)
fun SharedPreferences.Editor.putString(key: PrefKey, value: String): SharedPreferences.Editor =
    putString(key.name, value)
fun SharedPreferences.Editor.putInt(key: PrefKey, value: Int): SharedPreferences.Editor = putInt(key.name, value)
fun SharedPreferences.Editor.putLong(key: PrefKey, value: Long): SharedPreferences.Editor = putLong(key.name, value)
fun SharedPreferences.Editor.putBoolean(key: PrefKey, value: Boolean): SharedPreferences.Editor =
    putBoolean(key.name, value)
fun SharedPreferences.Editor.remove(key: PrefKey): SharedPreferences.Editor = remove(key.name)
