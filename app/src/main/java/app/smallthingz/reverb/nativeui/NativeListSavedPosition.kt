package app.smallthingz.reverb.nativeui

/** The two integers saved by released LazyListState.Saver; no job, key or View is retained. */
internal data class NativeListSavedPosition(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
)

internal typealias SettingsListSavedPosition = NativeListSavedPosition

/** Keeps an accepted list scroll request until RecyclerView actually places that request. */
internal class NativePendingListPosition(restored: NativeListSavedPosition?) {
    private var pending = restored
    private var pendingKey: String? = null
    val hasKeyedRequest: Boolean get() = pendingKey != null
    val requestedKey: String? get() = pendingKey

    fun cancelKeyedRequest() {
        if (pendingKey != null) { pending = null; pendingKey = null }
    }

    fun request(position: NativeListSavedPosition, key: String? = null) {
        pending = position
        pendingKey = key
    }

    /** LazyListMeasure clamps only against the item count used by the actual measure pass. */
    fun pendingForPlacement(itemCount: Int, indexOfKey: ((String) -> Int)? = null): NativeListSavedPosition? {
        pendingKey?.let { key ->
            val index = indexOfKey?.invoke(key) ?: return null
            if (index !in 0 until itemCount) {
                pending = null
                pendingKey = null
                return null
            }
            // Resolve the accepted identity against the catalog used by this actual layout.
            pending = NativeListSavedPosition(index, 0)
        }
        if (itemCount <= 0) return null
        val value = pending ?: return null
        return if (value.firstVisibleItemIndex >= itemCount) NativeListSavedPosition(itemCount - 1, 0)
        else value
    }

    fun placed(position: NativeListSavedPosition?) {
        if (position != null) {
            pending = null
            pendingKey = null
        }
    }

    fun save(placed: NativeListSavedPosition?): NativeListSavedPosition =
        (if (pendingKey == null) pending else null) ?: placed ?: NativeListSavedPosition(0, 0)
}

internal typealias SettingsPendingScrollPosition = NativePendingListPosition
