package app.smallthingz.reverb

import android.view.MotionEvent
import android.view.View
import app.smallthingz.reverb.nativeui.ReverbClickController

/** Requires the adjacent reviewed click-consumption-hook.patch; stream handled != consumed. */
internal interface NativeClickPhaseEndpoint {
    fun pointerBounds(width: Int, height: Int)
    fun touch(event: MotionEvent, consumedByOther: Boolean, consumeChanges: (allPointers: Boolean) -> Unit,
              interestedParent: Boolean, classification: Int): Boolean
    fun afterParentDispatch(consumedByOther: Boolean)
    fun cancel()
}

/** Existing concrete row/button exposes its already-owned controller; no input registry. */
internal interface NativeClickPhaseHost {
    val nativeClickEndpoint: NativeClickPhaseEndpoint
}

internal class ReverbClickPhaseEndpoint(private val input: ReverbClickController) : NativeClickPhaseEndpoint {
    override fun pointerBounds(width: Int, height: Int) = input.pointerBounds(width, height)
    override fun touch(
        event: MotionEvent,
        consumedByOther: Boolean,
        consumeChanges: (allPointers: Boolean) -> Unit,
        interestedParent: Boolean,
        classification: Int,
    ): Boolean = input.touch(event, consumedByOther, consumeChanges, interestedParent, classification)

    override fun afterParentDispatch(consumedByOther: Boolean) = input.afterParentDispatch(consumedByOther)
    override fun cancel() = input.cancel()
}

/**
 * Direct adapter on the existing row View/controller. No duplicate click state or synthesized click.
 * The only retained change identity is the original DOWN exemption in pinned Clickable.Final.
 */
internal class NativeClickPhaseNode(
    override val view: View,
    private val input: NativeClickPhaseEndpoint,
    private val parentInterested: (NativeDragPointerChange) -> Boolean,
) : NativeViewPhasePointerNode {
    private var rootEvent: MotionEvent? = null
    private var transform: NativePointerCoordinates? = null
    private var pointerMask = 0
    private var localEvent: MotionEvent? = null
    private val consumption = NativeClickPhaseConsumption()

    override fun bindMotion(event: MotionEvent?, coordinates: NativePointerCoordinates?, androidPointerMask: Int) {
        localEvent?.recycle()
        localEvent = null
        rootEvent = event
        transform = coordinates
        pointerMask = androidPointerMask
    }

    override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
        input.pointerBounds(view.width, view.height)
        when (pass) {
            NativePointerPass.INITIAL -> Unit
            NativePointerPass.MAIN -> {
                val motion = localEvent ?: requireNotNull(rootEvent).nativePointerSubset(pointerMask).also {
                    it.transform(requireNotNull(transform).rootToLocal)
                    localEvent = it
                }
                consumption.main(event) { consumed, consume ->
                    input.touch(motion, consumed, consume, parentInterested(event.changes.first()),
                        requireNotNull(rootEvent).nativeOriginalClassification())
                }
                // Deliberately ignore touch()'s handled Boolean: a canceled row can still own
                // Android's stream while contributing no consumption to this event.
            }
            NativePointerPass.FINAL -> {
                consumption.final(event, input::afterParentDispatch)
            }
        }
    }

    override fun cancel() {
        input.cancel()
        consumption.cancel()
    }
}

internal class NativeDragPhaseNode(
    override val view: View,
    private val router: NativeDragPointerRouter,
) : NativeViewPhasePointerNode {
    override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) = router.dispatch(event, pass)
    override fun cancel() = router.reset()
}

internal class NativePagerDirectionPhaseNode(
    override val view: View,
    private val observer: NativePagerDirectionObserver,
) : NativeViewPhasePointerNode {
    override fun dispatch(event: NativeDragPointerEvent, pass: NativePointerPass) {
        if (pass == NativePointerPass.INITIAL) observer.initial(event)
    }
    override fun cancel() = observer.cancel()
}
