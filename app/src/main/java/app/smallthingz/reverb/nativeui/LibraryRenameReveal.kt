package app.smallthingz.reverb.nativeui

/** Null reveals the field alone when its outline plus actions cannot share the viewport.
 * Validation instead reveals the bottom error/actions; it does not claim the whole header fits. */
internal fun libraryRenameRevealTop(headerHeight: Int, fieldTop: Int, viewportHeight: Int,
    validation: Boolean = false): Int? {
    if (viewportHeight <= 0) return null
    val top = (headerHeight - viewportHeight).coerceAtLeast(0)
    return top.takeIf { validation || it <= fieldTop }
}
