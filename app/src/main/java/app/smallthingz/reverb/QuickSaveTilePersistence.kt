package app.smallthingz.reverb

import android.content.SharedPreferences

/** Definition changes and their observed history have one durability/publication boundary. */
internal fun commitQuickSaveTilePreferences(
    prefs: SharedPreferences,
    definitions: List<QuickSaveTileOption>?,
    history: QuickSaveTileAddedHistory,
    publish: () -> Unit,
): Boolean {
    val encodedDefinitions = definitions?.let(::encodeQuickSaveTileOptions)
    val encodedHistory = encodeQuickSaveTileAddedStates(history)
    val previousDefinitions = encodedDefinitions?.let { prefs.snapshotDurablePreferenceValue(PrefKey.QUICK_SAVE_TILE_OPTIONS) }
    val previousHistory = prefs.snapshotDurablePreferenceValue(PrefKey.QUICK_SAVE_TILE_ADDED_STATES)
    val committed = commitDurablePreferenceOrRestoreInMemory(
        commit = {
            val editor = prefs.edit().putString(PrefKey.QUICK_SAVE_TILE_ADDED_STATES, encodedHistory)
            if (encodedDefinitions != null) editor.putString(PrefKey.QUICK_SAVE_TILE_OPTIONS, encodedDefinitions)
            editor.commit()
        },
        restoreInMemory = {
            val editor = prefs.edit().restoreDurablePreferenceValue(PrefKey.QUICK_SAVE_TILE_ADDED_STATES, previousHistory)
            if (previousDefinitions != null) editor.restoreDurablePreferenceValue(PrefKey.QUICK_SAVE_TILE_OPTIONS, previousDefinitions)
            editor.commit()
        },
    )
    if (committed) publish()
    return committed
}
