package app.smallthingz.reverb.nativeui

/**
 * RecyclerView may bind an expanded row before attaching it. Creating its controller then would
 * retire an unused initial-playback grant as hidden. Defer only that first construction; existing
 * controllers must still receive detached bindings to pause without losing accepted trim work.
 * Inline so this testable dispatch adds no wrapper or retained callback to a player lease.
 */
internal inline fun <T : Any> bindAttachedLibraryPlayer(
    current: T?,
    attached: Boolean,
    create: () -> T,
    bindExisting: (T) -> Unit,
): T? {
    if (current != null) {
        bindExisting(current)
        return current
    }
    return if (attached) create() else null
}
