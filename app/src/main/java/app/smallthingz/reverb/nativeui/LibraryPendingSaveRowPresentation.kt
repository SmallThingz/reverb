package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.safeReportedErrorDetail

import android.content.Context
import android.view.View
import smallthingz.ui.animation.smoothProgressSpring
import smallthingz.ui.animation.view.ViewSpringDriver
import smallthingz.ui.animation.core.AnimationVector1D
import app.smallthingz.reverb.PendingSave
import app.smallthingz.reverb.R
import app.smallthingz.reverb.SavePhase
import app.smallthingz.reverb.formatRecordingStartTimestamp
import app.smallthingz.reverb.formatSavedRecordingDuration
import kotlinx.coroutines.CoroutineScope
import smallthingz.ui.runtime.MonotonicFrameClock

/** Presentation only: a job uses the normal row without acquiring recording actions or authority. */
internal class LibraryPendingSaveRowPresentation(
    private val context: Context,
    environment: ReverbViewEnvironment,
    scope: CoroutineScope,
    inputRoot: View,
) : AutoCloseable {
    val row = ReverbLibraryRecordingRow(context, environment, scope, inputRoot)
    private val progress = ReverbIncidentLoadingView(context, environment.palette.primary,
        checkNotNull(scope.coroutineContext[MonotonicFrameClock]), scope,
        { reverbAnimatorDurationScale(context) }, strokeWidthDp = 2.5f)
    private val noActions = LibraryRecordingRowActions(null, null, null)
    private val fill = smoothProgressSpring()
    private var driver: ViewSpringDriver<Float, AnimationVector1D>? = null
    private var identity: String? = null
    private var target = 0f
    private var active = false
    private var color = environment.palette.primary

    init {
        row.onPresentationVisibilityChanged = { updateMotion() }
        row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { updateMotion() }
            override fun onViewDetachedFromWindow(view: View) {
                stopMotion()
                fill.snapTo(target)
                paint()
            }
        })
    }

    private fun paint() = row.summarySurface.setBackgroundProgress(fill.value, (color and 0x00ffffff) or 0x22000000)
    private fun stopMotion() { driver?.close(); driver = null }
    private fun updateMotion() {
        if (!active || !row.isAttachedToWindow || !row.isShown || row.windowVisibility != View.VISIBLE) {
            stopMotion()
            fill.snapTo(target)
            paint()
            return
        }
        val motion = driver ?: ViewSpringDriver(row, fill, { paint() }).also { driver = it }
        motion.retarget(target)
    }

    fun bind(save: PendingSave, environment: ReverbViewEnvironment, edges: ReverbRecordingGroupEdges? = null) {
        row.setEnvironment(environment)
        progress.setColor(environment.palette.primary)
        row.setSummarySlots(if (save.phase == SavePhase.FAILED) null else progress, null)
        row.bind(pendingSaveRecordingRow(context, save, edges), noActions)
        color = environment.palette.primary
        if (identity != save.id) {
            stopMotion()
            identity = save.id
            target = 0f
            fill.snapTo(0f)
        }
        target = pendingSaveProgressTarget(target, save.progress, save.phase == SavePhase.FAILED)
        updateMotion()
        paint()
    }

    fun setPresentationActive(active: Boolean) {
        this.active = active
        progress.setPresentationActive(active)
        updateMotion()
    }

    override fun close() {
        active = false
        stopMotion()
        identity = null
        target = 0f
        fill.snapTo(0f)
        paint()
        // Removing the spinner also retires its frame job and platform scale observer.
        row.setSummarySlots(null, null)
        row.unbind()
    }
}

internal fun pendingSaveRecordingRow(
    context: Context,
    save: PendingSave,
    edges: ReverbRecordingGroupEdges? = null,
): LibraryRecordingRow {
    val phase = context.getString(when (save.phase) {
        SavePhase.QUEUED -> R.string.save_job_queued
        SavePhase.SAVING -> R.string.save_job_materializing
        SavePhase.RECOVERING -> R.string.save_job_recovering
        SavePhase.FAILED -> R.string.save_job_failed
    })
    val subtitle = listOf(phase, safeReportedErrorDetail(save.error, 512).orEmpty(), formatSavedRecordingDuration(context, save.durationMillis),
        save.codecSummary).filter { it.isNotBlank() }.joinToString(" \u2022 ")
    return LibraryRecordingRow(
        recordingId = "save-job:" + save.id,
        title = save.displayName,
        subtitle = subtitle,
        timestamp = formatRecordingStartTimestamp(context, save.startedAtMillis),
        selected = false,
        selectionActive = false,
        interactionsEnabled = false,
        edges = edges,
    )
}
