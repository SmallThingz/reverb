package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeTopBarInsets
import smallthingz.ui.components.NativeCenteredTopBarLayout
import smallthingz.ui.components.NativeToolbarBackground
import smallthingz.ui.components.NativeToolbarHint
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import smallthingz.ui.geometry.Rect as ComposeRect
import smallthingz.ui.input.phase.NativeDragPointerEvent
import smallthingz.ui.input.phase.NativePointerPass
import smallthingz.ui.input.phase.NativeViewPhasePointerNode
import app.smallthingz.reverb.R
import kotlinx.coroutines.CoroutineScope

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
    val backHint: Any? = null,
) {
    val doneEnabled get() = hasUnsavedChanges && !settingsPersisting && !calibrationRunning &&
        !quickTileEditorOpen && !notificationDurationPending
}

/** Centered Settings title with the compact Reverb action slots and the existing input gate. */
internal class ReverbSettingsTopBarView(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    animationScope: CoroutineScope,
    inputRoot: () -> View?,
) : ViewGroup(context), AutoCloseable {
    private var insets = NativeTopBarInsets(0, 0, 0)
    private val chrome = NativeToolbarBackground(this, initialEnvironment.appearance.toolbars,
        initialEnvironment.appearance.toolbars.backgroundColor(initialEnvironment.palette)) {
        invalidateSettingsTextContrast(this)
    }
    private val content = TopBarContent(context, initialEnvironment, inputRoot)
    private val backHint = NativeToolbarHint(this, animationScope, initialEnvironment.appearance.toolbars,
        initialEnvironment.appearance.toolbars.contentColor(initialEnvironment.palette), content.navigation.visual, content.done.visual)
    val title: ParagraphView get() = content.title
    val navigation: ReverbTopBarIconAction get() = content.navigation
    val done: ReverbTopBarIconAction get() = content.done
    val contentClipView: View get() = content
    val touchShieldNode = object : NativeViewPhasePointerNode {
        override val view: View get() = this@ReverbSettingsTopBarView
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
        override fun cancel() = Unit
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false; clipToPadding = false
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        addView(content)
    }

    fun bind(value: ReverbViewEnvironment, binding: SettingsTopBarBinding) {
        val style = value.appearance.toolbars
        chrome.bind(style, style.backgroundColor(value.palette))
        content.bind(value, binding)
        backHint.bind(style, style.contentColor(value.palette), binding.backHint)
        requestLayout()
    }

    override fun onDetachedFromWindow() {
        backHint.clearPulse()
        super.onDetachedFromWindow()
    }

    override fun close() {
        backHint.close()
        cancelInput()
    }

    fun setRemainingWindowInsets(value: NativeTopBarInsets) {
        require(value.left >= 0 && value.top >= 0 && value.right >= 0)
        if (value == insets) return
        insets = value
        requestLayout()
    }

    /** Include this exact viewport layer in the window's shared text/semantics clip policy. */
    fun sourceClip(view: View): NativeTextSemanticsClip? = when (view) {
        content -> NativeTextSemanticsClip.Rectangle(ComposeRect(0f, 0f, content.width.toFloat(), content.height.toFloat()))
        this -> NativeTextSemanticsClip.Unclipped
        else -> navigation.sourceClip(view) ?: done.sourceClip(view)
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
        environment: ReverbViewEnvironment,
        inputRoot: () -> View?,
        val navigation: ReverbTopBarIconAction = ReverbTopBarIconAction(context, environment, inputRoot,
            ReverbIcon.Back, R.string.onboarding_back, selectionWrapper = false, rolePresent = true),
        val title: ParagraphView = environment.paragraph(context, context.getString(R.string.settings_title),
            environment.appearance.toolbars.centeredTitleRole, environment.appearance.toolbars.contentColor(environment.palette)),
        val done: ReverbTopBarIconAction = ReverbTopBarIconAction(context, environment, inputRoot,
            ReverbIcon.Check, R.string.done, selectionWrapper = false, rolePresent = true),
    ) : NativeCenteredTopBarLayout(context, environment.textDensity.density, navigation, title, done,
        environment.appearance.toolbars, useCompactActions = true) {
        fun bind(value: ReverbViewEnvironment, state: SettingsTopBarBinding) {
            val style = value.appearance.toolbars
            bindStyle(value.textDensity.density, style)
            value.bindParagraphStyle(title, style.centeredTitleRole, style.contentColor(value.palette))
            title.setText(context.getString(R.string.settings_title))
            navigation.bindIcon(if (state.hasUnsavedChanges) ReverbIcon.Undo else ReverbIcon.Back,
                context.getString(if (state.hasUnsavedChanges) R.string.undo else R.string.onboarding_back))
            navigation.bind(value, true, NativeToolbarActionTone.MUTED, state.onNavigation)
            done.bind(value, state.doneEnabled, NativeToolbarActionTone.MUTED, state.onDone)
        }
    }

    private companion object {
        fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
    }
}
