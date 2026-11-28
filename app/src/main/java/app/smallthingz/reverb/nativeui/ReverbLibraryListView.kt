package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeGroupEdges
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.reverb.ReverbLibraryLayout
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.smallthingz.reverb.InlinePlaybackRowOwner
import app.smallthingz.reverb.LibraryListItem
import app.smallthingz.reverb.LibraryListSnapshot
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.samePlaybackRecording
import app.smallthingz.reverb.sameRecordingActionTarget
import app.smallthingz.reverb.PendingSave
import app.smallthingz.reverb.R
import app.smallthingz.reverb.formatRecordingStartTimestamp
import app.smallthingz.reverb.formatSavedRecordingDuration
import app.smallthingz.reverb.formatShortFileSize
import app.smallthingz.reverb.libraryListItemKey
import smallthingz.ui.util.closePreservingPrimaryFailure

/** Existing FilesScreen state is sampled by the owner; this View does not own or mutate it. */
internal data class LibraryNativeListState(
    val items: List<LibraryListItem>,
    val selectedIds: Set<String>,
    val selectionActive: Boolean,
    val activeMutationIds: Set<String>,
    val localOperationBusyIds: Set<String>,
    val expandedOwner: InlinePlaybackRowOwner?,
    val trimRequestRecordingId: String?,
    val screenActive: Boolean,
    val infoRecordingId: String? = null,
    val renameRecordingId: String? = null,
    val playingRecording: RecordingEntity? = null,
    val waveformReadAllowed: Boolean = true,
)

/** Optional fixture work witness; normal screens do not install one. */
internal interface LibraryListWorkObserver {
    fun finishedEntryCreated()
    fun catalogDiff()
    fun rowBound(key: String)
}

/**
 * Existing inline player/trim presentation bridge. Its View is inserted inside the native card's
 * shared background/outline/selection-alpha layer. This lease owns presentation only: retiring it
 * must detach UI delivery and preserve every already accepted process-owned mutation/export.
 *
 * bind(expanded=false) immediately revokes preview admission. The native row independently owns
 * source outer animateContentSize320 and inline expand320/shrink260/fade220+70/140. This lease has
 * no duplicate height/alpha scheduler and is retired after the row reports source exit completion.
 */
internal interface LibraryInlineExpansionHost {
    fun create(context: Context, recordingId: String): LibraryInlineExpansionLease
    fun createInfo(context: Context, recordingId: String): LibraryInlineExpansionLease = error("No Info host")
    fun renameHeader(recordingId: String): View? = null
}

internal interface LibraryInlineExpansionLease {
    val view: View
    fun bind(recording: RecordingEntity, owner: InlinePlaybackRowOwner?, expanded: Boolean,
        screenActive: Boolean, trimRequested: Boolean)
    fun bind(recording: RecordingEntity, owner: InlinePlaybackRowOwner?, expanded: Boolean,
        screenActive: Boolean, trimRequested: Boolean, waveformReadAllowed: Boolean) =
        bind(recording, owner, expanded, screenActive, trimRequested)
    fun setEnvironment(environment: ReverbViewEnvironment)
    fun setPresentationAttached(attached: Boolean)
    fun closePresentation()
}

/**
 * Native counterpart of FilesScreen's populated LazyColumn only. Parent retains its released top
 * bar, loading/empty/error branches, refresh/edge-dismiss, snackbar/Undo, dialogs and controllers.
 *
 * Input records are already filtered by the existing pending-deletion owner. Never pass the hidden
 * deletion map here and never finalize an operation from a View callback or recycler cleanup.
 */
internal class ReverbLibraryListView(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    private val animationScope: CoroutineScope,
    private val nativeInputRoot: View,
    actions: LibraryRecordingRowActions,
    private val inlineHost: LibraryInlineExpansionHost,
    private val onRetryFailedSaves: () -> Unit,
    private val onRowNodesChanged: () -> Unit = {},
) : FrameLayout(context) {
    private var clearingListComposition = false
    private var disposed = false
    var workObserver: LibraryListWorkObserver? = null
    var onViewportGeometryChanged: () -> Unit = {}
    var onDateFormattingChanged: () -> Unit = {}
    var onPendingRecordingRevealCancelled: (() -> Unit)? = null
    private var rowNodesDirty = false
    private var rowNodesPosted = false
    private val publishRowNodes = Runnable {
        rowNodesPosted = false
        if (!disposed && isAttachedToWindow && rowNodesDirty) {
            rowNodesDirty = false
            onRowNodesChanged()
        }
    }
    private fun rowNodesChanged() {
        if (disposed) return
        rowNodesDirty = true
        // Holders are born during RecyclerView layout, after the controller's publication.
        // Coalesce that traversal and bind the actual descendants outside recycler callbacks.
        if (isAttachedToWindow && !rowNodesPosted) {
            rowNodesPosted = true
            post(publishRowNodes)
        }
    }
    private var environment = initialEnvironment
    private var formattingLocales = resources.configuration.locales
    private var formattingTimeZone = java.util.TimeZone.getDefault().id
    private var formattingRevision = 0L
    private val timeFormatObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = invalidateRecordingFormatting()
    }
    private val timeZoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            formattingTimeZone = java.util.TimeZone.getDefault().id
            onDateFormattingChanged()
            invalidateRecordingFormatting()
        }
    }
    private val currentActions = actions
    private val rowUpdates = NativeLibraryListUpdates<InlinePlaybackRowOwner>()
    private var blockedRecordingIds = emptySet<String>()
    private var pendingPosition = NativePendingListPosition(NativeListSavedPosition(0, 0))
    private val expansionReveal = LibraryExpansionReveal()
    private var expansionRevealPosted = false
    private val revealExpansion = Runnable {
        expansionRevealPosted = false
        if (!disposed && isAttachedToWindow) {
            val id = expansionReveal.requestedId
            val row = id?.let(::attachedRecordingAnchor)
            val available = list.height - list.paddingTop - list.paddingBottom
            if (row != null && expansionReveal.changed(row.height, row.width, available)) {
                // The whole entry when it fits; otherwise keep the leading viewport accessible.
                // RV supplies the minimal clamped delta, without a second animation/scroll owner.
                list.requestChildRectangleOnScreen(row, android.graphics.Rect(0, 0, row.width,
                    row.height.coerceAtMost(available)), true)
            }
        }
    }
    private fun scheduleExpansionReveal() {
        if (!disposed && expansionReveal.requestedId != null && isAttachedToWindow && !expansionRevealPosted) {
            expansionRevealPosted = true
            post(revealExpansion)
        }
    }
    private val layout: LinearLayoutManager = object : LinearLayoutManager(context, RecyclerView.VERTICAL, false) {
        override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
            if (!state.isPreLayout) {
                if (list.applyPendingScrollIndicatorPlacement(this, state.itemCount)) {
                    // A top request queued before the thumb moved must not win a later layout pass.
                    pendingPosition = NativePendingListPosition(null)
                } else {
                    pendingPosition.pendingForPlacement(state.itemCount, recordingIndexForPlacement)?.let { target ->
                        scrollToPositionWithOffset(target.firstVisibleItemIndex, -target.firstVisibleItemScrollOffset)
                    }
                }
            }
            super.onLayoutChildren(recycler, state)
        }
        override fun onLayoutCompleted(state: RecyclerView.State) {
            super.onLayoutCompleted(state)
            if (!state.isPreLayout) {
                pendingPosition.placed(placedPosition())
                onViewportGeometryChanged()
                scheduleExpansionReveal()
            }
        }
    }
    private val rowAdapter = LibraryRowsAdapter()
    private val recordingIndexForPlacement: (String) -> Int = rowAdapter::recordingIndex
    private val list = ReverbLibraryRecyclerView(context).apply {
        layoutManager = layout
        adapter = rowAdapter
        sourceOnUserScroll = ::cancelPendingRecordingReveal
        // Released populated LazyColumn does not apply animateItem. Default RV fades/moves differ.
        itemAnimator = null
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        scrollIndicatorPresentationActive = false
        bindScrollIndicator(initialEnvironment.appearance.scrollIndicator, initialEnvironment.palette.muted)
        setHasFixedSize(false)
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = onViewportGeometryChanged()
        })
    }
    val scrollView: ReverbLibraryRecyclerView get() = list
    private val guardedActions = LibraryRecordingRowActions(
        activate = { id -> if (admit(id)) currentActions.activate?.invoke(id) },
        longClick = { id -> if (admit(id)) currentActions.longClick?.invoke(id) },
        pointerLongClick = { id, origin ->
            if (admit(id)) currentActions.pointerLongClick?.invoke(id, origin)
        },
        leadingLongClick = { id -> if (admit(id)) (currentActions.leadingLongClick ?: currentActions.longClick)?.invoke(id) },
    )

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        clipChildren = false
        clipToPadding = false
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        updateInsets()
    }

    override fun addChildrenForAccessibility(children: ArrayList<View>) {
        val first = children.size
        super.addChildrenForAccessibility(children)
        if (first == children.size) return
        if (list.sourceAccessibilityNode?.invoke()?.present == false) {
            for (index in children.lastIndex downTo first) {
                if (children[index] === list) children.removeAt(index)
            }
        }
    }

    fun setEnvironment(value: ReverbViewEnvironment) {
        if (environment === value) return
        environment = value
        list.bindScrollIndicator(value.appearance.scrollIndicator, value.palette.muted)
        list.sourceOverscroll?.setGlowColor(ReverbLibraryLayout.overscrollGlowColor(value.palette))
        updateInsets()
        invalidateRecordingFormatting()
    }

    private fun invalidateRecordingFormatting() {
        if (disposed) return
        formattingRevision++
        rowAdapter.rebindAll()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (formattingLocales != newConfig.locales) {
            formattingLocales = newConfig.locales
            onDateFormattingChanged()
            invalidateRecordingFormatting()
        }
    }

    /** One main-thread immutable presentation update; no operation, playback or Undo side effects. */
    fun submit(value: LibraryNativeListState) {
        if (disposed || clearingListComposition) return
        list.scrollIndicatorPresentationActive = value.screenActive
        expansionReveal.update(if (value.screenActive && value.renameRecordingId == null)
            value.infoRecordingId ?: value.expandedOwner?.recordingId else null, value.infoRecordingId != null)
        val snapshot = value.items as? LibraryListSnapshot
        val update = rowUpdates.submit(snapshot?.finishedIdentity ?: value.items, NativeLibraryRowState(
            selectedIds = value.selectedIds,
            selectionActive = value.selectionActive,
            blockedIds = value.activeMutationIds + value.localOperationBusyIds,
            expandedRecordingId = value.expandedOwner?.recordingId,
            expandedOwner = value.expandedOwner,
            trimRequestRecordingId = value.trimRequestRecordingId,
            screenActive = value.screenActive,
            infoRecordingId = value.infoRecordingId,
            renameRecordingId = value.renameRecordingId,
            playingRecording = value.playingRecording,
            waveformReadAllowed = value.waveformReadAllowed,
        ))
        val structureChanged = update?.catalogChanged == true || rowAdapter.pendingStructureChanged(snapshot)
        val preserveTop = structureChanged && !pendingPosition.hasKeyedRequest &&
            !list.hasPendingScrollIndicatorPlacement &&
            savePosition() == NativeListSavedPosition(0, 0)
        blockedRecordingIds = checkNotNull(rowUpdates.current).blockedIds
        rowAdapter.submit(value.items, snapshot, update)
        // DiffUtil otherwise preserves the old first row as its stable-ID anchor when a fresh
        // save/header arrives after Home's open-at-top request was already laid out.
        if (preserveTop) {
            pendingPosition.request(NativeListSavedPosition(0, 0))
            list.requestLayout()
        }
    }

    /** Called once when this source branch consumes its restored rememberLazyListState slot. */
    fun restorePosition(position: NativeListSavedPosition, cancelRecordingReveal: Boolean = true) {
        list.cancelPendingScrollIndicatorPlacement()
        if (cancelRecordingReveal) cancelPendingRecordingReveal()
        pendingPosition.request(position)
        // Catalog membership can change again before measurement. Normalize against the actual
        // layout count there, keeping this exact saved request until placement publishes its result.
        list.requestLayout()
    }

    fun openAtTop() {
        list.sourceStopScroll?.invoke()
        list.stopScroll()
        restorePosition(NativeListSavedPosition(0, 0))
    }

    fun revealRecording(recordingId: String): Boolean {
        val index = rowAdapter.recordingIndex(recordingId)
        if (index < 0) return false
        list.cancelPendingScrollIndicatorPlacement()
        list.sourceStopScroll?.invoke()
        list.stopScroll()
        pendingPosition.request(NativeListSavedPosition(index, 0), key = recordingId)
        list.requestLayout()
        return true
    }

    val pendingRecordingRevealId: String? get() = pendingPosition.requestedKey

    fun cancelPendingRecordingReveal() {
        expansionReveal.cancel()
        removeCallbacks(revealExpansion)
        expansionRevealPosted = false
        pendingPosition.cancelKeyedRequest()
        onPendingRecordingRevealCancelled?.invoke()
    }

    fun savePosition(): NativeListSavedPosition = pendingPosition.save(placedPosition())

    private fun placedPosition(): NativeListSavedPosition? {
        val index = layout.findFirstVisibleItemPosition()
        if (index == RecyclerView.NO_POSITION) return null
        val item = layout.findViewByPosition(index) ?: return null
        return NativeListSavedPosition(index, (list.paddingTop - layout.getDecoratedTop(item)).coerceAtLeast(0))
    }

    /** Source DropdownMenu remains separately owned; this supplies its current attached row anchor. */
    fun attachedRecordingAnchor(recordingId: String): View? {
        var result: View? = null
        forEachRecordingHolder { holder ->
            if (holder.binding?.recording?.id == recordingId) result = holder.row
        }
        return result
    }

    /** Keep the complete outlined field visible; include actions whenever they fit with it. */
    fun revealRenameHeader(header: View, editor: View, field: View, validation: Boolean = false) {
        val item = list.findContainingItemView(header) as? ViewGroup ?: return
        val headerBounds = android.graphics.Rect(0, 0, header.width, header.height)
        val editorBounds = android.graphics.Rect(0, 0, editor.width, editor.height)
        val fieldBounds = android.graphics.Rect(0, 0, field.width, field.height)
        item.offsetDescendantRectToMyCoords(header, headerBounds)
        item.offsetDescendantRectToMyCoords(editor, editorBounds)
        item.offsetDescendantRectToMyCoords(field, fieldBounds)
        val viewportHeight = (list.height - list.paddingTop - list.paddingBottom).coerceAtLeast(0)
        val top = libraryRenameRevealTop(header.height, fieldBounds.top - headerBounds.top,
            viewportHeight, validation)
        val rectangle = if (top != null) headerBounds.apply { this.top += top }
            else if (fieldBounds.height() <= viewportHeight) fieldBounds else editorBounds
        // Same retained-rectangle path as Settings: scroll RV, never request a second window pan.
        list.requestChildRectangleOnScreen(item, rectangle, true)
    }

    /** Mirrors removal of the source LazyColumn branch; a temporary inactive screen does not call this. */
    fun clearListComposition() {
        if (clearingListComposition) return
        clearingListComposition = true
        try {
            list.scrollIndicatorPresentationActive = false
            expansionReveal.update(null, false)
            removeCallbacks(revealExpansion)
            expansionRevealPosted = false
            val holders = (0 until list.childCount).map { list.getChildViewHolder(list.getChildAt(it)) }
            var failure: Throwable? = null
            holders.filterIsInstance<RecordingHolder>().forEach { failure = closePreservingPrimaryFailure(failure, it::retire) }
            holders.filterIsInstance<PendingSaveHolder>().forEach { failure = closePreservingPrimaryFailure(failure, it.presentation::close) }
            failure = closePreservingPrimaryFailure(failure, rowAdapter::clear)
            rowUpdates.clear()
            blockedRecordingIds = emptySet()
            // Old detached holders can survive until the next RV layout. A new source branch must save
            // its fresh zero state instead of reading those stale positions during that interval.
            pendingPosition = NativePendingListPosition(NativeListSavedPosition(0, 0))
            closePreservingPrimaryFailure(failure) { layout.scrollToPositionWithOffset(0, 0) }?.let { throw it }
        } finally {
            clearingListComposition = false
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        removeCallbacks(publishRowNodes)
        rowNodesPosted = false
        rowNodesDirty = false
        val failure = closePreservingPrimaryFailure(null, ::clearListComposition)
        list.sourceOnUserScroll = null
        workObserver = null
        onDateFormattingChanged = {}
        onPendingRecordingRevealCancelled = null
        closePreservingPrimaryFailure(failure) { list.adapter = null }?.let { throw it }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Changes while detached have no receiver; refresh the retained day groups on return.
        val zone = java.util.TimeZone.getDefault().id
        val locales = resources.configuration.locales
        if (formattingTimeZone != zone || formattingLocales != locales) {
            formattingTimeZone = zone
            formattingLocales = locales
            onDateFormattingChanged()
            invalidateRecordingFormatting()
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.TIME_12_24), false, timeFormatObserver,
        )
        ContextCompat.registerReceiver(context, timeZoneReceiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        rowNodesChanged()
    }

    override fun onDetachedFromWindow() {
        expansionReveal.cancel()
        removeCallbacks(revealExpansion)
        expansionRevealPosted = false
        context.contentResolver.unregisterContentObserver(timeFormatObserver)
        context.unregisterReceiver(timeZoneReceiver)
        formattingRevision++
        removeCallbacks(publishRowNodes)
        rowNodesPosted = false
        super.onDetachedFromWindow()
    }

    private fun admit(id: String) = rowAdapter.containsRecording(id) && id !in blockedRecordingIds
    private fun updateInsets() {
        // The thumb uses the full safe viewport; the rows retain the source horizontal inset.
        ReverbLibraryLayout.applyListInsets(list, environment.textDensity.density)
    }
    private inline fun forEachRecordingHolder(block: (RecordingHolder) -> Unit) {
        for (index in 0 until list.childCount) {
            (list.getChildViewHolder(list.getChildAt(index)) as? RecordingHolder)?.let(block)
        }
    }

    private inline fun forEachPendingHolder(block: (PendingSaveHolder) -> Unit) {
        for (index in 0 until list.childCount) {
            (list.getChildViewHolder(list.getChildAt(index)) as? PendingSaveHolder)?.let(block)
        }
    }

    private data class Entry(
        val key: String,
        val stableId: Long,
        val header: String? = null,
        val alwaysCompactHeader: Boolean = false,
        val recording: RecordingEntity? = null,
        val pendingSave: PendingSave? = null,
        val retryFailedSaves: Boolean = false,
        val edges: NativeGroupEdges? = null,
        val row: LibraryRecordingRow? = null,
        val expandedOwner: InlinePlaybackRowOwner? = null,
        val waveformReadAllowed: Boolean = true,
        val trimRequested: Boolean = false,
        val screenActive: Boolean = false,
        val info: Boolean = false,
        val rename: Boolean = false,
        val modalHidden: Boolean = false,
        val footer: Boolean = false,
    ) {
        fun isCompactHeader(position: Int) = alwaysCompactHeader || (header != null && position == 0)
    }

    private class AdapterEntries(
        val pending: MutableList<Entry> = mutableListOf(),
        val finished: List<Entry> = emptyList(),
    ) : AbstractList<Entry>(), RandomAccess {
        override val size: Int get() = pending.size + finished.size
        override fun get(index: Int): Entry =
            if (index < pending.size) pending[index] else finished[index - pending.size]
    }

    private inner class LibraryRowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var entries = AdapterEntries()
        private var recordingsById = emptyMap<String, Entry>()
        private var snapshot: LibraryListSnapshot? = null
        private var nextStableId = 0L
        init { setHasStableIds(true) }

        fun containsRecording(id: String): Boolean = id in recordingsById
        fun recordingIndex(id: String): Int = entries.indexOfFirst { it.recording?.id == id }

        fun pendingStructureChanged(next: LibraryListSnapshot?): Boolean =
            next != null && snapshot?.structure !== next.structure

        fun submit(items: List<LibraryListItem>, next: LibraryListSnapshot?, update: NativeLibraryListUpdate?) {
            when {
                update?.catalogChanged == true -> submitCatalog(items, next?.pending?.size ?: 0)
                next != null && pendingStructureChanged(next) -> submitPending(next.pending)
                next != null && snapshot !== next -> next.pending.forEachIndexed { index, item ->
                    val entry = entries.pending[index]
                    if (item is LibraryListItem.Pending && entry.pendingSave != item.save) {
                        entries.pending[index] = entry.copy(pendingSave = item.save)
                        notifyItemChanged(index)
                    }
                }
            }
            snapshot = next
            if (update == null) return
            forEachPendingHolder { it.presentation.setPresentationActive(rowUpdates.current?.screenActive == true) }
            // Only bound visible rows need a transient update. Cached/prefetched holders sample the
            // latest state when attached, so selection-mode changes never invalidate the full catalog.
            forEachRecordingHolder { holder ->
                holder.binding?.recording?.id?.let { id ->
                    if (update.affects(id)) refresh(holder)
                }
            }
        }

        private fun submitPending(items: List<LibraryListItem>) {
            val previous = entries
            val previousByKey = previous.pending.associateBy { it.key }
            val pending = ArrayList<Entry>(items.size)
            items.forEachIndexed { index, item -> pending.add(makeEntry(items, index, item, previousByKey)) }
            val diff = calculateDiff(previous.pending, pending)
            entries = AdapterEntries(pending, previous.finished)
            diff.dispatchUpdatesTo(this)

            // Only the leading date header's top inset depends on whether a prefix is present.
            val firstFinished = entries.finished.firstOrNull()
            if (previous.pending.isEmpty() != pending.isEmpty() &&
                firstFinished?.header != null && !firstFinished.alwaysCompactHeader) {
                notifyItemChanged(pending.size)
            }
        }

        private fun submitCatalog(items: List<LibraryListItem>, pendingSize: Int) {
            val previous = entries
            val previousByKey = previous.associateBy { it.key }
            val pending = ArrayList<Entry>(pendingSize)
            val finished = ArrayList<Entry>(items.size - pendingSize + 1)
            val nextRecordings = HashMap<String, Entry>(items.size - pendingSize)
            items.forEachIndexed { index, item ->
                val entry = makeEntry(items, index, item, previousByKey)
                if (index < pendingSize) pending.add(entry) else finished.add(entry)
                entry.recording?.let {
                    nextRecordings[it.id] = entry
                    workObserver?.finishedEntryCreated()
                }
            }
            // Released LazyColumn always appends item { Spacer(Modifier.height(84.dp)) }.
            finished.add(Entry("library-footer", previousByKey["library-footer"]?.stableId ?: nextStableId++, footer = true))
            val next = AdapterEntries(pending, finished)
            workObserver?.catalogDiff()
            val diff = calculateDiff(previous, next)
            entries = next
            recordingsById = nextRecordings
            diff.dispatchUpdatesTo(this)
        }

        private fun makeEntry(items: List<LibraryListItem>, index: Int, item: LibraryListItem,
            previousByKey: Map<String, Entry>): Entry {
            val key = libraryListItemKey(item)
            val id = previousByKey[key]?.stableId ?: nextStableId++
            return when (item) {
                is LibraryListItem.Header -> Entry(key, id, header = item.dateLabel)
                is LibraryListItem.Pending -> Entry(key, id, pendingSave = item.save,
                    edges = NativeGroupEdges(
                        first = items.getOrNull(index - 1) !is LibraryListItem.Pending,
                        last = items.getOrNull(index + 1) !is LibraryListItem.Pending))
                is LibraryListItem.SaveRecoveryStatus -> Entry(key, id, header = item.message, alwaysCompactHeader = true)
                LibraryListItem.RetryFailedSaves -> Entry(key, id, retryFailedSaves = true)
                is LibraryListItem.Recording -> Entry(key, id, recording = item.recording,
                    edges = NativeGroupEdges(
                        first = items.getOrNull(index - 1) !is LibraryListItem.Recording,
                        last = items.getOrNull(index + 1) !is LibraryListItem.Recording))
            }
        }

        private fun calculateDiff(previous: List<Entry>, next: List<Entry>) =
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previous.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition].key == next[newItemPosition].key
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val before = previous[oldItemPosition]
                    val after = next[newItemPosition]
                    return before == after &&
                        before.isCompactHeader(oldItemPosition) == after.isCompactHeader(newItemPosition)
                }
            })

        private fun presentation(entry: Entry, previous: LibraryRecordingRow? = null): Entry {
            val recording = checkNotNull(entry.recording)
            val state = checkNotNull(rowUpdates.current)
            val info = state.infoRecordingId == recording.id
            val rename = state.renameRecordingId == recording.id
            val modalId = state.infoRecordingId ?: state.renameRecordingId
            val expanded = info || state.expandedRecordingId == recording.id
            return entry.copy(row = LibraryRecordingRow(
                recordingId = recording.id,
                title = recording.displayName,
                subtitle = previous?.subtitle ?: (formatSavedRecordingDuration(context, recording.durationMillis) +
                    " \u2022 " + formatShortFileSize(recording.sizeBytes)),
                timestamp = previous?.timestamp ?: formatRecordingStartTimestamp(context, recording.startedAtMillis),
                selected = recording.id in state.selectedIds,
                selectionActive = state.selectionActive,
                interactionsEnabled = recording.id !in state.blockedIds && !rename &&
                    (modalId == null || modalId == recording.id),
                edges = checkNotNull(entry.edges),
                expanded = expanded,
                isPlaying = state.playingRecording?.let { samePlaybackRecording(it, recording) } == true,
                activationLabel = if (info) context.getString(R.string.recording_details_hide) else null,
            ), expandedOwner = state.expandedOwner?.takeIf { expanded },
                waveformReadAllowed = state.waveformReadAllowedFor(recording.id),
                trimRequested = state.trimRequestRecordingId == recording.id,
                screenActive = state.screenActive, info = info, rename = rename,
                modalHidden = modalId != null && modalId != recording.id)
        }

        private fun refresh(holder: RecordingHolder) {
            val binding = holder.binding ?: return
            val id = binding.recording?.id ?: return
            val entry = recordingsById[id] ?: return holder.retire()
            val state = checkNotNull(rowUpdates.current)
            if (holder.boundRevision != rowUpdates.bindingRevision ||
                holder.boundFormattingRevision != formattingRevision ||
                binding.waveformReadAllowed != state.waveformReadAllowedFor(id) ||
                binding.row?.isPlaying != (state.playingRecording?.let {
                    samePlaybackRecording(it, checkNotNull(entry.recording))
                } == true)) {
                // Transient updates can reuse the text already owned by this holder. Full catalog
                // binds and changes to Android's formatting inputs sample fresh text.
                val previous = binding.takeIf {
                    holder.boundFormattingRevision == formattingRevision && it.recording === entry.recording
                }?.row
                holder.bind(presentation(entry, previous))
            }
        }

        fun rebindAll() { notifyItemRangeChanged(0, itemCount) }
        fun clear() {
            val count = entries.size
            entries = AdapterEntries()
            recordingsById = emptyMap()
            snapshot = null
            if (count > 0) notifyItemRangeRemoved(0, count)
        }
        override fun getItemCount() = entries.size
        override fun getItemId(position: Int) = entries[position].stableId
        override fun getItemViewType(position: Int) = when {
            entries[position].footer -> 2
            entries[position].header != null -> 0
            entries[position].pendingSave != null -> 3
            entries[position].retryFailedSaves -> 4
            else -> 1
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val holder = when (viewType) {
                0 -> object : RecyclerView.ViewHolder(ReverbLibraryHeaderView(context, environment)) {}
                1 -> RecordingHolder(ReverbLibraryRecordingRow(context, environment, animationScope, nativeInputRoot))
                3 -> PendingSaveHolder(LibraryPendingSaveRowPresentation(context, environment, animationScope, nativeInputRoot))
                4 -> object : RecyclerView.ViewHolder(ReverbLibraryTextAction(context, environment, nativeInputRoot)) {}
                else -> object : RecyclerView.ViewHolder(View(context).apply {
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }) {}
            }
            holder.itemView.layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            return holder
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val entry = entries[position]
            val pending = holder as? PendingSaveHolder
            val progressOnly = pending?.boundSave?.let { before ->
                entry.pendingSave?.let { after -> pendingSaveOnlyProgressChanged(before, after) }
            } == true
            if (holder !is RecordingHolder) workObserver?.rowBound(entry.key)
            if (entry.footer) {
                holder.itemView.layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT,
                    ReverbLibraryLayout.pendingRecoveryHeightPx(environment.textDensity.density))
            } else if (entry.retryFailedSaves) {
                (holder.itemView as ReverbLibraryTextAction).bind(environment, context.getString(R.string.library_retry)) {
                    if (rowUpdates.current?.screenActive == true) onRetryFailedSaves()
                }
            } else if (entry.pendingSave != null) {
                (holder as PendingSaveHolder).presentation.apply {
                    setPresentationActive(rowUpdates.current?.screenActive == true)
                    bind(entry.pendingSave, environment, entry.edges)
                }
            } else if (holder is RecordingHolder) holder.bind(presentation(entry)) else {
                (holder.itemView as ReverbLibraryHeaderView).apply {
                    setEnvironment(environment)
                    bind(checkNotNull(entry.header), entry.isCompactHeader(position))
                }
            }
            pending?.boundSave = entry.pendingSave
            if (!progressOnly) rowNodesChanged()
        }
        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            (holder as? PendingSaveHolder)?.presentation?.setPresentationActive(rowUpdates.current?.screenActive == true)
            (holder as? RecordingHolder)?.let {
                // RecyclerView can attach a cached holder without another onBindViewHolder call.
                // Refresh before restoring preview admission, including an outgoing collapse lease.
                refresh(it)
                it.presentationAttached(true)
            }
            rowNodesChanged()
        }
        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            (holder as? PendingSaveHolder)?.presentation?.setPresentationActive(false)
            (holder as? RecordingHolder)?.presentationAttached(false)
            rowNodesChanged()
        }
        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder as? RecordingHolder)?.retire()
            (holder as? PendingSaveHolder)?.let {
                it.boundSave = null
                it.presentation.close()
            }
            super.onViewRecycled(holder)
        }
    }

    private class PendingSaveHolder(val presentation: LibraryPendingSaveRowPresentation) :
        RecyclerView.ViewHolder(presentation.row) {
        var boundSave: PendingSave? = null
    }

    private inner class RecordingHolder(val row: ReverbLibraryRecordingRow) : RecyclerView.ViewHolder(row) {
        var binding: Entry? = null
            private set
        var boundRevision = -1L
            private set
        var boundFormattingRevision = -1L
            private set
        private var expansion: LibraryInlineExpansionLease? = null
        private var expansionToken: Any? = null
        private var attached = false
        init { row.parentInterestedInDown = { true } }

        fun bind(entry: Entry) {
            workObserver?.rowBound(entry.key)
            val previousRecording = binding?.recording
            val recording = checkNotNull(entry.recording)
            if (binding?.key != entry.key ||
                (previousRecording != null && previousRecording !== recording &&
                    !sameRecordingActionTarget(previousRecording, recording))) {
                // A reused catalog ID must not carry a held gesture into a different asset.
                retire()
            }
            if (expansion != null && entry.row?.expanded == true && binding?.info != entry.info) releaseExpansion()
            binding = entry
            boundRevision = rowUpdates.bindingRevision
            boundFormattingRevision = formattingRevision
            row.setEnvironment(environment)
            val model = checkNotNull(entry.row)
            row.importantForAccessibility = if (entry.modalHidden) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO
            row.setHeaderView(if (entry.rename) inlineHost.renameHeader(model.recordingId) else null)
            row.bind(model, guardedActions.copy(
                activate = guardedActions.activate.takeIf { currentActions.activate != null },
                longClick = guardedActions.longClick.takeIf { currentActions.longClick != null && !entry.info },
                pointerLongClick = guardedActions.pointerLongClick.takeIf {
                    currentActions.pointerLongClick != null && currentActions.longClick != null && !entry.info },
                leadingLongClick = guardedActions.leadingLongClick.takeIf {
                    currentActions.leadingLongClick != null && !entry.info }))
            if (model.expanded && expansion == null) {
                val token = Any()
                expansionToken = token
                row.onExpansionExit = {
                    if (expansionToken === token && binding?.row?.expanded == false) releaseExpansion()
                }
                val lease = if (entry.info) inlineHost.createInfo(context, model.recordingId)
                    else inlineHost.create(context, model.recordingId)
                expansion = lease
                row.setExpansionView(lease.view)
                lease.setPresentationAttached(attached)
            }
            expansion?.apply {
                setEnvironment(environment)
                bind(checkNotNull(entry.recording), entry.expandedOwner, model.expanded,
                    entry.screenActive && model.expanded && attached, entry.trimRequested, entry.waveformReadAllowed)
            }
        }

        fun presentationAttached(value: Boolean) {
            attached = value
            expansion?.setPresentationAttached(value)
            val entry = binding ?: return
            expansion?.bind(checkNotNull(entry.recording), entry.expandedOwner,
                entry.row?.expanded == true, entry.screenActive && entry.row?.expanded == true && value,
                entry.trimRequested, entry.waveformReadAllowed)
        }

        private fun releaseExpansion() {
            expansionToken = null
            row.onExpansionExit = null
            val old = expansion
            expansion = null
            val failure = closePreservingPrimaryFailure(null) { row.setExpansionView(null) }
            closePreservingPrimaryFailure(failure) { old?.closePresentation() }?.let { throw it }
        }

        fun retire() {
            row.setHeaderView(null)
            binding = null
            boundRevision = -1L
            boundFormattingRevision = -1L
            val failure = closePreservingPrimaryFailure(null, ::releaseExpansion)
            closePreservingPrimaryFailure(failure, row::unbind)?.let { throw it }
        }
    }
}
