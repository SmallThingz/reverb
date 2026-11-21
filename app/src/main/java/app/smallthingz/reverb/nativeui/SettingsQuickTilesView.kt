package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
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
import smallthingz.ui.components.NativeInlineContentMorph
import smallthingz.ui.components.NativeQuickTileInlineLayout
import smallthingz.ui.components.NativeQuickTileRowView
import smallthingz.ui.components.NativeQuickTileSectionLayout

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
) : NativeQuickTileSectionLayout(context, environment, context.getString(R.string.tiles_ui_title),
    context.getString(R.string.tiles_ui_loading), context.getString(R.string.tiles_ui_apply_first),
    inputRoot, ::invalidateSettingsTextContrast, owner::retry), AutoCloseable {
    private val rowViews = QuickTileRowCache(
        create = { option -> QuickTileOptionRow(context, option, this.environment, inputRoot,
            owner::add, owner::requestDelete) },
        retire = QuickTileOptionRow::close,
    )
    private val create = SettingsCalibrationActionView(context, environment, inputRoot, ReverbIcon.Add) {
        owner.openCreation()
    }
    private var inline: QuickTileInlineView? = null
    private var inlineOwner: NativeQuickTileInlineOwner? = null
    private val creation = NativeInlineContentMorph<QuickTileInlineView>(context, create) { retired ->
        retired.close()
        if (inline === retired) { inline = null; inlineOwner = null }
        render()
    }
    private var closed = false
    private var rendering = false
    private var renderAgain = false

    init {
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
        bindListAppearance(environment)
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
            bindLoading()
            children += loadingRow
        }
        val canShowCreation = owner.failure == null && owner.options != null && items.size < QUICK_SAVE_TILE_COUNT
        if (canShowCreation) {
            create.bind(context.getString(R.string.tiles_ui_create),
                owner.interactive && !owner.editorState.editorOpen &&
                    owner.inputs.durationSubmissionAllowed, environment)
            children += creation
        }

        val nextOwner = owner.inline
        if (nextOwner != null && inlineOwner !== nextOwner) {
            inlineOwner = nextOwner
            inline = QuickTileInlineView(context, nextOwner, owner.lifecycle, environment, inputRoot,
                animationScope)
        }
        inline?.bind(environment, nextOwner != null)
        creation.editor(if (nextOwner != null) inline else null, canShowCreation)
        // The source action and editor share this exact row and its surrounding surface.
        if (creation.hasEditor && creation !in children) children += creation
        bindListContent(children, !owner.inputs.durationSubmissionAllowed,
            owner.failure ?: owner.observationFailure, context.getString(R.string.tiles_ui_retry), owner.interactive)
        deleteTargetChanged(owner.editorState.deleting?.takeIf { owner.inputs.active })
        requestLayout()
    }

    override fun close() {
        if (closed) return
        closed = true
        owner.close()
        creation.close()
        inline = null
        rowViews.close()
        create.close()
        closeRetry()
        deleteTargetChanged(null)
    }
}

private class QuickTileOptionRow(
    context: Context,
    private var option: QuickSaveTileOption,
    environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    private val add: (QuickSaveTileOption) -> Unit,
    private val delete: (QuickSaveTileOption) -> Unit,
    private val pin: ReverbStandardIconButtonView = ReverbStandardIconButtonView(context, environment, inputRoot),
    private val remove: ReverbStandardIconButtonView = ReverbStandardIconButtonView(context, environment, inputRoot),
) : NativeQuickTileRowView(context, environment, quickSaveTileBitmap(option.buffer, option.amount, option.unit, recipe = environment.appearance.quickTiles), pin, remove) {
    private var iconRecipe = environment.appearance.quickTiles

    fun bind(option: QuickSaveTileOption, added: QuickSaveTileAddedState, enabled: Boolean,
             next: ReverbViewEnvironment) {
        val recipe = next.appearance.quickTiles
        if (this.option != option || iconRecipe.glyphStroke != recipe.glyphStroke) {
            iconRecipe = recipe
            bindIcon(quickSaveTileBitmap(option.buffer, option.amount, option.unit, recipe = recipe))
        }
        this.option = option
        val showPin = added != QuickSaveTileAddedState.ADDED
        bindRow(next, context.getString(if (option.buffer == BufferSlot.LOOPING)
            R.string.buffer_loop else R.string.buffer_one_shot), formatDurationInput(option.seconds), showPin)
        val content = next.appearance.quickTiles.actionInk(next.palette.onSurface, enabled)
        if (showPin) pin.bind(next, if (added == QuickSaveTileAddedState.NOT_ADDED) ReverbIcon.Add else ReverbIcon.Settings,
            context.getString(if (added == QuickSaveTileAddedState.NOT_ADDED)
                R.string.tiles_ui_add else R.string.tiles_ui_setup), enabled, content, content) { add(this.option) }
        remove.bind(next, ReverbIcon.Delete, context.getString(R.string.tiles_ui_delete),
            enabled, content, content) { delete(this.option) }
        requestLayout()
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
) : NativeQuickTileInlineLayout(context), AutoCloseable {
    private val close = ReverbStandardIconButtonView(context, environment, inputRoot)
    private val selector = ReverbBufferSelectorView(context, environment, inputRoot, animationScope)
    private val save = SettingsCalibrationActionView(context, environment, inputRoot) { owner.save() }
    private var wheel: NativeNotificationDurationWheel? = null
    private var maximum = 1
    private var exiting = false
    private var closed = false
    init {
        installInline(environment, close, selector, save)
    }
    fun bind(next: ReverbViewEnvironment, interactive: Boolean = true) {
        if (closed) return
        val beganExit = !interactive && !exiting
        exiting = !interactive
        environment = next
        val iconColor = next.appearance.quickTiles.actionInk(next.palette.onSurface, interactive && !owner.target.busy)
        close.bind(next, ReverbIcon.Close, context.getString(R.string.cancel), interactive && !owner.target.busy,
            iconColor, iconColor, owner::dismiss)
        selector.setEnvironment(next)
        selector.bind(owner.target.buffer, null, false, owner.capacities?.oneShotSeconds != null,
            false, owner.capacities?.loopingSeconds != null, interactive && owner.enabled, owner::selectBuffer)
        val capacity = owner.capacity
        val showWheel = owner.durationSubmissionAllowed && capacity != null && capacity.maximumSeconds >= 1
        if (showWheel) {
            maximum = quickTileRepresentableSeconds(requireNotNull(capacity).maximumSeconds)
            val current = wheel ?: NativeNotificationDurationWheel(context, lifecycle,
                owner.durationInteraction, { owner.wheelOwner(maximum) },
                { owner.changeSeconds(it, maximum) }, ::quickTileStepSeconds,
                owner::interactionChanged).also { wheel = it; replaceWheelView(it.view) }
            current.bind(owner.target.seconds, maximum, interactive && owner.enabled && owner.capacityReady,
                owner.target.wheelOwner(owner.renderedRevision), next)
        } else wheel?.let {
            wheel = null
            it.close()
            replaceWheelView(null)
        }
        val loadingText = when {
            !owner.durationSubmissionAllowed -> null
            capacity == null && owner.message == null -> context.getString(R.string.tiles_ui_loading)
            capacity != null && capacity.maximumSeconds < 1 -> context.getString(R.string.tiles_ui_unavailable)
            else -> null
        }
        val warningText = if (showWheel && owner.target.seconds > maximum)
            context.getString(R.string.tiles_ui_reduce_duration, formatDurationInput(owner.target.seconds)) else null
        bindInlineLayout(next, loadingText, warningText, owner.message,
            context.getString(if (owner.shortened == null) R.string.tiles_ui_save else R.string.tiles_ui_save_anyway),
            interactive && owner.saveEnabled)
        if (beganExit) wheel?.cancelInteraction()
        requestLayout()
    }
    override fun close() {
        if (closed) return
        closed = true
        close.cancelInput()
        save.close()
        wheel?.close()
        wheel = null
    }
}
