package app.smallthingz.reverb.nativeui

import smallthingz.ui.components.NativeActionSheetContentContext

import android.view.View
import app.smallthingz.reverb.BufferClearPhase
import app.smallthingz.reverb.BufferClearStatus
import app.smallthingz.reverb.NativeCaptureUiOwner
import app.smallthingz.reverb.NativeCaptureUiState
import app.smallthingz.reverb.R
import app.smallthingz.reverb.ReverbService
import app.smallthingz.reverb.bufferClearProgressFraction
import app.smallthingz.reverb.formatShortFileSize
import app.smallthingz.reverb.formatShortTimer
import smallthingz.ui.reverb.ReverbClearProgressPresentation
import smallthingz.ui.reverb.ReverbClearProgressSheet
import smallthingz.ui.reverb.ReverbTextSheet

/** CaptureScreen's four independent sheet branches. Accepted work remains in the Capture owner. */
internal class NativeCaptureSheets(
    private val anchor: View,
    private val owner: NativeCaptureUiOwner,
) : AutoCloseable {
    private var clear: CaptureTextSheet? = null
    private var progress: ReverbClearProgressSheet? = null
    private var clamp: CaptureTextSheet? = null
    private var error: CaptureTextSheet? = null
    private var closed = false

    fun bind(state: NativeCaptureUiState, next: ReverbViewEnvironment) {
        if (closed) return
        // Preserve the released branch/window ordering; none of these dismissals cancel accepted work.
        val slot = state.pendingClearBuffer
        if (slot == null) { clear?.close(); clear = null } else {
            val title = anchor.context.getString(if (slot == ReverbService.BufferSlot.ONE_SHOT)
                R.string.clear_one_shot_title else R.string.clear_loop_title)
            val message = anchor.context.getString(R.string.clear_buffer_confirmation)
            val existing = clear
            if (existing == null) {
                clear = createDiscardBufferSheet(anchor, next, title, message,
                    owner::dismissClearBuffer, owner::confirmClearBuffer).also { it.show() }
            } else existing.bind(title, message, next, owner::dismissClearBuffer)
        }
        val currentProgress = state.clearStatus
        if (currentProgress == null) { progress?.close(); progress = null } else {
            val existing = progress
            if (existing == null) {
                progress = ReverbClearProgressSheet(anchor, next.theme, currentProgress.presentation(anchor.context), owner::cancelClearBuffer)
                    .also { it.show() }
            } else existing.bind(currentProgress.presentation(anchor.context), next.theme)
        }
        if (!state.showExportClamp) { clamp?.close(); clamp = null } else {
            val title = anchor.context.getString(R.string.export_limit_dialog_title)
            val message = anchor.context.getString(R.string.export_limit_dialog_message,
                formatShortTimer(state.clampWarningSeconds))
            val existing = clamp
            if (existing == null) {
                clamp = CaptureTextSheet(anchor, next, title, message, owner::dismissExportClamp) { context ->
                    listOf(
                        LibrarySheetButton(anchor.context, context, next, false,
                            anchor.context.getString(R.string.cancel), context.requestAnimatedDismiss, leadingIcon = ReverbIcon.Close),
                        LibrarySheetButton(anchor.context, context, next, true,
                            anchor.context.getString(R.string.export), owner::proceedExportClamp),
                    )
                }.also { it.show() }
            } else existing.bind(title, message, next, owner::dismissExportClamp)
        }
        val message = state.errorMessage
        if (message == null) { error?.close(); error = null } else {
            val title = anchor.context.getString(R.string.error)
            val existing = error
            if (existing == null) {
                error = CaptureTextSheet(anchor, next, title, message, owner::dismissError) { context ->
                    listOf(LibrarySheetButton(anchor.context, context, next, true,
                        anchor.context.getString(R.string.close), context.requestAnimatedDismiss))
                }.also { it.show() }
            } else existing.bind(title, message, next, owner::dismissError)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val owned: List<AutoCloseable> = listOfNotNull(error, clamp, progress, clear)
        error = null; clamp = null; progress = null; clear = null
        captureReleaseAll(owned.map { value -> { value.close() } })
    }
}

internal fun createDiscardBufferSheet(
    anchor: View,
    environment: ReverbViewEnvironment,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
): CaptureTextSheet = CaptureTextSheet(anchor, environment, title, message, onDismiss) { context ->
    listOf(
        LibrarySheetButton(anchor.context, context, environment, false,
            anchor.context.getString(R.string.cancel), context.requestAnimatedDismiss, leadingIcon = ReverbIcon.Close),
        LibrarySheetButton(anchor.context, context, environment, true,
            anchor.context.getString(R.string.clear_buffer), onConfirm,
            leadingIcon = ReverbIcon.Delete, errorColors = true),
    )
}.apply { guardActionsDuringDismissal() }

internal typealias CaptureTextSheet = ReverbTextSheet

internal fun CaptureTextSheet(
    anchor: View,
    environment: ReverbViewEnvironment,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    buttons: (NativeActionSheetContentContext) -> List<LibrarySheetButton>,
): CaptureTextSheet = ReverbTextSheet(anchor, environment.theme, title, message, onDismiss, buttons)

internal fun CaptureTextSheet.bind(title: String, message: String, next: ReverbViewEnvironment, onDismiss: () -> Unit) =
    bind(title, message, next.theme, onDismiss)

private fun BufferClearStatus.presentation(context: android.content.Context): ReverbClearProgressPresentation {
    val fraction = bufferClearProgressFraction(this)
    val cancelling = phase == BufferClearPhase.CANCELLING
    val detail = if (totalBytes > 0L) context.getString(R.string.clear_buffer_progress_detail,
        (((fraction ?: 0f) * 100f).toInt()).coerceIn(0, 100), formatShortFileSize(remainingBytes))
    else context.getString(R.string.clear_buffer_progress_starting)
    return ReverbClearProgressPresentation(
        title = context.getString(if (bufferSlot == ReverbService.BufferSlot.ONE_SHOT)
            R.string.clear_one_shot_progress_title else R.string.clear_loop_progress_title),
        detail = detail,
        note = context.getString(R.string.clear_buffer_cancel_note),
        cancelLabel = context.getString(if (cancelling) R.string.clear_buffer_cancelling else R.string.cancel),
        canCancel = !cancelling,
        fraction = fraction,
        operationId = operationId,
        isTerminal = phase.isTerminal,
    )
}

internal fun captureReleaseAll(blocks: List<() -> Unit>) {
    var failure: Throwable? = null
    for (block in blocks) try { block() } catch (error: Throwable) {
        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
    }
    failure?.let { throw it }
}
