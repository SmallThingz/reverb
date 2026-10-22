package app.smallthingz.reverb

import androidx.compose.ui.platform.AccessibilityManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal fun legacyRecommendedFeedbackTimeoutMillis(
    accessibilityManager: AccessibilityManager?,
    originalTimeoutMillis: Long,
    containsIcons: Boolean,
    containsText: Boolean,
    containsControls: Boolean,
): Long = accessibilityManager?.calculateRecommendedTimeoutMillis(
    originalTimeoutMillis = originalTimeoutMillis,
    containsIcons = containsIcons,
    containsText = containsText,
    containsControls = containsControls,
) ?: originalTimeoutMillis

/** Cancellation or obscuration leaves the head queued for a full visible delivery next time. */
internal suspend fun legacyDeliverFeedbackEvent(
    event: FeedbackEvent,
    canDisplay: () -> Boolean,
    display: (FeedbackEvent?) -> Unit,
    awaitVisibleFrame: suspend () -> Unit,
    acknowledge: (Long) -> Unit,
    accessibilityManager: AccessibilityManager? = null,
    waitForDuration: suspend (Long) -> Unit = { delay(it) },
) {
    if (!canDisplay()) return
    display(event)
    try {
        awaitVisibleFrame()
        if (!canDisplay()) return
        val originalTimeoutMillis = if (event.tone == FeedbackTone.ERROR) 4_500L else 2_800L
        // The queued host renders text-only cards, without icons or action buttons.
        val timeoutMillis = legacyRecommendedFeedbackTimeoutMillis(
            accessibilityManager = accessibilityManager,
            originalTimeoutMillis = originalTimeoutMillis,
            containsIcons = false,
            containsText = true,
            containsControls = false,
        )
        waitForDuration(timeoutMillis)
        currentCoroutineContext().ensureActive()
        if (canDisplay()) acknowledge(event.id)
    } finally {
        display(null)
    }
}

