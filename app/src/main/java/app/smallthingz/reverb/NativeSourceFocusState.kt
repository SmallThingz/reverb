package app.smallthingz.reverb

/** One current focus target and one deferred owner invalidation; no retained render tree. */
internal class NativeSourceFocusState<T : Any> {
    var active: T? = null
        private set
    var pendingOwnerInvalidation = false
        private set

    fun focused(target: T) {
        active = target
        pendingOwnerInvalidation = false
    }

    /** A retained AndroidView may synchronously refocus while its Compose wrapper still exists. */
    fun childCleared(retainedAndroidViewStillAttached: Boolean) {
        if (active == null) return
        if (!retainedAndroidViewStillAttached) {
            active = null
            pendingOwnerInvalidation = true
        }
    }

    fun retireInvalidation() { pendingOwnerInvalidation = false }

    fun clear() {
        active = null
        pendingOwnerInvalidation = false
    }
}

/** AndroidComposeView.requestFocusBypassUnfocusableComposeView, with actual View targets. */
internal fun <T : Any, R> nativeRequestSourceOwnerFocus(
    state: NativeSourceFocusState<T>,
    ownerFocused: Boolean,
    activeIsMigrated: (T) -> Boolean,
    direction: Int,
    previousRect: R?,
    hasFocus: Boolean,
    isOneDimensional: (Int) -> Boolean,
    parkOwner: () -> Boolean,
    search: (Int, R?, T?) -> Boolean,
    reset: (Int) -> Boolean,
): Boolean {
    if (state.pendingOwnerInvalidation) return parkOwner()
    // A real migrated descendant stands for the virtual target owned by AndroidComposeView.
    if (ownerFocused || state.active?.let(activeIsMigrated) == true) return true
    if (search(direction, previousRect, state.active)) return true
    if (search(direction, null, state.active)) return true
    return hasFocus && isOneDimensional(direction) && reset(direction)
}

internal fun nativeReportsSourceFocusAvailability(retainedAndroidView: Boolean): Boolean =
    retainedAndroidView // UI1.11.2 isInitialFocusOnFocusableAvailable=false.

/** A source focusProperties.onEnter boundary, independent of individual child focusability. */
internal interface NativeSourceFocusEntryBoundary {
    val sourceFocusEntryAllowed: Boolean
}

/** Entering a blocked ancestor is cancelled; moving within it or leaving it remains possible. */
internal inline fun <T : Any> nativeMayEnterSourceFocus(
    target: T,
    active: T?,
    parent: (T) -> T?,
): Boolean {
    var ancestor: T? = target
    while (ancestor != null) {
        if (ancestor is NativeSourceFocusEntryBoundary && !ancestor.sourceFocusEntryAllowed) {
            var source = active
            while (source != null && source !== ancestor) source = parent(source)
            if (source == null) return false
        }
        ancestor = parent(ancestor)
    }
    return true
}
