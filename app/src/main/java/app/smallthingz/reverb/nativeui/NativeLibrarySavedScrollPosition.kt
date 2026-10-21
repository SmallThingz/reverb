package app.smallthingz.reverb.nativeui

/** Separate rememberSaveable slots of FilesScreen's populated and empty branches. */
internal data class NativeLibrarySavedScrollPosition(
    val listPosition: NativeListSavedPosition? = null,
    val emptyScrollPosition: Int? = null,
)

/**
 * Unconsumed restoration survives loading and repeated saves. Once a branch enters, its actual
 * state owns that slot; removing the branch disposes it instead of caching it for a later visit.
 */
internal class NativeLibraryScrollRestoration(restored: NativeLibrarySavedScrollPosition?) {
    private var list = restored?.listPosition
    private var empty = restored?.emptyScrollPosition

    fun consumeList(): NativeListSavedPosition? = list.also { list = null }
    fun consumeEmpty(): Int? = empty.also { empty = null }

    fun save(
        currentList: NativeListSavedPosition?,
        currentEmpty: Int?,
    ) = NativeLibrarySavedScrollPosition(currentList ?: list, currentEmpty ?: empty)
}
