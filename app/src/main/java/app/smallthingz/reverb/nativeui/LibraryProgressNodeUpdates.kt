package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.LibraryListItem
import app.smallthingz.reverb.LibraryListSnapshot
import app.smallthingz.reverb.NativeLibraryPresentation
import app.smallthingz.reverb.PendingSave

/** Conservative fast path: every field except progress must remain equal. No semantic value cache. */
internal fun pendingSaveOnlyProgressChanged(before: PendingSave, after: PendingSave): Boolean =
    before.copy(progress = after.progress) == after

/**
 * Ignore only pending progress, never catalog/row admission or modal changes. The immutable
 * structure token avoids walking finished recordings; only the small pending prefix is inspected.
 */
internal fun libraryOnlySaveProgressChanged(
    before: NativeLibraryPresentation?,
    after: NativeLibraryPresentation,
): Boolean {
    if (before == null) return false
    val oldItems = before.list.items as? LibraryListSnapshot ?: return false
    val newItems = after.list.items as? LibraryListSnapshot ?: return false
    if (oldItems === newItems || oldItems.structure !== newItems.structure) return false
    if (before.list.copy(items = after.list.items) != after.list ||
        before.copy(list = after.list) != after) return false
    return oldItems.pending.indices.all { index ->
        val old = oldItems.pending[index]
        val new = newItems.pending[index]
        if (old is LibraryListItem.Pending && new is LibraryListItem.Pending)
            pendingSaveOnlyProgressChanged(old.save, new.save)
        else old == new
    }
}
