package app.smallthingz.reverb

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
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

@Composable
internal fun AppFeedbackHost(
    modifier: Modifier = Modifier,
    deliveryAllowed: Boolean = true,
) {
    var event by remember { mutableStateOf<FeedbackEvent?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val window = LocalWindowInfo.current
    val view = LocalView.current
    val accessibilityManager = LocalAccessibilityManager.current
    val currentDeliveryAllowed = rememberUpdatedState(deliveryAllowed)

    LaunchedEffect(lifecycleOwner, window, view, accessibilityManager) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { currentDeliveryAllowed.value && window.isWindowFocused }
                .collectLatest { allowed ->
                    if (!allowed) return@collectLatest
                    AppFeedbackCenter.events
                        .map { pending -> pending.firstOrNull() }
                        .distinctUntilChangedBy { next -> next?.id }
                        .collect { next ->
                            if (next == null) return@collect
                            deliverFeedbackEvent(
                                event = next,
                                canDisplay = {
                                    currentDeliveryAllowed.value && window.isWindowFocused &&
                                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                        view.isAttachedToWindow && view.isShown && view.hasWindowFocus()
                                },
                                display = { event = it },
                                awaitVisibleFrame = {
                                    // A state write is not a displayed notice. Allow composition and
                                    // its following frame before starting the acknowledged duration.
                                    withFrameNanos { }
                                    withFrameNanos { }
                                },
                                acknowledge = AppFeedbackCenter::acknowledge,
                                accessibilityManager = accessibilityManager,
                            )
                        }
                }
        }
    }

    // An actionable local notice owns this region immediately, without overlapping an exit fade.
    if (!deliveryAllowed || !window.isWindowFocused) return
    AnimatedVisibility(
        visible = event != null,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
    ) {
        val current = event
        if (current != null) {
            FeedbackCard(
                message = current.message,
                tone = current.tone,
            )
        }
    }
}

@Composable
internal fun FeedbackCard(
    message: String,
    tone: FeedbackTone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val (container, content) = when (tone) {
        FeedbackTone.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        FeedbackTone.SUCCESS -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        FeedbackTone.INFO -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = container,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = content)
                }
            }
        }
    }
}
