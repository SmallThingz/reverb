package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.NativeClickPhaseEndpoint
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import androidx.compose.ui.unit.IntSize
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.ui.text.style.TextOverflow
import app.smallthingz.reverb.R
import kotlin.math.roundToInt

/** Already-formatted presentation only: no file I/O, date formatting, player or mutation owner. */
internal data class LibraryRecordingRow(
    val recordingId: String,
    val title: String,
    val subtitle: String,
    val timestamp: String?,
    val selected: Boolean,
    val selectionActive: Boolean,
    val interactionsEnabled: Boolean,
    val edges: ReverbRecordingGroupEdges?,
    val expanded: Boolean = false,
)

/** Both short clicks share the released action; the leading target has its own long-click action. */
internal data class LibraryRecordingRowActions(
    val activate: ((String) -> Unit)?,
    val longClick: ((String) -> Unit)?,
    val leadingLongClick: ((String) -> Unit)?,
)

/**
 * The explicit output of the source's four animateColorAsState/tween channels plus alpha channel.
 * The native row owns the original Oklab 180ms FastOutSlowIn channels. Packed colors exist only
 * at this paint boundary; channel values retain the original Color throughout interruption.
 */
internal data class LibraryRecordingRowFrame(
    val background: Int,
    val border: Int,
    val disc: Int,
    val icon: Int,
    val contentAlpha: Float,
) {
    companion object {
        fun target(row: LibraryRecordingRow, palette: ReverbPalette) = LibraryRecordingRowFrame(
            if (row.selected) reverbColorWithAlpha(palette.primaryContainer, 0.56f) else palette.field,
            if (row.selected) reverbColorWithAlpha(palette.primary, 0.48f) else palette.border,
            if (row.selected) reverbColorWithAlpha(palette.primary, 0.18f) else palette.raised,
            if (row.selected) palette.primary else palette.ink,
            if (row.selectionActive && !row.selected) 0.75f else 1f,
        )
    }
}

/**
 * RecordingSummaryCard with the source selection, visibility and outer-size motion. The real row is the click host; its 72dp leading child is a
 * separate click host. The parent list supplies current scroll interest and owns row recycling.
 * Group edges/outline use the narrow ReverbGroupLayout proposal supplied alongside this file.
 */
internal class ReverbLibraryRecordingRow(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    animationScope: CoroutineScope,
    private val nativeInputRoot: View,
) : FrameLayout(context) {
    private var environment = initialEnvironment
    private var row: LibraryRecordingRow? = null
    private var actions: LibraryRecordingRowActions? = null
    private val surface = ReverbGroupLayout(context)
    private val body = RecordingRowBody(context, initialEnvironment)
    private val column = RecordingCardColumn(context, body)
    private var selectionMotion: LibrarySelectionMotion? = null
    private var expansionMotion = LibraryExpansionMotion(false)
    private var outerSizeMotion: LibraryTweenValue<IntSize, androidx.compose.animation.core.AnimationVector2D>? = null
    private var exitPosted = false
    var onExpansionExit: (() -> Unit)? = null
    private val expansionViewport = LibraryExpansionViewport(context,
        currentMotion = { expansionMotion },
        needsFrames = { scheduleFrame() })
    private val frames: HomeFrameLoop = HomeFrameLoop(this, animationScope,
        { row != null && (selectionMotion?.running == true || expansionMotion.running || outerSizeMotion?.running == true) },
    ) { time, scale ->
        val previousExpansionSize = expansionMotion.measuredSize
        val previousOuterSize = outerSizeMotion?.value
        val selectionWasRunning = selectionMotion?.running == true
        selectionMotion?.frame(time, scale)
        expansionMotion.frame(time, scale)
        outerSizeMotion?.frame(time, scale)
        if (selectionWasRunning) selectionMotion?.value?.let(::setFrame)
        expansionViewport.publishAlpha()
        if (previousExpansionSize != expansionMotion.measuredSize || previousOuterSize != outerSizeMotion?.value) {
            requestLayout()
        }
        if (expansionMotion.exitCompleted && !exitPosted) {
            exitPosted = true
            val identity = row?.recordingId
            val owner = expansionMotion
            post {
                if (row?.recordingId == identity && expansionMotion === owner && owner.exitCompleted) {
                    onExpansionExit?.invoke()
                }
            }
        }
        scheduleFrame()
    }
    private val bodyIndication = ReverbRowIndication.install(body, initialEnvironment.palette.ink,
        ValueAnimator::getDurationScale)
    private val leadingIndication = ReverbRowIndication.install(body.leading, initialEnvironment.palette.ink,
        ValueAnimator::getDurationScale)
    var parentInterestedInDown: (MotionEvent) -> Boolean = { false }
    private val bodyInput = input(body, bodyIndication)
    private val leadingInput = input(body.leading, leadingIndication)

    init {
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        surface.addRow(column, ReverbGroupLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.sourceClick = { activate(); true }
        body.leading.sourceClick = { activate(); true }
        body.sourceLongClick = { longClick(leading = false) }
        body.leading.sourceLongClick = { longClick(leading = true) }
        body.input = bodyInput
        body.leading.input = leadingInput
        body.setOnClickListener { activate() }
        body.leading.setOnClickListener { activate() }
        body.setOnLongClickListener { longClick(leading = false) }
        body.leading.setOnLongClickListener { longClick(leading = true) }
        body.measured = {
            bodyIndication.onMeasured(body.width, body.height, environment.textDensity.density)
            leadingIndication.onMeasured(body.leading.width, body.leading.height, environment.textDensity.density)
        }
    }

    fun bind(value: LibraryRecordingRow, callbacks: LibraryRecordingRowActions) {
        if (row?.recordingId != value.recordingId) {
            bodyInput.bind(null)
            leadingInput.bind(null)
            body.replaceTextIdentities()
            cancelFrames()
            selectionMotion = LibrarySelectionMotion(LibraryRecordingRowFrame.target(value, environment.palette))
            expansionMotion = LibraryExpansionMotion(value.expanded)
            outerSizeMotion = null
            exitPosted = false
        }
        row = value
        actions = callbacks
        surface.setRecordingGroupEdges(value.edges)
        body.bind(value, callbacks)
        bindInputs()
        checkNotNull(selectionMotion).target(LibraryRecordingRowFrame.target(value, environment.palette),
            frames.durationScale)
        expansionMotion.setVisible(value.expanded)
        if (value.expanded) exitPosted = false
        setFrame(checkNotNull(selectionMotion).value)
        scheduleFrame()
        visibility = VISIBLE
    }

    fun setEnvironment(value: ReverbViewEnvironment) {
        environment = value
        body.setEnvironment(value)
        bodyIndication.updateColor(value.palette.ink)
        leadingIndication.updateColor(value.palette.ink)
        row?.let { selectionMotion?.target(LibraryRecordingRowFrame.target(it, value.palette),
            frames.durationScale) }
        selectionMotion?.value?.let(::setFrame)
        scheduleFrame()
    }

    /** Optional slots from the released SavingRecordingCard; ordinary Library rows keep defaults. */
    fun setSummarySlots(leadingContent: View?, trailingContent: View?) {
        body.leading.setContent(leadingContent)
        body.setTrailingContent(trailingContent)
    }
    val paragraphLeaves: List<ReverbParagraphView> get() = body.paragraphLeaves
    val summarySurface: ReverbGroupLayout get() = surface

    fun setFrame(frame: LibraryRecordingRowFrame) {
        surface.setPalette(frame.background, frame.border)
        surface.setRecordingSeparatorColor(environment.palette.border)
        column.alpha = frame.contentAlpha
        body.leading.setColors(frame.disc, frame.icon)
    }

    /** Player owns its presentation content; this row owns the released visibility/size motion. */
    fun setExpansionView(view: View?) {
        expansionViewport.setContent(view)
        column.setExpansionView(if (view == null) null else expansionViewport)
        if (view == null && expansionMotion.exitCompleted) {
            // Source disposes the completed AnimatedVisibility subtree; a later opening starts PreEnter.
            expansionMotion = LibraryExpansionMotion(false)
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        surface.measure(widthMeasureSpec, heightMeasureSpec)
        val natural = IntSize(surface.measuredWidth, surface.measuredHeight)
        val size = outerSizeMotion ?: LibraryTweenValue.size(natural).also { outerSizeMotion = it }
        size.target(natural, frames.durationScale)
        setMeasuredDimension(resolveSize(size.value.width, widthMeasureSpec),
            resolveSize(size.value.height, heightMeasureSpec))
        scheduleFrame()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // animateContentSize's default Alignment.TopStart; the child stays at its natural size.
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - surface.measuredWidth else 0
        surface.layout(x, 0, x + surface.measuredWidth, surface.measuredHeight)
    }

    private fun scheduleFrame() = frames.request()
    private fun cancelFrames() = frames.cancel()

    fun unbind() {
        bodyInput.bind(null)
        leadingInput.bind(null)
        row = null
        actions = null
        onExpansionExit = null
        cancelFrames()
        selectionMotion = null
        expansionMotion = LibraryExpansionMotion(false)
        outerSizeMotion = null
        expansionViewport.setContent(null)
        column.setExpansionView(null)
        body.clear()
        visibility = INVISIBLE
    }

    private fun activate() {
        val current = row ?: return
        if (current.interactionsEnabled) actions?.activate?.invoke(current.recordingId)
    }

    private fun longClick(leading: Boolean): Boolean {
        val current = row ?: return false
        if (!current.interactionsEnabled || actions?.activate == null) return false
        val callback = if (leading) actions?.leadingLongClick ?: actions?.longClick else actions?.longClick
        callback ?: return false
        callback(current.recordingId)
        return true
    }

    private fun bindInputs() {
        val current = row
        val callbacks = actions
        val enabled = current != null && current.interactionsEnabled && callbacks?.activate != null
        fun bind(target: RecordingRowInputTarget, controller: ReverbClickController, leading: Boolean) {
            val long = if (leading) callbacks?.leadingLongClick ?: callbacks?.longClick else callbacks?.longClick
            target.isClickable = enabled
            target.isLongClickable = enabled && long != null
            target.isFocusable = enabled
            target.refreshSemanticFlags()
            controller.bind(if (!enabled) null else NativeClickBinding(
                ownerKey = checkNotNull(current).recordingId,
                enabled = true,
                onClick = { target.performClick() },
                onLongClick = if (long == null) null else { { target.performLongClick(); Unit } },
            ))
            controller.focusChanged(target.isFocused)
        }
        bind(body, bodyInput, false)
        bind(body.leading, leadingInput, true)
    }

    private fun input(target: View, indication: ReverbRowIndication): ReverbClickController =
        ReverbClickController(target, object : NativeClickEnvironment {
            override val nativeInputRoot: View get() = this@ReverbLibraryRecordingRow.nativeInputRoot
            override val minimumTouchWidthPx get() = 48f * environment.textDensity.density
            override val minimumTouchHeightPx get() = minimumTouchWidthPx
            override fun admitDown(event: MotionEvent): Boolean = reverbGroupGeometry(
                target.width.toFloat(), target.height.toFloat(), environment.textDensity.density, 0f,
            ).acceptsPointer(event.x, event.y, event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER,
                minimumTouchWidthPx)
            override fun interestedParent(event: MotionEvent): Boolean = parentInterestedInDown(event)
            override fun longPressOwnershipChanged(owns: Boolean) {
                target.parent?.requestDisallowInterceptTouchEvent(owns)
            }
        }, ReverbRowIndicationSink(indication) { x, y, output -> output.set(x, y) })

    override fun onAttachedToWindow() { super.onAttachedToWindow(); bindInputs(); scheduleFrame() }
    override fun onDetachedFromWindow() {
        cancelFrames()
        bodyInput.bind(null)
        leadingInput.bind(null)
        super.onDetachedFromWindow()
    }
}

/** Source Column: both summary and inline content share the one selection-alpha layer. */
private class RecordingCardColumn(context: Context, private val body: View) : ViewGroup(context) {
    private var expansion: View? = null
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        addView(body)
    }
    fun setExpansionView(view: View?) {
        if (expansion === view) return
        expansion?.let(::removeView)
        expansion = view
        view?.let(::addView)
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        body.measure(widthSpec, heightMeasureSpec)
        val child = expansion
        if (child != null && child.visibility != GONE) {
            val remaining = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) heightMeasureSpec
                else MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(heightMeasureSpec) - body.measuredHeight).coerceAtLeast(0), MeasureSpec.AT_MOST)
            child.measure(widthSpec, remaining)
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(body.measuredHeight +
            if (child != null && child.visibility != GONE) child.measuredHeight else 0, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        body.layout(0, 0, body.measuredWidth, body.measuredHeight)
        expansion?.takeUnless { it.visibility == GONE }?.let {
            it.layout(0, body.measuredHeight, it.measuredWidth, body.measuredHeight + it.measuredHeight)
        }
    }
}

/** Forwards exact click-controller input; does not fall back to stock touch/key click handling. */
internal interface NativeLibrarySummaryInput : NativeClickPhaseHost {
    val sourceClickPresent: Boolean
}
private abstract class RecordingRowInputTarget(context: Context) : ViewGroup(context), NativeLibrarySummaryInput,
    NativeMergedRowAccessibilityHost {
    var sourceClick: () -> Boolean = { false }
    var sourceLongClick: () -> Boolean = { false }
    protected open fun semanticChildren(): List<View> = emptyList()
    override val mergesAccessibilityDescendants get() = sourceClickPresent
    override val mergedRowAccessibility = ReverbMergedRowAccessibility(this, NativeMergedRowRole.BUTTON,
        { NativeMergedRowAccessibilityState(true, null, clickLabel, semanticChildren(),
            selected = selectedSemantic, contentDescription = contentDescription,
            clickable = isClickable, longClickable = isLongClickable, longClickLabel = longClickLabel,
            rolePresent = isClickable) },
        { sourceClick() }, { sourceLongClick() })
    private val semanticPresent get() = isClickable || selectedSemantic != null || !contentDescription.isNullOrEmpty()
    override val hasAccessibilitySemanticNode get() = semanticPresent
    fun refreshSemanticFlags() {
        importantForAccessibility = if(semanticPresent) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
        isScreenReaderFocusable = isClickable || (semanticPresent && semanticChildren().isEmpty())
        mergedRowAccessibility.roleView.importantForAccessibility =
            if(isClickable)IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    protected fun measureRole() = mergedRowAccessibility.roleView.measure(
        MeasureSpec.makeMeasureSpec(measuredWidth,MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(measuredHeight,MeasureSpec.EXACTLY))
    protected fun layoutRole() = mergedRowAccessibility.roleView.layout(0,0,width,height)
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo? =
        if(semanticPresent && mergedRowAccessibility.isNodePresent())super.createAccessibilityNodeInfo()else null
    override fun addChildrenForAccessibility(out:ArrayList<View>) {
        if(semanticPresent)mergedRowAccessibility.addChildrenForAccessibility(out)else super.addChildrenForAccessibility(out)
    }
    private var endpoint: NativeClickPhaseEndpoint? = null
    var input: ReverbClickController? = null
        set(value) { field = value; endpoint = value?.let(::ReverbClickPhaseEndpoint) }
    override val nativeClickEndpoint: NativeClickPhaseEndpoint get() = checkNotNull(endpoint)
    override val sourceClickPresent: Boolean get() = isClickable
    var clickLabel: String? = null
    var longClickLabel: String? = null
    var selectedSemantic: Boolean? = null
    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(mergedRowAccessibility.roleView)
        accessibilityDelegate = mergedRowAccessibility
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = input?.touch(event) == true
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (isRecordingActivationKey(keyCode)) input?.key(event) == true else super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (isRecordingActivationKey(keyCode)) input?.key(event) == true else super.onKeyUp(keyCode, event)
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val accessibilityHandled = super.onHoverEvent(event)
        return input?.hover(event) == true || accessibilityHandled
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, old: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, old)
        input?.focusChanged(gainFocus)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        input?.attached()
        input?.focusChanged(isFocused)
    }
    override fun onDetachedFromWindow() { input?.detached(); super.onDetachedFromWindow() }

}

private class RecordingRowBody(context: Context, initial: ReverbViewEnvironment) : RecordingRowInputTarget(context) {
    private var environment = initial
    val leading = RecordingRowLeading(context, initial)
    private val title = initial.paragraph(context, "", ReverbTextRole.TITLE_MEDIUM)
    private val subtitle = initial.paragraph(context, "", ReverbTextRole.BODY_MEDIUM, initial.palette.muted)
    private val timestamp = initial.paragraph(context, "", ReverbTextRole.LABEL_LARGE)
    var measured: () -> Unit = {}
    private var textColumnWidth = 0
    private var trailingContent: View? = null
    private var hasTimestamp = false
    val paragraphLeaves = listOf(title, subtitle, timestamp)
    fun setTrailingContent(view: View?) {
        if (trailingContent === view) return
        trailingContent?.let(::removeView)
        trailingContent = view
        view?.let(::addView)
        timestamp.visibility = if (view == null && hasTimestamp) VISIBLE else GONE
        requestLayout()
    }
    override fun semanticChildren(): List<View> = buildList {
        if(leading.isClickable)add(leading)
        add(title);add(subtitle)
        trailingContent?.let(::add) ?: run { if(timestamp.visibility!=GONE)add(timestamp) }
    }
    private var bodyHeight = 0
    private var textColumnHeight = 0
    init {
        listOf(title, subtitle).forEach { it.setTextLayout(maxLines = 1, overflow = TextOverflow.Ellipsis) }
        listOf(leading, title, subtitle, timestamp).forEach { addView(it) }
    }
    fun bind(row: LibraryRecordingRow, actions: LibraryRecordingRowActions) {
        title.setText(row.title)
        subtitle.setText(row.subtitle)
        timestamp.setText(row.timestamp ?: "")
        hasTimestamp = row.timestamp != null
        timestamp.visibility = if (!hasTimestamp || trailingContent != null) GONE else VISIBLE
        val selectedLabel = context.getString(if (row.selected) R.string.recording_deselect else R.string.recording_select)
        clickLabel = if (row.selectionActive) selectedLabel else context.getString(
            if (row.expanded) R.string.recording_close_player else R.string.recording_open_player)
        longClickLabel = if (row.selectionActive) selectedLabel else context.getString(R.string.open_options)
        selectedSemantic = row.selected.takeIf { row.selectionActive }
        leading.contentDescription = row.title.takeIf { row.interactionsEnabled && actions.activate != null }
        leading.clickLabel = clickLabel
        leading.longClickLabel = if (actions.leadingLongClick != null) selectedLabel else longClickLabel
        leading.selectedSemantic = selectedSemantic
        requestLayout()
    }
    fun clear() {
        listOf(title, subtitle, timestamp).forEach { it.setText("") }
        selectedSemantic = null
        leading.selectedSemantic = null
        leading.contentDescription = null
        isClickable = false; isLongClickable = false; isFocusable = false
        leading.isClickable = false; leading.isLongClickable = false; leading.isFocusable = false
        refreshSemanticFlags();leading.refreshSemanticFlags()
    }
    fun replaceTextIdentities() {
        listOf(title, subtitle, timestamp).forEach { it.replacePlainTextAccessibilityIdentity() }
    }
    fun setEnvironment(value: ReverbViewEnvironment) {
        environment = value
        value.bindParagraphStyle(title, ReverbTextRole.TITLE_MEDIUM)
        value.bindParagraphStyle(subtitle, ReverbTextRole.BODY_MEDIUM, value.palette.muted)
        value.bindParagraphStyle(timestamp, ReverbTextRole.LABEL_LARGE)
        leading.setEnvironment(value)
        requestLayout()
    }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else MeasureSpec.getSize(heightMeasureSpec)
        val leadingWidth = minOf(dp(72), width)
        val leadingHeightSpec = if (maxHeight == Int.MAX_VALUE) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            else MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        leading.measure(MeasureSpec.makeMeasureSpec(leadingWidth, MeasureSpec.EXACTLY), leadingHeightSpec)
        val bodyWidth = (width - leadingWidth).coerceAtLeast(0)
        val innerWidth = (bodyWidth - dp(14)).coerceAtLeast(0)
        val top = dp(12)
        val bottom = dp(12)
        val innerHeight = (maxHeight.toLong() - top - bottom).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val textHeightSpec = if (maxHeight == Int.MAX_VALUE) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            else MeasureSpec.makeMeasureSpec(innerHeight, MeasureSpec.AT_MOST)
        val trailing = trailingContent ?: timestamp.takeIf { it.visibility != GONE }
        val gap = if (trailing == null) 0 else minOf(dp(if (trailingContent == null) 12 else 8), innerWidth)
        trailing?.measure(MeasureSpec.makeMeasureSpec((innerWidth - gap).coerceAtLeast(0), MeasureSpec.AT_MOST), textHeightSpec)
        textColumnWidth = (innerWidth - gap - (trailing?.measuredWidth ?: 0)).coerceAtLeast(0)
        val columnSpec = MeasureSpec.makeMeasureSpec(textColumnWidth, MeasureSpec.AT_MOST)
        title.measure(columnSpec, textHeightSpec)
        val subtitleTop = minOf(dp(4), (innerHeight - title.measuredHeight).coerceAtLeast(0))
        val subtitleHeight = if (maxHeight == Int.MAX_VALUE) textHeightSpec else MeasureSpec.makeMeasureSpec(
            (innerHeight - title.measuredHeight - subtitleTop).coerceAtLeast(0), MeasureSpec.AT_MOST)
        subtitle.measure(columnSpec, subtitleHeight)
        textColumnHeight = title.measuredHeight + subtitleTop + subtitle.measuredHeight
        bodyHeight = maxOf(textColumnHeight, trailing?.measuredHeight ?: 0) + top + bottom
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(maxOf(leading.measuredHeight, bodyHeight), heightMeasureSpec))
        measureRole()
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        fun center(space: Int, child: Int) = ((space - child) / 2f).roundToInt()
        fun place(view: View, start: Int, top: Int) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - view.measuredWidth else start
            view.layout(x, top, x + view.measuredWidth, top + view.measuredHeight)
        }
        place(leading, 0, center(height, leading.measuredHeight))
        val textStart = leading.measuredWidth
        val bodyTop = center(height, bodyHeight) + dp(12)
        val innerHeight = bodyHeight - dp(12) - dp(12)
        val columnTop = bodyTop + center(innerHeight, textColumnHeight)
        place(title, textStart, columnTop)
        place(subtitle, textStart, columnTop + textColumnHeight - subtitle.measuredHeight)
        (trailingContent ?: timestamp.takeIf { it.visibility != GONE })?.let { trailing ->
            place(trailing, width - dp(14) - trailing.measuredWidth,
                bodyTop + center(innerHeight, trailing.measuredHeight))
        }
        layoutRole()
        measured()
    }
    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection); requestLayout()
    }
}

private class RecordingRowLeading(context: Context, initial: ReverbViewEnvironment) : RecordingRowInputTarget(context) {
    private var environment = initial
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val disc = RectF()
    private var icon = ReverbIconDrawable(ReverbIcon.AudioFile, initial.textDensity.density, initial.palette.ink)
    private var discColor = initial.palette.raised
    private var iconColor = initial.palette.ink
    private var content: View? = null
    override fun semanticChildren(): List<View> = listOfNotNull(content)
    fun setContent(view: View?) {
        if (content === view) return
        content?.let(::removeView)
        content = view
        view?.let(::addView)
        requestLayout(); invalidate()
    }
    init { setWillNotDraw(false) }
    fun setColors(background: Int, tint: Int) {
        discColor = background; iconColor = tint
        paint.color = background; icon.setTint(tint); invalidate()
    }
    fun setEnvironment(value: ReverbViewEnvironment) {
        val densityChanged = environment.textDensity.density != value.textDensity.density
        environment = value
        if (densityChanged) icon = ReverbIconDrawable(ReverbIcon.AudioFile, value.textDensity.density, iconColor)
        requestLayout()
    }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(72), widthMeasureSpec), resolveSize(dp(68), heightMeasureSpec))
        content?.measure(MeasureSpec.makeMeasureSpec(minOf(dp(22), measuredWidth), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(minOf(dp(22), measuredHeight), MeasureSpec.EXACTLY))
        measureRole()
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val discWidth = minOf(dp(44), width)
        val discHeight = minOf(dp(44), height)
        val x = ((width - discWidth) / 2f).roundToInt()
        val y = ((height - discHeight) / 2f).roundToInt()
        disc.set(x.toFloat(), y.toFloat(), (x + discWidth).toFloat(), (y + discHeight).toFloat())
        val iconWidth = minOf(dp(24), discWidth)
        val iconHeight = minOf(dp(24), discHeight)
        val iconX = x + ((discWidth - iconWidth) / 2f).roundToInt()
        val iconY = y + ((discHeight - iconHeight) / 2f).roundToInt()
        icon.setBounds(iconX, iconY, iconX + iconWidth, iconY + iconHeight)
        content?.let { child ->
            val childX = x + ((discWidth - child.measuredWidth) / 2f).roundToInt()
            val childY = y + ((discHeight - child.measuredHeight) / 2f).roundToInt()
            child.layout(childX, childY, childX + child.measuredWidth, childY + child.measuredHeight)
        }
        layoutRole()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = discColor
        val radius = minOf(22f * environment.textDensity.density, minOf(disc.width(), disc.height()) / 2f)
        canvas.drawRoundRect(disc, radius, radius, paint)
        if (content == null) {
            icon.layoutDirection = layoutDirection
            icon.draw(canvas)
        }
    }
}

private fun isRecordingActivationKey(code: Int): Boolean = code == KeyEvent.KEYCODE_DPAD_CENTER ||
    code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_SPACE
