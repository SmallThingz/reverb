package app.smallthingz.reverb

import android.content.ActivityNotFoundException
import android.content.Context
import java.util.Date
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed class ListItem {
    data class Header(val dateLabel: String) : ListItem()
    data class Recording(val recording: RecordingEntity) : ListItem()
}

private data class LibraryNotice(
    val message: String,
    val tone: FeedbackTone,
    val canUndo: Boolean = false,
)

private val recordingCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Composable
fun FilesScreen(
    modifier: Modifier = Modifier.fillMaxSize(),
    initialRecordings: List<RecordingEntity> = emptyList(),
    onSelectionActiveChange: (Boolean) -> Unit = {},
    onRecordingCountChanged: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var recordings by remember { mutableStateOf(initialRecordings) }
    var isRefreshing by remember { mutableStateOf(false) }
    var hasLoaded by remember { mutableStateOf(initialRecordings.isNotEmpty()) }
    val refreshGeneration = remember { intArrayOf(0) }

    val selectedIds = remember { mutableStateMapOf<String, RecordingEntity>() }
    val pendingDeletions = remember { mutableStateMapOf<String, RecordingEntity>() }
    var isDeleting by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var infoRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var showPlayerDialog by remember { mutableStateOf(false) }
    var playerRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var notice by remember { mutableStateOf<LibraryNotice?>(null) }
    var deletionJob by remember { mutableStateOf<Job?>(null) }

    fun refresh(showSpinner: Boolean = true) {
        val generation = ++refreshGeneration[0]
        if (showSpinner) isRefreshing = true
        scope.launch {
            try {
                val stored = RecordingRepository.refresh(context)
                if (generation != refreshGeneration[0]) return@launch
                recordings = stored
                hasLoaded = true
                val storedIds = stored.mapTo(mutableSetOf()) { it.id }
                pendingDeletions.keys.toList().forEach { id ->
                    if (id !in storedIds) pendingDeletions.remove(id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == refreshGeneration[0]) {
                    notice = LibraryNotice(
                        context.getString(R.string.recordings_refresh_failed),
                        FeedbackTone.ERROR,
                    )
                }
            } finally {
                if (generation == refreshGeneration[0]) isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasLoaded) {
            recordings = try {
                RecordingRepository.listKnown(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            hasLoaded = true
        }

        // Guarantee a first frame from the local DB before any SAF/filesystem work.
        withFrameNanos { }
        delay(250L)
        refresh(showSpinner = false)
    }

    suspend fun finalizeDeletions() {
        val pending = pendingDeletions.values.toList()
        if (pending.isEmpty()) return
        var deleted = 0
        var failed = false
        pending.forEach { recording ->
            val didDelete = try {
                RecordingRepository.delete(context, recording)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
                false
            }
            pendingDeletions.remove(recording.id)
            if (didDelete) deleted++
        }
        try {
            recordings = RecordingRepository.refresh(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
        if (failed || deleted == 0) {
            notice = LibraryNotice(
                context.getString(R.string.recording_delete_failed),
                FeedbackTone.ERROR,
            )
        }
    }

    fun commitPendingDeletionsInBackground() {
        if (pendingDeletions.isEmpty()) return
        deletionJob?.cancel()
        deletionJob = null
        val pending = pendingDeletions.values.toList()
        pendingDeletions.clear()
        isDeleting = false
        val appContext = context.applicationContext
        recordingCleanupScope.launch {
            pending.forEach { recording ->
                runCatching { RecordingRepository.delete(appContext, recording) }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh(showSpinner = false)
            }
            if (event == Lifecycle.Event.ON_STOP && pendingDeletions.isNotEmpty()) {
                notice = null
                commitPendingDeletionsInBackground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            deletionJob?.cancel()
            commitPendingDeletionsInBackground()
        }
    }

    fun clearSelection() {
        selectedIds.clear()
    }

    val visibleRecordings by remember {
        derivedStateOf { recordings.filterNot { it.id in pendingDeletions } }
    }

    val listItems by remember {
        derivedStateOf { buildListItems(context, visibleRecordings) }
    }
    LaunchedEffect(hasLoaded, recordings.size) {
        if (hasLoaded) onRecordingCountChanged(recordings.size)
    }

    fun deleteSelected() {
        if (isDeleting) return
        val selected = selectedIds.values.toList()
        if (selected.isEmpty()) { clearSelection(); return }
        isDeleting = true
        selected.forEach { pendingDeletions[it.id] = it }
        clearSelection()
        val count = pendingDeletions.size
        val message = if (count == 1) context.getString(R.string.recording_deleted)
        else context.resources.getQuantityString(R.plurals.recordings_deleted, count, count)
        notice = LibraryNotice(message, FeedbackTone.INFO, canUndo = true)
        deletionJob?.cancel()
        deletionJob = scope.launch {
            delay(4_500L)
            notice = null
            finalizeDeletions()
            isDeleting = false
        }
    }

    fun undoDelete() {
        deletionJob?.cancel()
        deletionJob = null
        pendingDeletions.clear()
        notice = null
        isDeleting = false
    }

    fun renameSelected() {
        val recording = selectedIds.values.singleOrNull() ?: return
        renameRecording = recording
        showRenameDialog = true
    }

    fun infoSelected() {
        val recording = selectedIds.values.singleOrNull() ?: return
        infoRecording = recording
        showInfoDialog = true
    }

    val selectionActive by remember { derivedStateOf { selectedIds.isNotEmpty() } }
    LaunchedEffect(selectionActive) { onSelectionActiveChange(selectionActive) }
    DisposableEffect(Unit) { onDispose { onSelectionActiveChange(false) } }
    BackHandler(enabled = selectionActive) { clearSelection() }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (selectionActive) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_selection))
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.recordings_selected, selectedIds.size, selectedIds.size,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.weight(1f))
                        if (selectedIds.size == 1) {
                            IconButton(onClick = { renameSelected() }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.rename_recording))
                            }
                        }
                        if (selectedIds.size == 1) {
                            IconButton(onClick = { infoSelected() }) {
                                Icon(Icons.Default.Info, contentDescription = stringResource(R.string.recording_info))
                            }
                        }
                        if (selectedIds.isNotEmpty()) {
                            IconButton(onClick = { deleteSelected() }, enabled = !isDeleting) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete_recording),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { refresh(showSpinner = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (listItems.isEmpty() && !isRefreshing) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        EmptyState()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                    ) {
                        items(listItems, key = { item ->
                            when (item) {
                                is ListItem.Header -> "header:${item.dateLabel}"
                                is ListItem.Recording -> "recording:${item.recording.id}"
                            }
                        }) { item ->
                            when (item) {
                                is ListItem.Header -> HeaderItem(item.dateLabel)
                                is ListItem.Recording -> {
                                    RecordingItem(
                                        item = item,
                                        isSelected = item.recording.id in selectedIds,
                                        selectionActive = selectionActive,
                                        onClick = {
                                            if (selectionActive) {
                                                if (selectedIds.containsKey(item.recording.id)) {
                                                    selectedIds.remove(item.recording.id)
                                                } else {
                                                    selectedIds[item.recording.id] = item.recording
                                                }
                                            } else {
                                                playerRecording = item.recording
                                                showPlayerDialog = true
                                            }
                                        },
                                        onLongClick = {
                                            if (selectedIds.containsKey(item.recording.id)) {
                                                selectedIds.remove(item.recording.id)
                                            } else {
                                                selectedIds[item.recording.id] = item.recording
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(84.dp)) }
                    }
                }
            }

            notice?.let { current ->
                FeedbackCard(
                    message = current.message,
                    tone = current.tone,
                    actionLabel = if (current.canUndo) stringResource(R.string.undo) else null,
                    onAction = if (current.canUndo) ::undoDelete else null,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    if (showRenameDialog) {
        if (renameRecording == null) {
            showRenameDialog = false
        } else {
            RenameRecordingDialog(
                recording = renameRecording ?: return,
                onDismiss = { showRenameDialog = false; renameRecording = null },
                onRenamed = {
                    showRenameDialog = false
                    renameRecording = null
                    clearSelection()
                    refresh()
                },
            )
        }
    }

    if (showInfoDialog) {
        if (infoRecording == null) {
            showInfoDialog = false
        } else {
            RecordingInfoDialogContent(
                recording = infoRecording ?: return,
                onDismiss = { showInfoDialog = false; infoRecording = null },
            )
        }
    }

    if (showPlayerDialog) {
        if (playerRecording == null) {
            showPlayerDialog = false
        } else {
            val currentRecording = playerRecording ?: return
            RecordingPlayerDialog(
                recording = currentRecording,
                onDismiss = { showPlayerDialog = false; playerRecording = null },
                onInfoClick = {
                    showPlayerDialog = false
                    playerRecording = null
                    infoRecording = currentRecording
                    showInfoDialog = true
                },
                onPlaybackFailed = {
                    showPlayerDialog = false
                    playerRecording = null
                    try {
                        context.startActivity(buildOpenRecordingIntent(context, currentRecording))
                    } catch (_: ActivityNotFoundException) {
                        notice = LibraryNotice(context.getString(R.string.no_app_available), FeedbackTone.ERROR)
                    } catch (_: RuntimeException) {
                        notice = LibraryNotice(context.getString(R.string.no_app_available), FeedbackTone.ERROR)
                    }
                },
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Surface(
        modifier = Modifier.size(84.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_tab_files),
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun HeaderItem(dateLabel: String) {
    Text(
        text = dateLabel,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = (0.04).sp,
        modifier = Modifier.padding(top = 28.dp, bottom = 8.dp),
    )
}

@Composable
private fun RecordingItem(
    item: ListItem.Recording,
    isSelected: Boolean,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    RecordingEntityCard(
        recording = item.recording,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        isSelected = isSelected,
        selectionActive = selectionActive,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

@Composable
private fun RenameRecordingDialog(
    recording: RecordingEntity,
    onDismiss: () -> Unit,
    onRenamed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(recording.id, recording.displayName) {
        val baseName = recording.displayName.substringBeforeLast('.', recording.displayName)
        mutableStateOf(if (baseName.isEmpty()) recording.displayName else baseName)
    }
    var error by remember(recording.id) { mutableStateOf<String?>(null) }
    var isRenaming by remember(recording.id) { mutableStateOf(false) }
    val illegalChars = setOf('\\', '/', '*', '?', '"', '<', '>', '|')

    fun validateAndRename(trimmed: String) {
        if (isRenaming) return
        if (trimmed.isBlank()) {
            error = context.getString(R.string.rename_recording_invalid)
            return
        }
        if (trimmed.any { it in illegalChars }) {
            error = context.getString(R.string.rename_recording_illegal_chars)
            return
        }
        isRenaming = true
        scope.launch {
            try {
                val renamed = RecordingRepository.rename(context, recording, trimmed)
                if (renamed == null) {
                    error = context.getString(R.string.rename_recording_failed)
                } else {
                    onRenamed()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = context.getString(R.string.rename_recording_failed)
            } finally {
                isRenaming = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.rename_recording),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!isRenaming) validateAndRename(name.trim()) }),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { validateAndRename(name.trim()) },
                    enabled = error == null && !isRenaming,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = stringResource(R.string.rename_recording),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun RecordingInfoDialogContent(
    recording: RecordingEntity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val detailsText = remember(recording) { buildRecordingDetailsText(context, recording) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.recording_info),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                Text(
                    text = detailsText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 24.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = {},
    )
}

private fun buildRecordingDetailsText(context: Context, recording: RecordingEntity): String {
    val dateFormat = android.text.format.DateFormat.getMediumDateFormat(context)
    val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
    val startedAt = Date(recording.startedAtMillis)
    val sizeText = formatShortFileSize(recording.sizeBytes)
    val durationText = formatSavedRecordingDuration(context, recording.durationMillis)

    return buildString {
        appendLine("${context.getString(R.string.recording_details_name)} ${recording.displayName}")
        appendLine(
            "${context.getString(R.string.recording_details_started)} ${
                dateFormat.format(startedAt)
            } ${timeFormat.format(startedAt)}",
        )
        appendLine("${context.getString(R.string.recording_details_duration)} $durationText")
        appendLine("${context.getString(R.string.recording_details_size)} $sizeText")
        appendLine("${context.getString(R.string.recording_details_codec)} ${recording.codecSummary}")
        appendLine("${context.getString(R.string.recording_details_mime)} ${recording.mimeType}")
        appendLine("${context.getString(R.string.recording_details_storage)} ${recording.storageType}")
        append(
            "${context.getString(R.string.recording_details_location)} ${
                describeRecordingLocation(context, recording)
            }",
        )
    }
}

private fun buildListItems(context: Context, recordings: List<RecordingEntity>): List<ListItem> {
    val items = mutableListOf<ListItem>()
    var currentDateHeader = ""
    recordings.forEach { recording ->
        val dateHeader = formatRecordingDateHeader(context, recording.startedAtMillis)
        if (dateHeader != currentDateHeader) {
            currentDateHeader = dateHeader
            items.add(ListItem.Header(dateHeader))
        }
        items.add(ListItem.Recording(recording))
    }
    return items
}
