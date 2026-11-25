package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeMenuOrigin

import smallthingz.ui.input.phase.NativePointerLayerShape
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.reverb.ReverbActionMenuHost
import smallthingz.ui.reverb.ReverbActionMenuKind
import smallthingz.ui.reverb.ReverbMenuAction
import smallthingz.ui.scroll.NativeVerticalNestedHost
import smallthingz.ui.scroll.NativeVerticalViewport
import smallthingz.ui.text.view.NativeTextSemanticsClip
import android.content.Context
import android.graphics.Path
import smallthingz.ui.reverb.ReverbRecordingSpotlight
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.reverb.ReverbLibraryLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import smallthingz.ui.runtime.MonotonicFrameClock
import smallthingz.ui.platform.AccessibilityManager
import androidx.lifecycle.LifecycleOwner
import app.smallthingz.reverb.NativeLibraryCallbacks
import app.smallthingz.reverb.NativeLibraryController
import app.smallthingz.reverb.NativeLibraryInput
import app.smallthingz.reverb.NativeLibraryPresentation
import app.smallthingz.reverb.NativeRecordingRenameCallbacks
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.RecordingPlaybackRequest
import app.smallthingz.reverb.R
import app.smallthingz.reverb.libraryEmptyStateVisible
import kotlinx.coroutines.CoroutineScope

/** Already-consumed ScaffoldDefaults.contentWindowInsets; top is owned by the 66dp top bar. */
internal typealias LibraryScaffoldInsets = smallthingz.ui.reverb.ReverbLibraryInsets
internal data class NativeLibraryScreenInput(
    val owner: NativeLibraryInput,
    val hasIncidents: Boolean,
    val showNormalTopBar: Boolean,
    val scaffoldInsets: LibraryScaffoldInsets,
    val brand: () -> Unit,
    val incidents: () -> Unit,
    val settings: () -> Unit,
    val dismiss: () -> Unit,
)

/**
 * Native FilesScreen content and retained operation owner. Its surface is transparent, as released:
 * the parent Library panel alone owns the noise/background and opening/closing translations.
 *
 * Window integration owns one shared pointer dispatcher, system Back and source popup/sheet hosts.
 * This exposes actual leaf nodes and current typed transient state to that existing window instead
 * of adding a parallel rendering model, duplicate storage owner or default Android dialogs.
 */
internal class ReverbLibraryScreenContent(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    initial: NativeLibraryScreenInput,
    private val lifecycleOwner: LifecycleOwner,
    backDispatcher: OnBackPressedDispatcher,
    private val scope: CoroutineScope,
    frames: MonotonicFrameClock,
    durationScale: () -> Float,
    accessibilityManager: AccessibilityManager?,
    private val inputRoot: View,
    nested: NativeVerticalNestedHost,
    private val changedNodes: () -> Unit,
    private val transientStateChanged: (NativeLibraryPresentation) -> Unit,
    restoredScrollPosition: NativeLibrarySavedScrollPosition? = null,
) : ViewGroup(context), AutoCloseable {
    private val animationScope = CoroutineScope(scope.coroutineContext + frames)
    private var environment = initialEnvironment
    private var input = initial
    private var presentation: NativeLibraryPresentation? = null
    private var closed = false
    private val scrollRestoration = NativeLibraryScrollRestoration(restoredScrollPosition.takeIf { initial.owner.active })
    private val playbackReveal = NativeRecordingRevealRequest(
        scrollRestoration.consumeReveal(), scrollRestoration.consumeCancelledReveal())
    private var pendingRevealNeedsReconciliation = playbackReveal.pendingRecordingId != null
    private class RenameLifetime(val recordingId: String, val presentation: NativeLibraryRenamePresentation)
    private var renameHeader: RenameLifetime? = null
    private var startRenamePending = false
    private var revealEditorAfterLayout = false
    private var spotlight: ReverbRecordingSpotlight? = null
    private var spotlightRecordingId: String? = null
    private val spotlightClipPath = Path()
    private val spotlightMotionTransform = android.graphics.Matrix()
    private val spotlightWindowTransform = android.graphics.Matrix()
    private val spotlightWindowLocation = IntArray(2)
    private var inlinePointerId: String? = null
    private var inlinePointerInfo = false
    private var inlinePointerActive = initial.owner.active
    var inlinePointerRevision = 0L
        private set
    private val toolbar = ReverbLibraryToolbarView(context, environment, inputRoot)
    private val body = Body(context)
    private var status: ReverbLibraryStatusView? = null
    private var bodyMode: LibraryBodyMode? = null
    private var listVisible = false
    private var undo: ReverbLibraryUndoCard? = null
    private val leases = LinkedHashSet<NativeLibraryPlayerLease>()
    private val menus = ReverbActionMenuHost(environment.theme, ReverbActionMenuKind.RECORDINGS, frames, scope)
    private class MenuLifetime(val id: String, val anchor: View, val identity: Any = Any())
    private val menuLifetimes = mutableListOf<MenuLifetime>()
    private var activeMenu: MenuLifetime? = null
    private class PointerMenuOrigin(val id: String, val anchor: View, val point: NativeMenuOrigin)
    private var pointerMenuOrigin: PointerMenuOrigin? = null
    private var menuAwaitingLayout = false
    private var selectionBackActive = false
    private var selectionBackProgress = 0f
    private var selectionBackDirection = 1f

    val owner = NativeLibraryController(context, this, lifecycleOwner, scope, frames,
        accessibilityManager, initial.owner, publish = ::publish)

    private val pull = NativeLibraryPullRefreshOwner(animationScope, environment.textDensity,
        initialRefreshing = false, ancestor = nested, onRefresh = { owner.refresh(showSpinner = true, userInitiated = true) })
    private val refreshIndicator = ReverbLibraryPullRefreshIndicator(context, environment, pull,
        frames, animationScope, durationScale)
    val nestedScroll: NativeVerticalNestedHost get() = pull.nested

    // Keep screen fallback and selection registrations before lazily composed player owners.
    private val fallbackBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                renameHeader != null -> renameHeader?.presentation?.owner?.requestDismiss()
                presentation?.infoRecording != null -> owner.dismissInfo()
                else -> owner.fallbackExpandedBack()
            }
        }
    }.also { backDispatcher.addCallback(lifecycleOwner, it) }
    private val selectionBack = NativeLibraryPredictiveBack(backDispatcher, lifecycleOwner,
        scope, frames, ::selectionBackFrame).apply { animateCancellation = true }

    private val list = ReverbLibraryListView(context, environment, animationScope, inputRoot,
        LibraryRecordingRowActions(
            activate = { id -> currentRecording(id)?.let(owner::activate) },
            longClick = { id ->
                pointerMenuOrigin = null
                currentRecording(id)?.let(owner::longClick)
            },
            pointerLongClick = ::pointerLongClick,
            leadingLongClick = { id -> currentRecording(id)?.let(owner::leadingLongClick) }),
        object : LibraryInlineExpansionHost {
            override fun createInfo(context: Context, recordingId: String): LibraryInlineExpansionLease =
                NativeLibraryInfoLease(context, environment, animationScope)
            override fun renameHeader(recordingId: String): View? =
                renameHeader?.takeIf { it.recordingId == recordingId }?.presentation?.view
            override fun create(context: Context, recordingId: String): LibraryInlineExpansionLease {
                val lease = NativeLibraryPlayerLease(context, recordingId, environment, owner,
                    lifecycleOwner, backDispatcher, scope, frames, durationScale, inputRoot, changedNodes)
                leases += lease
                return object : LibraryInlineExpansionLease by lease {
                    override fun closePresentation() {
                        if (leases.remove(lease)) lease.closePresentation()
                    }
                }
            }
        }, onRetryFailedSaves = owner::retryFailedSaves, onRowNodesChanged = {
            if (!closed) { changedNodes(); startInlineEditing() }
        })
    private val makeStatus = {
        ReverbLibraryStatusView(context, environment, frames, animationScope, pull.nested, inputRoot, durationScale)
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(toolbar)
        addView(body)
        body.addView(refreshIndicator)
        list.onViewportGeometryChanged = { spotlight?.invalidate() }
        list.onDateFormattingChanged = owner::dateFormattingChanged
        list.onPendingRecordingRevealCancelled = {
            playbackReveal.cancel()
            pendingRevealNeedsReconciliation = false
        }
    }

    /** Main calls after installing this screen's input and accessibility bindings. */
    fun publishInitialPresentation() = owner.publishInitialPresentation()

    fun bind(next: NativeLibraryScreenInput, nextEnvironment: ReverbViewEnvironment = environment) {
        if (closed) return
        val environmentChanged = environment !== nextEnvironment
        val insetsChanged = input.scaffoldInsets != next.scaffoldInsets
        val opening = !input.owner.active && next.owner.active
        if (!next.owner.active) list.cancelPendingRecordingReveal()
        if (opening) {
            scrollRestoration.openedFromHome()
            list.openAtTop()
            status?.openAtTop()
        }
        input = next
        environment = nextEnvironment
        if (environmentChanged) {
            menus.bindPalette(nextEnvironment.palette)
            renameHeader?.presentation?.bindEnvironment(nextEnvironment)
            spotlight?.bindEnvironment(nextEnvironment)
            refreshIndicator.bindEnvironment(nextEnvironment)
            toolbar.setEnvironment(nextEnvironment)
            list.setEnvironment(nextEnvironment)
            status?.setEnvironment(nextEnvironment)
            leases.forEach { it.setEnvironment(nextEnvironment) }
        }
        owner.bind(next.owner)
        presentation?.let(::publish)
        if (insetsChanged) { revealEditorAfterLayout = renameHeader != null; requestLayout() }
    }

    fun saveScrollPosition(): NativeLibrarySavedScrollPosition = scrollRestoration.save(
        currentList = if (listVisible) list.savePosition() else null,
        currentEmpty = if (bodyMode == LibraryBodyMode.EMPTY) status?.emptyScrollPosition else null,
        currentReveal = playbackReveal.pendingRecordingId ?: list.pendingRecordingRevealId,
        currentCancelledRevealToken = playbackReveal.cancelledRequestToken,
    )

    fun openSavedRecording(request: RecordingPlaybackRequest, finished: () -> Unit) {
        if (closed || playbackReveal.isPending(request.token)) return
        list.cancelPendingRecordingReveal()
        val revealGeneration = playbackReveal.begin(request.token)
        owner.requestPlayback(request, beforePlayback = {
            leases.toList().forEach { it.prepareOrdinaryPlayback() }
        }) { recording ->
            if (!closed) {
                if (playbackReveal.complete(revealGeneration, recording?.id)) {
                    pendingRevealNeedsReconciliation = false
                    presentation?.let(::publish)
                }
                // Input revokes only the visual jump, not the accepted transport completion.
                finished()
            }
        }
    }

    private fun currentRecording(id: String): RecordingEntity? =
        presentation?.recordings?.firstOrNull { it.id == id }

    private fun toolbarState(value: NativeLibraryPresentation) = LibraryToolbarState(
        value.selectedCount, value.selectionHasBusyMutation, value.acceptsDeletions,
        input.hasIncidents, input.showNormalTopBar, selectionBackActive,
        selectionBackProgress, selectionBackDirection)
    private fun toolbarActions() = LibraryToolbarActions(
        input.dismiss, input.brand, input.incidents, input.settings,
        owner::clearSelection, owner::shareSelection, owner::deleteSelected)

    private fun publish(next: NativeLibraryPresentation) {
        if (closed) return
        // Hiding must reach retained players before the owner's queued publication. Keep the
        // clamped snapshot so a quick reopen waits for fresh owner state before admitting playback.
        val value = if (!input.owner.active && next.list.screenActive) {
            next.copy(list = next.list.copy(screenActive = false))
        } else next
        presentation = value
        if (pendingRevealNeedsReconciliation && value.hasLoaded) {
            // Restoring this UI request must never replay the already-consumed transport command.
            if (value.recordings.none { it.id == playbackReveal.pendingRecordingId }) playbackReveal.clearPending()
            pendingRevealNeedsReconciliation = false
        }
        pull.bind(value.isRefreshing) { owner.refresh(showSpinner = true, userInitiated = true) }
        renderInline(value)
        fallbackBack.isEnabled = input.owner.active && value.selectedCount == 0 &&
            (value.list.expandedOwner != null || value.renameRecording != null || value.infoRecording != null)
        selectionBack.bind(input.owner.active && value.selectedCount > 0, owner::clearSelection)
        toolbar.setActionInsets(input.scaffoldInsets.left, input.scaffoldInsets.right)
        toolbar.bind(toolbarState(value), toolbarActions())
        val mode = when {
            value.list.items.isEmpty() && value.loadFailed -> LibraryBodyMode.FAILED
            value.list.items.isEmpty() && !value.hasLoaded -> LibraryBodyMode.LOADING
            libraryEmptyStateVisible(value.hasLoaded, value.list.items.isEmpty(), value.isRefreshing) ->
                LibraryBodyMode.EMPTY
            else -> null
        }
        if (mode == null) {
            val restoredList = if (!listVisible) scrollRestoration.consumeList() else null
            if (!listVisible) {
                status?.let(body::removeView)
                status = null
                bodyMode = null
                body.addView(list, 0)
                listVisible = true
            }
            list.submit(value.list)
            restoredList?.let { list.restorePosition(it, cancelRecordingReveal = false) }
            if (!pendingRevealNeedsReconciliation) playbackReveal.pendingRecordingId?.let { id ->
                if (list.revealRecording(id)) playbackReveal.clearPending()
            }
        } else {
            if (listVisible) {
                // Real source branch disposal, unlike a hidden retained screen.
                list.clearListComposition()
                body.removeView(list)
                listVisible = false
            }
            val current = status ?: makeStatus().also { status = it; body.addView(it, 0) }
            val restoredEmpty = if (mode == LibraryBodyMode.EMPTY && bodyMode != LibraryBodyMode.EMPTY)
                scrollRestoration.consumeEmpty() else null
            bodyMode = mode
            current.setPresentationActive(value.list.screenActive)
            current.bind(mode, restoredEmpty) { owner.refresh(showSpinner = true, userInitiated = true) }
        }
        val notice = value.undo
        if (notice == null) {
            undo?.let(body::removeView)
            undo = null
        } else {
            val card = undo ?: ReverbLibraryUndoCard(context, environment, inputRoot).also {
                undo = it
                body.addView(it)
            }
            card.bind(environment, notice.message, context.getString(R.string.undo)) { owner.undoDelete(notice.window) }
        }
        renderMenu(value)
        transientStateChanged(value)
        changedNodes()
    }

    private fun renderInline(value: NativeLibraryPresentation) {
        val rename = value.renameRecording
        val pointerId = rename?.id ?: value.infoRecording?.id
        val pointerInfo = rename == null && value.infoRecording != null
        if (inlinePointerId != pointerId || inlinePointerInfo != pointerInfo || inlinePointerActive != input.owner.active) {
            inlinePointerId = pointerId
            inlinePointerInfo = pointerInfo
            inlinePointerActive = input.owner.active
            // Detach this Library generation, preserving the root's pressed-pointer history:
            // an old finger's next MOVE must not become a fresh DOWN in the new modal.
            inlinePointerRevision++
            list.scrollView.sourceStopScroll?.invoke()
        }
        if (renameHeader?.recordingId != rename?.id) {
            val previous = renameHeader
            renameHeader = null // Retire before focus/terminal callbacks can reenter.
            startRenamePending = false
            previous?.presentation?.close()
        }
        if (rename != null) {
            val callbacks = NativeRecordingRenameCallbacks(owner::dismissRename,
                { renamed -> owner.renamed(rename, renamed) }, owner::renameUncertain)
            val current = renameHeader
            if (current == null) {
                renameHeader = RenameLifetime(rename.id, NativeLibraryRenamePresentation(this, environment,
                    rename, input.owner.active, lifecycleOwner, animationScope, callbacks, changedNodes,
                    { revealEditorAfterLayout = true }))
                startRenamePending = true
            } else current.presentation.bind(rename, input.owner.active, callbacks)
        }
        val modal = rename != null || value.infoRecording != null
        toolbar.importantForAccessibility = if (modal) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else IMPORTANT_FOR_ACCESSIBILITY_NO
        refreshIndicator.importantForAccessibility = if (modal) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else IMPORTANT_FOR_ACCESSIBILITY_NO
        if (modal) {
            spotlightRecordingId = rename?.id ?: value.infoRecording?.id
            val current = spotlight ?: ReverbRecordingSpotlight(context, environment,
                ::inlineCardPath, animationScope, ::retireSpotlight) { dismissInline() }.also {
                spotlight = it
                it.setOnGenericMotionListener { source, event ->
                    val viewport = listViewport
                    val axis = when {
                        event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER) -> android.view.MotionEvent.AXIS_VSCROLL
                        event.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER) -> android.view.MotionEvent.AXIS_SCROLL
                        else -> null
                    }
                    if (!inlineInfoActive || viewport == null || event.actionMasked != android.view.MotionEvent.ACTION_SCROLL ||
                        axis == null || event.getAxisValue(axis) == 0f) {
                        false
                    } else {
                        val transform = spotlightMotionTransform
                        // Both Views belong to this window. Reuse its pinned SDK28 fallback,
                        // sampling inverse(viewportToWindow) * sourceToWindow before dispatch.
                        smallthingz.ui.input.phase.nativeViewMatrixToWindow(viewport,
                            spotlightWindowLocation, spotlightWindowTransform)
                        if (!spotlightWindowTransform.invert(transform)) return@setOnGenericMotionListener true
                        smallthingz.ui.input.phase.nativeViewMatrixToWindow(source,
                            spotlightWindowLocation, spotlightWindowTransform)
                        transform.preConcat(spotlightWindowTransform)
                        val local = android.view.MotionEvent.obtain(event)
                        try {
                            local.transform(transform)
                            viewport.dispatchGenericMotionEvent(local)
                            // RV can consume pixels and return false. Do not deliver twice to
                            // the dimmed body underneath this exclusive backdrop.
                            true
                        } finally { local.recycle() }
                    }
                }
                it.setShown(true)
                addView(it)
                requestLayout()
            }
            current.setShown(true)
        } else {
            spotlight?.setShown(false) // Captures the old card path before its identity retires.
            spotlightRecordingId = null
        }
        spotlight?.invalidate()
    }

    private fun retireSpotlight(view: ReverbRecordingSpotlight) {
        if (spotlight !== view) return
        spotlight = null
        view.close()
        removeView(view)
        if (!closed) {
            presentation?.let(transientStateChanged)
            changedNodes()
        }
    }
    val inlineBackdropVisible: Boolean get() = spotlight != null

    private fun dismissInline() {
        if (renameHeader != null) renameHeader?.presentation?.owner?.requestDismiss() else owner.dismissInfo()
    }
    private fun startInlineEditing() {
        if (!startRenamePending || !input.owner.active) return
        val view = renameHeader?.presentation?.view ?: return
        if (!view.isAttachedToWindow || !view.isLaidOut) return
        startRenamePending = false
        // The editor owns its guarded layout/focus admission and single deferred IME request.
        view.startEditing()
        list.revealRenameHeader(view, view.editor, view.outlinedField,
            validation = renameHeader?.presentation?.owner?.state?.error != null)
    }
    private fun inlineCardPath(out: Path) {
        out.rewind()
        val id = spotlightRecordingId ?: return
        val row = list.attachedRecordingAnchor(id) as? ReverbLibraryRecordingRow ?: return
        val viewport = listViewport ?: return
        val surface = row.summarySurface
        surface.copyVisualPath(out)
        mapSpotlightPath(out, surface)
        // The natural surface can exceed the animated row, and the row can cross the viewport.
        // Intersect AFTER transforming the actual surface, never recreate its joined corners.
        intersectSpotlightClip(out, row)
        intersectSpotlightClip(out, viewport)
    }
    private fun intersectSpotlightClip(out: Path, clip: View) {
        spotlightClipPath.rewind()
        spotlightClipPath.addRect(0f, 0f, clip.width.toFloat(), clip.height.toFloat(), Path.Direction.CW)
        mapSpotlightPath(spotlightClipPath, clip)
        if (!out.op(spotlightClipPath, Path.Op.INTERSECT)) out.rewind()
    }
    private fun mapSpotlightPath(path: Path, descendant: View) {
        var current = descendant
        while (current !== this) {
            val parent = current.parent as? View ?: run { path.rewind(); return }
            path.transform(current.matrix)
            path.offset((current.left - parent.scrollX).toFloat(), (current.top - parent.scrollY).toFloat())
            current = parent
        }
    }

    private fun pointerLongClick(id: String, point: NativeMenuOrigin) {
        val recording = currentRecording(id) ?: return
        pointerMenuOrigin = list.attachedRecordingAnchor(id)?.let { PointerMenuOrigin(id, it, point) }
        owner.longClick(recording)
    }

    private fun renderMenu(value: NativeLibraryPresentation) {
        val iterator = menuLifetimes.iterator()
        while (iterator.hasNext()) {
            val lifetime = iterator.next()
            if (!lifetime.anchor.isAttachedToWindow || list.attachedRecordingAnchor(lifetime.id) !== lifetime.anchor) {
                menus.release(lifetime.identity)
                if (activeMenu === lifetime) activeMenu = null
                if (pointerMenuOrigin?.anchor === lifetime.anchor) pointerMenuOrigin = null
                iterator.remove()
            }
        }
        val recording = value.menuRecording
        if (recording == null) {
            pointerMenuOrigin = null
            activeMenu?.let { menus.hide(it.identity) }
            activeMenu = null
            menuAwaitingLayout = false
            return
        }
        val anchor = list.attachedRecordingAnchor(recording.id)
        if (anchor == null || !anchor.isLaidOut) { menuAwaitingLayout = true; return }
        menuAwaitingLayout = false
        pointerMenuOrigin = pointerMenuOrigin?.takeIf { it.id == recording.id && it.anchor === anchor }
        val lifetime = menuLifetimes.firstOrNull { it.id == recording.id && it.anchor === anchor }
            ?: MenuLifetime(recording.id, anchor).also(menuLifetimes::add)
        if (activeMenu !== lifetime) activeMenu?.let { menus.hide(it.identity) }
        activeMenu = lifetime
        val busy = recording.id in value.list.activeMutationIds || recording.id in value.list.localOperationBusyIds
        fun item(label: Int, icon: ReverbIcon, enabled: Boolean = !busy, destructive: Boolean = false,
                 action: () -> Unit) = ReverbMenuAction(context.getString(label), icon, destructive, enabled, action)
        menus.show(lifetime.identity, anchor, listOf(
            item(R.string.rename_recording, ReverbIcon.Edit) { owner.rename(recording) },
            item(R.string.recording_info, ReverbIcon.Info) { owner.info(recording) },
            item(R.string.share_recording, ReverbIcon.Share) { owner.share(recording) },
            item(R.string.trim_recording, ReverbIcon.Trim) { owner.trim(recording) },
            item(R.string.delete_recording, ReverbIcon.Delete, !busy && value.acceptsDeletions,
                destructive = true) { owner.deleteRecordings(listOf(recording)) },
            item(R.string.multi_select, ReverbIcon.MultiSelect) { owner.multiSelect(recording) },
        ), origin = pointerMenuOrigin?.point, onDismissRequest = owner::dismissMenu)
    }

    /** Parent source selection predictive owner is registered before lazily created row owners. */
    fun selectionBackFrame(active: Boolean, progress: Float, direction: Float) {
        selectionBackActive = active
        selectionBackProgress = progress
        selectionBackDirection = direction
        presentation?.let { toolbar.bind(toolbarState(it), toolbarActions()) }
    }
    fun activePlayerLeases(): List<NativeLibraryPlayerLease> = leases.toList()
    fun currentState(): NativeLibraryPresentation? = presentation
    fun pointerNodes(view: View): List<NativeViewPhasePointerNode> {
        for (lease in leases) {
            val nodes = lease.pointerNodes(view)
            if (nodes.isNotEmpty()) return nodes
        }
        return emptyList()
    }
    val inlineModalActive: Boolean get() = presentation?.let { it.infoRecording != null || it.renameRecording != null } == true
    val pointerInputActive: Boolean get() = input.owner.active && !closed
    val inlineInfoActive: Boolean get() = pointerInputActive &&
        presentation?.infoRecording != null && presentation?.renameRecording == null
    /** Native source admission and focus must agree with the modal pointer backdrop. */
    fun inlineInputExcluded(view: View): Boolean {
        if (!inlineModalActive) return false
        val id = presentation?.renameRecording?.id ?: presentation?.infoRecording?.id
        var current: View? = view
        while (current != null && current !== this) {
            if (current === toolbar || current === refreshIndicator || current === undo) return true
            if (current.parent === listViewport) return (current as? ReverbLibraryRecordingRow)?.recordingId != id
            current = current.parent as? View
        }
        return false
    }
    val emptyViewport: NativeVerticalViewport? get() = status?.emptyViewport
    val scrollIndicatorViewport: ReverbLibraryRecyclerView get() = list.scrollView
    val listViewport: ReverbLibraryRecyclerView? get() = scrollIndicatorViewport.takeIf { listVisible }
    val toolbarAccessibilityRoot: ViewGroup get() = toolbar
    fun sourceClip(view:View):NativeTextSemanticsClip {
        var ancestor:View?=view
        while(ancestor!=null && ancestor!==this) {
            when(ancestor) {
                is ReverbAppTopBarView->ancestor.sourceClip(view)?.let{return it}
                is ReverbTopBarIconAction->ancestor.sourceClip(view)?.let{return it}
            }
            ancestor=ancestor.parent as? View
        }
        val rectangle={NativeTextSemanticsClip.Rectangle(smallthingz.ui.geometry.Rect(
            0f,0f,view.width.toFloat(),view.height.toFloat()))}
        return when {
            view===this || view is ReverbLibraryRecordingRow || view is LibraryExpansionViewport -> rectangle()
            view===listViewport || view===emptyViewport -> {
                val extra=ReverbLibraryLayout.horizontalOverflowPx(environment.textDensity.density).toFloat()
                NativeTextSemanticsClip.Rectangle(smallthingz.ui.geometry.Rect(-extra,0f,
                    view.width+extra,view.height.toFloat()))
            }
            view.parent is NativeGroupLayout && (view.parent as NativeGroupLayout).pointerContent===view -> rectangle()
            view is NativePointerLayerShape && view.clipToOutline -> rectangle()
            else -> NativeTextSemanticsClip.Unclipped
        }
    }
    val bodyPointerView: View get() = body
    val pointerEnvironment: ReverbViewEnvironment get() = environment
    val pointerDismissKey: Any get() = input.dismiss
    fun dismissFromEdge() {
        if (inlineModalActive) dismissInline() else input.dismiss()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        ReverbLibraryLayout.measureScreen(toolbar, body, width, height, input.scaffoldInsets, environment.textDensity.density)
        spotlight?.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        ReverbLibraryLayout.layoutScreen(toolbar, body, width, height, input.scaffoldInsets)
        spotlight?.layout(0, 0, width, height)
        spotlight?.invalidate()
        startInlineEditing()
        if (revealEditorAfterLayout) {
            revealEditorAfterLayout = false
            val header = renameHeader?.presentation?.view
            header?.post {
                if (!closed && input.owner.active && renameHeader?.presentation?.view === header)
                    list.revealRenameHeader(header, header.editor, header.outlinedField,
                        validation = renameHeader?.presentation?.owner?.state?.error != null)
            }
        }
        if (menuAwaitingLayout) presentation?.let(::renderMenu)
    }
    override fun close() {
        if (closed) return
        closed = true
        playbackReveal.cancel()
        list.onViewportGeometryChanged = {}
        var failure: Throwable? = null
        fun release(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        val previousRename = renameHeader
        renameHeader = null
        startRenamePending = false
        release { previousRename?.presentation?.close() }
        val oldSpotlight = spotlight
        spotlight = null
        spotlightRecordingId = null
        release { oldSpotlight?.close() }
        release(fallbackBack::remove)
        release(selectionBack::close)
        release(menus::close)
        release(pull::close)
        menuLifetimes.clear()
        pointerMenuOrigin = null
        // Match source rows disposing with screen; accepted mutations retain existing background owners.
        release(list::dispose)
        leases.toList().forEach { release(it::closePresentation) }
        leases.clear()
        release(owner::close)
        release(::removeAllViews)
        release(changedNodes)
        failure?.let { throw it }
    }
    private inner class Body(context: Context) : ViewGroup(context) {
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; clipChildren = false }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            val content = if (listVisible) list else status
            ReverbLibraryLayout.measureBody(content, refreshIndicator, undo, width, height, environment.textDensity.density)
            setMeasuredDimension(width, height)
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val content = if (listVisible) list else status
            ReverbLibraryLayout.layoutBody(content, refreshIndicator, undo, width, height, environment.textDensity.density)
        }
    }
}
