package app.smallthingz.reverb.nativeui

/** Presentation-only state. The owner token is compared by identity, including same-recording renewals. */
internal data class NativeLibraryRowState<Owner : Any>(
    val selectedIds: Set<String>,
    val selectionActive: Boolean,
    val blockedIds: Set<String>,
    val expandedRecordingId: String?,
    val expandedOwner: Owner?,
    val trimRequestRecordingId: String?,
    val screenActive: Boolean,
)

internal data class NativeLibraryListUpdate(
    val catalogChanged: Boolean,
    val allVisibleRows: Boolean,
    val recordingIds: Set<String>,
) {
    fun affects(recordingId: String): Boolean = allVisibleRows || recordingId in recordingIds
}

/**
 * The controller publishes an immutable catalog and replaces its list on metadata/membership changes.
 * Compare that reference, never list equality: a local row-state publication must not traverse it.
 * Detached RecyclerView caches read [current] when they attach at a newer [revision].
 */
internal class NativeLibraryListUpdates<Owner : Any> {
    private var catalog: Any? = null
    var current: NativeLibraryRowState<Owner>? = null
        private set
    var revision = 0L
        private set

    fun submit(catalog: Any, state: NativeLibraryRowState<Owner>): NativeLibraryListUpdate? {
        val previous = current
        val catalogChanged = this.catalog !== catalog
        if (!catalogChanged && previous != null &&
            previous.selectedIds == state.selectedIds &&
            previous.selectionActive == state.selectionActive &&
            previous.blockedIds == state.blockedIds &&
            previous.expandedRecordingId == state.expandedRecordingId &&
            previous.expandedOwner === state.expandedOwner &&
            previous.trimRequestRecordingId == state.trimRequestRecordingId &&
            previous.screenActive == state.screenActive) return null

        val next = state.copy(selectedIds = state.selectedIds.toSet(), blockedIds = state.blockedIds.toSet())
        val affected = mutableSetOf<String>()
        if (previous != null) {
            changedMembers(previous.selectedIds, next.selectedIds, affected)
            changedMembers(previous.blockedIds, next.blockedIds, affected)
            if (previous.expandedRecordingId != next.expandedRecordingId ||
                previous.expandedOwner !== next.expandedOwner) {
                previous.expandedRecordingId?.let(affected::add)
                next.expandedRecordingId?.let(affected::add)
            }
            if (previous.trimRequestRecordingId != next.trimRequestRecordingId) {
                previous.trimRequestRecordingId?.let(affected::add)
                next.trimRequestRecordingId?.let(affected::add)
            }
        }
        this.catalog = catalog
        current = next
        revision++
        return NativeLibraryListUpdate(catalogChanged,
            previous == null || previous.selectionActive != next.selectionActive ||
                previous.screenActive != next.screenActive,
            affected)
    }

    fun clear() {
        catalog = null
        current = null
        revision++
    }

    private fun changedMembers(previous: Set<String>, next: Set<String>, result: MutableSet<String>) {
        if (previous == next) return
        previous.forEach { if (it !in next) result.add(it) }
        next.forEach { if (it !in previous) result.add(it) }
    }
}
