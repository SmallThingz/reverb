package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeBackdropStyle
import smallthingz.ui.components.NativeSelectionTopBarLayout
import smallthingz.ui.components.NativeToolbarActionTone
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import smallthingz.ui.text.style.TextOverflow
import smallthingz.ui.components.NativeToolbarBackground
import smallthingz.ui.components.NativeGroupLayout
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.text.TextStyle
import smallthingz.ui.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import androidx.core.graphics.Insets
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.smallthingz.reverb.AppFeedbackCenter
import app.smallthingz.reverb.FeedbackTone
import app.smallthingz.reverb.IncidentHistoryController
import app.smallthingz.reverb.IncidentHistoryLoadStatus
import app.smallthingz.reverb.IncidentHistoryUiState
import app.smallthingz.reverb.IncidentPresentation
import app.smallthingz.reverb.IncidentMessage
import app.smallthingz.reverb.RecordingIncident
import app.smallthingz.reverb.recordingIncidentReferenceMatches
import app.smallthingz.reverb.R
import app.smallthingz.reverb.summaryAt
import kotlin.math.max
import kotlin.math.roundToInt

internal data class IncidentsContentAppearance(
    val card: IncidentCardAppearance,
    val titleMedium: TextStyle,
    val backdrops: NativeBackdropStyle,
)

/**
 * Native contents of released IncidentsScreen. The caller retains the existing panel transform,
 * reversible open-progress, predictive Back, navigation, and history-observer lifetime.
 * No collection, accepted storage work, or Home changes are performed by this layout.
 */
internal class ReverbIncidentsContentView(
    context: Context,
    initialAppearance: IncidentsContentAppearance,
    initialEnvironment: ReverbViewEnvironment,
    menuHost: IncidentCardMenuHost,
    private var actions: IncidentCardActions,
    private val onBack: () -> Unit,
    frames: MonotonicFrameClock,
    scope: CoroutineScope,
    durationScale: () -> Float,
    inputRoot: View,
    private val savedPosition: NativeIncidentsSavedPosition = NativeIncidentsSavedPosition(),
) : ViewGroup(context) {
    private var appearance = initialAppearance
    private val backdrop = initialAppearance.backdrops.attachPage(this, initialAppearance.card.palette)
    private var environment = initialEnvironment
    private var history = IncidentHistoryUiState()
    private var selecting = false
    private val selection = linkedMapOf<String, RecordingIncident>()
    private var clearSheet: NativeIncidentClearSheet? = null
    private val header = IncidentsHeader(context, initialEnvironment, inputRoot, ::back,
        ::selectAllOrEnter, ::deleteSelected, ::requestClear)
    private val empty = IncidentsEmptyContent(context, initialAppearance, frames, scope, durationScale)
    private fun rowActions() = actions.copy(toggleSelection = ::toggleSelection)
    private val adapter = IncidentsAdapter(context, initialAppearance.card, menuHost, rowActions(), inputRoot)
    private var accessibilityMembershipChanged: () -> Unit = {}
    private val policyDurationTick = Runnable { updatePolicyDuration() }
    private val manager: LinearLayoutManager = object : LinearLayoutManager(context) {
        override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
            if (!state.isPreLayout) {
                savedPosition.pendingForPlacement(state.itemCount)?.let { position ->
                    scrollToPositionWithOffset(position.firstVisibleItemIndex, -position.firstVisibleItemScrollOffset)
                }
            }
            super.onLayoutChildren(recycler, state)
        }
        override fun onLayoutCompleted(state: RecyclerView.State) {
            super.onLayoutCompleted(state)
            if (!state.isPreLayout && state.itemCount > 0) {
                currentListPosition()?.let(savedPosition::placed)
            }
        }
    }
    private val list: ReverbLibraryRecyclerView = ReverbLibraryRecyclerView(context).apply {
        layoutManager = manager
        adapter = this@ReverbIncidentsContentView.adapter
        itemAnimator = ReverbIncidentItemAnimator(frames, scope, durationScale)
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = accessibilityMembershipChanged()
            override fun onChildViewDetachedFromWindow(view: View) = accessibilityMembershipChanged()
        })
        addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                outRect.set(0, 0, 0, if (position >= 0 && position < state.itemCount - 1) dp(2f) else 0)
            }
        })
    }

    init {
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addView(header)
        addView(list)
        addView(empty)
        updateListPadding()
        bind(IncidentHistoryUiState())
    }

    val selectionActive: Boolean get() = selecting

    fun bindHeaderAccessibility(cursor: PlainTextAccessibilityWindowScope, geometry: NativePlainTextViewGeometry,
                                sourceNode: (View) -> NativeSourceAccessibilityNode): AutoCloseable =
        header.bindAccessibility(cursor, geometry, sourceNode)

    fun observeAccessibilityMembership(changed: () -> Unit) { accessibilityMembershipChanged = changed }
    fun bindSourceAccessibility(sourceNode: ((View) -> NativeSourceAccessibilityNode)?) {
        empty.bindSourceAccessibility(sourceNode)
    }
    /** The source LazyColumn exists only in the nonempty history branch. */
    val pointerList: ReverbLibraryRecyclerView?
        get() = list.takeIf { it.visibility == VISIBLE && it.adapter != null }
    /** Same index/offset values as LazyListState.Saver; pending restore wins before placement. */
    fun savePosition(): NativeListSavedPosition? = savedPosition.save(currentListPosition())

    private fun currentListPosition(): NativeListSavedPosition? {
        if (list.visibility != VISIBLE || list.adapter == null) return null
        val index = manager.findFirstVisibleItemPosition()
        if (index == RecyclerView.NO_POSITION) return null
        val item = manager.findViewByPosition(index) ?: return null
        return NativeListSavedPosition(index, (list.paddingTop - manager.getDecoratedTop(item)).coerceAtLeast(0))
    }

    fun setStatusBarInsets(insets: Insets) = header.setWindowInsets(insets)
    fun semanticsClip(view: View): NativeTextSemanticsClip = when {
        view === this || view === list ||
            (view.parent is NativeGroupLayout && (view.parent as NativeGroupLayout).getChildAt(0) === view) ->
            NativeTextSemanticsClip.Rectangle(smallthingz.ui.geometry.Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
        else -> NativeTextSemanticsClip.Unclipped
    }

    /** Already prepared history only; timestamp formatting and storage stay off the UI thread. */
    fun bind(state: IncidentHistoryUiState) {
        history = state
        if (state.canMutate && selecting) {
            val previous = selection.toMap()
            selection.clear()
            val current = state.history.rows.associateBy { it.key }
            previous.forEach { (key, incident) ->
                val row = current[key] ?: current.values.singleOrNull {
                    recordingIncidentReferenceMatches(it.incident, incident)
                }
                if (row != null) selection[row.key] = row.incident
            }
            if (state.history.rows.isEmpty()) selecting = false
        }
        clearSheet?.setEnabled(state.canMutate)
        bindHeader()
        val hasRows = state.history.rows.isNotEmpty()
        list.visibility = if (hasRows) VISIBLE else GONE
        empty.visibility = if (hasRows) GONE else VISIBLE
        if (hasRows) {
            if (list.adapter == null) list.adapter = adapter
            adapter.submit(state, selection.keys.takeIf { selecting })
            savedPosition.enterList()?.let { position ->
                manager.scrollToPositionWithOffset(position.firstVisibleItemIndex, -position.firstVisibleItemScrollOffset)
            }
        } else {
            savedPosition.leaveList()
            // Match removal of LazyColumn from composition, including owned popup/input disposal.
            list.itemAnimator?.endAnimations()
            adapter.disposeVisible(list)
            list.adapter = null
            adapter.submit(state, selection.keys.takeIf { selecting })
            list.scrollToPosition(0)
            empty.bind(state)
        }
        requestLayout()
        accessibilityMembershipChanged()
        updatePolicyDuration()
    }

    fun consumeSelectionBack(): Boolean {
        if (!selecting) return false
        selecting = false
        selection.clear()
        updateSelection()
        return true
    }

    private fun back() {
        if (!consumeSelectionBack()) onBack()
    }

    private fun bindHeader() = header.bind(environment, selecting, selection.size,
        history.history.rows.size, history.canMutate)

    private fun updateSelection() {
        bindHeader()
        adapter.submit(history, selection.keys.takeIf { selecting })
        accessibilityMembershipChanged()
    }

    private fun selectAllOrEnter() {
        if (!history.canMutate || history.history.rows.isEmpty()) return
        if (!selecting) selecting = true
        else if (selection.size == history.history.rows.size) selection.clear()
        else history.history.rows.forEach { selection[it.key] = it.incident }
        updateSelection()
    }

    private fun toggleSelection(row: IncidentPresentation) {
        if (!selecting || !history.canMutate || history.history.rows.none { it.key == row.key }) return
        if (selection.remove(row.key) == null) selection[row.key] = row.incident
        updateSelection()
    }

    private fun deleteSelected() {
        if (!history.canMutate || selection.isEmpty()) return
        actions.deleteMany(selection.values.toList())
        consumeSelectionBack()
    }

    private fun requestClear() {
        if (!history.canMutate || clearSheet != null) return
        val targets = history.history.rows.map { it.incident }
        if (targets.isEmpty()) return
        lateinit var sheet: NativeIncidentClearSheet
        sheet = NativeIncidentClearSheet(this, environment, { if (clearSheet === sheet) dismissClear() }) {
            if (clearSheet === sheet && history.canMutate) {
                actions.deleteMany(targets)
                dismissClear()
            }
        }
        clearSheet = sheet
        try {
            sheet.show()
        } catch (failure: Throwable) {
            try { dismissClear() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun dismissClear() {
        val sheet = clearSheet
        clearSheet = null
        sheet?.close()
    }

    private fun updatePolicyDuration() {
        removeCallbacks(policyDurationTick)
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE) return
        if (history.history.rows.none { it.incident.ongoingPolicyDuration?.isOngoing == true }) return
        val now = SystemClock.elapsedRealtime()
        for (index in 0 until list.childCount) {
            (list.getChildAt(index) as? ReverbIncidentCardView)?.updatePolicyDuration(now)
        }
        postDelayed(policyDurationTick, 1000)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) updatePolicyDuration() else removeCallbacks(policyDurationTick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(policyDurationTick)
        super.onDetachedFromWindow()
    }

    fun setAppearance(value: IncidentsContentAppearance, environment: ReverbViewEnvironment, actions: IncidentCardActions) {
        appearance = value
        this.environment = environment
        this.actions = actions
        value.backdrops.bindPage(backdrop, value.card.palette)
        bindHeader()
        clearSheet?.bindEnvironment(environment)
        empty.setAppearance(value)
        adapter.setAppearance(value.card, rowActions())
        updateListPadding()
        requestLayout()
    }

    private fun dp(value: Float) = (value * appearance.card.density.density).roundToInt()
    private fun updateListPadding() = list.setPaddingRelative(dp(18f), dp(12f), dp(18f), dp(28f))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        header.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        val body = max(0, height - header.measuredHeight)
        val childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val childHeight = MeasureSpec.makeMeasureSpec(body, MeasureSpec.EXACTLY)
        for (index in 0..1) {
            val child = if (index == 0) list else empty
            if (child.visibility != GONE) child.measure(childWidth, childHeight)
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        header.layout(0, 0, width, header.measuredHeight)
        for (index in 0..1) {
            val child = if (index == 0) list else empty
            if (child.visibility != GONE) child.layout(0, header.measuredHeight, width, height)
        }
    }

    /** Root owner calls this when the source overlay leaves composition at fully closed progress. */
    fun dispose() {
        try {
            dismissClear()
        } finally {
            removeCallbacks(policyDurationTick)
            bindSourceAccessibility(null)
            savedPosition.leaveList()
            list.itemAnimator?.endAnimations()
            adapter.disposeVisible(list)
            list.adapter = null
        }
    }
}

/** Direct accepted-work and copy-feedback bridge; no optimistic acknowledgement/deletion state. */
internal fun incidentContentActions(context: Context, controller: IncidentHistoryController): IncidentCardActions =
    IncidentCardActions(controller::toggleAcknowledged, controller::delete, controller::deleteOccurrences, {}) { row ->
        val text = buildList {
            add(row.date)
            add(row.summaryAt(context, SystemClock.elapsedRealtime()))
            row.ended?.let { add(context.getString(R.string.incident_occurrence_end, it)) }
            if (row.cause.isNotEmpty()) add(row.cause)
            if (row.runtime.isNotEmpty()) add(row.runtime)
            if (row.age.isNotEmpty()) add(row.age)
            row.description?.let(::add)
        }.joinToString("\n")
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.incident_copy_label), text))
        AppFeedbackCenter.post(context.getString(R.string.incident_copied), FeedbackTone.SUCCESS)
    }

private class IncidentsHeader(
    context: Context,
    environment: ReverbViewEnvironment,
    inputRoot: View,
    private val back: () -> Unit,
    private val select: () -> Unit,
    private val deleteSelected: () -> Unit,
    private val clearAll: () -> Unit,
    private val navigation: ReverbTopBarIconAction = ReverbTopBarIconAction(context, environment, inputRoot,
        ReverbIcon.Back, R.string.back, selectionWrapper = true),
    private val title: ParagraphView = environment.paragraph(context, "",
        environment.appearance.toolbars.leadingTitleRole, environment.palette.onSurface).apply {
        setTextLayout(maxLines = 1, overflow = TextOverflow.Ellipsis)
    },
    private val actions: LinearLayout = LinearLayout(context),
) : FrameLayout(context) {
    private var selectionMode = false
    private val bar = NativeSelectionTopBarLayout(context, environment.appearance.toolbars, navigation, title, actions, 2)
    private val chrome = NativeToolbarBackground(this, environment.appearance.toolbars,
        environment.appearance.toolbars.backgroundColor(environment.palette))
    private val destructive = ReverbTopBarIconAction(context, environment, inputRoot,
        ReverbIcon.Delete, R.string.incidents_clear_all, selectionWrapper = true)
    private val selection = ReverbTopBarIconAction(context, environment, inputRoot,
        ReverbIcon.MultiSelect, R.string.multi_select, selectionWrapper = true)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        actions.orientation = LinearLayout.HORIZONTAL
        actions.clipChildren = false
        actions.addView(destructive, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        actions.addView(selection, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bindAccessibility(cursor: PlainTextAccessibilityWindowScope, geometry: NativePlainTextViewGeometry,
                          sourceNode: (View) -> NativeSourceAccessibilityNode): AutoCloseable {
        val leases = listOf(navigation, destructive, selection).map { it.bindAccessibility(cursor, geometry, sourceNode) }
        return AutoCloseable { leases.forEach(AutoCloseable::close) }
    }

    fun setWindowInsets(insets: Insets) = setPadding(insets.left, insets.top, insets.right, insets.bottom)

    fun bind(value: ReverbViewEnvironment, selecting: Boolean, selected: Int, count: Int, ready: Boolean) {
        val palette = value.palette
        val style = value.appearance.toolbars
        if (selectionMode != selecting) {
            selectionMode = selecting
            navigation.cancelInput()
            destructive.cancelInput()
            selection.cancelInput()
        }
        bar.bindStyle(value.textDensity.density, 0, style)
        chrome.bind(style, style.backgroundColor(palette))
        title.setText(if (selecting) resources.getQuantityString(R.plurals.incidents_selected, selected, selected)
            else context.getString(R.string.incidents_title))
        value.bindParagraphStyle(title, if (selecting) style.selectionTitleRole else style.leadingTitleRole,
            style.contentColor(palette))
        navigation.bindIcon(if (selecting) ReverbIcon.Close else ReverbIcon.Back,
            context.getString(if (selecting) R.string.clear_selection else R.string.back))
        navigation.bind(value, true, NativeToolbarActionTone.MUTED, back)
        destructive.visibility = if (count > 0) VISIBLE else INVISIBLE
        destructive.bindIcon(ReverbIcon.Delete, context.getString(if (selecting) R.string.incidents_delete_selected
            else R.string.incidents_clear_all))
        destructive.bind(value, ready && (if (selecting) selected > 0 else count > 0),
            NativeToolbarActionTone.DESTRUCTIVE, if (selecting) deleteSelected else clearAll)
        selection.visibility = if (count > 0) VISIBLE else INVISIBLE
        selection.bindIcon(ReverbIcon.MultiSelect, context.getString(when {
            !selecting -> R.string.multi_select
            selected == count -> R.string.incidents_deselect_all
            else -> R.string.incidents_select_all
        }))
        selection.bind(value, ready && count > 0, NativeToolbarActionTone.NEUTRAL, select)
    }
}

private class IncidentsEmptyContent(
    context: Context,
    private var appearance: IncidentsContentAppearance,
    frames: MonotonicFrameClock,
    scope: CoroutineScope,
    durationScale: () -> Float,
) : ViewGroup(context) {
    private val loading = IncidentsLoadingView(context, appearance.card.palette.primary, frames, scope, durationScale)
    private val icon = IncidentStatusIcon(context, appearance.card.palette.muted)
    private val title = paragraph(context, appearance.card, appearance.titleMedium, appearance.card.palette.ink)
    private val detail = paragraph(context, appearance.card, appearance.card.bodyMedium, appearance.card.palette.muted)
    private var state = IncidentHistoryUiState()
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(loading); addView(icon); addView(title); addView(detail)
        bind(state)
    }

    private var sourceAccessibilityNode: ((View) -> NativeSourceAccessibilityNode)? = null

    fun bindSourceAccessibility(sourceNode: ((View) -> NativeSourceAccessibilityNode)?) {
        sourceAccessibilityNode = sourceNode
        loading.bindSourceAccessibility(sourceNode?.let { node -> { node(loading) } })
    }

    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) {
        val children = ArrayList<View>()
        super.addChildrenForAccessibility(children)
        children.filterTo(outChildren) { it !== loading || sourceAccessibilityNode?.invoke(it)?.present != false }
    }

    fun bind(value: IncidentHistoryUiState) {
        state = value
        loading.visibility = if (value.status == IncidentHistoryLoadStatus.LOADING) VISIBLE else GONE
        icon.visibility = if (loading.visibility == VISIBLE) GONE else VISIBLE
        title.visibility = if (value.showEmptyHistory) VISIBLE else GONE
        if (value.showEmptyHistory) {
            title.setText(context.getString(R.string.incidents_empty))
            detail.setText(context.getString(R.string.incidents_empty_detail))
        } else {
            detail.setText(context.getString(if (value.status == IncidentHistoryLoadStatus.LOADING)
                R.string.incidents_loading else R.string.incidents_unavailable))
        }
        requestLayout()
    }

    fun setAppearance(value: IncidentsContentAppearance) {
        appearance = value
        loading.setColor(value.card.palette.primary)
        icon.setColor(value.card.palette.muted)
        title.setTypography(value.titleMedium, value.card.density, value.card.fontResolver)
        title.animateTextColor(value.card.palette.ink)
        detail.setTypography(value.card.bodyMedium, value.card.density, value.card.fontResolver)
        detail.animateTextColor(value.card.palette.muted)
        requestLayout()
    }

    private fun dp(value: Float) = (value * appearance.card.density.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val padding = dp(28f)
        val availableWidth = max(0, width - padding * 2)
        val availableHeight = max(0, height - padding * 2)
        var usedHeight = 0
        var count = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            if (count++ > 0) usedHeight += dp(10f)
            if (child === loading || child === icon) {
                val size = dp(if (child === loading) 28f else 34f)
                child.measure(MeasureSpec.makeMeasureSpec(minOf(size, availableWidth), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(minOf(size, max(0, availableHeight - usedHeight)), MeasureSpec.EXACTLY))
            } else child.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(max(0, availableHeight - usedHeight), MeasureSpec.AT_MOST))
            usedHeight += child.measuredHeight
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // These four private children keep fixed indices. Snapshot membership before layout callbacks.
        var visibleChildren = 0
        var visibleCount = 0
        var total = 0
        var columnWidth = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            visibleChildren = visibleChildren or (1 shl index)
            visibleCount++
            total += child.measuredHeight
            columnWidth = max(columnWidth, child.measuredWidth)
        }
        total += max(0, visibleCount - 1) * dp(10f)
        val padding = dp(28f)
        // Box centers the intrinsic Column first; Column then centers each child independently.
        val columnX = padding + ((max(0, width - padding * 2) - columnWidth) / 2f).roundToInt()
        var y = padding + ((max(0, height - padding * 2) - total) / 2f).roundToInt()
        for (index in 0 until childCount) {
            if (visibleChildren and (1 shl index) == 0) continue
            val child = getChildAt(index)
            val x = columnX + ((columnWidth - child.measuredWidth) / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            y += child.measuredHeight + dp(10f)
        }
    }
}

/** Incidents' unmerged progress leaf; other callers retain their own description/merge policy. */
private class IncidentsLoadingView(
    context: Context,
    color: Int,
    frames: MonotonicFrameClock,
    scope: CoroutineScope,
    durationScale: () -> Float,
) : ReverbIncidentLoadingView(context, color, frames, scope, durationScale) {
    private var sourceAccessibilityNode: (() -> NativeSourceAccessibilityNode)? = null

    init { isScreenReaderFocusable = true }

    fun bindSourceAccessibility(sourceNode: (() -> NativeSourceAccessibilityNode)?) {
        sourceAccessibilityNode = sourceNode
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.text = null
        info.contentDescription = null
        info.isFocusable = false
        info.isClickable = false
        info.isScreenReaderFocusable = true
        info.movementGranularities = 0
        val actions = info.actionList
        for (index in actions.size - 1 downTo 0) info.removeAction(actions[index])
        info.addAction(if (isAccessibilityFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        sourceAccessibilityNode?.invoke()?.populate(info)
    }

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if (sourceAccessibilityNode?.invoke()?.present == false) null else super.createAccessibilityNodeInfo()

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        if (sourceAccessibilityNode?.invoke()?.present == false) false else when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> super.performAccessibilityAction(action, arguments)
            else -> false
        }
}

private class IncidentStatusIcon(context: Context, color: Int) : View(context) {
    private val icon = ReverbIconDrawable(ReverbIcon.Incidents, resources.displayMetrics.density, color)
    private val colorMotion=colorTransition(color) {icon.setTint(it);invalidate()}
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun setColor(color: Int) {colorMotion.retarget(color)}
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { icon.setBounds(0, 0, w, h) }
    override fun onDraw(canvas: Canvas) { icon.draw(canvas) }
}

private class IncidentStaleView(context: Context, private var appearance: IncidentCardAppearance) : ViewGroup(context) {
    private val text = paragraph(context, appearance, appearance.bodyMedium, appearance.palette.muted).apply {
        setText(context.getString(R.string.incidents_stale))
    }
    init { addView(text); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun bind(value: IncidentCardAppearance) {
        appearance = value
        text.setTypography(value.bodyMedium, value.density, value.fontResolver)
        text.animateTextColor(value.palette.muted)
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val inset = (4f * appearance.density.density).roundToInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        text.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(resolveSize(text.measuredWidth, widthMeasureSpec), text.measuredHeight + inset * 2)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val inset = (4f * appearance.density.density).roundToInt()
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - text.measuredWidth else 0
        text.layout(x, inset, x + text.measuredWidth, inset + text.measuredHeight)
    }
}

private class IncidentsAdapter(
    private val context: Context,
    private var appearance: IncidentCardAppearance,
    private val menus: IncidentCardMenuHost,
    private var actions: IncidentCardActions,
    private val inputRoot: View,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private data class Item(
        val key: Any,
        val id: Long,
        val row: IncidentPresentation? = null,
        val heading: IncidentMessage? = null,
        val selected: Boolean? = null,
    )
    private var items = emptyList<Item>()
    private var nextId = 1L
    private var canMutate = false
    init { setHasStableIds(true) }

    fun submit(state: IncidentHistoryUiState, selection: Set<String>?) {
        val old = items
        val oldCanMutate = canMutate
        val ids = old.associate { it.key to it.id }
        val next = buildList {
            if (state.history.rows.isNotEmpty() && state.status == IncidentHistoryLoadStatus.UNAVAILABLE) {
                add(Item("incident-history-status", ids["incident-history-status"] ?: nextId++))
            }
            state.history.groups.forEach { (heading, rows) ->
                add(Item(heading, ids[heading] ?: nextId++, heading = heading))
                rows.forEach { row ->
                    add(Item(row.key, ids[row.key] ?: nextId++, row = row,
                        selected = selection?.contains(row.key)))
                }
            }
        }
        val difference = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                old[oldItemPosition].key == next[newItemPosition].key
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                old[oldItemPosition] == next[newItemPosition] && oldCanMutate == state.canMutate
        })
        items = next
        canMutate = state.canMutate
        difference.dispatchUpdatesTo(this)
    }

    fun setAppearance(value: IncidentCardAppearance, actions: IncidentCardActions) {
        appearance = value
        this.actions = actions
        notifyItemRangeChanged(0, itemCount)
    }
    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].id
    override fun getItemViewType(position: Int) = when {
        items[position].heading != null -> 2
        items[position].row != null -> 1
        else -> 0
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = when (viewType) {
            0 -> IncidentStaleView(context, appearance)
            2 -> paragraph(context, appearance, appearance.bodyLarge, appearance.palette.ink).apply {
                ViewCompat.setAccessibilityHeading(this, true)
            }
            else -> ReverbIncidentCardView(context, appearance, menus).apply {
                parentInterestedInDown = { true }
                pointerInputRoot = inputRoot
            }
        }
        view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return object : RecyclerView.ViewHolder(view) {}
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (val view = holder.itemView) {
            is IncidentStaleView -> view.bind(appearance)
            is ParagraphView -> {
                val density = appearance.density.density
                view.setPaddingRelative((8f * density).roundToInt(), (12f * density).roundToInt(),
                    (8f * density).roundToInt(), (4f * density).roundToInt())
                view.setTypography(appearance.bodyLarge.copy(fontWeight = smallthingz.ui.text.font.FontWeight.SemiBold),
                    appearance.density, appearance.fontResolver)
                view.animateTextColor(appearance.palette.ink)
                view.setText(checkNotNull(item.heading).displayText(context))
            }
            is ReverbIncidentCardView -> {
                view.setAppearance(appearance)
                view.bind(checkNotNull(item.row), canMutate, item.selected, actions)
            }
        }
    }
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder.itemView as? ReverbIncidentCardView)?.unbind()
        super.onViewRecycled(holder)
    }
    fun disposeVisible(list: RecyclerView) {
        for (index in 0 until list.childCount) (list.getChildAt(index) as? ReverbIncidentCardView)?.unbind()
    }
}

private fun paragraph(context: Context, appearance: IncidentCardAppearance, style: TextStyle, color: Int) =
    ParagraphView(context, style, appearance.density, appearance.fontResolver, color)
