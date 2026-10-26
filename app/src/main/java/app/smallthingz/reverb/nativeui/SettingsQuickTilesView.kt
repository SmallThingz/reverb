package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import app.smallthingz.reverb.NativeQuickSettingsTilesOwner
import app.smallthingz.reverb.NativeQuickTileInlineOwner
import app.smallthingz.reverb.NativeQuickTilesInputs
import app.smallthingz.reverb.QUICK_SAVE_TILE_COUNT
import app.smallthingz.reverb.QuickSaveTileAddedState
import app.smallthingz.reverb.QuickSaveTileOption
import app.smallthingz.reverb.QuickSettingsTileDeleteTarget
import app.smallthingz.reverb.R
import app.smallthingz.reverb.ReverbService.BufferSlot
import app.smallthingz.reverb.formatDurationInput
import app.smallthingz.reverb.quickSaveTileAddedState
import app.smallthingz.reverb.quickSaveTileBitmap
import app.smallthingz.reverb.quickTileRepresentableSeconds
import app.smallthingz.reverb.quickTileStepSeconds
import kotlinx.coroutines.CoroutineScope
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Validated options have one immutable revision per slot; retain creation order for teardown. */
internal class QuickTileRowCache<Row>(
    private val create: (QuickSaveTileOption) -> Row,
    private val retire: (Row) -> Unit,
) : AutoCloseable {
    private class Entry<Row>(val slot: Int, val revision: String, val row: Row)
    private val slots = arrayOfNulls<Entry<Row>>(QUICK_SAVE_TILE_COUNT)
    private val creationOrder = ArrayList<Entry<Row>>(QUICK_SAVE_TILE_COUNT)

    fun retain(options: List<QuickSaveTileOption>) {
        var seen = 0
        var retained = 0
        for (index in options.indices) {
            val option = options[index]
            val bit = 1 shl option.slot
            require(seen and bit == 0) { "Quick-tile rows require unique slots" }
            seen = seen or bit
            if (slots[option.slot]?.revision == option.revision) retained = retained or bit
        }
        var index = 0
        while (index < creationOrder.size) {
            val entry = creationOrder[index]
            if (retained and (1 shl entry.slot) == 0) {
                retire(entry.row)
                slots[entry.slot] = null
                creationOrder.removeAt(index)
            } else index++
        }
    }

    fun getOrCreate(option: QuickSaveTileOption): Row {
        val existing = slots[option.slot]
        if (existing != null) {
            check(existing.revision == option.revision) { "Retire old tile revisions before creating rows" }
            return existing.row
        }
        val entry = Entry(option.slot, option.revision, create(option))
        slots[option.slot] = entry
        creationOrder.add(entry)
        return entry.row
    }

    override fun close() {
        for (index in creationOrder.indices) retire(creationOrder[index].row)
        creationOrder.clear()
        slots.fill(null)
    }
}

/**
 * Released QuickSettingsTilesControls concrete section. Owns the source16dp/12dp outer inset once.
 * The tile model/accepted work remains in its owner; existing options have Add/Delete, never Edit.
 */
internal class SettingsQuickTilesView(
    context: Context,
    private val owner: NativeQuickSettingsTilesOwner,
    private var environment: ReverbViewEnvironment,
    private val inputRoot: () -> View?,
    private val animationScope: CoroutineScope,
    private val editorChanged: () -> Unit,
    private val deleteTargetChanged: (QuickSettingsTileDeleteTarget?) -> Unit,
) : ViewGroup(context), AutoCloseable {
    private val title = environment.paragraph(context, context.getString(R.string.tiles_ui_title),
        ReverbTextRole.TITLE_MEDIUM, environment.palette.ink)
    private val surface = ReverbGroupLayout(context, 20f)
    private val rows = TileRowsColumn(context)
    private val rowViews = QuickTileRowCache(
        create = { option -> QuickTileOptionRow(context, option, this.environment, inputRoot,
            owner::add, owner::requestDelete) },
        retire = QuickTileOptionRow::close,
    )
    private val loading = tileStatus(context, context.getString(R.string.tiles_ui_loading), environment)
    private val loadingInset = SettingsInsetView(context, loading, 16, 16, 16, 16, environment, false)
    private val create = SettingsCalibrationActionView(context, environment, inputRoot, ReverbIcon.Add) {
        owner.openCreation()
    }
    private val applyFirst = tileStatus(context, context.getString(R.string.tiles_ui_apply_first), environment)
    private val error = tileStatus(context, "", environment)
    private val retry = SettingsCalibrationActionView(context, environment, inputRoot) { owner.retry() }
    private var inline: QuickTileInlineView? = null
    private var inlineOwner: NativeQuickTileInlineOwner? = null
    private var ordered = emptyList<View>()
    private var closed = false
    private var rendering = false
    private var renderAgain = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        surface.addRow(rows, ReverbGroupLayout.LayoutParams(-1, -2))
        addView(title)
        addView(surface)
        addView(applyFirst)
        addView(error)
        addView(retry)
        owner.start {
            render()
            editorChanged()
        }
        render()
    }

    fun bind(inputs: NativeQuickTilesInputs, next: ReverbViewEnvironment) {
        check(!closed)
        environment = next
        owner.bind(inputs)
        render()
    }

    private fun render() {
        if (closed) return
        if (rendering) { renderAgain = true; return }
        rendering = true
        try {
            do {
                renderAgain = false
                renderOnce()
            } while (renderAgain && !closed)
        } finally { rendering = false }
    }

    private fun renderOnce() {
        val colors = environment.palette
        environment.bindParagraphStyle(title, ReverbTextRole.TITLE_MEDIUM, colors.ink)
        surface.setPalette(colors.field, colors.border)
        rows.dividerColor = colors.border
        val items = owner.options.orEmpty()
        rowViews.retain(items)
        val children = mutableListOf<View>()
        for (option in items) {
            val row = rowViews.getOrCreate(option)
            row.bind(option, quickSaveTileAddedState(option, owner.observations),
                owner.interactive && !owner.editorState.editorOpen && owner.failure == null,
                environment)
            children += row
        }
        if (owner.options == null && owner.failure == null) {
            environment.bindParagraphStyle(loading, ReverbTextRole.BODY_SMALL, colors.muted)
            loadingInset.setEnvironment(environment)
            children += loadingInset
        }
        if (owner.failure == null && owner.options != null && items.size < QUICK_SAVE_TILE_COUNT) {
            create.bind(context.getString(R.string.tiles_ui_create),
                owner.interactive && !owner.editorState.editorOpen &&
                    owner.inputs.durationSubmissionAllowed, environment)
            children += create
        }
        rows.bind(children)

        val nextOwner = owner.inline
        if (inlineOwner !== nextOwner) {
            inline?.let { removeView(it); it.close() }
            inlineOwner = nextOwner
            inline = nextOwner?.let {
                QuickTileInlineView(context, it, owner.lifecycle, environment, inputRoot,
                    animationScope).also(::addView)
            }
        }
        inline?.bind(environment)
        applyFirst.visibility = if (owner.inputs.durationSubmissionAllowed) GONE else VISIBLE
        environment.bindParagraphStyle(applyFirst, ReverbTextRole.BODY_SMALL, colors.muted)
        val failure = owner.failure ?: owner.observationFailure
        error.setText(failure.orEmpty())
        error.visibility = if (failure == null) GONE else VISIBLE
        environment.bindParagraphStyle(error, ReverbTextRole.BODY_SMALL, colors.muted)
        retry.visibility = error.visibility
        retry.bind(context.getString(R.string.tiles_ui_retry), owner.interactive, environment)
        ordered = listOfNotNull(title, surface, inline,
            applyFirst.takeIf { it.visibility != GONE },
            error.takeIf { it.visibility != GONE }, retry.takeIf { it.visibility != GONE })
        // Child drawing/focus order matches the source conditional Column, without detaching survivors.
        ordered.forEach(::bringChildToFront)
        deleteTargetChanged(owner.editorState.deleting?.takeIf { owner.inputs.active })
        requestLayout()
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val inner = (width - 2 * dp(16)).coerceAtLeast(0)
        var height = 2 * dp(12)
        val children = ordered
        for (index in children.indices) {
            val child = children[index]
            val fill = child === surface || child === inline
            child.measure(if (fill) exact(inner) else atMost(inner), unspecified())
            height += child.measuredHeight + if (index == 0) 0 else dp(8)
        }
        setMeasuredDimension(resolveSize(width, w), resolveSize(height, h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = dp(12)
        val children = ordered
        for (index in children.indices) {
            val child = children[index]
            if (index != 0) y += dp(8)
            placeTileLogical(child, dp(16), y)
            y += child.measuredHeight
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        owner.close()
        inline?.close()
        inline = null
        rowViews.close()
        create.close()
        retry.close()
        deleteTargetChanged(null)
    }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}

/** Source Column has no inter-row gap; only one physical-pixel divider over later row content. */
private class TileRowsColumn(context: Context) : ViewGroup(context) {
    private var ordered = emptyList<View>()
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    var dividerColor: Int
        get() = divider.color
        set(value) { divider.color = value; invalidate() }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun bind(next: List<View>) {
        for (old in ordered) if (old !in next) removeView(old)
        for (child in next) {
            if (child.parent == null) addView(child)
            bringChildToFront(child)
        }
        ordered = next
        requestLayout()
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        var height = 0
        val children = ordered
        for (index in children.indices) {
            val child = children[index]
            child.measure(exact(width), unspecified())
            height += child.measuredHeight
        }
        setMeasuredDimension(width, resolveSize(height, h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = 0
        val children = ordered
        for (index in children.indices) {
            val child = children[index]
            child.layout(0, y, width, y + child.measuredHeight)
            y += child.measuredHeight
        }
    }
    override fun drawChild(canvas: Canvas, child: View, time: Long): Boolean {
        val result = super.drawChild(canvas, child, time)
        if (ordered.indexOf(child) > 0) {
            divider.strokeWidth = 1f
            canvas.drawLine(0f, child.top + .5f, width.toFloat(), child.top + .5f, divider)
        }
        return result
    }
}

private class QuickTileOptionRow(
    context: Context,
    private var option: QuickSaveTileOption,
    private var environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    private val add: (QuickSaveTileOption) -> Unit,
    private val delete: (QuickSaveTileOption) -> Unit,
) : ViewGroup(context), AutoCloseable {
    private val bitmap = QuickTileBitmapView(context, option, environment.palette.ink)
    private val buffer = environment.paragraph(context, "", ReverbTextRole.BODY_LARGE,
        environment.palette.onSurface)
    private val duration = environment.paragraph(context, "", ReverbTextRole.BODY_SMALL,
        environment.palette.muted)
    private val pin = ReverbStandardIconButtonView(context, environment, inputRoot)
    private val remove = ReverbStandardIconButtonView(context, environment, inputRoot)
    private var textWidth = 0
    private var textHeight = 0
    private var columnWidth = 0
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(bitmap); addView(buffer); addView(duration); addView(pin); addView(remove)
    }
    fun bind(option: QuickSaveTileOption, added: QuickSaveTileAddedState,
        enabled: Boolean, next: ReverbViewEnvironment) {
        this.option = option
        environment = next
        bitmap.bind(option)
        bitmap.tint = next.palette.ink
        buffer.setText(context.getString(if (option.buffer == BufferSlot.LOOPING)
            R.string.buffer_loop else R.string.buffer_one_shot))
        duration.setText(formatDurationInput(option.seconds))
        next.bindParagraphStyle(buffer, ReverbTextRole.BODY_LARGE, next.palette.onSurface)
        next.bindParagraphStyle(duration, ReverbTextRole.BODY_SMALL, next.palette.muted)
        val content = if (enabled) next.palette.onSurface
            else reverbColorWithAlpha(next.palette.onSurface, .38f)
        if (added == QuickSaveTileAddedState.ADDED) {
            if (pin.visibility != GONE) pin.cancelInput()
            pin.visibility = GONE
        } else {
            pin.visibility = VISIBLE
            pin.bind(next, if (added == QuickSaveTileAddedState.NOT_ADDED) ReverbIcon.Add else ReverbIcon.Settings,
                context.getString(if (added == QuickSaveTileAddedState.NOT_ADDED)
                    R.string.tiles_ui_add else R.string.tiles_ui_setup), enabled, content, content) { add(this.option) }
        }
        remove.bind(next, ReverbIcon.Delete, context.getString(R.string.tiles_ui_delete),
            enabled, content, content) { delete(this.option) }
        requestLayout()
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        var remaining = (width - dp(16) - dp(4)).coerceAtLeast(0)
        bitmap.measure(exact(min(dp(32), remaining)), exact(dp(32)))
        remaining -= bitmap.measuredWidth
        if (pin.visibility != GONE) {
            pin.measure(atMost(remaining), unspecified())
            remaining -= pin.measuredWidth
        }
        remove.measure(atMost(remaining), unspecified())
        remaining -= remove.measuredWidth
        columnWidth = remaining.coerceAtLeast(0)
        textWidth = (columnWidth - 2 * dp(12)).coerceAtLeast(0)
        buffer.measure(atMost(textWidth), unspecified())
        duration.measure(atMost(textWidth), unspecified())
        textHeight = buffer.measuredHeight + duration.measuredHeight
        val rowHeight = max(max(bitmap.measuredHeight, textHeight),
            max(remove.measuredHeight, if (pin.visibility != GONE) pin.measuredHeight else 0))
        setMeasuredDimension(width, resolveSize(rowHeight + 2 * dp(4), h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var start = dp(16)
        placeTileLogical(bitmap, start, ((height - bitmap.measuredHeight) / 2f).roundToInt())
        start += bitmap.measuredWidth
        val y = ((height - textHeight) / 2f).roundToInt()
        placeTileLogical(buffer, start + dp(12), y)
        placeTileLogical(duration, start + dp(12), y + buffer.measuredHeight)
        start += columnWidth
        if (pin.visibility != GONE) {
            placeTileLogical(pin, start, ((height - pin.measuredHeight) / 2f).roundToInt())
            start += pin.measuredWidth
        }
        placeTileLogical(remove, start, ((height - remove.measuredHeight) / 2f).roundToInt())
    }
    override fun close() { pin.cancelInput(); remove.cancelInput() }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}

private class QuickTileBitmapView(context: Context, option: QuickSaveTileOption, color: Int) : View(context) {
    private var option = option
    private var bitmap = quickSaveTileBitmap(option.buffer, option.amount, option.unit)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = Rect()
    var tint: Int = color
        set(value) {
            if (field == value) return
            field = value
            paint.colorFilter = PorterDuffColorFilter(value, PorterDuff.Mode.SRC_IN)
            invalidate()
        }
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }
    fun bind(next: QuickSaveTileOption) {
        if (option == next) return
        option = next
        bitmap = quickSaveTileBitmap(next.buffer, next.amount, next.unit)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        destination.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, null, destination, paint)
    }
}

/** Stable target-keyed inline editor: changing buffer retains this surface and its native wheel. */
internal class QuickTileInlineView(
    context: Context,
    private val owner: NativeQuickTileInlineOwner,
    private val lifecycle: androidx.lifecycle.Lifecycle,
    private var environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    animationScope: CoroutineScope,
) : ViewGroup(context), AutoCloseable {
    private val surface = ReverbGroupLayout(context, 20f)
    private val body = Body(context)
    private val close = ReverbStandardIconButtonView(context, environment, inputRoot)
    private val selector = ReverbBufferSelectorView(context, environment, inputRoot, animationScope)
    private val loading = tileStatus(context, "", environment)
    private val warning = tileStatus(context, "", environment)
    private val message = tileStatus(context, "", environment)
    private val save = SettingsCalibrationActionView(context, environment, inputRoot) { owner.save() }
    private var wheel: NativeNotificationDurationWheel? = null
    private var maximum = 1
    private var ordered = emptyList<View>()
    private var closed = false
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surface.addRow(body, ReverbGroupLayout.LayoutParams(-1, -2))
        addView(surface)
        listOf(close, selector, loading, warning, message, save).forEach(body::addView)
    }
    fun bind(next: ReverbViewEnvironment) {
        if (closed) return
        environment = next
        surface.setPalette(next.palette.field, next.palette.border)
        val iconColor = if (!owner.target.busy) next.palette.onSurface
            else reverbColorWithAlpha(next.palette.onSurface, .38f)
        close.bind(next, ReverbIcon.Close, context.getString(R.string.cancel), !owner.target.busy,
            iconColor, iconColor, owner::dismiss)
        selector.setEnvironment(next)
        selector.bind(owner.target.buffer, null, false, owner.capacities?.oneShotSeconds != null,
            false, owner.capacities?.loopingSeconds != null, owner.enabled, owner::selectBuffer)
        val capacity = owner.capacity
        val showWheel = owner.durationSubmissionAllowed && capacity != null && capacity.maximumSeconds >= 1
        if (showWheel) {
            maximum = quickTileRepresentableSeconds(requireNotNull(capacity).maximumSeconds)
            val current = wheel ?: NativeNotificationDurationWheel(context, lifecycle,
                owner.durationInteraction, { owner.wheelOwner(maximum) },
                { owner.changeSeconds(it, maximum) }, ::quickTileStepSeconds,
                owner::interactionChanged).also { wheel = it; body.addView(it.view) }
            current.bind(owner.target.seconds, maximum, owner.enabled && owner.capacityReady,
                owner.target.wheelOwner(owner.renderedRevision), next)
        } else wheel?.let {
            wheel = null
            it.close()
            body.removeView(it.view)
        }
        val loadingText = when {
            !owner.durationSubmissionAllowed -> null
            capacity == null && owner.message == null -> context.getString(R.string.tiles_ui_loading)
            capacity != null && capacity.maximumSeconds < 1 -> context.getString(R.string.tiles_ui_unavailable)
            else -> null
        }
        bindStatus(loading, loadingText, next)
        bindStatus(warning, if (showWheel && owner.target.seconds > maximum)
            context.getString(R.string.tiles_ui_reduce_duration, formatDurationInput(owner.target.seconds)) else null, next)
        bindStatus(message, owner.message, next)
        save.bind(context.getString(if (owner.shortened == null) R.string.tiles_ui_save
            else R.string.tiles_ui_save_anyway), owner.saveEnabled, next)
        ordered = listOfNotNull(close, selector,
            loading.takeIf { it.visibility != GONE }, wheel?.view,
            warning.takeIf { it.visibility != GONE }, message.takeIf { it.visibility != GONE }, save)
        ordered.forEach(body::bringChildToFront)
        requestLayout()
    }
    override fun onMeasure(w: Int, h: Int) {
        surface.measure(w, h)
        setMeasuredDimension(surface.measuredWidth, surface.measuredHeight)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) =
        surface.layout(0, 0, width, height)
    override fun close() {
        if (closed) return
        closed = true
        close.cancelInput()
        save.close()
        wheel?.close()
        wheel = null
    }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
    private inner class Body(context: Context) : ViewGroup(context), app.smallthingz.reverb.NativeAndroidInteropHost {
    override val nativeInteropRequests = app.smallthingz.reverb.NativeAndroidInteropRequests()
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        nativeInteropRequests.request(disallowIntercept)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            val inner = (width - 2 * dp(16)).coerceAtLeast(0)
            var height = 2 * dp(16)
            val children = ordered
            for (index in children.indices) {
                val child = children[index]
                child.measure(if (child === wheel?.view) exact(inner) else atMost(inner),
                    if (child === wheel?.view) exact(dp(160)) else unspecified())
                height += child.measuredHeight + if (index == 0) 0 else dp(8)
            }
            setMeasuredDimension(width, resolveSize(height, h))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            var y = dp(16)
            val children = ordered
            for (index in children.indices) {
                val child = children[index]
                if (index != 0) y += dp(8)
                val start = when (child) {
                    close, save -> width - dp(16) - child.measuredWidth
                    selector -> ((width - child.measuredWidth) / 2f).roundToInt()
                    else -> dp(16)
                }
                if (child === selector) child.layout(start, y, start + child.measuredWidth,
                    y + child.measuredHeight)
                else placeTileLogical(child, start, y)
                y += child.measuredHeight
            }
        }
    }
}

private fun tileStatus(context: Context, value: String, environment: ReverbViewEnvironment) =
    environment.paragraph(context, value, ReverbTextRole.BODY_SMALL, environment.palette.muted).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
private fun bindStatus(view: ReverbParagraphView, text: String?, environment: ReverbViewEnvironment) {
    view.setText(text.orEmpty())
    view.visibility = if (text == null) View.GONE else View.VISIBLE
    environment.bindParagraphStyle(view, ReverbTextRole.BODY_SMALL, environment.palette.muted)
}
private fun ViewGroup.placeTileLogical(child: View, start: Int, top: Int) {
    val x = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
    child.layout(x, top, x + child.measuredWidth, top + child.measuredHeight)
}
