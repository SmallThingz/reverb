package app.smallthingz.reverb

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun RecordingEntityCard(
    recording: RecordingEntity,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    selectionActive: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onIconLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    RecordingSummaryCard(
        title = recording.displayName,
        subtitle = "${formatSavedRecordingDuration(context, recording.durationMillis)} \u2022 ${recording.codecSummary}",
        trailingTop = formatRecordingStartTimestamp(context, recording.startedAtMillis),
        trailingBottom = formatShortFileSize(recording.sizeBytes),
        modifier = modifier,
        isSelected = isSelected,
        selectionActive = selectionActive,
        onClick = onClick,
        onLongClick = onLongClick,
        onIconLongClick = onIconLongClick,
    )
}

@Composable
internal fun SavingRecordingCard(
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    RecordingSummaryCard(
        title = androidx.compose.ui.res.stringResource(R.string.saving),
        subtitle = androidx.compose.ui.res.stringResource(R.string.app_name),
        trailingTop = null,
        trailingBottom = null,
        modifier = modifier,
        showProgress = true,
        trailingContent = onCancel?.let { cancel ->
            {
                IconButton(onClick = cancel) {
                    Icon(
                        imageVector = AppIcons.close,
                        contentDescription = androidx.compose.ui.res.stringResource(R.string.cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun RecordingSummaryCard(
    title: String,
    subtitle: String,
    trailingTop: String?,
    trailingBottom: String?,
    modifier: Modifier,
    isSelected: Boolean = false,
    selectionActive: Boolean = false,
    showProgress: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onIconLongClick: (() -> Unit)? = null,
) {
    val chrome = appChrome()
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.56f)
        else chrome.field,
        label = "recordingCardColor",
    )
    val iconBackground by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else chrome.raised,
        label = "recordingCardIconBackground",
    )
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else chrome.ink,
        label = "recordingCardIconTint",
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        border = BorderStroke(1.dp, chrome.border),
        tonalElevation = 0.dp,
    ) {
        val iconInteractionModifier = if (onClick != null) {
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onIconLongClick ?: onLongClick ?: {},
            )
        } else {
            Modifier
        }
        val cardInteractionModifier = if (onClick != null) {
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick ?: {},
            )
        } else {
            Modifier
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (selectionActive && !isSelected) 0.75f else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .heightIn(min = 68.dp)
                    .then(iconInteractionModifier),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(iconBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    if (showProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            imageVector = AppIcons.audioFile,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(cardInteractionModifier)
                    .padding(end = 14.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = chrome.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = chrome.muted,
                        modifier = Modifier.padding(top = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (trailingContent != null) {
                    Spacer(Modifier.width(8.dp))
                    trailingContent()
                } else if (trailingTop != null || trailingBottom != null) {
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        if (trailingTop != null) {
                            Text(
                                text = trailingTop,
                                style = MaterialTheme.typography.labelLarge,
                                color = chrome.ink,
                            )
                        }
                        if (trailingBottom != null) {
                            Text(
                                text = trailingBottom,
                                style = MaterialTheme.typography.bodySmall,
                                color = chrome.muted,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
