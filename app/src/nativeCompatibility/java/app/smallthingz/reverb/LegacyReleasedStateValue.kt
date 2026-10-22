package app.smallthingz.reverb

/** Only the two data-only released Parcelable readers belong to this migration boundary. */
internal fun released012StateValue(raw: Any): Any? = when (raw) {
    is androidx.compose.runtime.ParcelableSnapshotMutableState -> raw.savedValue
    is androidx.compose.runtime.ParcelableSnapshotMutableIntState -> raw.savedValue
    else -> null
}
