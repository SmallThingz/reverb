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
import app.smallthingz.reverb.RecordingEntity
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
)

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
}

internal interface LibraryInlineExpansionLease {
    val view: View
    fun bind(recording: RecordingEntity, owner: InlinePlaybackRowOwner?, expanded: Boolean,
        screenActive: Boolean, trimRequested: Boolean)
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
    private var disposed = false
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
    private var formattingRevision = 0L
    private val timeFormatObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = invalidateRecordingFormatting()
    }
    private val timeZoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = invalidateRecordingFormatting()
    }
    private val currentActions = actions
    private val rowUpdates = NativeLibraryListUpdates<InlinePlaybackRowOwner>()
    private var blockedRecordingIds = emptySet<String>()
    private var pendingPosition = NativePendingListPosition(NativeListSavedPosition(0, 0))
    private val layout = object : LinearLayoutManager(context, RecyclerView.VERTICAL, false) {
        override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
            if (!state.isPreLayout) pendingPosition.pendingForPlacement(state.itemCount, recordingIndexForPlacement)?.let { target ->
                scrollToPositionWithOffset(target.firstVisibleItemIndex, -target.firstVisibleItemScrollOffset)
            }
            super.onLayoutChildren(recycler, state)
        }
        override fun onLayoutCompleted(state: RecyclerView.State) {
            super.onLayoutCompleted(state)
            if (!state.isPreLayout) pendingPosition.placed(placedPosition())
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
        setHasFixedSize(false)
    }
    val scrollView: ReverbLibraryRecyclerView get() = list
    private val guardedActions = LibraryRecordingRowActions(
        activate = { id -> if (admit(id)) currentActions.activate?.invoke(id) },
        longClick = { id -> if (admit(id)) currentActions.longClick?.invoke(id) },
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
            invalidateRecordingFormatting()
        }
    }

    /** One main-thread immutable presentation update; no operation, playback or Undo side effects. */
    fun submit(value: LibraryNativeListState) {
        val update = rowUpdates.submit(value.items, NativeLibraryRowState(
            selectedIds = value.selectedIds,
            selectionActive = value.selectionActive,
            blockedIds = value.activeMutationIds + value.localOperationBusyIds,
            expandedRecordingId = value.expandedOwner?.recordingId,
            expandedOwner = value.expandedOwner,
            trimRequestRecordingId = value.trimRequestRecordingId,
            screenActive = value.screenActive,
        )) ?: return
        val preserveTop = update.catalogChanged && !pendingPosition.hasKeyedRequest &&
            savePosition() == NativeListSavedPosition(0, 0)
        blockedRecordingIds = checkNotNull(rowUpdates.current).blockedIds
        rowAdapter.submit(value.items, update)
        // DiffUtil otherwise preserves the old first row as its stable-ID anchor when a fresh
        // save/header arrives after Home's open-at-top request was already laid out.
        if (preserveTop) {
            pendingPosition.request(NativeListSavedPosition(0, 0))
            list.requestLayout()
        }
    }

    /** Called once when this source branch consumes its restored rememberLazyListState slot. */
    fun restorePosition(position: NativeListSavedPosition, cancelRecordingReveal: Boolean = true) {
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
        list.sourceStopScroll?.invoke()
        list.stopScroll()
        pendingPosition.request(NativeListSavedPosition(index, 0), key = recordingId)
        list.requestLayout()
        return true
    }

    val pendingRecordingRevealId: String? get() = pendingPosition.requestedKey

    fun cancelPendingRecordingReveal() {
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

    /** Mirrors removal of the source LazyColumn branch; a temporary inactive screen does not call this. */
    fun clearListComposition() {
        var failure: Throwable? = null
        forEachRecordingHolder { failure = closePreservingPrimaryFailure(failure, it::retire) }
        forEachPendingHolder { failure = closePreservingPrimaryFailure(failure, it.presentation::close) }
        failure = closePreservingPrimaryFailure(failure, rowAdapter::clear)
        rowUpdates.clear()
        blockedRecordingIds = emptySet()
        // Old detached holders can survive until the next RV layout. A new source branch must save
        // its fresh zero state instead of reading those stale positions during that interval.
        pendingPosition = NativePendingListPosition(NativeListSavedPosition(0, 0))
        closePreservingPrimaryFailure(failure) { layout.scrollToPositionWithOffset(0, 0) }?.let { throw it }
    }

    fun dispose() {
        disposed = true
        removeCallbacks(publishRowNodes)
        rowNodesPosted = false
        rowNodesDirty = false
        val failure = closePreservingPrimaryFailure(null, ::clearListComposition)
        list.sourceOnUserScroll = null
        onPendingRecordingRevealCancelled = null
        closePreservingPrimaryFailure(failure) { list.adapter = null }?.let { throw it }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.TIME_12_24), false, timeFormatObserver,
        )
        ContextCompat.registerReceiver(context, timeZoneReceiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        rowNodesChanged()
    }

    override fun onDetachedFromWindow() {
        context.contentResolver.unregisterContentObserver(timeFormatObserver)
        context.unregisterReceiver(timeZoneReceiver)
        formattingRevision++
        removeCallbacks(publishRowNodes)
        rowNodesPosted = false
        super.onDetachedFromWindow()
    }

    private fun admit(id: String) = rowAdapter.containsRecording(id) && id !in blockedRecordingIds
    private fun updateInsets() {
        // Source modifier.padding(horizontal=16.dp) shrinks the viewport; it is not scroll content padding.
        ReverbLibraryLayout.applyListInsets(this, environment.textDensity.density)
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
        val compactHeader: Boolean = false,
        val recording: RecordingEntity? = null,
        val pendingSave: PendingSave? = null,
        val retryFailedSaves: Boolean = false,
        val edges: NativeGroupEdges? = null,
        val row: LibraryRecordingRow? = null,
        val expandedOwner: InlinePlaybackRowOwner? = null,
        val trimRequested: Boolean = false,
        val screenActive: Boolean = false,
        val footer: Boolean = false,
    )

    private inner class LibraryRowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var entries = emptyList<Entry>()
        private var recordingsById = emptyMap<String, Entry>()
        private var nextStableId = 0L
        init { setHasStableIds(true) }

        fun containsRecording(id: String): Boolean = id in recordingsById
        fun recordingIndex(id: String): Int = entries.indexOfFirst { it.recording?.id == id }

        fun submit(items: List<LibraryListItem>, update: NativeLibraryListUpdate) {
            if (update.catalogChanged) submitCatalog(items)
            forEachPendingHolder { it.presentation.setPresentationActive(rowUpdates.current?.screenActive == true) }
            // Only bound visible rows need a transient update. Cached/prefetched holders sample the
            // latest state when attached, so selection-mode changes never invalidate the full catalog.
            forEachRecordingHolder { holder ->
                holder.binding?.recording?.id?.let { id ->
                    if (update.affects(id)) refresh(holder)
                }
            }
        }

        private fun submitCatalog(items: List<LibraryListItem>) {
            val previous = entries
            val previousByKey = previous.associateBy { it.key }
            val next = ArrayList<Entry>(items.size + 1)
            val nextRecordings = HashMap<String, Entry>(items.size)
            items.forEachIndexed { index, item ->
                val key = libraryListItemKey(item)
                val id = previousByKey[key]?.stableId ?: nextStableId++
                next.add(when (item) {
                    is LibraryListItem.Header -> Entry(key, id, header = item.dateLabel, compactHeader = index == 0)
                    is LibraryListItem.Pending -> Entry(key, id, pendingSave = item.save,
                        edges = NativeGroupEdges(
                            first = items.getOrNull(index - 1) !is LibraryListItem.Pending,
                            last = items.getOrNull(index + 1) !is LibraryListItem.Pending))
                    is LibraryListItem.SaveRecoveryStatus -> Entry(key, id, header = item.message, compactHeader = true)
                    LibraryListItem.RetryFailedSaves -> Entry(key, id, retryFailedSaves = true)
                    is LibraryListItem.Recording -> Entry(key, id, recording = item.recording,
                        edges = NativeGroupEdges(
                            first = items.getOrNull(index - 1) !is LibraryListItem.Recording,
                            last = items.getOrNull(index + 1) !is LibraryListItem.Recording))
                        .also { nextRecordings[item.recording.id] = it }
                })
            }
            // Released LazyColumn always appends item { Spacer(Modifier.height(84.dp)) }.
            next.add(Entry("library-footer", previousByKey["library-footer"]?.stableId ?: nextStableId++, footer = true))
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previous.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition].key == next[newItemPosition].key
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition] == next[newItemPosition]
            })
            entries = next
            recordingsById = nextRecordings
            diff.dispatchUpdatesTo(this)
        }

        private fun presentation(entry: Entry, previous: LibraryRecordingRow? = null): Entry {
            val recording = checkNotNull(entry.recording)
            val state = checkNotNull(rowUpdates.current)
            val expanded = state.expandedRecordingId == recording.id
            return entry.copy(row = LibraryRecordingRow(
                recordingId = recording.id,
                title = recording.displayName,
                subtitle = previous?.subtitle ?: (formatSavedRecordingDuration(context, recording.durationMillis) +
                    " \u2022 " + formatShortFileSize(recording.sizeBytes)),
                timestamp = previous?.timestamp ?: formatRecordingStartTimestamp(context, recording.startedAtMillis),
                selected = recording.id in state.selectedIds,
                selectionActive = state.selectionActive,
                interactionsEnabled = recording.id !in state.blockedIds,
                edges = checkNotNull(entry.edges),
                expanded = expanded,
            ), expandedOwner = state.expandedOwner?.takeIf { expanded },
                trimRequested = state.trimRequestRecordingId == recording.id,
                screenActive = state.screenActive)
        }

        private fun refresh(holder: RecordingHolder) {
            val id = holder.binding?.recording?.id ?: return
            val entry = recordingsById[id]
            if (entry == null) holder.retire()
            else if (holder.boundRevision != rowUpdates.revision || holder.boundFormattingRevision != formattingRevision) {
                // Transient updates can reuse the text already owned by this holder. Full catalog
                // binds and changes to Android's formatting inputs sample fresh text.
                val previous = holder.binding?.takeIf {
                    holder.boundFormattingRevision == formattingRevision && it.recording === entry.recording
                }?.row
                holder.bind(presentation(entry, previous))
            }
        }

        fun rebindAll() { notifyItemRangeChanged(0, itemCount) }
        fun clear() {
            val count = entries.size
            entries = emptyList()
            recordingsById = emptyMap()
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
                    bind(checkNotNull(entry.header), entry.compactHeader)
                }
            }
            rowNodesChanged()
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
            (holder as? PendingSaveHolder)?.presentation?.close()
            super.onViewRecycled(holder)
        }
    }

    private class PendingSaveHolder(val presentation: LibraryPendingSaveRowPresentation) :
        RecyclerView.ViewHolder(presentation.row)

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
            if (binding?.key != entry.key) retire()
            binding = entry
            boundRevision = rowUpdates.revision
            boundFormattingRevision = formattingRevision
            row.setEnvironment(environment)
            val model = checkNotNull(entry.row)
            row.bind(model, guardedActions.copy(
                activate = guardedActions.activate.takeIf { currentActions.activate != null },
                longClick = guardedActions.longClick.takeIf { currentActions.longClick != null },
                leadingLongClick = guardedActions.leadingLongClick.takeIf {
                    currentActions.leadingLongClick != null }))
            if (model.expanded && expansion == null) {
                val token = Any()
                expansionToken = token
                row.onExpansionExit = {
                    if (expansionToken === token && binding?.row?.expanded == false) releaseExpansion()
                }
                val lease = inlineHost.create(context, model.recordingId)
                expansion = lease
                row.setExpansionView(lease.view)
                lease.setPresentationAttached(attached)
            }
            expansion?.apply {
                setEnvironment(environment)
                bind(checkNotNull(entry.recording), entry.expandedOwner, model.expanded,
                    entry.screenActive && model.expanded && attached, entry.trimRequested)
            }
        }

        fun presentationAttached(value: Boolean) {
            attached = value
            expansion?.setPresentationAttached(value)
            val entry = binding ?: return
            expansion?.bind(checkNotNull(entry.recording), entry.expandedOwner,
                entry.row?.expanded == true, entry.screenActive && entry.row?.expanded == true && value,
                entry.trimRequested)
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
            binding = null
            boundRevision = -1L
            boundFormattingRevision = -1L
            val failure = closePreservingPrimaryFailure(null, ::releaseExpansion)
            closePreservingPrimaryFailure(failure, row::unbind)?.let { throw it }
        }
    }
}
