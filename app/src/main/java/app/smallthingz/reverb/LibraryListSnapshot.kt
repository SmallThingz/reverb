package app.smallthingz.reverb

/** Immutable publication: progress replaces the small prefix, never copies the finished catalog. */
internal class LibraryListSnapshot private constructor(
    val pending: List<LibraryListItem>,
    val finished: List<LibraryListItem>,
    val structure: Any,
) : AbstractList<LibraryListItem>(), java.util.RandomAccess {
    override val size: Int get() = pending.size + finished.size
    override fun get(index: Int): LibraryListItem {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("index=$index, size=$size")
        return if (index < pending.size) pending[index] else finished[index - pending.size]
    }

    companion object {
        fun publish(previous: List<LibraryListItem>, pending: List<LibraryListItem>,
            finished: List<LibraryListItem>): LibraryListSnapshot {
            val old = previous as? LibraryListSnapshot
            if (old != null && old.finished === finished && old.pending == pending) return old
            val sameStructure = old != null && old.finished === finished &&
                old.pending.size == pending.size && pending.indices.all { index ->
                    val before = old.pending[index]
                    val after = pending[index]
                    if (before is LibraryListItem.Pending && after is LibraryListItem.Pending)
                        before.save.id == after.save.id
                    else before == after
                }
            return LibraryListSnapshot(pending, finished, if (sameStructure) checkNotNull(old).structure else Any())
        }
    }
}
