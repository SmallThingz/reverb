package app.smallthingz.reverb

import app.smallthingz.nativeui.runtime.getValue
import app.smallthingz.nativeui.runtime.mutableIntStateOf
import app.smallthingz.nativeui.runtime.mutableLongStateOf
import app.smallthingz.nativeui.runtime.mutableStateOf
import app.smallthingz.nativeui.runtime.setValue

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

/** Drafts and accepted mutations belong to the Settings screen, not a recycled lazy item. */
internal class QuickSettingsTileEditorTarget {
    internal var buffer by mutableStateOf(ReverbService.BufferSlot.LOOPING)
        private set
    internal var seconds by mutableIntStateOf(DEFAULT_QUICK_SAVE_SECONDS)
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

    /** Only values cross Activity recreation; each replacement gets new edit/gesture ownership. */
    internal fun saveDraft(): List<Any> = listOf(
        1, buffer.storageCode.toInt(), seconds,
        "", // Keep the creation-draft format compatible with previously saved state.
    )

    companion object {
        internal fun restoreDraft(saved: List<Any>): QuickSettingsTileEditorTarget? = runCatching {
            require(saved.size == 4 && saved[0] == 1)
            val buffer = requireNotNull(ReverbService.BufferSlot.fromStorageCode(saved[1] as Int))
            val seconds = saved[2] as Int
            require(seconds in 1..86_400 && quickTileRepresentableSeconds(seconds) == seconds)
            // Older versions saved edits of existing definitions here. Never restore one as a
            // new tile or reintroduce an editing action after an update/recreation.
            require(saved[3] == "")
            QuickSettingsTileEditorTarget().also {
                it.buffer = buffer
                it.seconds = seconds
            }
        }.getOrNull()
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

/** Reject callbacks belonging to a parent draft that changed before recomposition. */
internal fun quickSettingsTileActionRevision(renderedRevision: Long, currentRevision: Long?): Long? =
    currentRevision?.takeIf { it == renderedRevision }

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

internal class QuickSettingsTilesEditorState private constructor(restored: QuickSettingsTileEditorTarget?) {
    constructor() : this(null)

    private var pendingDraft by mutableStateOf(restored)
    internal var target by mutableStateOf<QuickSettingsTileEditorTarget?>(null)
    internal var deleting by mutableStateOf<QuickSettingsTileDeleteTarget?>(null)
    internal var deletingBusy by mutableStateOf(false)
    internal var pinning by mutableStateOf<Any?>(null)
    val mutationInProgress: Boolean get() = target?.busy == true || deletingBusy
    val editorOpen: Boolean get() = pendingDraft != null || target != null || deleting != null || mutationInProgress

    fun closeEditor(): Boolean {
        if (mutationInProgress) return false
        pendingDraft = null
        target = null
        deleting = null
        return true
    }

    /** A pending restore survives another rotation, but never restores an accepted mutation. */
    fun save(panelActive: Boolean): List<Any> {
        if (!panelActive || mutationInProgress || deleting != null || pinning != null) return emptyList()
        return (target ?: pendingDraft)?.saveDraft().orEmpty()
    }

    /** Called only after the replacement Settings owner finishes its fresh parent hydration. */
    fun restorePendingDraft(parentReady: Boolean): Boolean {
        if (!parentReady) return false
        val restored = pendingDraft ?: return false
        pendingDraft = null
        if (target != null || deleting != null || pinning != null || mutationInProgress) return false
        target = restored
        return true
    }

    companion object {
        fun restore(saved: List<Any>) = QuickSettingsTilesEditorState(
            QuickSettingsTileEditorTarget.restoreDraft(saved),
        )
    }
}

