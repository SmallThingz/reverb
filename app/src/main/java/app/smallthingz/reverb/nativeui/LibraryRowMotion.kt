/*
 * Source-derived from RecordingCard.kt at 3e16f8d and AndroidX Compose 1.11.2.
 * See SOURCE.md. This owns math/state only; no Composition or rendering tree.
 */
package app.smallthingz.reverb.nativeui

import smallthingz.ui.animation.VectorConverter
import smallthingz.ui.animation.core.AnimationVector1D
import smallthingz.ui.animation.core.AnimationVector2D
import smallthingz.ui.animation.core.AnimationVector4D
import smallthingz.ui.animation.core.FastOutSlowInEasing
import smallthingz.ui.animation.core.FiniteAnimationSpec
import smallthingz.ui.animation.core.Spring
import smallthingz.ui.animation.core.VectorConverter
import smallthingz.ui.animation.core.spring
import smallthingz.ui.animation.core.tween
import smallthingz.ui.graphics.Color
import smallthingz.ui.graphics.toArgb
import smallthingz.ui.unit.IntSize
import kotlin.math.roundToLong

internal typealias LibraryTweenValue<T, V> = smallthingz.ui.animation.AnimatedValue<T, V>
internal typealias LibraryTransitionValue<T, V> = smallthingz.ui.animation.TransitionValue<T, V>

// Reverb owns the selected-row and content-size durations; the samplers are generic.
internal fun libraryColorTween(initial: Color) = LibraryTweenValue(initial,
    (Color.VectorConverter)(initial.colorSpace),
    tween<Color>(180, easing = FastOutSlowInEasing),
    { AnimationVector4D(0f, 0f, 0f, 0f) })
internal fun libraryAlphaTween(initial: Float) = LibraryTweenValue(initial, Float.VectorConverter,
    tween<Float>(180, easing = FastOutSlowInEasing), { AnimationVector1D(0f) })
internal fun librarySizeTween(initial: IntSize) = LibraryTweenValue(initial, IntSize.VectorConverter,
    tween<IntSize>(320, easing = FastOutSlowInEasing), { AnimationVector2D(0f, 0f) })

/** One AnimatedVisibility lifetime. Full-size measurement precedes the deferred size animation. */
internal class LibraryExpansionMotion(initiallyVisible: Boolean) {
    private enum class Phase { PreEnter, Visible, PostExit }
    private var current = if (initiallyVisible) Phase.Visible else Phase.PreEnter
    private var target = current
    private var segmentFrom = current
    private var fullSize: IntSize? = null
    private var size: LibraryTransitionValue<IntSize, AnimationVector2D>? = null
    private var fade: LibraryTransitionValue<Float, AnimationVector1D>? = null
    private var startFrame: Long? = null
    private var playTime = 0L
    private var stateUpdate = false
    var exitCompleted = false
        private set
    val visibleTarget get() = target == Phase.Visible
    val running get() = size?.running == true || fade?.running == true
    val alpha get() = fade?.value ?: if (visibleTarget) 1f else 0f
    val measuredSize get() = size?.value ?: fullSize ?: IntSize.Zero

    fun setVisible(visible: Boolean) {
        val next = if (visible) Phase.Visible else if (current == Phase.PreEnter) Phase.PreEnter else Phase.PostExit
        if (target == next) return
        val oldTarget = target
        segmentFrom = oldTarget
        if (current != oldTarget) current = oldTarget
        // targetEnterExit observes the previous Visible waypoint even when enter was interrupted.
        target = if (!visible && current == Phase.Visible) Phase.PostExit else next
        exitCompleted = false
        stateUpdate = true
        fullSize?.let(::measured)
    }

    fun measured(full: IntSize) {
        fullSize = full
        if (size == null) {
            val initialSize = if (current == Phase.Visible) full else IntSize(full.width, 0)
            size = LibraryTransitionValue(initialSize, IntSize.VectorConverter,
                spring(visibilityThreshold = IntSize(1, 1)), { AnimationVector2D(0f, 0f) })
            fade = LibraryTransitionValue(if (current == Phase.Visible) 1f else 0f,
                Float.VectorConverter, spring(visibilityThreshold = .01f), { AnimationVector1D(0f) })
        }
        val height = checkNotNull(size)
        val opacity = checkNotNull(fade)
        if (current == target && target == Phase.Visible && !running && !stateUpdate) {
            // trackActiveEnter/Exit become None in a settled Visible state: size changes immediately.
            height.snap(full)
            opacity.snap(1f)
            return
        }
        val entering = segmentFrom == Phase.PreEnter && target == Phase.Visible
        val exiting = segmentFrom == Phase.Visible && target == Phase.PostExit
        val sizeSpec: FiniteAnimationSpec<IntSize> = when {
            entering -> tween(320, easing = FastOutSlowInEasing)
            exiting -> tween(260, easing = FastOutSlowInEasing)
            else -> spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize(1, 1))
        }
        val alphaSpec: FiniteAnimationSpec<Float> = when {
            entering -> tween(220, delayMillis = 70)
            exiting -> tween(140)
            else -> spring(stiffness = Spring.StiffnessMediumLow)
        }
        height.target(if (visibleTarget) full else IntSize(full.width, 0), sizeSpec, playTime, stateUpdate)
        opacity.target(if (visibleTarget) 1f else 0f, alphaSpec, playTime, stateUpdate)
        stateUpdate = false
    }

    fun frame(time: Long, scale: Float) {
        if (!running) return
        require(scale >= 0f) { "negative scale factor" }
        val start = startFrame ?: time.also { startFrame = it }
        playTime = if (scale == 0f) time - start else ((time - start) / scale.toDouble()).roundToLong()
        size?.frame(playTime, scale == 0f)
        fade?.frame(playTime, scale == 0f)
        if (!running) {
            current = target
            startFrame = null
            playTime = 0L
            if (target == Phase.PostExit) exitCompleted = true
        }
    }
}

/** Stores original Color values between frames; ARGB is only the final native paint boundary. */
internal class LibrarySelectionMotion(initial: LibraryRecordingRowFrame) {
    private val background = libraryColorTween(Color(initial.background))
    private val border = libraryColorTween(Color(initial.border))
    private val disc = libraryColorTween(Color(initial.disc))
    private val icon = libraryColorTween(Color(initial.icon))
    private val alpha = libraryAlphaTween(initial.contentAlpha)
    val running get() = background.running || border.running || disc.running || icon.running || alpha.running
    val value get() = LibraryRecordingRowFrame(background.value.toArgb(), border.value.toArgb(),
        disc.value.toArgb(), icon.value.toArgb(), alpha.value)
    fun target(next: LibraryRecordingRowFrame, scale: Float) {
        background.target(Color(next.background), scale); border.target(Color(next.border), scale)
        disc.target(Color(next.disc), scale); icon.target(Color(next.icon), scale)
        alpha.target(next.contentAlpha, scale)
    }
    fun frame(time: Long, scale: Float) {
        background.frame(time, scale); border.frame(time, scale); disc.frame(time, scale)
        icon.frame(time, scale); alpha.frame(time, scale)
    }
}
