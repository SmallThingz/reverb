package app.smallthingz.reverb

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun RecordingEntityCard(
    recording: RecordingEntity,
    modifier: Modifier = Modifier,
    groupEdges: RecordingGroupEdges? = null,
    isSelected: Boolean = false,
    selectionActive: Boolean = false,
    interactionsEnabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onIconLongClick: (() -> Unit)? = null,
    expanded: Boolean = false,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    RecordingSummaryCard(
        title = recording.displayName,
        subtitle = "${formatSavedRecordingDuration(context, recording.durationMillis)} \u2022 ${formatShortFileSize(recording.sizeBytes)}",
        trailingTop = formatRecordingStartTimestamp(context, recording.startedAtMillis),
        modifier = modifier,
        groupEdges = groupEdges,
        isSelected = isSelected,
        selectionActive = selectionActive,
        interactionsEnabled = interactionsEnabled,
        onClick = onClick,
        onLongClick = onLongClick,
        onIconLongClick = onIconLongClick,
        expanded = expanded,
        expandedContent = expandedContent,
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
    modifier: Modifier,
    groupEdges: RecordingGroupEdges? = null,
    isSelected: Boolean = false,
    selectionActive: Boolean = false,
    interactionsEnabled: Boolean = true,
    showProgress: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onIconLongClick: (() -> Unit)? = null,
    expanded: Boolean = false,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val chrome = appChrome()
    val selectionAnimation = tween<androidx.compose.ui.graphics.Color>(
        durationMillis = 180,
        easing = FastOutSlowInEasing,
    )
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.56f)
        else chrome.field,
        animationSpec = selectionAnimation,
        label = "recordingCardColor",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.48f) else chrome.border,
        animationSpec = selectionAnimation,
        label = "recordingCardBorder",
    )
    val iconBackground by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else chrome.raised,
        animationSpec = selectionAnimation,
        label = "recordingCardIconBackground",
    )
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else chrome.ink,
        animationSpec = selectionAnimation,
        label = "recordingCardIconTint",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (selectionActive && !isSelected) 0.75f else 1f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "recordingCardSelectionAlpha",
    )
    val cardShape = if (groupEdges == null) RoundedCornerShape(16.dp) else RoundedCornerShape(
        topStart = if (groupEdges.first) 16.dp else 0.dp,
        topEnd = if (groupEdges.first) 16.dp else 0.dp,
        bottomStart = if (groupEdges.last) 16.dp else 0.dp,
        bottomEnd = if (groupEdges.last) 16.dp else 0.dp,
    )
    Surface(
        modifier = modifier.animateContentSize(
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        ).then(
            if (groupEdges == null) Modifier
            else Modifier.recordingGroupOutline(groupEdges, borderColor, chrome.border),
        ),
        shape = cardShape,
        color = bgColor,
        border = if (groupEdges == null) BorderStroke(1.dp, borderColor) else null,
        tonalElevation = 0.dp,
    ) {
        val selectionLabel = stringResource(
            if (isSelected) R.string.recording_deselect else R.string.recording_select,
        )
        val clickLabel = if (selectionActive) selectionLabel else stringResource(
            if (expanded) R.string.recording_close_player else R.string.recording_open_player,
        )
        val longClickLabel = if (selectionActive) selectionLabel else stringResource(R.string.open_options)
        val iconInteractionModifier = if (interactionsEnabled && onClick != null) {
            Modifier.combinedClickable(
                role = Role.Button,
                onClickLabel = clickLabel,
                onClick = onClick,
                onLongClickLabel = if (onIconLongClick != null) selectionLabel else longClickLabel,
                onLongClick = onIconLongClick ?: onLongClick,
            ).semantics {
                contentDescription = title
                if (selectionActive) selected = isSelected
            }
        } else {
            Modifier
        }
        val cardInteractionModifier = if (interactionsEnabled && onClick != null) {
            Modifier.combinedClickable(
                role = Role.Button,
                onClickLabel = clickLabel,
                onClick = onClick,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
            )
        } else {
            Modifier
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(contentAlpha),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(cardInteractionModifier)
                    .then(
                        if (selectionActive) Modifier.semantics { selected = isSelected }
                        else Modifier,
                    ),
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
                } else if (trailingTop != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = trailingTop,
                        style = MaterialTheme.typography.labelLarge,
                        color = chrome.ink,
                    )
                }
            }
            }
            AnimatedVisibility(
                visible = expanded && expandedContent != null,
                enter = expandVertically(animationSpec = tween(320, easing = FastOutSlowInEasing)) +
                    fadeIn(animationSpec = tween(220, delayMillis = 70)),
                exit = shrinkVertically(animationSpec = tween(260, easing = FastOutSlowInEasing)) +
                    fadeOut(animationSpec = tween(140)),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    expandedContent?.invoke(this)
                }
            }
        }
    }
}

private fun Modifier.recordingGroupOutline(
    edges: RecordingGroupEdges,
    color: Color,
    separatorColor: Color,
): Modifier = drawWithCache {
    val width = 1.dp.toPx()
    val inset = width / 2f
    val left = inset
    val right = size.width - inset
    val top = inset
    val bottom = size.height - inset
    val radius = (16.dp.toPx() - inset).coerceAtMost((right - left) / 2f)
        .coerceAtMost((bottom - top) / 2f).coerceAtLeast(0f)
    val outline = Path().apply {
        if (edges.first) {
            moveTo(left, top + radius)
            arcTo(Rect(left, top, left + 2f * radius, top + 2f * radius), 180f, 90f, false)
            lineTo(right - radius, top)
            arcTo(Rect(right - 2f * radius, top, right, top + 2f * radius), 270f, 90f, false)
        } else {
            moveTo(right, 0f)
        }
        lineTo(right, if (edges.last) bottom - radius else size.height)
        if (edges.last) {
            arcTo(Rect(right - 2f * radius, bottom - 2f * radius, right, bottom), 0f, 90f, false)
            lineTo(left + radius, bottom)
            arcTo(Rect(left, bottom - 2f * radius, left + 2f * radius, bottom), 90f, 90f, false)
        } else {
            moveTo(left, size.height)
        }
        lineTo(left, if (edges.first) top + radius else 0f)
    }
    onDrawWithContent {
        drawContent()
        drawPath(outline, color, style = Stroke(width))
        if (!edges.first) {
            drawLine(separatorColor, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1f)
        }
    }
}
