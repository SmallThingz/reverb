package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeSelectionTopBarLayout
import smallthingz.ui.components.NativeToolbarBackground
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.components.NativeGroupLayout
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import smallthingz.ui.text.style.TextOverflow
import app.smallthingz.reverb.R

internal data class LibraryToolbarState(
    val selectedCount: Int,
    val selectionHasBusyMutation: Boolean,
    val acceptsDeletions: Boolean,
    val hasIncidents: Boolean,
    val showNormalTopBar: Boolean = true,
    val predictiveGestureActive: Boolean = false,
    val predictiveProgress: Float = 0f,
    val predictiveDirection: Float = 1f,
)
internal data class LibraryToolbarActions(
    val back: () -> Unit,
    val brand: () -> Unit,
    val incidents: () -> Unit,
    val settings: () -> Unit,
    val clearSelection: () -> Unit,
    val shareSelection: () -> Unit,
    val deleteSelection: () -> Unit,
)

/**
 * FilesScreen's compact top bar only. No status-bar padding, route ownership, selection state,
 * predictive-back registration or Home changes. Host supplies the released predictive frame.
 */
internal class ReverbLibraryToolbarView(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    private val nativeInputRoot: View,
) : ViewGroup(context) {
    private var environment = initialEnvironment
    private var state = LibraryToolbarState(0, false, true, false)
    private var actions: LibraryToolbarActions? = null
    private var normal: ReverbAppTopBarView? = null
    private var selection: LibrarySelectionBar? = null
    private var leftInset = 0
    private var rightInset = 0
    val contentHeightPx: Int get() = environment.appearance.toolbars.compactHeightPx(environment.textDensity.density)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
    }

    fun bind(value: LibraryToolbarState, callbacks: LibraryToolbarActions) {
        state = value
        actions = callbacks
        val selected = value.selectedCount > 0
        val progress = if (value.predictiveGestureActive) value.predictiveProgress.coerceIn(0f, 1f) else 0f
        val showNormal = if (selected) value.predictiveGestureActive && progress > 0f else value.showNormalTopBar
        if (showNormal && normal == null) {
            normal = ReverbAppTopBarView(context, environment, nativeInputRoot).also { addView(it, 0) }
        } else if (!showNormal) {
            normal?.let(::removeView)
            normal = null
        }
        if (selected && selection == null) {
            selection = LibrarySelectionBar(context, environment, nativeInputRoot).also(::addView)
        } else if (!selected) {
            selection?.let(::removeView)
            selection = null
        }
        normal?.setActionInsets(leftInset, rightInset)
        normal?.bind(environment, value.hasIncidents,
            ReverbAppTopBarActions(callbacks.brand, callbacks.incidents, callbacks.settings, callbacks.back),
            applyStatusBarPadding = false)
        selection?.bind(environment, value, callbacks)
        applyMotion()
        requestLayout()
    }

    fun setActionInsets(left: Int, right: Int) {
        leftInset = left
        rightInset = right
        normal?.setActionInsets(left, right)
    }

    fun setEnvironment(value: ReverbViewEnvironment) {
        environment = value
        actions?.let { bind(state, it) }
        requestLayout()
    }

    private fun applyMotion() {
        val child = selection ?: return
        val progress = if (state.predictiveGestureActive) state.predictiveProgress.coerceIn(0f, 1f) else 0f
        environment.appearance.toolbars.applySelectionBackPreview(child, progress, state.predictiveDirection)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = contentHeightPx
        for (index in 0 until childCount) getChildAt(index).measure(libraryToolbarExact(width), libraryToolbarExact(height))
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (index in 0 until childCount) getChildAt(index).layout(0, 0, width, height)
        applyMotion()
    }
}

private class LibrarySelectionBar(
    context: Context,
    environment: ReverbViewEnvironment,
    inputRoot: View,
    private val close: ReverbTopBarIconAction = ReverbTopBarIconAction(context, environment, inputRoot,
        ReverbIcon.Close, R.string.clear_selection, true),
    private val share: ReverbTopBarIconAction = ReverbTopBarIconAction(context, environment, inputRoot,
        ReverbIcon.Share, R.string.share_recording, true, sharedSurface = true),
    private val delete: ReverbTopBarIconAction = ReverbTopBarIconAction(context, environment, inputRoot,
        ReverbIcon.Delete, R.string.delete_recording, true, sharedSurface = true),
    private val selectionActions: NativeGroupLayout = NativeGroupLayout(context, environment.appearance.toolbars.action.radiusDp,
        LinearLayout.HORIZONTAL, onPaintedSurfaceChanged = ::invalidateSettingsTextContrast),
    private val title: smallthingz.ui.text.view.ParagraphView = environment.paragraph(context, "",
        environment.appearance.toolbars.selectionTitleRole, environment.appearance.toolbars.contentColor(environment.palette)).apply {
        setTextLayout(maxLines = 1, overflow = TextOverflow.Ellipsis)
    },
) : NativeSelectionTopBarLayout(context, environment.appearance.toolbars, close, title, selectionActions, 2) {
    private val chrome = NativeToolbarBackground(this, toolbarStyle, toolbarStyle.backgroundColor(environment.palette))
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        selectionActions.addRow(share, NativeGroupLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        selectionActions.addRow(delete, NativeGroupLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
            dividerStart = true
        })
    }
    fun bind(value: ReverbViewEnvironment, state: LibraryToolbarState, actions: LibraryToolbarActions) {
        bindStyle(value.textDensity.density, 0, value.appearance.toolbars)
        chrome.bind(toolbarStyle, toolbarStyle.backgroundColor(value.palette))
        title.setText(resources.getQuantityString(R.plurals.recordings_selected, state.selectedCount, state.selectedCount))
        value.bindParagraphStyle(title, toolbarStyle.selectionTitleRole, toolbarStyle.contentColor(value.palette))
        close.bind(value, true, NativeToolbarActionTone.NEUTRAL, actions.clearSelection)
        share.bind(value, !state.selectionHasBusyMutation, NativeToolbarActionTone.NEUTRAL, actions.shareSelection)
        delete.bind(value, state.acceptsDeletions && !state.selectionHasBusyMutation,
            NativeToolbarActionTone.DESTRUCTIVE, actions.deleteSelection)
        selectionActions.setCornerRadiusDp(toolbarStyle.action.radiusDp)
        selectionActions.setPalette(toolbarStyle.actionFill(value.palette),
            toolbarStyle.actionOutline(value.palette, NativeToolbarActionTone.NEUTRAL))
    }
}


private fun libraryToolbarExact(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
