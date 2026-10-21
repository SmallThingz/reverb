package app.smallthingz.reverb

import android.content.Context
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor

internal data class NativeQuickTilesInputs(
    val active: Boolean,
    val interactionReady: Boolean,
    val durationSubmissionAllowed: Boolean,
    val editRevision: Long,
)

/**
 * Direct owner of released QuickSettingsTilesControls effects/actions. Existing editor state remains
 * the sole draft/accepted-mutation state. No tile editing or optimistic durable state is introduced.
 * The caller owns this at the keyed Settings section lifetime, not at an individual row lifetime.
 */
internal class NativeQuickSettingsTilesOwner(
    private val context: Context,
    val lifecycle: Lifecycle,
    parentScope: CoroutineScope,
    val editorState: QuickSettingsTilesEditorState,
    initial: NativeQuickTilesInputs,
    private val readActionRevision: () -> Long?,
    private val readSheetActionRevision: () -> Long?,
    private val readEditRevision: () -> Long?,
    private val readSheetEditRevision: () -> Long?,
) : AutoCloseable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private var attached = true
    private var started = false
    private var refreshKey: Pair<Boolean, Boolean>? = null
    private var changed: () -> Unit = {}
    var inputs = initial
        private set
    var inline: NativeQuickTileInlineOwner? = null
        private set
    val options get() = QuickSaveTileOptions.options.value
    val failure get() = QuickSaveTileOptions.error.value
    val observationFailure get() = QuickSaveTileOptions.addedStateError.value
    val observations get() = QuickSaveTileOptions.addedStates.value
    val interactive get() = inputs.active && inputs.interactionReady &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        editorState.pinning == null && !editorState.mutationInProgress &&
        quickSettingsTileActionRevision(inputs.editRevision, readSheetActionRevision()) != null

    /** Install only after the renderer exists; callbacks read this owner, never copy its state. */
    fun start(onChanged: () -> Unit) {
        check(attached)
        changed = onChanged
        if (!started) {
            started = true
            scope.launch { QuickSaveTileOptions.options.collect { notifyChanged() } }
            scope.launch { QuickSaveTileOptions.error.collect { notifyChanged() } }
            scope.launch { QuickSaveTileOptions.addedStates.collect { notifyChanged() } }
            scope.launch { QuickSaveTileOptions.addedStateError.collect { notifyChanged() } }
            scope.launch {
                lifecycle.currentStateFlow.collect {
                    inline?.bind(inputs)
                    notifyChanged()
                }
            }
        }
        bind(inputs)
    }

    fun bind(next: NativeQuickTilesInputs) {
        check(attached)
        inputs = next
        reconcileInline()
        val key = next.active to next.interactionReady
        if (refreshKey != key) {
            refreshKey = key
            if (next.active && next.interactionReady) QuickSaveTileOptions.refresh(context)
        }
        inline?.bind(next)
    }

    private fun actionRevision(): Long? =
        quickSettingsTileActionRevision(inputs.editRevision, readActionRevision())?.takeIf {
            inputs.active && inputs.interactionReady &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                editorState.pinning == null && !editorState.mutationInProgress
        }

    fun openCreation() {
        if (!interactive || actionRevision() == null || readEditRevision() != inputs.editRevision ||
            failure != null || editorState.editorOpen) return
        editorState.target = QuickSettingsTileEditorTarget()
        notifyChanged()
    }

    fun add(option: QuickSaveTileOption) {
        if (!interactive || actionRevision() == null || editorState.editorOpen || failure != null) return
        if (options?.find { it.slot == option.slot } != option ||
            quickSaveTileAddedState(option, observations) == QuickSaveTileAddedState.ADDED) return
        val request = Any()
        editorState.pinning = request
        notifyChanged()
        requestQuickTile(context, option) { result ->
            if (editorState.pinning === request) {
                editorState.pinning = null
                AppFeedbackCenter.post(result)
                notifyChanged()
            }
        }
    }

    fun requestDelete(option: QuickSaveTileOption) {
        val revision = actionRevision() ?: return
        val enabled = interactive && !editorState.editorOpen && failure == null
        if (enabled && !editorState.editorOpen && failure == null &&
            options?.find { it.slot == option.slot } == option) {
            editorState.deleting = QuickSettingsTileDeleteTarget(option, revision)
            notifyChanged()
        }
    }

    fun dismissDelete() {
        if (!editorState.deletingBusy) {
            editorState.deleting = null
            notifyChanged()
        }
    }

    /** sheetReady comes from the actual sheet lifecycle/window, including attachment on dispatch. */
    fun canConfirmDelete(target: QuickSettingsTileDeleteTarget, sheetReady: Boolean): Boolean =
        target.canConfirm(editorState.deleting, readSheetActionRevision(),
            options?.find { it.slot == target.option.slot }, sheetReady,
            inputs.active && inputs.interactionReady && editorState.pinning == null &&
                !editorState.mutationInProgress && failure == null)

    fun confirmDelete(target: QuickSettingsTileDeleteTarget, sheetReady: Boolean) {
        if (!canConfirmDelete(target, sheetReady)) return
        editorState.deletingBusy = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    withContext(Dispatchers.IO) {
                        QuickSaveTileOptions.remove(context.applicationContext, target.option)
                    }
                    AppFeedbackCenter.post(context.getString(R.string.tiles_ui_deleted))
                } catch (error: Exception) {
                    AppFeedbackCenter.post(error.message ?: context.getString(R.string.tile_save_failed),
                        FeedbackTone.ERROR)
                } finally {
                    editorState.deletingBusy = false
                    if (editorState.deleting === target) editorState.deleting = null
                    notifyChanged()
                }
            }
        }
        notifyChanged()
    }

    fun retry() {
        if (interactive && actionRevision() != null) QuickSaveTileOptions.refresh(context)
    }

    private fun reconcileInline() {
        val target = editorState.target?.takeIf { inputs.active }
        if (inline?.target === target) return
        inline?.close()
        inline = target?.let {
            NativeQuickTileInlineOwner(context, lifecycle, scope, editorState, it, inputs,
                readEditRevision, readSheetEditRevision, ::notifyChanged).also { owner -> owner.bind(inputs) }
        }
    }

    private fun notifyChanged() {
        if (!attached) return
        reconcileInline()
        changed()
    }

    override fun close() {
        if (!attached) return
        attached = false
        changed = {}
        inline?.close()
        inline = null
        // The accepted UNDISPATCHED/NonCancellable mutation completes exactly as in the source.
        scope.cancel()
    }
}

/** Released QuickTileInlineEditor effects and callbacks, with explicit native render notifications. */
internal class NativeQuickTileInlineOwner(
    private val context: Context,
    private val lifecycle: Lifecycle,
    parentScope: CoroutineScope,
    private val owner: QuickSettingsTilesEditorState,
    val target: QuickSettingsTileEditorTarget,
    initial: NativeQuickTilesInputs,
    private val readEditRevision: () -> Long?,
    private val readDraftRevision: () -> Long?,
    private val changed: () -> Unit,
) : AutoCloseable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private var attached = true
    private var inputs = initial
    private var loadJob: Job? = null
    private var loadKey: LoadKey? = null
    private var retry = 0L
    val capacityLoad = TileCapacityLoad()
    val durationInteraction = NotificationDurationInteractionState()
    var message: String? = null
        private set
    var shortened: TileCapacity? = null
        private set
    private data class LoadKey(val ready: Boolean, val allowed: Boolean, val revision: Long,
        val lifecycle: Lifecycle.State, val retry: Long)
    val enabled get() = inputs.interactionReady && inputs.durationSubmissionAllowed && !target.busy &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    val durationSubmissionAllowed get() = inputs.durationSubmissionAllowed
    val renderedRevision get() = inputs.editRevision
    val capacities get() = capacityLoad.visible
    val capacity get() = capacities?.forBuffer(target.buffer)
    val capacityReady get() = capacityLoad.ready(inputs.editRevision) != null
    val saveEnabled get() = enabled && !durationInteraction.pending && capacityReady &&
        capacity?.maximumSeconds?.let { it >= target.seconds } == true

    private fun admission(): Long? = readEditRevision()?.takeIf {
        owner.target === target && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    private fun draftAdmission(): Long? = readDraftRevision()?.takeIf {
        attached && owner.target === target && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    fun bind(next: NativeQuickTilesInputs) {
        if (!attached) return
        inputs = next
        val key = LoadKey(next.interactionReady, next.durationSubmissionAllowed,
            next.editRevision, lifecycle.currentState, retry)
        if (loadKey == key) return
        loadKey = key
        loadJob?.cancel()
        val request = capacityLoad.begin(draftAdmission())
        shortened = null
        if (request == null) return
        loadJob = scope.launch {
            try {
                val read = withContext(Dispatchers.IO) { readTileCapacities(context.applicationContext) }
                if (currentCoroutineContext().isActive &&
                    capacityLoad.finish(request, draftAdmission(), read)) {
                    message = null
                    changed()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (currentCoroutineContext().isActive && capacityLoad.isCurrent(request, draftAdmission())) {
                    message = error.message ?: context.getString(R.string.tile_save_failed)
                    changed()
                }
            }
        }
    }

    fun dismiss() {
        if (!target.busy && owner.target === target) {
            owner.closeEditor()
            changed()
        }
    }

    fun selectBuffer(buffer: ReverbService.BufferSlot) {
        val revision = admission()
        if (enabled && revision != null && target.selectBuffer(buffer)) {
            shortened = null
            message = null
            if (capacityLoad.ready(revision) == null) {
                retry++
                bind(inputs)
            }
            changed()
        }
    }

    fun wheelOwner(maximum: Int): Long? = admission()?.takeIf {
        attached && !target.busy &&
            capacityLoad.ready(it)?.maximumSeconds?.let(::quickTileRepresentableSeconds) == maximum
    }?.let(target::wheelOwner)

    fun changeSeconds(seconds: Int, maximum: Int): NotificationDurationCommit? {
        val revision = admission()
        if (!enabled || target.busy || revision == null || capacityLoad.ready(revision) == null) return null
        target.editRevision++
        target.seconds = quickTileRepresentableSeconds(seconds.coerceIn(1, maximum))
        shortened = null
        message = null
        val result = NotificationDurationCommit(target.seconds, target.wheelOwner(revision))
        // The wheel publishes after committing its binding, never rebind it mid-commit.
        return result
    }

    fun interactionChanged() { if (attached) changed() }

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
                    val freshCapacities = withContext(Dispatchers.IO) {
                        readTileCapacities(context.applicationContext)
                    }
                    if (admission() != revision) {
                        AppFeedbackCenter.post(context.getString(R.string.tiles_ui_save_interrupted),
                            FeedbackTone.ERROR)
                        return@withContext
                    }
                    val refresh = capacityLoad.begin(revision)!!
                    capacityLoad.finish(refresh, admission(), freshCapacities)
                    val fresh = freshCapacities.forBuffer(buffer)
                    when {
                        seconds > fresh.maximumSeconds -> message = context.getString(
                            R.string.tiles_ui_maximum, formatDurationInput(fresh.maximumSeconds))
                        fresh.retainedSeconds == null -> message = context.getString(R.string.tiles_ui_disabled_buffer)
                        seconds > fresh.retainedSeconds && confirmed != fresh -> {
                            shortened = fresh
                            message = context.getString(R.string.tiles_ui_shorter,
                                if (fresh.retainedSeconds < 1.0)
                                    context.getString(R.string.tiles_ui_less_than_second)
                                else formatDurationInput(floor(fresh.retainedSeconds).toInt()))
                        }
                        else -> {
                            val (amount, unit) = quickTileAmountAndUnit(seconds)
                            withContext(Dispatchers.IO) {
                                QuickSaveTileOptions.create(context.applicationContext, buffer, amount, unit)
                            }
                            if (owner.target === target) owner.target = null
                            AppFeedbackCenter.post(context.getString(R.string.tiles_ui_saved))
                        }
                    }
                } catch (error: Exception) {
                    val text = error.message ?: context.getString(R.string.tile_save_failed)
                    if (attached) message = text else AppFeedbackCenter.post(text, FeedbackTone.ERROR)
                } finally {
                    target.busy = false
                    changed()
                }
            }
        }
        changed()
    }

    override fun close() {
        if (!attached) return
        attached = false
        scope.cancel()
    }
}
