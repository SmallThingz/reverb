package app.smallthingz.reverb

import smallthingz.ui.platform.AccessibilityManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class FeedbackTone {
    INFO,
    SUCCESS,
    ERROR,
}

data class FeedbackEvent(
    val id: Long,
    val message: String,
    val tone: FeedbackTone,
)

private const val MAX_PENDING_FEEDBACK_EVENTS = 16

internal fun enqueueFeedbackEvent(
    pending: List<FeedbackEvent>,
    event: FeedbackEvent,
    maxEvents: Int = MAX_PENDING_FEEDBACK_EVENTS,
): List<FeedbackEvent> {
    require(maxEvents > 0)
    if (pending.size < maxEvents) return pending + event

    // The head is the next acknowledged delivery and may already be on-screen. Capacity pressure
    // can shed older tail events, but must never erase that unacknowledged delivery.
    val head = pending.first()
    if (maxEvents == 1) return listOf(head)
    return listOf(head) + (pending.drop(1) + event).takeLast(maxEvents - 1)
}

internal fun acknowledgeFeedbackEvent(
    pending: List<FeedbackEvent>,
    eventId: Long,
): List<FeedbackEvent> = if (pending.firstOrNull()?.id == eventId) pending.drop(1) else pending

object AppFeedbackCenter {
    private val nextId = AtomicLong(1L)
    private val mutableEvents = MutableStateFlow<List<FeedbackEvent>>(emptyList())
    val events = mutableEvents.asStateFlow()

    fun post(message: String, tone: FeedbackTone = FeedbackTone.INFO) {
        val normalized = message.trim()
        if (normalized.isEmpty()) return
        val event = FeedbackEvent(nextId.getAndIncrement(), normalized, tone)
        mutableEvents.update { pending -> enqueueFeedbackEvent(pending, event) }
    }

    fun acknowledge(eventId: Long) {
        mutableEvents.update { pending -> acknowledgeFeedbackEvent(pending, eventId) }
    }
}

internal fun recommendedFeedbackTimeoutMillis(
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
internal suspend fun deliverFeedbackEvent(
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
        val timeoutMillis = recommendedFeedbackTimeoutMillis(
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

