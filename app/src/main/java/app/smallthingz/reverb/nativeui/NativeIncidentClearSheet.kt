package app.smallthingz.reverb.nativeui

import android.view.View
import app.smallthingz.reverb.R
import smallthingz.ui.util.closePreservingPrimaryFailure

internal class NativeIncidentClearSheet(
    anchor: View,
    environment: ReverbViewEnvironment,
    onDismiss: () -> Unit,
    private val onConfirm: () -> Unit,
) : AutoCloseable {
    private val context = anchor.context
    private val title = context.getString(R.string.incidents_clear_all)
    private val dialog = createReverbActionSheet(anchor, environment, title, onDismiss)
    private val host = dialog.contentContext
    private val body = environment.paragraph(context, context.getString(R.string.incidents_clear_all_detail),
        ReverbTextRole.BODY_LARGE, environment.palette.onSurface)
    private val confirm = LibrarySheetButton(context, host, environment, true, title, onConfirm, errorColors = true)
    private val actions = LibrarySheetActions(context, host, environment, listOf(
        LibrarySheetButton(context, host, environment, false, context.getString(R.string.cancel), onDismiss),
        confirm,
    ))
    private val text = body.bindPlainTextAccessibility(host.accessibility.cursor, host.accessibility.geometry,
        { host.accessibility.sourceNode(body) }) { host.accessibility.sourceNode(body).present }
    private var closed = false

    init { dialog.setContent(body, actions, listOf(actions.hitLayout, host.hit(body, emptyList()))) }

    fun show() = dialog.show()

    fun setEnabled(enabled: Boolean) = confirm.bind(title, enabled, onConfirm)

    fun bindEnvironment(value: ReverbViewEnvironment) {
        dialog.bindEnvironment(value)
        value.bindParagraphStyle(body, ReverbTextRole.BODY_LARGE, value.palette.onSurface)
        actions.bindEnvironment(value)
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure = closePreservingPrimaryFailure(null, dialog::dismiss)
        failure = closePreservingPrimaryFailure(failure, actions::close)
        failure = closePreservingPrimaryFailure(failure, text::close)
        failure?.let { throw it }
    }
}
