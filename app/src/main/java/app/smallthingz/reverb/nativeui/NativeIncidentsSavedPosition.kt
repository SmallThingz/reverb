package app.smallthingz.reverb.nativeui

/**
 * LazyColumn's conditional rememberSaveable lifetime, not a retained scroll cache.
 * An unconsumed restored value survives loading/empty history until the first nonempty branch.
 * Once that branch exists, its initial anchor must survive save-before-layout; leaving the branch
 * then retires it just as removing the released rememberLazyListState provider does.
 */
internal class NativeIncidentsSavedPosition(restored: NativeListSavedPosition? = null) {
    private var pending = restored
    private var current: NativeListSavedPosition? = null
    private var entered = false

    /** Returns the initial anchor only when entering a new nonempty list branch. */
    fun enterList(): NativeListSavedPosition? {
        if (entered) return null
        entered = true
        val initial = pending ?: NativeListSavedPosition(0, 0)
        pending = initial
        current = null
        return initial
    }

    /** LazyListMeasure134–138 clamps a vanished restored index only during real measurement. */
    fun pendingForPlacement(itemCount: Int): NativeListSavedPosition? {
        if (!entered || itemCount <= 0) return null
        val initial = pending ?: return null
        return if (initial.firstVisibleItemIndex >= itemCount) NativeListSavedPosition(itemCount - 1, 0)
        else initial
    }

    /** Only a completed, non-prelayout placement can replace the unapplied restored anchor. */
    fun placed(position: NativeListSavedPosition) {
        if (!entered) return
        pending = null
        current = position
    }

    fun save(livePosition: NativeListSavedPosition? = null): NativeListSavedPosition? =
        pending ?: if (entered) livePosition ?: current ?: NativeListSavedPosition(0, 0) else null

    fun leaveList() {
        if (!entered) return // The restored registry value has not been consumed by LazyColumn yet.
        entered = false
        pending = null
        current = null
    }
}
