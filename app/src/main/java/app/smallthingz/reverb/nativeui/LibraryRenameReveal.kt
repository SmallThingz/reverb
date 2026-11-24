package app.smallthingz.reverb.nativeui

/** Null keeps caret priority when editor plus actions genuinely cannot share the viewport.
 * Validation instead reveals the bottom error/actions; it does not claim the whole header fits. */
internal fun libraryRenameRevealTop(headerHeight: Int, editorTop: Int, viewportHeight: Int,
    validation: Boolean = false): Int? {
    if (viewportHeight <= 0) return null
    val top = (headerHeight - viewportHeight).coerceAtLeast(0)
    return top.takeIf { validation || it <= editorTop }
}
