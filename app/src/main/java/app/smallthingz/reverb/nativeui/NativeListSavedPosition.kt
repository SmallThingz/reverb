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

    fun request(position: NativeListSavedPosition) { pending = position }

    /** LazyListMeasure clamps only against the item count used by the actual measure pass. */
    fun pendingForPlacement(itemCount: Int): NativeListSavedPosition? {
        if (itemCount <= 0) return null
        val value = pending ?: return null
        return if (value.firstVisibleItemIndex >= itemCount) NativeListSavedPosition(itemCount - 1, 0)
        else value
    }

    fun placed(position: NativeListSavedPosition?) {
        if (position != null) pending = null
    }

    fun save(placed: NativeListSavedPosition?): NativeListSavedPosition =
        pending ?: placed ?: NativeListSavedPosition(0, 0)
}

internal typealias SettingsPendingScrollPosition = NativePendingListPosition
