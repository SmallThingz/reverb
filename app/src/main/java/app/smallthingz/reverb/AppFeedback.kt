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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    return (pending + event).takeLast(maxEvents)
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

@Composable
internal fun AppFeedbackHost(
    modifier: Modifier = Modifier,
) {
    var event by remember { mutableStateOf<FeedbackEvent?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            AppFeedbackCenter.events
                .map { pending -> pending.firstOrNull() }
                .distinctUntilChangedBy { next -> next?.id }
                .collect { next ->
                    if (next == null) {
                        event = null
                        return@collect
                    }
                    event = next
                    delay(if (next.tone == FeedbackTone.ERROR) 4_500L else 2_800L)
                    if (event?.id == next.id) event = null
                    AppFeedbackCenter.acknowledge(next.id)
                }
        }
    }

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
    val container = when (tone) {
        FeedbackTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        FeedbackTone.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        FeedbackTone.INFO -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when (tone) {
        FeedbackTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        FeedbackTone.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
        FeedbackTone.INFO -> MaterialTheme.colorScheme.onSurface
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
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = content)
                }
            }
        }
    }
}
