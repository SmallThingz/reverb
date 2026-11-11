package app.smallthingz.reverb

/** Visual-only close ownership; a cancelled opening never accepted a Library close. */
internal class LibraryTopBarCloseMotion {
    private var acceptedClose = false
    private var initialized = false
    private var morphing = false
    private var lastCloseProgress = 0f
    private var glyphProgress = 0f
    private var closeAnchor = 0f
    private var glyphAnchor = 0f

    fun onNavigation(wasOpen: Boolean, isOpen: Boolean) {
        if (wasOpen && !isOpen) acceptedClose = true
    }

    fun progress(panelOpenProgress: Float, libraryOpen: Boolean, backPreviewActive: Boolean): Float {
        val open = panelOpenProgress.coerceIn(0f, 1f)
        val close = 1f - open
        if ((backPreviewActive || acceptedClose) && !morphing) {
            closeAnchor = if (initialized) lastCloseProgress else 0f
            glyphAnchor = if (closeAnchor == 1f) 1f else glyphProgress
            morphing = true
        }
        glyphProgress = if (!morphing) 0f else when {
            close == 1f -> 1f
            close == closeAnchor -> glyphAnchor
            close < closeAnchor -> glyphAnchor * close / closeAnchor
            else -> glyphAnchor + (1f - glyphAnchor) * (close - closeAnchor) / (1f - closeAnchor)
        }
        initialized = true
        lastCloseProgress = close
        // Preserve the same path if an accepted close reverses into reopening before it settles.
        if ((libraryOpen && open == 1f) || (!libraryOpen && open == 0f)) {
            acceptedClose = false
            morphing = false
        }
        return glyphProgress
    }
}
