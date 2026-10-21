package app.smallthingz.reverb

private const val PANEL_COMMIT_PROGRESS = 0.12f

internal fun panelRevealProgress(dragDistancePx: Float, viewportHeightPx: Float): Float {
    if (viewportHeightPx <= 0f) return 0f
    return (dragDistancePx / viewportHeightPx).coerceIn(0f, 1f)
}

internal fun shouldCommitPanelReveal(progress: Float): Boolean =
    progress.coerceIn(0f, 1f) >= PANEL_COMMIT_PROGRESS

internal fun shouldComposeMainPanel(
    previouslyComposed: Boolean,
    visible: Boolean,
    progress: Float,
): Boolean = previouslyComposed || visible || progress > 0f
