package app.smallthingz.reverb.nativeui

import android.view.View
import android.view.ViewGroup
import app.smallthingz.reverb.BufferClearPhase
import app.smallthingz.reverb.BufferClearStatus
import app.smallthingz.reverb.NativeCaptureUiOwner
import app.smallthingz.reverb.NativeCaptureUiState
import app.smallthingz.reverb.R
import app.smallthingz.reverb.ReverbService
import app.smallthingz.reverb.bufferClearProgressFraction
import app.smallthingz.reverb.formatShortFileSize
import app.smallthingz.reverb.formatShortTimer
import kotlin.math.max
import kotlin.math.roundToInt

/** CaptureScreen's four independent sheet branches. Accepted work remains in the Capture owner. */
internal class NativeCaptureSheets(
    private val anchor: View,
    private val owner: NativeCaptureUiOwner,
) : AutoCloseable {
    private var clear: CaptureTextSheet? = null
    private var progress: CaptureClearProgressSheet? = null
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
                clear = CaptureTextSheet(anchor, next, title, message, owner::dismissClearBuffer) { context ->
                    listOf(
                        LibrarySheetButton(anchor.context, context, false,
                            anchor.context.getString(R.string.cancel), owner::dismissClearBuffer),
                        LibrarySheetButton(anchor.context, context, true,
                            anchor.context.getString(R.string.clear_buffer), owner::confirmClearBuffer,
                            leadingIcon = ReverbIcon.Delete, errorColors = true),
                    )
                }.also { it.show() }
            } else existing.bind(title, message, next, owner::dismissClearBuffer)
        }
        val currentProgress = state.clearStatus
        if (currentProgress == null) { progress?.close(); progress = null } else {
            val existing = progress
            if (existing == null) {
                progress = CaptureClearProgressSheet(anchor, next, currentProgress, owner::cancelClearBuffer)
                    .also { it.show() }
            } else existing.bind(currentProgress, next)
        }
        if (!state.showExportClamp) { clamp?.close(); clamp = null } else {
            val title = anchor.context.getString(R.string.export_limit_dialog_title)
            val message = anchor.context.getString(R.string.export_limit_dialog_message,
                formatShortTimer(state.clampWarningSeconds))
            val existing = clamp
            if (existing == null) {
                clamp = CaptureTextSheet(anchor, next, title, message, owner::dismissExportClamp) { context ->
                    listOf(
                        LibrarySheetButton(anchor.context, context, false,
                            anchor.context.getString(R.string.cancel), owner::dismissExportClamp),
                        LibrarySheetButton(anchor.context, context, true,
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
                    listOf(LibrarySheetButton(anchor.context, context, true,
                        anchor.context.getString(R.string.close), owner::dismissError))
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

private class CaptureTextSheet(
    anchor: View,
    environment: ReverbViewEnvironment,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    buttons: (NativeActionSheetContentContext) -> List<LibrarySheetButton>,
) : AutoCloseable {
    private val dialog = ReverbActionSheetDialog(anchor, environment, title, onDismiss)
    private val body = CaptureSheetText(dialog.contentContext, message)
    private val actions = LibrarySheetActions(anchor.context, dialog.contentContext, buttons(dialog.contentContext))
    private var closed = false
    init {
        dialog.setContent(body, actions, listOf(actions.hitLayout))
    }
    fun show() = dialog.show()
    fun bind(title: String, message: String, next: ReverbViewEnvironment, onDismiss: () -> Unit) {
        if (closed) return
        dialog.bindEnvironment(next)
        dialog.bind(title, onDismiss)
        body.bind(message, next)
        actions.bindEnvironment(next)
    }
    override fun close() {
        if (closed) return
        closed = true
        captureReleaseAll(listOf<() -> Unit>(dialog::dismiss, actions::close, body::close))
    }
}

private class CaptureSheetText(
    private val host: NativeActionSheetContentContext,
    message: String,
) : ViewGroup(host.inputRoot.context), AutoCloseable {
    private val text = host.environment.paragraph(context, message, ReverbTextRole.BODY_MEDIUM,
        host.environment.palette.onSurfaceVariant)
    private val lease = text.bindPlainTextAccessibility(host.accessibility.cursor, host.accessibility.geometry,
        { host.accessibility.sourceNode(text) }) { host.accessibility.sourceNode(text).present }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; addView(text) }
    fun bind(message: String, next: ReverbViewEnvironment) {
        next.bindParagraphStyle(text, ReverbTextRole.BODY_MEDIUM, next.palette.onSurfaceVariant)
        text.setText(message)
    }
    override fun onMeasure(w: Int, h: Int) {
        text.measure(captureAtMost(MeasureSpec.getSize(w)), h)
        setMeasuredDimension(resolveSize(text.measuredWidth, w), resolveSize(text.measuredHeight, h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - text.measuredWidth else 0
        text.layout(x, 0, x + text.measuredWidth, text.measuredHeight)
    }
    override fun close() = lease.close()
}

private class CaptureClearProgressSheet(
    anchor: View,
    environment: ReverbViewEnvironment,
    initial: BufferClearStatus,
    private val onCancel: () -> Unit,
) : AutoCloseable {
    private val dialog = ReverbActionSheetDialog(anchor, environment, clearProgressTitle(anchor, initial)) {}
    private val body = CaptureClearProgressBody(dialog.contentContext, initial)
    private val cancel = LibrarySheetButton(anchor.context, dialog.contentContext, false,
        anchor.context.getString(R.string.cancel), onCancel)
    private val actions = LibrarySheetActions(anchor.context, dialog.contentContext, listOf(cancel))
    private var closed = false
    init {
        dialog.bindDismissalAdmission { false }
        dialog.setContent(body, actions, listOf(actions.hitLayout))
        bind(initial, environment)
    }
    fun show() = dialog.show()
    fun bind(status: BufferClearStatus, next: ReverbViewEnvironment) {
        if (closed) return
        dialog.bindEnvironment(next)
        dialog.bind(clearProgressTitle(dialog.contentContext.inputRoot, status)) {}
        body.bind(status, next)
        actions.bindEnvironment(next)
        val cancelling = status.phase == BufferClearPhase.CANCELLING
        cancel.bind(dialog.context.getString(if (cancelling) R.string.clear_buffer_cancelling else R.string.cancel),
            !cancelling, onCancel)
    }
    override fun close() {
        if (closed) return
        closed = true
        captureReleaseAll(listOf<() -> Unit>(dialog::dismiss, actions::close, body::close))
    }
}

private fun clearProgressTitle(view: View, status: BufferClearStatus) =
    view.context.getString(if (status.bufferSlot == ReverbService.BufferSlot.ONE_SHOT)
        R.string.clear_one_shot_progress_title else R.string.clear_loop_progress_title)

/** Source Column: progress,12dp,bodyMedium detail,8dp,bodySmall cancellation note. */
private class CaptureClearProgressBody(
    private val host: NativeActionSheetContentContext,
    initial: BufferClearStatus,
) : ViewGroup(host.inputRoot.context), AutoCloseable {
    private var environment = host.environment
    private var progress: View? = null
    private var progressLease: AutoCloseable? = null
    private val detail = environment.paragraph(context, "", ReverbTextRole.BODY_MEDIUM,
        environment.palette.onSurfaceVariant)
    private val note = environment.paragraph(context, context.getString(R.string.clear_buffer_cancel_note),
        ReverbTextRole.BODY_SMALL, environment.palette.onSurfaceVariant)
    private val textLeases = listOf(detail, note).map { label ->
        label.bindPlainTextAccessibility(host.accessibility.cursor, host.accessibility.geometry,
            { host.accessibility.sourceNode(label) }) { host.accessibility.sourceNode(label).present }
    }
    private var firstGap = 0
    private var secondGap = 0
    private var closed = false
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false; clipToPadding = false
        addView(detail); addView(note)
        bind(initial, environment)
    }
    fun bind(status: BufferClearStatus, next: ReverbViewEnvironment) {
        if (closed) return
        environment = next
        val fraction = bufferClearProgressFraction(status)
        val wantDeterminate = fraction != null
        val current = progress
        if (current == null || (current is SettingsCalibrationProgressView) != wantDeterminate) {
            progressLease?.close(); progressLease = null
            current?.let(::removeView)
            val replacement = if (wantDeterminate) SettingsCalibrationProgressView(context, next)
                else ReverbIndeterminateLinearProgressView(context, next, host.runtime.frames,
                    host.runtime.scope, { reverbAnimatorDurationScale(context) })
            progress = replacement
            addView(replacement, 0)
            progressLease = when (replacement) {
                is SettingsCalibrationProgressView -> replacement.bindAccessibility(host.accessibility.geometry) {
                    host.accessibility.sourceNode(replacement).present
                }
                is ReverbIndeterminateLinearProgressView -> replacement.bindAccessibility(host.accessibility.geometry) {
                    host.accessibility.sourceNode(replacement).present
                }
                else -> error("Unexpected progress branch")
            }
        }
        when (val value = progress) {
            is SettingsCalibrationProgressView -> value.bind(checkNotNull(fraction), next)
            is ReverbIndeterminateLinearProgressView -> value.bindEnvironment(next)
        }
        val message = if (status.totalBytes > 0L) context.getString(R.string.clear_buffer_progress_detail,
            (((fraction ?: 0f) * 100f).toInt()).coerceIn(0, 100), formatShortFileSize(status.remainingBytes))
            else context.getString(R.string.clear_buffer_progress_starting)
        next.bindParagraphStyle(detail, ReverbTextRole.BODY_MEDIUM, next.palette.onSurfaceVariant)
        next.bindParagraphStyle(note, ReverbTextRole.BODY_SMALL, next.palette.onSurfaceVariant)
        detail.setText(message)
        note.setText(context.getString(R.string.clear_buffer_cancel_note))
        // Text, branch membership and density request layout only when their geometry changes.
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val bounded = MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED
        val maximum = if (bounded) MeasureSpec.getSize(h) else Int.MAX_VALUE
        val indicator = checkNotNull(progress)
        indicator.measure(captureExact(width), if (bounded) captureAtMost(maximum) else h)
        var used = indicator.measuredHeight
        firstGap = minOf(dp(12), max(0, maximum - used)); used += firstGap
        detail.measure(captureAtMost(width), if (bounded) captureAtMost(max(0, maximum - used)) else h)
        used += detail.measuredHeight
        secondGap = minOf(dp(8), max(0, maximum - used)); used += secondGap
        note.measure(captureAtMost(width), if (bounded) captureAtMost(max(0, maximum - used)) else h)
        used += note.measuredHeight
        setMeasuredDimension(resolveSize(width, w), resolveSize(used, h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val indicator = checkNotNull(progress)
        indicator.layout(0, 0, indicator.measuredWidth, indicator.measuredHeight)
        fun place(view: View, top: Int) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - view.measuredWidth else 0
            view.layout(x, top, x + view.measuredWidth, top + view.measuredHeight)
        }
        val detailTop = indicator.measuredHeight + firstGap
        place(detail, detailTop)
        place(note, detailTop + detail.measuredHeight + secondGap)
    }
    override fun close() {
        if (closed) return
        closed = true
        val lease = progressLease; progressLease = null
        progress?.let(::removeView); progress = null
        captureReleaseAll(listOf<() -> Unit>({ lease?.close() }) + textLeases.asReversed().map { value -> { value.close() } })
    }
    private fun dp(value: Int) = (value * environment.textDensity.density).roundToInt()
}

internal fun captureReleaseAll(blocks: List<() -> Unit>) {
    var failure: Throwable? = null
    for (block in blocks) try { block() } catch (error: Throwable) {
        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
    }
    failure?.let { throw it }
}
private fun captureExact(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
private fun captureAtMost(value: Int) = View.MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), View.MeasureSpec.AT_MOST)
