package app.smallthingz.reverb.nativeui

import smallthingz.ui.reverb.ReverbDesign
import smallthingz.ui.reverb.ReverbTitledTopBarLayout
import smallthingz.ui.components.NativeToolbarHint
import smallthingz.ui.components.NativeToolbarActionTone
import smallthingz.ui.text.view.NativePlainTextViewGeometry
import smallthingz.ui.text.view.NativeSourceAccessibilityNode
import smallthingz.ui.text.view.NativeTextSemanticsClip
import smallthingz.ui.text.view.ParagraphView
import smallthingz.ui.text.view.PlainTextAccessibilityWindowScope
import android.content.Context
import android.view.View
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
    val autoStartPermissionPending: Boolean = false,
) {
    val doneEnabled get() = hasUnsavedChanges && !settingsPersisting && !calibrationRunning &&
        !quickTileEditorOpen && !notificationDurationPending && !autoStartPermissionPending
}

/** Centered Settings title with the compact Reverb action slots and the existing input gate. */
internal class ReverbSettingsTopBarView private constructor(
    context: Context,
    initialEnvironment: ReverbViewEnvironment,
    animationScope: CoroutineScope,
    private val content: TopBarContent,
) : ReverbTitledTopBarLayout(context, initialEnvironment.textDensity.density, initialEnvironment.palette,
    content.navigation, content.title, content.done), AutoCloseable {
    constructor(context: Context, initialEnvironment: ReverbViewEnvironment, animationScope: CoroutineScope,
                inputRoot: () -> View?) :
        this(context, initialEnvironment, animationScope, TopBarContent(context, initialEnvironment, inputRoot))

    private val toolbarStyle = ReverbDesign.appearance.toolbars
    private val backHint = NativeToolbarHint(this, animationScope, toolbarStyle,
        toolbarStyle.contentColor(initialEnvironment.palette), content.navigation.visual, content.done.visual)
    val title: ParagraphView get() = content.title
    val navigation: ReverbTopBarIconAction get() = content.navigation
    val done: ReverbTopBarIconAction get() = content.done
    val touchShieldNode = object : NativeViewPhasePointerNode {
        override val view: View get() = this@ReverbSettingsTopBarView
        override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = Unit
        override fun cancel() = Unit
    }

    fun bind(value: ReverbViewEnvironment, binding: SettingsTopBarBinding) {
        bindTitledToolbar(value.textDensity.density, value.palette)
        content.bind(value, binding)
        backHint.bind(toolbarStyle, toolbarStyle.contentColor(value.palette), binding.backHint)
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

    /** Include this exact viewport layer in the window's shared text/semantics clip policy. */
    fun sourceClip(view: View): NativeTextSemanticsClip? = when (view) {
        contentClipView -> NativeTextSemanticsClip.Rectangle(ComposeRect(0f, 0f,
            contentClipView.width.toFloat(), contentClipView.height.toFloat()))
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

    fun cancelInput() { navigation.cancelInput(); done.cancelInput() }

    private class TopBarContent(
        private val context: Context,
        environment: ReverbViewEnvironment,
        inputRoot: () -> View?,
        val navigation: ReverbTopBarIconAction = ReverbTopBarIconAction(context, environment, inputRoot,
            ReverbIcon.Back, R.string.onboarding_back, selectionWrapper = false, rolePresent = true),
        val title: ParagraphView = environment.paragraph(context, context.getString(R.string.settings_title),
            ReverbDesign.appearance.toolbars.centeredTitleRole, ReverbDesign.appearance.toolbars.contentColor(environment.palette)),
        val done: ReverbTopBarIconAction = ReverbTopBarIconAction(context, environment, inputRoot,
            ReverbIcon.Check, R.string.done, selectionWrapper = false, rolePresent = true),
    ) {
        fun bind(value: ReverbViewEnvironment, state: SettingsTopBarBinding) {
            val style = ReverbDesign.appearance.toolbars
            value.bindParagraphStyle(title, style.centeredTitleRole, style.contentColor(value.palette))
            title.setText(context.getString(R.string.settings_title))
            navigation.bindIcon(if (state.hasUnsavedChanges) ReverbIcon.Undo else ReverbIcon.Back,
                context.getString(if (state.hasUnsavedChanges) R.string.undo else R.string.onboarding_back))
            navigation.bind(value, true, NativeToolbarActionTone.MUTED, state.onNavigation)
            done.bind(value, state.doneEnabled, NativeToolbarActionTone.MUTED, state.onDone)
        }
    }
}
