package app.smallthingz.reverb.nativeui

import smallthingz.ui.text.view.ParagraphView
import android.content.Context
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.smallthingz.reverb.NativeQuickSettingsTilesOwner
import app.smallthingz.reverb.QuickSettingsTileDeleteTarget
import app.smallthingz.reverb.R
import smallthingz.ui.components.NativeTrailingActionPair

/** Actual tile-delete sheet content, retaining one target identity and accepted owner mutation. */
internal class QuickTileDeleteContent(
    context: Context,
    private val owner: NativeQuickSettingsTilesOwner,
    val target: QuickSettingsTileDeleteTarget,
    private var environment: ReverbViewEnvironment,
    inputRoot: () -> View?,
    private val sheetLifecycle: Lifecycle,
    private val sheetView: () -> View,
    private val hasWindowFocus: () -> Boolean,
) : AutoCloseable {
    // Text() inherits MaterialTheme's bodyLarge; the source does not specify bodyMedium here.
    val body = ParagraphView(context, environment.appearance.typography.defaultTextStyle, environment.textDensity,
        environment.sharedFontResolver, environment.palette.onSurface).also {
        it.setText(context.getString(R.string.tiles_ui_delete_confirmation))
    }
    private val cancel = SettingsCalibrationActionView(context, environment, inputRoot) {
        owner.dismissDelete()
    }
    private val confirm = SettingsCalibrationActionView(context, environment, inputRoot) {
        val view = sheetView()
        owner.confirmDelete(target, sheetLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            view.isAttachedToWindow && view.isShown && hasWindowFocus())
    }
    val actions = NativeTrailingActionPair(context, cancel, confirm) { bind(environment) }
    private var closed = false
    private val lifecycleObserver = LifecycleEventObserver { _, _ -> bind(environment) }
    init {
        sheetLifecycle.addObserver(lifecycleObserver)
        bind(environment)
    }
    fun bind(next: ReverbViewEnvironment) {
        if (closed) return
        environment = next
        body.setTypography(next.appearance.typography.defaultTextStyle, next.textDensity, next.sharedFontResolver)
        body.animateTextColor(next.palette.onSurface)
        cancel.bind(body.context.getString(R.string.cancel), !owner.editorState.deletingBusy, next)
        confirm.bind(body.context.getString(R.string.delete_recording),
            owner.canConfirmDelete(target, hasWindowFocus() &&
                sheetLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)), next)
        actions.requestLayout()
    }
    override fun close() {
        if (closed) return
        closed = true
        sheetLifecycle.removeObserver(lifecycleObserver)
        cancel.close()
        confirm.close()
    }
}
