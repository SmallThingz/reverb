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
import androidx.compose.ui.platform.LocalResources
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

@Composable
private fun ReleasedSettingsTileStatus(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = appChrome().muted,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
internal fun ReleasedSettingsQuickTileInlineEditor(
    target: LegacyQuickSettingsTileEditorTarget,
    owner: LegacyQuickSettingsTilesEditorState,
    interactionReady: Boolean,
    durationSubmissionAllowed: Boolean,
    editRevision: Long,
    readEditRevision: () -> Long?,
    readDraftRevision: () -> Long?,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val chrome = appChrome()
    val capacityLoad = remember { LegacyTileCapacityLoad() }
    val durationInteraction = remember { LegacyNotificationDurationInteractionState() }
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
                message = error.message ?: resources.getString(R.string.tile_save_failed)
            }
        }
    }
    fun close() { if (!target.busy && owner.target === target) owner.closeEditor() }
    fun save() {
        if (durationInteraction.pending) return
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
                        AppFeedbackCenter.post(resources.getString(R.string.tiles_ui_save_interrupted), FeedbackTone.ERROR)
                        return@withContext
                    }
                    val refresh = capacityLoad.begin(revision)!!
                    capacityLoad.finish(refresh, admission(), freshCapacities)
                    val fresh = freshCapacities.forBuffer(buffer)
                    when {
                        seconds > fresh.maximumSeconds -> message = resources.getString(R.string.tiles_ui_maximum, formatDurationInput(fresh.maximumSeconds))
                        fresh.retainedSeconds == null -> message = resources.getString(R.string.tiles_ui_disabled_buffer)
                        seconds > fresh.retainedSeconds && confirmed != fresh -> {
                            shortened = fresh
                            message = resources.getString(R.string.tiles_ui_shorter,
                                if (fresh.retainedSeconds < 1.0) resources.getString(R.string.tiles_ui_less_than_second)
                                else formatDurationInput(floor(fresh.retainedSeconds).toInt()))
                        }
                        else -> {
                            val (amount, unit) = quickTileAmountAndUnit(seconds)
                            withContext(Dispatchers.IO) { QuickSaveTileOptions.create(context.applicationContext, buffer, amount, unit) }
                            if (owner.target === target) owner.target = null
                            AppFeedbackCenter.post(resources.getString(R.string.tiles_ui_saved))
                        }
                    }
                } catch (error: Exception) {
                    val text = error.message ?: resources.getString(R.string.tile_save_failed)
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
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
                capacity == null -> if (message == null) ReleasedSettingsTileStatus(stringResource(R.string.tiles_ui_loading))
                capacity.maximumSeconds < 1 -> ReleasedSettingsTileStatus(stringResource(R.string.tiles_ui_unavailable))
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
                    }, interactionState = durationInteraction, stepDuration = ::quickTileStepSeconds)
                    if (target.seconds > maximum) ReleasedSettingsTileStatus(stringResource(R.string.tiles_ui_reduce_duration, formatDurationInput(target.seconds)))
                }
            }
            message?.let { ReleasedSettingsTileStatus(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = ::save, enabled = enabled && !durationInteraction.pending && capacityReady && capacity?.maximumSeconds?.let { it >= target.seconds } == true) {
                    Text(stringResource(if (shortened == null) R.string.tiles_ui_save else R.string.tiles_ui_save_anyway))
                }
            }
        }
    }
}
