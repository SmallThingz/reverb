package app.smallthingz.reverb

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import app.smallthingz.reverb.nativeui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import smallthingz.ui.runtime.MonotonicFrameClock

/** Actual row presentation with inert metadata; never constructs a repository or audio owner. */
internal fun createNativeThemeLibraryFixture(
    context: Context,
    environment: ReverbViewEnvironment,
    scope: CoroutineScope,
    inputRoot: View,
): NativeThemeLibraryFixture = NativeThemeLibraryFixture(context, environment, scope, inputRoot)

internal class NativeThemeLibraryFixture(
    private val context: Context,
    private val environment: ReverbViewEnvironment,
    private val scope: CoroutineScope,
    private val inputRoot: View,
) : AutoCloseable {
    private val pending = ArrayList<LibraryPendingSaveRowPresentation>()
    private val completed = ReverbLibraryRecordingRow(context, environment, scope, inputRoot)
    private val labels = linkedSetOf<String>()
    val contrastLabels: Set<String> get() = labels
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val inset = (16 * environment.textDensity.density).toInt()
        setPadding(inset, inset, inset, inset)
        setBackgroundColor(environment.palette.surface)
    }

    init {
        val completedModel = LibraryRecordingRow("qa-finished", "Finished recording",
            "1:20 \u2022 8.4 MB", "10:30", false, true, true,
            ReverbRecordingGroupEdges(first = true, last = true))
        val forbidden: (String) -> Unit = { error("Theme fixture cannot perform recording actions") }
        completed.bind(completedModel, LibraryRecordingRowActions(forbidden, forbidden, forbidden))
        labels += completedModel.subtitle
        view.addView(completed, LinearLayout.LayoutParams(-1, -2))
        val jobs = listOf(
            PendingSave("qa-queued", "Queued recording", 1_700_000_000_000L, 80_000L, SavePhase.QUEUED, codecSummary = "FLAC"),
            PendingSave("qa-saving", "Materializing recording", 1_700_000_000_000L, 80_000L, SavePhase.SAVING, .42f, codecSummary = "FLAC"),
            PendingSave("qa-recovering", "Resuming recording", 1_700_000_000_000L, 80_000L, SavePhase.RECOVERING, codecSummary = "WAV"),
            PendingSave("qa-failed", "Failed recording", 1_700_000_000_000L, 80_000L, SavePhase.FAILED,
                error = "Storage unavailable", codecSummary = "FLAC"),
        )
        jobs.forEachIndexed { index, job ->
            val edges = ReverbRecordingGroupEdges(first = index == 0, last = index == jobs.lastIndex)
            val model = pendingSaveRecordingRow(context, job, edges)
            check(model.recordingId == "save-job:" + job.id)
            check(!model.interactionsEnabled && !model.selected && !model.selectionActive && !model.expanded)
            labels += model.subtitle
            val presentation = LibraryPendingSaveRowPresentation(context, environment, scope, inputRoot)
            presentation.bind(job, environment, edges)
            pending += presentation
            view.addView(presentation.row, LinearLayout.LayoutParams(-1, -2))
        }
        // A recycled job row must remove its spinner on failure and restore it on a new bind.
        val recycled = pending[1]
        recycled.bind(jobs[1].copy(phase = SavePhase.FAILED), environment)
        check(descendants(recycled.row).none { it is ReverbIncidentLoadingView })
        recycled.close()
        recycled.bind(jobs[1], environment, ReverbRecordingGroupEdges(first = false, last = false))
        check(descendants(recycled.row).count { it is ReverbIncidentLoadingView } == 1)
        verify()
    }

    fun verify() {
        pending.forEach { presentation ->
            check(descendants(presentation.row).none { it.isClickable || it.isLongClickable })
        }
        check(descendants(completed).filterIsInstance<ReverbParagraphView>().all { paragraph ->
            var ancestor: View? = paragraph
            var opacity = 1f
            while (ancestor != null && ancestor !== view) {
                opacity *= ancestor.alpha
                ancestor = ancestor.parent as? View
            }
            opacity == 1f
        })
        if (view.isAttachedToWindow) {
            val presentation = pending.first()
            val row = presentation.row
            val spinner = descendants(row).filterIsInstance<ReverbIncidentLoadingView>().single()
            val jobField = ReverbIncidentLoadingView::class.java.getDeclaredField("frameJob").apply { isAccessible = true }
            fun running() = (jobField.get(spinner) as? Job)?.isActive == true
            val motionEnabled = reverbAnimatorDurationScale(view.context) != 0f
            check(running() == motionEnabled)
            presentation.setPresentationActive(false)
            check(!running())
            presentation.setPresentationActive(true)
            check(running() == motionEnabled)
            row.visibility = View.INVISIBLE
            check(!running())
            row.visibility = View.VISIBLE
            check(running() == motionEnabled)
            val index = view.indexOfChild(row)
            view.removeView(row)
            check(!spinner.isAttachedToWindow && !running())
            view.addView(row, index, LinearLayout.LayoutParams(-1, -2))
            check(running() == motionEnabled)
            if (completed.isLaidOut && row.isLaidOut) check(completed.height == row.height)
            verifyRetainedLoadingOwnership()
        }
    }

    private fun verifyRetainedLoadingOwnership() {
        val nested = object : NativeVerticalNestedHost {
            override fun preScroll(availableY: Float, source: NativeVerticalScrollSource) = 0f
            override fun postScroll(consumedY: Float, availableY: Float, source: NativeVerticalScrollSource) = 0f
            override suspend fun preFling(availableY: Float) = 0f
            override suspend fun postFling(consumedY: Float, availableY: Float) = 0f
        }
        var motionScale = 1f
        val status = ReverbLibraryStatusView(context, environment,
            checkNotNull(scope.coroutineContext[MonotonicFrameClock]), scope, nested, inputRoot) { motionScale }
        status.alpha = .01f
        status.translationY = view.height.toFloat()
        status.setPresentationActive(false)
        status.bind(LibraryBodyMode.EMPTY) { error("Loading fixture cannot refresh storage") }
        view.addView(status, LinearLayout.LayoutParams(-1, (64 * environment.textDensity.density).toInt()))
        try {
            // Closing an empty Library changes its retained branch to LOADING while its View stays shown.
            status.bind(LibraryBodyMode.LOADING) { error("Loading fixture cannot refresh storage") }
            val spinner = descendants(status).filterIsInstance<ReverbIncidentLoadingView>().single()
            val type = ReverbIncidentLoadingView::class.java
            val job = type.getDeclaredField("frameJob").apply { isAccessible = true }
            val rotation = type.getDeclaredField("rotation").apply { isAccessible = true }
            val progress = type.getDeclaredField("progress").apply { isAccessible = true }
            fun running() = (job.get(spinner) as? Job)?.isActive == true
            check(status.isAttachedToWindow && spinner.isShown && status.visibility == View.VISIBLE)
            check(!running())
            status.setPresentationActive(true)
            check(running())
            // Pausing preserves the current painted pose, rather than substituting reduced-motion geometry.
            rotation.setFloat(spinner, 73f)
            progress.setFloat(spinner, .39f)
            status.setPresentationActive(false)
            check(!running() && rotation.getFloat(spinner) == 73f && progress.getFloat(spinner) == .39f)
            motionScale = 0f
            spinner.motionScaleChanged()
            check(!running() && rotation.getFloat(spinner) == 73f && progress.getFloat(spinner) == .39f)
            status.setPresentationActive(true)
            check(!running() && rotation.getFloat(spinner) == 1440f && progress.getFloat(spinner) == .87f)
            motionScale = 1f
            spinner.motionScaleChanged()
            check(running())
            status.setPresentationActive(false)
            check(!running() && status.alpha == .01f && status.translationY == view.height.toFloat())
            view.removeView(status)
            check(!spinner.isAttachedToWindow && !running())
        } finally {
            view.removeView(status)
        }
    }

    override fun close() {
        pending.forEach { it.close() }
        completed.unbind()
        view.removeAllViews()
    }
}

private fun descendants(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
}
