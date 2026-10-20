package app.smallthingz.reverb

import androidx.compose.ui.platform.AccessibilityManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** The accessibility deadline owns the real Undo phase, not only its visible card. */
internal suspend fun runLibraryUndoDeadline(
    state: LibraryDeletionState,
    revision: Long,
    accessibilityManager: AccessibilityManager?,
    waitForDuration: suspend (Long) -> Unit = { delay(it) },
    commit: suspend () -> Unit,
) {
    waitForDuration(recommendedFeedbackTimeoutMillis(
        accessibilityManager = accessibilityManager,
        originalTimeoutMillis = 4_500L,
        containsIcons = false,
        containsText = true,
        containsControls = true,
    ))
    currentCoroutineContext().ensureActive()
    if (!state.beginForegroundCommit(revision)) return
    try {
        commit()
    } finally {
        state.completeForegroundCommit()
    }
}

internal suspend fun dismissSavedFeedbackAfterTimeout(
    expected: CaptureSaveStatus.Saved,
    accessibilityManager: AccessibilityManager?,
    currentStatus: () -> CaptureSaveStatus?,
    dismiss: () -> Unit,
    waitForDuration: suspend (Long) -> Unit = { delay(it) },
    canDisplay: () -> Boolean = { true },
    awaitVisibleFrame: suspend () -> Unit = {},
) {
    if (!canDisplay()) return
    awaitVisibleFrame()
    currentCoroutineContext().ensureActive()
    if (!canDisplay()) return
    waitForDuration(recommendedFeedbackTimeoutMillis(
        accessibilityManager = accessibilityManager,
        originalTimeoutMillis = 1_500L,
        containsIcons = true,
        containsText = true,
        containsControls = false,
    ))
    currentCoroutineContext().ensureActive()
    if (canDisplay() && currentStatus() === expected) dismiss()
}
