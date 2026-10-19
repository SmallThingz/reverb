package app.smallthingz.reverb

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Icon as AndroidIcon
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor

internal data class TileCapacity(val maximumSeconds: Int, val retainedSeconds: Double?)

/** Both choices share one format limit and one coherent retention snapshot. */
internal data class TileCapacities(
    val maximumSeconds: Int,
    val loopingSeconds: Double?,
    val oneShotSeconds: Double?,
) {
    fun forBuffer(buffer: ReverbService.BufferSlot) = TileCapacity(maximumSeconds,
        if (buffer == ReverbService.BufferSlot.LOOPING) loopingSeconds else oneShotSeconds)
}

/** Keep the wheel mounted during refresh without treating its old limits as save authority. */
internal class TileCapacityLoad {
    internal class Request(val revision: Long)
    private var request: Request? = null
    private var readyRevision by mutableStateOf<Long?>(null)
    var visible by mutableStateOf<TileCapacities?>(null)
        private set

    fun begin(revision: Long?): Request? {
        readyRevision = null
        return revision?.let(::Request).also { request = it }
    }

    fun isCurrent(candidate: Request, liveRevision: Long?): Boolean =
        request === candidate && candidate.revision == liveRevision

    fun finish(candidate: Request, liveRevision: Long?, capacities: TileCapacities): Boolean {
        if (!isCurrent(candidate, liveRevision)) return false
        visible = capacities
        readyRevision = candidate.revision
        return true
    }

    fun ready(liveRevision: Long?): TileCapacities? =
        visible.takeIf { liveRevision != null && readyRevision == liveRevision }
}

private fun readTileCapacities(context: Context): TileCapacities = withRetentionPersistenceLock {
    val rate = getConfiguredSampleRate(context)
    val channels = getConfiguredChannelMode(context).channelCount
    val sampleFormat = getConfiguredPcmSampleFormat(context)
    val formatLimit = exportDurationLimitExactSeconds(
        getConfiguredOutputFormat(context), ExportCodec.PCM_16, rate, channels, sampleFormat,
    )
    val retention = retentionConfigurationForRead(context)
    fun retained(buffer: ReverbService.BufferSlot) = when (retention.mode) {
        RetentionMode.TIME -> (if (buffer == ReverbService.BufferSlot.LOOPING)
            retention.loopingSeconds else retention.oneShotSeconds).takeIf { it > 0 }?.toDouble()
        RetentionMode.SIZE -> {
            val bytes = if (buffer == ReverbService.BufferSlot.LOOPING)
                retention.loopingSizeBytes else retention.oneShotSizeBytes
            val bytesPerSecond = rate.toDouble() * channels * sampleFormat.bytesPerSample
            if (bytes > 0 && bytesPerSecond > 0) bytes / bytesPerSecond else null
        }
    }
    TileCapacities(floor(formatLimit.coerceIn(0.0, 86_400.0)).toInt(),
        retained(ReverbService.BufferSlot.LOOPING), retained(ReverbService.BufferSlot.ONE_SHOT))
}

private fun Context.quickTileActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.quickTileActivity()
    else -> null
}

private fun requestQuickTile(context: Context, option: QuickSaveTileOption, result: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        result(context.getString(R.string.tiles_ui_manual, option.slot + 1))
        return
    }
    val activity = context.quickTileActivity()
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        result(context.getString(R.string.tiles_ui_add_failed))
        return
    }
    try {
        val request = QuickSaveTileOptions.beginAddRequest(option)
        val label = context.getString(if (option.buffer == ReverbService.BufferSlot.LOOPING)
            R.string.buffer_loop else R.string.buffer_one_shot)
        activity.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, quickSaveTileClass(option.slot)), label,
            AndroidIcon.createWithBitmap(quickSaveTileBitmap(option.buffer, option.amount, option.unit)),
            ContextCompat.getMainExecutor(context),
        ) { code ->
            val added = code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
            QuickSaveTileOptions.recordAddRequestResult(context.applicationContext, request, added)
            result(context.getString(when {
                added -> R.string.tiles_ui_added
                code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> R.string.tiles_ui_not_added
                else -> R.string.tiles_ui_add_failed
            }))
        }
    } catch (_: RuntimeException) {
        result(context.getString(R.string.tiles_ui_add_failed))
    }
}

/** Drafts and accepted mutations belong to the Settings screen, not a recycled lazy item. */
internal class QuickSettingsTileEditorTarget(val existing: QuickSaveTileOption?) {
    internal var buffer by mutableStateOf(existing?.buffer ?: ReverbService.BufferSlot.LOOPING)
        private set
    internal var seconds by mutableIntStateOf(existing?.seconds ?: DEFAULT_QUICK_SAVE_SECONDS)
    internal var busy by mutableStateOf(false)
    internal var editRevision by mutableLongStateOf(0L)
    private var lastWheelOwner: Pair<Long, Long>? = null
    private var wheelRevision = 0L

    internal fun selectBuffer(selected: ReverbService.BufferSlot): Boolean {
        if (busy || buffer == selected) return false
        buffer = selected
        editRevision++
        return true
    }

    internal fun wheelOwner(parentRevision: Long): Long {
        val current = parentRevision to editRevision
        if (lastWheelOwner != current) {
            lastWheelOwner = current
            wheelRevision++
        }
        return wheelRevision
    }
}

internal class QuickSettingsTileDeleteTarget(val option: QuickSaveTileOption, val editRevision: Long) {
    fun canConfirm(
        currentTarget: QuickSettingsTileDeleteTarget?,
        currentRevision: Long?,
        currentOption: QuickSaveTileOption?,
        sheetReady: Boolean,
        mutationAvailable: Boolean,
    ): Boolean = currentTarget === this && currentRevision == editRevision &&
        currentOption == option && sheetReady && mutationAvailable
}

internal class QuickSettingsTilesEditorState {
    internal var target by mutableStateOf<QuickSettingsTileEditorTarget?>(null)
    internal var deleting by mutableStateOf<QuickSettingsTileDeleteTarget?>(null)
    internal var deletingBusy by mutableStateOf(false)
    internal var pinning by mutableStateOf<Any?>(null)
    val mutationInProgress: Boolean get() = target?.busy == true || deletingBusy
    val editorOpen: Boolean get() = target != null || deleting != null || mutationInProgress

    fun closeEditor(): Boolean {
        if (mutationInProgress) return false
        target = null
        deleting = null
        return true
    }
}

/** Tile definitions save separately because Android can use them immediately. */
@Composable
internal fun QuickSettingsTilesControls(
    active: Boolean,
    interactionReady: Boolean,
    durationSubmissionAllowed: Boolean,
    editorState: QuickSettingsTilesEditorState,
    editRevision: Long,
    readEditRevision: () -> Long?,
    readSheetEditRevision: () -> Long?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val options by QuickSaveTileOptions.options.collectAsState()
    val failure by QuickSaveTileOptions.error.collectAsState()
    val observations by QuickSaveTileOptions.addedStates.collectAsState()
    val observationFailure by QuickSaveTileOptions.addedStateError.collectAsState()
    val scope = rememberCoroutineScope()
    val chrome = appChrome()
    val interactive = active && interactionReady && lifecycleState.isAtLeast(Lifecycle.State.RESUMED) &&
        editorState.pinning == null && !editorState.mutationInProgress && readSheetEditRevision() != null
    fun inputReady() = readEditRevision() != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(active, interactionReady) {
        if (active && interactionReady) QuickSaveTileOptions.refresh(context)
    }
    fun open(option: QuickSaveTileOption?) {
        if (!interactive || !inputReady() || failure != null || editorState.editorOpen) return
        if (option != null && QuickSaveTileOptions.options.value?.find { it.slot == option.slot } != option) return
        editorState.target = QuickSettingsTileEditorTarget(option)
    }
    fun add(option: QuickSaveTileOption) {
        if (!interactive || !inputReady() || editorState.editorOpen || QuickSaveTileOptions.error.value != null) return
        if (QuickSaveTileOptions.options.value?.find { it.slot == option.slot } != option ||
            quickSaveTileAddedState(option, QuickSaveTileOptions.addedStates.value) == QuickSaveTileAddedState.ADDED) return
        val request = Any()
        editorState.pinning = request
        requestQuickTile(context, option) { result ->
            if (editorState.pinning === request) {
                editorState.pinning = null
                AppFeedbackCenter.post(result)
            }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.tiles_ui_title), style = MaterialTheme.typography.titleMedium, color = chrome.ink)
        Surface(shape = RoundedCornerShape(20.dp), color = chrome.field, border = BorderStroke(1.dp, chrome.border)) {
            Column(Modifier.fillMaxWidth()) {
                val items = options.orEmpty()
                for ((index, option) in items.withIndex()) key(option.slot, option.revision) {
                    val enabled = interactive && !editorState.editorOpen && failure == null
                    Surface(onClick = { open(option) }, enabled = enabled, color = chrome.field,
                        modifier = Modifier.settingsAudioDivider(top = index > 0)) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            val bitmap = remember(option) { quickSaveTileBitmap(option.buffer, option.amount, option.unit).asImageBitmap() }
                            Image(bitmap, null, Modifier.size(32.dp), colorFilter = ColorFilter.tint(chrome.ink))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(stringResource(if (option.buffer == ReverbService.BufferSlot.LOOPING)
                                    R.string.buffer_loop else R.string.buffer_one_shot), style = MaterialTheme.typography.bodyLarge)
                                Text(formatDurationInput(option.seconds), style = MaterialTheme.typography.bodySmall, color = chrome.muted)
                            }
                            val added = quickSaveTileAddedState(option, observations)
                            if (added != QuickSaveTileAddedState.ADDED) IconButton(onClick = { add(option) }, enabled = enabled) {
                                Icon(if (added == QuickSaveTileAddedState.NOT_ADDED) Icons.Rounded.Add else AppIcons.settings,
                                    stringResource(if (added == QuickSaveTileAddedState.NOT_ADDED) R.string.tiles_ui_add else R.string.tiles_ui_setup))
                            }
                            IconButton(onClick = {
                                val revision = readEditRevision() ?: return@IconButton
                                if (inputReady() && enabled) editorState.deleting = QuickSettingsTileDeleteTarget(option, revision)
                            }, enabled = enabled) {
                                Icon(AppIcons.delete, stringResource(R.string.tiles_ui_delete))
                            }
                        }
                    }
                }
                if (options == null && failure == null) TileStatus(stringResource(R.string.tiles_ui_loading), Modifier.padding(16.dp))
                if (failure == null && options != null && items.size < QUICK_SAVE_TILE_COUNT) {
                    TextButton(onClick = { open(null) }, enabled = interactive && !editorState.editorOpen && durationSubmissionAllowed,
                        modifier = Modifier.fillMaxWidth().settingsAudioDivider(top = items.isNotEmpty())) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(20.dp))
                        Text(stringResource(R.string.tiles_ui_create), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        editorState.target?.takeIf { active }?.let { target -> key(target) {
            QuickTileInlineEditor(target, editorState, interactionReady, durationSubmissionAllowed,
                editRevision, readEditRevision, readSheetEditRevision)
        } }
        if (!durationSubmissionAllowed) TileStatus(stringResource(R.string.tiles_ui_apply_first))
        (failure ?: observationFailure)?.let {
            TileStatus(it)
            TextButton(onClick = { if (inputReady()) QuickSaveTileOptions.refresh(context) }, enabled = interactive) {
                Text(stringResource(R.string.tiles_ui_retry))
            }
        }
    }
    editorState.deleting?.takeIf { active }?.let { target ->
        ReverbActionSheet(
            title = stringResource(R.string.tiles_ui_delete),
            onDismiss = { if (!editorState.deletingBusy) editorState.deleting = null },
            content = { Text(stringResource(R.string.tiles_ui_delete_confirmation)) },
            actions = {
                val sheetView = LocalView.current
                val sheetWindow = LocalWindowInfo.current
                val sheetLifecycle = LocalLifecycleOwner.current.lifecycle
                val sheetLifecycleState by sheetLifecycle.currentStateFlow.collectAsState()
                fun confirmationReady(): Boolean = target.canConfirm(
                    currentTarget = editorState.deleting,
                    currentRevision = readSheetEditRevision(),
                    currentOption = QuickSaveTileOptions.options.value?.find { it.slot == target.option.slot },
                    sheetReady = sheetLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        sheetView.isAttachedToWindow && sheetView.isShown && sheetView.hasWindowFocus(),
                    mutationAvailable = active && interactionReady && editorState.pinning == null &&
                        !editorState.mutationInProgress && QuickSaveTileOptions.error.value == null,
                )
                val confirmationEnabled = target.canConfirm(
                    currentTarget = editorState.deleting,
                    currentRevision = readSheetEditRevision(),
                    currentOption = options?.find { it.slot == target.option.slot },
                    sheetReady = sheetWindow.isWindowFocused && sheetLifecycleState.isAtLeast(Lifecycle.State.RESUMED),
                    mutationAvailable = active && interactionReady && editorState.pinning == null &&
                        !editorState.mutationInProgress && failure == null,
                )
                TextButton(onClick = { editorState.deleting = null }, enabled = !editorState.deletingBusy) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = {
                    if (!confirmationReady()) return@TextButton
                    editorState.deletingBusy = true
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        withContext(NonCancellable) {
                            try {
                                withContext(Dispatchers.IO) { QuickSaveTileOptions.remove(context.applicationContext, target.option) }
                                AppFeedbackCenter.post(context.getString(R.string.tiles_ui_deleted))
                            } catch (error: Exception) {
                                AppFeedbackCenter.post(error.message ?: context.getString(R.string.tile_save_failed), FeedbackTone.ERROR)
                            } finally {
                                editorState.deletingBusy = false
                                if (editorState.deleting === target) editorState.deleting = null
                            }
                        }
                    }
                }, enabled = confirmationEnabled) { Text(stringResource(R.string.delete_recording)) }
            },
        )
    }
}

@Composable
private fun TileStatus(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = appChrome().muted,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun QuickTileInlineEditor(
    target: QuickSettingsTileEditorTarget,
    owner: QuickSettingsTilesEditorState,
    interactionReady: Boolean,
    durationSubmissionAllowed: Boolean,
    editRevision: Long,
    readEditRevision: () -> Long?,
    readDraftRevision: () -> Long?,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val chrome = appChrome()
    val capacityLoad = remember { TileCapacityLoad() }
    var message by remember { mutableStateOf<String?>(null) }
    var shortened by remember { mutableStateOf<TileCapacity?>(null) }
    var capacityRetry by remember { mutableLongStateOf(0L) }
    var attached by remember { mutableStateOf(true) }
    DisposableEffect(target) { onDispose { attached = false } }
    fun admission(): Long? = readEditRevision()?.takeIf {
        owner.target === target && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    fun draftAdmission(): Long? = readDraftRevision()?.takeIf {
        attached && owner.target === target && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    LaunchedEffect(interactionReady, durationSubmissionAllowed, editRevision, lifecycleState, capacityRetry) {
        val request = capacityLoad.begin(draftAdmission())
        shortened = null
        if (request == null) return@LaunchedEffect
        try {
            val read = withContext(Dispatchers.IO) { readTileCapacities(context.applicationContext) }
            if (currentCoroutineContext().isActive && capacityLoad.finish(request, draftAdmission(), read)) message = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (currentCoroutineContext().isActive && capacityLoad.isCurrent(request, draftAdmission())) {
                message = error.message ?: context.getString(R.string.tile_save_failed)
            }
        }
    }
    fun close() { if (!target.busy && owner.target === target) owner.target = null }
    fun save() {
        val revision = admission() ?: return
        if (target.busy || capacityLoad.ready(revision) == null) return
        val buffer = target.buffer
        val seconds = target.seconds
        val confirmed = shortened
        target.busy = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    val freshCapacities = withContext(Dispatchers.IO) { readTileCapacities(context.applicationContext) }
                    if (admission() != revision) {
                        AppFeedbackCenter.post(context.getString(R.string.tiles_ui_save_interrupted), FeedbackTone.ERROR)
                        return@withContext
                    }
                    val refresh = capacityLoad.begin(revision)!!
                    capacityLoad.finish(refresh, admission(), freshCapacities)
                    val fresh = freshCapacities.forBuffer(buffer)
                    when {
                        seconds > fresh.maximumSeconds -> message = context.getString(R.string.tiles_ui_maximum, formatDurationInput(fresh.maximumSeconds))
                        fresh.retainedSeconds == null -> message = context.getString(R.string.tiles_ui_disabled_buffer)
                        seconds > fresh.retainedSeconds && confirmed != fresh -> {
                            shortened = fresh
                            message = context.getString(R.string.tiles_ui_shorter,
                                if (fresh.retainedSeconds < 1.0) context.getString(R.string.tiles_ui_less_than_second)
                                else formatDurationInput(floor(fresh.retainedSeconds).toInt()))
                        }
                        else -> {
                            val (amount, unit) = quickTileAmountAndUnit(seconds)
                            withContext(Dispatchers.IO) { QuickSaveTileOptions.save(context.applicationContext, target.existing, buffer, amount, unit) }
                            if (owner.target === target) owner.target = null
                            AppFeedbackCenter.post(context.getString(R.string.tiles_ui_saved))
                        }
                    }
                } catch (error: Exception) {
                    val text = error.message ?: context.getString(R.string.tile_save_failed)
                    if (attached) message = text else AppFeedbackCenter.post(text, FeedbackTone.ERROR)
                } finally { target.busy = false }
            }
        }
    }
    val enabled = interactionReady && durationSubmissionAllowed && !target.busy && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val capacities = capacityLoad.visible
    val capacity = capacities?.forBuffer(target.buffer)
    val capacityReady = capacityLoad.ready(editRevision) != null
    Surface(shape = RoundedCornerShape(20.dp), color = chrome.field, border = BorderStroke(1.dp, chrome.border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (target.existing == null) R.string.tiles_ui_create else R.string.tiles_ui_edit),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = ::close, enabled = !target.busy) { Icon(AppIcons.close, stringResource(R.string.cancel)) }
            }
            BufferSelector(
                selectedBuffer = target.buffer,
                activeBuffer = null,
                isListening = false,
                oneShotEnabled = capacities?.oneShotSeconds != null,
                oneShotFull = false,
                loopingEnabled = capacities?.loopingSeconds != null,
                interactionEnabled = enabled,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                onSelectBuffer = { buffer ->
                    val revision = admission()
                    if (enabled && revision != null && target.selectBuffer(buffer)) {
                        shortened = null
                        message = null
                        if (capacityLoad.ready(revision) == null) capacityRetry++
                    }
                },
            )
            when {
                !durationSubmissionAllowed -> Unit
                capacity == null -> if (message == null) TileStatus(stringResource(R.string.tiles_ui_loading))
                capacity.maximumSeconds < 1 -> TileStatus(stringResource(R.string.tiles_ui_unavailable))
                else -> {
                    val maximum = quickTileRepresentableSeconds(capacity.maximumSeconds)
                    NotificationDurationWheel(target.seconds, maximum, { seconds ->
                        val revision = admission()
                        if (enabled && !target.busy && revision != null && capacityLoad.ready(revision) != null) {
                            target.editRevision++
                            target.seconds = quickTileRepresentableSeconds(seconds.coerceIn(1, maximum))
                            shortened = null
                            message = null
                            NotificationDurationCommit(target.seconds, target.wheelOwner(revision))
                        } else null
                    }, enabled = enabled && capacityReady, editRevision = target.wheelOwner(editRevision), readEditRevision = {
                        admission()?.takeIf { attached && !target.busy && capacityLoad.ready(it)?.maximumSeconds?.let(::quickTileRepresentableSeconds) == maximum }?.let(target::wheelOwner)
                    }, stepDuration = ::quickTileStepSeconds)
                    if (target.seconds > maximum) TileStatus(stringResource(R.string.tiles_ui_reduce_duration, formatDurationInput(target.seconds)))
                }
            }
            message?.let { TileStatus(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = ::save, enabled = enabled && capacityReady && capacity?.maximumSeconds?.let { it >= target.seconds } == true) {
                    Text(stringResource(if (shortened == null) R.string.tiles_ui_save else R.string.tiles_ui_save_anyway))
                }
            }
        }
    }
}
