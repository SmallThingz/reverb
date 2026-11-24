package app.smallthingz.reverb.nativeui

import android.view.View
import app.smallthingz.reverb.CaptureSaveStatus
import app.smallthingz.reverb.NativeCaptureUiOwner
import app.smallthingz.reverb.NativeCaptureUiState
import app.smallthingz.reverb.formatRecordingStartTimestamp
import app.smallthingz.reverb.formatSavedRecordingDuration
import app.smallthingz.reverb.formatShortFileSize
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.input.phase.NativeViewPointerHitLayout
import smallthingz.ui.reverb.home.ReverbCaptureSaveStatus
import smallthingz.ui.reverb.home.ReverbCaptureSaveStatusView

internal class NativeCaptureTransientHost(
    anchor: View,
    initialEnvironment: ReverbViewEnvironment,
    owner: NativeCaptureUiOwner,
    host: NativeMaterialButtonHost,
    inheritedScope: CoroutineScope,
) : AutoCloseable {
    private val context = anchor.context
    private val sheets = NativeCaptureSheets(anchor, owner)
    private val status = ReverbCaptureSaveStatusView(context, initialEnvironment, host, inheritedScope, owner::cancelExport)
    val statusCard: View get() = status
    val statusHitLayout: NativeViewPointerHitLayout get() = status.hitLayout
    private var currentStatus: CaptureSaveStatus? = null
    private var presentation: ReverbCaptureSaveStatus? = null
    private var closed = false

    fun bind(state: NativeCaptureUiState, environment: ReverbViewEnvironment) {
        if (closed) return
        val value = state.saveStatus
        if (currentStatus != value) {
            presentation = when (value) {
                null -> null
                is CaptureSaveStatus.Saving -> ReverbCaptureSaveStatus.Saving(value.cancellable)
                is CaptureSaveStatus.Saved -> value.recording.let { recording ->
                    val duration = formatSavedRecordingDuration(context, recording.durationMillis)
                    val size = formatShortFileSize(recording.sizeBytes)
                    ReverbCaptureSaveStatus.Saved(recording.displayName, duration, size,
                        formatRecordingStartTimestamp(context, recording.startedAtMillis))
                }
            }
            currentStatus = value
        }
        status.bind(presentation, environment)
        sheets.bind(state, environment)
    }

    override fun close() {
        if (closed) return
        closed = true
        captureReleaseAll(listOf<() -> Unit>(sheets::close, status::close))
    }
}
