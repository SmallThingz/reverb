package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import smallthingz.ui.text.style.TextOverflow
import app.smallthingz.reverb.R
import app.smallthingz.reverb.NativeClickPhaseHost
import app.smallthingz.reverb.NativeClickPhaseEndpoint
import app.smallthingz.reverb.ReverbClickPhaseEndpoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
 * FilesScreen's 66dp top bar only. No status-bar padding, route ownership, selection state,
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
        normal?.bind(environment, value.hasIncidents,
            ReverbAppTopBarActions(callbacks.brand, callbacks.incidents, callbacks.settings, callbacks.back),
            applyStatusBarPadding = false)
        selection?.bind(environment, value, callbacks)
        applyMotion()
        requestLayout()
    }

    fun setEnvironment(value: ReverbViewEnvironment) {
        environment = value
        actions?.let { bind(state, it) }
        requestLayout()
    }

    private fun applyMotion() {
        val child = selection ?: return
        val progress = if (state.predictiveGestureActive) state.predictiveProgress.coerceIn(0f, 1f) else 0f
        child.translationX = state.predictiveDirection * child.width * progress
        child.alpha = 1f - .18f * progress
        child.scaleX = 1f - .015f * progress
        child.scaleY = 1f - .015f * progress
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (66f * environment.textDensity.density).roundToInt()
        for (index in 0 until childCount) getChildAt(index).measure(libraryToolbarExact(width), libraryToolbarExact(height))
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (index in 0 until childCount) getChildAt(index).layout(0, 0, width, height)
        applyMotion()
    }
}

private class LibrarySelectionBar(context: Context, private var environment: ReverbViewEnvironment, inputRoot: View) :
    ReverbTopBarActionRoutingGroup(context) {
    private val close = ReverbTopBarIconAction(context, environment, inputRoot, ReverbIcon.Close, R.string.clear_selection, true)
    private val share = ReverbTopBarIconAction(context, environment, inputRoot, ReverbIcon.Share, R.string.share_recording, true)
    private val delete = ReverbTopBarIconAction(context, environment, inputRoot, ReverbIcon.Delete, R.string.delete_recording, true)
    private val title = environment.paragraph(context, "", ReverbTextRole.TITLE_MEDIUM).apply {
        setTextLayout(maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    private val noise = ReverbNoiseDrawable(resources, environment.palette.surface, ReverbNoiseDrawable.SEED_TOP_BAR)
    init {
        background = noise
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(close); addView(title); addView(share); addView(delete)
    }
    fun bind(value: ReverbViewEnvironment, state: LibraryToolbarState, actions: LibraryToolbarActions) {
        environment = value
        noise.setBackgroundColor(value.palette.surface)
        title.setText(resources.getQuantityString(R.plurals.recordings_selected, state.selectedCount, state.selectedCount))
        value.bindParagraphStyle(title, ReverbTextRole.TITLE_MEDIUM)
        close.bind(value, true, value.palette.ink, value.palette.border, actions.clearSelection)
        share.bind(value, !state.selectionHasBusyMutation, value.palette.ink, value.palette.border, actions.shareSelection)
        delete.bind(value, state.acceptsDeletions && !state.selectionHasBusyMutation,
            value.palette.error, value.palette.border, actions.deleteSelection)
        touchDensity = value.textDensity.density
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = true
    private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val size = dp(48f)
        listOf(close, share, delete).forEach { it.measure(libraryToolbarExact(size), libraryToolbarExact(size)) }
        val available = max(0, width - dp(16f) - dp(16f) - size * 3 - dp(16f))
        title.measure(libraryToolbarExact(available), libraryToolbarAtMost(dp(66f)))
        setMeasuredDimension(width, resolveSize(dp(66f), heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var x = dp(16f)
        fun place(child: View) {
            val px = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - x - child.measuredWidth else x
            val y = ((height - child.measuredHeight) / 2f).roundToInt()
            child.layout(px, y, px + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth
        }
        place(close); x += dp(16f); place(title); place(share); place(delete)
    }
}


private fun libraryToolbarExact(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
private fun libraryToolbarAtMost(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.AT_MOST)

private fun isActivationKey(code: Int): Boolean = when (code) {
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> true
    else -> false
}
