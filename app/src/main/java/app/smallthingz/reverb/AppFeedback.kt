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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest

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

object AppFeedbackCenter {
    private val nextId = AtomicLong(1L)
    private val mutableEvents = MutableSharedFlow<FeedbackEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = mutableEvents.asSharedFlow()

    fun post(message: String, tone: FeedbackTone = FeedbackTone.INFO) {
        val normalized = message.trim()
        if (normalized.isEmpty()) return
        mutableEvents.tryEmit(FeedbackEvent(nextId.getAndIncrement(), normalized, tone))
    }
}

@Composable
internal fun AppFeedbackHost(
    modifier: Modifier = Modifier,
) {
    var event by remember { mutableStateOf<FeedbackEvent?>(null) }

    LaunchedEffect(Unit) {
        AppFeedbackCenter.events.collectLatest { next ->
            event = next
            delay(if (next.tone == FeedbackTone.ERROR) 4_500L else 2_800L)
            if (event?.id == next.id) event = null
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
