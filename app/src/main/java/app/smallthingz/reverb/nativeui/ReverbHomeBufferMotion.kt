package app.smallthingz.reverb.nativeui

import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.tween
import app.smallthingz.reverb.ReverbService.BufferSlot
import app.smallthingz.reverb.bufferSwipeProgress
import app.smallthingz.reverb.bufferTransitionDepthScale
import app.smallthingz.reverb.bufferTransitionDisplayedSlot
import app.smallthingz.reverb.bufferTransitionFlipDegrees
import app.smallthingz.reverb.bufferTransitionPivotFractionX
import app.smallthingz.reverb.oppositeBufferSlot
import app.smallthingz.reverb.shouldCommitBufferSwipe

/**
 * MainCaptureContent's original finite buffer-navigation state, without a pointer detector/clock.
 * A host supplies source Main-pass drag deltas and frame times, and invokes its render callback.
 * The original pure math stays shared with the released source, including its physical LTR swipe
 * direction (there is intentionally no added RTL inversion not present in the released function).
 */
internal class ReverbHomeBufferMotion(initialSelection: BufferSlot) {
    var displayedBuffer = initialSelection
        private set
    private var observedSelection = initialSelection
    private var target: BufferSlot? = null
    private var pendingCommit: BufferSlot? = null
    private var dragging = false
    private var dragProgress = 0f
    private var dragPixels = 0f
    private var progressTarget = 0f
    private var saving = false
    private val progress = LibraryTweenValue(0f, Float.VectorConverter, tween<Float>(260),
        { AnimationVector1D(0f) })
    // A zero-duration retarget may finish inside target(), before the owner samples a frame.
    // Keep its terminal delivery scheduled until the non-dragging target has been retired.
    val running get() = progress.running || (!dragging && target != null)
    val renderedBuffer get() = target?.let {
        bufferTransitionDisplayedSlot(displayedBuffer, it, progress.value)
    } ?: displayedBuffer
    val flipFrame get() = ReverbHomeFlipFrame(
        degrees = target?.let { bufferTransitionFlipDegrees(displayedBuffer, it, progress.value) } ?: 0f,
        pivotFractionX = bufferTransitionPivotFractionX(),
        depthScale = if (target == null) 1f else bufferTransitionDepthScale(progress.value),
    )

    /** Source LaunchedEffect(selectedBuffer) restarts only when that external key changes. */
    fun bind(selectedBuffer: BufferSlot, isSaving: Boolean, durationScale: Float) {
        saving = isSaving
        if (observedSelection == selectedBuffer) return
        observedSelection = selectedBuffer
        if (selectedBuffer == displayedBuffer) return
        dragging = false
        dragProgress = 0f
        pendingCommit = null
        target = selectedBuffer
        animateTo(1f, durationScale)
    }

    fun requestSelection(next: BufferSlot, durationScale: Float) {
        if (saving || dragging || target != null || next == displayedBuffer) return
        target = next
        pendingCommit = next
        animateTo(1f, durationScale)
    }

    fun dragStarted() {
        dragPixels = 0f
        if (saving || target != null) return
        dragging = true
        dragProgress = 0f
        progressTarget = 0f
        pendingCommit = null
        target = oppositeBufferSlot(displayedBuffer)
        progress.snap(0f)
    }

    /** True means the source onHorizontalDrag consumes this change. */
    fun dragged(amount: Float, width: Float): Boolean {
        if (!dragging) return false
        dragPixels += amount
        dragProgress = bufferSwipeProgress(displayedBuffer, dragPixels, width)
        progress.snap(dragProgress)
        return true
    }

    fun dragEnded(durationScale: Float) {
        if (!dragging) return
        val destination = target
        val commit = destination != null && shouldCommitBufferSwipe(dragProgress)
        dragging = false
        if (commit) {
            pendingCommit = destination
            animateTo(1f, durationScale)
        } else if (dragProgress <= .001f) {
            pendingCommit = null
            target = null
            progressTarget = 0f
            progress.snap(0f)
        } else {
            pendingCommit = null
            animateTo(0f, durationScale)
        }
    }

    fun dragCancelled(durationScale: Float) {
        if (!dragging) return
        dragging = false
        pendingCommit = null
        if (dragProgress <= .001f) target = null
        animateTo(0f, durationScale)
    }

    private fun animateTo(next: Float, scale: Float) {
        progressTarget = next
        progress.target(next, scale)
    }

    /** Returns the source onSelectBuffer commit to deliver once, after rendering this final frame. */
    fun frame(timeNanos: Long, durationScale: Float): BufferSlot? {
        progress.frame(timeNanos, durationScale)
        if (progress.running || dragging) return null
        val destination = target ?: return null
        if (progress.value >= .999f) {
            val commit = pendingCommit == destination
            displayedBuffer = destination
            pendingCommit = null
            target = null
            progressTarget = 0f
            dragProgress = 0f
            progress.snap(0f)
            return if (commit) destination else null
        }
        if (progress.value <= .001f && progressTarget <= 0f) {
            pendingCommit = null
            target = null
            dragProgress = 0f
        }
        return null
    }
}
