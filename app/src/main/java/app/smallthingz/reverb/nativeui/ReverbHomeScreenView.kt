package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewGroup
import app.smallthingz.reverb.ReverbService.BufferSlot
import kotlinx.coroutines.CoroutineScope

/**
 * Resolved physical Scaffold content insets. The window owns system-inset/consumption policy;
 * this screen does not read, change or guess system bars, IME visibility or accessibility settings.
 */
internal data class ReverbHomeInsets(
    val statusTop: Int,
    val contentLeft: Int,
    val contentRight: Int,
    val contentBottom: Int,
)

/**
 * Executable ordinary Home surface: unchanged background-noise phase, AppTopBar, and normal capture
 * body. The surrounding navigation/capture owner supplies data, lifecycle and existing operations.
 * Range export, permission recovery, transient save feedback and panel overlays are separate
 * released branches; this normal branch never substitutes fake state or launches their operations.
 */
internal class ReverbHomeScreenView(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    initialState: ReverbHomeBodyState,
    initialSelection: BufferSlot,
    initialActions: ReverbHomeActions,
    initialNavigation: ReverbAppTopBarActions,
    initialHasIncidents: Boolean,
    initialInsets: ReverbHomeInsets,
    nativeInputRoot: View,
    animationScope: CoroutineScope,
    onBoundsChanged: (body: View, bodyLocalBounds: RectF, blob: View) -> Unit,
) : ViewGroup(context), AutoCloseable {
    private var environment = initialEnvironment
    private var insets = initialInsets
    private var navigation = initialNavigation
    private var hasIncidents = initialHasIncidents
    private val noise = ReverbNoiseDrawable(resources, environment.palette.surface)
    private val header = ReverbAppTopBarView(context, environment, nativeInputRoot)
    private val bodyOwner = ReverbHomeBodyOwner(context, environment, initialState,
        initialSelection, initialActions, animationScope, { nativeInputRoot }, onBoundsChanged)

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        background = noise
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(header)
        addView(bodyOwner.view)
        header.bind(environment, initialHasIncidents, initialNavigation,
            statusInsetPx = insets.statusTop)
    }

    fun bind(
        state: ReverbHomeBodyState,
        selection: BufferSlot,
        actions: ReverbHomeActions,
        navigation: ReverbAppTopBarActions,
        hasIncidents: Boolean,
        nextInsets: ReverbHomeInsets,
    ) {
        if (insets != nextInsets) { insets = nextInsets; requestLayout() }
        this.navigation = navigation
        this.hasIncidents = hasIncidents
        header.bind(environment, hasIncidents, navigation, statusInsetPx = insets.statusTop)
        bodyOwner.bind(state, selection, actions)
    }

    fun setEnvironment(value: ReverbViewEnvironment) {
        environment = value
        noise.setBackgroundColor(value.palette.surface)
        header.bind(value, hasIncidents, navigation, statusInsetPx = insets.statusTop)
        bodyOwner.setEnvironment(value)
        requestLayout()
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        setMeasuredDimension(width, height)
        header.measure(exact(width), MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        val contentWidth = (width - insets.contentLeft - insets.contentRight).coerceAtLeast(0)
        val contentHeight = (height - header.measuredHeight - insets.contentBottom).coerceAtLeast(0)
        bodyOwner.view.measure(exact(contentWidth), exact(contentHeight))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        header.layout(0, 0, width, header.measuredHeight)
        val body = bodyOwner.view
        body.layout(insets.contentLeft, header.measuredHeight,
            insets.contentLeft + body.measuredWidth, header.measuredHeight + body.measuredHeight)
    }

    override fun close() = bodyOwner.close()
    private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
}
