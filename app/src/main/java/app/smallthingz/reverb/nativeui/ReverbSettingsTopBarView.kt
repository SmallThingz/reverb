package app.smallthingz.reverb.nativeui

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import app.smallthingz.nativeui.geometry.Rect as ComposeRect
import app.smallthingz.reverb.NativeDragPointerEvent
import app.smallthingz.reverb.NativePointerPass
import app.smallthingz.reverb.NativeViewPhasePointerNode
import app.smallthingz.reverb.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Values projected by SettingsScreen's existing draft/save owner. Callbacks retain all original
 * branch ordering (tile editor, calibration, Undo, Back and actual persist); this View never saves.
 */
internal data class SettingsTopBarBinding(
    val hasUnsavedChanges: Boolean,
    val settingsPersisting: Boolean,
    val calibrationRunning: Boolean,
    val quickTileEditorOpen: Boolean,
    val notificationDurationPending: Boolean,
    val onNavigation: () -> Unit,
    val onDone: () -> Unit,
) {
    val doneEnabled get() = hasUnsavedChanges && !settingsPersisting && !calibrationRunning &&
        !quickTileEditorOpen && !notificationDurationPending
}

/**
 * Released Settings CenterAlignedTopAppBar with null scrollBehavior.
 *
 * The root supplies the remaining systemBars.union(displayCutout) horizontal/top insets after
 * ancestor consumption. It also owns Settings' existing Initial-pass/focus/hide gate; do not
 * replace that gate with disabled navigation styling. No IME inset,66dp custom AppTopBar, stock
 * toolbar, elevation, title ellipsis, title maxLines or new text is introduced.
 *
 * Parent inserts [touchShieldNode] on this outer box and the two visual NativeClickPhaseHosts
 * into its existing source hit path. IconButton's inner circular node uses float48dp near hits.
 */
internal class ReverbSettingsTopBarView(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    inputRoot: () -> View?,
) : ViewGroup(context) {
    private var insets = NativeSettingsTopBarInsets(0, 0, 0)
    private val noise = ReverbNoiseDrawable(resources, initialEnvironment.palette.surface,
        ReverbNoiseDrawable.SEED_TOP_BAR)
    private val content = TopBarContent(context, initialEnvironment, inputRoot)
    val title: ReverbParagraphView get() = content.title
    val navigation: ReverbStandardIconButtonView get() = content.navigation
    val done: ReverbStandardIconButtonView get() = content.done
    val contentClipView: View get() = content
    val touchShieldNode = object : NativeViewPhasePointerNode {
        override val view: View get() = this@ReverbSettingsTopBarView
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
        override fun cancel() = Unit
    }

    init {
        background = noise
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false; clipToPadding = false
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(content)
    }

    fun bind(value: ReverbViewEnvironment, binding: SettingsTopBarBinding) {
        noise.setBackgroundColor(value.palette.surface)
        content.bind(value, binding)
        requestLayout()
    }

    fun setRemainingWindowInsets(value: NativeSettingsTopBarInsets) {
        require(value.left >= 0 && value.top >= 0 && value.right >= 0)
        if (value == insets) return
        insets = value
        requestLayout()
    }

    /** Include this exact viewport layer in the window's shared text/semantics clip policy. */
    fun sourceClip(view: View): NativeTextSemanticsClip? = when (view) {
        content -> NativeTextSemanticsClip.Rectangle(ComposeRect(0f, 0f, content.width.toFloat(), content.height.toFloat()))
        this -> NativeTextSemanticsClip.Unclipped
        else -> null
    }

    fun bindAccessibility(
        cursor: PlainTextAccessibilityWindowScope,
        geometry: NativePlainTextViewGeometry,
        sourceNode: (View) -> NativeSourceAccessibilityNode,
    ): AutoCloseable {
        val titleLease = title.bindPlainTextAccessibility(cursor, geometry,
            { sourceNode(title) }, { !sourceNode(title).hidden })
        val navLease = navigation.bindAccessibility(cursor, geometry, sourceNode)
        val doneLease = done.bindAccessibility(cursor, geometry, sourceNode)
        return AutoCloseable { doneLease.close(); navLease.close(); titleLease.close() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        require(MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            "Released TopAppBarMeasurePolicy requires bounded width"
        }
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val innerWidth = (w - insets.left - insets.right).coerceAtLeast(0)
        val maxHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED)
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        else MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(heightMeasureSpec) - insets.top).coerceAtLeast(0),
            MeasureSpec.AT_MOST)
        content.measure(exact(innerWidth), maxHeight)
        setMeasuredDimension(w, resolveSize(insets.top + content.measuredHeight, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content.layout(insets.left, insets.top,
            insets.left + content.measuredWidth, insets.top + content.measuredHeight)
    }
    override fun onTouchEvent(event: MotionEvent) = true // Released outer empty pointer node shields content behind the bar.
    fun cancelInput() { navigation.cancelInput(); done.cancelInput() }

    private class TopBarContent(
        context: Context,
        private var environment: ReverbViewEnvironment,
        inputRoot: () -> View?,
    ) : ViewGroup(context) {
        val navigation = ReverbStandardIconButtonView(context, environment, inputRoot)
        val title = environment.paragraph(context, context.getString(R.string.settings_title),
            ReverbTextRole.TITLE_LARGE, environment.palette.onSurface)
        val done = ReverbStandardIconButtonView(context, environment, inputRoot)
        private var logicalHeight = 0
        private var titleWrapperWidth = 0
        private var navigationWrapperWidth = 0
        private var actionWrapperWidth = 0
        private var navigationPadding = 0
        private var actionPadding = 0
        private var titlePadding = 0

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            clipChildren = false; clipToPadding = false
            addView(navigation); addView(title); addView(done)
        }
        fun bind(value: ReverbViewEnvironment, state: SettingsTopBarBinding) {
            environment = value
            val colors = value.palette
            value.bindParagraphStyle(title, ReverbTextRole.TITLE_LARGE, colors.onSurface)
            title.setText(context.getString(R.string.settings_title))
            navigation.bind(value, if (state.hasUnsavedChanges) ReverbIcon.Undo else ReverbIcon.Back,
                context.getString(if (state.hasUnsavedChanges) R.string.undo else R.string.onboarding_back),
                enabled = true, iconTint = colors.onSurface, inheritedContentColor = colors.onSurface,
                onClick = state.onNavigation)
            done.bind(value, ReverbIcon.Check, context.getString(R.string.done), state.doneEnabled,
                iconTint = if (state.hasUnsavedChanges) colors.onSurface else reverbColorWithAlpha(colors.onSurface, .38f),
                inheritedContentColor = colors.onSurfaceVariant, onClick = state.onDone)
        }
        private fun dp(value: Float) = (value * environment.textDensity.density).roundToInt()
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val maxH = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
                else MeasureSpec.getSize(heightMeasureSpec)
            val childH = if (maxH == Int.MAX_VALUE) unspecified() else atMost(maxH)
            navigationPadding = dp(4f)
            actionPadding = dp(4f)
            navigation.measure(atMost((w - navigationPadding).coerceAtLeast(0)), childH)
            done.measure(atMost((w - actionPadding).coerceAtLeast(0)), childH)
            navigationWrapperWidth = min(w, navigation.measuredWidth + navigationPadding)
            actionWrapperWidth = min(w, done.measuredWidth + actionPadding)
            val titleMax = (w - navigationWrapperWidth - actionWrapperWidth).coerceAtLeast(0)
            titlePadding = dp(4f)
            title.measure(atMost((titleMax - titlePadding * 2).coerceAtLeast(0)), childH)
            titleWrapperWidth = min(titleMax, title.measuredWidth + titlePadding * 2)
            logicalHeight = max(dp(64f), title.measuredHeight)
            setMeasuredDimension(w, resolveSize(logicalHeight, heightMeasureSpec))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            val p = settingsTopBarPlacement(width, logicalHeight, navigationWrapperWidth,
                navigation.measuredHeight, titleWrapperWidth, title.measuredHeight,
                actionWrapperWidth, done.measuredHeight, environment.textDensity.density, rtl)
            // Placeable apparentToRealOffset centers a source layout that exceeds constraints.
            val yOffset = (height - logicalHeight) / 2
            val nx = p.navigationX + if (rtl)
                navigationWrapperWidth - navigation.measuredWidth - navigationPadding else navigationPadding
            navigation.layout(nx, p.navigationY + yOffset, nx + navigation.measuredWidth,
                p.navigationY + yOffset + navigation.measuredHeight)
            val tx = p.titleX + if (rtl) titleWrapperWidth - title.measuredWidth - titlePadding else titlePadding
            title.layout(tx, p.titleY + yOffset, tx + title.measuredWidth,
                p.titleY + yOffset + title.measuredHeight)
            val ax = p.actionX + if (rtl) actionWrapperWidth - done.measuredWidth else 0
            done.layout(ax, p.actionY + yOffset, ax + done.measuredWidth,
                p.actionY + yOffset + done.measuredHeight)
        }
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            try {
                // Source windowInsetsPadding precedes clipToBounds: status/cutout area stays clear.
                canvas.clipRect(0, 0, width, height)
                super.draw(canvas)
            } finally { canvas.restoreToCount(save) }
        }
    }
    private companion object {
        fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        fun atMost(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST)
        fun unspecified() = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
    }
}
