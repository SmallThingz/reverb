package app.smallthingz.reverb.nativeui

import android.content.Context
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.smallthingz.reverb.NativeQuickSettingsTilesOwner
import app.smallthingz.reverb.QuickSettingsTileDeleteTarget
import app.smallthingz.reverb.R
import smallthingz.ui.reverb.ReverbTileDeleteSheetContent

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
    private val presentation = ReverbTileDeleteSheetContent(context, environment, inputRoot,
        context.getString(R.string.tiles_ui_delete_confirmation), context.getString(R.string.cancel),
        context.getString(R.string.delete_recording), { owner.dismissDelete(target) }) {
        val view = sheetView()
        owner.confirmDelete(target, sheetLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            view.isAttachedToWindow && view.isShown && hasWindowFocus())
    }
    val body get() = presentation.body
    val actions get() = presentation.actions
    private var closed = false
    private val lifecycleObserver = LifecycleEventObserver { _, _ -> bind(environment) }
    init {
        sheetLifecycle.addObserver(lifecycleObserver)
        bind(environment)
    }
    fun bind(next: ReverbViewEnvironment) {
        if (closed) return
        environment = next
        presentation.bind(next, !owner.editorState.deletingBusy, owner.canConfirmDelete(target,
            hasWindowFocus() && sheetLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)))
    }
    override fun close() {
        if (closed) return
        closed = true
        sheetLifecycle.removeObserver(lifecycleObserver)
        presentation.close()
    }
}
