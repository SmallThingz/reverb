/*
 * Spring policy from foundation1.11.2 LazyItemScope/LazyLayoutItemAnimation, Apache-2.0.
 * Native RecyclerView phase/anchor behavior still requires the released/native motion fixture.
 */
package app.smallthingz.reverb.nativeui

import android.animation.ValueAnimator
import android.view.Choreographer
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import kotlin.math.roundToInt

/** animateItem's MediumLow spring fades/placement; no platform default timing or change crossfade. */
internal class ReverbIncidentItemAnimator : SimpleItemAnimator(), Choreographer.FrameCallback {
    private val active = LinkedHashMap<RecyclerView.ViewHolder, Motion>()
    private var posted = false
    private enum class Kind { ADD, REMOVE }
    private class Motion(val holder: RecyclerView.ViewHolder) {
        var alpha: FloatMotion? = null
        var kind: Kind? = null
        var alphaStarted = false
        var offset: OffsetMotion? = null
        var moveStarted = false
    }

    init { supportsChangeAnimations = false }

    override fun canReuseUpdatedViewHolder(viewHolder: RecyclerView.ViewHolder): Boolean = true

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        if (holder.itemView !is ReverbIncidentCardView) { dispatchAddFinished(holder); return false }
        val motion = active.getOrPut(holder) { Motion(holder) }
        val wasRemoving = motion.kind == Kind.REMOVE
        finishAlphaCallback(motion)
        val velocity = if (wasRemoving) motion.alpha?.velocity ?: 0f else 0f
        val initial = if (wasRemoving) holder.itemView.alpha else 0f
        holder.itemView.alpha = initial
        motion.alpha = FloatMotion(initial, 1f, velocity)
        motion.kind = Kind.ADD
        motion.alphaStarted = false
        return true
    }

    override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
        if (holder.itemView !is ReverbIncidentCardView) { dispatchRemoveFinished(holder); return false }
        val motion = active.getOrPut(holder) { Motion(holder) }
        val velocity = motion.alpha?.velocity ?: 0f
        finishAlphaCallback(motion)
        motion.alpha = FloatMotion(holder.itemView.alpha, 0f, velocity)
        motion.kind = Kind.REMOVE
        motion.alphaStarted = false
        return true
    }

    override fun animateMove(holder: RecyclerView.ViewHolder, fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
        if (holder.itemView !is ReverbIncidentCardView) { dispatchMoveFinished(holder); return false }
        val view = holder.itemView
        val initial = IntOffset((view.translationX + fromX - toX).roundToInt(), (view.translationY + fromY - toY).roundToInt())
        val existing = active[holder]
        if (existing?.offset != null) {
            dispatchMoveFinished(holder)
            existing.moveStarted = false
        }
        if (initial == IntOffset.Zero) {
            existing?.offset = null
            view.translationX = 0f; view.translationY = 0f
            dispatchMoveFinished(holder)
            pruneFinished()
            return false
        }
        val motion = existing ?: Motion(holder).also { active[holder] = it }
        val velocity = motion.offset?.velocity ?: AnimationVector2D(0f, 0f)
        motion.offset = OffsetMotion(initial, velocity)
        motion.moveStarted = false
        view.translationX = initial.x.toFloat(); view.translationY = initial.y.toFloat()
        return true
    }

    override fun animateChange(oldHolder: RecyclerView.ViewHolder, newHolder: RecyclerView.ViewHolder?,
        fromLeft: Int, fromTop: Int, toLeft: Int, toTop: Int): Boolean {
        if (oldHolder === newHolder) return animateMove(oldHolder, fromLeft, fromTop, toLeft, toTop)
        // Stable-key content enrichments reuse their holder. A true type/key replacement is not a
        // source content crossfade, so finish these callbacks without inventing that animation.
        dispatchChangeFinished(oldHolder, true)
        newHolder?.let { dispatchChangeFinished(it, false) }
        return false
    }

    override fun runPendingAnimations() {
        active.values.forEach { motion ->
            if (motion.kind != null && !motion.alphaStarted) {
                motion.alphaStarted = true
                when (motion.kind) {
                    Kind.ADD -> dispatchAddStarting(motion.holder)
                    Kind.REMOVE -> dispatchRemoveStarting(motion.holder)
                    null -> Unit
                }
            }
            if (motion.offset != null && !motion.moveStarted) {
                motion.moveStarted = true
                dispatchMoveStarting(motion.holder)
            }
        }
        schedule()
    }

    private fun schedule() {
        if (!posted && active.isNotEmpty()) {
            posted = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        val scale = ValueAnimator.getDurationScale()
        // Framework completion callbacks may reenter the animator; iterate an identity snapshot.
        active.values.toList().forEach { motion ->
            if (active[motion.holder] !== motion) return@forEach
            val view = motion.holder.itemView
            val alpha = motion.alpha
            if (alpha != null && motion.alphaStarted) {
                view.alpha = alpha.frame(frameTimeNanos, scale).coerceIn(0f, 1f)
                if (alpha.finished) {
                    motion.alpha = null
                    if (motion.kind == Kind.REMOVE) {
                        // Retire every old-holder channel before RecyclerView can recycle it.
                        active.remove(motion.holder)
                        val wasMoving = motion.offset != null
                        motion.offset = null
                        motion.moveStarted = false
                        view.alpha = 1f
                        view.translationX = 0f
                        view.translationY = 0f
                        if (wasMoving) dispatchMoveFinished(motion.holder)
                        finishAlphaCallback(motion)
                        return@forEach
                    }
                    finishAlphaCallback(motion)
                }
            }
            val offset = motion.offset
            if (offset != null && motion.moveStarted) {
                val position = offset.frame(frameTimeNanos, scale)
                view.translationX = position.x.toFloat(); view.translationY = position.y.toFloat()
                if (offset.finished) {
                    motion.offset = null
                    motion.moveStarted = false
                    dispatchMoveFinished(motion.holder)
                }
            }
        }
        pruneFinished()
        schedule()
    }

    private fun finishAlphaCallback(motion: Motion) {
        val kind = motion.kind ?: return
        motion.kind = null
        motion.alphaStarted = false
        when (kind) {
            Kind.ADD -> dispatchAddFinished(motion.holder)
            Kind.REMOVE -> dispatchRemoveFinished(motion.holder)
        }
    }

    private fun pruneFinished() {
        active.entries.removeAll { it.value.alpha == null && it.value.offset == null }
        if (active.isEmpty()) {
            if (posted) Choreographer.getInstance().removeFrameCallback(this)
            posted = false
            dispatchAnimationsFinished()
        }
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        val motion = active.remove(item) ?: return
        item.itemView.alpha = 1f
        item.itemView.translationX = 0f; item.itemView.translationY = 0f
        finishAlphaCallback(motion)
        if (motion.offset != null) {
            motion.offset = null
            dispatchMoveFinished(item)
        }
        pruneFinished()
    }

    override fun endAnimations() {
        active.keys.toList().forEach(::endAnimation)
        if (posted) Choreographer.getInstance().removeFrameCallback(this)
        posted = false
    }

    override fun isRunning(): Boolean = active.isNotEmpty()

    private class FloatMotion(initial: Float, target: Float, initialVelocity: Float) {
        private val animation = TargetBasedAnimation(
            spring<Float>(stiffness = Spring.StiffnessMediumLow), Float.VectorConverter,
            initial, target, AnimationVector1D(initialVelocity))
        private var start = Long.MIN_VALUE
        var velocity = initialVelocity
            private set
        var finished = false
            private set
        fun frame(now: Long, scale: Float): Float {
            if (start == Long.MIN_VALUE) start = now
            val play = if (scale == 0f) animation.durationNanos else ((now - start) / scale).toLong()
            velocity = animation.getVelocityVectorFromNanos(play).value
            finished = animation.isFinishedFromNanos(play)
            return animation.getValueFromNanos(play)
        }
    }

    private class OffsetMotion(initial: IntOffset, initialVelocity: AnimationVector2D) {
        private val converter = TwoWayConverter<IntOffset, AnimationVector2D>(
            { AnimationVector2D(it.x.toFloat(), it.y.toFloat()) },
            { IntOffset(it.v1.roundToInt(), it.v2.roundToInt()) })
        private val animation = TargetBasedAnimation(
            spring<IntOffset>(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1)),
            converter, initial, IntOffset.Zero, AnimationVector2D(initialVelocity.v1, initialVelocity.v2))
        private var start = Long.MIN_VALUE
        var velocity = AnimationVector2D(initialVelocity.v1, initialVelocity.v2)
            private set
        var finished = false
            private set
        fun frame(now: Long, scale: Float): IntOffset {
            if (start == Long.MIN_VALUE) start = now
            val play = if (scale == 0f) animation.durationNanos else ((now - start) / scale).toLong()
            velocity = animation.getVelocityVectorFromNanos(play)
            finished = animation.isFinishedFromNanos(play)
            return animation.getValueFromNanos(play)
        }
    }
}
