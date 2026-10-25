package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import android.view.ViewGroup
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
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.RecordingEntity
import app.smallthingz.reverb.R
import app.smallthingz.reverb.libraryEmptyStateVisible
import kotlinx.coroutines.CoroutineScope
import kotlin.math.max
import kotlin.math.roundToInt

/** Already-consumed ScaffoldDefaults.contentWindowInsets; top is owned by the 66dp top bar. */
internal data class LibraryScaffoldInsets(val left: Int = 0, val right: Int = 0, val bottom: Int = 0)
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
    private val scrollRestoration = NativeLibraryScrollRestoration(restoredScrollPosition)
    private class RenameLifetime(val recordingId: String, val sheet: NativeRecordingRenameSheet)
    private class InfoLifetime(val recordingId: String, val sheet: NativeRecordingInfoSheet)
    private var renameSheet: RenameLifetime? = null
    private var infoSheet: InfoLifetime? = null
    private val toolbar = ReverbLibraryToolbarView(context, environment, inputRoot)
    private val body = Body(context)
    private var status: ReverbLibraryStatusView? = null
    private var bodyMode: LibraryBodyMode? = null
    private var listVisible = false
    private var undo: ReverbLibraryUndoCard? = null
    private val leases = LinkedHashSet<NativeLibraryPlayerLease>()
    private val menus = ReverbActionMenuHost(environment, frames, scope)
    private class MenuLifetime(val id: String, val anchor: View, val identity: Any = Any())
    private val menuLifetimes = mutableListOf<MenuLifetime>()
    private var activeMenu: MenuLifetime? = null
    private var menuAwaitingLayout = false
    private var selectionBackActive = false
    private var selectionBackProgress = 0f
    private var selectionBackDirection = 1f

    val owner = NativeLibraryController(context, this, lifecycleOwner, scope, frames,
        accessibilityManager, initial.owner, ::publish)

    private val pull = NativeLibraryPullRefreshOwner(animationScope, environment.textDensity,
        initialRefreshing = false, ancestor = nested, onRefresh = { owner.refresh(showSpinner = true) })
    private val refreshIndicator = ReverbLibraryPullRefreshIndicator(context, environment, pull,
        frames, animationScope, durationScale)
    val nestedScroll: NativeVerticalNestedHost get() = pull.nested

    // Keep screen fallback and selection registrations before lazily composed player owners.
    private val fallbackBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { owner.fallbackExpandedBack() }
    }.also { backDispatcher.addCallback(lifecycleOwner, it) }
    private val selectionBack = NativeLibraryPredictiveBack(backDispatcher, lifecycleOwner,
        scope, frames, ::selectionBackFrame)

    private val list = ReverbLibraryListView(context, environment, animationScope, inputRoot,
        LibraryRecordingRowActions(
            activate = { id -> currentRecording(id)?.let(owner::activate) },
            longClick = { id -> currentRecording(id)?.let(owner::longClick) },
            leadingLongClick = { id -> currentRecording(id)?.let(owner::leadingLongClick) }),
        object : LibraryInlineExpansionHost {
            override fun create(context: Context, recordingId: String): LibraryInlineExpansionLease {
                val lease = NativeLibraryPlayerLease(context, recordingId, environment, owner,
                    lifecycleOwner, backDispatcher, scope, frames, durationScale, inputRoot, changedNodes)
                leases += lease
                return object : LibraryInlineExpansionLease by lease {
                    override fun closePresentation() {
                        lease.closePresentation()
                        leases -= lease
                        changedNodes()
                    }
                }
            }
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
    }

    fun bind(next: NativeLibraryScreenInput, nextEnvironment: ReverbViewEnvironment = environment) {
        if (closed) return
        val environmentChanged = environment !== nextEnvironment
        val insetsChanged = input.scaffoldInsets != next.scaffoldInsets
        input = next
        environment = nextEnvironment
        if (environmentChanged) {
            menus.bindPalette(nextEnvironment.palette)
            renameSheet?.sheet?.bindEnvironment(nextEnvironment)
            infoSheet?.sheet?.bindEnvironment(nextEnvironment)
            refreshIndicator.bindPalette(nextEnvironment.palette)
            toolbar.setEnvironment(nextEnvironment)
            list.setEnvironment(nextEnvironment)
            status?.setEnvironment(nextEnvironment)
            leases.forEach { it.setEnvironment(nextEnvironment) }
        }
        owner.bind(next.owner)
        presentation?.let(::publish)
        if (insetsChanged) requestLayout()
    }

    fun saveScrollPosition(): NativeLibrarySavedScrollPosition = scrollRestoration.save(
        currentList = if (listVisible) list.savePosition() else null,
        currentEmpty = if (bodyMode == LibraryBodyMode.EMPTY) status?.emptyScrollPosition else null,
    )

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
        pull.bind(value.isRefreshing) { owner.refresh(showSpinner = true) }
        fallbackBack.isEnabled = input.owner.active && value.list.expandedOwner != null && value.selectedCount == 0
        selectionBack.bind(input.owner.active && value.selectedCount > 0, owner::clearSelection)
        toolbar.bind(toolbarState(value), toolbarActions())
        val mode = when {
            value.list.items.isEmpty() && !value.hasLoaded ->
                if (value.loadFailed) LibraryBodyMode.FAILED else LibraryBodyMode.LOADING
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
            restoredList?.let(list::restorePosition)
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
            current.bind(mode, restoredEmpty) { owner.refresh(showSpinner = true) }
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
            card.bind(environment, notice.message) { owner.undoDelete(notice.window) }
        }
        renderMenu(value)
        renderSheets(value)
        transientStateChanged(value)
        changedNodes()
    }

    private fun renderSheets(value: NativeLibraryPresentation) {
        val rename = value.renameRecording
        if (renameSheet?.recordingId != rename?.id) {
            val previous = renameSheet
            renameSheet = null // Retire before dismissal can reenter callbacks.
            previous?.sheet?.close()
        }
        if (rename != null) {
            val callbacks = NativeRecordingRenameCallbacks(owner::dismissRename, owner::renamed, owner::renameUncertain)
            val current = renameSheet
            if (current == null) {
                val sheet = NativeRecordingRenameSheet(this, environment, rename, input.owner.active,
                    lifecycleOwner, scope, callbacks)
                renameSheet = RenameLifetime(rename.id, sheet)
                sheet.show()
            } else current.sheet.bind(rename, input.owner.active, callbacks)
        }
        val info = value.infoRecording
        if (infoSheet?.recordingId != info?.id) {
            val previous = infoSheet
            infoSheet = null
            previous?.sheet?.close()
        }
        if (info != null) {
            val current = infoSheet
            if (current == null) {
                val sheet = NativeRecordingInfoSheet(this, environment, info, owner::dismissInfo)
                infoSheet = InfoLifetime(info.id, sheet)
                sheet.show()
            } else current.sheet.bind(info, owner::dismissInfo)
        }
    }

    private fun renderMenu(value: NativeLibraryPresentation) {
        val iterator = menuLifetimes.iterator()
        while (iterator.hasNext()) {
            val lifetime = iterator.next()
            if (!lifetime.anchor.isAttachedToWindow || list.attachedRecordingAnchor(lifetime.id) !== lifetime.anchor) {
                menus.release(lifetime.identity)
                if (activeMenu === lifetime) activeMenu = null
                iterator.remove()
            }
        }
        val recording = value.menuRecording
        if (recording == null) {
            activeMenu?.let { menus.hide(it.identity) }
            activeMenu = null
            menuAwaitingLayout = false
            return
        }
        val anchor = list.attachedRecordingAnchor(recording.id)
        if (anchor == null || !anchor.isLaidOut) { menuAwaitingLayout = true; return }
        menuAwaitingLayout = false
        val lifetime = menuLifetimes.firstOrNull { it.id == recording.id && it.anchor === anchor }
            ?: MenuLifetime(recording.id, anchor).also(menuLifetimes::add)
        if (activeMenu !== lifetime) activeMenu?.let { menus.hide(it.identity) }
        activeMenu = lifetime
        val busy = recording.id in value.list.activeMutationIds || recording.id in value.list.localOperationBusyIds
        val ink = environment.palette.ink
        fun item(label: Int, icon: ReverbIcon, enabled: Boolean = !busy, color: Int = ink, action: () -> Unit) =
            ReverbMenuAction(context.getString(label), icon, color, color, enabled, action)
        menus.show(lifetime.identity, anchor, listOf(
            item(R.string.rename_recording, ReverbIcon.Edit) { owner.rename(recording) },
            item(R.string.recording_info, ReverbIcon.Info) { owner.info(recording) },
            item(R.string.share_recording, ReverbIcon.Share) { owner.share(recording) },
            item(R.string.trim_recording, ReverbIcon.Trim) { owner.trim(recording) },
            item(R.string.delete_recording, ReverbIcon.Delete, !busy && value.acceptsDeletions,
                environment.palette.error) { owner.deleteRecordings(listOf(recording)) },
            item(R.string.multi_select, ReverbIcon.MultiSelect) { owner.multiSelect(recording) },
        ), environment.palette.raised, owner::dismissMenu)
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
    fun recordingMenuAnchor(id: String): View? = list.attachedRecordingAnchor(id)
    fun pointerNodes(view: View): List<NativeViewPhasePointerNode> {
        for (lease in leases) {
            val nodes = lease.pointerNodes(view)
            if (nodes.isNotEmpty()) return nodes
        }
        return emptyList()
    }
    val emptyViewport: NativeVerticalViewport? get() = status?.emptyViewport
    val listViewport: ReverbLibraryRecyclerView? get() = list.scrollView.takeIf { listVisible }
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
                val extra=(30f*environment.textDensity.density).roundToInt().toFloat()
                NativeTextSemanticsClip.Rectangle(smallthingz.ui.geometry.Rect(-extra,0f,
                    view.width+extra,view.height.toFloat()))
            }
            view.parent is ReverbGroupLayout && (view.parent as ReverbGroupLayout).pointerContent===view -> rectangle()
            view is NativePointerLayerShape && view.clipToOutline -> rectangle()
            else -> NativeTextSemanticsClip.Unclipped
        }
    }
    val bodyPointerView: View get() = body
    val pointerEnvironment: ReverbViewEnvironment get() = environment
    val pointerDismissKey: Any get() = input.dismiss
    fun dismissFromEdge() = input.dismiss()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        toolbar.measure(libraryRootExact(width), libraryRootExact(dp(66f)))
        val insets = input.scaffoldInsets
        body.measure(libraryRootExact(max(0, width - insets.left - insets.right)),
            libraryRootExact(max(0, height - toolbar.measuredHeight - insets.bottom)))
        setMeasuredDimension(width, height)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        toolbar.layout(0, 0, width, toolbar.measuredHeight)
        val insets = input.scaffoldInsets
        body.layout(insets.left, toolbar.measuredHeight, width - insets.right, height - insets.bottom)
        if (menuAwaitingLayout) presentation?.let(::renderMenu)
    }
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        val previousRename = renameSheet
        val previousInfo = infoSheet
        renameSheet = null
        infoSheet = null
        release { previousRename?.sheet?.close() }
        release { previousInfo?.sheet?.close() }
        release(fallbackBack::remove)
        release(selectionBack::close)
        release(menus::close)
        release(pull::close)
        menuLifetimes.clear()
        // Match source rows disposing with screen; accepted mutations retain existing background owners.
        release(list::dispose)
        leases.toList().forEach { release(it::closePresentation) }
        leases.clear()
        release(owner::close)
        release(::removeAllViews)
        release(changedNodes)
        failure?.let { throw it }
    }
    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()

    private inner class Body(context: Context) : ViewGroup(context) {
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; clipChildren = false }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            val content = if (listVisible) list else status
            content?.measure(libraryRootExact(width), libraryRootExact(height))
            refreshIndicator.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
            undo?.measure(libraryRootExact(max(0, width - dp(16f) - dp(16f))),
                MeasureSpec.makeMeasureSpec(max(0, height - dp(12f) - dp(12f)), MeasureSpec.AT_MOST))
            setMeasuredDimension(width, height)
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val content = if (listVisible) list else status
            content?.layout(0, 0, width, height)
            val indicatorX = ((width - refreshIndicator.measuredWidth) / 2f).roundToInt()
            refreshIndicator.layout(indicatorX, 0, indicatorX + refreshIndicator.measuredWidth, refreshIndicator.measuredHeight)
            undo?.let { card ->
                val x = ((width - card.measuredWidth) / 2f).roundToInt()
                val y = height - dp(12f) - card.measuredHeight
                card.layout(x, y, x + card.measuredWidth, y + card.measuredHeight)
            }
        }
    }
}
private fun libraryRootExact(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
