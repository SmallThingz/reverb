package app.smallthingz.reverb.nativeui

/** One expansion request; a user's scroll cancels it until a different expansion is admitted. */
internal class LibraryExpansionReveal {
    private var target: String? = null
    private var info = false
    var requestedId: String? = null
        private set
    private var height = -1
    private var width = -1
    private var viewport = -1

    fun update(id: String?, isInfo: Boolean) {
        if (target == id && info == isInfo) return
        target = id
        info = isInfo
        requestedId = id
        height = -1
        width = -1
        viewport = -1
    }

    fun cancel() { requestedId = null }

    /** Repeated state/layout delivery cannot enqueue another scroll for unchanged geometry. */
    fun changed(rowHeight: Int, rowWidth: Int, viewportHeight: Int): Boolean {
        if (requestedId == null || rowHeight <= 0 || rowWidth <= 0 || viewportHeight <= 0) return false
        if (height == rowHeight && width == rowWidth && viewport == viewportHeight) return false
        height = rowHeight
        width = rowWidth
        viewport = viewportHeight
        return true
    }
}
