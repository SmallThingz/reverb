package app.smallthingz.reverb.nativeui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
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
import app.smallthingz.reverb.R
import kotlin.math.max
import kotlin.math.roundToInt

internal data class IncidentsContentAppearance(
    val card: IncidentCardAppearance,
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
)

/**
 * Native contents of released IncidentsScreen. The caller retains the existing panel transform,
 * reversible open-progress, predictive Back, navigation, and history-observer lifetime.
 * No collection, accepted storage work, or Home changes are performed by this layout.
 */
internal class ReverbIncidentsContentView(
    context: Context,
    initialAppearance: IncidentsContentAppearance,
    menuHost: IncidentCardMenuHost,
    private var actions: IncidentCardActions,
    onBack: () -> Unit,
    frames: MonotonicFrameClock,
    scope: CoroutineScope,
    durationScale: () -> Float,
    inputRoot: View,
    private val savedPosition: NativeIncidentsSavedPosition = NativeIncidentsSavedPosition(),
) : ViewGroup(context) {
    private var appearance = initialAppearance
    private val backgroundNoise = ReverbNoiseDrawable(resources, initialAppearance.card.palette.surface)
    private val header = IncidentsHeader(context, initialAppearance, onBack, inputRoot)
    private val empty = IncidentsEmptyContent(context, initialAppearance, frames, scope, durationScale)
    private val adapter = IncidentsAdapter(context, initialAppearance.card, menuHost, actions, inputRoot)
    private var accessibilityMembershipChanged: () -> Unit = {}
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
                outRect.set(0, 0, 0, if (position >= 0 && position < state.itemCount - 1) dp(10f) else 0)
            }
        })
    }

    init {
        background = backgroundNoise
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addView(header)
        addView(list)
        addView(empty)
        updateListPadding()
        bind(IncidentHistoryUiState())
    }

    fun observeAccessibilityMembership(changed: () -> Unit) { accessibilityMembershipChanged = changed }
    fun bindSourceAccessibility(sourceNode: ((View) -> NativeSourceAccessibilityNode)?) {
        header.bindSourceAccessibility(sourceNode)
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

    fun setStatusBarInsets(insets: Insets) = header.setStatusBarInsets(insets)
    fun semanticsClip(view: View): NativeTextSemanticsClip = when {
        view === this || view === list ||
            (view.parent is ReverbGroupLayout && (view.parent as ReverbGroupLayout).getChildAt(0) === view) ->
            NativeTextSemanticsClip.Rectangle(smallthingz.ui.geometry.Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
        else -> NativeTextSemanticsClip.Unclipped
    }

    /** Already prepared history only; formatting and storage stay in the existing controller. */
    fun bind(state: IncidentHistoryUiState) {
        val hasRows = state.history.rows.isNotEmpty()
        list.visibility = if (hasRows) VISIBLE else GONE
        empty.visibility = if (hasRows) GONE else VISIBLE
        if (hasRows) {
            if (list.adapter == null) list.adapter = adapter
            adapter.submit(state)
            savedPosition.enterList()?.let { position ->
                manager.scrollToPositionWithOffset(position.firstVisibleItemIndex, -position.firstVisibleItemScrollOffset)
            }
        } else {
            savedPosition.leaveList()
            // Match removal of LazyColumn from composition, including owned popup/input disposal.
            list.itemAnimator?.endAnimations()
            adapter.disposeVisible(list)
            list.adapter = null
            adapter.submit(state)
            list.scrollToPosition(0)
            empty.bind(state)
        }
        requestLayout()
        accessibilityMembershipChanged()
    }

    fun setAppearance(value: IncidentsContentAppearance, onBack: () -> Unit, actions: IncidentCardActions) {
        appearance = value
        this.actions = actions
        backgroundNoise.setBackgroundColor(value.card.palette.surface)
        header.bind(value, onBack)
        empty.setAppearance(value)
        adapter.setAppearance(value.card, actions)
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
        bindSourceAccessibility(null)
        savedPosition.leaveList()
        list.itemAnimator?.endAnimations()
        adapter.disposeVisible(list)
        list.adapter = null
    }
}

/** Direct accepted-work and copy-feedback bridge; no optimistic acknowledgement/deletion state. */
internal fun incidentContentActions(context: Context, controller: IncidentHistoryController): IncidentCardActions =
    IncidentCardActions(controller::toggleAcknowledged, controller::delete) { row ->
        val text = buildList {
            add(row.date)
            add(row.stopSummary)
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
    private var appearance: IncidentsContentAppearance,
    onBack: () -> Unit,
    inputRoot: View,
) : ViewGroup(context) {
    private val back = ReverbIncidentBackView(context, appearance.card.palette, onBack).apply {
        pointerInputRoot = inputRoot
    }
    private val title = paragraph(context, appearance.card, appearance.titleLarge, appearance.card.palette.ink).apply {
        setText(context.getString(R.string.incidents_title))
    }
    private val noise = ReverbNoiseDrawable(resources, appearance.card.palette.surface, ReverbNoiseDrawable.SEED_TOP_BAR)
    private var insets = Insets.NONE
    private var sourceAccessibilityNode: ((View) -> NativeSourceAccessibilityNode)? = null

    fun bindSourceAccessibility(sourceNode: ((View) -> NativeSourceAccessibilityNode)?) {
        sourceAccessibilityNode = sourceNode
    }

    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) {
        val children = ArrayList<View>()
        super.addChildrenForAccessibility(children)
        children.filterTo(outChildren) { it !== back || sourceAccessibilityNode?.invoke(it)?.present != false }
    }

    init {
        background = noise
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(back)
        addView(title)
    }

    fun setStatusBarInsets(value: Insets) {
        if (insets != value) { insets = value; requestLayout() }
    }

    fun bind(value: IncidentsContentAppearance, onBack: () -> Unit) {
        appearance = value
        back.bind(value.card.palette, onBack)
        title.setTypography(value.titleLarge, value.card.density, value.card.fontResolver)
        title.setTextColor(value.card.palette.ink)
        noise.setBackgroundColor(value.card.palette.surface)
        requestLayout()
    }
    private fun dp(value: Float) = (value * appearance.card.density.density).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val contentHeight = dp(66f)
        val backSize = dp(46f)
        back.measure(MeasureSpec.makeMeasureSpec(backSize, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(backSize, MeasureSpec.EXACTLY))
        val titleWidth = max(0, width - insets.left - insets.right - dp(28f) - backSize - dp(8f))
        title.measure(MeasureSpec.makeMeasureSpec(titleWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.AT_MOST))
        setMeasuredDimension(width, resolveSize(contentHeight + insets.top + insets.bottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val rowTop = insets.top
        val contentHeight = dp(66f)
        val backX = if (rtl) width - insets.right - dp(14f) - back.measuredWidth else insets.left + dp(14f)
        val backY = rowTop + ((contentHeight - back.measuredHeight) / 2f).roundToInt()
        // IconButton(size46dp) keeps its outer Row allocation, while M3's minimum48dp
        // layout + Placeable's apparent-size centering can leave a one-pixel visual residual.
        val visualX = backX + nativeMinimumInteractiveVisualOffset(back.measuredWidth, dp(48f))
        val visualY = backY + nativeMinimumInteractiveVisualOffset(back.measuredHeight, dp(48f))
        back.layout(visualX, visualY, visualX + back.measuredWidth, visualY + back.measuredHeight)
        val titleX = if (rtl) backX - dp(8f) - title.measuredWidth else backX + back.measuredWidth + dp(8f)
        val titleY = rowTop + ((contentHeight - title.measuredHeight) / 2f).roundToInt()
        title.layout(titleX, titleY, titleX + title.measuredWidth, titleY + title.measuredHeight)
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
        title.setTextColor(value.card.palette.ink)
        detail.setTypography(value.card.bodyMedium, value.card.density, value.card.fontResolver)
        detail.setTextColor(value.card.palette.muted)
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
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun setColor(color: Int) { icon.setTint(color); invalidate() }
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
        text.setTextColor(value.palette.muted)
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
    private data class Item(val key: String, val row: IncidentPresentation?, val id: Long)
    private var items = emptyList<Item>()
    private var nextId = 1L
    private var canMutate = false
    init { setHasStableIds(true) }

    fun submit(state: IncidentHistoryUiState) {
        val old = items
        val oldCanMutate = canMutate
        val ids = old.associate { it.key to it.id }
        val next = buildList {
            if (state.history.rows.isNotEmpty() && state.status == IncidentHistoryLoadStatus.UNAVAILABLE) {
                add(Item("incident-history-status", null, ids["incident-history-status"] ?: nextId++))
            }
            state.history.rows.forEach { row -> add(Item(row.key, row, ids[row.key] ?: nextId++)) }
        }
        val difference = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                old[oldItemPosition].key == next[newItemPosition].key
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                old[oldItemPosition].row == next[newItemPosition].row && oldCanMutate == state.canMutate
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
    override fun getItemViewType(position: Int) = if (items[position].row == null) 0 else 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = if (viewType == 0) IncidentStaleView(context, appearance)
            else ReverbIncidentCardView(context, appearance, menus).apply {
                parentInterestedInDown = { true }
                pointerInputRoot = inputRoot
            }
        view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return object : RecyclerView.ViewHolder(view) {}
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = items[position].row
        if (row == null) (holder.itemView as IncidentStaleView).bind(appearance)
        else (holder.itemView as ReverbIncidentCardView).apply {
            setAppearance(appearance)
            bind(row, canMutate, actions)
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
    ReverbParagraphView(context, style, appearance.density, appearance.fontResolver, color)
