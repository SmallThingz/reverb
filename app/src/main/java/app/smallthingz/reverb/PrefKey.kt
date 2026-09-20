package app.smallthingz.reverb

import android.annotation.SuppressLint
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

internal data class DurablePreferenceValueSnapshot(
    val present: Boolean,
    val value: Any?,
)

internal fun durablePreferenceValueSnapshot(
    values: Map<String, *>,
    key: PrefKey,
): DurablePreferenceValueSnapshot {
    if (!values.containsKey(key.name)) return DurablePreferenceValueSnapshot(false, null)
    val value = values[key.name]
    val copied = if (value is Set<*>) value.toSet() else value
    return DurablePreferenceValueSnapshot(true, copied)
}

internal fun SharedPreferences.snapshotDurablePreferenceValue(key: PrefKey): DurablePreferenceValueSnapshot =
    durablePreferenceValueSnapshot(all, key)

@Suppress("UNCHECKED_CAST")
internal fun SharedPreferences.Editor.restoreDurablePreferenceValue(
    key: PrefKey,
    snapshot: DurablePreferenceValueSnapshot,
): SharedPreferences.Editor {
    if (!snapshot.present) return remove(key)
    return when (val value = snapshot.value) {
        is String -> putString(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key.name, value)
        is Boolean -> putBoolean(key, value)
        is Set<*> -> {
            if (value.any { it !is String }) {
                throw IllegalStateException("Unsupported durable preference set for ${key.name}")
            }
            putStringSet(key, (value as Set<String>).toSet())
        }
        null -> putString(key.name, null)
        else -> throw IllegalStateException(
            "Unsupported durable preference value ${value::class.java.name} for ${key.name}",
        )
    }
}

internal const val MAX_DURABLE_JOURNAL_ENTRIES = 2_048
internal const val MAX_DURABLE_JOURNAL_ENTRY_CHARS = 16 * 1_024
internal const val MAX_DURABLE_JOURNAL_TOTAL_CHARS = 2 * 1_024 * 1_024

internal fun boundedDurableStringSet(entries: Set<String>, label: String): Set<String> {
    if (entries.size > MAX_DURABLE_JOURNAL_ENTRIES) {
        throw IllegalStateException(
            "Durable preference $label has ${entries.size} entries; max is $MAX_DURABLE_JOURNAL_ENTRIES",
        )
    }
    var totalChars = 0L
    val copy = LinkedHashSet<String>(entries.size)
    for (entry in entries) {
        if (entry.length > MAX_DURABLE_JOURNAL_ENTRY_CHARS) {
            throw IllegalStateException(
                "Durable preference $label has an oversized entry (${entry.length} chars)",
            )
        }
        totalChars += entry.length.toLong()
        if (totalChars > MAX_DURABLE_JOURNAL_TOTAL_CHARS.toLong()) {
            throw IllegalStateException(
                "Durable preference $label exceeds $MAX_DURABLE_JOURNAL_TOTAL_CHARS total chars",
            )
        }
        copy += entry
    }
    return copy
}

internal fun SharedPreferences.requireDurableStringSet(key: PrefKey): Set<String> =
    requireDurablePreference(
        present = contains(key),
        absent = emptySet(),
        label = key.name,
    ) {
        boundedDurableStringSet(getStringSet(key, emptySet()) ?: emptySet(), key.name)
    }

internal inline fun commitDurablePreferenceOrRestoreInMemory(
    commit: () -> Boolean,
    restoreInMemory: () -> Unit,
): Boolean {
    val committed = try {
        commit()
    } catch (error: Throwable) {
        try {
            restoreInMemory()
        } catch (restoreError: Throwable) {
            if (restoreError !== error) error.addSuppressed(restoreError)
        }
        throw error
    }
    if (committed) return true
    restoreInMemory()
    return false
}

@SuppressLint("UseKtx") // commit() success is the durability boundary for journal authority.
internal fun SharedPreferences.commitDurableStringSetReplacement(
    key: PrefKey,
    previousEntries: Set<String>,
    updatedEntries: Set<String>,
): Boolean {
    val boundedPrevious = boundedDurableStringSet(previousEntries, key.name)
    val boundedUpdated = boundedDurableStringSet(updatedEntries, key.name)
    val previousPresent = contains(key)
    fun write(entries: Set<String>, present: Boolean): Boolean {
        val editor = edit()
        if (present) editor.putStringSet(key, entries.toSet()) else editor.remove(key)
        return editor.commit()
    }
    return commitDurablePreferenceOrRestoreInMemory(
        commit = { write(boundedUpdated, boundedUpdated.isNotEmpty()) },
        // SharedPreferences commits update the process-local map before disk I/O. A failed
        // durability boundary must therefore restore the prior map synchronously so later
        // same-process replay cannot consume authority that was never durably published, or
        // forget authority whose removal never became durable. The rollback commit's Boolean
        // is intentionally not promoted to success; the original mutation still failed.
        restoreInMemory = { write(boundedPrevious, previousPresent) },
    )
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
